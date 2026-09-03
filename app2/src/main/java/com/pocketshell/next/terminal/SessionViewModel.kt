package com.pocketshell.next.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.PtyChannel
import com.pocketshell.core.transport.TransportState
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the session screen can be showing.
 *
 * [Reconnecting] is a first-class state rather than a flavour of [Failed]
 * because the two look nothing alike to a user: one keeps the last frame on
 * screen under a countdown and comes back by itself, the other is over.
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

    /**
     * The link went away and a fresh attach is on the ladder (task U-7).
     *
     * [attempt] is 0-based, exactly as [ReconnectController] counts.
     * [retryInMs] is what is LEFT of the current wait and ticks down once a
     * second, so the screen can render a live countdown while staying a pure
     * function of this state. (The plan sketched an absolute `nextRetryAtMs`;
     * rendering that needs a clock AND a ticking timer inside a composable, and
     * an unbounded composable timer is the classic never-idle hang under both
     * Robolectric and instrumented Compose tests. The remaining-time form moves
     * the tick to the one place already driven by a virtual clock in tests.)
     *
     * [terminal] is the SAME emulator instance the session was [Live] on: tmux
     * repaints on reattach, so there is deliberately no client-side snapshot or
     * reseed — the last frame simply stays on screen, under the banner, until
     * new bytes arrive. Carrying it here rather than letting the screen remember
     * the last live one keeps the screen stateless.
     */
    data class Reconnecting(
        val attempt: Int,
        val retryInMs: Long,
        val terminal: TerminalSession,
    ) : SessionUiState

    /** Never attached, the session ended, or the ladder ran out. [message] is user-facing. */
    data class Failed(val message: String) : SessionUiState
}

