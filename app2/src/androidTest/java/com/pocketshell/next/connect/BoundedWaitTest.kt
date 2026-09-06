package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bound in [BoundedWait] is the thing #2479 asked for, so it is asserted
 * against an app that genuinely never goes idle — not eyeballed.
 *
 * ## Why these run on the device rather than the JVM
 *
 * [BoundedWait] is journey-harness code and lives in `app2/src/androidTest`,
 * which the JVM unit lane does not compile; the bound it enforces is built out
 * of Espresso's idling machinery, which only exists on a device. Wiring is
 * automatic: `.github/workflows/app2.yml`'s `app2-journey` job runs
 * `:app2:connectedDebugAndroidTest` ONCE, unfiltered, so every class under
 * `app2/src/androidTest/` runs. These cases need no fixture host, none of the
 * app's own screens and no network.
 *
 * ## Three wedge shapes, because they fail differently
 *
 * A `waitForIdle()` can fail to return for three structurally different reasons,
 * and the bound has to cover all of them:
 *
 *  - the main queue never drains ([BusyMainLooper]) — `Interrogator` keeps
 *    dispatching, so it reaches its own deadline check;
 *  - the main queue drains but an idling resource stays busy
 *    ([BusyIdlingResource]) — `MessageQueue.next()` parks with nothing to wake
 *    it, so only the deadline nudge ends the wait;
 *  - everything is idle and Compose is simply waiting for the next
 *    **Choreographer frame** — nothing times out at all, `Espresso.onIdle()`
 *    returns normally, and Compose spins on it. This is the shape that cost
 *    issue #2549's review run 24 of its 52 tests, and only the deadline tripwire
 *    breaks it.
 *
 * The budgets here are deliberately small (hundreds of milliseconds against an
 * app busy for seconds) — the property under test is "the caller regains control
 * at its deadline", and a small budget proves it as well as a 15-second one
 * while keeping the lane fast.
 */
@RunWith(AndroidJUnit4::class)
class BoundedWaitTest {

    @get:Rule
    val compose = createComposeRule()

    private val busyLooper = BusyMainLooper()
    private val busyResource = BusyIdlingResource("pocketshell-busy-idling-fixture")

    @Before
    fun registerTheResourceWedge() {
        IdlingRegistry.getInstance().register(busyResource)
    }

    /**
     * Unlike [ComposeIdleReentrancyTest], nothing here uses the Compose rule's
     * teardown as an oracle, so both wedges are released as soon as the
     * assertions are done and the lane does not pay for their timers.
     */
    @After
    fun releaseTheWedges() {
        busyLooper.release()
        busyResource.release()
        IdlingRegistry.getInstance().unregister(busyResource)
    }

    /**
     * The regression for issue #2549's blocking review finding.
     *
     * The first fix bounded a `waitForIdle()` with `IdlingPolicies` alone, and a
     * reviewer's unfiltered run then parked one `awaitIdle` for **961 seconds on
     * a 15-second budget** and lost 24 of the lane's 52 tests. The thread dump
     * put the calling thread in
     * `AndroidComposeUiTestEnvironment.waitForNextChoreographerFrame`, which is
     * (`ComposeUiTest.android.kt:402`, bytecode 182-212) exactly:
     *
     * ```
     * root.postOnAnimation { root.post { frameReceived = true } }
     * while (!frameReceived) { idlingStrategy.runUntilIdle() }   // Espresso.onIdle()
     * ```
     *
     * An idling policy cannot bound that: with nothing busy, every
     * `Espresso.onIdle()` in the loop returns normally and Compose immediately
     * calls it again, so the wait's real floor is one vsync — 60 seconds on the
     * starved swiftshader emulator that run was on.
     *
     * The block below is that loop verbatim, with the "frame" put [FRAME_MS]
     * away instead of one vsync, because a 60-second frame is a property of a
     * starved GPU and cannot be manufactured on demand. What is being asserted
     * is the thing that failed: the caller gets control back on ITS budget, not
     * on the frame's schedule.
     */
    @Test
    fun aSpinBetweenChoreographerFramesIsBoundedByTheBudget() {
        showSomething()
        val frameReceived = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).postDelayed({ frameReceived.set(true) }, FRAME_MS)
        val bounded = BoundedWait("frame-spin")
        val started = SystemClock.elapsedRealtime()

