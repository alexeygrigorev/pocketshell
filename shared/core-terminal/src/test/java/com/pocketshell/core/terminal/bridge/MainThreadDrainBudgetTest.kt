package com.pocketshell.core.terminal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #803: pure contract test for the per-frame VT-append drain budget. This
 * is the JVM-fast sibling of the Robolectric [MainThreadDrainSchedulerTest] and
 * the on-device [com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest]:
 * it pins the budget arithmetic that decides how many 16 KB `append` slices a
 * single main-thread turn may parse before yielding to the next frame.
 */
class MainThreadDrainBudgetTest {

    @Test
    fun `default budget parses one slice per turn then yields`() {
        val budget = MainThreadDrainBudget()
        // One 16 KB append slice per main-thread turn — the SAME per-turn parse the
        // vendored handler always did, so #803 never increases the contiguous
        // main-thread occupancy of a single turn. The fix is the GUARANTEED frame
        // yield BETWEEN turns (postDelayed), not a bigger/smaller per-turn budget.
        assertEquals(1, budget.maxSlicesPerFrame)
    }

    @Test
    fun `budget always allows at least one slice per turn (forward progress)`() {
        // Even a budget smaller than one slice must drain a whole slice per turn,
        // otherwise the drain could never make progress and the queue would wedge.
        val budget = MainThreadDrainBudget(
            drainSliceBytes = 16 * 1024,
            bytesPerFrame = 16 * 1024,
        )
        assertEquals(1, budget.maxSlicesPerFrame)
    }

    @Test
    fun `larger per-frame budget drains more slices per turn`() {
        val budget = MainThreadDrainBudget(
            drainSliceBytes = 16 * 1024,
            bytesPerFrame = 128 * 1024,
        )
        assertEquals(8, budget.maxSlicesPerFrame)
    }

    @Test
    fun `budget below one slice is rejected (would never progress)`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MainThreadDrainBudget(
                drainSliceBytes = 16 * 1024,
                bytesPerFrame = 8 * 1024,
            )
        }
        assertTrue(ex.message!!.contains("bytesPerFrame"))
    }

    @Test
    fun `non-positive slice size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MainThreadDrainBudget(drainSliceBytes = 0, bytesPerFrame = 64 * 1024)
        }
    }

    @Test
    fun `default per-turn byte cap equals one drain slice (no occupancy increase vs base)`() {
        // The per-turn parse cap (one 16 KB slice) is the worst-case contiguous
        // main-thread occupancy between yields, and it equals the SAME single slice
        // the vendored handler always drained per MSG_NEW_INPUT — so #803 never
        // increases per-turn occupancy; it only adds the guaranteed frame yield
        // between turns.
        val budget = MainThreadDrainBudget()
        val perTurnBytes = budget.maxSlicesPerFrame * budget.drainSliceBytes
        assertEquals(16 * 1024, perTurnBytes)
    }
}
