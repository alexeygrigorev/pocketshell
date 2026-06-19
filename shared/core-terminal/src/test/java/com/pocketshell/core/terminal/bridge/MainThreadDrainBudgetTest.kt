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
    fun `default budget byte cap is a generous ceiling (time cap is the real limiter)`() {
        val budget = MainThreadDrainBudget()
        // #796: the slice is 2 KB and the per-turn byte CEILING is 256 KB, so the
        // byte budget allows up to 128 × 2 KB slices/turn. This is deliberately
        // generous — the binding per-turn limiter is the scheduler's ELAPSED-TIME
        // budget, which trips after a slice or two for clear-heavy content but lets
        // many slices run for cheap append (so the drain keeps up with a flood).
        // The byte cap only guards a pathological clock-stall.
        assertEquals(128, budget.maxSlicesPerFrame)
    }

    @Test
    fun `byte cap exceeds the 64 KB process queue (drain can never wedge on the cap)`() {
        val budget = MainThreadDrainBudget()
        // The per-turn byte ceiling (256 KB) is larger than the 64 KB
        // process→terminal queue, so the byte cap can never strand the queue — a
        // turn could drain the whole queue in one pass if the time cap allowed.
        // The time cap is what actually paces it.
        assertTrue(budget.maxSlicesPerFrame * budget.drainSliceBytes >= 64 * 1024)
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
    fun `default per-turn byte ceiling is 256 KB (generous - time cap limits)`() {
        // The per-turn BYTE ceiling (128 × 2 KB = 256 KB after #796) is a generous
        // safety bound, NOT the primary limiter. It never reduces cheap-append
        // throughput (the time cap ends those turns first) and is large enough that
        // it cannot strand the 64 KB queue. The clear-heavy bound is the SEPARATE
        // elapsed-time cap in the scheduler, not this byte count.
        val budget = MainThreadDrainBudget()
        val perTurnBytes = budget.maxSlicesPerFrame * budget.drainSliceBytes
        assertEquals(256 * 1024, perTurnBytes)
    }
}
