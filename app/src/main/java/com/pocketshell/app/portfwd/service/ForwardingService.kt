package com.pocketshell.app.portfwd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.MainActivity
import com.pocketshell.app.R
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.portfwd.DefaultDispatcher
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.systemsurfaces.ForwardingTileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process alive while at least
 * one SSH port-forward tunnel is active.
 *
 * Issue #203 expanded scope, ported (and adapted to sshj +
 * PocketShell architecture) from
 * `ssh-auto-forward-android/.../service/ForwardingService.kt`.
 *
 * D21 carve-out (see `docs/decisions.md`): PocketShell otherwise runs
 * strictly foreground-only, but tunnels are bound to the device-side
 * SSH transport and die the moment the app process is backgrounded.
 * Without a foreground service the user loses the entire auto-forward
 * feature on backgrounding — incompatible with the "supervise long
 * running agents from your phone" core JTBD. The service exists ONLY
 * while ≥1 host has auto-forward enabled; it tears itself down the
 * moment the last tunnel goes away.
 *
 * Responsibilities:
 *  - Promote the process to foreground state with a persistent,
 *    user-visible notification (so the OS doesn't kill us)
 *  - Mirror connection / tunnel state from [ForwardingController] into
 *    the notification text so the user sees host name + tunnel count
 *  - Stop itself when [ForwardingController.flowOfActiveHostCount]
 *    drops to zero (all hosts disabled their auto-forward toggle)
 *
 * ## Network-change handling is NOT the service's job (issue #980)
 *
 * The service deliberately does NOT register its own network callback.
 * Before #980 it registered a raw `ConnectivityManager.registerDefaultNetworkCallback`
 * that called [ForwardingController.forceReconnectNow] on ANY raw
 * `onLost`→`onAvailable` pair — including a same-AP band-steer / mesh-node
 * roam / momentary RF re-association the link actually survives. That
 * force-closed a transport whose keepalive window said it was still alive,
 * a self-inflicted drop on stable wifi (the #974 signature) — and it
 * bypassed the same-SSID-reassoc hardening
 * ([com.pocketshell.app.connectivity.TerminalNetworkObserver]'s
 * `hasSameNetworkIdentityAs`, #875) that the rest of the app uses.
 *
 * Network-driven reconnect for the forwarding feature is owned SOLELY by
 * [ForwardingController], which subscribes to the hardened, debounced
 * `TerminalNetworkObserver.changes` signal and forces a rebuild only on a
 * REAL validated default-network handoff. Hard-cut (D22): the service's
 * private raw callback is deleted, not gated behind a flag — there is one
 * hardened network path, not two competing ones.
 */
@AndroidEntryPoint
class ForwardingService : Service() {

    @Inject
    lateinit var controller: ForwardingController

    /**
     * Dispatcher backing the notification-observe coroutine ([launchObserver]).
     *
     * Injected (not a bare `Dispatchers.Default`) so a test can confine the
     * observe loop to its own [kotlinx.coroutines.test.TestDispatcher] — one it
     * cancels in `@After` — instead of leaking a real `Dispatchers.Default`
     * background coroutine that outlives the test and dereferences the
     * already-torn-down Robolectric Application in [updateNotification] (issue
     * #994: the intermittent full-suite `UncaughtExceptionsBeforeTest` NPE).
     *
     * Hilt field injection runs for this `@AndroidEntryPoint` service before
     * `onCreate`, so by the time [onStartCommand] builds [scope] the injected
     * dispatcher is in place. The `Dispatchers.Default` default keeps a
     * non-Hilt-graph instantiation (a plain `new ForwardingService()` in a unit
     * test that does not exercise the observe loop) working without a crash.
     */
    @Inject
    @DefaultDispatcher
    @JvmField
    @androidx.annotation.VisibleForTesting
    var observeDispatcher: CoroutineDispatcher = Dispatchers.Default

    // Built lazily off [observeDispatcher] on first use ([armObserver]) so the
    // injected dispatcher (set by Hilt field injection before onStartCommand, or
    // by a test before it drives onStartCommand) backs the scope. `lazy` rather
    // than building it in onCreate because the generated `Hilt_ForwardingService`
    // onCreate requires a @HiltAndroidApp Application — a plain Robolectric unit
    // test drives onStartCommand directly without onCreate, so scope creation must
    // not depend on onCreate having run. Cancelled in [onDestroy].
    private val scopeDelegate = lazy {
        // SupervisorJob so a failure in the observe collector does not cascade.
        CoroutineScope(SupervisorJob() + observeDispatcher)
    }
    private val scope: CoroutineScope by scopeDelegate

    /**
     * Guards [observeJob] + [observeGeneration] as ONE unit. They are written
     * from the service thread (`onStartCommand` / `onDestroy`) and from the
     * observe dispatcher (a zero snapshot tearing the service down), so the
     * pair must move atomically — issue #2006 is exactly what happens when it
     * does not (see [armObserver]).
     */
    private val observerLock = Any()

    @Volatile
    private var observeJob: Job? = null

    /**
     * Notification generation [observeJob] was launched for, or
     * [NO_GENERATION] when no observer is armed. The observer is only reusable
     * for the generation it was bound to (issue #2006).
     */
    @Volatile
    private var observeGeneration = NO_GENERATION

    @Volatile
    private var hasStartedForeground = false
    private var notificationGeneration = 0L

    /**
     * Test-only pause point immediately before a computed positive snapshot
     * enters the serialized mutation authority. It makes the #1933 late-write
     * race deterministic without replacing NotificationManager or the service
     * lifecycle path with a proxy.
     */
    @androidx.annotation.VisibleForTesting
    internal var beforeNotificationPublishForTest: (() -> Unit)? = null

    private val notificationMutations = ForwardingNotificationMutationAuthority(
        record = ::recordNotificationOperation,
    )

    /** Issue #994 test seam: the observe coroutine, so a test can assert it is
     * active while observing and cancelled by `onDestroy()`. */
    @get:androidx.annotation.VisibleForTesting
    internal val observeJobForTest: Job?
        get() = observeJob

    /** Issue #994 test seam: the dispatcher the observe collector resumed on,
     * recorded in [launchObserver]. Lets a test assert the observe loop is
     * confined to the injected dispatcher, not a leaked Dispatchers.Default. */
    @androidx.annotation.VisibleForTesting
    internal var lastObserveDispatcherForTest: CoroutineDispatcher? = null
        private set

    companion object {
        private const val TAG = "PsForwardingService"

        // Issue #487 (reopened 2026-06-10): the maintainer swiped the tray
        // "clear all" and the forwarding notification went away with the
        // ordinary push notifications. He wants the Recorder/Spotify "ongoing
        // status" feel: a quiet, persistent, sweep-resistant status — NOT an
        // alert that buzzes or pops a heads-up every time a forward starts.
        //
        // Sweep-resistance is FLAG_NO_CLEAR + FLAG_ONGOING_EVENT (set via
        // setOngoing(true)); that alone blocks the "clear all" sweep at ANY
        // channel importance. The QUIET feel is no sound + no vibration +
        // setSilent(true) + setOnlyAlertOnce(true) — NOT a low importance.
        //
        // Issue #752 (REOPENED 2026-06-24, v0.4.14): the maintainer has 18 ports
        // forwarding but there is NO persistent ⇄ status-bar icon near the clock;
        // Android has filed the ongoing notification under the "Silent" group.
        // ROOT CAUSE of the regression: the channel was IMPORTANCE_LOW. On a real
        // Pixel a below-DEFAULT channel is treated as "Silent" and its persistent
        // status-bar icon near the clock is suppressed (only the shade row shows).
        // The earlier #487/#521 emulator validation passed because the swiftshader
        // AVD status bar renders the LOW icon, but the maintainer's device does
        // not — the happy-fixture-masks-reality gap (D33/G10). The fix is to raise
        // the channel to IMPORTANCE_DEFAULT so it leaves the "Silent" group and the
        // persistent ⇄ icon shows near the clock, while keeping it QUIET (null
        // sound, no vibration, setSilent(true)) so it never buzzes or pops a
        // heads-up — visible-but-silent, the Recorder/Spotify contract. DEFAULT
        // (not HIGH) is deliberate: HIGH would heads-up; DEFAULT only surfaces the
        // status-bar icon, and setSilent(true) suppresses the DEFAULT sound.
        //
        // Notification-channel importance AND showBadge are IMMUTABLE after first
        // creation, so changing either requires a NEW channel id —
        // `createNotificationChannel()` is a no-op for an existing channel. `_v5`
        // already shipped at IMPORTANCE_LOW, so raising the importance in place is
        // silently ignored on every installed app (the maintainer's included) and
        // the status-bar icon would still never surface. Hard-cut per D22: we
        // create `_v6` at IMPORTANCE_DEFAULT (silent) with `setShowBadge(true)` and
        // delete the stale `_v5`/`_v4`/`_v3`/`_v2`/legacy channels so no install
        // keeps the silent-group LOW presentation, the buzzing HIGH one, the old
        // swipe-away one, or the no-badge `_v4` channel.
        private const val CHANNEL_ID = "pocketshell_forwarding_status_v6"
        private val LEGACY_CHANNEL_IDS = listOf(
            "pocketshell_forwarding_status_v5",
            "pocketshell_forwarding_status_v4",
            "pocketshell_forwarding_status_v3",
            "pocketshell_forwarding_status_v2",
            "pocketshell_forwarding_status",
            "pocketshell_forwarding",
        )
        private const val NOTIFICATION_ID = 0x70_46_53_56 // "pFSV" — unique within app

        /** Sentinel for "no notification observer is armed" (issue #2006). */
        private const val NO_GENERATION = 0L

        /**
         * Android drops notification updates when one package exceeds its
         * enqueue rate. One controller mutation advances several StateFlows,
         * so the observer must collapse that synchronous burst before writing
         * to NotificationManager (issue #2047). Zero remains immediate so the
         * foreground service never outlives its last forward.
         */
        private const val POSITIVE_NOTIFICATION_SETTLE_MS = 300L

        const val ACTION_START = "com.pocketshell.app.portfwd.action.START_FORWARDING"
        const val ACTION_STOP = "com.pocketshell.app.portfwd.action.STOP_FORWARDING"

        /**
         * Start the service in foreground mode. Idempotent — Android
         * will route the intent to the existing [ForwardingService]
         * instance if one is already running. Callers (typically
         * [com.pocketshell.app.portfwd.PortForwardPanelViewModel])
         * invoke this when the first tunnel goes active.
         *
         * Uses [ContextCompat.startForegroundService] so it works
         * across SDK levels — the platform requires `startForeground()`
         * to be called within ~5 s of starting, which we do in
         * [onStartCommand].
         */
        fun start(context: Context) {
            val intent = Intent(context, ForwardingService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "foreground service start was rejected", it)
            }
        }

        /**
         * Request that the service stop. Routed through `startService`
         * (not `stopService`) so we can use the same `onStartCommand`
         * dispatch path; the service then calls `stopForeground` +
         * `stopSelf` from there.
         */
        fun stop(context: Context) {
            val intent = Intent(context, ForwardingService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.w(TAG, "foreground service stop request was rejected", it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stopAllForwarding(requestServiceStop = false)
                stopForwarding(reason = "action_stop")
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or null (restart-after-kill). Even on a
                // null intent — Android can restart a STICKY service
                // without an intent — we promote to foreground because
                // a STARTED foreground service that doesn't call
                // startForeground() fast enough crashes the process
                // with ForegroundServiceDidNotStartInTimeException.
                //
                // Issue #1232: mirror SessionConnectionService — a start that
                // lands on a background-restricted edge throws
                // ForegroundServiceStartNotAllowedException (Android 12+; 14+
                // adds specialUse throw conditions). promoteToForegroundIfNeeded
                // now contains that throw and returns false rather than
                // promote-or-die; on failure we stop the service cleanly instead
                // of crashing the process.
                var promoted = false
                notificationGeneration = notificationMutations.open(liveControllerCount()) {
                    promoted = promoteToForegroundIfNeeded(initialNotification())
                }
                if (!promoted) {
                    stopForwarding(reason = "promotion_rejected")
                    return START_NOT_STICKY
                }
                armObserver(notificationGeneration)
            }
        }
        // START_STICKY: if Android kills us under memory pressure, the
        // system tries to recreate us — useful if the user has tunnels
        // open and the OS reclaims memory. We re-render the
        // notification on recreate from the current
        // [ForwardingController] state.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clearObserver(expectedGeneration = null)
        notificationMutations.close(
            controllerCount = ::liveControllerCount,
            reason = "service_destroy",
        ) { generation ->
            recordNotificationOperation(
                ForwardingNotificationOperation(
                    kind = ForwardingNotificationOperation.Kind.REMOVE,
                    generation = generation,
                    controllerCount = liveControllerCount(),
                    reason = "service_destroy",
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasStartedForeground = false
        }
        // Cancel the observe-loop scope so no coroutine outlives the service
        // (issue #994). Guarded so we don't force-create the lazy scope just to
        // cancel it on a service that never started observing.
        if (scopeDelegate.isInitialized()) scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Issue #446 (maintainer ruling): active tunnels stay alive on
        // swipe-away. The ongoing notification is an "always-on" control —
        // the user keeps forwarding running (and the one-tap Stop / body-
        // tap deep-link reachable) until they explicitly stop it, rather
        // than swiping the task away tearing everything down. We therefore
        // deliberately do NOT call stopForwarding() here; the service is
        // still torn down the moment the active-host count drops to zero
        // (Stop action or the user disabling auto-forward in the panel).
        //
        // This is still inside the D21 carve-out: the foreground service
        // only exists while ≥1 host is actively auto-forwarding, with a
        // visible persistent notification.
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Bind the notification observer to [generation], replacing any observer
     * that belongs to a different one.
     *
     * ## Issue #2006 — why the generation, not `isActive`, is the authority
     *
     * This used to be `if (observeJob == null || observeJob?.isActive != true)
     * startObserving(...)`, i.e. "reuse whatever observer is still running".
     * That is wrong, because the observer is fenced by the notification
     * generation it was LAUNCHED with: every
     * [ForwardingNotificationMutationAuthority.publish] it makes carries that
     * generation, and once the generation is invalidated every one of its
     * publishes is dropped as stale.
     *
     * The observer's teardown ([stopForwarding]) runs on the observe dispatcher
     * while `onStartCommand` runs on the service thread, so an
     * `ACTION_START` landing between the observer clearing itself and the
     * generation actually closing armed a NEW observer on the still-open
     * generation, which the very next instruction then invalidated. That
     * observer stayed `isActive`, so the next `ACTION_START` — the one that
     * re-promotes the service and posts the bootstrap "Connecting…" body —
     * REUSED it. Every subsequent snapshot was dropped as stale and nothing
     * re-armed, so the ongoing notification was permanently stranded on
     * `Running in the background · Connecting…` while real tunnels were up.
     *
     * Comparing the generation instead makes the stale observer unusable: it is
     * cancelled and replaced, so the fresh generation always has an observer
     * whose publishes are accepted.
     *
     * This is one half of the fence; the other half is [clearObserver], which
     * must be scoped to the generation being torn down for the same reason (see
     * its KDoc).
     */
    private fun armObserver(generation: Long) {
        synchronized(observerLock) {
            if (observeGeneration == generation && observeJob?.isActive == true) return
            observeJob?.cancel()
            observeGeneration = generation
            observeJob = launchObserver(generation)
        }
    }

    /**
     * Cancel and forget the observer bound to [expectedGeneration].
     *
     * ## Issue #2006 — the clear is generation-scoped too
     *
     * This used to cancel "whatever observer is currently armed", and it runs
     * AFTER [ForwardingNotificationMutationAuthority.close] has released its
     * monitor. An `ACTION_START` landing in that gap opens the NEXT generation,
     * posts the bootstrap `Connecting…` body and arms an observer for it — and
     * the old teardown's clear then cancelled that brand-new observer, leaving
     * the fresh generation with no observer at all and the notification frozen
     * on `Connecting…` forever. Same class of defect as [armObserver]: the
     * generation is the authority for publishing and for closing, so it must be
     * the authority for clearing as well.
     *
     * @param expectedGeneration the generation whose teardown is doing the
     *   clearing; the clear is a no-op if a newer generation has since armed its
     *   own observer. `null` means unconditional and is used ONLY by
     *   [onDestroy], where the whole service (and its scope) is going away.
     */
    private fun clearObserver(expectedGeneration: Long?) {
        synchronized(observerLock) {
            if (expectedGeneration != null && observeGeneration != expectedGeneration) return
            observeJob?.cancel()
            observeJob = null
            observeGeneration = NO_GENERATION
        }
    }

    @OptIn(FlowPreview::class)
    private fun launchObserver(generation: Long): Job {
        return scope.launch {
            // Issue #994: record the dispatcher the collector actually resumed
            // on so a JVM test can assert the observe loop is confined to the
            // INJECTED dispatcher (not a free-running Dispatchers.Default thread
            // that outlives the test). No production effect — read only by tests.
            lastObserveDispatcherForTest =
                coroutineContext[kotlinx.coroutines.CoroutineDispatcher]
            // Combine active-host count + tunnel count so a single
            // collector renders both into the notification. Dropping
            // duplicates means we don't churn the notification on
            // every byte-counter update — only when the topology or
            // host name changes.
            combine(
                controller.flowOfActiveHostCount(),
                controller.flowOfTotalTunnelCount(),
                controller.flowOfPrimaryHostName(),
                controller.flowOfRestoringHostCount(),
                controller.flowOfHostSnapshots(),
            ) { activeHosts, tunnels, primaryHost, restoringHosts, snapshots ->
                NotificationSnapshot(
                    activeHosts = activeHosts,
                    tunnels = tunnels,
                    primaryHost = primaryHost,
                    restoringHosts = restoringHosts,
                    activePorts = snapshots.values
                        .asSequence()
                        .filter { it.active }
                        .flatMap { it.activeRemotePorts.asSequence() }
                        .toSortedSet(),
                )
            }
                .distinctUntilChanged()
                // Issue #2047: recomputeSnapshot updates five independent
                // StateFlows synchronously. `combine` exposes intermediate
                // tuples (for example two hosts with the previous tunnel
                // count), and posting every tuple can cross Android's package
                // notification enqueue-rate limit. The platform then drops the
                // trailing authoritative removal, leaving the tray stuck on
                // "2 ports forwarded" even though the controller is back to
                // one. Collapse each positive topology burst to its settled
                // tuple. A zero snapshot is deliberately never delayed: it is
                // the lifecycle signal that removes the foreground service.
                .debounce { snapshot ->
                    if (snapshot.activeHosts == 0) 0L else POSITIVE_NOTIFICATION_SETTLE_MS
                }
                .collect { snapshot ->
                    if (snapshot.activeHosts == 0) {
                        // All hosts disabled — tear down. The
                        // controller is responsible for posting the
                        // initial zero-count snapshot, so this also
                        // covers the edge case where the user toggled
                        // off before the service finished promoting to
                        // foreground.
                        //
                        // Issue #2006: fenced on THIS observer's generation AND
                        // re-validated against the live controller count inside
                        // the close monitor. A leftover observer from an
                        // invalidated generation must not tear down the
                        // generation that superseded it, and a zero snapshot
                        // that a newly started forward has since made false must
                        // not tear down that live forward's notification.
                        stopForwarding(
                            reason = "zero_snapshot",
                            observerGeneration = generation,
                        )
                    } else {
                        updateNotification(
                            generation,
                            snapshot.primaryHost,
                            snapshot.activeHosts,
                            snapshot.tunnels,
                            snapshot.restoringHosts,
                            snapshot.activePorts,
                        )
                    }
                }
        }
    }

    /**
     * Invalidate the current notification generation, remove the foreground
     * notification and stop the service.
     *
     * @param observerGeneration passed ONLY by the notification observer, which
     *   (a) may belong to a generation that has already been superseded and (b)
     *   decided to tear down from a snapshot that a newly started forward may
     *   already have invalidated (issue #2006). Either condition rejects the
     *   whole teardown inside the close monitor: the observer neither removes
     *   the notification nor stops the service, and it does not clear the
     *   observer either — it simply goes back to collecting and publishes the
     *   live state. Lifecycle commands from the service thread pass null and
     *   always tear down.
     */
    private fun stopForwarding(reason: String, observerGeneration: Long? = null) {
        ForwardingNotificationCloseBarrier.await(ForwardingNotificationCloseBarrier.Phase.BEFORE_CLOSE)
        val closedGeneration = notificationMutations.close(
            controllerCount = ::liveControllerCount,
            reason = reason,
            observerGeneration = observerGeneration,
        ) { generation ->
            // STOP_FOREGROUND_REMOVE makes the notification disappear
            // immediately. The constant has been stable since API 24.
            recordNotificationOperation(
                ForwardingNotificationOperation(
                    kind = ForwardingNotificationOperation.Kind.REMOVE,
                    generation = generation,
                    controllerCount = liveControllerCount(),
                    reason = reason,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasStartedForeground = false
            recordNotificationOperation(
                ForwardingNotificationOperation(
                    kind = ForwardingNotificationOperation.Kind.SERVICE_STOP,
                    generation = generation,
                    controllerCount = liveControllerCount(),
                    reason = reason,
                ),
            )
            stopSelf()
        }
        ForwardingNotificationCloseBarrier.await(ForwardingNotificationCloseBarrier.Phase.AFTER_CLOSE)
        if (closedGeneration != null) clearObserver(expectedGeneration = closedGeneration)
    }

    /**
     * Promote the process to foreground state, containing a
     * background-restricted-start failure instead of crashing.
     *
     * Issue #1232: previously this called `startForeground` unguarded, so a
     * start that landed on a background-restricted edge (a network-restore-driven
     * rebuild, or an enable landing exactly on the app→background transition)
     * threw `ForegroundServiceStartNotAllowedException` (Android 12+; Android 14+
     * adds specialUse throw conditions) and crashed the process. The sibling
     * [com.pocketshell.app.sessions.service.SessionConnectionService.promoteToForegroundIfNeeded]
     * already wrapped the identical call precisely because it can start on the
     * background path; this mirrors that guard. On failure the caller stops the
     * service cleanly (promote-or-stop, not promote-or-die).
     *
     * @return true if the service is now in the foreground (either it just
     *   promoted or it was already foreground); false if promotion was rejected.
     */
    private fun promoteToForegroundIfNeeded(notification: Notification): Boolean {
        if (hasStartedForeground) return true
        return runCatching {
            // On Android 14+ (API 34) the service type must be supplied
            // explicitly to `startForeground()`. We declare the type in
            // the manifest as `specialUse` because PocketShell's
            // foreground-service usage is not "data sync" or "media
            // playback" — it's keeping a user-initiated SSH transport
            // alive while the app is backgrounded. `specialUse` is the
            // catch-all category for use cases not covered by the other
            // pre-defined types and requires the `propertyName` attribute
            // in the manifest, which we supply.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasStartedForeground = true
            true
        }.getOrElse {
            Log.w(TAG, "forwarding foreground service promotion failed", it)
            false
        }
    }

    private fun updateNotification(
        generation: Long,
        hostName: String,
        hostCount: Int,
        tunnelCount: Int,
        restoringHostCount: Int = 0,
        activePorts: Set<Int> = emptySet(),
    ) {
        val notification =
            buildNotification(hostName, hostCount, tunnelCount, restoringHostCount, activePorts)
        beforeNotificationPublishForTest?.invoke()
        notificationMutations.publish(
            expectedGeneration = generation,
            controllerCount = hostCount,
        ) {
            if (!hasStartedForeground) {
                promoteToForegroundIfNeeded(notification)
                return@publish
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun recordNotificationOperation(operation: ForwardingNotificationOperation) {
        val message = buildString {
            append("notification-op=").append(operation.kind.name.lowercase())
            append(" generation=").append(operation.generation)
            operation.expectedGeneration?.let { append(" expectedGeneration=").append(it) }
            append(" controllerCount=").append(operation.controllerCount)
            append(" foreground=").append(hasStartedForeground)
            operation.reason?.let { append(" reason=").append(it) }
        }
        Log.i(TAG, message)
        DiagnosticEvents.record(
            "portforward",
            "notification_${operation.kind.name.lowercase()}",
            "generation" to operation.generation,
            "expectedGeneration" to operation.expectedGeneration,
            "controllerCount" to operation.controllerCount,
            "foreground" to hasStartedForeground,
            "reason" to operation.reason,
        )
    }

    /**
     * The LIVE active-host count, read straight off the controller's StateFlow.
     *
     * Issue #2006: this is an authority input, not just a diagnostic — the
     * notification-close monitor re-reads it to decide whether an observer's
     * "no hosts left" conclusion is still true. It deliberately reads the
     * conflated StateFlow rather than `activeHostIdsSnapshot()`, which walks the
     * controller's mutable list off the controller's own monitor.
     */
    private fun liveControllerCount(): Int =
        if (::controller.isInitialized) controller.flowOfActiveHostCount().value else 0

    /**
     * Snapshot of the controller state the notification renders (issue
     * #439 added [restoringHosts]). A named class keeps the `combine` of
     * four flows readable and lets `distinctUntilChanged` collapse on
     * value equality.
     */
    private data class NotificationSnapshot(
        val activeHosts: Int,
        val tunnels: Int,
        val primaryHost: String,
        val restoringHosts: Int,
        val activePorts: Set<Int> = emptySet(),
    )

    private fun initialNotification(): Notification = buildNotification(
        hostName = "",
        hostCount = 0,
        tunnelCount = 0,
        contentTextOverride = "Connecting…",
    )

    /**
     * Build the foreground-service notification.
     *
     * Only depends on the service's [Context] (`this`) — never on the
     * Hilt-injected [controller] — so a JVM Robolectric test can construct the
     * service and call this directly to assert the title / body / icon / ongoing
     * wording (issue #521) without standing up Hilt. Marked
     * [androidx.annotation.VisibleForTesting] for that reason.
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildNotification(
        hostName: String,
        hostCount: Int,
        tunnelCount: Int,
        restoringHostCount: Int = 0,
        activePorts: Set<Int> = emptySet(),
        contentTextOverride: String? = null,
    ): Notification {
        // Issue #446: body-tap deep-links to the port-forward panel entry
        // (the host chooser) rather than just "open the app on whatever
        // was last on top". Reuses the same EXTRA the QS tile sets, so the
        // activity's initialDestinationFromIntent routes to
        // PortForwardChooser.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, ForwardingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Issue #487 / #521: the maintainer wants an always-on "port
        // forwarding is running in the background" status with the Google
        // Recorder ongoing feel — a recognizable status-bar icon plus wording
        // that explicitly says it's running in the background right now.
        //
        // `detail` is the live host + tunnel topology (changes as tunnels come
        // and go). It's reused both in the collapsed body and in the expanded
        // BigText so the user always sees what's forwarded.
        //
        // Issue #752: the maintainer wants the forwarded PORT COUNT conveyed,
        // Google-Recorder style: a status-bar badge number plus explicit
        // "N ports forwarded" wording. Each active tunnel is one forwarded
        // port, so [tunnelCount] is the port count. We lead the detail with
        // "N ports forwarded" (replacing the older "N tunnels" phrasing) so the
        // count is the first thing the user reads, and feed the same count into
        // setNumber() below for the status-bar badge.
        val detail = contentTextOverride ?: buildString {
            if (hostName.isNotEmpty()) {
                append(hostName)
                if (hostCount > 1) append(" + ${hostCount - 1} more")
                append(" · ")
            }
            // Issue #439: while a host's transport is down the supervisor
            // is re-establishing SSH and re-opening the user's desired
            // forwards. Show "Restoring…" instead of "0 ports forwarded"
            // so a transient blip reads as restoring, not removed.
            if (restoringHostCount > 0) {
                append("Restoring…")
            } else {
                append("$tunnelCount port")
                if (tunnelCount != 1) append("s")
                append(" forwarded")
            }
        }

        // Issue #521: the collapsed body leads with the explicit "Running in
        // the background" phrasing (Recorder's "Recording now") and then the
        // live detail, so even the one-line collapsed shade row reads as an
        // active background process the user controls.
        val contentText = "Running in the background · $detail"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Issue #521: title explicitly says it's running (not just
            // "active"), matching Recorder's persistent-status headline.
            .setContentTitle("Port forwarding running")
            .setContentText(contentText)
            // A larger body so the host + tunnel detail is fully readable when
            // the user expands the notification shade, like Recorder's
            // "Recording in progress · 00:42" expanded row.
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            // Issue #521: dedicated monochrome status-bar glyph (the same
            // bidirectional forward-arrow as the QS tile) so the status bar
            // shows an unmistakable PocketShell port-forward mark instead of
            // the old generic ic_dialog_info.
            .setSmallIcon(R.drawable.ic_stat_forwarding)
            // Issue #752: convey the forwarded PORT COUNT as a badge number on
            // the status-bar icon, like the circled count Google Recorder /
            // some apps draw next to their indicator. Each active tunnel is one
            // forwarded port, so the tunnel count IS the port count. setNumber
            // drives both the launcher dot's count and (on many OEM status
            // bars) a small number near the small icon. Only set a positive
            // number — 0/Connecting renders no badge. The badge requires the
            // channel to allow badging (setShowBadge(true), see the channel).
            .apply { if (tunnelCount > 0) setNumber(tunnelCount) }
            .setContentIntent(contentIntent)
            // Ongoing is the closest supported FGS contract to "non-
            // dismissible": while ≥1 tunnel is active Android keeps this as
            // the service's foreground notification and the user stops it via
            // Stop or by disabling forwarding. Newer Android versions may
            // still expose limited system-managed dismissal controls for some
            // foreground-service notifications, so we also remove it
            // immediately on Stop / zero active hosts.
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            // Silence the notification itself (no sound/vibration) regardless of
            // the channel importance. The `_v6` channel is DEFAULT importance so
            // the persistent status-bar icon shows near the clock (#752), but
            // setSilent(true) keeps it from buzzing or popping a heads-up — the
            // Recorder/Spotify quiet-but-visible contract. Sweep-resistance comes
            // from the ongoing/NO_CLEAR flags below, not importance.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Issue #752 (reopened): DEFAULT pre-O priority pairs with the
            // DEFAULT-importance channel so the status-bar icon surfaces near the
            // clock (PRIORITY_LOW kept the icon out of the status bar on real
            // devices). setSilent(true) above keeps it quiet despite DEFAULT, and
            // sweep-resistance is the NO_CLEAR/ongoing flags below — not importance.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent,
            )

        // Issue #1487: Android 16 Live Update. A forward is user-initiated and
        // exposes a local service through the phone until Stop is pressed; that
        // security-relevant live exposure must remain glanceable outside the app.
        // Always REQUEST promotion on API 36+. Android/OEM/user policy remains
        // free to demote it to this same required ordinary D21 ongoing FGS
        // notification; that is platform policy, not an application fallback.
        // Pre-36 builds remain the ordinary ongoing notification with no hidden
        // promotion contract.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val chipLabel =
                forwardingChipShortLabel(tunnelCount, restoringHostCount, activePorts)
            builder
                .setShortCriticalText(chipLabel)
                .setRequestPromotedOngoing(true)
        }

        var notification = builder.build()

        // The original Android 16 (API 36) framework shipped the promoted-
        // ongoing eligibility check with one extra requirement: non-CallStyle
        // notifications had to request colorization. Android 16 QPR2 / API
        // 36.1 reversed that rule and instead disqualifies colorized
        // notifications. Probe the framework's own eligibility predicate so
        // each Android 16 implementation gets the shape it requires: current
        // releases keep the preferred non-colorized BigTextStyle, while the
        // initial API-36 release gets its required colorized request.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            !notification.hasPromotableCharacteristics()
        ) {
            builder.setColorized(true)
            notification = builder.build()
        }

        // Issue #487 (reopened): make the non-clearable contract explicit on the
        // raw notification. NotificationCompat.setOngoing(true) already sets both
        // FLAG_ONGOING_EVENT and FLAG_NO_CLEAR, but setting them directly here
        // documents the intent, is asserted by the unit test, and guards against
        // a future builder change silently dropping the sweep-resistance the
        // maintainer asked for. FLAG_NO_CLEAR is what keeps a tray "clear all"
        // from removing the notification while a tunnel is active.
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    @androidx.annotation.VisibleForTesting
    internal fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // Hard-cut (D22): drop the stale channels so no install keeps the old
        // silent-group LOW (no status-bar icon — the #752 regression), the
        // buzzing HIGH, or the swipe-away presentation. Channel importance is
        // immutable once created, which is why the visible channel id is bumped
        // (now `_v6`) instead of mutated; deleting the old ids keeps the app's
        // channel settings list clean and prevents a stale "Port forwarding" entry.
        LEGACY_CHANNEL_IDS.forEach { runCatching { manager.deleteNotificationChannel(it) } }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Port forwarding",
            // Issue #752 (reopened 2026-06-24): DEFAULT importance so the ongoing
            // notification leaves the "Silent" group and its persistent ⇄
            // status-bar icon shows near the clock — the always-on indicator the
            // maintainer asked for. IMPORTANCE_LOW was the regression: a
            // below-DEFAULT channel is "Silent" on a real device and its
            // status-bar icon is suppressed. DEFAULT (not HIGH) only surfaces the
            // icon — it does NOT pop a heads-up; the channel is then forced silent
            // below (null sound + no vibration) and the notification sets
            // setSilent(true), so it is visible-but-quiet (Recorder/Spotify
            // contract), never buzzing on forward-start. Sweep-resistance is the
            // NO_CLEAR/ongoing flags on the notification, NOT importance.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Always-on quiet status while SSH port forwarding is active"
            // Issue #752: allow the channel to badge so the forwarded port
            // count (NotificationCompat.setNumber, set per-build) can surface as
            // the circled count near the status-bar icon / launcher dot, the
            // Google-Recorder-style "icon + number" the maintainer asked for.
            // This is just the badge COUNT — the channel is DEFAULT importance but
            // forced silent below, so it never buzzes or pops a heads-up;
            // sweep-resistance is still the FLAG_NO_CLEAR/ongoing flags, not
            // importance.
            setShowBadge(true)
            // Belt-and-braces: explicitly silence the channel. IMPORTANCE_LOW is
            // already non-alerting, but null sound + vibration off guarantees the
            // channel never buzzes or rings even if the platform default changes.
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * Issue #2006 test-only barrier at the notification-generation close boundary.
 *
 * [ForwardingService.stopForwarding] runs on TWO threads — the service thread
 * (`ACTION_STOP`, rejected promotion) and the observe dispatcher (a zero
 * snapshot) — and the teardown it performs is not atomic with respect to a
 * concurrent `ACTION_START`. There are TWO windows, and the #2006 defect had
 * one bug in each:
 *
 *  - [Phase.BEFORE_CLOSE] — between "the observer decided to tear down" and
 *    "the generation is actually invalidated". A forward that starts here makes
 *    the observer's zero snapshot false; acting on it anyway removed a live
 *    forward's notification and stopped the service.
 *  - [Phase.AFTER_CLOSE] — between "the generation was invalidated" and "the
 *    torn-down observer is cleared". A start here opens the NEXT generation,
 *    posts the bootstrap `Connecting…` body and arms its own observer, which
 *    the old, generation-blind clear then cancelled — stranding the tray on
 *    `Connecting…`.
 *
 * Production always invokes [await]; with no barrier installed it is a plain
 * no-op field read, so there is no production behaviour and no extra thread,
 * timer, or background work (D21). A test installs a barrier to hold one of
 * those windows open and drive the interleaving deterministically instead of
 * hoping a loaded emulator reproduces a microsecond-wide preemption — the same
 * hard-inject model as `BackgroundGraceTestOverride` (#450) and the #780
 * synthetic IME inset.
 *
 * A barrier is process-wide, so the installed lambda must decide for itself
 * which caller to hold (which phase, only the first call, only off the main
 * thread) and MUST always return; it is released in the test's teardown.
 */
internal object ForwardingNotificationCloseBarrier {

    /** Which side of the generation close the caller is on. */
    enum class Phase { BEFORE_CLOSE, AFTER_CLOSE }

    @Volatile
    private var barrier: ((Phase) -> Unit)? = null

    fun setForTest(barrier: ((Phase) -> Unit)?) {
        this.barrier = barrier
    }

    fun await(phase: Phase) {
        barrier?.invoke(phase)
    }
}

/**
 * Android 16's short critical text has a suggested seven-character budget.
 * A single known forward names its remote port (`:65535`); multiple forwards
 * show a count (`2 ports`). Very large counts compact to `12345p` / `99999+p`.
 */
internal fun forwardingChipShortLabel(
    tunnelCount: Int,
    restoringHostCount: Int,
    activePorts: Set<Int>,
): String {
    if (restoringHostCount > 0 || tunnelCount <= 0) return "…"
    if (tunnelCount == 1 && activePorts.size == 1) return ":${activePorts.single()}"
    if (tunnelCount == 1) return "1 port"
    return when {
        tunnelCount <= 9 -> "$tunnelCount ports"
        tunnelCount <= 99_999 -> "${tunnelCount}p"
        else -> "99999+p"
    }
}
