package com.pocketshell.next.terminal

import com.pocketshell.core.transport.PtyChannel
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole of app2's terminal plumbing: remote PTY bytes in, user keystrokes
 * out (rewrite task U-4).
 *
 * ## What it replaces
 *
 * The pre-rewrite client's `SshTerminalBridge` was ~1,450 lines of seed gates,
 * frame-budgeted drain schedulers, seed-tail pumps and non-parking lock
 * acquisition — machinery that existed because the old control-mode transport
 * fed the emulator from TWO sources at once (a snapshot and a live output
 * stream) and they raced. app2 has exactly ONE source: a plain PTY
 * channel running `pocketshell sessions attach`, which is what a terminal
 * emulator was designed to read in the first place. So this class is a pump,
 * not a reconciler, and it has no gate, no snapshot, no reseed and no epoch.
 *
 * ## Two flows, no queues, no reflection
 *
 * [com.termux.terminal.TerminalSession] is PocketShell's own remote-only class
 * (issue #2566), not upstream Termux's local-pty one, so this bridge talks to
 * it through plain public methods:
 *
 *  - **Output.** Every frame from [pty] is applied to the session in slices of
 *    at most [DRAIN_SLICE_BYTES], one `withContext(mainDispatcher)` turn each.
 *    That bound is the #796 fix: a single uninterruptible `append` of a large
 *    clear-heavy alt-screen redraw pinned the looper for over a second on a
 *    swiftshader emulator. Slicing keeps each main-thread turn small without a
 *    scheduler, a budget or a coalescer.
 *  - **Back-pressure** falls out of the same call: `collect` suspends until the
 *    slice has been applied, so a slow main thread throttles the collector,
 *    which throttles the SSH channel window — exactly what the vendored 64 KB
 *    queue used to do by filling up.
 *  - **Input.** [start] installs a [TerminalSession.InputSink]; everything the
 *    user types (and every query reply the emulator answers) is handed to an
 *    unbounded [Channel] and written to [pty] in order by ONE consumer
 *    coroutine. The sink is called from the view's input dispatch on the main
 *    thread, so it only offers to the channel — it never blocks and never
 *    polls.
 *
 * ## Ownership of the session's sink is stop-then-start
 *
 * A session outlives the channel it is attached through (that is what keeps the
 * last frame on screen across a reconnect), so several bridges drive one
 * session over its life — but never at the same time. [stop] clears the sink,
 * the next bridge's [start] installs its own.
 * [SessionViewModel.releaseChannel] is the single place that sequences the two.
 *
 * Between those two points nobody is listening, and that gap spans a whole SSH
 * dial: the session itself holds what is typed there (up to
 * [TerminalSession.PENDING_INPUT_CAPACITY_BYTES]) and hands it to the next sink
 * on install, so a command typed at the "Reconnecting" banner runs when the
 * ladder lands. The buffer is the session's, not this class's — a bridge owns
 * nothing across attaches.
 *
 * ## Resize has ONE owner
 *
 * [resize] is the only place `pty.resize` is called. The emulator half is
 * applied only when the emulator is not already at that size, because in the
 * app the vendored `TerminalView.updateSize()` has usually resized it already
 * (it owns the font metrics) and then reported the new size back through
 * [com.pocketshell.next.terminal.SessionViewModel.onResized]. Re-applying it
 * would be a second owner writing the same state with worse cell metrics.
 *
 * @param pty the remote channel; spent once its output completes.
 * @param emulator the [TerminalSession] this bridge drives. Named for the role
 *   it plays here (it IS the emulator front end) per the task spec.
 * @param scope owns the two pumps. Cancelled by the caller, not by this class.
 * @param mainDispatcher where the emulator is fed. The emulator is
 *   single-threaded by upstream contract and [TerminalSession.append] enforces
 *   it, so this must dispatch to the main thread; injected rather than
 *   hard-coded so a test can drive it on a virtual clock.
 * @param onOutputEnded fired once when [pty]'s output flow completes — remote
 *   EOF, a closed channel or a dropped transport. The session layer turns that
 *   into a user-visible state; this class has no opinion about it.
 */
class TerminalPtyBridge(
    private val pty: PtyChannel,
    private val emulator: TerminalSession,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX,
    private val cellHeightPx: Int = DEFAULT_CELL_HEIGHT_PX,
    private val onOutputEnded: () -> Unit = {},
) {

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /**
     * Typed bytes waiting for the channel.
     *
     * Unbounded because the producer is the main thread inside a keystroke: a
     * bounded channel would either drop the keystroke or block the UI, and a
     * human cannot outrun an SSH channel anyway. Closed by [stop], which is
     * what retires the consumer.
     */
    private val input = Channel<ByteArray>(Channel.UNLIMITED)

    private var outputJob: Job? = null
    private var inputJob: Job? = null

    /** Starts both pumps. Idempotent; a stopped bridge is not restartable. */
    fun start() {
        if (stopped.get()) return
        if (!started.compareAndSet(false, true)) return
        emulator.setInputSink { data, offset, count ->
            input.trySend(data.copyOfRange(offset, offset + count))
        }
        outputJob = scope.launch { pumpRemoteOutput() }
        inputJob = scope.launch { pumpUserInput() }
    }

    /**
     * Applies a new terminal size to BOTH ends.
     *
     * The emulator is only touched when it disagrees with the requested size —
     * see the class doc on single ownership. `pty.resize` is unconditional: a
     * `window-change` for the size the remote already has is harmless, and
     * skipping it on a "no change" the emulator happened to have applied first
     * would leave the remote at the old size forever.
     */
    suspend fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (stopped.get()) return
        val screen: TerminalEmulator = emulator.emulator
        if (screen.mColumns != cols || screen.mRows != rows) {
            withContext(mainDispatcher) {
                emulator.updateSize(cols, rows, cellWidthPx, cellHeightPx)
            }
        }
        pty.resize(cols, rows)
    }

    /**
     * Retires both pumps and releases the session's input sink.
     *
     * Every way an attach can end goes through here: a drop, a clean remote
     * exit, a requested close and the screen being left. What it deliberately
     * does NOT touch is the [TerminalSession] itself — the emulator keeps its
     * grid, which is what leaves the last frame on screen under the reconnect
     * banner, and a fresh bridge can adopt the same session by starting on it.
     *
     * Bytes typed after this and before the next bridge starts go nowhere near
     * THIS bridge — its channel is spent and its pumps are cancelled. The
     * session holds them instead and flushes them to the next bridge's sink
     * ([TerminalSession.setInputSink]), which is what makes typing at a
     * reconnecting terminal work. Idempotent, and a stopped bridge is not
     * restartable.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        emulator.setInputSink(null)
        input.close()
        outputJob?.cancel()
        inputJob?.cancel()
        outputJob = null
        inputJob = null
    }

    // --- pumps ---------------------------------------------------------------

    /**
     * Remote bytes → emulator.
     *
     * One main-thread turn per [DRAIN_SLICE_BYTES] slice (see the class doc):
     * the loop suspends on each turn, so the frame is parsed in bounded pieces
     * with the looper free to draw in between, and the collector cannot run
     * ahead of the main thread.
     */
    private suspend fun pumpRemoteOutput() {
        try {
            pty.output.collect { frame ->
                var offset = 0
                while (offset < frame.size) {
                    val length = minOf(DRAIN_SLICE_BYTES, frame.size - offset)
                    val start = offset
                    withContext(mainDispatcher) { emulator.append(frame, start, length) }
                    offset += length
                }
            }
        } finally {
            // Remote EOF, a torn-down channel, or cancellation. `stop()` is the
            // cancelling path and has already told the session layer, so only a
            // genuine end reports.
            if (!stopped.get()) onOutputEnded()
        }
    }

    /**
     * User input → remote.
     *
     * One consumer, so the order bytes were typed in is the order they reach
     * the channel. The loop ends when [input] is closed by [stop] and drained.
     */
    private suspend fun pumpUserInput() {
        for (payload in input) {
            try {
                pty.write(payload)
            } catch (failure: Throwable) {
                // A dead channel: the output pump's completion is the event the
                // session layer reacts to, so this pump just retires.
                if (failure is CancellationException) throw failure
                return
            }
        }
    }

    companion object {

        /**
         * How much of a remote frame is applied to the emulator in one
         * main-thread turn.
         *
         * PocketShell #796/#803: a 16 KB slice of clear-heavy alt-screen
         * content (an agent's full-viewport redraw: `ESC[H` + 30x `ESC[K` per
         * chunk) does ~4000 blockClear/allocateFullLineIfNecessary ops and on a
         * swiftshader emulator pinned the looper for >1 s. 2 KB keeps the
         * worst-case single atomic append well under the responsiveness budget.
         * The bound is this class's own now — nothing vendored has to agree
         * with it.
         */
        const val DRAIN_SLICE_BYTES: Int = 2 * 1024

        /**
         * Initial emulator geometry. 80x24 is the historical default a remote
         * shell assumes when nobody says otherwise, and it only ever survives
         * for the handful of frames before the view reports its real size.
         */
        const val DEFAULT_COLS: Int = 80
        const val DEFAULT_ROWS: Int = 24

        /**
         * Placeholder cell metrics. [TerminalEmulator]'s constructor needs
         * positive values, and the real ones come from the renderer's font
         * metrics on first layout. They are only ever read back by the `CSI 14t`
         * / `CSI 16t` pixel-size query responses.
         */
        const val DEFAULT_CELL_WIDTH_PX: Int = 8
        const val DEFAULT_CELL_HEIGHT_PX: Int = 16

        /** Scrollback depth — upstream Termux's own default. */
        const val DEFAULT_TRANSCRIPT_ROWS: Int = 2000
    }
}

