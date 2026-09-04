package com.pocketshell.next.terminal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.core.transport.GraceHandle
import com.pocketshell.next.connect.ConnectionsRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts and stops the grace foreground service. The Android implementation is
 * [AndroidGraceServiceControl]; the seam exists so [GraceCoordinator]'s policy —
 * which is the whole of D21 — is unit-testable without a `Context`, a
 * `ServiceController` or Robolectric.
 */
interface GraceServiceControl {

    /**
     * Brings the service up, showing a count-down to [deadlineMs] (wall-clock
     * epoch millis). Called at most once per background window.
     */
    fun start(deadlineMs: Long)

    /** Takes the service (and with it the wake lock and the notification) down. Idempotent. */
    fun stop()
}

/**
 * The WHOLE background policy (rewrite task U-8, plan §C.4, decision D21).
 *
 * Leaving the app arms ONE bounded delayed close per live connection and shows a
 * count-down notification; coming back cancels them. That is all it is, and the
 * shortness is the design: the pre-rewrite client answered the same question
 * with a service controller, a snapshot type, a phase enum, a debounce, an
 * activity observer and a diagnostics trail, and the thing users actually felt
 * was one bit — "did my session survive the app switch".
 *
 * ## What runs while backgrounded
 *
 * Exactly two timers, both bounded by [graceMs] and both cancelled on return:
 * the transport's own delayed close ([com.pocketshell.core.transport.HostConnection.scheduleGraceClose],
 * task T-5) and this class's expiry job, whose ONLY job is to take the service
 * down at the same instant so no wake lock outlives the connection it was held
 * for. No polling, no keep-alive, no reconnect ladder — [SessionViewModel] gates
 * every rung of that on [ForegroundSignal] for the same reason.
 *
 * ## Why the FGS start is driven off the ACTIVITY boundary
 *
 * §C.4 specifies a [ProcessLifecycleOwner]-registered [DefaultLifecycleObserver],
 * and that is what this is — but `ProcessLifecycleOwner` dispatches `ON_STOP`
 * from a single 700 ms-delayed runnable, i.e. ~700 ms INTO the background, past
 * the point where Android 12+ still allows `startForegroundService()`. The old
 * client shipped exactly that and paid for it (issue #1595: the start was
 * rejected with `ForegroundServiceStartNotAllowedException`, the hold never came
 * up, and the OS tore the socket down ~4.4 s after every background — so every
 * foreground return was a full redial instead of the silent ride-through D21
 * promises). So this class ALSO registers as
 * [Application.ActivityLifecycleCallbacks] and treats "the started-activity
 * count reached zero" as the same event. [enterBackground] is idempotent, so
 * whichever signal arrives first wins and the other is a no-op; the
 * `ProcessLifecycleOwner` observer stays as the §C.4 shape and as the backstop
 * for a process with no Activity of ours on top.
 *
 * A configuration change (rotation, dark-mode flip) stops and recreates the
 * Activity without being a background at all, which is why
 * [Activity.isChangingConfigurations] is checked — `ProcessLifecycleOwner`'s
 * 700 ms debounce exists for the same case, and dropping the guard here would
 * flash a notification and arm a close on every rotation.
 *
 * ## Nothing happens when there is nothing to hold
 *
 * With no live connection there is no service, no notification and no wake lock:
 * a user who never opened a host must not find an ongoing notification in their
 * tray because they backgrounded the app.
 */
