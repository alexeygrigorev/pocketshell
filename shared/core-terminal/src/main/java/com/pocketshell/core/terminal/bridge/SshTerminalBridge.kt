package com.pocketshell.core.terminal.bridge

import android.os.Handler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.OutputStream
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges raw bytes from a remote source (typically an SSH `Session.startShell`
 * channel) into the vendored Termux [TerminalEmulator] / [TerminalSession]
 * machinery, and forwards user input from the emulator back to the same
 * remote stream.
 *
 * ## Why this exists
 *
 * Upstream Termux's [TerminalSession] is wired to spawn a *local* PTY
 * subprocess via the JNI `libtermux.so` library
 * ([com.termux.terminal.JNI.createSubprocess], ...). PocketShell's data flow
 * is the inverse — bytes come from a remote SSH stream, not a local PTY —
 * so we must keep `TerminalSession.initializeEmulator` (which fires the JNI
 * subprocess) from ever running, while still keeping the rest of the
 * session/view/emulator stack happy.
 *
 * Strategy (recommended path "A" in the issue #9 brief):
 *
 * 1. Construct a real [TerminalSession]. Its constructor does not touch JNI.
 * 2. Pre-populate `TerminalSession.mEmulator` via reflection so that
 *    `updateSize`'s "create new emulator" branch is never taken. The else
 *    branch instead calls `JNI.setPtyWindowSize` + `mEmulator.resize`.
 * 3. The PocketShell stub `libtermux.so` (`shared/core-terminal/src/main/cpp/`)
 *    makes `setPtyWindowSize` a safe no-op, so `JNI.setPtyWindowSize` is
 *    callable without crashing — even though we never actually drive a local
 *    PTY.
 * 4. Set `TerminalSession.mShellPid` to a positive value via reflection so
 *    that `TerminalSession.write(byte[], int, int)`'s
 *    `if (mShellPid > 0)` gate accepts user input writes.
 * 5. Run two coroutines: one drains the session's
 *    `mTerminalToProcessIOQueue` and forwards bytes to the SSH stdin; the
 *    other reads bytes from the SSH stdout and dispatches them into the
 *    emulator via the same `mProcessToTerminalIOQueue` + `MSG_NEW_INPUT`
 *    path the upstream input thread uses.
 *
 * The bridge speaks only via [TerminalSession]'s public surface plus four
 * package-private fields (`mEmulator`, `mShellPid`, `mProcessToTerminalIOQueue`,
 * `mMainThreadHandler`), accessed by reflection so the bridge can live
 * outside the `com.termux.terminal` package without modifying the vendored
 * sources. The four lookups are cached at object-construction time.
 *
 * ## Threading
 *
 * The Termux handler is a [Handler] tied to the main looper. All calls to
 * [feedBytes] and [enqueueInput] post a message there so the emulator runs
 * on the UI thread, matching upstream's contract ("All terminal emulation
 * and callback methods will be performed on the main thread."). Callers
 * may invoke [feedBytes] from any thread.
 *
 * @property session The underlying [TerminalSession] consumed by
 *   [com.termux.view.TerminalView].
 * @property emulator The pre-installed emulator. Resized by
 *   [com.termux.view.TerminalView.updateSize] on every layout change once the
 *   view has a non-zero size.
 */
public class SshTerminalBridge(
    columns: Int = INITIAL_COLUMNS,
    rows: Int = INITIAL_ROWS,
    cellWidthPixels: Int = INITIAL_CELL_WIDTH_PX,
    cellHeightPixels: Int = INITIAL_CELL_HEIGHT_PX,
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    private val client: TerminalSessionClient = NoOpTerminalSessionClient(),
) {

    /**
     * The vendored [TerminalSession] that [com.termux.view.TerminalView]
     * speaks to. Constructed with dummy shell args — they are never read
     * because we bypass `initializeEmulator`.
     */
    public val session: TerminalSession = TerminalSession(
        /* shellPath = */ "/system/bin/sh",
        /* cwd = */ "/",
        /* args = */ emptyArray(),
        /* env = */ emptyArray(),
        /* transcriptRows = */ transcriptRows,
        /* client = */ client,
    )

    /**
     * The terminal emulator that the [com.termux.view.TerminalView] will
     * render. Pre-installed so `TerminalSession.updateSize`'s "first call"
     * branch (which would create an emulator AND spawn a local subprocess
     * via JNI) is never taken — the else branch fires instead, which only
     * calls our stubbed `JNI.setPtyWindowSize` + `mEmulator.resize`.
     */
    public val emulator: TerminalEmulator = TerminalEmulator(
        /* session = */ session,
        /* columns = */ columns,
        /* rows = */ rows,
        /* cellWidthPixels = */ cellWidthPixels,
        /* cellHeightPixels = */ cellHeightPixels,
        /* transcriptRows = */ transcriptRows,
        /* client = */ client,
    )

    /**
     * Background thread that reads bytes the View pushes into
     * `mTerminalToProcessIOQueue` (via `session.write(...)`) and forwards
     * them to whatever [OutputStream] was set with [setRemoteStdin]. Stays
     * alive until [stop] is called.
     */
    @Volatile
    private var inputDrainerThread: Thread? = null
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var remoteStdin: OutputStream? = null

    init {
        // Install the pre-built emulator on the session so `updateSize`'s
        // null-check fails and the JNI-spawning branch is skipped.
        SessionReflection.setEmulator(session, emulator)

        // Make `TerminalSession.write` accept input. Upstream gates writes on
        // `if (mShellPid > 0)`. We have no real shell pid; any positive value
        // unlocks the queue. The value 1 is arbitrary — nobody reads it from
        // session state because we never call `finishIfRunning` (which would
        // SIGKILL pid 1 on the device, which is `init` — but we explicitly
        // never call it, and the public API has no other consumer of the
        // field).
        SessionReflection.setShellPid(session, FAKE_SHELL_PID)
    }

    /**
     * Set the [OutputStream] that will receive bytes written by the user
     * (typed characters, IME commits, etc.) via the [TerminalSession]. Pass
     * the SSH shell channel's stdin here. Pass `null` to detach.
     *
     * The stream is invoked from a dedicated drainer thread; callers do not
     * need to thread-shift before passing the stream.
     */
    public fun setRemoteStdin(stream: OutputStream?) {
        remoteStdin = stream
        if (stream != null && inputDrainerThread == null) {
            startInputDrainer()
        }
    }

    /**
     * Push [count] bytes from [data] starting at [offset] into the
     * emulator's input queue, then nudge the session's main-thread handler
     * so it actually drains the queue and renders the bytes.
     *
     * Safe to call from any thread. Equivalent of what the upstream
     * `TermSessionInputReader` thread does after reading from the local PTY
     * file descriptor.
     */
    public fun feedBytes(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        if (count <= 0) return
        val written = SessionReflection.writeProcessToTerminalQueue(session, data, offset, count)
        if (!written) return
        val handler = SessionReflection.getMainThreadHandler(session)
        handler.sendEmptyMessage(MSG_NEW_INPUT)
    }

    /**
     * Stop the drainer thread and detach the remote stdin reference. Does
     * NOT close the underlying [TerminalSession] — its lifecycle belongs to
     * the caller; the emulator is still consumable by anyone holding a
     * reference (e.g. for end-of-session message rendering).
     */
    public fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        SessionReflection.closeTerminalToProcessQueue(session)
        inputDrainerThread?.interrupt()
        inputDrainerThread = null
        remoteStdin = null
    }

    private fun startInputDrainer() {
        val t = Thread({ drainInputLoop() }, "PocketShellInputDrainer")
        t.isDaemon = true
        inputDrainerThread = t
        t.start()
    }

    private fun drainInputLoop() {
        val buffer = ByteArray(4096)
        while (!stopped.get()) {
            val read = SessionReflection.readTerminalToProcessQueue(session, buffer)
            if (read == -1) return
            if (read == 0) continue
            val stream = remoteStdin ?: continue
            try {
                stream.write(buffer, 0, read)
                stream.flush()
            } catch (t: Throwable) {
                // Stream is broken; bail. Caller is responsible for
                // detecting the SSH disconnect and tearing the bridge
                // down via stop().
                return
            }
        }
    }

    /**
     * No-op implementation of [TerminalSessionClient] used as the default
     * client when the caller doesn't supply one. Production callers (the
     * Compose adapter) install a client that bridges
     * [TerminalSessionClient.onTextChanged] to
     * [com.termux.view.TerminalView.onScreenUpdated], which is what drives
     * the canvas redraw.
     */
    public class NoOpTerminalSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
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

    public companion object {
        /**
         * Constant chosen to match the message id `TerminalSession.MSG_NEW_INPUT`
         * (= 1). Defined `private static final int` in the vendored source;
         * we hardcode it here so we don't need to reflect into a private
         * field per-call. If a future Termux refresh changes this value, the
         * vendored source diff will catch the mismatch.
         */
        public const val MSG_NEW_INPUT: Int = 1

        /**
         * Arbitrary positive value used as the "fake" shell pid so that
         * `TerminalSession.write` accepts user input writes. We never
         * actually SIGKILL this pid (would require calling
         * `TerminalSession.finishIfRunning`, which we explicitly do not).
         */
        public const val FAKE_SHELL_PID: Int = 1

        /** Initial emulator size; the View will resize on first layout. */
        public const val INITIAL_COLUMNS: Int = 80
        public const val INITIAL_ROWS: Int = 24

        /**
         * Initial cell pixel dimensions. These are deliberately small but
         * non-zero so [TerminalEmulator] constructor's internal asserts (it
         * requires positive dimensions) succeed. The [com.termux.view.TerminalView]
         * recomputes the cell pixel dimensions from the renderer's font
         * metrics on first layout and resizes the emulator accordingly via
         * `emulator.resize(...)`.
         */
        public const val INITIAL_CELL_WIDTH_PX: Int = 8
        public const val INITIAL_CELL_HEIGHT_PX: Int = 16

        /**
         * Scrollback buffer depth. Matches the upstream Termux default
         * recommended in `TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS`.
         */
        public const val DEFAULT_TRANSCRIPT_ROWS: Int = 2000
    }
}

