package com.pocketshell.next.connect

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.WeakHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A wall-clock bound around a harness wait that has no bound of its own.
 *
 * ## Why this exists (issue #2479)
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
 * `ComposeTestRule.waitForIdle()` is not one of them: it blocks until Compose
 * agrees the app has settled, with no timeout of its own. When Compose's idling
 * machinery never agrees — as it did not, twice on CI and repeatedly locally,
 * after the second rotation in `J03AttachAndTypeJourney` — the "60 second"
 * deadline becomes unreachable and the FIRST iteration of the loop consumes the
 * whole job.
 *
 * The cost of that is much larger than one red test. The instrumentation
 * process never exits, Gradle is killed by the job's step timeout 45 minutes
 * later, and no `TEST-*.xml` is written AT ALL — so the fifteen journeys that
 * genuinely passed before the wedge produce zero evidence and
 * `scripts/check-app2-lane-execution.py` correctly reports the whole lane as
 * unproven. One intermittent wait turns a suite into a coin flip with no
 * salvage.
 *
 * ## What this does about it
 *
 * The blocking call runs on a worker thread and the caller waits on a
 * [Future] with an explicit budget. When the budget expires the caller gets
 * `false` and CONTINUES — the poll loop then re-checks its own deadline, keeps
 * polling its real oracle without the idle sync, and fails with its usual
 * screenshot and diagnosis at the time it promised to. A wedged idle-check
 * becomes a red test with evidence instead of a dead job.
 *
 * Two details are load bearing:
 *
 *  - **One worker thread, and never a queue behind a stuck wait.** If the
 *    previous submission has not returned, the next [run] returns `false`
 *    immediately rather than submitting more work. A wedge therefore costs at
 *    most ONE parked thread per instance for the rest of the test, not one per
 *    poll iteration — and the poll loop spins at its normal [Future]-free cost
 *    once it has been told the wait is hopeless.
 *  - **The worker is a daemon.** A thread parked forever inside Compose's
 *    idling machinery must not keep the instrumentation process alive after
 *    JUnit has recorded the failure and moved on.
 *
 * This is a harness bound, deliberately NOT a fix for whatever makes Compose
 * refuse to go idle (#2479 stays open for that). It makes the symptom cheap and
 * observable — a 60-second failure carrying [stuckDiagnosis] — rather than
 * silent and total.
 */
class BoundedWait(private val name: String) {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bounded-wait-$name").apply { isDaemon = true }
    }

    private var pending: Future<*>? = null
    private var stuckLabel: String? = null
    private var stuckSince: Long = 0L
    private var timeouts: Int = 0

    /**
     * Runs [block] on the worker and waits at most [budgetMs] for it.
     *
     * @return `true` when [block] completed inside the budget, `false` when it
     *   did not (or when an earlier call is still parked). Anything [block]
     *   throws is rethrown to the caller unchanged, so a real assertion failure
     *   inside a wrapped wait is not swallowed by the bound.
     */
    @Synchronized
    fun run(label: String, budgetMs: Long, block: () -> Unit): Boolean {
        val outstanding = pending
        if (outstanding != null) {
            if (!outstanding.isDone) {
                // Still parked from an earlier call. Submitting now would queue
                // behind it and hand the caller a bound it cannot enforce.
                return false
            }
            pending = null
        }

        val future = worker.submit(Runnable { block() })
        pending = future
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
            pending = null
            true
        } catch (timeout: TimeoutException) {
            timeouts += 1
            if (stuckLabel == null) {
                stuckLabel = label
                stuckSince = SystemClock.elapsedRealtime()
            }
            println(
                "BOUNDED_WAIT_TIMEOUT $name: `$label` did not return within ${budgetMs}ms " +
                    "(see issue #2479). Continuing without it so the caller's own " +
                    "deadline can still fire.",
            )
            false
        } catch (failure: ExecutionException) {
            pending = null
            throw failure.cause ?: failure
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
        val parked = pending?.isDone == false
        val elapsed = SystemClock.elapsedRealtime() - stuckSince
        return "NOTE (#2479): `$label` blew its wait budget ${timeouts}x, first " +
            "${elapsed}ms ago, and is ${if (parked) "STILL parked" else "no longer parked"}. " +
            "The wait was abandoned rather than allowed to consume this test's deadline."
    }
}

/**
 * The bounded form of [ComposeTestRule.waitForIdle], one budget per rule.
 *
 * Keyed on the rule instance (identity, via a [WeakHashMap]) so the "a wait is
 * already parked" state resets automatically between tests: JUnit builds a new
 * test-class instance — and therefore a new rule — per method, and a wedge in
 * one method must not silently disable idle syncing for the next one.
 */
object ComposeIdle {

    /**
     * How long ONE idle sync may take before the caller gives up on it.
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
