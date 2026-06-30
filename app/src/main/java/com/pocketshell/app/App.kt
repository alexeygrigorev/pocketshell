package com.pocketshell.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.connectivity.hasSameNetworkIdentityAs
import com.pocketshell.app.connectivity.networkDiagnosticFields
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticRecorder
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.diagnostics.StrictModeInstaller
import com.pocketshell.app.portfwd.ForwardingResumeScheduler
import com.pocketshell.app.release.UpdateCheckScheduler
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.service.SessionServiceController
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxSessionRuntimeCache
import com.pocketshell.app.tmux.closeCachedRuntime
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt-managed [Application]. Beyond installing the crash reporter, this
 * class wires the singleton [UsageScheduler] to the
 * [androidx.lifecycle.ProcessLifecycleOwner] so the polling loop pauses
 * the moment the user backgrounds the app and resumes on the next
 * `ON_START` (issue #161, decision D21 — no background work).
 *
 * The scheduler is started here unconditionally. While the singleton
 * has no eligible hosts to poll (every saved host has
 * `pocketshellInstalled != true`) `fetchOnce` is essentially a Room read and a
 * no-op snapshot update — cheap. The wire-up therefore costs nothing on
 * a fresh install and means the first time a user bootstraps pocketshell on a
 * host their usage data starts flowing without an additional
 * screen-mount trigger.
 *
 * Issue #235: also installs the process-wide tmux auto-detach observer.
 * On `ON_STOP` every live tmux `-CC` client is asked to detach
 * cleanly so a desktop client attaching to the same session while
 * PocketShell is in the background sees the window at the desktop's
 * dimensions rather than `min(phone, desktop)`. On `ON_START` the
 * registered view models reattach to their previous session. The view
 * models register their per-host hooks into [ActiveTmuxClients] when
 * they attach; this class drives all hooks fanned out from a single
 * process lifecycle event.
 *
 * Issue #450 (maintainer-sanctioned relaxation of D21): the terminal
 * teardown on `ON_STOP` is now **delayed by a bounded grace window**
 * ([BACKGROUND_GRACE_MILLIS], default 60 s) instead of firing immediately. A
 * quick app-switch — to copy a snippet, glance at another app —
 * returns to PocketShell within the grace window, the pending teardown
 * is cancelled on `ON_START`, and the live SSH/tmux connection is never
 * torn down. The user resumes instantly with no reconnect and no
 * "Connecting"/"Reconnecting" UI. Only when the app stays backgrounded
 * past the grace window does the existing teardown run. This is NOT
 * permanent background work: it is a one-shot, self-cancelling delay of
 * an already-scheduled teardown, not a `WorkManager`/`AlarmManager`
 * job, not a polling loop, and not a wakelock. The usage-poll loop and
 * agent detection still pause on `ON_STOP` immediately — the grace
 * window applies only to the terminal connection teardown.
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var usageScheduler: UsageScheduler

    @Inject
    lateinit var activeTmuxClients: ActiveTmuxClients

    @Inject
    lateinit var sshLeaseManager: SshLeaseManager

    @Inject
    lateinit var terminalNetworkObserver: TerminalNetworkObserver

    @Inject
    lateinit var tmuxRuntimeCache: TmuxSessionRuntimeCache

    @Inject
    lateinit var diagnosticRecorder: DiagnosticRecorder

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * Issue #698: fires the GitHub-Releases update check on process
     * foreground resume (and host-open, from MainActivity), throttled, so
     * the maintainer — who deep-links straight into a host and almost
     * never opens the home screen — actually sees update prompts. Wired to
     * [ProcessLifecycleOwner] in [onCreate], mirroring [usageScheduler].
     */
    @Inject
    lateinit var updateCheckScheduler: UpdateCheckScheduler

    /**
     * Issue #752 (REOPENED): re-establishes port forwarding for every host
     * the user previously enabled the moment the app foregrounds, so the
     * persisted `host.enabled` intent actually drives
     * [com.pocketshell.app.portfwd.ForwardingController.activeHostCount] and
     * all three port-forward indicators (⇄ notification, host-list pill,
     * in-session chip) appear without the user re-toggling the panel switch.
     * Foreground-only (D21): hooked to [ProcessLifecycleOwner]'s ON_START
     * like [usageScheduler] / [updateCheckScheduler]; no background work.
     */
    @Inject
    lateinit var forwardingResumeScheduler: ForwardingResumeScheduler

    /**
     * Issue #977: starts the session foreground-service hold while at
     * least one live tmux client is attached, and exposes that hold to
     * the background grace teardown gate below.
     */
    @Inject
    lateinit var sessionServiceController: SessionServiceController

    /**
     * Issue #235: scope for fanning out tmux detach/reattach hooks
     * driven by the lifecycle observer. The dispatcher is
     * [Dispatchers.Main.immediate]: each hook posts work back into a
     * [com.pocketshell.app.tmux.TmuxSessionViewModel] coroutine scope,
     * which is the cheap, main-thread-friendly path. The actual SSH
     * round-trip happens inside `closeCurrentConnectionAndJoin` /
     * `connect`, which already switch to [Dispatchers.IO] internally;
     * dispatching the hook itself on Main avoids an extra thread hop
     * and keeps the observer execution deterministic against the test
     * `Dispatchers.setMain` swap. A [SupervisorJob] is used so a
     * single hook's failure does not cancel sibling hosts.
     */
    private val tmuxLifecycleScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sshLifecycleScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val terminalNetworkScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val terminalNetworkLifecycleGate = TerminalNetworkLifecycleGate(
        // Issue #1080 — wall-elapsed boot clock (counts deep sleep). The
        // background-elapsed / post-resume-suppression decisions here are the
        // App-level sibling of the transport-liveness staleness clock; on
        // System.nanoTime they froze across a Doze gap and mis-evaluated the
        // grace/suppression windows. Production injects the boot clock; the unit
        // tests keep the System.nanoTime default (android.jar stub).
        nowMillis = { SystemClock.elapsedRealtime() },
    )

    /**
     * Issue #450: scope that runs the bounded grace-window timer. Uses
     * [Dispatchers.Main.immediate] so the start/cancel decision is
     * deterministic against the test `Dispatchers.setMain` swap and so a
     * cancel from `ON_START` is observed before any new STOP timer would
     * start. The actual teardown the timer fans out to still hops to IO
     * internally (see [dispatchTmuxBackground] / the SSH lease worker).
     */
    private val graceLifecycleScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sshLeaseLifecycleDispatcher = SshLeaseLifecycleDispatcher(
        scope = sshLifecycleScope,
        onProcessStopped = { sshLeaseManager.onProcessStopped() },
        onProcessStarted = { sshLeaseManager.onProcessStarted() },
    )

    /**
     * Issue #450: delays the terminal teardown on background by a bounded
     * grace window and cancels it if the app foregrounds first. The
     * controller drives the existing tmux detach + SSH lease stop only
     * when the window actually elapses; an `ON_START` within the window
     * is a no-op so the live connection survives a quick app-switch.
     */
    private val backgroundGraceController = BackgroundGraceController(
        scope = graceLifecycleScope,
        graceMillis = BACKGROUND_GRACE_MILLIS,
        onGraceElapsed = {
            // Issue #1123 (bounded-grace D21 update): once the grace window elapses the
            // app FULLY tears down — detach the `-CC` control client cleanly (no orphan,
            // #215), close cached runtimes, and release the SSH lease. The #977/#1021
            // INDEFINITE foreground-service hold (which previously PRESERVED the live
            // connection past grace) is removed: nothing runs in the background beyond
            // the grace window. The tmux session persists server-side; a return after
            // grace does a normal reconnect. The bounded foreground service stops as the
            // teardown unregisters the last live client (see [SessionServiceController]).
            dispatchTmuxBackground()
            tmuxRuntimeCache.clear().forEach { cached ->
                runCatching { cached.closeCachedRuntime() }
                    .onFailure { Log.w(APP_LIFECYCLE_TAG, "tmux cached runtime close failed", it) }
            }
            sshLeaseLifecycleDispatcher.dispatch(Lifecycle.Event.ON_STOP)
        },
        onForeground = { resumedWithinGrace ->
            // Always reverse the SSH lease "stopped" gate so reacquire
            // works. When the grace window already elapsed (teardown
            // ran) the tmux view models reattach via their foreground
            // hooks; when we resumed within the window nothing was torn
            // down, so reattach is a no-op and the user sees no
            // reconnect.
            sshLeaseLifecycleDispatcher.dispatch(Lifecycle.Event.ON_START)
            // Issue #548: always run the cheap foreground probe even for
            // within-grace resumes. The probe checks whether the live tmux
            // control channel is still responsive (a lightweight
            // `refresh-client` round-trip); if it has gone stale during the
            // brief background interval the probe triggers a reconnect.
            // Previously the within-grace branch skipped this probe entirely,
            // so a stale SSH transport survived the grace window and only
            // surfaced as a failure on the next user interaction.
            dispatchTmuxForeground(resumedWithinGrace = resumedWithinGrace)
            val runtimeDiagnostics = terminalRuntimeDiagnostics()
            val networkDecision = terminalNetworkLifecycleGate.onForegroundResumeFinished(
                resumedWithinGrace = resumedWithinGrace,
                hasLiveTerminalRuntime = runtimeDiagnostics.hasLiveTerminalRuntime,
            )
            dispatchTerminalNetworkDecision(networkDecision, runtimeDiagnostics)
            if (!resumedWithinGrace) {
                terminalNetworkObserver.refresh("process-foreground")
            }
        },
        foregroundDiagnosticFields = { resumedWithinGrace ->
            val runtimeDiagnostics = terminalRuntimeDiagnostics()
            runtimeDiagnostics.diagnosticFields() +
                terminalNetworkLifecycleGate.previewForegroundResumeFinished(
                    resumedWithinGrace = resumedWithinGrace,
                    hasLiveTerminalRuntime = runtimeDiagnostics.hasLiveTerminalRuntime,
                ).diagnosticFields()
        },
        // Issue #1080 — wall-elapsed boot clock so the within-grace vs
        // beyond-grace decision (`resumedWithinGrace`, `backgroundDeadlineAtMs`,
        // background elapsed) counts time spent in deep sleep. On System.nanoTime
        // (CLOCK_MONOTONIC, frozen in deep Doze) a Doze gap that began within the
        // ~60s grace window made the frozen clock under-count elapsed time and
        // `resumedWithinGrace` could wrongly evaluate true — routing the resume
        // into the within-grace reseed-only fast path that does ZERO liveness
        // check over a now-dead socket. The boot clock makes the window math
        // reflect real elapsed time so the beyond-grace arm (which verifies
        // liveness) runs. Unit tests keep the System.nanoTime default.
        nowMillis = { SystemClock.elapsedRealtime() },
    )

    private val terminalLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                val terminalGraceMillis = BackgroundGraceTestOverride.currentOr(
                    settingsRepository.settings.value.backgroundGraceMillis,
                )
                terminalNetworkLifecycleGate.onBackground(graceMillis = terminalGraceMillis)
                DiagnosticEvents.record(
                    "app",
                    "background",
                    "source" to "process_lifecycle",
                    "trigger" to "on_stop",
                )
                backgroundGraceController.setGraceMillis(terminalGraceMillis)
                backgroundGraceController.onBackground()
                // Issue #1123: stamp the wall-clock disconnect deadline so the bounded
                // session-hold notification renders a live count-down to teardown. Posted
                // ONCE; the system chronometer does the per-second rendering (no wakeups).
                sessionServiceController.onBackgroundGraceStarted(
                    System.currentTimeMillis() + terminalGraceMillis.coerceAtLeast(0L),
                )
            }
            Lifecycle.Event.ON_START -> {
                terminalNetworkLifecycleGate.onForegroundResumeStarted()
                DiagnosticEvents.record(
                    "app",
                    "foreground",
                    "source" to "process_lifecycle",
                    "trigger" to "on_start",
                )
                backgroundGraceController.onForeground()
                // Issue #1123: returned within grace — clear the disconnect count-down so
                // the notification reverts to its steady "connected" state. (Beyond grace
                // the teardown stops the service entirely.)
                sessionServiceController.onForegroundResumed()
            }
            else -> Unit
        }
    }

    override fun onCreate() {
        StartupTiming.mark("app-on-create-start")
        super.onCreate()
        DiagnosticEvents.install(diagnosticRecorder)
        TmuxClientDiagnostics.install { event, fields ->
            DiagnosticEvents.record("connection", event, *fields.toList().toTypedArray())
        }
        DiagnosticEvents.record("app", "created")
        CrashReporter.install(this)
        StartupTiming.mark("app-crash-reporter-installed")
        // Issue #933 (#928 D9 / P1): install the process-wide Main-thread
        // StrictMode tripwire so a main-thread disk read / write / mutex wait
        // (the #926/#928-D1 freeze class — `runBlocking { … }` Room reads that
        // `detectNetwork()` misses) is routed into DiagnosticEvents as a
        // `strictmode.violation` for the load-bearing journeys to HARD-assert.
        // DEBUG/TEST-scoped (no-op on the signed release APK) and NEVER
        // penaltyDeath — the journey is the gate, not a process kill. Installed
        // right after the crash reporter and before the rest of onCreate so the
        // startup hot window is itself observed.
        StrictModeInstaller.installIfDebuggable(this)
        StartupTiming.mark("strict-mode-installed")
        // No-background-work hook-up (issue #161 / D21). Attach the
        // ProcessLifecycleOwner observer before starting the loop so
        // the loop's `processStarted.first { it }` gate sees the
        // already-correct value rather than waiting for the first
        // ON_START event.
        usageScheduler.observeProcessLifecycle()
        StartupTiming.mark("usage-lifecycle-observed")
        usageScheduler.start()
        StartupTiming.mark("usage-scheduler-started")

        // Issue #698: fire the GitHub-Releases update check on process
        // foreground resume (throttled) so the maintainer — who deep-links
        // straight into a host and skips the home screen — actually sees
        // update prompts. Foreground-only (D21): the observer only fires on
        // ON_START and each check is a single one-shot HTTP round-trip; no
        // WorkManager / AlarmManager / repeating timer.
        updateCheckScheduler.observeProcessLifecycle()
        StartupTiming.mark("update-check-lifecycle-observed")

        // Issue #752 (REOPENED): re-establish port forwarding for every
        // host the user previously enabled (persisted `host.enabled = 1`)
        // on every foreground resume, so the persisted intent populates the
        // [ForwardingController] and the always-on port-forward indicators
        // (⇄ notification, host-list pill, in-session chip) appear without
        // the user re-toggling the panel switch. Foreground-only (D21): the
        // observer fires on ON_START only; nothing runs while backgrounded,
        // no WorkManager / AlarmManager / boot receiver. Idempotent — a host
        // already actively forwarding in this process is never re-started.
        forwardingResumeScheduler.observeProcessLifecycle()
        StartupTiming.mark("forwarding-resume-lifecycle-observed")

        // Issue #977 + #1123: BOUNDED foreground-service hold for live SSH/tmux
        // sessions. The service runs while a live tmux client is attached so the OS
        // sees an explicit foreground service + wakelock that keeps the connection
        // alive through Doze during the (now 5-min, #1123) background grace window. The
        // hold is BOUNDED, not indefinite: once the grace window elapses the terminal
        // teardown unregisters the live client, which stops this service (no wake-lock
        // or hold beyond grace). The old indefinite-hold "preserve past grace" + the
        // notification-Stop-after-grace teardown plumbing are removed (#1123).
        sessionServiceController.observeActiveSessions()
        StartupTiming.mark("session-service-lifecycle-observed")

        // Issue #235 + #450: auto-detach tmux `-CC` clients on lifecycle
        // background + reattach on foreground, but only after the bounded
        // grace window elapses (see [backgroundGraceController]). The
        // observer attaches to [ProcessLifecycleOwner] (not the
        // activity-level lifecycle — rotation / dark-mode flips would
        // thrash the detach on every config change) so the journey only
        // fires when ALL PocketShell activities go background. A quick
        // app-switch that returns within the grace window never tears the
        // terminal connection down.
        ProcessLifecycleOwner.get().lifecycle.addObserver(terminalLifecycleObserver)
        terminalNetworkScope.launch {
            terminalNetworkObserver.changes.collect { change ->
                val runtimeDiagnostics = terminalRuntimeDiagnostics()
                when (val decision = terminalNetworkLifecycleGate.onNetworkChange(
                    change = change,
                    hasLiveTerminalRuntime = runtimeDiagnostics.hasLiveTerminalRuntime,
                )) {
                    is TerminalNetworkDecision.Dispatch ->
                        dispatchTerminalNetworkChange(decision.change, decision.gateDiagnostics, runtimeDiagnostics)
                    is TerminalNetworkDecision.Defer ->
                        logDeferredTerminalNetworkChange(decision.change, decision.gateDiagnostics, runtimeDiagnostics)
                    is TerminalNetworkDecision.Suppress ->
                        decision.change?.let {
                            logSuppressedTerminalNetworkChange(it, decision.gateDiagnostics, runtimeDiagnostics)
                        }
                }
            }
        }
        StartupTiming.mark("app-on-create-end")
    }

    private fun dispatchTerminalNetworkDecision(
        decision: TerminalNetworkDecision,
        runtimeDiagnostics: TerminalRuntimeDiagnostics = terminalRuntimeDiagnostics(),
    ) {
        when (decision) {
            is TerminalNetworkDecision.Dispatch ->
                dispatchTerminalNetworkChange(decision.change, decision.gateDiagnostics, runtimeDiagnostics)
            is TerminalNetworkDecision.Defer ->
                logDeferredTerminalNetworkChange(decision.change, decision.gateDiagnostics, runtimeDiagnostics)
            is TerminalNetworkDecision.Suppress ->
                decision.change?.let {
                    logSuppressedTerminalNetworkChange(it, decision.gateDiagnostics, runtimeDiagnostics)
                }
        }
    }

    private fun dispatchTerminalNetworkChange(
        change: TerminalNetworkChange,
        gateDiagnostics: TerminalNetworkGateDiagnostics,
        runtimeDiagnostics: TerminalRuntimeDiagnostics,
    ) {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        recordTerminalNetworkCauseTrail(
            outcome = if (hooks.isEmpty()) "no_hooks" else "dispatch",
            change = change,
            gateDiagnostics = gateDiagnostics,
            runtimeDiagnostics = runtimeDiagnostics,
            hookCount = hooks.size,
        )
        if (hooks.isEmpty()) return
        Log.i(
            TERMINAL_NETWORK_TAG,
            "terminal-network-change-fanout count=${hooks.size} " +
                "sequence=${change.sequence} reason=${change.reason} " +
                "previous=${change.previous.logValue} current=${change.current.logValue}",
        )
        DiagnosticEvents.record(
            "network",
            "change",
            *networkEventFields(
                change = change,
                gateDiagnostics = gateDiagnostics,
                runtimeDiagnostics = runtimeDiagnostics,
                "hookCount" to hooks.size,
            ),
        )
        for (hook in hooks) {
            terminalNetworkScope.launch {
                runCatching { hook.onNetworkChanged(change) }
                    .onFailure { Log.w(TERMINAL_NETWORK_TAG, "terminal network hook failed", it) }
            }
        }
    }

    private fun logDeferredTerminalNetworkChange(
        change: TerminalNetworkChange,
        gateDiagnostics: TerminalNetworkGateDiagnostics,
        runtimeDiagnostics: TerminalRuntimeDiagnostics,
    ) {
        Log.i(
            TERMINAL_NETWORK_TAG,
            "terminal-network-change-deferred sequence=${change.sequence} " +
                "reason=${change.reason}",
        )
        DiagnosticEvents.record(
            "network",
            "change_deferred",
            *networkEventFields(change, gateDiagnostics, runtimeDiagnostics),
        )
        recordTerminalNetworkCauseTrail(
            outcome = "defer",
            change = change,
            gateDiagnostics = gateDiagnostics,
            runtimeDiagnostics = runtimeDiagnostics,
        )
    }

    private fun logSuppressedTerminalNetworkChange(
        change: TerminalNetworkChange,
        gateDiagnostics: TerminalNetworkGateDiagnostics,
        runtimeDiagnostics: TerminalRuntimeDiagnostics,
    ) {
        if (!change.isRealValidatedIdentityChange) return
        Log.i(
            TERMINAL_NETWORK_TAG,
            "terminal-network-change-suppressed-within-grace " +
                "sequence=${change.sequence} reason=${change.reason}",
        )
        DiagnosticEvents.record(
            "network",
            "change_suppressed_within_grace",
            *networkEventFields(change, gateDiagnostics, runtimeDiagnostics),
        )
        recordTerminalNetworkCauseTrail(
            outcome = "suppress",
            change = change,
            gateDiagnostics = gateDiagnostics,
            runtimeDiagnostics = runtimeDiagnostics,
        )
    }

    private val TerminalNetworkChange.isRealValidatedIdentityChange: Boolean
        get() = previousValidated != null && !previousValidated.hasSameNetworkIdentityAs(current)

    private fun terminalRuntimeDiagnostics(): TerminalRuntimeDiagnostics {
        val activeClients = activeTmuxClients.clients.value.values.toList()
        val cacheDiagnostics = tmuxRuntimeCache.diagnosticSnapshot()
        val liveActiveClientCount = activeClients.count { entry -> !entry.client.disconnected.value }
        return TerminalRuntimeDiagnostics(
            activeTmuxClientCount = activeClients.size,
            liveActiveTmuxClientCount = liveActiveClientCount,
            cachedRuntimeCount = cacheDiagnostics.cachedRuntimeCount,
            liveCachedRuntimeCount = cacheDiagnostics.liveCachedRuntimeCount,
            clientDisconnected = activeClients.singleOrNull()?.client?.disconnected?.value
                ?: cacheDiagnostics.clientDisconnected,
            sessionConnected = cacheDiagnostics.sessionConnected,
        )
    }

    private fun dispatchTmuxBackground() {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        if (hooks.isEmpty()) return
        Log.i(
            APP_LIFECYCLE_TAG,
            "tmux-on-stop fanout count=${hooks.size}",
        )
        DiagnosticEvents.record(
            "app",
            "terminal_background_teardown",
            "hookCount" to hooks.size,
            "source" to "background_grace_elapsed",
            "trigger" to "process_background",
            "withinGrace" to false,
        )
        ReconnectCauseTrail.record(
            stage = "background_teardown",
            outcome = "dispatch",
            cause = "background_grace_elapsed",
            trigger = "process_background",
            "hookCount" to hooks.size,
        )
        for (hook in hooks) {
            tmuxLifecycleScope.launch {
                runCatching { hook.onBackground() }
                    .onFailure { Log.w(APP_LIFECYCLE_TAG, "tmux background hook failed", it) }
            }
        }
    }

    private fun dispatchTmuxForeground(resumedWithinGrace: Boolean = false) {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        if (hooks.isEmpty()) return
        Log.i(
            APP_LIFECYCLE_TAG,
            "tmux-on-start fanout count=${hooks.size} " +
                "resumedWithinGrace=$resumedWithinGrace",
        )
        DiagnosticEvents.record(
            "app",
            "terminal_foreground_reattach",
            "hookCount" to hooks.size,
            "source" to "background_grace_foreground",
            "trigger" to "process_foreground",
            "resumedWithinGrace" to resumedWithinGrace,
        )
        ReconnectCauseTrail.record(
            stage = "foreground_reattach",
            outcome = "dispatch",
            cause = if (resumedWithinGrace) "within_grace_foreground" else "post_grace_foreground",
            trigger = "process_foreground",
            "hookCount" to hooks.size,
            "resumedWithinGrace" to resumedWithinGrace,
        )
        for (hook in hooks) {
            tmuxLifecycleScope.launch {
                runCatching { hook.onForeground(resumedWithinGrace) }
                    .onFailure { Log.w(APP_LIFECYCLE_TAG, "tmux foreground hook failed", it) }
            }
        }
    }
}

