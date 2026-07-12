package com.pocketshell.app.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // -------------------------------------------------------------------------
    // Issue #1494 — the BOUNDED single-flight: a WEDGED holder must not permanently
    // reject future heals, but a SLOW-BUT-ALIVE holder must still serialize (no double-run).
    // -------------------------------------------------------------------------

    @Test
    fun withPaneHealLockForceResetsAHolderWedgedPastTheBound() = runTest {
        var clock = 0L
        val coordinator = RenderHealCoordinator(nowMs = { clock })

        // A heal WEDGES holding the pane's single-flight (models an uninterruptible capture on a
        // dead socket that never returns and so never releases the lock).
        val wedge = CompletableDeferred<Unit>()
        val wedged = launch { coordinator.withPaneHealLock("%1") { wedge.await() } }
        runCurrent()

        // The holder has now held the lock PAST the bound.
        clock = RenderHealCoordinator.HELD_TOO_LONG_MS
        var laterRan = false
        val later = launch { coordinator.withPaneHealLock("%1") { laterRan = true } }
        runCurrent()

        assertTrue(
            "REGRESSION (#1494): a heal whose per-pane single-flight holder is WEDGED past the " +
                "held-too-long bound must FORCE-RESET the lock and proceed — before #1494 it " +
                "parked on withLock forever, so one hung capture wedged every future heal.",
            laterRan,
        )

        wedged.cancel()
        later.cancel()
    }

    @Test
    fun withPaneHealLockWaitsForASlowButAliveHolderInsteadOfForceResetting() = runTest {
        var clock = 0L
        val coordinator = RenderHealCoordinator(nowMs = { clock })

        val holderGate = CompletableDeferred<Unit>()
        var holderInside = false
        var laterRan = 0
        var sawConcurrentDoubleRun = false

        val holder = launch {
            coordinator.withPaneHealLock("%1") {
                holderInside = true
                holderGate.await()
                holderInside = false
            }
        }
        runCurrent()

        // A second heal arrives while the holder is only briefly held — WELL WITHIN the bound.
        clock = RenderHealCoordinator.HELD_TOO_LONG_MS - 1
        val later = launch {
            coordinator.withPaneHealLock("%1") {
                if (holderInside) sawConcurrentDoubleRun = true
                laterRan += 1
            }
        }
        runCurrent()

        assertEquals(
            "single-flight preserved (#1484): a slow-but-alive holder must be WAITED for, never " +
                "force-reset — the second heal has not run while the holder is inside its window",
            0,
            laterRan,
        )

        // The holder releases; the queued heal now proceeds — SERIALIZED, never overlapping.
        holderGate.complete(Unit)
        runCurrent()

        assertEquals("the queued heal runs exactly once after the holder releases", 1, laterRan)
        assertFalse(
            "the force-reset/fallback must NOT double-run a heal that is merely slow-but-alive",
            sawConcurrentDoubleRun,
        )

        holder.cancel()
        later.cancel()
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
