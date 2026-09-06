package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.IdlingResourceTimeoutException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A wall-clock bound around a harness wait that has no bound of its own.
 *
 * ## What has to be bounded (issue #2479)
 *
 * Every journey in this suite polls for its oracle inside a bounded loop:
 *
 * ```
 * val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
 * while (SystemClock.elapsedRealtime() < deadline) {
 *     compose.waitForIdle()          // <- UNBOUNDED
 *     if (oracleSatisfied()) return
 *     SystemClock.sleep(POLL_MS)
 * }
 * throw AssertionError(...)           // with a screenshot and a diagnosis
 * ```
 *
 * The deadline is only as real as the calls inside the loop body, and
 * `ComposeTestRule.waitForIdle()` is not one of them. `waitForIdle()` is three
 * waits, and only the first is bounded on its own
 * (`AndroidComposeUiTestEnvironment`, compose-ui-test 1.8.1):
 *
 * ```
 * composeRootRegistry.waitForComposeRoots(...)  // bounded: 2s / 500ms latch
 * idlingStrategy.runUntilIdle()                 // = Espresso.onIdle()
 * waitForNextChoreographerFrame()               // <- UNBOUNDED, see below
 * ```
 *
 * `waitForNextChoreographerFrame` (`ComposeUiTest.android.kt:402`, bytecode
 * offsets 182-212) is:
 *
 * ```
 * root.postOnAnimation { root.post { frameReceived = true } }
 * while (!frameReceived) { idlingStrategy.runUntilIdle() }   // no deadline
 * ```
 *
 * — a spin on a REAL vsync callback. So `waitForIdle()` has a hard floor of one
 * Choreographer frame, and on a starved swiftshader emulator (issue #2549 run
 * #3 logged `app_time_stats avg=60012.00ms`, i.e. a 60-SECOND frame) that floor
 * is measured in minutes. That run parked a single `awaitIdle` for **961
 * seconds on a 15-second budget** and cost the lane 24 of its 52 tests.
 *
 * No amount of `IdlingPolicies` tuning fixes that by itself: each individual
 * `Espresso.onIdle()` inside the spin can be bounded, but Compose's loop simply
 * calls it again, and when nothing is busy `onIdle()` returns normally instead
 * of timing out. The bound has to make the NEXT `onIdle()` throw. That is what
 * [DeadlineTripwire] below does.
 *
 * ## Why it does NOT use a worker thread (issue #2549)
 *
 * The first version of this class ran `waitForIdle()` on a daemon worker and let
 * the caller walk away from it when the budget expired. That is not safe.
 *
 * `Espresso.onIdle()` called off the main thread posts a
 * `loopMainThreadUntilIdle` task to the main looper and blocks on its future.
 * That task runs `Interrogator.loopAndInterrogate`, which sets a thread-local
 * `interrogating` flag on the main thread and then *dispatches main-queue
 * messages itself*. Espresso's `UiController` is single-caller by contract: when
 * a second thread calls `onIdle()` while the first one's task is still
 * interrogating, its task is dispatched BY that interrogation loop, re-enters
 * `loopAndInterrogate`, and `checkSanity()` throws
 * `IllegalStateException: Already interrogating!` — surfacing on the second
 * caller as
 * `RuntimeException: ExecutionException: IllegalStateException: Already interrogating!`.
 *
 * Abandoning a parked `waitForIdle()` therefore leaves an interrogation running
 * on the main thread with nobody tracking it, and the next Espresso/Compose call
 * from ANY thread — a semantics read, a click, or the Compose rule's own
 * teardown `waitForIdle()` — blows up in the harness rather than on a product
 * assertion. That is issue #2549: three CI runs lost to a harness error in
 * `J03AttachAndTypeJourney`, the class that uses the largest budgets here.
 *
 * So there are two hard requirements, and both have to hold at once:
 *
 * > **1. Never return from a wait while a main-thread interrogation we started
 * >    is still outstanding.**
 * > **2. Always return within the budget.**
 *
 * ## How both hold
 *
 * [block] runs on the CALLER's thread, so there is only ever one thread inside
 * Espresso — requirement 1 is structural, not probabilistic. Requirement 2 is
 * enforced by making Espresso itself refuse to be idle once the budget is gone,
 * so the wait ends by THROWING out of the caller's own stack rather than by
 * being abandoned:
 *
 *  - **The deadline tripwire.** [DeadlineTripwire] is a real Espresso
 *    [IdlingResource], registered for the duration of the call, that reports
 *    idle until the budget expires and busy for ever after. Espresso re-polls a
 *    currently-idle resource on every pass
 *    (`IdlingResourceRegistry.allResourcesAreIdle`, offsets 47-64), so the first
 *    `Espresso.onIdle()` after the deadline sees a busy resource, enters
 *    `loopUntil` with `DYNAMIC_TASKS_HAVE_IDLED` unsignalled, hits the (now
 *    short) idling-resource timeout and throws [IdlingResourceTimeoutException]
 *    **from the main-thread task**, which the caller rethrows. Any loop that
 *    goes back through `onIdle()` — Compose's frame spin, Espresso's own
 *    `loopMainThreadUntilIdle` `do/while`, a journey's poll — is broken by it,
 *    and it is broken at a point where the main-thread task has already
 *    completed, so requirement 1 is untouched.
 *  - **Idling policies as the inner bound.** `IdlingPolicies` is lowered to the
 *    budget for the call and restored after, so a single `loopUntil` cannot
 *    outlive the budget either; on trip they drop to [TRIPPED_TIMEOUT_MS] so the
 *    tripwire converts into a throw promptly rather than after another full
 *    budget.
 *  - **The deadline nudge.** `Interrogator` reaches its deadline check only when
 *    it dispatches a message, so a queue that drains while something is still
 *    busy parks `MessageQueue.next()` with nothing to wake it. After the
 *    deadline (and only then) the watchdog posts a no-op to the main looper
 *    every [NUDGE_PERIOD_MS] so the check always runs.
 *
 * Nothing above runs before the deadline: the tripwire reports idle, the
 * policies are a plain per-call override, and no message is posted. A healthy
 * wait is the wait it always was.
 *
 * ## The one thing still not bounded, stated plainly
 *
 * If the main thread wedges inside a SINGLE message dispatch — an app-side
 * deadlock — nothing here helps: the tripwire cannot be observed, because
 * observing it requires Espresso to run on the main thread. That case is
 * unbounded by construction and no caller-owned mechanism can change it; the
 * old worker thread only appeared to bound it, by returning a `false` the caller
 * could not act on without corrupting the interrogator. Every wedge that keeps
 * the main thread dispatching — which is every wedge #2479 and #2549 actually
 * observed, including the 961-second frame spin — is bounded.
 *
 * ## This class owns process-global state
 *
 * [IdlingPolicies] is static and [IdlingRegistry] is a process singleton, while
 * [run] synchronises per instance. That is safe only because instrumentation
 * runs one test thread at a time; two concurrent [run] calls would interleave
 * their save/restore. Do not call this from a worker — which is also
 * requirement 1.
 */
class BoundedWait(private val name: String) {

    private var stuckLabel: String? = null
    private var stuckSince: Long = 0L
    private var timeouts: Int = 0

    /**
     * Runs [block] on the calling thread, bounded to [budgetMs].
     *
     * @return `true` when [block] completed, `false` when Espresso gave up
     *   because the app never went idle inside the budget. Anything else
     *   [block] throws is rethrown unchanged, so a real assertion failure
     *   inside a wrapped wait is not swallowed by the bound.
     */
    @Synchronized
    fun run(label: String, budgetMs: Long, block: () -> Unit): Boolean {
        val master = IdlingPolicies.getMasterIdlingPolicy()
        val dynamic = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
        val tripwire = DeadlineTripwire(name)
        IdlingRegistry.getInstance().register(tripwire)
        IdlingPolicies.setMasterPolicyTimeout(budgetMs, TimeUnit.MILLISECONDS)
        IdlingPolicies.setIdlingResourceTimeout(budgetMs, TimeUnit.MILLISECONDS)
        val watchdog = DeadlineWatchdog(name, budgetMs, tripwire).also { it.start() }
        return try {
            block()
            true
        } catch (failure: Throwable) {
            if (!isNotIdle(failure)) throw failure
            timeouts += 1
            if (stuckLabel == null) {
                stuckLabel = label
                stuckSince = SystemClock.elapsedRealtime()
            }
            println(
                "BOUNDED_WAIT_TIMEOUT $name: `$label` did not go idle within ${budgetMs}ms " +
                    "(see issues #2479/#2549). Espresso ended its own interrogation and threw " +
                    "${failure.javaClass.simpleName}, so the caller's deadline can still fire.",
            )
            false
        } finally {
            // Order matters: the watchdog also writes IdlingPolicies, so it has
            // to be stopped and joined before the restore, and the tripwire has
            // to leave the registry before any later wait can observe it.
            watchdog.stop()
            IdlingRegistry.getInstance().unregister(tripwire)
            IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
            IdlingPolicies.setIdlingResourceTimeout(dynamic.idleTimeout, dynamic.idleTimeoutUnit)
        }
    }

    /** True once any wait has blown its budget in this test. */
    @Synchronized
    fun isStuck(): Boolean = stuckLabel != null

    /**
     * A sentence for a failure message, or `""` when nothing ever timed out.
     *
     * Worth carrying into every assertion message in a journey: "the terminal
     * never laid out portrait" reads like a layout bug, and "…and by the way
     * Compose has not gone idle for 58 seconds" is a completely different
     * investigation.
     */
    @Synchronized
    fun stuckDiagnosis(): String {
        val label = stuckLabel ?: return ""
        val elapsed = SystemClock.elapsedRealtime() - stuckSince
        return "NOTE (#2479): `$label` blew its wait budget ${timeouts}x, first " +
            "${elapsed}ms ago. Espresso was made non-idle so the sync would throw " +
            "rather than consume this test's deadline."
    }

    /**
     * Reports idle until [budgetMs] have passed, and busy from then on.
     *
     * The whole bound rests on this: an `Espresso.onIdle()` with a busy resource
     * registered cannot return normally, it can only time out and throw. Espresso
     * re-polls `isIdleNow()` on every pass for a resource it currently believes
     * idle, so no callback is needed to make the flip visible.
     *
     * The name carries a per-instance counter because Espresso's
     * `IdlingResourceRegistry` refuses a duplicate name and only logs about it —
     * a collision would silently disable the bound rather than fail.
     */
    private class DeadlineTripwire(owner: String) : IdlingResource {

        private val resourceName = "pocketshell-bounded-wait-$owner-${COUNTER.incrementAndGet()}"

        @Volatile
        private var tripped = false

        override fun getName(): String = resourceName

        override fun isIdleNow(): Boolean = !tripped

        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback) {
            // Deliberately ignored. This resource never transitions BACK to idle,
            // so there is nothing to announce; Espresso's timeout is the exit.
        }

        fun trip() {
            tripped = true
        }

        companion object {
            val COUNTER = AtomicInteger(0)
        }
    }

    /**
     * Trips [tripwire] once the budget is gone, then keeps the main looper
     * waking up so Espresso notices.
     *
     * A plain daemon thread rather than anything cleverer: it never touches the
     * calling thread, never interrupts anything and never calls into Espresso's
     * `UiController`, so it cannot violate requirement 1. All it does is flip a
     * boolean, lower two static timeouts and post no-op messages.
     */
    private class DeadlineWatchdog(
        private val owner: String,
        private val budgetMs: Long,
        private val tripwire: DeadlineTripwire,
    ) {

        private val handler = Handler(Looper.getMainLooper())

        @Volatile
        private var running = true

        private val nudge = Runnable { }

        private val thread = Thread({ watch() }, "bounded-wait-deadline-$owner").apply {
            isDaemon = true
        }

        fun start() = thread.start()

        fun stop() {
            running = false
            thread.interrupt()
            thread.join(JOIN_MS)
            handler.removeCallbacks(nudge)
        }

        private fun watch() {
            if (!sleepFor(budgetMs)) return
            tripwire.trip()
            IdlingPolicies.setMasterPolicyTimeout(TRIPPED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            IdlingPolicies.setIdlingResourceTimeout(TRIPPED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            while (running) {
                // Wakes a `MessageQueue.next()` parked on an empty queue so the
                // Interrogator reaches its deadline check. `stop()` may race one
                // already-posted no-op past `removeCallbacks`; that is harmless
                // because it is an empty Runnable, and because a message due
                // 100ms out is well outside `Interrogator.LOOKAHEAD_MILLIS` (15)
                // and so is not even counted as queue activity.
                handler.post(nudge)
                if (!sleepFor(NUDGE_PERIOD_MS)) return
            }
        }

        /** @return `false` when [stop] cut the sleep short. */
        private fun sleepFor(millis: Long): Boolean =
            try {
                Thread.sleep(millis)
                running
            } catch (interrupted: InterruptedException) {
                false
            }

        private companion object {
            const val JOIN_MS = 5_000L
        }
    }

    private companion object {

        /**
         * How long Espresso may keep trying once the tripwire has been trodden.
         *
         * Small, because at this point the answer is already known — it only
         * has to be long enough for `loopUntil` to enter, dispatch, and report
         * the timeout rather than to race with its own setup.
         */
        const val TRIPPED_TIMEOUT_MS = 200L

        /** How often the nudge re-posts once the budget has already expired. */
        const val NUDGE_PERIOD_MS = 100L

        /**
         * True when [failure] (or anything it wraps) is Espresso/Compose saying
         * "the app never went idle", as opposed to a real failure from `block`.
         */
        fun isNotIdle(failure: Throwable): Boolean {
            var cause: Throwable? = failure
            val seen = HashSet<Throwable>()
            while (cause != null && seen.add(cause)) {
                if (cause is AppNotIdleException ||
                    cause is IdlingResourceTimeoutException ||
                    cause is ComposeNotIdleException
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }
    }
}

/**
 * The bounded form of [ComposeTestRule.waitForIdle], one budget per rule.
 *
 * Keyed on the rule instance (identity, via a [WeakHashMap]) so the "a wait blew
 * its budget" state resets automatically between tests: JUnit builds a new test
 * class instance — and therefore a new rule — per method, and a wedge in one
 * method must not carry its diagnosis into the next one.
 */
object ComposeIdle {

    /**
     * How long ONE idle sync may take before Espresso is made non-idle.
     *
     * Two orders of magnitude above a healthy `waitForIdle()` (milliseconds,
     * even on a contended emulator with an IME animation in flight) and well
     * under the 60-second deadline every journey poll loop carries, so a
     * budget expiry leaves the loop most of its own budget to fail properly in.
     */
    const val BUDGET_MS: Long = 15_000L

    private val waits = WeakHashMap<ComposeTestRule, BoundedWait>()

    @Synchronized
    private fun waitFor(rule: ComposeTestRule): BoundedWait =
        waits.getOrPut(rule) { BoundedWait("compose-idle") }

    fun await(rule: ComposeTestRule, label: String, budgetMs: Long = BUDGET_MS): Boolean =
        waitFor(rule).run(label, budgetMs) { rule.waitForIdle() }

    fun diagnosis(rule: ComposeTestRule): String = waitFor(rule).stuckDiagnosis()
}

/**
 * `compose.waitForIdle()` with a deadline.
 *
 * Drop-in for every `waitForIdle()` call in this suite: identical behaviour on a
 * healthy run (it returns as soon as Compose is idle), and a `false` return
 * instead of an unbounded park when it is not. [label] names the wait in the
 * timeout log line and in [idleWedgeNote].
 */
fun ComposeTestRule.awaitIdle(
    label: String,
    budgetMs: Long = ComposeIdle.BUDGET_MS,
): Boolean = ComposeIdle.await(this, label, budgetMs)

/**
 * `"\n" + diagnosis` when an idle wait has blown its budget in this test, `""`
 * otherwise — ready to append to an assertion message.
 */
fun ComposeTestRule.idleWedgeNote(): String =
    ComposeIdle.diagnosis(this).let { if (it.isEmpty()) "" else "\n$it" }