private fun networkEventFields(
    change: TerminalNetworkChange,
    gateDiagnostics: TerminalNetworkGateDiagnostics,
    runtimeDiagnostics: TerminalRuntimeDiagnostics,
    vararg extraFields: Pair<String, Any?>,
): Array<Pair<String, Any?>> =
    buildList {
        addAll(change.networkDiagnosticFields().toList())
        addAll(gateDiagnostics.diagnosticFields())
        addAll(runtimeDiagnostics.diagnosticFields())
        addAll(extraFields.toList())
    }.toTypedArray()

private fun recordTerminalNetworkCauseTrail(
    outcome: String,
    change: TerminalNetworkChange,
    gateDiagnostics: TerminalNetworkGateDiagnostics,
    runtimeDiagnostics: TerminalRuntimeDiagnostics,
    hookCount: Int? = null,
) {
    ReconnectCauseTrail.record(
        stage = "network_gate",
        outcome = outcome,
        cause = gateDiagnostics.reason,
        trigger = change.reason,
        "sequence" to change.sequence,
        "deferredFromBackground" to change.deferredFromBackground,
        "gateDecision" to gateDiagnostics.decision,
        "reconnectOutcome" to gateDiagnostics.decision.reconnectOutcome(),
        "backgroundCycleId" to gateDiagnostics.backgroundCycleId,
        "pendingNetworkClassification" to gateDiagnostics.pendingNetworkClassification,
        "hasLiveTerminalRuntime" to runtimeDiagnostics.hasLiveTerminalRuntime,
        "liveActiveTmuxClientCount" to runtimeDiagnostics.liveActiveTmuxClientCount,
        "liveCachedRuntimeCount" to runtimeDiagnostics.liveCachedRuntimeCount,
        "hookCount" to hookCount,
    )
}

