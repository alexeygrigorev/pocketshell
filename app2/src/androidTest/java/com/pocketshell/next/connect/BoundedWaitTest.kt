package com.pocketshell.next.connect

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bound in [BoundedWait] is the thing #2479 asked for, so it is asserted
 * against a wait that genuinely never returns — not eyeballed.
 *
 * ## Why these run on the device rather than the JVM
 *
 * [BoundedWait] is journey-harness code and lives in `app2/src/androidTest`,
 * which the JVM unit lane does not compile; and its callers pass it
 * `ComposeTestRule.waitForIdle`, which only exists on a device. Wiring is
 * automatic: `.github/workflows/app2.yml`'s `app2-journey` job runs
 * `:app2:connectedDebugAndroidTest` ONCE, unfiltered, so every class under
 * `app2/src/androidTest/` runs. These four cases add ~4 seconds to that lane
 * and need no fixture, no Activity and no network.
 *
 * The budgets here are deliberately small (hundreds of milliseconds against a
 * wait parked for hours) — the property under test is "the caller regains
 * control at its deadline", and a small budget proves it as well as a 15-second
 * one while keeping the lane fast.
 */
@RunWith(AndroidJUnit4::class)
class BoundedWaitTest {

    /**
     * The #2479 shape itself: a wait that never returns must not consume more
     * than its budget.
     *
     * `Long.MAX_VALUE` rather than a long sleep on purpose — a wait that
     * "eventually" returns could pass this by accident.
     */
    @Test
    fun aWaitThatNeverReturnsGivesUpWithinItsBudget() {
        val bounded = BoundedWait("never-returns")
        val started = SystemClock.elapsedRealtime()

        val completed = bounded.run("parked-forever", budgetMs = 500L) {
            parkForever()
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("a wait that never returns must not report completion", completed)
        assertTrue("the budget must actually bound the call (took ${elapsed}ms)", elapsed < 5_000L)
        assertTrue("the wedge must be reported for the failure message", bounded.isStuck())
        assertTrue(
            "the diagnosis must name the wait: ${bounded.stuckDiagnosis()}",
            bounded.stuckDiagnosis().contains("parked-forever"),
        )
    }

    /**
     * The regression this issue is actually about, in miniature: a poll loop
     * shaped exactly like `J03AttachAndTypeJourney.rotate()` — bounded loop,
     * blocking wait in the body, condition that never comes true — must reach
     * its own deadline and fail.
     *
     * Before the fix the equivalent loop parked on its FIRST iteration and
     * never came back; the whole point is that the loop below still owns its
     * clock.
     */
    @Test
    fun aPollLoopWithAWedgedWaitStillReachesItsDeadline() {
        val bounded = BoundedWait("wedged-poll")
        val loopBudgetMs = 3_000L
        val started = SystemClock.elapsedRealtime()
        val deadline = started + loopBudgetMs
        var iterations = 0
        // The oracle `rotate()` polls for, standing in for "the terminal laid
        // out landscape": here it never comes true, so only the loop's own
        // deadline can end this.
        val laidOut = { false }

        while (SystemClock.elapsedRealtime() < deadline) {
            bounded.run("idle-sync", budgetMs = 400L) { parkForever() }
            iterations += 1
            if (laidOut()) break
            SystemClock.sleep(50L)
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("the fixture's condition never comes true", laidOut())
        assertTrue(
            "the loop must exit at its own deadline, not the wait's (took ${elapsed}ms)",
            elapsed in loopBudgetMs..(loopBudgetMs + 5_000L),
        )
        // The first iteration pays the 400 ms budget; every later one is told
        // immediately that the wait is already parked, which is what keeps the
        // remaining budget available for real polling.
        assertTrue("the loop must keep polling after the wedge ($iterations)", iterations > 3)
    }

    /** A healthy wait is unchanged: it completes, and nothing is reported stuck. */
    @Test
    fun aWaitThatReturnsIsReportedComplete() {
        val bounded = BoundedWait("healthy")
        var ran = 0

        repeat(3) { assertTrue(bounded.run("quick", budgetMs = 5_000L) { ran += 1 }) }

        assertEquals("the block must run every time", 3, ran)
        assertFalse("nothing timed out, so nothing is stuck", bounded.isStuck())
        assertEquals("no diagnosis when nothing wedged", "", bounded.stuckDiagnosis())
    }

    /**
     * A real failure inside a wrapped wait must reach the test, not be laundered
     * into "the wait timed out" or swallowed on the worker thread — otherwise
     * the bound would hide the assertion failures it wraps.
     */
    @Test
    fun aFailureInsideTheWaitReachesTheCaller() {
        val bounded = BoundedWait("throwing")

        val thrown = runCatching {
            bounded.run("boom", budgetMs = 5_000L) { throw IllegalStateException("boom") }
        }.exceptionOrNull()

        assertTrue("expected the block's own exception, got $thrown", thrown is IllegalStateException)
        assertEquals("boom", thrown?.message)
        assertFalse("a thrown block is not a wedge", bounded.isStuck())
    }

    /** A wait that outlives its budget and never returns; the worker is a daemon. */
    private fun parkForever() {
        CountDownLatch(1).await(1L, TimeUnit.DAYS)
    }
}