        val completed = bounded.run("frame spin", budgetMs = BUDGET_MS) {
            while (!frameReceived.get()) {
                Espresso.onIdle()
            }
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("a spin waiting on a frame must not report completion", completed)
        assertTrue(
            "the budget must end the spin, not the frame ${FRAME_MS}ms away " +
                "(took ${elapsed}ms)",
            elapsed < FRAME_MS,
        )
        assertTrue("the wedge must be reported for the failure message", bounded.isStuck())
    }

    /**
     * The #2479 shape: an app that will not go idle must not consume more than
     * the budget.
     */
    @Test
    fun aWaitOnABusyMainLooperGivesUpWithinItsBudget() {
        val bounded = BoundedWait("never-idle")
        showSomething()
        busyLooper.beBusyFor(BUSY_MS)
        val started = SystemClock.elapsedRealtime()

        val completed = bounded.run("busy-queue", budgetMs = BUDGET_MS) {
            compose.waitForIdle()
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("a wait on a busy app must not report completion", completed)
        assertTrue(
            "the budget must bound the call, not the fixture's own ${BUSY_MS}ms " +
                "timer (took ${elapsed}ms)",
            elapsed < BUSY_MS,
        )
        assertTrue("the wedge must be reported for the failure message", bounded.isStuck())
        assertTrue(
            "the diagnosis must name the wait: ${bounded.stuckDiagnosis()}",
            bounded.stuckDiagnosis().contains("busy-queue"),
        )
    }

    /**
     * The wedge Espresso's own 60-second policy cannot break: a main queue with
     * nothing due plus a busy idling resource parks `MessageQueue.next()`, so
     * `Interrogator` never reaches the deadline check that would end the wait.
     *
     * This is the case the deadline nudge exists for. If the nudge stopped being
     * posted, this would take [BUSY_MS] and fail here.
     */
    @Test
    fun aWaitParkedOnAnEmptyQueueGivesUpWithinItsBudget() {
        val bounded = BoundedWait("parked-on-an-empty-queue")
        showSomething()
        busyResource.beBusyFor(BUSY_MS)
        val started = SystemClock.elapsedRealtime()

        val completed = bounded.run("empty-queue", budgetMs = BUDGET_MS) {
            compose.waitForIdle()
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("a wait on a busy resource must not report completion", completed)
        assertTrue(
            "the deadline nudge must wake the parked queue inside the budget " +
                "(took ${elapsed}ms against a ${BUSY_MS}ms fixture)",
            elapsed < BUSY_MS,
        )
        assertTrue("the wedge must be reported for the failure message", bounded.isStuck())
    }

    /**
     * The regression this bound is actually about, in miniature: a poll loop
     * shaped exactly like `J03AttachAndTypeJourney.rotate()` — bounded loop,
     * blocking idle sync in the body, condition that never comes true — must
     * reach its own deadline and fail.
     *
     * Before the bound existed the equivalent loop parked on its FIRST iteration
     * and never came back; the whole point is that the loop below still owns its
     * clock.
     */
    @Test
    fun aPollLoopWithAWedgedWaitStillReachesItsDeadline() {
        val bounded = BoundedWait("wedged-poll")
        showSomething()
        busyLooper.beBusyFor(LOOP_BUDGET_MS + 2_000L)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + LOOP_BUDGET_MS
        var iterations = 0
        // The oracle `rotate()` polls for, standing in for "the terminal laid
        // out landscape": here it never comes true, so only the loop's own
        // deadline can end this.
        val laidOut = { false }

        while (SystemClock.elapsedRealtime() < deadline) {
            bounded.run("idle-sync", budgetMs = 400L) { compose.waitForIdle() }
            iterations += 1
            if (laidOut()) break
            SystemClock.sleep(50L)
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        assertFalse("the fixture's condition never comes true", laidOut())
        assertTrue(
            "the loop must exit at its own deadline, not the wait's (took ${elapsed}ms)",
            elapsed in LOOP_BUDGET_MS..(LOOP_BUDGET_MS + 5_000L),
        )
        assertTrue("the loop must keep polling through the wedge ($iterations)", iterations > 3)
    }

    /** A healthy wait is unchanged: it completes, and nothing is reported stuck. */
    @Test
    fun aWaitThatReturnsIsReportedComplete() {
        val bounded = BoundedWait("healthy")
        showSomething()
        var ran = 0

        repeat(3) {
            assertTrue(
                bounded.run("quick", budgetMs = 15_000L) {
                    compose.waitForIdle()
                    ran += 1
                },
            )
        }

        assertEquals("the block must run every time", 3, ran)
        assertFalse("nothing timed out, so nothing is stuck", bounded.isStuck())
        assertEquals("no diagnosis when nothing wedged", "", bounded.stuckDiagnosis())
    }

    /**
     * A real failure inside a wrapped wait must reach the test, not be laundered
     * into "the wait timed out" — otherwise the bound would hide the assertion
     * failures it wraps.
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

    /**
     * The bound must not leave Espresso's global idling policies lowered on ANY
     * exit path: every unbounded `onNodeWithTag`/`performClick` in every later
     * journey runs on them, and a 400 ms master policy left behind would redden
     * a slow-but-healthy emulator suite-wide.
     *
     * All three paths, because the two that matter are the ones a success-only
     * test never reaches — the give-up path lowers the policies twice (once per
     * call, once on trip) and the throwing path unwinds through `finally` from a
     * different stack.
     */
    @Test
    fun theBoundRestoresEspressosGlobalIdlingPoliciesOnEveryPath() {
        showSomething()
        val masterBefore = masterTimeoutMs()
        val resourceBefore = resourceTimeoutMs()

        BoundedWait("policy-success").run("quick", budgetMs = 5_000L) { compose.waitForIdle() }
        assertEquals("after a completed wait: master", masterBefore, masterTimeoutMs())
        assertEquals("after a completed wait: resource", resourceBefore, resourceTimeoutMs())

        busyLooper.beBusyFor(BUSY_MS)
        val completed = BoundedWait("policy-giveup")
            .run("wedged", budgetMs = BUDGET_MS) { compose.waitForIdle() }
        assertFalse("the give-up path must actually be taken", completed)
        assertEquals("after a give-up: master", masterBefore, masterTimeoutMs())
        assertEquals("after a give-up: resource", resourceBefore, resourceTimeoutMs())
        busyLooper.release()

        runCatching {
            BoundedWait("policy-throw").run("boom", budgetMs = 5_000L) {
                throw IllegalStateException("boom")
            }
        }
        assertEquals("after a thrown block: master", masterBefore, masterTimeoutMs())
        assertEquals("after a thrown block: resource", resourceBefore, resourceTimeoutMs())
    }

    /**
     * The tripwire must leave the registry on every path too.
     *
     * A leaked one is worse than no bound at all: it reports busy for ever, so
     * the NEXT journey's every Espresso call would time out against a resource
     * belonging to a test that finished minutes ago.
     */
    @Test
    fun theBoundLeavesNoIdlingResourceBehind() {
        showSomething()

        BoundedWait("leak-success").run("quick", budgetMs = 5_000L) { compose.waitForIdle() }
        assertEquals("after a completed wait", emptyList<String>(), tripwiresInRegistry())

        busyLooper.beBusyFor(BUSY_MS)
        val completed = BoundedWait("leak-giveup")
            .run("wedged", budgetMs = BUDGET_MS) { compose.waitForIdle() }
        assertFalse("the give-up path must actually be taken", completed)
        assertEquals("after a give-up", emptyList<String>(), tripwiresInRegistry())
        busyLooper.release()

        runCatching {
            BoundedWait("leak-throw").run("boom", budgetMs = 5_000L) {
                throw IllegalStateException("boom")
            }
        }
        assertEquals("after a thrown block", emptyList<String>(), tripwiresInRegistry())
    }

    /**
     * Names of any [BoundedWait] tripwire still registered.
     *
     * The prefix is [BoundedWait]'s, not this test's — the busy-resource wedge
     * above deliberately uses a different one, because a fixture that shared the
     * prefix would make this assertion fail against its own fixture rather than
     * against a leak (it did, first time).
     */
    private fun tripwiresInRegistry(): List<String> =
        IdlingRegistry.getInstance().resources
            .map { it.name }
            .filter { it.startsWith("pocketshell-bounded-wait-") }

    private fun masterTimeoutMs(): Long =
        IdlingPolicies.getMasterIdlingPolicy().let { it.idleTimeoutUnit.toMillis(it.idleTimeout) }

    private fun resourceTimeoutMs(): Long =
        IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
            .let { it.idleTimeoutUnit.toMillis(it.idleTimeout) }

    private fun showSomething() {
        compose.setContent { Text("bounded wait fixture") }
        compose.waitForIdle()
    }

    private companion object {

        /** Comfortably longer than every budget below, so no budget can be met. */
        const val BUSY_MS = 4_000L

        const val BUDGET_MS = 500L

        const val LOOP_BUDGET_MS = 3_000L

        /**
         * Stands in for the 60-second frame the reviewer's emulator was taking.
         * Far enough above [BUDGET_MS] that "the budget ended it" and "the frame
         * ended it" cannot be confused for one another.
         */
        const val FRAME_MS = 20_000L
    }
}