private data class TerminalRuntimeDiagnostics(
    val activeTmuxClientCount: Int,
    val liveActiveTmuxClientCount: Int,
    val cachedRuntimeCount: Int,
    val liveCachedRuntimeCount: Int,
    val clientDisconnected: Boolean?,
    val sessionConnected: Boolean?,
) {
    val hasLiveTerminalRuntime: Boolean =
        liveActiveTmuxClientCount > 0 || liveCachedRuntimeCount > 0

    fun diagnosticFields(): List<Pair<String, Any?>> =
        listOf(
            "hasLiveTerminalRuntime" to hasLiveTerminalRuntime,
            "activeTmuxClientCount" to activeTmuxClientCount,
            "liveActiveTmuxClientCount" to liveActiveTmuxClientCount,
            "cachedRuntimeCount" to cachedRuntimeCount,
            "liveCachedRuntimeCount" to liveCachedRuntimeCount,
            "clientDisconnected" to clientDisconnected,
            "sessionConnected" to sessionConnected,
        )
}

internal fun shouldDispatchPendingTerminalNetworkChange(
    resumedWithinGrace: Boolean,
    pendingChange: TerminalNetworkChange?,
    hasLiveTerminalRuntime: Boolean,
): Boolean {
    if (pendingChange == null) return false
    if (!resumedWithinGrace) return true
    // Issue #997: a pending bare-LOSS / RESTORE deferred while backgrounded is a
    // meaningful drop/recovery, not a same-identity reassoc — replay it on a
    // within-grace foreground when there is no live runtime, the same as a real
    // validated handoff. (A NetworkRestored drives the fast reconnect; a
    // NetworkLost flips the UI to reconnecting/holds.)
    if (pendingChange.kind != TerminalNetworkChangeKind.ValidatedIdentityChange) {
        return !hasLiveTerminalRuntime
    }
    return !hasLiveTerminalRuntime && pendingChange.previousValidated != null &&
        !pendingChange.previousValidated.hasSameNetworkIdentityAs(pendingChange.current)
}

