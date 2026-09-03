package com.pocketshell.next.terminal

import android.os.Handler
import android.os.Looper
import com.pocketshell.core.transport.PtyChannel
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole of app2's terminal plumbing: remote PTY bytes in, user keystrokes
 * out (rewrite task U-4).
 *
 * ## What it replaces
 *
 * The pre-rewrite client's `SshTerminalBridge` was ~1,450 lines of seed gates,
 * frame-budgeted drain schedulers, seed-tail pumps and non-parking lock
 * acquisition — machinery that existed because tmux `-CC` control mode fed the
 * emulator from TWO sources at once (a `capture-pane` snapshot and a live
 * `%output` stream) and they raced. app2 has exactly ONE source: a plain PTY
 * channel running `pocketshell sessions attach`, which is what a terminal
 * emulator was designed to read in the first place. So this class is a pump,
 * not a reconciler, and it has no gate, no snapshot, no reseed and no epoch.
 *
 * ## How the vendored emulator is driven
 *
 * `com.termux.terminal.TerminalSession` is upstream Termux's, unchanged: it
 * expects to spawn a LOCAL pty subprocess through JNI and then shuttle bytes
 * between that file descriptor and the emulator through two [ByteQueue]s. We
 * want the shuttle and not the subprocess, so — exactly as the old bridge did,
 * and for the same reasons — two package-private fields are set by reflection
 * at construction ([createRemoteTerminalSession]):
 *
 *  - `mEmulator` is pre-installed, so `TerminalSession.updateSize`'s
 *    "no emulator yet" branch (which calls `JNI.createSubprocess` and starts
 *    three local-pty threads) can never be taken;
 *  - `mShellPid` is set positive, because `TerminalSession.write` drops user
 *    input on the floor while it is 0.
 *
 * Everything else goes through the vendored queues, which is why this class
 * needs no repaint callback of its own: writing into `mProcessToTerminalIOQueue`
 * and posting `MSG_NEW_INPUT` makes the session's own main-thread handler parse
 * the bytes AND call `notifyScreenUpdate()`, which is what repaints the view.
 *
 * ## Threading
 *
 * - [pty] output is collected on [scope] (the caller supplies the dispatcher);
 *   each frame is written to the queue in slice-sized chunks with one
 *   `MSG_NEW_INPUT` per chunk, so the emulator parse happens on the main looper
 *   in bounded turns rather than one unbounded append.
 * - User input is drained on [scope] too, by a blocking read of the session's
 *   terminal→process queue. [stop] closes both queues, which is what unparks a
 *   blocked reader or writer — cancelling the scope alone could not.
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
 * @param emulator the vendored [TerminalSession] this bridge drives. Named for
 *   the role it plays here (it IS the emulator front end) per the task spec.
 * @param scope owns the two pumps. Cancelled by the caller, not by this class.
 * @param onOutputEnded fired once when [pty]'s output flow completes — remote
 *   EOF, a closed channel or a dropped transport. The session layer turns that
 *   into a user-visible state; this class has no opinion about it.
 */
