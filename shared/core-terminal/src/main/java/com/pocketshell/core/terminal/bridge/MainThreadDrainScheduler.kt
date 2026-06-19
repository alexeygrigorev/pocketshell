package com.pocketshell.core.terminal.bridge

import android.os.Handler
import android.os.Message
import android.os.SystemClock
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
 *  - **Budgets** the parse per main-thread turn by TWO caps, whichever trips
 *    first (#796):
 *      - a **byte cap** — one turn drains at most
 *        [MainThreadDrainBudget.maxSlicesPerFrame] × [MainThreadDrainBudget.drainSliceBytes]
 *        bytes (dispatching the single-slice `MSG_NEW_INPUT` inline via
 *        [Handler.dispatchMessage], each call running exactly one
 *        `mEmulator.append`). This protects cheap-append throughput.
 *      - an **elapsed-time cap** ([maxTurnMillis]) — after each slice, if the
 *        turn has already spent more than the budget of main-thread wall time,
 *        it STOPS even though the byte cap is not yet reached. This is the #796
 *        fix: a 2 KB slice of clear-heavy alt-screen content (an agent's
 *        full-viewport `ESC[H` + 30×`ESC[K` redraw) does hundreds of
 *        `blockClear` ops — far more main-thread WORK per byte than append text
 *        — so the byte count alone does NOT model the cost and a byte-budgeted
 *        turn could still pin the looper past the frame deadline. The time cap
 *        ends the turn as soon as the work (not the byte count) exceeds budget.
 *  - **Yields** between turns: if the queue still has bytes after a budgeted
 *    turn (byte cap OR time cap), the next turn is `postDelayed` one
 *    [yieldDelayMs] frame later, so the main looper services input dispatch /
 *    Choreographer / other UI work between budgeted parse turns. A heavy diff —
 *    append-dense OR clear-dense — is spread across frames instead of blocking
 *    input in one multi-second run.
 *
 * ## Ordering & final-byte safety (no #651/#658 regression)
 *
 * The underlying [com.termux.terminal.ByteQueue] is FIFO and the off-main writer
 * BLOCKS (backpressure) when the 64 KB queue is full, so deferring a drain to a
 * later frame can never drop, reorder, or duplicate bytes — it only PACES when
 * they are parsed. Each `dispatchMessage(MSG_NEW_INPUT)` reads the EXACT next
 * FIFO bytes from the queue (`mProcessToTerminalIOQueue.read`), so the
 * elapsed-time yield (#796) — which only stops dispatching mid-burst and reposts
 * the continuation — resumes on precisely the next unread byte. No reorder, no
 * drop, no double-process across the yield: the queue position IS the resume
 * cursor. The drain loop keeps dispatching slices (across frames) until
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
 * @param maxTurnMillis the per-turn ELAPSED main-thread time budget (#796). A
 *   turn stops dispatching slices once it has spent more than this much wall
 *   time, even if the byte budget is not yet reached, and reposts the
 *   remainder. ~8 ms (half a 16 ms display frame) by default so a single drain
 *   turn never occupies more than half a frame of main-thread work even for
 *   clear-heavy content — leaving the other half for input dispatch + render.
 *   The granularity floor is one slice (a turn always parses ≥ 1 slice for
 *   forward progress), so the worst-case turn is `maxTurnMillis` plus one
 *   2 KB-slice's cost, which stays far under the responsiveness budget.
 * @param nowMillis main-thread monotonic clock used to measure turn elapsed
 *   time. Defaults to [SystemClock.uptimeMillis]; injectable so the Robolectric
 *   contract test can drive it deterministically.
 */
internal class MainThreadDrainScheduler(
    private val handler: Handler,
    private val msgNewInput: Int,
    private val availableBytes: () -> Int,
    private val budget: MainThreadDrainBudget = MainThreadDrainBudget(),
    private val yieldDelayMs: Long = DEFAULT_YIELD_DELAY_MS,
    private val maxTurnMillis: Long = DEFAULT_MAX_TURN_MILLIS,
    private val nowMillis: () -> Long = { SystemClock.uptimeMillis() },
) {
    // True while a drain turn is scheduled (posted) or running. Collapses a burst
    // of requestDrain() calls into a single pending turn. Written from any thread
    // (requestDrain) and from the main looper (drainTurn); atomic for visibility.
    private val pending = AtomicBoolean(false)

    private val drainTurn = Runnable { runDrainTurn() }

    /**
     * Ask the scheduler to drain the queue. Idempotent across a burst: if a turn
     * is already pending or running, this is a no-op — `pending` stays true for
     * the whole turn, and the running/queued turn observes the newly-written
     * bytes via [availableBytes] at its end (it re-reads the queue and reposts a
     * DELAYED continuation; see [runDrainTurn]). This is what keeps a producer
     * flood from chaining no-delay back-to-back turns and starving the looper
     * (#796). Safe from any thread.
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
        // NOTE: [pending] stays TRUE for the whole turn. We deliberately do NOT
        // reset it up front. Under a producer flood, an off-main requestDrain()
        // racing this turn would otherwise win the compareAndSet and
        // `handler.post(drainTurn)` a continuation with NO delay — chaining
        // back-to-back drain turns with no looper gap and starving input dispatch
        // / the ping sampler / the frame-coalesced repaint (the #796 residual
        // stall). Keeping `pending` true makes a concurrent requestDrain a no-op;
        // THIS turn owns the next scheduling decision and always reposts DELAYED
        // (one frame), so the looper is guaranteed a servicing gap between turns
        // even under a sustained flood. The newly-written flood bytes are not
        // lost: this turn re-reads availableBytes() at its end and reposts.

        var slicesThisTurn = 0
        val maxSlices = budget.maxSlicesPerFrame
        val turnStartedAt = nowMillis()
        // Drain up to the per-turn budget. Each dispatchMessage runs exactly one
        // 2 KB mEmulator.append on this (main) thread, reading the EXACT next FIFO
        // bytes from the queue. A turn ends when WHICHEVER budget trips first:
        //   - the byte cap (maxSlices) — protects cheap-append throughput, OR
        //   - the elapsed-time cap (maxTurnMillis) — the #796 fix: clear-heavy
        //     slices cost far more main-thread WORK per byte, so a turn must yield
        //     on TIME, not just on byte count, to keep one turn under a frame.
        // We always parse AT LEAST one slice (forward progress) and check the time
        // budget AFTER each slice, so the worst-case turn is one slice past the
        // budget — bounded by the small 2 KB slice cost. Stop early if the queue
        // empties.
        while (slicesThisTurn < maxSlices && availableBytes() > 0) {
            handler.dispatchMessage(Message.obtain(handler, msgNewInput))
            slicesThisTurn += 1
            // Time budget: once this turn has occupied the main thread longer than
            // maxTurnMillis, stop and let the continuation resume next frame —
            // even if the byte cap and the queue are not yet exhausted. Checked
            // after at least one slice so the drain always progresses.
            if (nowMillis() - turnStartedAt >= maxTurnMillis) break
        }

        // Decide the next turn. If bytes remain after the budget (byte cap OR time
        // cap), YIELD to the next frame: post the continuation DELAYED by one frame
        // (keeping `pending` true) so the main looper services input dispatch /
        // frames between budgeted parse turns. This is the core #803/#796 fix — the
        // parse is paced across frames instead of running unbounded back-to-back.
        // The continuation resumes on the exact next unread FIFO byte (the queue
        // position is the resume cursor), so no byte is reordered, dropped, or
        // double-processed. The FINAL byte is still parsed because we keep
        // continuing until availableBytes() == 0.
        if (availableBytes() > 0) {
            // `pending` is already true; just post the delayed continuation.
            handler.postDelayed(drainTurn, yieldDelayMs)
            return
        }

        // Queue looks empty: release the pending flag so a FUTURE requestDrain can
        // schedule a fresh turn. Then re-check once — if a producer wrote between
        // our availableBytes() read above and this release, those bytes would be
        // stranded (the racing requestDrain saw pending==true and no-op'd). Re-arm
        // and post (delayed) so they drain on the next frame. This closes the
        // empty-queue race without ever scheduling a no-delay back-to-back turn.
        pending.set(false)
        if (availableBytes() > 0 && pending.compareAndSet(false, true)) {
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

        /**
         * Per-turn elapsed main-thread time budget (#796). Half a 16 ms display
         * frame: a single drain turn yields once it has spent more than this much
         * wall time parsing, so it never occupies more than ~half a frame even
         * for clear-heavy content — leaving the other half for input dispatch +
         * the frame-coalesced repaint. Combined with the small 2 KB slice (the
         * granularity floor), the worst-case turn is ~8 ms plus one slice, well
         * under the responsiveness / ANR budget. The byte cap still bounds cheap
         * append load (the time cap rarely trips there); the time cap is what
         * bounds work-dense clear-heavy bursts the byte count cannot model.
         */
        const val DEFAULT_MAX_TURN_MILLIS: Long = 8L
    }
}
