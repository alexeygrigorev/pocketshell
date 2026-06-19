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
 * whose `MSG_NEW_INPUT` handling parses exactly one slice (decrementing a
 * pending-bytes counter and recording WHICH main-thread turn that slice ran in).
 * This is faithful to production — in `SshTerminalBridge` the same
 * `dispatchMessage(MSG_NEW_INPUT)` runs exactly one `mEmulator.append` slice (the
 * vendored handler no longer self-re-posts). We model only the
 * queue-drain/turn-budget contract here, NOT the VT parse itself (that is the
 * connected proof's job), so a stand-in is appropriate and BOTH the per-frame
 * byte budget AND the #796 elapsed-time budget are exercised (the time budget
 * via an injected per-slice clock).
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
    fun `a single turn parses at most the per-frame byte budget then yields`() {
        val h = SliceDrainHarness()
        // Use an explicit SMALL byte budget (4 slices/turn) so the byte-cap path is
        // exercised deterministically under Robolectric (where the injected/real
        // clock does not advance during synchronous dispatch, so the TIME cap never
        // trips). This pins the byte-cap arithmetic; the time-cap path has its own
        // tests below.
        val budget = MainThreadDrainBudget(
            drainSliceBytes = sliceBytes,
            bytesPerFrame = sliceBytes * 4,
        )
        // Queue 10 slices — far more than one turn's byte budget (4).
        h.pending.set(sliceBytes * 10)
        val scheduler = h.scheduler(budget)
        scheduler.requestDrain()

        // Run ONLY the first (non-delayed) turn.
        h.currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()

        // The first turn must parse AT MOST maxSlicesPerFrame (4) slices — not all
        // 10 back-to-back (the #803 ANR shape).
        assertEquals(
            "first main-thread turn must be bounded to the per-frame byte budget",
            budget.maxSlicesPerFrame,
            h.slicesPerTurn[0],
        )
        assertTrue("bytes must remain for a later frame", h.availableBytes() > 0)
    }

    @Test
    fun `a large queue drains across multiple frames and every byte is parsed`() {
        val h = SliceDrainHarness()
        // Explicit small byte budget (4 slices/turn) so the multi-frame byte-cap
        // tail is exercised deterministically under Robolectric (the default 128-
        // slice ceiling + non-advancing clock would otherwise drain it in ~1 turn).
        val budget = MainThreadDrainBudget(
            drainSliceBytes = sliceBytes,
            bytesPerFrame = sliceBytes * 4,
        )
        // Far more than one turn's byte budget so the multi-frame tail is
        // exercised: 4 slices/turn means this needs several yielded turns.
        val totalSlices = budget.maxSlicesPerFrame * 5 + 3
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
            "a ${totalSlices}-slice queue (byte budget ${budget.maxSlicesPerFrame}/turn) " +
                "must drain across multiple yielded frames, was $turnsWithWork turn(s): " +
                "${h.slicesPerTurn}",
            turnsWithWork >= 6,
        )
        // No single turn exceeded the per-frame byte budget.
        assertTrue(
            "no main-thread turn may exceed the per-frame slice budget; turns=${h.slicesPerTurn}",
            h.slicesPerTurn.all { it <= budget.maxSlicesPerFrame },
        )
    }

    @Test
    fun `a turn yields on the elapsed-time budget before the byte budget`() {
        // #796: when each slice costs real main-thread WORK (a clear-heavy
        // alt-screen slice), the ELAPSED-TIME budget must end the turn BEFORE the
        // byte budget. Model that by injecting a clock that advances per slice so a
        // few slices exceed maxTurnMillis, and a generous byte budget that would
        // otherwise let many more slices run in one turn.
        val h = SliceDrainHarness()
        // Generous byte budget: 16 slices/turn — far more than the time budget will
        // allow once each slice "costs" wall time.
        val budget = MainThreadDrainBudget(
            drainSliceBytes = sliceBytes,
            bytesPerFrame = sliceBytes * 16,
        )
        // Each dispatched slice advances the injected clock by 3 ms; with an 8 ms
        // turn budget the turn must stop after 3 slices (3 ms → 6 ms → 9 ms ≥ 8).
        val msPerSlice = 3L
        val maxTurnMillis = 8L
        val clock = AtomicInteger(0)
        val timedHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_NEW_INPUT) {
                    val before = h.pending.get()
                    if (before <= 0) return
                    h.pending.addAndGet(-minOf(sliceBytes, before))
                    while (h.slicesPerTurn.size <= h.currentTurn) h.slicesPerTurn.add(0)
                    h.slicesPerTurn[h.currentTurn] = h.slicesPerTurn[h.currentTurn] + 1
                    clock.addAndGet(msPerSlice.toInt())
                }
            }
        }
        val scheduler = MainThreadDrainScheduler(
            handler = timedHandler,
            msgNewInput = MSG_NEW_INPUT,
            availableBytes = h::availableBytes,
            budget = budget,
            yieldDelayMs = YIELD_MS,
            maxTurnMillis = maxTurnMillis,
            nowMillis = { clock.get().toLong() },
        )

        // Queue 16 slices — one full byte budget. The time budget must cut the
        // FIRST turn short well before 16 slices.
        h.pending.set(sliceBytes * 16)
        scheduler.requestDrain()

        h.currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()

        // First turn stopped on the TIME budget (3 slices = 9 ms ≥ 8 ms), NOT the
        // 16-slice byte budget. Bytes remain for a later frame.
        assertEquals(
            "the elapsed-time budget must cut the turn short (3 slices), not the " +
                "byte budget (16); turns=${h.slicesPerTurn}",
            3,
            h.slicesPerTurn[0],
        )
        assertTrue("bytes must remain for a later frame", h.availableBytes() > 0)
        assertTrue(
            "the time-bounded turn must be well under the byte budget",
            h.slicesPerTurn[0] < budget.maxSlicesPerFrame,
        )
    }

    @Test
    fun `the time-bounded drain still parses every byte across frames`() {
        // #796: the time budget only PACES the drain — it must never drop bytes.
        // With a per-slice clock cost, the whole queue must still drain in FIFO
        // order across the yielded frames.
        val h = SliceDrainHarness()
        val budget = MainThreadDrainBudget(
            drainSliceBytes = sliceBytes,
            bytesPerFrame = sliceBytes * 16,
        )
        val clock = AtomicInteger(0)
        val timedHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_NEW_INPUT) {
                    val before = h.pending.get()
                    if (before <= 0) return
                    h.pending.addAndGet(-minOf(sliceBytes, before))
                    while (h.slicesPerTurn.size <= h.currentTurn) h.slicesPerTurn.add(0)
                    h.slicesPerTurn[h.currentTurn] = h.slicesPerTurn[h.currentTurn] + 1
                    clock.addAndGet(3)
                }
            }
        }
        val maxTurnMillis = 8L
        val scheduler = MainThreadDrainScheduler(
            handler = timedHandler,
            msgNewInput = MSG_NEW_INPUT,
            availableBytes = h::availableBytes,
            budget = budget,
            yieldDelayMs = YIELD_MS,
            maxTurnMillis = maxTurnMillis,
            nowMillis = { clock.get().toLong() },
        )

        val totalSlices = 20
        h.pending.set(sliceBytes * totalSlices)
        scheduler.requestDrain()

        // The injected clock advances 3 ms per slice and never resets, but the
        // scheduler re-reads nowMillis() at EACH turn's start (turnStartedAt), so
        // every turn measures its own elapsed time and stops after ~3 slices
        // (3+3+3=9 ≥ 8). 20 slices therefore spread across ~7 yielded frames.
        // Drain to completion and assert no byte is lost.
        h.currentTurn = 0
        shadowOf(Looper.getMainLooper()).idle()
        var guard = 0
        while (h.availableBytes() > 0) {
            h.currentTurn += 1
            shadowOf(Looper.getMainLooper()).idleFor(YIELD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            guard += 1
            check(guard < 10_000) { "drain did not converge (possible wedge)" }
        }

        assertEquals("the full queue must drain — no lost bytes", 0, h.availableBytes())
        assertEquals("every slice parsed exactly once", totalSlices, h.slicesPerTurn.sum())
        val turnsWithWork = h.slicesPerTurn.count { it > 0 }
        assertTrue(
            "the time-bounded drain must spread across multiple yielded frames; " +
                "turns=${h.slicesPerTurn}",
            turnsWithWork >= 3,
        )
    }

    @Test
    fun `cancel drops a scheduled continuation`() {
        val h = SliceDrainHarness()
        // Explicit small byte budget so the first turn leaves a continuation
        // (under the default 128-slice ceiling + non-advancing Robolectric clock a
        // 10-slice queue would drain in one turn, leaving nothing to cancel).
        val budget = MainThreadDrainBudget(
            drainSliceBytes = sliceBytes,
            bytesPerFrame = sliceBytes * 4,
        )
        h.pending.set(sliceBytes * 10)
        val scheduler = h.scheduler(budget)
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
