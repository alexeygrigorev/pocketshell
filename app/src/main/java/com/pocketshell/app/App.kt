package com.pocketshell.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.core.ssh.SshLeaseManager
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
 * ([BACKGROUND_GRACE_MILLIS], 60 s) instead of firing immediately. A
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

    private var processForeground: Boolean = false
    private var pendingTerminalNetworkChange: TerminalNetworkChange? = null

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
            dispatchTmuxBackground()
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
            if (!resumedWithinGrace) {
                dispatchTmuxForeground()
            }
        },
    )

    private val terminalLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                processForeground = false
                backgroundGraceController.onBackground()
            }
            Lifecycle.Event.ON_START -> {
                processForeground = true
                backgroundGraceController.onForeground()
                drainPendingTerminalNetworkChange()
                terminalNetworkObserver.refresh("process-foreground")
            }
            else -> Unit
        }
    }

    override fun onCreate() {
        StartupTiming.mark("app-on-create-start")
        super.onCreate()
        CrashReporter.install(this)
        StartupTiming.mark("app-crash-reporter-installed")
        // No-background-work hook-up (issue #161 / D21). Attach the
        // ProcessLifecycleOwner observer before starting the loop so
        // the loop's `processStarted.first { it }` gate sees the
        // already-correct value rather than waiting for the first
        // ON_START event.
        usageScheduler.observeProcessLifecycle()
        StartupTiming.mark("usage-lifecycle-observed")
        usageScheduler.start()
        StartupTiming.mark("usage-scheduler-started")

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
                if (processForeground) {
                    dispatchTerminalNetworkChange(change)
                } else {
                    pendingTerminalNetworkChange = change
                    Log.i(
                        TERMINAL_NETWORK_TAG,
                        "terminal-network-change-deferred sequence=${change.sequence} " +
                            "reason=${change.reason}",
                    )
                }
            }
        }
        StartupTiming.mark("app-on-create-end")
    }

    private fun drainPendingTerminalNetworkChange() {
        val change = pendingTerminalNetworkChange ?: return
        pendingTerminalNetworkChange = null
        dispatchTerminalNetworkChange(change)
    }

    private fun dispatchTerminalNetworkChange(change: TerminalNetworkChange) {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        if (hooks.isEmpty()) return
        Log.i(
            TERMINAL_NETWORK_TAG,
            "terminal-network-change-fanout count=${hooks.size} " +
                "sequence=${change.sequence} reason=${change.reason} " +
                "previous=${change.previous.logValue} current=${change.current.logValue}",
        )
        for (hook in hooks) {
            terminalNetworkScope.launch {
                runCatching { hook.onNetworkChanged(change.reason) }
                    .onFailure { Log.w(TERMINAL_NETWORK_TAG, "terminal network hook failed", it) }
            }
        }
    }

    private fun dispatchTmuxBackground() {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        if (hooks.isEmpty()) return
        Log.i(
            APP_LIFECYCLE_TAG,
            "tmux-on-stop fanout count=${hooks.size}",
        )
        for (hook in hooks) {
            tmuxLifecycleScope.launch {
                runCatching { hook.onBackground() }
                    .onFailure { Log.w(APP_LIFECYCLE_TAG, "tmux background hook failed", it) }
            }
        }
    }

    private fun dispatchTmuxForeground() {
        val hooks = activeTmuxClients.lifecycleHooksSnapshot()
        if (hooks.isEmpty()) return
        Log.i(
            APP_LIFECYCLE_TAG,
            "tmux-on-start fanout count=${hooks.size}",
        )
        for (hook in hooks) {
            tmuxLifecycleScope.launch {
                runCatching { hook.onForeground() }
                    .onFailure { Log.w(APP_LIFECYCLE_TAG, "tmux foreground hook failed", it) }
            }
        }
    }
}

/**
 * Issue #235: logcat tag for the application-level tmux lifecycle
 * fanout. Kept short to satisfy `Log.isLoggable`'s 23-character cap on
 * older Android versions.
 */
private const val APP_LIFECYCLE_TAG: String = "PsAppTmuxLifecycle"

private const val TERMINAL_NETWORK_TAG: String = "PsAppTerminalNet"

/**
 * Issue #450: logcat tag for the bounded background grace-window state
 * machine. Kept short for the 23-character `Log.isLoggable` cap.
 */
internal const val GRACE_LIFECYCLE_TAG: String = "PsAppBgGrace"

/**
 * Issue #450: the single, easily-tunable bounded grace window before the
 * terminal SSH/tmux connection is torn down after the app backgrounds.
 *
 * The maintainer asked for "at least a minute" so a quick app-switch
 * (copying a snippet, glancing at another app) does not trigger a
 * reconnect on return. 60 s satisfies that while keeping the window
 * bounded — this is a delay of the existing teardown, not permanent
 * background work. Flip this one constant to retune.
 */
internal const val BACKGROUND_GRACE_MILLIS: Long = 60_000L

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
    private val graceMillis: Long,
    private val onGraceElapsed: suspend () -> Unit,
    private val onForeground: suspend (resumedWithinGrace: Boolean) -> Unit,
) {
    /** The in-flight grace timer, if the app is currently backgrounded. */
    private var graceJob: Job? = null

    /**
     * True once [onGraceElapsed] has actually run for the current
     * background cycle. Distinguishes a within-grace resume (connection
     * intact) from a post-grace resume (connection torn down, reattach
     * needed). Reset on the next [onBackground].
     */
    private var teardownFired: Boolean = false

    fun onBackground() {
        // A second ON_STOP without an intervening ON_START should not
        // restart the window — keep the original deadline.
        if (graceJob?.isActive == true) return
        teardownFired = false
        Log.i(GRACE_LIFECYCLE_TAG, "grace-window-start millis=$graceMillis")
        graceJob = scope.launch {
            delay(graceMillis)
            teardownFired = true
            Log.i(GRACE_LIFECYCLE_TAG, "grace-window-elapsed teardown")
            onGraceElapsed()
        }
    }

    fun onForeground() {
        val pending = graceJob
        graceJob = null
        val resumedWithinGrace = pending?.isActive == true && !teardownFired
        pending?.cancel()
        Log.i(
            GRACE_LIFECYCLE_TAG,
            "grace-window-foreground resumedWithinGrace=$resumedWithinGrace",
        )
        scope.launch { onForeground(resumedWithinGrace) }
    }

    /** Test seam: true while the grace timer is counting down. */
    internal fun isGracePendingForTest(): Boolean = graceJob?.isActive == true
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