class TerminalPtyBridge(
    private val pty: PtyChannel,
    private val emulator: TerminalSession,
    private val scope: CoroutineScope,
    private val cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX,
    private val cellHeightPx: Int = DEFAULT_CELL_HEIGHT_PX,
    private val onOutputEnded: () -> Unit = {},
) {

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private var outputJob: Job? = null
    private var inputJob: Job? = null

    /** Starts both pumps. Idempotent; a stopped bridge is not restartable. */
    fun start() {
        if (stopped.get()) return
        if (!started.compareAndSet(false, true)) return
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
        val screen: TerminalEmulator? = emulator.emulator
        if (screen != null && (screen.mColumns != cols || screen.mRows != rows)) {
            onMainThread { screen.resize(cols, rows, cellWidthPx, cellHeightPx) }
        }
        pty.resize(cols, rows)
    }

    /**
     * Stops both pumps and releases anything parked on the vendored queues.
     *
     * Closing the queues is not optional cleanup: a `ByteQueue.write` into a
     * FULL queue parks on the queue monitor and is woken ONLY by `close()`, so
     * a slow/dead main looper plus a fast remote would otherwise strand the
     * output pump past cancellation. Closing also makes the input pump's next
     * read return -1 so it retires immediately instead of on its next poll
     * tick. Idempotent.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { TerminalSessionInternals.closeTerminalToProcessQueue(emulator) }
        runCatching { TerminalSessionInternals.closeProcessToTerminalQueue(emulator) }
        outputJob?.cancel()
        inputJob?.cancel()
        outputJob = null
        inputJob = null
    }

    // --- pumps ---------------------------------------------------------------

    /**
     * Remote bytes → emulator.
     *
     * One `MSG_NEW_INPUT` per written slice, because the vendored
     * `MainThreadHandler` drains EXACTLY one [DRAIN_SLICE_BYTES] slice per
     * message and deliberately does not re-post itself (upstream PocketShell
     * #796/#803). Posting per slice therefore parses the whole frame while
     * keeping each main-looper turn to one bounded append.
     */
    private suspend fun pumpRemoteOutput() {
        val handler = TerminalSessionInternals.mainThreadHandler(emulator)
        try {
            pty.output.collect { frame ->
                var offset = 0
                while (offset < frame.size) {
                    val length = minOf(DRAIN_SLICE_BYTES, frame.size - offset)
                    val accepted = TerminalSessionInternals.writeProcessToTerminalQueue(
                        session = emulator,
                        data = frame,
                        offset = offset,
                        length = length,
                    )
                    // false == the queue was closed under us, i.e. `stop()` ran.
                    if (!accepted) return@collect
                    handler.sendEmptyMessage(MSG_NEW_INPUT)
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
     * The vendored view writes typed characters, IME commits and key-handler
     * escape sequences into `mTerminalToProcessIOQueue`; upstream's local-pty
     * writer thread is never started (we bypassed `initializeEmulator`), so this
     * is the only consumer.
     *
     * ## Why it polls
     *
     * `ByteQueue` offers a BLOCKING read and no callback, and upstream's
     * consumer is a dedicated `Thread` that can afford to park in it. A
     * coroutine cannot: a blocking read pins whatever thread the dispatcher gave
     * it, is not interruptible by cancellation, and on a test dispatcher would
     * wedge the whole scheduler. Polling with `delay` keeps the pump
     * cancellable, keeps it honest under virtual time, and costs one cheap
     * synchronized read per [INPUT_POLL_MS]. At [INPUT_POLL_MS] the added
     * keystroke latency is well under a frame — invisible next to the network
     * round trip that follows it.
     */
    private suspend fun pumpUserInput() {
        val buffer = ByteArray(INPUT_BUFFER_BYTES)
        while (scope.isActive && !stopped.get()) {
            val read = runCatching {
                TerminalSessionInternals.readTerminalToProcessQueue(emulator, buffer)
            }.getOrElse { -1 }
            // -1 == the queue was closed, i.e. `stop()` ran. 0 == nothing typed.
            if (read < 0) return
            if (read == 0) {
                delay(INPUT_POLL_MS)
                continue
            }
            val payload = buffer.copyOf(read)
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

    private suspend fun onMainThread(block: () -> Unit) {
        val handler = TerminalSessionInternals.mainThreadHandler(emulator)
        if (Looper.myLooper() === handler.looper) {
            block()
            return
        }
        val done = CompletableDeferred<Unit>()
        handler.post {
            runCatching(block)
            done.complete(Unit)
        }
        done.await()
    }

    companion object {
        /**
         * Matches `TerminalSession.MSG_NEW_INPUT` (private static final int = 1
         * in the vendored source). Hard-coded rather than reflected per call; a
         * Termux refresh that changed it would show up in the vendored diff.
         */
        const val MSG_NEW_INPUT: Int = 1

        /**
         * Must match `TerminalSession.MainThreadHandler.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES`
         * (the vendored `mReceiveBuffer` size). One posted message drains one
         * slice, so writing in slice-sized chunks makes "one message per chunk"
         * exactly enough to parse everything we wrote.
         */
        const val DRAIN_SLICE_BYTES: Int = 2 * 1024

        /** Matches the size of the vendored terminal→process queue. */
        private const val INPUT_BUFFER_BYTES: Int = 4096

        /** How often the input pump looks for freshly typed bytes. */
        const val INPUT_POLL_MS: Long = 8L

        /**
         * Any positive value unlocks `TerminalSession.write`'s `mShellPid > 0`
         * gate. Nothing ever reads it back: `finishIfRunning()` (the one method
         * that would `SIGKILL` it) is never called on a remote session.
         */
        const val FAKE_SHELL_PID: Int = 1

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
 * Builds a [TerminalSession] that renders a REMOTE stream instead of spawning a
 * local pty subprocess.
 *
 * See [TerminalPtyBridge]'s class doc for why the two field writes are needed.
 * They are done here, once, at construction — never later and never
 * conditionally — so there is exactly one place in app2 that knows the vendored
 * session has an inside.
 */
fun createRemoteTerminalSession(
    cols: Int = TerminalPtyBridge.DEFAULT_COLS,
    rows: Int = TerminalPtyBridge.DEFAULT_ROWS,
    cellWidthPx: Int = TerminalPtyBridge.DEFAULT_CELL_WIDTH_PX,
    cellHeightPx: Int = TerminalPtyBridge.DEFAULT_CELL_HEIGHT_PX,
    transcriptRows: Int = TerminalPtyBridge.DEFAULT_TRANSCRIPT_ROWS,
    client: TerminalSessionClient = NoOpTerminalSessionClient(),
): TerminalSession {
    val session = TerminalSession(
        /* shellPath = */ "/system/bin/sh",
        /* cwd = */ "/",
        /* args = */ emptyArray(),
        /* env = */ emptyArray(),
        /* transcriptRows = */ transcriptRows,
        /* client = */ client,
    )
    val emulator = TerminalEmulator(
        /* session = */ session,
        /* columns = */ cols,
        /* rows = */ rows,
        /* cellWidthPixels = */ cellWidthPx,
        /* cellHeightPixels = */ cellHeightPx,
        /* transcriptRows = */ transcriptRows,
        /* client = */ client,
    )
    TerminalSessionInternals.setEmulator(session, emulator)
    TerminalSessionInternals.setShellPid(session, TerminalPtyBridge.FAKE_SHELL_PID)
    return session
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

/**
 * The four package-private members of the vendored [TerminalSession] app2
 * touches, and nothing else.
 *
 * Reflection rather than a patch to the vendored source because
 * `shared/core-terminal` is pinned byte-identical to upstream Termux (its
 * `build.gradle.kts` states the "do not refactor" rule and `VENDORED.md`
 * documents the refresh procedure). A missing field fails loudly, naming the
 * refresh as the likely cause, instead of degrading into a silently dead
 * terminal.
 */
internal object TerminalSessionInternals {

    private val emulatorField: Field by lazy { field("mEmulator") }
    private val shellPidField: Field by lazy { field("mShellPid") }
    private val processToTerminalQueueField: Field by lazy { field("mProcessToTerminalIOQueue") }
    private val terminalToProcessQueueField: Field by lazy { field("mTerminalToProcessIOQueue") }
    private val mainThreadHandlerField: Field by lazy { field("mMainThreadHandler") }

    private val byteQueueClass: Class<*> by lazy { Class.forName("com.termux.terminal.ByteQueue") }

    private val byteQueueWrite by lazy {
        byteQueueClass.getDeclaredMethod(
            "write",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    private val byteQueueRead by lazy {
        byteQueueClass.getDeclaredMethod(
            "read",
            ByteArray::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    private val byteQueueClose by lazy {
        byteQueueClass.getDeclaredMethod("close").apply { isAccessible = true }
    }

    fun setEmulator(session: TerminalSession, emulator: TerminalEmulator) {
        emulatorField.set(session, emulator)
    }

    fun setShellPid(session: TerminalSession, pid: Int) {
        shellPidField.setInt(session, pid)
    }

    fun mainThreadHandler(session: TerminalSession): Handler =
        mainThreadHandlerField.get(session) as Handler

    /** Returns false when the queue was closed before the write completed. */
    fun writeProcessToTerminalQueue(
        session: TerminalSession,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val queue = processToTerminalQueueField.get(session)
        return byteQueueWrite.invoke(queue, data, offset, length) as Boolean
    }

    /**
     * NON-blocking read: 0 when nothing has been typed, -1 once the queue is
     * closed. Blocking is deliberately not used — see
     * [TerminalPtyBridge.pumpUserInput].
     */
    fun readTerminalToProcessQueue(session: TerminalSession, buffer: ByteArray): Int {
        val queue = terminalToProcessQueueField.get(session)
        return byteQueueRead.invoke(queue, buffer, false) as Int
    }

    fun closeTerminalToProcessQueue(session: TerminalSession) {
        byteQueueClose.invoke(terminalToProcessQueueField.get(session))
    }

    fun closeProcessToTerminalQueue(session: TerminalSession) {
        byteQueueClose.invoke(processToTerminalQueueField.get(session))
    }

    private fun field(name: String): Field = try {
        TerminalSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    } catch (missing: NoSuchFieldException) {
        throw IllegalStateException(
            "TerminalPtyBridge expected field `$name` on ${TerminalSession::class.java.name}. " +
                "The vendored Termux sources were probably refreshed without updating " +
                "app2's terminal bridge (see shared/core-terminal/VENDORED.md).",
            missing,
        )
    }
}