/**
 * Builds the [TerminalSession] this screen renders into.
 *
 * The one construction site in app2, so the palette install and the geometry
 * defaults have exactly one answer. [installTerminalPalette] runs BEFORE the
 * constructor because the session builds its emulator, and the emulator's
 * constructor copies the default scheme into the live palette — patching it
 * afterwards would hold only until the first `reset`.
 */
fun createRemoteTerminalSession(
    cols: Int = TerminalPtyBridge.DEFAULT_COLS,
    rows: Int = TerminalPtyBridge.DEFAULT_ROWS,
    cellWidthPx: Int = TerminalPtyBridge.DEFAULT_CELL_WIDTH_PX,
    cellHeightPx: Int = TerminalPtyBridge.DEFAULT_CELL_HEIGHT_PX,
    transcriptRows: Int = TerminalPtyBridge.DEFAULT_TRANSCRIPT_ROWS,
    client: TerminalSessionClient = NoOpTerminalSessionClient(),
): TerminalSession {
    installTerminalPalette()
    return TerminalSession(
        /* columns = */ cols,
        /* rows = */ rows,
        /* cellWidthPx = */ cellWidthPx,
        /* cellHeightPx = */ cellHeightPx,
        /* transcriptRows = */ transcriptRows,
        /* client = */ client,
    )
}

/**
 * The client a session carries until a view adopts it. Every callback is a
 * no-op: with no view attached there is nothing to repaint, no clipboard to
 * touch and no bell to ring.
 */
class NoOpTerminalSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
