package com.pocketshell.core.terminal.bridge

/**
 * Issue #803: the per-frame VT-append drain budget — the pure, Android-free
 * policy that the [MainThreadDrainScheduler] applies on the main looper.
 *
 * ## Why this exists
 *
 * The Termux emulator VT/SGR parse ([com.termux.terminal.TerminalEmulator.append]
 * = per-byte `processByte`) runs on the **main looper** (Termux's contract: the
 * emulator buffer is shared with the [com.termux.view.TerminalView] renderer and
 * has no internal locking, so the parse MUST stay single-threaded on main). A
 * dense Codex colored diff (walls of truecolor SGR spans — 3–10× more VT bytes
 * than visible characters) arrives as many `%output` chunks fed off-main into
 * Termux's 64 KB `mProcessToTerminalIOQueue`. Pre-#803 each chunk posted one
 * `MSG_NEW_INPUT`, and the vendored drain handler re-posted `MSG_NEW_INPUT`
 * **unbounded** while the queue stayed full — a back-to-back run of 16 KB
 * `append` parses on the main thread with NO frame yield, pinning the looper for
 * multiple seconds → input-dispatch ANR. `85835356` frame-gated only the
 * *render* signal downstream of the parse; the parse itself was never bounded.
 *
 * ## The contract this encodes
 *
 * The append stays single-threaded on main (Option A in the #803 spike — Option
 * B, moving the parse off-main, is deferred behind a thread-safety design). But
 * the drain is now **frame-budgeted**: each main-thread turn parses AT MOST
 * [bytesPerFrame] worth of queue (a small whole number of [drainSliceBytes]
 * slices), then the scheduler YIELDS to the next frame (`postDelayed`) before
 * draining more. Between budgeted turns the main looper services input dispatch,
 * Choreographer frames, and other UI work, so a heavy diff is spread across
 * frames instead of blocking input in one multi-second run.
 *
 * Byte ORDER and the FINAL byte are preserved unconditionally: the underlying
 * [com.termux.terminal.ByteQueue] is FIFO and the writer blocks (backpressure)
 * when full, so deferring the drain to a later frame can never drop, reorder, or
 * duplicate bytes — it only PACES when they are parsed. This is what keeps the
 * #651/#658 garble family from regressing: the emulator still sees every byte in
 * arrival order, just not all in one looper turn.
 *
 * @property drainSliceBytes one drain slice — must match the vendored
 *   `TerminalSession.MainThreadHandler.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES`
 *   (16 KB), the size of `mReceiveBuffer` that one `MSG_NEW_INPUT` dispatch
 *   parses.
 * @property bytesPerFrame the per-main-thread-turn parse budget. After this many
 *   bytes have been parsed in one turn the scheduler yields to the next frame.
 *   Chosen so a single turn's `append` cost stays well under one display frame
 *   even for dense SGR, while still draining several slices per turn so total
 *   throughput on a big diff is not needlessly slow.
 */
internal class MainThreadDrainBudget(
    val drainSliceBytes: Int = DEFAULT_DRAIN_SLICE_BYTES,
    val bytesPerFrame: Int = DEFAULT_BYTES_PER_FRAME,
) {
    init {
        require(drainSliceBytes > 0) { "drainSliceBytes must be > 0" }
        require(bytesPerFrame >= drainSliceBytes) {
            "bytesPerFrame ($bytesPerFrame) must be >= drainSliceBytes ($drainSliceBytes) " +
                "so at least one slice is parsed per turn (else the drain could never progress)"
        }
    }

    /**
     * The maximum number of [drainSliceBytes] slices to parse in a single
     * main-thread turn before yielding to the next frame. Always ≥ 1 (the
     * [bytesPerFrame] >= [drainSliceBytes] invariant guarantees forward
     * progress — a turn that parsed zero slices would deadlock the drain).
     */
    val maxSlicesPerFrame: Int
        get() = (bytesPerFrame / drainSliceBytes).coerceAtLeast(1)

    internal companion object {
        /**
         * Matches `TerminalSession.MainThreadHandler.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES`
         * and `SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES` (16 KB).
         */
        const val DEFAULT_DRAIN_SLICE_BYTES: Int = 16 * 1024

        /**
         * One 16 KB `append` slice parsed per main-thread turn, then a frame
         * yield. This is deliberately the SAME per-turn parse the vendored handler
         * always did (one `drainProcessOutputSlice`), so the per-#803 change never
         * INCREASES the contiguous main-thread occupancy of a single turn — it only
         * adds the guaranteed `postDelayed` frame yield BETWEEN turns.
         *
         * Why the yield is the fix (not a bigger/smaller per-turn budget): the
         * pre-#803 path re-posted `MSG_NEW_INPUT` with `sendEmptyMessage` (NO
         * delay). Under a producer flood that keeps the 64 KB queue full, those
         * zero-delay re-posts are ALWAYS ready at the front of the looper, so they
         * run back-to-back and starve any delayed/again-posted main-thread work
         * (input dispatch, the frame-coalesced repaint) — the looper never idles
         * for the whole burst. The #803 scheduler instead `postDelayed`s the
         * continuation one frame out, so the looper is guaranteed a gap each frame
         * to service input + render between parse turns. One slice/turn keeps each
         * gap close (≈1 slice of latency) while still pacing the total parse across
         * frames so the burst can never pin the thread past the frame budget.
         */
        const val DEFAULT_BYTES_PER_FRAME: Int = 16 * 1024
    }
}
