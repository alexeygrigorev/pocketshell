package com.pocketshell.next.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.PtyChannel
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.termux.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the session screen can be showing.
 *
 * Three states, not four: there is deliberately no `Reconnecting`. A dropped
 * transport lands on [Failed] and the user goes back to the tree — reconnect is
 * rewrite task U-7, and stubbing half of it here would mean writing the state
 * machine twice.
 */
sealed interface SessionUiState {

    /** Dialling, resolving the attach command, or opening the PTY. */
    data object Connecting : SessionUiState

    /**
     * Attached. [terminal] is the live vendored emulator front end the screen
     * renders; it is carried on the state rather than exposed as a second
     * ViewModel property so that "there is a terminal to draw" and "we are
     * attached" cannot disagree.
     */
    data class Live(val terminal: TerminalSession) : SessionUiState

    /** Never attached, or the session ended. [message] is user-facing text. */
    data class Failed(val message: String) : SessionUiState
}

/**
 * One attached session (rewrite task U-4, journey J03) — the point of the app.
 *
 * ## The whole lifecycle, in one place
 *
 * [open] does four things and stops: get the host's live connection from
 * [ConnectionsRegistry], ask [HostCliClientFactory] for the attach command,
 * open a PTY channel running it, and pump that channel into the vendored
 * terminal emulator through a [TerminalPtyBridge]. There is no lease, no
 * refcount, no pool, no shadow session tree and no reconnect supervisor. When
 * the channel ends, the screen says so.
 *
 * ## Trust is not answered here
 *
 * A [ConnectResult.NeedsTrust] becomes [SessionUiState.Failed] with a message
 * pointing at the host list, exactly as the session tree does (task U-3). Two
 * screens able to write the trust store is two places a host key can be
 * accepted, and the host list is the one that owns that decision.
 *
 * ## Size
 *
 * The PTY is opened at [TerminalPtyBridge.DEFAULT_COLS] x
 * [TerminalPtyBridge.DEFAULT_ROWS] — the size a remote shell assumes when
 * nobody has said otherwise — because at `open()` time no view has been laid
 * out yet and therefore no real geometry exists. The screen reports the real
 * size through [onResized] as soon as the terminal view knows its font metrics
 * (typically the first frame), and that call is the single path to
 * `pty.resize`. Polishing that path — rotation, IME insets, the key bar — is
 * task U-5.
 *
 * ## Budget
 *
 * The rewrite plan caps this file at 600 lines and the public surface at
 * [uiState], [open], [sendBytes], [onResized] and [onCleared]. Nothing here is
 * annotated for tests: the seams are the three constructor parameters, and the
 * unit suite drives the real class over a scripted `FakeHostConnection`.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Connecting)

    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /**
     * Owns the two bridge pumps. Separate from [viewModelScope] because the
     * output pump parks on a blocking queue write and the input pump on a
     * blocking queue read: both need [dispatcher] (an IO pool), not the main
     * dispatcher `viewModelScope` carries. Cancelled in [onCleared].
     */
    private val pumpScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private var attachJob: Job? = null
    private var bridge: TerminalPtyBridge? = null
    private var channel: PtyChannel? = null

    /** The session's own name, kept so an end-of-session message can say which. */
    private var sessionLabel: String? = null

    private var cols: Int = TerminalPtyBridge.DEFAULT_COLS
    private var rows: Int = TerminalPtyBridge.DEFAULT_ROWS

    /**
     * Attaches to [sessionName] on [hostId].
     *
     * Idempotent by design: the screen calls it from a `LaunchedEffect`, which
     * re-runs on configuration change and on returning to a recomposed route,
     * and a second attach would open a second PTY on the same tmux session.
     * A repeat call after a failure is also ignored — retry is task U-7; today
     * the user goes back to the tree.
     */
    fun open(hostId: Long, sessionName: String) {
        if (attachJob != null) return
        attachJob = viewModelScope.launch { attach(hostId, sessionName) }
    }

    /**
     * Sends raw bytes to the remote PTY. Never throws.
     *
     * Used by the screen for anything that is not a keystroke the vendored
     * terminal view already handles itself (that input path goes straight into
     * the session's own queue and out through the bridge). A failure flips
     * [uiState]; whether a caller's draft survives is the caller's business.
     */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val target = channel ?: return
        viewModelScope.launch {
            try {
                target.write(bytes)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                fail("Could not send to the session: " + describe(failure))
            }
        }
    }

    /**
     * Reports the terminal's real size in character cells.
     *
     * Called by the screen whenever the vendored view recomputes its geometry.
     * Before the bridge exists the size is only remembered, so a resize that
     * lands during the dial still opens the PTY at the right size instead of
     * being lost.
     */
    fun onResized(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        val live = bridge ?: return
        viewModelScope.launch {
            try {
                live.resize(cols, rows)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A resize that cannot reach the remote is not worth tearing the
                // screen down for: the emulator half already applied, so the
                // pane still renders and the next output frame will reveal a
                // genuinely dead channel through the output pump instead.
            }
        }
    }

    /**
     * Detaches. Stops the pumps, closes the PTY, leaves the CONNECTION alone —
     * the registry owns that, and a second screen on the same host must not
     * lose its transport because this one was popped.
     */
    override fun onCleared() {
        attachJob?.cancel()
        attachJob = null
        bridge?.stop()
        bridge = null
        val open = channel
        channel = null
        if (open != null) {
            // The scope this runs on is about to die with the ViewModel, so the
            // close cannot be launched there. NonCancellable on the pump scope,
            // which is cancelled immediately afterwards, keeps the channel
            // teardown from being dropped half-done.
            pumpScope.launch(NonCancellable) { runCatching { open.close() } }
        }
        pumpScope.cancel()
        super.onCleared()
    }

    // --- attach --------------------------------------------------------------

    private suspend fun attach(hostId: Long, sessionName: String) {
        sessionLabel = sessionName
        val connection = when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection

            is ConnectResult.NeedsTrust -> return fail(
                "This host's key still needs to be confirmed. Open it from the " +
                    "host list to review the key.",
            )

            is ConnectResult.Failed -> return fail(result.message)
        }

        val command = runCatching { clients.create(connection).attachCommand(sessionName) }
            .getOrElse { failure ->
                return fail("Could not build the attach command: " + describe(failure))
            }

        val terminal = createRemoteTerminalSession(cols = cols, rows = rows)

        val pty = try {
            openPty(connection, command)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return fail("Could not attach to \"$sessionName\": " + describe(failure))
        }

        val pump = TerminalPtyBridge(
            pty = pty,
            emulator = terminal,
            scope = pumpScope,
            onOutputEnded = { onChannelEnded(pty) },
        )
        channel = pty
        bridge = pump
        pump.start()
        _uiState.value = SessionUiState.Live(terminal)

        // Second observer, on the other end of the channel. The output stream
        // and the channel close normally end together, but they are separate
        // events on the transport and either can be the one that arrives — a
        // stream torn down without a close, or a close whose stream never
        // completed. [endSession] is first-wins, so whichever fires reports and
        // the other is a no-op.
        viewModelScope.launch {
            val status = pty.exit.await()
            endSession(endedMessage(status))
        }
    }

    private suspend fun openPty(connection: HostConnection, command: String): PtyChannel =
        withContext(dispatcher) {
            connection.openPty(command = command, cols = cols, rows = rows)
        }

    /**
     * The bridge's output flow completed. Fires off the pump dispatcher, so it
     * hops back onto the ViewModel scope to touch state.
     *
     * The exit status is waited for BRIEFLY rather than skipped: the stream and
     * the channel close land within milliseconds of each other, and the status
     * is the difference between "you typed exit" (0) and "there is no such
     * session" (3, `sessions attach`'s own contract). The wait is bounded
     * because a server that never sends one must not leave the screen on
     * "Attaching…" forever.
     */
    private fun onChannelEnded(ended: PtyChannel) {
        if (channel !== ended) return
        viewModelScope.launch {
            val status = withTimeoutOrNull(EXIT_STATUS_GRACE_MS) { ended.exit.await() }
            endSession(endedMessage(status))
        }
    }

    private fun endSession(message: String) {
        if (_uiState.value is SessionUiState.Failed) return
        bridge?.stop()
        fail(message)
    }

    private fun fail(message: String) {
        _uiState.value = SessionUiState.Failed(message)
    }

    /**
     * What the user reads when a session goes away.
     *
     * An exit status is included when the host reported one, because it is the
     * difference between "you typed `exit`" (0) and "the attach command could
     * not find that session" (3) — the same distinction `pocketshell sessions
     * attach` documents in its exit codes.
     */
    private fun endedMessage(exitCode: Int?): String {
        val name = sessionLabel
        val subject = if (name == null) "The session" else "Session \"$name\""
        return when {
            exitCode == null || exitCode == 0 -> "$subject ended."
            else -> "$subject ended (exit $exitCode)."
        }
    }

    private fun describe(failure: Throwable): String =
        failure.message ?: failure::class.simpleName ?: "unknown error"

    private companion object {
        /**
         * How long the end-of-session message waits for the remote's exit
         * status. Short: the two events are effectively simultaneous, and this
         * is only a ceiling on how long a server that sends no status at all
         * can delay the message.
         */
        const val EXIT_STATUS_GRACE_MS = 2_000L
    }
}