/**
 * One attached session (rewrite tasks U-4 and U-7, journeys J03 and J05) — the
 * point of the app.
 *
 * ## The whole lifecycle, in one place
 *
 * [open] does four things: get the host's live connection from
 * [ConnectionsRegistry], ask [HostCliClientFactory] for the attach command,
 * open a PTY channel running it, and pump that channel into the vendored
 * terminal emulator through a [TerminalPtyBridge]. [attachOnce] is that whole
 * sequence, and the reconnect loop re-runs the SAME function — there is no
 * second, subtly different attach path, which is what kept the pre-rewrite
 * client's reconnect and its first connect from ever agreeing.
 *
 * There is no lease, no refcount, no pool and no shadow session tree. The
 * reconnect supervisor is [ReconnectController] — a ladder and a give-up.
 *
 * ## What counts as a drop
 *
 * A resolved exit STATUS means the remote command really ran and exited: the
 * session is over (you typed `exit`, or `sessions attach` said "no such
 * session" with exit 3) and the screen says so. A channel that ends with NO
 * status, or a [TransportState.Lost], is the link going away underneath a
 * session that is still alive on the host — that is the reconnect case. The
 * distinction is the transport's own: sshj carries `exit-status` on the channel
 * close, and a dropped socket has none to carry.
 *
 * A third case (issue #2477) also ends with no status: [HostConnection.close]
 * called on the connection this screen is watching — deliberately, by
 * something other than a network failure. That is NOT a drop either, even
 * though the channel dies the same way a dropped socket's does; see
 * [isDeliberateClose].
 *
 * ## Nothing runs while the app is away
 *
 * Every rung of the ladder — the countdown as well as the dial — is gated on
 * [ForegroundSignal]. D21's "no background work" is not a soft target here: a
 * backgrounded app that kept dialling would be the reconnect storm the rewrite
 * exists to delete. Coming back to the foreground resets the ladder and tries
 * at once, as does [retryNow].
 *
 * ## Trust is not answered here
 *
 * A [ConnectResult.NeedsTrust] becomes [SessionUiState.Failed] with a message
 * pointing at the host list, exactly as the session tree does (task U-3), and
 * it is NOT retried: two screens able to write the trust store is two places a
 * host key can be accepted, and the host list is the one that owns that
 * decision.
 *
 * ## Size and budget
 *
 * The PTY opens at [TerminalPtyBridge.DEFAULT_COLS] x
 * [TerminalPtyBridge.DEFAULT_ROWS] — the size a remote shell assumes when
 * nobody has said otherwise — because at `open()` time no view has been laid
 * out and therefore no real geometry exists; [onResized] is the single path to
 * `pty.resize` once the view knows its font metrics.
 *
 * The rewrite plan caps this file at 600 lines and the public surface at
 * [uiState], [open], [sendBytes], [retryNow], [onResized] and [onCleared].
 * Nothing here is annotated for tests: the seams are the constructor
 * parameters, and the unit suite drives the real class over a scripted
 * `FakeHostConnection` and a fake foreground signal.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val reconnect: ReconnectController,
    private val foreground: ForegroundSignal,
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
    private var reconnectJob: Job? = null
    private var watchJob: Job? = null
    private var bridge: TerminalPtyBridge? = null
    private var channel: PtyChannel? = null

    /**
     * The connection [channel] is currently attached through (issue #2477).
     *
     * Kept only so a channel that ends with NO exit status can be told apart
     * from a genuine network drop: [HostConnection.close] tears the PTY down
     * the same way a dead socket does (no clean exit-status), but it also
     * flips THIS field's state to [TransportState.Closed] rather than
     * [TransportState.Lost] — the one signal that distinguishes "someone
     * closed this on purpose" from "the link went away". See [isDeliberateClose].
     */
    private var connection: HostConnection? = null

    /**
     * The ONE emulator front end for this screen's whole life.
     *
     * Created by [open] and never replaced: a reattach that built a second
     * [TerminalSession] would hand the screen an empty grid, which is exactly
     * the "terminal cleared itself while reconnecting" symptom this task exists
     * to prevent.
     */
    private var terminal: TerminalSession? = null

    private var hostId: Long? = null

    /** The session's own name, kept so an end-of-session message can say which. */
    private var sessionLabel: String? = null

    private var cols: Int = TerminalPtyBridge.DEFAULT_COLS
    private var rows: Int = TerminalPtyBridge.DEFAULT_ROWS

    init {
        // Coming back to the app is a reason to try NOW, on a fresh ladder: the
        // wait the loop is parked on was sized for a network blip, not for
        // however long the phone was in a pocket.
        viewModelScope.launch {
            foreground.isForeground.drop(1).filter { it }.collect {
                if (_uiState.value is SessionUiState.Reconnecting) restartLadder()
            }
        }
    }

    /**
     * Attaches to [sessionName] on [hostId].
     *
     * Idempotent by design: the screen calls it from a `LaunchedEffect`, which
     * re-runs on configuration change and on returning to a recomposed route,
     * and a second attach would open a second PTY on the same tmux session.
     * A repeat call after a failure is also ignored — [retryNow] is the retry.
     */
    fun open(hostId: Long, sessionName: String) {
        if (attachJob != null) return
        this.hostId = hostId
        this.sessionLabel = sessionName
        // Built before the dial so every later state — including a reconnect
        // that starts before the first attach ever landed — has a terminal to
        // show, and so `terminal` is never null once the screen is open.
        this.terminal = createRemoteTerminalSession(cols = cols, rows = rows)
        attachJob = viewModelScope.launch {
            // A FIRST attach that cannot reach the host is a failure, not a
            // reconnect episode: there is nothing to reconnect TO yet, and a
            // ladder here would hide a wrong hostname behind 18 seconds of
            // countdown. The ladder starts only after a session was live.
            when (val outcome = attachOnce()) {
                AttachOutcome.Attached -> Unit
                is AttachOutcome.Refused -> fail(outcome.message)
                is AttachOutcome.Unreachable -> fail(outcome.message)
            }
        }
    }

    /**
     * Sends raw bytes to the remote PTY. Never throws.
     *
     * Used by the screen for anything that is not a keystroke the vendored
     * terminal view already handles itself (that input path goes straight into
     * the session's own queue and out through the bridge). While reconnecting
     * there is no channel and the call is dropped; whether a caller's draft
     * survives is the caller's business.
     */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val target = channel ?: return
        viewModelScope.launch {
            try {
                target.write(bytes)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A PTY write only throws on a channel that is already gone, so
                // this is a link-down report like any other and goes through the
                // one place that decides between "ended" and "reconnect". Making
                // it its own failure message would mean a drop noticed by typing
                // ended the screen while a drop noticed by the output pump
                // reconnected.
                val status = withTimeoutOrNull(EXIT_STATUS_GRACE_MS) { target.exit.await() }
                settleEnd(target, status, deliberateClose = status == null && isDeliberateClose())
            }
        }
    }

    /**
     * The user asking for another go — from the reconnect banner or from a
     * failure. Resets the ladder to its first rung and tries immediately.
     *
     * Ignored while [SessionUiState.Live] (nothing to retry) and while
     * [SessionUiState.Connecting] (the first attach is still in flight, and a
     * second one would open a second PTY).
     */
    fun retryNow() {
        when (_uiState.value) {
            is SessionUiState.Reconnecting, is SessionUiState.Failed -> restartLadder()
            SessionUiState.Connecting, is SessionUiState.Live -> Unit
        }
    }

    /**
     * Reports the terminal's real size in character cells.
     *
     * Called by the screen whenever the vendored view recomputes its geometry.
     * Before the bridge exists the size is only remembered, so a resize that
     * lands during the dial — or during a reconnect — still opens the PTY at
     * the right size instead of being lost.
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
        reconnectJob?.cancel()
        reconnectJob = null
        watchJob?.cancel()
        watchJob = null
        bridge?.stop()
        bridge = null
        connection = null
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

    /** What one pass of [attachOnce] can come back with. */
    private sealed interface AttachOutcome {

        /** Attached; [SessionUiState.Live] is on screen. */
        data object Attached : AttachOutcome

        /**
         * The host said no in a way another dial cannot fix (an unconfirmed
         * host key, a session that is not there). Ends the ladder.
         */
        data class Refused(val message: String) : AttachOutcome

        /** Could not reach the host this time. The ladder's business. */
        data class Unreachable(val message: String) : AttachOutcome
    }

    /**
     * One full attach: connection → attach command → PTY → pumps → [Live].
     *
     * The single attach path, shared by [open] and the reconnect ladder. It
     * always asks [ConnectionsRegistry] for the connection rather than holding
     * one, which is what makes a reconnect use a FRESH transport: a spent
     * `HostConnection` never self-heals, and the registry treats a
     * dead-but-stored entry as absent and dials a new one.
     */
    private suspend fun attachOnce(): AttachOutcome {
        val host = hostId ?: return AttachOutcome.Refused("No host to attach to.")
        val sessionName = sessionLabel ?: return AttachOutcome.Refused("No session to attach to.")

        val connection = when (val result = registry.getOrConnect(host)) {
            is ConnectResult.Connected -> result.connection

            is ConnectResult.NeedsTrust -> return AttachOutcome.Refused(
                "This host's key still needs to be confirmed. Open it from the " +
                    "host list to review the key.",
            )

            is ConnectResult.Failed -> return AttachOutcome.Unreachable(result.message)
        }

        val command = runCatching { clients.create(connection).attachCommand(sessionName) }
            .getOrElse { failure ->
                return AttachOutcome.Refused(
                    "Could not build the attach command: " + describe(failure),
                )
            }

        val emulator = terminal
            ?: createRemoteTerminalSession(cols = cols, rows = rows).also { terminal = it }

        val pty = try {
            openPty(connection, command)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return AttachOutcome.Unreachable(
                "Could not attach to \"$sessionName\": " + describe(failure),
            )
        }

        val pump = TerminalPtyBridge(
            pty = pty,
            emulator = emulator,
            scope = pumpScope,
            onOutputEnded = { onOutputEnded(pty) },
        )
        channel = pty
        bridge = pump
        this.connection = connection
        pump.start()
        _uiState.value = SessionUiState.Live(emulator)

        // Two more observers, on the other end of the channel. The output
        // stream, the channel close and the transport's own state are three
        // separate events and any of them can be the one that arrives first —
        // a stream torn down without a close, a close whose stream never
        // completed, or a transport that reported the drop before either.
        // [settleEnd] is first-wins on the channel identity, so whichever fires
        // decides and the others are no-ops.
        watchJob = viewModelScope.launch {
            launch {
                val status = pty.exit.await()
                settleEnd(pty, status, deliberateClose = status == null && isDeliberateClose())
            }
            launch {
                connection.state.first { it is TransportState.Lost }
                settleEnd(pty, status = null)
            }
        }
        return AttachOutcome.Attached
    }

    /**
     * True when [connection] ended up [TransportState.Closed] rather than
     * [TransportState.Lost] (issue #2477).
     *
     * A PTY that ends with no exit status ordinarily means the link dropped —
     * worth a reconnect. But [HostConnection.close] tears the channel down the
     * exact same way (no clean exit-status) when something OTHER than a
     * network failure closed the connection deliberately: nothing in
     * production calls that today, but a test's own end-of-test hygiene
     * (`ConnectionsRegistry.closeAll()`, run while this screen's watcher is
     * still alive) does, and so would a future "disconnect" action. Redialling
     * in that case does not reconnect anything the user asked for — it opens a
     * BRAND NEW connection nobody is watching, orphaned in the registry until
     * the next background/grace cycle finds it "live" and holds it open for a
     * session that no longer has a screen (exactly what stranded J06's
     * `backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification` on a shared
     * full-suite run — a PREVIOUS test's deliberate close redialled and left
     * this orphan behind).
     */
    private fun isDeliberateClose(): Boolean = connection?.state?.value is TransportState.Closed

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
     * is what tells an ended session apart from a dropped link. The wait is
     * bounded because a server that never sends one must not hold up the
     * reconnect.
     */
    private fun onOutputEnded(ended: PtyChannel) {
        if (channel !== ended) return
        viewModelScope.launch {
            val status = withTimeoutOrNull(EXIT_STATUS_GRACE_MS) { ended.exit.await() }
            settleEnd(ended, status, deliberateClose = status == null && isDeliberateClose())
        }
    }

    /**
     * The channel [ended] is over. [status] is the remote's exit status, or
     * null when there was none — which is the whole discriminator between an
     * ended session and a dropped link (see the class doc). [deliberateClose]
     * (issue #2477, see [isDeliberateClose]) is the finer discriminator WITHIN
     * "no status": a connection closed on purpose is reported as ended, the
     * same as a clean remote exit, rather than redialled.
     */
    private fun settleEnd(ended: PtyChannel, status: Int?, deliberateClose: Boolean = false) {
        if (channel !== ended) return
        if (status != null || deliberateClose) {
            // Both watchers of `attachOnce`'s watchJob have now been overtaken
            // by events: the one that just fired settled us here, and the
            // sibling watching `connection.state` for Lost must stop too — a
            // deliberately CLOSED connection's state is terminal and sticky
            // (never becomes Lost), so leaving that watcher running would park
            // it forever. Pre-existing for a clean remote exit too (status !=
            // null): once the screen has failed there is nothing left for
            // either watcher to report.
            watchJob?.cancel()
            watchJob = null
            bridge?.stop()
            bridge = null
            channel = null
            fail(if (deliberateClose) closedMessage() else endedMessage(status))
            return
        }
        beginReconnect()
    }

    // --- reconnect -----------------------------------------------------------

    /**
     * The link went away under a live session: keep the last frame, say so, and
     * start the ladder.
     */
    private fun beginReconnect() {
        val emulator = terminal ?: return fail(endedMessage(null))
        releaseChannel()
        // Said immediately, before the first rung, so a user coming back to the
        // screen never sees a stale "attached" over a dead session.
        _uiState.value = SessionUiState.Reconnecting(attempt = 0, retryInMs = 0, terminal = emulator)
        restartLadder()
    }

    /**
     * Runs (or re-runs) the ladder from its first rung.
     *
     * The previous run is cancelled AND joined inside the new coroutine rather
     * than fire-and-forget, so a Retry tap or a foreground return can never
     * leave two ladders dialling the same session at once.
     */
    private fun restartLadder() {
        val previous = reconnectJob
        reconnectJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            runLadder()
        }
    }

    private suspend fun runLadder() {
        val emulator = terminal ?: return
        var attempt = 0
        while (true) {
            when (val decision = reconnect.decide(attempt)) {
                ReconnectController.Decision.GiveUp -> return fail(GAVE_UP_MESSAGE)

                is ReconnectController.Decision.RetryAfter -> {
                    awaitRetryWindow(decision.attempt, decision.delayMs, emulator)
                    when (val outcome = attachOnce()) {
                        AttachOutcome.Attached -> return
                        is AttachOutcome.Refused -> return fail(outcome.message)
                        is AttachOutcome.Unreachable -> attempt = decision.attempt + 1
                    }
                }
            }
        }
    }

    /**
     * Waits out one rung, publishing the countdown as it goes, and returns only
     * with the app in the foreground.
     *
     * The foreground check is the FIRST thing each turn and the last thing
     * before returning, so neither the countdown nor the dial that follows it
     * can happen behind the launcher (D21). A backgrounded app therefore parks
     * here for as long as it takes, showing the reconnect banner it will still
     * be showing when the user comes back.
     */
    private suspend fun awaitRetryWindow(attempt: Int, delayMs: Long, emulator: TerminalSession) {
        var remaining = delayMs
        while (true) {
            foreground.awaitForeground()
            _uiState.value = SessionUiState.Reconnecting(attempt, remaining, emulator)
            if (remaining <= 0) return
            val step = minOf(remaining, COUNTDOWN_TICK_MS)
            delay(step)
            remaining -= step
        }
    }

    /**
     * Retires the spent channel and its pumps WITHOUT closing the vendored
     * terminal's byte queues — the whole reason [TerminalPtyBridge.detach]
     * exists. `ByteQueue.close()` is one-way, so stopping the bridge the normal
     * way would make this screen's [TerminalSession] permanently unwritable and
     * force the reattach to build a fresh, empty one: a cleared screen.
     */
    private fun releaseChannel() {
        watchJob?.cancel()
        watchJob = null
        bridge?.detach()
        bridge = null
        val spent = channel
        channel = null
        if (spent != null) {
            pumpScope.launch(NonCancellable) { runCatching { spent.close() } }
        }
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

    /**
     * What the user reads when THIS screen's connection was closed on purpose
     * rather than lost (issue #2477, [isDeliberateClose]).
     */
    private fun closedMessage(): String {
        val name = sessionLabel
        val subject = if (name == null) "The session" else "Session \"$name\""
        return "$subject ended: the connection was closed."
    }

    private fun describe(failure: Throwable): String =
        failure.message ?: failure::class.simpleName ?: "unknown error"

    private companion object {
        /**
         * How long the end-of-session message waits for the remote's exit
         * status. Short: the two events are effectively simultaneous, and this
         * is only a ceiling on how long a server that sends no status at all
         * can delay the decision to reconnect.
         */
        const val EXIT_STATUS_GRACE_MS = 2_000L

        /** How often the reconnect countdown is republished. */
        const val COUNTDOWN_TICK_MS = 1_000L

        /** Shown when the ladder is exhausted. Names the way out, which is Retry. */
        const val GAVE_UP_MESSAGE =
            "Could not reconnect to the session. Tap Retry to try again."
    }
}
