package com.pocketshell.core.terminal.bridge

import android.os.Handler
import android.os.Looper
import android.os.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #803: deterministic contract test for [MainThreadDrainScheduler] — the
 * frame-budgeted main-looper drain that replaced the pre-#803 unbounded
 * back-to-back VT-append run.
 *
 * The scheduler is driven against a stand-in single-slice drain: a [Handler]
 * whose `MSG_NEW_INPUT` handling parses exactly one 16 KB slice (decrementing a
 * pending-bytes counter and recording WHICH main-thread turn that slice ran in).
 * This is faithful to production — in `SshTerminalBridge` the same
 * `dispatchMessage(MSG_NEW_INPUT)` runs exactly one `mEmulator.append` slice (the
 * vendored handler no longer self-re-posts). We model only the
 * queue-drain/turn-budget contract here, NOT the VT parse itself (that is the
 * connected proof's job), so a stand-in is appropriate and the per-frame budget
 * arithmetic is fully exercised.
 *
 * Load-bearing assertions:
 *  - **Collapse**: a burst of `requestDrain()` calls before the first turn
 *    schedules AT MOST one pending main-looper drain (not one per call).
 *  - **Per-turn budget**: a single main-thread turn parses AT MOST
 *    `maxSlicesPerFrame` slices before yielding — NOT the whole queue
 *    back-to-back (the #803 ANR shape).
 *  - **Frame yield + final byte**: a queue larger than one turn's budget is
 *    drained across multiple frames (yields between turns) and EVERY byte is
 *    eventually parsed (the final-byte / no-loss guarantee).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainThreadDrainSchedulerTest {

    private val sliceBytes = MainThreadDrainBudget.DEFAULT_DRAIN_SLICE_BYTES

    /**
     * A single-slice-drain stand-in: each MSG_NEW_INPUT dispatch consumes one
     * slice from [pending] and records the turn index it ran in, so the test can
     * assert how many slices ran per main-thread turn.
     */
    private inner class SliceDrainHarness {
        val pending = AtomicInteger(0)
        var currentTurn = 0
        val slicesPerTurn = mutableListOf<Int>()

        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_NEW_INPUT) {
                    // Parse exactly one slice (cap the decrement at what's left).
                    val before = pending.get()
                    if (before <= 0) return
                    pending.addAndGet(-minOf(sliceBytes, before))
                    while (slicesPerTurn.size <= currentTurn) slicesPerTurn.add(0)
                    slicesPerTurn[currentTurn] = slicesPerTurn[currentTurn] + 1
                }
            }
        }

        fun availableBytes(): Int = pending.get()

        fun scheduler(budget: MainThreadDrainBudget = MainThreadDrainBudget()) =
            MainThreadDrainScheduler(
                handler = handler,
                msgNewInput = MSG_NEW_INPUT,
                availableBytes = ::availableBytes,
                budget = budget,
                yieldDelayMs = YIELD_MS,
            )
    }

    private companion object {
        const val MSG_NEW_INPUT = 1
        const val YIELD_MS = 16L
    }

    /**
     * Run the scheduler to completion, recording each main-thread turn. Robolectric
     * PAUSED looper: `idleFor` advances the clock so the `postDelayed` frame-yield
     * continuations fire. We tick the [SliceDrainHarness.currentTurn] before each
     * idle window so slices are attributed to the turn they ran in.
     */
    private fun SliceDrainHarness.drainToCompletion() {
        // The first turn was posted with handler.post (no delay) — run it.
        currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()
        // Subsequent yielded turns are postDelayed(YIELD_MS) each; advance the
        // clock one frame at a time so each lands in its own turn.
        var guard = 0
        while (availableBytes() > 0) {
            currentTurn += 1
            shadowOf(Looper.getMainLooper()).idleFor(YIELD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            guard += 1
            check(guard < 10_000) { "drain did not converge (possible wedge)" }
        }
        // One more idle window to flush any trailing posted continuation.
        currentTurn += 1
        shadowOf(Looper.getMainLooper()).idleFor(YIELD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Test
    fun `a burst of requestDrain calls collapses to one pending turn`() {
        val h = SliceDrainHarness()
        // Exactly one slice of work queued.
        h.pending.set(sliceBytes)
        val scheduler = h.scheduler()

        // A storm of requestDrain() calls before the looper runs — production
        // shape: many off-main %output chunks between two frames.
        repeat(500) { scheduler.requestDrain() }

        // Only ONE drain runnable should be posted (collapse). After running the
        // looper once, the single slice drains and the queue empties.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the whole queue must drain", 0, h.availableBytes())
        // The first turn ran exactly one slice (only one slice was queued); the
        // 500 requestDrain calls did NOT each schedule their own turn.
        assertEquals("burst must collapse to one turn", 1, h.slicesPerTurn.size)
        assertEquals(1, h.slicesPerTurn[0])
    }

    @Test
    fun `a single turn parses at most the per-frame slice budget then yields`() {
        val h = SliceDrainHarness()
        val budget = MainThreadDrainBudget() // default: 1 × 16 KB slice/turn
        // Queue 10 slices — far more than one turn's budget.
        h.pending.set(sliceBytes * 10)
        val scheduler = h.scheduler(budget)
        scheduler.requestDrain()

        // Run ONLY the first (non-delayed) turn.
        h.currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()

        // The first turn must parse AT MOST maxSlicesPerFrame slices — not all 10
        // back-to-back (the #803 ANR shape).
        assertEquals(
            "first main-thread turn must be bounded to the per-frame budget",
            budget.maxSlicesPerFrame,
            h.slicesPerTurn[0],
        )
        assertTrue("bytes must remain for a later frame", h.availableBytes() > 0)
    }

    @Test
    fun `a large queue drains across multiple frames and every byte is parsed`() {
        val h = SliceDrainHarness()
        val budget = MainThreadDrainBudget() // default: 1 × 16 KB slice/turn
        val totalSlices = 23 // far more than one turn's budget — exercises the multi-frame tail
        h.pending.set(sliceBytes * totalSlices)
        val scheduler = h.scheduler(budget)
        scheduler.requestDrain()

        h.drainToCompletion()

        // EVERY byte parsed (final-byte / no-loss guarantee).
        assertEquals("the full queue must drain — no lost bytes", 0, h.availableBytes())
        val totalParsed = h.slicesPerTurn.sum()
        assertEquals("every slice parsed exactly once", totalSlices, totalParsed)

        // The drain was SPREAD across multiple frames (yielded between turns) —
        // NOT one unbounded back-to-back run.
        val turnsWithWork = h.slicesPerTurn.count { it > 0 }
        assertTrue(
            "a 23-slice queue (budget 1/turn) must drain across multiple yielded " +
                "frames, was $turnsWithWork turn(s): ${h.slicesPerTurn}",
            turnsWithWork >= 6,
        )
        // No single turn exceeded the per-frame budget.
        assertTrue(
            "no main-thread turn may exceed the per-frame slice budget; turns=${h.slicesPerTurn}",
            h.slicesPerTurn.all { it <= budget.maxSlicesPerFrame },
        )
    }

    @Test
    fun `cancel drops a scheduled continuation`() {
        val h = SliceDrainHarness()
        h.pending.set(sliceBytes * 10)
        val scheduler = h.scheduler()
        scheduler.requestDrain()

        // Run the first turn (drains the budget, then posts a delayed continuation).
        h.currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()
        val afterFirstTurn = h.availableBytes()
        assertTrue(afterFirstTurn > 0)

        // Cancel: the delayed continuation must be removed, so advancing the clock
        // does NO further draining.
        scheduler.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(YIELD_MS * 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("cancel must drop the scheduled drain", afterFirstTurn, h.availableBytes())
    }
}
