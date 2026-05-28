package com.pocketshell.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.usage.UsageScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * `quseInstalled != true`) `fetchOnce` is essentially a Room read and a
 * no-op snapshot update — cheap. The wire-up therefore costs nothing on
 * a fresh install and means the first time a user bootstraps quse on a
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
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var usageScheduler: UsageScheduler

    @Inject
    lateinit var activeTmuxClients: ActiveTmuxClients

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

    private val tmuxLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> dispatchTmuxBackground()
            Lifecycle.Event.ON_START -> dispatchTmuxForeground()
            else -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // No-background-work hook-up (issue #161 / D21). Attach the
        // ProcessLifecycleOwner observer before starting the loop so
        // the loop's `processStarted.first { it }` gate sees the
        // already-correct value rather than waiting for the first
        // ON_START event.
        usageScheduler.observeProcessLifecycle()
        usageScheduler.start()

        // Issue #235: auto-detach tmux `-CC` clients on lifecycle
        // background + reattach on foreground. The observer attaches
        // to [ProcessLifecycleOwner] (not the activity-level lifecycle
        // — rotation / dark-mode flips would thrash the detach on
        // every config change) so the journey only fires when ALL
        // PocketShell activities go background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(tmuxLifecycleObserver)
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