internal class TerminalNetworkLifecycleGate(
    // Issue #1080 — production injects `SystemClock.elapsedRealtime()` (counts
    // deep sleep). This System.nanoTime default is the pure-JVM unit-test
    // fallback only (the android.jar stub throws on SystemClock).
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var processForeground: Boolean = false
    private var foregroundResumePending: Boolean = false
    private var pendingTerminalNetworkChange: TerminalNetworkChange? = null
    private var backgroundCycleId: Long = 0L
    private var backgroundStartedMillis: Long? = null
    private var backgroundGraceMillis: Long = BACKGROUND_GRACE_MILLIS
    // Android can deliver default-network callbacks caused by the just-ended
    // background interval after ON_START. Keep suppressing callbacks for that
    // same cycle until the bounded attribution window expires.
    private var postResumeNetworkSuppression: PostResumeNetworkSuppression? = null

    fun onBackground(graceMillis: Long = BACKGROUND_GRACE_MILLIS) {
        backgroundCycleId += 1L
        processForeground = false
        foregroundResumePending = false
        backgroundStartedMillis = nowMillis()
        backgroundGraceMillis = graceMillis.coerceAtLeast(0L)
        postResumeNetworkSuppression = null
    }

    fun onForegroundResumeStarted() {
        processForeground = true
        foregroundResumePending = true
    }

    fun onForegroundResumeFinished(
        resumedWithinGrace: Boolean,
        hasLiveTerminalRuntime: Boolean,
    ): TerminalNetworkDecision {
        foregroundResumePending = false
        val pendingChange = pendingTerminalNetworkChange
        pendingTerminalNetworkChange = null
        val dispatchPending = shouldDispatchPendingTerminalNetworkChange(
            resumedWithinGrace = resumedWithinGrace,
            pendingChange = pendingChange,
            hasLiveTerminalRuntime = hasLiveTerminalRuntime,
        )
        val gateDiagnostics = foregroundResumeDiagnostics(
            pendingChange = pendingChange,
            resumedWithinGrace = resumedWithinGrace,
            hasLiveTerminalRuntime = hasLiveTerminalRuntime,
            dispatchPending = dispatchPending,
        )
        postResumeNetworkSuppression =
            if (resumedWithinGrace && hasLiveTerminalRuntime) {
                PostResumeNetworkSuppression(
                    backgroundCycleId = backgroundCycleId,
                    untilMillis = postResumeNetworkSuppressionUntilMillis(),
                )
            } else {
                null
            }
        return if (dispatchPending) {
            TerminalNetworkDecision.Dispatch(
                pendingChange!!.copy(deferredFromBackground = true),
                gateDiagnostics = gateDiagnostics,
            )
        } else {
            TerminalNetworkDecision.Suppress(
                pendingChange,
                gateDiagnostics = gateDiagnostics,
            )
        }
    }

    fun previewForegroundResumeFinished(
        resumedWithinGrace: Boolean,
        hasLiveTerminalRuntime: Boolean,
    ): TerminalNetworkGateDiagnostics {
        val pendingChange = pendingTerminalNetworkChange
        return foregroundResumeDiagnostics(
            pendingChange = pendingChange,
            resumedWithinGrace = resumedWithinGrace,
            hasLiveTerminalRuntime = hasLiveTerminalRuntime,
            dispatchPending = shouldDispatchPendingTerminalNetworkChange(
                resumedWithinGrace = resumedWithinGrace,
                pendingChange = pendingChange,
                hasLiveTerminalRuntime = hasLiveTerminalRuntime,
            ),
        )
    }

    fun onNetworkChange(
        change: TerminalNetworkChange,
        hasLiveTerminalRuntime: Boolean = true,
    ): TerminalNetworkDecision {
        val suppression = postResumeNetworkSuppression
        if (suppression != null) {
            val withinSuppressionWindow =
                nowMillis() <= suppression.untilMillis && hasLiveTerminalRuntime
            if (withinSuppressionWindow) {
                // Issue #1098 (item 5): the post-resume attribution window exists
                // ONLY to drop a STALE *validated-identity-change* callback that
                // Android queued during the just-ended background interval (the
                // #548 handoff path — see [postResumeNetworkSuppressionUntilMillis]).
                // A bare availability LOSS / RESTORE (`onLost` / airplane-mode
                // round-trip — the orthogonal #997 signal) is a CURRENT, meaningful
                // event, not a stale handoff. Swallowing it here left the lease
                // un-held during the loss and the restore-driven fast reconnect
                // never fired, so a real network blip never recovered the terminal.
                // Only ValidatedIdentityChange is suppressed in this window;
                // loss/restore fall through to the dispatch path below. The window
                // stays armed so a later stale validated-identity callback is still
                // suppressed.
                if (change.kind == TerminalNetworkChangeKind.ValidatedIdentityChange) {
                    return TerminalNetworkDecision.Suppress(
                        change,
                        gateDiagnostics = TerminalNetworkGateDiagnostics(
                            decision = "suppress",
                            reason = "post_resume_within_grace_live_runtime",
                            processForeground = processForeground,
                            foregroundResumePending = foregroundResumePending,
                            resumedWithinGrace = true,
                            hasLiveTerminalRuntime = true,
                            backgroundCycleId = suppression.backgroundCycleId,
                            pendingNetworkChange = true,
                            pendingNetworkClassification = change.networkClassification(),
                        ),
                    )
                }
            } else {
                postResumeNetworkSuppression = null
            }
        }

        return if (processForeground && !foregroundResumePending) {
            TerminalNetworkDecision.Dispatch(
                change,
                gateDiagnostics = TerminalNetworkGateDiagnostics(
                    decision = "dispatch",
                    reason = "foreground_active",
                    processForeground = processForeground,
                    foregroundResumePending = foregroundResumePending,
                    hasLiveTerminalRuntime = hasLiveTerminalRuntime,
                    backgroundCycleId = backgroundCycleId,
                    pendingNetworkChange = false,
                    pendingNetworkClassification = "none",
                ),
            )
        } else {
            pendingTerminalNetworkChange = change
            TerminalNetworkDecision.Defer(
                change,
                gateDiagnostics = TerminalNetworkGateDiagnostics(
                    decision = "defer",
                    reason = if (foregroundResumePending) {
                        "foreground_resume_pending"
                    } else {
                        "process_background"
                    },
                    processForeground = processForeground,
                    foregroundResumePending = foregroundResumePending,
                    hasLiveTerminalRuntime = hasLiveTerminalRuntime,
                    backgroundCycleId = backgroundCycleId,
                    pendingNetworkChange = true,
                    pendingNetworkClassification = change.networkClassification(),
                ),
            )
        }
    }

    private fun foregroundResumeDiagnostics(
        pendingChange: TerminalNetworkChange?,
        resumedWithinGrace: Boolean,
        hasLiveTerminalRuntime: Boolean,
        dispatchPending: Boolean,
    ): TerminalNetworkGateDiagnostics =
        TerminalNetworkGateDiagnostics(
            decision = if (dispatchPending) "dispatch" else "suppress",
            reason = if (dispatchPending) {
                if (resumedWithinGrace) {
                    "within_grace_no_live_runtime_real_handoff"
                } else {
                    "post_grace_foreground"
                }
            } else {
                when {
                    pendingChange == null -> "no_pending_change"
                    resumedWithinGrace && hasLiveTerminalRuntime -> "within_grace_live_runtime"
                    else -> "non_real_validated_change"
                }
            },
            processForeground = processForeground,
            foregroundResumePending = false,
            resumedWithinGrace = resumedWithinGrace,
            hasLiveTerminalRuntime = hasLiveTerminalRuntime,
            backgroundCycleId = backgroundCycleId,
            pendingNetworkChange = pendingChange != null,
            pendingNetworkClassification = pendingChange.pendingNetworkClassification(),
        )

    private fun postResumeNetworkSuppressionUntilMillis(): Long {
        val now = nowMillis()
        val originalGraceDeadline = backgroundStartedMillis
            ?.let { started -> started + backgroundGraceMillis }
            ?: now
        return maxOf(
            now + POST_RESUME_NETWORK_ATTRIBUTION_MILLIS,
            originalGraceDeadline,
        )
    }

    private data class PostResumeNetworkSuppression(
        val backgroundCycleId: Long,
        val untilMillis: Long,
    )
}

