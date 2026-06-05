package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Test double for [TmuxClient] used by [TmuxSessionViewModelTest].
 *
 * Mirrors the production wiring just enough that the view model can
 * exercise its event subscription and per-pane producer wiring without
 * any real SSH transport or tmux server:
 *
 * - [events] is a hot [MutableSharedFlow] tests `emit` into to drive the
 *   view model's reconcile path
 * - [sendCommand] is captured into [sentCommands] and returns a canned
 *   [CommandResponse] from [responses] (or an empty success if none
 *   queued). Production code only invokes `sendCommand("list-panes ...")`
 *   from reconcilePanes and `sendCommand("send-keys ...")` from the input
 *   path; tests pre-seed `list-panes` responses keyed by call order.
 * - [outputFor] returns the events flow filtered to
 *   [ControlEvent.Output] for the given pane ID — same shape as
 *   [com.pocketshell.core.tmux.RealTmuxClient.outputFor].
 *
 * Closed state is tracked via [closed] so tests can assert teardown.
 */
internal class FakeTmuxClient : TmuxClient {

    val emittedEvents: MutableSharedFlow<ControlEvent> = MutableSharedFlow(
        // replay=0 matches RealTmuxClient.eventBus exactly; tests subscribe
        // before emitting via the view model's attachClient(...).
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val events: Flow<ControlEvent> = emittedEvents

    /**
     * Issue #173: test-controllable disconnection signal so tests can
     * simulate the production [TmuxClient.readerLoop] exit (e.g. socket
     * tear-down during background) by flipping [disconnectedSignal] to
     * `true`. Defaults to `false` — connected — like a freshly-built
     * real client.
     */
    val disconnectedSignal: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val disconnected: StateFlow<Boolean> = disconnectedSignal.asStateFlow()

    val sentCommands: MutableList<String> = mutableListOf()

    /**
     * FIFO queue of canned responses. Pop one per `sendCommand` call.
     * Empty → a default successful empty response is returned.
     */
    val responses: ArrayDeque<CommandResponse> = ArrayDeque()

    val capturePaneResponses: ArrayDeque<CommandResponse> = ArrayDeque()

    /**
     * Issue #259: replies to the `display-message -p ... '#{cursor_x},#{cursor_y}'`
     * cursor query the seed path issues after a `capture-pane`. Defaults to a
     * single `0,0` reply (cursor home) so tests that do not care about the seed
     * cursor are unaffected; the seed-path regression test queues a specific
     * cursor (e.g. `0,2`) to drive the in-place rewrite alignment.
     */
    val cursorQueryResponses: ArrayDeque<CommandResponse> = ArrayDeque()

    var closeAndThrowOnCommandPrefix: String? = null

    var closeAndThrowException: Throwable = TmuxClientException("tmux command timed out")

    var suspendForeverOnCommandPrefix: String? = null

    var sendCommandDelayMs: Long = 0L

    var sendCommandGatePrefix: String? = null

    var sendCommandGate: CompletableDeferred<Unit>? = null

    @Volatile
    var closed: Boolean = false

    @Volatile
    var connectCalled: Boolean = false

    /**
     * Issue #465: when non-null, [connect] throws this instead of
     * succeeding. Models the production `tmux -CC` spawn failing because the
     * underlying SSH transport refused to open a new channel/shell — sshj
     * surfaces that as an `open failed` [TmuxClientException]. Used to drive
     * the poisoned-transport reconnect dead-end regression.
     */
    @Volatile
    var connectThrows: Throwable? = null

    override suspend fun connect() {
        connectCalled = true
        connectThrows?.let { throw it }
    }

    override suspend fun sendCommand(cmd: String): CommandResponse {
        sentCommands += cmd
        sendCommandGatePrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                sendCommandGate?.await()
            }
        }
        if (sendCommandDelayMs > 0L && cmd.startsWith("send-keys")) {
            delay(sendCommandDelayMs)
        }
        suspendForeverOnCommandPrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                CompletableDeferred<Unit>().await()
            }
        }
        closeAndThrowOnCommandPrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                close()
                throw closeAndThrowException
            }
        }
        if (cmd.startsWith("capture-pane")) {
            return capturePaneResponses.removeFirstOrNull() ?: CommandResponse(
                number = 0L,
                output = emptyList(),
                isError = false,
            )
        }
        // Issue #259: the seed path issues `display-message -p ... cursor_x,cursor_y`
        // right after `capture-pane`. Serve it from a dedicated queue so it
        // never consumes a `list-panes` response queued in [responses].
        if (cmd.startsWith("display-message") && cmd.contains("cursor")) {
            return cursorQueryResponses.removeFirstOrNull() ?: CommandResponse(
                number = 0L,
                output = listOf("0,0"),
                isError = false,
            )
        }
        return responses.removeFirstOrNull() ?: CommandResponse(
            number = 0L,
            output = emptyList(),
            isError = false,
        )
    }

    override fun outputFor(paneId: String): Flow<ControlEvent.Output> =
        emittedEvents
            .filterIsInstance<ControlEvent.Output>()
            .filter { it.paneId == paneId }

    var setWindowSizeLatestResponse: CommandResponse = CommandResponse(
        number = 0L,
        output = emptyList(),
        isError = false,
    )

    override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse {
        val escaped = sessionId.replace("'", "'\\''")
        sentCommands += "set-window-option -t '$escaped' window-size latest"
        return setWindowSizeLatestResponse
    }

    var refreshClientSizeResponse: CommandResponse = CommandResponse(
        number = 0L,
        output = emptyList(),
        isError = false,
    )

    var refreshClientSizeException: Throwable? = null

    val refreshClientSizeGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

    override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse {
        sentCommands += "refresh-client -C ${cols}x${rows}"
        refreshClientSizeException?.let { throw it }
        refreshClientSizeGates.removeFirstOrNull()?.await()
        return refreshClientSizeResponse
    }

    override fun close() {
        closed = true
        disconnectedSignal.value = true
    }

    /**
     * Issue #215: record-and-tear-down test double for the production
     * detach-then-close path. Tests can flip [detachCleanlyEmitsError]
     * to make the recorded `detach-client` command surface as an error
     * response without affecting the [close] / [disconnectedSignal]
     * side-effects — the close-then-disconnected sequence runs whether
     * the server-side command succeeds or not, mirroring the real
     * client's "best-effort detach, unconditional local close" shape.
     */
    var detachCleanlyCalled: Boolean = false
        private set

    var detachCleanlyTimeoutMs: Long = -1L
        private set

    var detachCleanlyGate: CompletableDeferred<Unit>? = null

    override suspend fun detachCleanly(timeoutMs: Long) {
        detachCleanlyCalled = true
        detachCleanlyTimeoutMs = timeoutMs
        // Mirror the production semantics so view-model tests that drive
        // close-paths see the same observable effects (sent command +
        // disconnected signal + closed = true) whether the production
        // path runs against the real client or the fake.
        sentCommands += "detach-client"
        detachCleanlyGate?.await()
        close()
    }
}