/**
 * Reflective access to package-private fields of [TerminalSession]. We
 * deliberately keep this isolated in one private helper so the rest of the
 * codebase consumes only well-named accessors. The reflection targets are
 * stable across the Termux releases we vendor (verified against the
 * upstream commit pinned in `shared/core-terminal/VENDORED.md`).
 *
 * If a future Termux refresh renames any of these fields, the bridge will
 * fail at first use with a clear [IllegalStateException] from
 * [resolveField] — easier to spot than a silent behavioural regression.
 */
private object SessionReflection {

    private val mEmulatorField: Field by lazy {
        resolveField(TerminalSession::class.java, "mEmulator")
    }
    private val mShellPidField: Field by lazy {
        resolveField(TerminalSession::class.java, "mShellPid")
    }
    private val mProcessToTerminalIOQueueField: Field by lazy {
        resolveField(TerminalSession::class.java, "mProcessToTerminalIOQueue")
    }
    private val mTerminalToProcessIOQueueField: Field by lazy {
        resolveField(TerminalSession::class.java, "mTerminalToProcessIOQueue")
    }
    private val mMainThreadHandlerField: Field by lazy {
        resolveField(TerminalSession::class.java, "mMainThreadHandler")
    }

    private val byteQueueWriteMethod by lazy {
        // `ByteQueue.write(byte[] buffer, int offset, int length) -> boolean`
        // Package-private class; access via reflection so the bridge does
        // not need to live in `com.termux.terminal`.
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val byteQueueReadMethod by lazy {
        // `ByteQueue.read(byte[] buffer, boolean block) -> int`
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val byteQueueCloseMethod by lazy {
        // `ByteQueue.close()`: wakes a blocking `read(..., block = true)`.
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("close")
            .apply { isAccessible = true }
    }

    fun setEmulator(session: TerminalSession, emulator: TerminalEmulator) {
        mEmulatorField.set(session, emulator)
    }

    fun setShellPid(session: TerminalSession, pid: Int) {
        mShellPidField.setInt(session, pid)
    }

    fun getMainThreadHandler(session: TerminalSession): Handler {
        return mMainThreadHandlerField.get(session) as Handler
    }

    /**
     * Push bytes into the session's "incoming from PTY" queue. Returns
     * `true` if the queue accepted them (false if the queue is closed,
     * matching `ByteQueue.write`'s contract).
     */
    fun writeProcessToTerminalQueue(
        session: TerminalSession,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val queue = mProcessToTerminalIOQueueField.get(session)
        return byteQueueWriteMethod.invoke(queue, data, offset, length) as Boolean
    }

    /**
     * Block-read from the session's "outgoing to PTY" queue. Returns the
     * number of bytes read, or -1 if the queue was closed.
     */
    fun readTerminalToProcessQueue(session: TerminalSession, buffer: ByteArray): Int {
        val queue = mTerminalToProcessIOQueueField.get(session)
        return byteQueueReadMethod.invoke(queue, buffer, true) as Int
    }

    fun closeTerminalToProcessQueue(session: TerminalSession) {
        val queue = mTerminalToProcessIOQueueField.get(session)
        byteQueueCloseMethod.invoke(queue)
    }

    private fun resolveField(cls: Class<*>, name: String): Field {
        return try {
            cls.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            throw IllegalStateException(
                "PocketShell SshTerminalBridge: expected field `$name` on " +
                    "${cls.name} (upstream Termux). Vendored source was " +
                    "refreshed without updating the bridge?",
                e,
            )
        }
    }
}
