package com.pocketshell.next.connect

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2549 — a bounded idle wait that gives up must not leave an Espresso
 * interrogation running behind it.
 *
 * ## The failure being reproduced
 *
 * `J03AttachAndTypeJourney` failed three times in CI (a v0.5.2 release cut, a
 * scheduled run, and a post-merge `main` run) with no product assertion
 * involved:
 *
 * ```
 * java.lang.RuntimeException: java.util.concurrent.ExecutionException:
 *   java.lang.IllegalStateException: Already interrogating!
 *   at androidx.test.espresso.Espresso.onIdle(Espresso.java:18)
 *   at androidx.compose.ui.test.EspressoLink.runUntilIdle(EspressoLink.android.kt:82)
 *   at androidx.compose.ui.test.AndroidComposeUiTestEnvironment.runTest(...)
 *   at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1.evaluate(...)
 * ```
 *
 * `Already interrogating!` is `Interrogator.checkSanity()` refusing a nested
 * main-thread interrogation. `Espresso.onIdle()` called off the main thread
 * posts a `loopMainThreadUntilIdle` task to the main looper and blocks on its
 * future; that task sets a thread-local `interrogating` flag on the main thread
 * and then dispatches main-queue messages ITSELF. So when a second thread calls
 * `onIdle()` while the first one's task is still interrogating, the second
 * one's task is dispatched from inside the first one's loop and the guard fires.
 *
 * The old [BoundedWait] created that state by design — it ran `waitForIdle()` on
 * a daemon worker and let the caller continue when the budget expired, leaving
 * the worker's interrogation outstanding with nobody tracking it. The next
 * Espresso call from the test thread then blew up in the harness rather than on
 * a product assertion: a semantics read, an action, or (as above) the Compose
 * rule's own teardown.
 *
 * ## The three shapes, all from the same defect
 *
 * Each test below abandons a wait and then does one of the three things a
 * journey does next. All three failed before the fix; the fix is that
 * [BoundedWait] never abandons an interrogation at all — it bounds the wait
 * with Espresso's own deadline, on the calling thread.
 *
 * ## Why a [BusyMainLooper] rather than a busy UI
 *
 * The wedge has to be a fact rather than emulator luck, and it has to be the
 * right KIND of wedge — see [BusyMainLooper] for why a busy `IdlingResource`
 * reproduces a sibling Espresso guard (`Callback has already been registered`)
 * instead of the one CI reported.
 */
@RunWith(AndroidJUnit4::class)
class ComposeIdleReentrancyTest {

    @get:Rule
    val compose = createComposeRule()

    // Deliberately NO @After that releases the fixture. The looper stays busy on
    // its own timer, through the Compose rule's teardown: releasing it first
    // would let a leaked interrogation finish early and hide the bug from the
    // teardown sync that reported it.
    private val busy = BusyMainLooper()

    private fun wedgeTheIdleSync() {
        compose.setContent { Text(ON_SCREEN) }
        compose.waitForIdle()
        busy.beBusyFor(BUSY_MS)

        val started = SystemClock.elapsedRealtime()
        val completed = compose.awaitIdle("wedged idle sync", budgetMs = BUDGET_MS)
        val elapsed = SystemClock.elapsedRealtime() - started

        assertFalse(
            "the wait must give up: the main looper is busy for ${BUSY_MS}ms " +
                "against a ${BUDGET_MS}ms budget",
            completed,
        )
        assertTrue(
            "the budget must bound the wait, not the fixture's own ${BUSY_MS}ms " +
                "timer (took ${elapsed}ms)",
            elapsed < BUSY_MS,
        )
    }

    /**
     * The exact reported stack. `AndroidComposeUiTestEnvironment.runTest`
     * (`ComposeUiTest.android.kt:363`) ends every test in this suite with a
     * plain `waitForIdle()`, and that is the frame CI printed — so the call
     * below is the reported failure, made explicit rather than left to the rule
     * to perform after the last line of the method.
     *
     * Making it explicit is deliberate: the leak from an earlier method also
     * poisons LATER methods and later classes, so leaving this to the teardown
     * would let the red land on whatever test happened to run next instead of
     * on the one that caused it.
     */
    @Test
    fun aGiveUpLeavesTheNextIdleSyncWorking() {
        wedgeTheIdleSync()

        compose.waitForIdle()
    }

    /**
     * The `J03AttachAndTypeJourney.awaitTag` stack from the first occurrence: a
     * poll loop that gave up on the idle sync goes straight back to reading the
     * semantics tree.
     */
    @Test
    fun aGiveUpLeavesTheNextSemanticsReadWorking() {
        wedgeTheIdleSync()

        compose.onNodeWithText(ON_SCREEN).assertIsDisplayed()
    }

    /**
     * The `safeScreenDiagnosis` shape: the failure path wraps its own Compose
     * reads in a [BoundedWait] too, so a give-up must leave the NEXT bounded
     * wait usable rather than poisoned.
     */
    @Test
    fun aGiveUpLeavesTheNextBoundedWaitWorking() {
        wedgeTheIdleSync()

        val diagnosis = BoundedWait("reentrancy-diagnosis")
        var nodes = 0
        val read = diagnosis.run("semantics read", DIAGNOSIS_BUDGET_MS) {
            nodes = compose.onAllNodesWithText(ON_SCREEN).fetchSemanticsNodes().size
        }

        assertTrue("the diagnosis read must complete, not be refused", read)
        assertTrue("the diagnosis must see the tree it read ($nodes)", nodes > 0)
    }

    private companion object {

        const val ON_SCREEN = "reentrancy fixture"

        /** Long enough that the budget below cannot be met by any timing luck. */
        const val BUSY_MS = 4_000L

        /** Short enough that three tests plus teardowns stay a few seconds. */
        const val BUDGET_MS = 1_000L

        /**
         * Generous on purpose. The assertion is that the wrapped read COMPLETES
         * — the looper is still busy when it starts, so a tight budget would
         * only re-prove the bound and would never reach the Compose call whose
         * re-entrancy is the subject here.
         */
        const val DIAGNOSIS_BUDGET_MS = 15_000L
    }
}