internal data class TerminalNetworkGateDiagnostics(
    val decision: String,
    val reason: String,
    val processForeground: Boolean,
    val foregroundResumePending: Boolean,
    val resumedWithinGrace: Boolean? = null,
    val hasLiveTerminalRuntime: Boolean? = null,
    val backgroundCycleId: Long? = null,
    val pendingNetworkChange: Boolean? = null,
    val pendingNetworkClassification: String? = null,
) {
    fun diagnosticFields(): List<Pair<String, Any?>> =
        buildList {
            add("gateDecision" to decision)
            add("gateReason" to reason)
            add("reconnectOutcome" to decision.reconnectOutcome())
            add("processForeground" to processForeground)
            add("foregroundResumePending" to foregroundResumePending)
            resumedWithinGrace?.let { add("resumedWithinGrace" to it) }
            hasLiveTerminalRuntime?.let { add("hasLiveTerminalRuntime" to it) }
            backgroundCycleId?.let { add("backgroundCycleId" to it) }
            pendingNetworkChange?.let { add("pendingNetworkChange" to it) }
            pendingNetworkClassification?.let { add("pendingNetworkClassification" to it) }
        }
}

private fun String.reconnectOutcome(): String =
    when (this) {
        "dispatch" -> "scheduled"
        "defer" -> "deferred"
        "suppress" -> "suppressed"
        else -> this
    }

