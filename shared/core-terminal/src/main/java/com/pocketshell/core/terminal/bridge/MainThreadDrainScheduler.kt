package com.pocketshell.core.terminal.bridge

import android.os.Handler
import android.os.Message
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #803: frame-budgeted, main-looper drain of the Termux process→terminal
 * queue. Replaces the pre-#803 path where every `%output` chunk posted its own
 * `MSG_NEW_INPUT` and the vendored handler re-posted `MSG_NEW_INPUT` UNBOUNDED
 * while the queue stayed full — a back-to-back run of 16 KB
 * [com.termux.terminal.TerminalEmulator.append] VT parses on the main thread
 * with no frame yield, which pinned the looper for seconds on a dense colored
 * diff → input-dispatch ANR (the maintainer's heavy-colored-diff freeze).
 *
 * ## What it does
 *
 *  - **Collapses** the per-chunk posts: [requestDrain] is idempotent — any number
 *    of off-main `feedBytes` chunks arriving before the next drain turn schedule
 *    AT MOST one pending main-looper drain (an [AtomicBoolean] pending flag plus
 *    `removeMessages` mirror the vendored handler's own `removeMessages`).
 *  - **Budgets** the parse per main-thread turn: one turn drains at most
 *    [MainThreadDrainBudget.maxSlicesPerFrame] × [MainThreadDrainBudget.drainSliceBytes]
 *    bytes (dispatching the single-slice `MSG_NEW_INPUT` inline via
 *    [Handler.dispatchMessage], each call running exactly one
 *    `mEmulator.append`), then **yields** to the next frame.
 *  - **Yields** between turns: if the queue still has bytes after a budgeted
 *    turn, the next turn is `postDelayed` one [yieldDelayMs] frame later, so the
 *    main looper services input dispatch / Choreographer / other UI work between
 *    budgeted parse turns. A heavy diff is spread across frames instead of
 *    blocking input in one multi-second run.
 *
 * ## Ordering & final-byte safety (no #651/#658 regression)
 *
 * The underlying [com.termux.terminal.ByteQueue] is FIFO and the off-main writer
 * BLOCKS (backpressure) when the 64 KB queue is full, so deferring a drain to a
 * later frame can never drop, reorder, or duplicate bytes — it only PACES when
 * they are parsed. The drain loop keeps dispatching slices (across frames) until
 * `availableBytes` reaches 0, so the FINAL byte of a burst is always parsed.
 * The emulator stays single-threaded on main (Termux's contract honored; Option
 * B off-main parse is deferred).
 *
 * ## Threading
 *
 * [requestDrain] is safe from any thread (it only flips an atomic + posts to the
 * handler). The drain turns themselves run on the handler's (main) looper, so
 * `availableBytes` / `dispatchSlice` are touched only there.
 *
 * @param handler the session's main-looper handler (the vendored
 *   `TerminalSession.mMainThreadHandler`).
 * @param msgNewInput the `MSG_NEW_INPUT` message id the handler routes to a
 *   single-slice drain (`TerminalSession.MSG_NEW_INPUT` == 1).
 * @param availableBytes reads the bytes still buffered in the process→terminal
 *   queue (reflected `TerminalSession.availableProcessOutputBytes()`); read on
 *   the main looper only.
 * @param budget the per-frame byte budget policy.
 * @param yieldDelayMs the delay used when yielding to the next frame. ~1 frame
 *   (16 ms) by default so a continued drain resumes promptly without starving
 *   the looper.
 */
internal class MainThreadDrainScheduler(
    private val handler: Handler,
    private val msgNewInput: Int,
    private val availableBytes: () -> Int,
    private val budget: MainThreadDrainBudget = MainThreadDrainBudget(),
    private val yieldDelayMs: Long = DEFAULT_YIELD_DELAY_MS,
) {
    // True while a drain turn is scheduled (posted) or running. Collapses a burst
    // of requestDrain() calls into a single pending turn. Written from any thread
    // (requestDrain) and from the main looper (drainTurn); atomic for visibility.
    private val pending = AtomicBoolean(false)

    private val drainTurn = Runnable { runDrainTurn() }

    /**
     * Ask the scheduler to drain the queue. Idempotent across a burst: if a turn
     * is already pending or running, this is a no-op (the running/queued turn
     * will observe the newly-written bytes via [availableBytes]). Safe from any
     * thread.
     */
    fun requestDrain() {
        if (pending.compareAndSet(false, true)) {
            handler.post(drainTurn)
        }
    }

    /**
     * Drop any scheduled drain turn. Called when the bridge stops so a torn-down
     * session leaves no posted runnable on the main looper.
     */
    fun cancel() {
        pending.set(false)
        handler.removeCallbacks(drainTurn)
    }

    private fun runDrainTurn() {
        // Mark "not pending" up front so a requestDrain() racing the tail of this
        // turn re-schedules cleanly (rather than being swallowed because we still
        // looked pending). If we yield-and-continue below we re-post explicitly.
        pending.set(false)

        var slicesThisTurn = 0
        val maxSlices = budget.maxSlicesPerFrame
        // Drain up to the per-frame slice budget. Each dispatchMessage runs
        // exactly one 16 KB mEmulator.append on this (main) thread, in queue
        // (FIFO) order. Stop early if the queue empties.
        while (slicesThisTurn < maxSlices && availableBytes() > 0) {
            handler.dispatchMessage(Message.obtain(handler, msgNewInput))
            slicesThisTurn += 1
        }

        // If bytes remain after the budget, YIELD to the next frame: re-arm the
        // pending flag and post the continuation delayed by one frame so the main
        // looper services input dispatch / frames between budgeted parse turns.
        // This is the core #803 fix — the parse is paced across frames instead of
        // running unbounded back-to-back. The FINAL byte is still parsed because
        // we keep continuing until availableBytes() == 0.
        if (availableBytes() > 0) {
            pending.set(true)
            handler.postDelayed(drainTurn, yieldDelayMs)
        }
    }

    internal companion object {
        /**
         * Yield delay between budgeted drain turns. ~1 display frame (60Hz) so a
         * continued drain resumes on the next frame — long enough to let the
         * looper service input dispatch + the frame-coalesced repaint between
         * parse turns, short enough that a big diff still finishes in a small
         * handful of frames.
         */
        const val DEFAULT_YIELD_DELAY_MS: Long = 16L
    }
}