class GraceCoordinator(
    private val connections: ConnectionsRegistry,
    private val service: GraceServiceControl,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val graceMs: Long = DEFAULT_GRACE_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    /**
     * Supervisor so a thrown expiry job can never kill the scope and with it
     * every later background window.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val lock = Any()

    /** True between [enterBackground] and whichever of return-or-expiry ends it. */
    private var armed = false

    /** The pending closes armed for this background window. Empty when not [armed]. */
    private var handles: List<GraceHandle> = emptyList()

    /** The one timer that takes the service down when the window ends. */
    private var expiry: Job? = null

    /** Number of STARTED activities. Zero means the app is genuinely backgrounded. */
    private var startedActivities = 0

    private val registered = AtomicBoolean(false)

    /** For tests and diagnostics: is a background window open right now? */
    val isHolding: Boolean get() = synchronized(lock) { armed }

    /**
     * Wires this coordinator to the process lifecycle and to [application]'s
     * activity lifecycle. Idempotent, so the single Activity may call it from
     * every `onCreate` without stacking observers.
     *
     * Posted to the main thread because `ProcessLifecycleOwner`'s registry is
     * main-thread-only and a `@Singleton` is created on whichever thread first
     * injects it (the same reason [ProcessForegroundSignal] posts).
     *
     * Also unregisters whichever OTHER [GraceCoordinator] instance was
     * previously the process's active one — see [activeInstance] for why that
     * is load-bearing rather than defensive ceremony (issue #2477).
     */
    fun register(application: Application) {
        if (!registered.compareAndSet(false, true)) return
        val wire = Runnable {
            val previous = activeInstance.getAndSet(this)
            if (previous != null && previous !== this) {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(previous)
                application.unregisterActivityLifecycleCallbacks(previous)
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            application.registerActivityLifecycleCallbacks(this)
        }
        if (Looper.myLooper() === Looper.getMainLooper()) {
            wire.run()
        } else {
            Handler(Looper.getMainLooper()).post(wire)
        }
    }

    // --- the two transitions -------------------------------------------------

    /**
     * The app went away: arm one bounded close per live connection, put the
     * count-down on screen, and start the timer that will take it down again.
     *
     * Idempotent — see the class doc for why it is called from two places.
     */
    fun enterBackground(): Unit = synchronized(lock) {
        if (armed) return
        val live = connections.liveConnections()
        // Nothing to hold: no service, no notification, no wake lock.
        if (live.isEmpty()) return
        armed = true
        handles = live.map { it.scheduleGraceClose(graceMs) }
        // Arming, showing and timing the window happen under ONE lock so a
        // foreground return landing mid-sequence cannot cancel a close that has
        // not been armed yet, or stop a service that is started a line later.
        service.start(clock() + graceMs)
        expiry = scope.launch {
            delay(graceMs)
            onGraceExpired()
        }
    }

    /**
     * The app came back inside the window: cancel every pending close and take
     * the service down. After this returns no timer this class armed may still
     * fire — that is the D21/#1123 contract the whole feature exists for, and it
     * is what keeps a return-within-grace free of a reconnect banner.
     */
    fun enterForeground(): Unit = synchronized(lock) {
        if (!armed) return
        armed = false
        expiry?.cancel()
        expiry = null
        handles.forEach { it.cancel() }
        handles = emptyList()
        service.stop()
    }

    /**
     * The window elapsed. The transport closes itself (task T-5 owns that
     * timer); all that is left here is releasing the wake lock and the
     * notification, so an expired grace leaves NOTHING alive.
     */
    private fun onGraceExpired(): Unit = synchronized(lock) {
        if (!armed) return
        armed = false
        handles = emptyList()
        expiry = null
        service.stop()
    }

    // --- ProcessLifecycleOwner (plan §C.4) -----------------------------------

    override fun onStop(owner: LifecycleOwner) = enterBackground()

    override fun onStart(owner: LifecycleOwner) = enterForeground()

    // --- the foreground-eligible activity boundary (issue #1595's lesson) ----

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = synchronized(lock) {
            val wasZero = startedActivities == 0
            startedActivities += 1
            wasZero
        }
        if (wasBackground) enterForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        // Decremented on EVERY stop so the count stays balanced across an
        // activity->activity transition and across a configuration-change
        // recreate (old.onStop then new.onStart).
        val nowBackground = synchronized(lock) {
            if (startedActivities > 0) startedActivities -= 1
            startedActivities == 0
        }
        if (nowBackground && !activity.isChangingConfigurations) enterBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {

        /**
         * The process's currently-active [GraceCoordinator] — the ONLY instance
         * that may be wired to [Application.ActivityLifecycleCallbacks] and
         * [ProcessLifecycleOwner] at any moment. Static/companion-scoped
         * DELIBERATELY, so it tracks across every instance regardless of which
         * Hilt component built it (issue #2477).
         *
         * A single Android process is meant to construct exactly one Hilt
         * `@Singleton` [GraceCoordinator] for its whole life, and [register]'s
         * own per-instance idempotence (`registered`) was written for that
         * world. That invariant does NOT hold in every environment this class
         * runs in: Hilt's Android test harness deliberately builds a FRESH
         * `SingletonComponent` — and therefore a fresh [GraceCoordinator] and a
         * fresh [ConnectionsRegistry] — for every `@HiltAndroidTest` method,
         * while every one of those components sits on top of the SAME real,
         * process-wide [Application] instance (`am instrument` never restarts
         * the process between test methods). Without this guard, EVERY
         * superseded test method's [GraceCoordinator] stayed permanently
         * registered as an activity-lifecycle observer — each holding its own
         * snapshot of whichever [ConnectionsRegistry] it was built with — and
         * kept independently reacting to every LATER test's Activity
         * transitions, capable of re-arming the ONE shared, OS-level grace
         * notification for a connection a later test's own `closeAll()` can
         * never reach (it only resolves the CURRENT Hilt graph). That is
         * exactly what stranded
         * `J06BackgroundGraceReturnJourney.backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification`
         * on a full, unfiltered suite run: J05's own leftover coordinator,
         * still alive and still registered, rearmed the shared notification
         * for J05's own never-closed connection while J06's test had opened
         * none of its own. [register] now unregisters whichever instance this
         * one is replacing, so at most one [GraceCoordinator] can ever answer
         * a lifecycle callback — true by construction, not by assuming a
         * component-recreation environment never happens.
         */
        private val activeInstance = AtomicReference<GraceCoordinator?>(null)

        /**
         * The ONE grace default (D21, #1159): 90 seconds. Long enough to answer
         * a message, check a calendar or paste something in from another app;
         * short enough that a phone left in a pocket is not holding an SSH
         * transport and a wake lock. There is deliberately no setting for it —
         * plan §U-8 non-goal.
         */
        const val DEFAULT_GRACE_MS: Long = 90_000L
    }
}