private fun TerminalNetworkChange?.pendingNetworkClassification(): String =
    this?.networkClassification() ?: "none"

private fun TerminalNetworkChange.networkClassification(): String =
    when (kind) {
        // Issue #997: the orthogonal bare-loss / restore signal — classified by
        // kind, not by validated-identity equality (which would mislabel them).
        TerminalNetworkChangeKind.NetworkLost -> "network_lost"
        TerminalNetworkChangeKind.NetworkRestored -> "network_restored"
        TerminalNetworkChangeKind.ValidatedIdentityChange ->
            if (
                previousValidated != null &&
                !previousValidated.hasSameNetworkIdentityAs(current)
            ) {
                "real_validated_identity_change"
            } else {
                "non_real_validated_change"
            }
    }

internal sealed interface TerminalNetworkDecision {
    val gateDiagnostics: TerminalNetworkGateDiagnostics

    data class Dispatch(
        val change: TerminalNetworkChange,
        override val gateDiagnostics: TerminalNetworkGateDiagnostics,
    ) : TerminalNetworkDecision

    data class Defer(
        val change: TerminalNetworkChange,
        override val gateDiagnostics: TerminalNetworkGateDiagnostics,
    ) : TerminalNetworkDecision

    data class Suppress(
        val change: TerminalNetworkChange?,
        override val gateDiagnostics: TerminalNetworkGateDiagnostics,
    ) : TerminalNetworkDecision
}

