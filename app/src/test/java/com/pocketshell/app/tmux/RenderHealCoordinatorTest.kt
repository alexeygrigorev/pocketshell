package com.pocketshell.app.tmux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1353 slice R3 — the pure ownership contract of the per-pane single-flight owner
 * ([RenderHealCoordinator]). This is the deterministic sibling of the end-to-end
 * [RenderHealSingleFlightTest]: it asserts the invariant that makes the guard correct —
 * SAME pane id → SAME mutex (heals serialize), DIFFERENT pane id → DIFFERENT mutex (heals
 * run concurrently, never a global lock).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderHealCoordinatorTest {

    @Test
    fun samePaneReturnsSameMutexDifferentPaneReturnsDifferentMutex() {
        val coordinator = RenderHealCoordinator()

        assertSame(
            "the same pane id MUST map to the SAME mutex — otherwise two heals for one pane " +
                "would take different locks and never serialize (the race would survive)",
            coordinator.paneHealMutex("%1"),
            coordinator.paneHealMutex("%1"),
        )
        assertNotSame(
            "different pane ids MUST map to DIFFERENT mutexes — otherwise the guard would be a " +
                "GLOBAL lock and serialize heals on unrelated panes",
            coordinator.paneHealMutex("%1"),
            coordinator.paneHealMutex("%2"),
        )
    }

    @Test
    fun samePaneHealBlocksWhileDifferentPaneHealProceeds() = runTest {
        val coordinator = RenderHealCoordinator()

        // Hold pane %1's mutex (a heal is "in flight" for %1).
        coordinator.paneHealMutex("%1").lock()

        // A concurrent heal for the SAME pane must NOT be able to enter (serialized).
        assertFalse(
            "REGRESSION (#1353 R3): a second heal for the SAME pane must block while the first " +
                "holds the pane mutex — this is the single-flight that closes the seed-gate race",
            coordinator.paneHealMutex("%1").tryLock(),
        )

        // A concurrent heal for a DIFFERENT pane must proceed immediately (per-pane, not global).
        var otherPaneRan = false
        val job = launch {
            coordinator.paneHealMutex("%2").withLock { otherPaneRan = true }
        }
        job.join()
        assertTrue(
            "REGRESSION (#1353 R3 per-pane): a heal for a DIFFERENT pane must run while pane " +
                "%1's heal holds its mutex — the guard is keyed per pane, not a global lock",
            otherPaneRan,
        )

        coordinator.paneHealMutex("%1").unlock()
    }

    @Test
    fun forgetDropsThePaneMutex() {
        val coordinator = RenderHealCoordinator()
        val first = coordinator.paneHealMutex("%1")
        coordinator.forget("%1")
        assertNotSame(
            "after forget(), the pane gets a FRESH mutex (the old one was evicted)",
            first,
            coordinator.paneHealMutex("%1"),
        )
    }
}
