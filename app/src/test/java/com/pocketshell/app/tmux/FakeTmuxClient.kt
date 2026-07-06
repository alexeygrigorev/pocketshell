package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
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
 * - [sendCommand] / [sendBestEffortCommand] are captured into [sentCommands]
 *   and return a canned
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
internal class FakeTmuxClient(
    paneOutputExtraBufferCapacity: Int = 64,
) : TmuxClient {

    val emittedEvents: MutableSharedFlow<ControlEvent> = MutableSharedFlow(
        // replay=0 matches RealTmuxClient.eventBus exactly; tests subscribe
        // before emitting via the view model's attachClient(...).
        replay = 0,
        extraBufferCapacity = 64,
    )

    /**
     * Issue #896: when set, the [events] flow THROWS this exception on the
     * next emission instead of delivering the event — faithfully reproducing a
     * close/EOF-cascade collector (the `bridgeScope.launch { client.events
     * .collect {} }` at TmuxSessionViewModel.bindClientObservers) firing IO
     * against an already-dead transport during teardown. The captured June-8
     * specimen was an `SshException: SSH session is not connected`; this seam
     * lets a unit test inject exactly that shape and assert the view model's
     * scope-level CoroutineExceptionHandler swallows it (process kept alive)
     * rather than letting it reach the thread's uncaught-exception handler.
     * Cleared after it fires once so subsequent emissions flow normally.
     */
    var throwFromEventsCollectorOnNextEmit: Throwable? = null

    override val events: Flow<ControlEvent> = kotlinx.coroutines.flow.flow {
        emittedEvents.collect { event ->
            throwFromEventsCollectorOnNextEmit?.let { boom ->
                throwFromEventsCollectorOnNextEmit = null
                throw boom
            }
            emit(event)
        }
    }

    var decoupleOutputForFromEvents: Boolean = false

    val emittedPaneOutputs: MutableSharedFlow<ControlEvent.Output> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = paneOutputExtraBufferCapacity,
    )

    /**
     * Issue #173: test-controllable disconnection signal so tests can
     * simulate the production [TmuxClient.readerLoop] exit (e.g. socket
     * tear-down during background) by flipping [disconnectedSignal] to
     * `true`. Defaults to `false` — connected — like a freshly-built
     * real client.
     */
    val disconnectedSignal: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val disconnected: StateFlow<Boolean> = disconnectedSignal.asStateFlow()

    val disconnectEventSignal: MutableStateFlow<TmuxDisconnectEvent?> = MutableStateFlow(null)

    override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> = disconnectEventSignal.asStateFlow()

    fun markDisconnectedForTest(event: TmuxDisconnectEvent) {
        disconnectEventSignal.value = event
        disconnectedSignal.value = true
    }

    val outputBacklogOverflowEvents: MutableSharedFlow<TmuxOutputBacklogOverflow> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)

    override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> =
        outputBacklogOverflowEvents

    var reportOutputBacklogOverflowOnTryEmitFailure: Boolean = false

    private val droppedPaneOutputCounts: MutableMap<String, Int> = mutableMapOf()

    fun tryEmitPaneOutput(paneId: String, data: ByteArray): Boolean {
        val event = ControlEvent.Output(paneId, data)
        val accepted = if (decoupleOutputForFromEvents) {
            emittedPaneOutputs.tryEmit(event)
        } else {
            emittedEvents.tryEmit(event)
        }
        if (!accepted && reportOutputBacklogOverflowOnTryEmitFailure) {
            val dropped = (droppedPaneOutputCounts[paneId] ?: 0) + 1
            droppedPaneOutputCounts[paneId] = dropped
            outputBacklogOverflowEvents.tryEmit(
                TmuxOutputBacklogOverflow(paneId = paneId, droppedEvents = dropped),
            )
        }
        return accepted
    }

    val sentCommands: MutableList<String> = mutableListOf()

    /**
     * Issue #640: synchronous side-channel invoked the instant a command is
     * recorded, before any canned response is produced. Lets a test observe
     * external state (e.g. the view model's [connectionStatus]) at the exact
     * moment a specific command reaches the wire — used to prove the seed
     * capture is sent BEFORE the Connected surface is revealed.
     */
    var onCommandSent: ((String) -> Unit)? = null

    /**
     * FIFO queue of canned responses. Pop one per `sendCommand` call.
     * Empty → a default successful empty response is returned.
     */
    val responses: ArrayDeque<CommandResponse> = ArrayDeque()

    val capturePaneResponses: ArrayDeque<CommandResponse> = ArrayDeque()

    /**
     * Issue #1294: sticky fallback for `capture-pane` when [capturePaneResponses] is empty.
     * The default when this is `null` is an EMPTY success response — which the #1294 heal
     * oracle now scores UNVERIFIED (an empty capture cannot CONFIRM the render). A test that
     * needs a REPEATED confirmed-healthy tick (a matching frame on every backoff tick, not a
     * one-shot from the FIFO) sets this to the frame that MATCHES the render, so the oracle
     * reads HEALTHY and the watchdog backs off (the #1219 battery lever), without polluting
     * the scrollback with a dense frame (which would defeat ≤3-line partial-black detection).
     * Explicitly-queued [capturePaneResponses] still take precedence (checked first).
     */
    @Volatile
    var defaultCaptureResponse: CommandResponse? = null

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

    var closeAndThrowDisconnectEvent: TmuxDisconnectEvent? = null

    var throwOnCommandPrefix: String? = null

    var throwOnCommandRemaining: Int = 0

    var throwOnCommandException: Throwable = TmuxClientException("tmux command timed out")

    var failBestEffortOnCommandPrefix: String? = null

    var bestEffortException: Throwable = TmuxClientException("tmux best-effort command timed out")

    var suspendForeverOnCommandPrefix: String? = null

    var sendCommandDelayMs: Long = 0L

    /**
     * Issue #869: per-`capture-pane` round-trip delay so the submit ack-gate's
     * RTT measurement (and its hardened needle-miss fallback floor) can be
     * exercised deterministically under the `runTest` virtual clock — injecting
     * a known RTT per capture lets a test assert the fallback waits
     * `FALLBACK_FLOOR + injectedRtt` before pressing Enter.
     */
    var captureCommandDelayMs: Long = 0L

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

    /**
     * Issue #692: records the chained-enumeration calls so tests can assert
     * the live-client discovery probe batches `list-sessions` + `list-panes`
     * into ONE round-trip. Each inner command still flows through
     * [handleCommand] so canned [responses] are consumed in submission order.
     */
    val chainedCommandBatches: MutableList<List<String>> = mutableListOf()

    /**
     * Issue #702: when true, [sendChainedCommands] parks forever (never
     * returns) — modelling a wedged shared `-CC` control channel whose
     * single-flight mutex is held by an in-session command that never
     * releases. The folder-list live-client enumeration must NOT hang on this:
     * it bounds the call with `withTimeoutOrNull` and falls through to the
     * bounded SSH-lease enumeration.
     */
    @Volatile
    var wedgeChainedCommandsForever: Boolean = false

    override suspend fun sendChainedCommands(commands: List<String>): List<CommandResponse> {
        chainedCommandBatches += commands
        if (wedgeChainedCommandsForever) {
            CompletableDeferred<Unit>().await()
        }
        return commands.map { handleCommand(it, bestEffort = false) }
    }

    /**
     * Issue #926: the thread name on which the most recent SEED round-trip
     * actually ran. The seed IO ([TmuxSessionViewModel.seedPaneFromCaptureOnce]
     * → [captureWithCursor]) and the attach/switch `list-panes`
     * ([handleCommand]) must run OFF the Main (UI) thread; a test pins Main and
     * the seed-IO dispatcher to two DISTINCT single-thread dispatchers and
     * asserts these record the IO thread, never the Main thread.
     */
    @Volatile
    var lastCaptureThreadName: String? = null

    @Volatile
    var lastListPanesThreadName: String? = null

    /**
     * Issue #926: the [timeoutMs] the VM passed on the most recent seed capture
     * — proves the SHORT seed ceiling (≈2.5 s) is threaded through instead of
     * the full 10 s per-command timeout.
     */
    @Volatile
    var lastCaptureTimeoutMs: Long? = null

    /**
     * Issue #926: when set, [captureWithCursor] PARKS on this gate (a
     * wedged-but-alive `-CC` control channel). A test releases it to model the
     * channel recovering, or leaves it parked and bounds the seed by the VM's
     * short ceiling via the production fall-through. The park happens on the
     * seed-IO dispatcher thread (off Main), so it never blocks the test's Main
     * looper.
     */
    @Volatile
    var captureWithCursorGate: CompletableDeferred<Unit>? = null

    override suspend fun captureWithCursor(
        paneId: String,
        scrollbackLines: Int,
        timeoutMs: Long?,
    ): com.pocketshell.core.tmux.CaptureWithCursor {
        lastCaptureThreadName = Thread.currentThread().name
        lastCaptureTimeoutMs = timeoutMs
        captureWithCursorGate?.await()
        // Reuse the canned-response plumbing in handleCommand so existing
        // capturePaneResponses / cursorQueryResponses fixtures keep working.
        val capture = handleCommand(
            "capture-pane -p -e -S -$scrollbackLines -t $paneId",
            bestEffort = true,
        )
        val cursor = runCatching {
            handleCommand(
                "display-message -p -t $paneId '#{cursor_x},#{cursor_y}'",
                bestEffort = true,
            )
        }.getOrNull()
            ?.takeUnless { it.isError }
            ?.output
            ?.firstOrNull()
        return com.pocketshell.core.tmux.CaptureWithCursor(capture = capture, cursorReply = cursor)
    }

    override suspend fun sendCommand(cmd: String): CommandResponse {
        if (cmd.startsWith("list-panes")) {
            lastListPanesThreadName = Thread.currentThread().name
        }
        return handleCommand(cmd, bestEffort = false)
    }

    override suspend fun sendBestEffortCommand(cmd: String): CommandResponse =
        handleCommand(cmd, bestEffort = true)

    private suspend fun handleCommand(cmd: String, bestEffort: Boolean): CommandResponse {
        sentCommands += cmd
        onCommandSent?.invoke(cmd)
        sendCommandGatePrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                sendCommandGate?.await()
            }
        }
        if (sendCommandDelayMs > 0L && cmd.startsWith("send-keys")) {
            delay(sendCommandDelayMs)
        }
        if (captureCommandDelayMs > 0L && cmd.startsWith("capture-pane")) {
            delay(captureCommandDelayMs)
        }
        suspendForeverOnCommandPrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                CompletableDeferred<Unit>().await()
            }
        }
        closeAndThrowOnCommandPrefix?.let { prefix ->
            if (cmd.startsWith(prefix)) {
                closeWithEvent(
                    closeAndThrowDisconnectEvent ?: TmuxDisconnectEvent(
                        reason = TmuxDisconnectReason.ExplicitClose,
                        source = "fake_close_and_throw",
                        intent = "local_close",
                    ),
                )
                throw closeAndThrowException
            }
        }
        throwOnCommandPrefix?.let { prefix ->
            if (cmd.startsWith(prefix) && throwOnCommandRemaining > 0) {
                throwOnCommandRemaining -= 1
                throw throwOnCommandException
            }
        }
        if (bestEffort) {
            failBestEffortOnCommandPrefix?.let { prefix ->
                if (cmd.startsWith(prefix)) {
                    throw bestEffortException
                }
            }
        }
        if (cmd.startsWith("capture-pane")) {
            return capturePaneResponses.removeFirstOrNull()
                ?: defaultCaptureResponse
                ?: CommandResponse(
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
        if (cmd == "display-message -p '#{session_name}'") {
            return CommandResponse(
                number = 0L,
                output = listOf("work"),
                isError = false,
            )
        }
        return responses.removeFirstOrNull() ?: CommandResponse(
            number = 0L,
            output = emptyList(),
            isError = false,
        )
    }

    /**
     * Issue #1205: records the panes whose backlog the recovery path asked to
     * drain, so a test can assert the reseed-and-reattach recovery drained the
     * stale burst before recapturing. The fake has no bounded channel, so the
     * count is unused here; it returns 0 like a pane with no queued frames.
     */
    val drainedPaneBacklogs: MutableList<String> = mutableListOf()

    override fun drainPaneOutputBacklog(paneId: String): Int {
        drainedPaneBacklogs += paneId
        return 0
    }

    override fun outputFor(paneId: String): Flow<ControlEvent.Output> =
        if (decoupleOutputForFromEvents) {
            emittedPaneOutputs.filter { it.paneId == paneId }
        } else {
            emittedEvents
                .filterIsInstance<ControlEvent.Output>()
                .filter { it.paneId == paneId }
        }

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
        closeWithEvent(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitClose,
                source = "fake_close",
                intent = "local_close",
            ),
        )
    }

    private fun closeWithEvent(event: TmuxDisconnectEvent) {
        closed = true
        disconnectEventSignal.value = event
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
        closeWithEvent(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitDetach,
                source = "fake_detach",
                intent = "detach_or_replace",
            ),
        )
    }
}