/**
 * Issue #235: logcat tag for the application-level tmux lifecycle
 * fanout. Kept short to satisfy `Log.isLoggable`'s 23-character cap on
 * older Android versions.
 */
private const val APP_LIFECYCLE_TAG: String = "PsAppTmuxLifecycle"

private const val TERMINAL_NETWORK_TAG: String = "PsAppTerminalNet"

/**
 * Bounded attribution window for Android default-network callbacks that were
 * plausibly queued by the just-ended background interval but reach the app
 * after the foreground grace decision has completed. Kept longer than the
 * observed >1s callback lag, but finite so later foreground handoffs dispatch.
 */
internal const val POST_RESUME_NETWORK_ATTRIBUTION_MILLIS: Long = 5_000L

/**
 * Issue #450: logcat tag for the bounded background grace-window state
 * machine. Kept short for the 23-character `Log.isLoggable` cap.
 */
internal const val GRACE_LIFECYCLE_TAG: String = "PsAppBgGrace"

/**
 * Issue #450: default bounded grace window before the terminal SSH/tmux
 * connection is torn down after the app backgrounds. The Settings →
 * Terminal preference can extend or shorten this for future background
 * cycles; the default preserves the original hard-coded 60s behaviour.
 */
internal const val BACKGROUND_GRACE_MILLIS: Long = AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS

/**
 * Test-only override for connected lifecycle proofs that need a short grace
 * window without adding unsupported values to user-facing Settings.
 */
internal object BackgroundGraceTestOverride {
    @Volatile
    private var overrideMillis: Long? = null

    fun setForTest(millis: Long?) {
        require(millis == null || millis >= 0L) { "background grace override must be non-negative" }
        overrideMillis = millis
    }

    fun currentOr(defaultMillis: Long): Long = overrideMillis ?: defaultMillis
}

/**
 * Issue #450: bounded grace-window state machine for the terminal
 * connection teardown.
 *
 * On [onBackground] (process `ON_STOP`) it starts a single-shot
 * [graceMillis] timer instead of tearing the connection down
 * immediately. If [onForeground] (process `ON_START`) arrives before the
 * timer fires, the timer is cancelled and [onForeground]'s callback is
 * invoked with `resumedWithinGrace = true` — the live connection was
 * never touched, so the user resumes instantly with no reconnect. If the
 * timer elapses while still backgrounded, [onGraceElapsed] runs the
 * existing teardown and a later [onForeground] is invoked with
 * `resumedWithinGrace = false` so the caller can drive the normal
 * reattach.
 *
 * The controller deliberately holds NO repeating timer, no
 * `WorkManager`/`AlarmManager`, and no wakelock — it is a one-shot,
 * self-cancelling [delay] coroutine. Once the teardown has fired (or
 * before any background event) it owns no scheduled work at all, so it
 * cannot keep the process awake. This is the bounded relaxation of D21
 * sanctioned for issue #450.
 *
 * All entry points run on the controller's single-threaded
 * [Dispatchers.Main.immediate] scope, so the [onBackground]/[onForeground]
 * ordering is deterministic; callers do not need their own locking.
 */
