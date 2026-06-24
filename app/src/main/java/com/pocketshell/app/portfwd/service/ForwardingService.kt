package com.pocketshell.app.portfwd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.MainActivity
import com.pocketshell.app.R
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.systemsurfaces.ForwardingTileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
 *  - Register a network-availability callback and call
 *    [ForwardingController.reconnectNow] on network recovery, so the
 *    controller-owned [com.pocketshell.core.portfwd.AutoForwarderSupervisor]
 *    skips its exponential-backoff sleep
 *  - Stop itself when [ForwardingController.flowOfActiveHostCount]
 *    drops to zero (all hosts disabled their auto-forward toggle)
 */
@AndroidEntryPoint
class ForwardingService : Service() {

    @Inject
    lateinit var controller: ForwardingController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var defaultNetworkWasLost = false
    private var hasStartedForeground = false

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
                stopForwarding()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or null (restart-after-kill). Even on a
                // null intent — Android can restart a STICKY service
                // without an intent — we promote to foreground because
                // a STARTED foreground service that doesn't call
                // startForeground() fast enough crashes the process
                // with ForegroundServiceDidNotStartInTimeException.
                promoteToForegroundIfNeeded(initialNotification())
                if (observeJob == null || observeJob?.isActive != true) {
                    startObserving()
                    registerNetworkCallback()
                }
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
        observeJob?.cancel()
        observeJob = null
        unregisterNetworkCallback()
        scope.cancel()
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

    private fun startObserving() {
        observeJob = scope.launch {
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
            ) { activeHosts, tunnels, primaryHost, restoringHosts ->
                NotificationSnapshot(activeHosts, tunnels, primaryHost, restoringHosts)
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.activeHosts == 0) {
                        // All hosts disabled — tear down. The
                        // controller is responsible for posting the
                        // initial zero-count snapshot, so this also
                        // covers the edge case where the user toggled
                        // off before the service finished promoting to
                        // foreground.
                        stopForwarding()
                    } else {
                        updateNotification(
                            snapshot.primaryHost,
                            snapshot.activeHosts,
                            snapshot.tunnels,
                            snapshot.restoringHosts,
                        )
                    }
                }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        defaultNetworkWasLost = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleDefaultNetworkAvailable()
            }

            override fun onLost(network: Network) {
                handleDefaultNetworkLost()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: SecurityException) {
            // Some restricted profiles disallow registering network
            // callbacks. Reconnect-on-network-recovery becomes a
            // best-effort feature in that case; the underlying
            // supervisor still retries on its own backoff schedule.
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Already unregistered (e.g. service was stop/started
            // quickly). Safe to ignore.
        }
        networkCallback = null
        defaultNetworkWasLost = false
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleDefaultNetworkAvailable() {
        // Network came back. Nudge the supervisor(s) so any in-flight
        // backoff sleep cancels and a reconnect attempt happens
        // immediately. If we observed an actual default-network loss
        // first, force a transport rebuild: sshj can leave the old
        // session looking "connected" after Android swaps networks,
        // while the forwards are already dead.
        if (defaultNetworkWasLost) {
            defaultNetworkWasLost = false
            controller.forceReconnectNow()
        } else {
            controller.reconnectNow()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleDefaultNetworkLost() {
        defaultNetworkWasLost = true
    }

    private fun stopForwarding() {
        observeJob?.cancel()
        observeJob = null
        unregisterNetworkCallback()
        // STOP_FOREGROUND_REMOVE makes the notification disappear
        // immediately. The constant has been stable since API 24.
        stopForeground(STOP_FOREGROUND_REMOVE)
        hasStartedForeground = false
        stopSelf()
    }

    private fun promoteToForegroundIfNeeded(notification: Notification) {
        if (hasStartedForeground) return
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
    }

    private fun updateNotification(
        hostName: String,
        hostCount: Int,
        tunnelCount: Int,
        restoringHostCount: Int = 0,
    ) {
        val notification = buildNotification(hostName, hostCount, tunnelCount, restoringHostCount)
        if (!hasStartedForeground) {
            promoteToForegroundIfNeeded(notification)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
            .build()

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