internal class BackgroundGraceController(
    private val scope: CoroutineScope,
    private var graceMillis: Long,
    private val onGraceElapsed: suspend () -> Unit,
    private val onForeground: suspend (resumedWithinGrace: Boolean) -> Unit,
    // Issue #1080 — production injects `SystemClock.elapsedRealtime()` (counts
    // deep sleep) so the within-grace/beyond-grace window math reflects real
    // elapsed time across a Doze gap. This System.nanoTime default is the
    // pure-JVM unit-test fallback only (the android.jar stub throws on
    // SystemClock); the deterministic tests inject their own virtual clock.
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val foregroundDiagnosticFields: (resumedWithinGrace: Boolean) -> List<Pair<String, Any?>> = {
        emptyList()
    },
) {
    /** The in-flight grace timer, if the app is currently backgrounded. */
    private var graceJob: Job? = null
    private var backgroundCycleId: Long = 0L
    private var backgroundStartedAtMs: Long = 0L
    private var backgroundDeadlineAtMs: Long = 0L
    private var backgroundGraceMillisForCycle: Long = graceMillis.coerceAtLeast(0L)
    private var backgrounded: Boolean = false

    /**
     * True once the grace-elapsed action has been dispatched for the current
     * background cycle. Issue #1123: the grace-elapsed action now ALWAYS runs the
     * full teardown (the old indefinite session-hold "preserve past grace" is gone),
     * so this is a one-shot guard against a duplicate dispatch within a cycle. Reset
     * on the next [onBackground].
     */
    private var teardownFired: Boolean = false

    /**
     * Update the grace window used by the next [onBackground] call. An
     * already-running timer keeps its original deadline so changing Settings
     * cannot unexpectedly prolong a backgrounded connection.
     */
    fun setGraceMillis(millis: Long) {
        val normalizedMillis = millis.coerceAtLeast(0L)
        if (backgrounded || graceMillis == normalizedMillis) return
        graceMillis = normalizedMillis
    }

    fun onBackground() {
        // A second ON_STOP without an intervening ON_START should not
        // restart the window — keep the original deadline.
        if (graceJob?.isActive == true) return
        if (backgrounded && teardownFired) return
        backgroundCycleId += 1L
        backgroundStartedAtMs = nowMillis()
        backgroundGraceMillisForCycle = graceMillis.coerceAtLeast(0L)
        backgroundDeadlineAtMs = backgroundStartedAtMs + backgroundGraceMillisForCycle
        backgrounded = true
        teardownFired = false
        Log.i(GRACE_LIFECYCLE_TAG, "grace-window-start millis=$backgroundGraceMillisForCycle")
        DiagnosticEvents.record(
            "app",
            "background_grace_start",
            "millis" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
            "source" to "process_lifecycle",
            "trigger" to "on_stop",
        )
        ReconnectCauseTrail.record(
            stage = "background_grace",
            outcome = "start",
            cause = "process_background",
            trigger = "on_stop",
            "graceMs" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
        )
        graceJob = scope.launch {
            delay(backgroundGraceMillisForCycle)
            dispatchGraceElapsedIfNeeded(source = "timer", trigger = "grace_timeout")
        }
    }

    fun onForeground() {
        val pending = graceJob
        graceJob = null
        val foregroundAtMs = nowMillis()
        val hadBackgroundCycle = backgroundCycleId > 0L
        val resumedWithinGrace = backgrounded && !teardownFired && foregroundAtMs < backgroundDeadlineAtMs
        val elapsedMs = elapsedMs(foregroundAtMs)
        backgrounded = false
        if (resumedWithinGrace) {
            pending?.cancel()
        } else if (!teardownFired && pending?.isActive == true) {
            pending.cancel()
        }
        scope.launch {
            if (!resumedWithinGrace) {
                if (!teardownFired && hadBackgroundCycle && foregroundAtMs >= backgroundDeadlineAtMs) {
                    dispatchGraceElapsedIfNeeded(
                        source = "process_lifecycle",
                        trigger = "deadline_before_on_start",
                    )
                } else {
                    pending?.join()
                }
            }
            recordForeground(resumedWithinGrace = resumedWithinGrace, elapsedMs = elapsedMs)
            onForeground(resumedWithinGrace)
        }
    }

    private suspend fun dispatchGraceElapsedIfNeeded(source: String, trigger: String): Boolean {
        if (teardownFired) return false
        teardownFired = true
        val now = nowMillis()
        val elapsedMs = elapsedMs(now)
        Log.i(GRACE_LIFECYCLE_TAG, "grace-window-deadline-elapsed")
        DiagnosticEvents.record(
            "app",
            "background_grace_elapsed",
            "deadlineElapsed" to true,
            "elapsedMs" to elapsedMs,
            "millis" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
            "source" to source,
            "trigger" to trigger,
        )
        ReconnectCauseTrail.record(
            stage = "background_grace",
            outcome = "elapsed",
            cause = "grace_deadline",
            trigger = trigger,
            "elapsedMs" to elapsedMs,
            "graceMs" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
        )
        onGraceElapsed()
        return true
    }

    private fun recordForeground(resumedWithinGrace: Boolean, elapsedMs: Long) {
        Log.i(
            GRACE_LIFECYCLE_TAG,
            "grace-window-foreground resumedWithinGrace=$resumedWithinGrace elapsedMs=$elapsedMs",
        )
        val diagnosticFields = foregroundDiagnosticFields(resumedWithinGrace)
        DiagnosticEvents.record(
            "app",
            "background_grace_foreground",
            "resumedWithinGrace" to resumedWithinGrace,
            "withinGrace" to resumedWithinGrace,
            "elapsedMs" to elapsedMs,
            "millis" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
            "source" to "process_lifecycle",
            "trigger" to "on_start",
            *diagnosticFields.toTypedArray(),
        )
        ReconnectCauseTrail.record(
            stage = "background_grace",
            outcome = if (resumedWithinGrace) "foreground_preserved" else "foreground_reattach_needed",
            cause = if (resumedWithinGrace) "within_grace" else "post_grace",
            trigger = "on_start",
            "withinGrace" to resumedWithinGrace,
            "elapsedMs" to elapsedMs,
            "graceMs" to backgroundGraceMillisForCycle,
            "deadlineMs" to backgroundDeadlineAtMs,
            "backgroundCycleId" to backgroundCycleId,
            *diagnosticFields.toTypedArray(),
        )
    }

    private fun elapsedMs(now: Long = nowMillis()): Long =
        (now - backgroundStartedAtMs).coerceAtLeast(0L)

    /** Test seam: true while the current background cycle is still inside its deadline. */
    internal fun isGracePendingForTest(): Boolean =
        backgrounded && !teardownFired && nowMillis() < backgroundDeadlineAtMs
}

internal class SshLeaseLifecycleDispatcher(
    scope: CoroutineScope,
    private val onProcessStopped: suspend () -> Unit,
    private val onProcessStarted: suspend () -> Unit,
) {
    private val events = Channel<SshLeaseLifecycleEvent>(capacity = Channel.UNLIMITED)
    private val worker: Job = scope.launch {
        for (event in events) {
            when (event) {
                SshLeaseLifecycleEvent.Stopped -> onProcessStopped()
                SshLeaseLifecycleEvent.Started -> onProcessStarted()
            }
        }
    }

    fun dispatch(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_STOP -> events.trySend(SshLeaseLifecycleEvent.Stopped).getOrThrow()
            Lifecycle.Event.ON_START -> events.trySend(SshLeaseLifecycleEvent.Started).getOrThrow()
            else -> Unit
        }
    }

    fun close() {
        events.close()
        worker.cancel()
    }

    private enum class SshLeaseLifecycleEvent {
        Stopped,
        Started,
    }
}
