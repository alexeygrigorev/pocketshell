package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.core.tmux.protocol.ControlEventStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level client wrapping a `tmux -CC` control channel running inside an
 * [SshSession].
 *
 * The `tmux -CC` protocol is line-oriented: tmux speaks structured
 * notifications (`%session-changed`, `%output`, `%window-add`, …) and
 * command-response blocks (`%begin` / `%end` / `%error`) over the same
 * stdio pair as the host shell. This client owns the stdio plumbing —
 * spawn a tmux session over an open SSH shell, parse the stream of
 * notifications into a [Flow]<[ControlEvent]>, and let callers issue
 * commands with matching response correlation.
 *
 * Lifecycle:
 *
 * ```kotlin
 * val client = factory.create(session)
 * client.connect()
 *
 * // Observe all non-output events:
 * scope.launch { client.events.collect { evt -> /* render */ } }
 *
 * // Observe a specific pane's output:
 * scope.launch { client.outputFor("%0").collect { bytes -> /* paint */ } }
 *
 * // Issue a command, wait for the matching response:
 * val r = client.sendCommand("list-sessions")
 *
 * client.close()
 * ```
 *
 * Concrete instances ship via [TmuxClientFactory]; this interface is the
 * boundary downstream modules (`app`, `core-terminal`'s tmux adapter)
 * program against.
 */
public interface TmuxClient : AutoCloseable {

    /**
     * Stream of structured control-mode events from tmux.
     *
     * Hot — backed by a shared flow inside the implementation so that
     * multiple subscribers see the same events without re-reading the SSH
     * stream. Per-pane `%output` events flow here too (they are
     * [ControlEvent.Output] instances) but most callers will subscribe
     * via the dedicated [outputFor] demux instead so they don't have to
     * filter the whole bus.
     */
    public val events: Flow<ControlEvent>

    /**
     * Connect: spawn `tmux -CC` over the wrapped session and start
     * forwarding events. Idempotent — calling twice returns immediately
     * if already connected.
     *
     * Suspends until the underlying SSH shell is open and the reader
     * coroutine is running. Throws [TmuxClientException] on transport
     * failures.
     */
    public suspend fun connect()

    /**
     * Send a tmux command (e.g. `list-sessions`, `kill-window -t @3`) and
     * wait for the matching `%begin` / (`%end` | `%error`) response block.
     *
     * tmux's control mode only allows one outstanding command at a time,
     * so this call serialises internally: concurrent callers are queued.
     *
     * Returns a [CommandResponse] whose [CommandResponse.isError] reflects
     * whether the response block closed with `%error`. Transport drops and
     * client teardown raise [TmuxClientException].
     */
    public suspend fun sendCommand(cmd: String): CommandResponse

    /**
     * Send a non-structural, rendering-only tmux command and wait for its
     * response without treating a delayed response as an immediate transport
     * disconnect.
     *
     * This path is for commands such as `capture-pane` and cursor queries that
     * can safely fail open locally during output storms. Implementations must
     * still protect control-mode FIFO correlation before allowing another
     * command onto the wire.
     */
    public suspend fun sendBestEffortCommand(cmd: String): CommandResponse =
        sendCommand(cmd)

    /**
     * Issue #640: capture a pane's content AND its cursor in a SINGLE wire
     * round-trip.
     *
     * The seed path needs both the `capture-pane` snapshot and the pane's true
     * cursor (`#{cursor_x},#{cursor_y}`, see #259) to repaint a freshly-attached
     * pane correctly. In `tmux -CC` control mode a `cmd1 ; cmd2` request is
     * answered with TWO separate `%begin`/`%end` blocks — chaining does NOT
     * collapse them — so a naive single `sendCommand` would only see the first
     * block and the cursor reply would arrive as an uncorrelated second block.
     *
     * Implementations therefore send the chained command and drain BOTH
     * response blocks under one single-flight acquisition, returning the capture
     * block plus the raw cursor reply line. This collapses the previous two
     * serial seed round-trips into one wire exchange while keeping the cursor
     * restore intact. Best-effort: a missing/failed cursor block degrades to a
     * null [CaptureWithCursor.cursorReply] (seed without explicit cursor
     * restore) rather than failing the capture.
     *
     * The default implementation falls back to two separate best-effort
     * commands for [TmuxClient] doubles that do not model control-mode block
     * correlation.
     */
    public suspend fun captureWithCursor(
        paneId: String,
        scrollbackLines: Int,
    ): CaptureWithCursor {
        val capture = sendBestEffortCommand("capture-pane -p -e -S -$scrollbackLines -t $paneId")
        val cursor = runCatching {
            sendBestEffortCommand("display-message -p -t $paneId '#{cursor_x},#{cursor_y}'")
        }.getOrNull()
            ?.takeUnless { it.isError }
            ?.output
            ?.firstOrNull()
        return CaptureWithCursor(capture = capture, cursorReply = cursor)
    }

    /**
     * Issue #692: send several tmux control-mode commands as ONE chained
     * `cmd1 ; cmd2 ; …` request and drain ALL of their `%begin` / (`%end` |
     * `%error`) response blocks under a SINGLE single-flight acquisition,
     * returning one [CommandResponse] per command in submission order.
     *
     * In `tmux -CC` control mode a chained `cmd1 ; cmd2` request is answered
     * with N SEPARATE `%begin`/`%end` blocks — chaining does NOT collapse
     * them — so a naive single [sendCommand] would see only the first block
     * and leave the rest as uncorrelated late blocks. This generalises the
     * [captureWithCursor] two-block drain so the folder-list discovery probe
     * can fetch `list-sessions` + `list-panes` in ONE wire round-trip instead
     * of two serial ones (the picker-gating enumeration behind issue #470).
     *
     * The returned list has the same size and order as [commands]. The
     * default implementation falls back to issuing each command serially via
     * [sendCommand] for [TmuxClient] doubles that do not model control-mode
     * block correlation.
     */
    public suspend fun sendChainedCommands(commands: List<String>): List<CommandResponse> =
        commands.map { sendCommand(it) }

    /**
     * Subscribe to `%output` events for a single pane.
     *
     * Multiple callers may subscribe to the same pane — the underlying
     * flow is hot and shared, and tmux emits each `%output` once. Pane
     * IDs include the leading `%` (e.g. `%0`, `%12`) — see
     * [ControlEvent.Output] for rationale.
     */
    public fun outputFor(paneId: String): Flow<ControlEvent.Output>

    /**
     * Issue #173: observable signal that the control channel has
     * disconnected — either because [close] was called or because the
     * underlying SSH transport's reader loop exited (clean EOF or
     * exception). Latches to `true` and never flips back; a fresh
     * [TmuxClient] is required to reconnect.
     *
     * Callers use this to react to the OS tearing down the TCP socket
     * underneath us while the app was backgrounded — the [events]
     * `SharedFlow` is hot and cannot signal end-of-stream, so observing
     * `disconnected` is the only way to learn that the reader is gone.
     */
    public val disconnected: StateFlow<Boolean>

    /**
     * Structured reason for [disconnected].
     *
     * Null while the client is live. Once the control channel is closed this
     * latches to the best-known reason and [disconnected] remains the legacy
     * Boolean compatibility signal. Code that needs UI or log diagnostics
     * should prefer this flow so it can distinguish a remote reader EOF from
     * local teardown.
     */
    public val disconnectEvent: StateFlow<TmuxDisconnectEvent?>

    /**
     * Emits when a pane's lossless terminal-output backlog is exhausted.
     *
     * This is a local rendering/ingestion failure signal, not an SSH
     * transport disconnect. Callers should recover the terminal surface
     * (for tmux panes, usually by recreating/reseeding from `capture-pane`)
     * or show a local terminal error state; they must not route this through
     * reconnect.
     */
    public val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow>

    /**
     * Tear down the tmux control channel and the underlying SSH shell.
     * Idempotent. After [close] the [events] flow completes and any
     * pending [sendCommand] calls fail with [TmuxClientException].
     */
    override fun close()

    /**
     * Issue #285: choose tmux's `latest` window-size policy for the
     * active window in [sessionId] so the phone control client can drive
     * sizing when it becomes the most recently active client. This also
     * clears the `manual` policy left behind by older `resize-window`
     * flows.
     */
    public suspend fun setWindowSizeLatest(sessionId: String): CommandResponse

    /**
     * Issue #285: report this `tmux -CC` control client's viewport size
     * to tmux. This is the control-mode primitive equivalent to a real
     * terminal changing size; it avoids forcing the window into
     * `window-size manual`.
     *
     * No-op for non-positive [cols] / [rows], surfaced as a synthetic
     * error response so callers can handle invalid geometry the same way
     * they handle tmux refusals.
     */
    public suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse

    /**
     * Issue #215: server-clean teardown of the tmux `-CC` control client.
     *
     * Sends `detach-client` to the tmux server before tearing the SSH
     * shell down so the server-side `tmux list-clients` entry for this
     * `-CC` connection disappears immediately. Without this step, a hard
     * close of the SSH transport would leave an orphan control client
     * registered on the server — reproducibly observed against tmux on
     * Alpine 3.x — which can block input from other clients (e.g. a
     * plain `tmux attach` from a laptop) attached to the same session.
     *
     * Workflow:
     *  1. Issue `detach-client` over the live control channel via
     *     [sendCommand]. Bounded by [timeoutMs] / 2 so a slow / broken
     *     server cannot wedge the teardown indefinitely.
     *  2. Wait for the server to close the control channel — observed
     *     via [disconnected] flipping `true` from the reader-loop EOF
     *     path. Bounded by the remaining [timeoutMs] budget.
     *  3. Call [close] unconditionally. Step (3) runs whether the
     *     command + wait succeeded or not — losing the SSH transport
     *     mid-detach (network drop, sshd kill, etc.) must still result
     *     in a fully cleaned-up local state.
     *
     * Suspends until the detach round-trip completes or [timeoutMs]
     * elapses. Safe to call concurrently with [sendCommand] — the
     * underlying `sendMutex` serialises us with other inflight commands.
     *
     * Idempotent: calling on an already-closed or never-connected client
     * is a no-op (it still invokes [close] to be defensive).
     *
     * @param timeoutMs total ceiling on time spent on the detach
     *   round-trip + reader-loop EOF wait. Defaults to 1000ms, which is
     *   well above the sub-50ms tmux takes on a healthy localhost /
     *   Docker server but small enough that a stuck transport does not
     *   block app shutdown.
     */
    public suspend fun detachCleanly(timeoutMs: Long = 1_000L)
}

/**
 * Thrown by [TmuxClient] for transport- and protocol-level failures
 * (SSH shell teardown, client close while waiting for a response, etc.).
 */
public class TmuxClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Issue #666: thrown by [TmuxClient.connect] when an attach-only connect
 * (`createIfMissing == false`, e.g. a foreground cold-restore to the last
 * session) finds that the target tmux session no longer exists on the
 * server — it was killed elsewhere while the app was backgrounded.
 *
 * On this restore path the session must NOT be recreated. Callers catch
 * this to surface "that session ended" and drop the user to the host /
 * session list instead of silently resurrecting a session via
 * `new-session -A`.
 */
public class TmuxSessionNotFoundException(
    public val sessionName: String,
    message: String = "tmux session '$sessionName' no longer exists",
) : RuntimeException(message)

public data class TmuxOutputBacklogOverflow(
    val paneId: String,
    val droppedEvents: Int,
)

public data class TmuxDisconnectEvent(
    val reason: TmuxDisconnectReason,
    val source: String,
    val intent: String,
    val commandKind: String? = null,
    val timeoutMode: String? = null,
    val exceptionClass: String? = null,
    val message: String? = null,
)

public enum class TmuxDisconnectReason(public val logValue: String) {
    ExplicitClose("explicit_close"),
    ExplicitDetach("explicit_detach"),
    ReaderEof("reader_eof"),
    ReaderException("reader_exception"),
    CommandTimeout("command_timeout"),
    Unknown("unknown"),
}

/**
 * Issue #676: injectable gate for the per-command response timeout, so the
 * timeout→close→fail-visibly sequence is testable without a wall-clock race.
 *
 * [run] has the exact contract of [kotlinx.coroutines.withTimeoutOrNull]:
 * it runs [body] and returns its result, or `null` if the deadline elapsed
 * first (cancelling [body]). Production wires [RealTime], which delegates
 * straight to `withTimeoutOrNull(timeoutMs)`, so runtime behaviour is
 * unchanged. Unit tests inject a deterministic gate that fires the timeout on
 * an explicit signal instead of a `delay`, removing the slow-runner flake.
 */
internal interface CommandTimeoutGate {
    /**
     * Runs [body] under a [timeoutMs] deadline, returning its result or `null`
     * if the deadline elapsed first (cancelling [body]), exactly like
     * [kotlinx.coroutines.withTimeoutOrNull]. [body] receives a [Checkpoint]
     * it invokes once it has finished its synchronous setup (the command write)
     * and is about to wait for the response. The production gate ignores the
     * checkpoint; the deterministic test gate uses it to know the timeout is now
     * being observed as a post-write event, so it can release a pending test
     * signal without racing the runner's wall clock (issue #676).
     */
    suspend fun <T> run(timeoutMs: Long, body: suspend (Checkpoint) -> T): T?

    /** Invoked by a command body once its write has completed. */
    fun interface Checkpoint {
        fun writeCompleted()
    }

    companion object {
        private val NOOP_CHECKPOINT = Checkpoint { }

        /**
         * Issue #576 (P4) + #794: the production gate is an **idle/inactivity**
         * deadline, not a fixed wall-clock one. [timeoutMs] is the maximum
         * tolerated *silence* on the control channel **measured from the moment
         * this command started waiting**: the deadline re-arms whenever
         * [readerActivityNanos] advances (the reader parsed another event), so
         * the gate fires only after [timeoutMs] elapses with NO reader-side
         * progress *since the command was issued*. A heavy `%output` redraw
         * backlog keeps the reader continuously busy, so a command waiting
         * behind it never self-inflicts a timeout while bytes are still flowing
         * — the #576 "busy link tears down a healthy session" regression.
         *
         * #794: the idle baseline is anchored at
         * `max(commandStart, lastReaderActivity)`, NOT at the raw
         * last-reader-activity timestamp. Without this anchor a command issued
         * on a genuinely-idle-but-HEALTHY channel (a steady foreground hold
         * with no `%output` — the reader has legitimately been quiet for far
         * longer than [timeoutMs]) was judged "already timed out" the instant it
         * was dispatched, because `now - lastReaderActivity` was ALREADY past
         * the window before the command's own reply had any chance to land. That
         * fired the deadline before the write even completed, escalating a
         * read-only `list-sessions` poll to a FATAL transport teardown every
         * ~[timeoutMs] (the #794 ~11s connection flap). Anchoring at the command
         * start gives every command its full window from dispatch; activity
         * that arrives AFTER dispatch still re-arms (the #576 busy-link
         * protection is preserved), and a truly silent command still fires after
         * a full window from its own start.
         *
         * Crucially the signal is **reader-side** progress only: a blackholed
         * / dead peer parses zero events, so [readerActivityNanos] never
         * advances past the command start and the deadline fires after a full
         * window from dispatch — exactly as a fixed timeout would. Dead-peer
         * detection (sshj keepalive, a separate layer) is therefore unaffected
         * — this gate cannot mask a genuinely silent channel, it just no longer
         * counts *pre-command* silence against the command.
         *
         * @param readerActivityNanos supplies the latest reader-progress
         *   timestamp (`System.nanoTime()` units). Read on each poll tick.
         */
        fun realTime(readerActivityNanos: () -> Long): CommandTimeoutGate =
            object : CommandTimeoutGate {
                override suspend fun <T> run(timeoutMs: Long, body: suspend (Checkpoint) -> T): T? =
                    coroutineScope {
                        // #794: anchor the idle baseline at the moment the
                        // command starts waiting so prior channel silence is
                        // never counted against THIS command. Captured before
                        // the body starts.
                        val commandStartNanos = System.nanoTime()
                        val bodyDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                            body(NOOP_CHECKPOINT)
                        }
                        // Idle-deadline watchdog: sleep until the deadline, and
                        // if reader activity advanced while we slept, re-arm
                        // from the new activity timestamp instead of firing.
                        // The body wins the race if the response arrives first.
                        val watchdog = async {
                            val timeoutNanos = timeoutMs * 1_000_000L
                            // Poll granularity: bounded so we re-check activity
                            // periodically rather than oversleeping a full
                            // window past the last byte. Small relative to
                            // timeoutMs, capped so tiny timeouts still tick.
                            val tickMs = (timeoutMs / 10).coerceIn(1L, 250L)
                            while (true) {
                                // #794: the idle clock runs from the LATER of
                                // the command start and the last reader event —
                                // reader activity after dispatch re-arms (#576),
                                // but silence that predates the command is not
                                // charged to it.
                                val idleBaseline =
                                    maxOf(commandStartNanos, readerActivityNanos())
                                val idleNanos = System.nanoTime() - idleBaseline
                                if (idleNanos >= timeoutNanos) return@async
                                val remainingNanos = timeoutNanos - idleNanos
                                val sleepMs = (remainingNanos / 1_000_000L)
                                    .coerceIn(1L, tickMs)
                                delay(sleepMs)
                            }
                        }
                        try {
                            select {
                                bodyDeferred.onAwait { result ->
                                    watchdog.cancel()
                                    result
                                }
                                watchdog.onAwait {
                                    bodyDeferred.cancel()
                                    null
                                }
                            }
                        } finally {
                            watchdog.cancel()
                            bodyDeferred.cancel()
                        }
                    }
            }
    }
}

/**
 * Real implementation of [TmuxClient] backed by an [SshSession]'s shell
 * channel.
 *
 * Construction is cheap — [connect] does the actual SSH shell open and
 * reader-coroutine launch. Exposed via [TmuxClientFactory] so callers
 * don't need to know about the [SshShell] / coroutine wiring.
 *
 * @param session the SSH transport. Caller-owned: closing the client
 *   tears down the *shell* channel but leaves the session usable for
 *   further exec / port-forward calls (parity with `core-ssh`'s
 *   [SshSession.startShell]).
 * @param scope coroutine scope to launch the stdout reader on. A
 *   `SupervisorJob`-based child scope is created internally so the
 *   reader's failures don't cancel siblings of [scope]. The caller's
 *   scope's lifetime is the upper bound on this client's lifetime.
 * @param sessionName tmux session name to attach to (or create). Defaults
 *   to `"pocketshell"`. Blank input also falls back to this default. We
 *   use `new-session -A -s <name>` so existing sessions with the same
 *   name are reattached rather than refused.
 * @param startDirectory optional tmux `-c` start directory for newly
 *   created sessions. Existing sessions are still attached via `-A`.
 * @param commandTimeoutMs ceiling for a single control-mode command
 *   transaction, including stdin write/flush and response wait. A
 *   structural command timeout closes the client so callers see a
 *   disconnect instead of waiting forever behind a stalled SSH/tmux
 *   channel. Best-effort commands, fail-open rendering/resize commands,
 *   and completed `send-keys` writes get a short late-response drain
 *   window first so local output pressure does not masquerade as a
 *   transport disconnect. Command writes that fail or never complete
 *   remain fatal.
 */
internal class RealTmuxClient(
    private val session: SshSession,
    scope: CoroutineScope,
    private val sessionName: String = DEFAULT_SESSION_NAME,
    private val startDirectory: String? = null,
    // Issue #666: attach-OR-create vs attach-only. The default `true`
    // preserves the explicit user "new/create session" intent (and reconnect
    // to a live session) which uses `new-session -A` so a missing session is
    // created. When `false` (the foreground cold-restore path) [connect] first
    // runs a `tmux has-session` preflight and throws [TmuxSessionNotFoundException]
    // for a session killed elsewhere, so a gone session is never resurrected.
    private val createIfMissing: Boolean = true,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    // Issue #676 / #576 (P4): seam for the per-command response timeout. When
    // left null, production wires the real idle-deadline gate
    // ([CommandTimeoutGate.realTime]) keyed on reader-side activity, so the
    // deadline fires only after the control channel has been *silent* for
    // [commandTimeoutMs] — a busy-but-alive redraw backlog keeps re-arming it
    // and never self-inflicts a close. Unit tests inject a deterministic gate
    // so the timeout fires exactly when the test releases it instead of racing
    // a tight wall-clock window against the runner's scheduler load.
    commandTimeoutGate: CommandTimeoutGate? = null,
) : TmuxClient {
    // Issue #576 (P4): monotonic counter of the last reader-side event the
    // control-mode reader parsed (any `%begin`/`%end`/`%error`/`%output`).
    // The idle-deadline gate re-arms whenever this advances, so a command
    // waiting behind a heavy `%output` backlog only times out when bytes
    // genuinely STOP flowing — not merely because 10 s of wall-clock elapsed
    // while the reader was still busy draining. Keyed strictly on reader-side
    // progress (NOT local write completion) so a blackholed link — which sends
    // zero bytes — still fires the deadline and surfaces the real drop.
    @Volatile
    private var lastReaderActivityNanos: Long = System.nanoTime()

    // Issue #676 / #576 (P4): the active gate. Resolved here (not as a default
    // constructor arg) because the production idle gate needs a reference to
    // this instance's [lastReaderActivityNanos], which isn't available at the
    // constructor-default evaluation point.
    private val commandTimeoutGate: CommandTimeoutGate =
        commandTimeoutGate ?: CommandTimeoutGate.realTime { lastReaderActivityNanos }
    private val clientId: Long = NEXT_CLIENT_ID.incrementAndGet()
    private val clientHash: Int = System.identityHashCode(this)

    // Child scope rooted under the caller's scope. SupervisorJob() so a
    // failing reader doesn't tear down peers; Dispatchers.IO because the
    // reader blocks on the SSH input stream.
    private val clientScope = CoroutineScope(
        scope.coroutineContext +
            SupervisorJob(scope.coroutineContext[Job]) +
            Dispatchers.IO,
    )

    // Shared flow of all events. extraBufferCapacity absorbs short bursts
    // without blocking the reader. Pane `%output` is emitted to this bus on a
    // best-effort basis from [emitOutput]; the per-pane terminal stream is the
    // authoritative output path, and it must never let diagnostic/global event
    // collectors starve the control reader.
    //
    // replay = 0 because subscribers only care about events that arrive
    // after they start collecting; tmux's structural state (sessions /
    // windows / panes) is re-derivable by issuing `list-sessions` etc.
    private val eventBus = MutableSharedFlow<ControlEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )

    override val events: Flow<ControlEvent> = eventBus.asSharedFlow()

    private val paneOutputPipes =
        ConcurrentHashMap<String, PaneOutputPipe>()

    // Issue #173: latched signal that the reader loop has exited (or
    // [close] was called). The reader sets this from its `finally` block
    // so subscribers (notably [TmuxSessionViewModel.attachClient]) can
    // observe a socket tear-down even though the hot [eventBus]
    // SharedFlow never completes on its own.
    private val _disconnected: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val disconnected: StateFlow<Boolean> = _disconnected.asStateFlow()

    private val _disconnectEvent: MutableStateFlow<TmuxDisconnectEvent?> = MutableStateFlow(null)

    override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> = _disconnectEvent.asStateFlow()

    private val _outputBacklogOverflows = MutableSharedFlow<TmuxOutputBacklogOverflow>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> =
        _outputBacklogOverflows.asSharedFlow()

    // Outstanding sendCommand waiters, FIFO. tmux's control mode is
    // strict one-at-a-time, and we serialise on the Kotlin side via
    // [sendMutex] before we ever write to stdin, so the queue is at most
    // one entry deep in practice. We still use a deque so future relaxations
    // (or genuine concurrent submissions during a brief race) don't
    // silently drop responses.
    //
    // Concurrent access from sendCommand (producer) and the reader loop
    // (consumer). ConcurrentLinkedDeque is the simplest fit — peek + poll
    // + offer are all O(1) and lock-free.
    private val pendingQueue = ConcurrentLinkedDeque<PendingCommand>()
    private val responseCorrelationLock = Any()
    private var staleResponseBlocksToIgnore: Int = 0
    private val eventBusDroppedEvents = AtomicInteger(0)
    private val eventBusOverflowDiagnosticEmitted = AtomicBoolean(false)

    @Volatile
    private var readerExitIntent: ReaderExitIntent = ReaderExitIntent.Unknown

    @Volatile
    private var readerExitCommandKind: String? = null

    @Volatile
    private var readerExitTimeoutMode: CommandTimeoutMode? = null

    // Serialises concurrent sendCommand calls. tmux can only handle one
    // at a time; we queue on the Kotlin side instead of letting them
    // interleave on the wire.
    private val sendMutex = Mutex()

    @Volatile
    private var shell: SshShell? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var closed: Boolean = false

    override suspend fun connect() {
        if (connected) return
        if (closed) throw TmuxClientException("client is closed")
        val resolvedSessionName = sessionName.trim().ifBlank { DEFAULT_SESSION_NAME }
        // Issue #666: attach-only restore. Before opening any shell or writing
        // a single byte, probe whether the target session still exists. tmux
        // `has-session` exits 0 when the session is alive and non-zero when it
        // is gone. A gone session must NOT be recreated — throw a distinct
        // signal so the caller drops to the host/session list instead of
        // resurrecting it via the `new-session -A` spawn below. We run this on
        // a separate `exec` channel (not the control shell) so the absence
        // check never touches the control-mode wire and never spawns tmux.
        if (!createIfMissing) {
            val probe = try {
                withContext(Dispatchers.IO) {
                    session.exec("tmux has-session -t '${escapeSingleQuoted(resolvedSessionName)}'")
                }
            } catch (t: Throwable) {
                throw TmuxClientException(
                    "failed to preflight tmux has-session for '$resolvedSessionName': ${t.message}",
                    t,
                )
            }
            if (probe.exitCode != 0) {
                Log.i(
                    ISSUE_105_DIAG_TAG,
                    "tmux-has-session-gone session=$resolvedSessionName exit=${probe.exitCode}",
                )
                throw TmuxSessionNotFoundException(resolvedSessionName)
            }
        }
        // Open the SSH shell and launch the reader. We do not synchronously
        // wait for tmux to emit a marker before returning — control mode
        // produces events asynchronously and the caller may immediately want
        // to send a command. The reader loop is up before connect() returns,
        // so any events arriving in the race window are buffered by
        // eventBus.extraBufferCapacity.
        val sh = try {
            withContext(Dispatchers.IO) { session.startShell() }
        } catch (t: Throwable) {
            throw TmuxClientException("failed to open SSH shell for tmux -CC: ${t.message}", t)
        }
        shell = sh
        // Launch the reader BEFORE we write the spawn line so we don't miss
        // tmux's initial notifications. The reader is suspendable on the
        // first read, so launching it doesn't race with the write below.
        readerJob = clientScope.launch { readerLoop(sh) }
        // Write the tmux -CC command. `new-session -A -s <name>` attaches to
        // an existing session with that name if it exists, otherwise creates
        // a new one — the right behaviour for "reattach across phone
        // reconnects" which is the whole point of running tmux remotely.
        try {
            val resolvedStartDirectory = startDirectory?.trim().orEmpty()
            val command = buildString {
                append("tmux -CC new-session -A -s '")
                append(escapeSingleQuoted(resolvedSessionName))
                append("'")
                if (resolvedStartDirectory.isNotEmpty()) {
                    append(" -c '")
                    append(escapeSingleQuoted(resolvedStartDirectory))
                    append("'")
                }
                append('\n')
            }
            withContext(Dispatchers.IO) {
                sh.stdin.write(command.toByteArray(Charsets.UTF_8))
                sh.stdin.flush()
            }
        } catch (t: Throwable) {
            readerJob?.cancel()
            readerJob = null
            runCatching { sh.close() }
            shell = null
            throw TmuxClientException("failed to spawn tmux -CC: ${t.message}", t)
        }
        connected = true
    }

    override suspend fun sendCommand(cmd: String): CommandResponse =
        sendCommandInternal(cmd, timeoutMode = timeoutModeForCommand(cmd))

    override suspend fun sendBestEffortCommand(cmd: String): CommandResponse =
        sendCommandInternal(cmd, timeoutMode = CommandTimeoutMode.BestEffortDrain)

    /**
     * Issue #640: capture a pane + its cursor in a single single-flight wire
     * exchange. tmux `-CC` answers `capture-pane ; display-message` with two
     * separate `%begin`/`%end` blocks, so we register TWO pending commands
     * (capture, then cursor) under one [sendMutex] acquisition, write the
     * chained line once, and drain both blocks in FIFO order. This collapses
     * the two serial seed round-trips into one while keeping the cursor restore.
     */
    override suspend fun captureWithCursor(
        paneId: String,
        scrollbackLines: Int,
    ): CaptureWithCursor {
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        val chained =
            "capture-pane -p -e -S -$scrollbackLines -t $paneId ; " +
                "display-message -p -t $paneId '#{cursor_x},#{cursor_y}'"
        return sendMutex.withLock {
            if (closed) throw TmuxClientException("client is closed")
            if (!connected) throw TmuxClientException("client is not connected")
            val sh = shell ?: throw TmuxClientException("client has no active shell")

            // Register both expected response blocks BEFORE writing so neither
            // can be lost if it arrives before the write returns. Order matters:
            // tmux answers the chained commands in submission order, so the
            // capture block correlates to [capturePending] and the cursor block
            // to [cursorPending].
            val capturePending = PendingCommand(deferred = CompletableDeferred())
            val cursorPending = PendingCommand(deferred = CompletableDeferred())
            synchronized(responseCorrelationLock) {
                pendingQueue.offer(capturePending)
                pendingQueue.offer(cursorPending)
            }

            val writeResult = CompletableDeferred<Unit>()
            val writeJob = clientScope.launch {
                try {
                    writeLine(sh.stdin, chained)
                    writeResult.complete(Unit)
                } catch (t: Throwable) {
                    writeResult.completeExceptionally(t)
                }
            }
            var writeCompleted = false

            try {
                val capture = commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    writeResult.await()
                    writeCompleted = true
                    checkpoint.writeCompleted()
                    capturePending.deferred.await()
                } ?: run {
                    // Capture itself timed out: tear down both pending entries
                    // and surface a best-effort failure (the caller falls back
                    // to opening the seed gate without a snapshot).
                    cleanupCaptureWithCursorPending(capturePending, cursorPending)
                    writeJob.cancel()
                    throw TmuxClientException(
                        "tmux capture-pane (combined) timed out after ${commandTimeoutMs}ms",
                    )
                }
                // The cursor block is best-effort: a slow/absent reply degrades
                // to no explicit cursor restore rather than failing the seed.
                val cursorReply = commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    checkpoint.writeCompleted()
                    cursorPending.deferred.await()
                }
                    ?.takeUnless { it.isError }
                    ?.output
                    ?.firstOrNull()
                if (cursorReply == null) {
                    // Stop waiting on the cursor block: drop it from the queue so
                    // a late reply is treated as a stale block to ignore rather
                    // than mis-correlating with the NEXT command on the wire.
                    synchronized(responseCorrelationLock) {
                        val stillWaitingForBegin = cursorPending.commandNumber < 0L
                        if (pendingQueue.remove(cursorPending) && stillWaitingForBegin) {
                            staleResponseBlocksToIgnore += 1
                        }
                    }
                }
                CaptureWithCursor(capture = capture, cursorReply = cursorReply)
            } catch (t: Throwable) {
                cleanupCaptureWithCursorPending(capturePending, cursorPending)
                writeJob.cancel()
                if (!writeCompleted) {
                    close()
                    throw TmuxClientException(
                        "failed to write tmux combined capture command: ${t.message}",
                        t,
                    )
                }
                throw t
            }
        }
    }

    /**
     * Issue #640: remove both pending entries for a combined capture exchange,
     * accounting for any block tmux has already begun (so its late `%end`
     * drains as a stale block rather than mis-correlating with a later command).
     */
    private fun cleanupCaptureWithCursorPending(
        capturePending: PendingCommand,
        cursorPending: PendingCommand,
    ) {
        synchronized(responseCorrelationLock) {
            for (pending in listOf(capturePending, cursorPending)) {
                val startedBlock = pending.commandNumber >= 0L
                if (pendingQueue.remove(pending) && startedBlock) {
                    staleResponseBlocksToIgnore += 1
                }
            }
        }
    }

    /**
     * Issue #692: chain N commands onto the wire under one [sendMutex]
     * acquisition and drain all N `%begin`/`%end` blocks in FIFO order.
     *
     * Generalises [captureWithCursor]'s two-block drain: register one
     * [PendingCommand] per command BEFORE the single chained write so no
     * block can be lost if it arrives before the write returns, then await
     * each block in submission order. Every block is best-effort: a slow /
     * failed block degrades to an error [CommandResponse] so the caller can
     * fall back per-section rather than failing the whole enumeration. This
     * collapses the folder-list discovery `list-sessions` + `list-panes`
     * pair into a single wire round-trip (the picker-gating enumeration
     * behind #470).
     */
    override suspend fun sendChainedCommands(commands: List<String>): List<CommandResponse> {
        if (commands.isEmpty()) return emptyList()
        if (commands.size == 1) return listOf(sendCommand(commands.single()))
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        val chained = commands.joinToString(separator = " ; ")
        // Issue #702: bound the SINGLE-FLIGHT ACQUIRE itself, not the per-block
        // drain inside the lock. `commandTimeoutGate.run(...)` below only wraps
        // the writes/awaits AFTER the mutex is held; the wait to ACQUIRE the
        // mutex had no deadline of its own. The folder-list picker enumeration
        // reaches here on the one shared per-host `-CC` client and serializes
        // against the in-session terminal's own control traffic. If a current
        // holder never releases (e.g. a stalled command during an attach/
        // teardown window) the enumeration would park on the acquire forever
        // and pin the session picker in `Loading` (no SSH socket, no
        // `PsFolderProbe` — exactly the #470 capture). `Mutex.lock()` IS a
        // cancellable suspension point, so we bound ONLY the acquire with
        // `withTimeoutOrNull` and, on a wedged channel, degrade to best-effort
        // error responses instead of hanging. We deliberately do NOT wrap the
        // drain in the same bound: the drain has its own per-block timeouts and
        // a healthy trailing block can legitimately take up to `commandTimeoutMs`
        // to arrive — wrapping both would race the two deadlines and could turn
        // a delivered first block into a spurious error. The caller (folder-list
        // discovery) treats an all-error result as a signal to fall through to
        // the bounded SSH-lease enumeration. Structural fix to make the bare
        // acquire bounded everywhere (sendCommand/captureWithCursor too) is #687.
        val acquired = withTimeoutOrNull(commandTimeoutMs) {
            sendMutex.lock()
            true
        }
        if (acquired != true) {
            Log.w(
                ISSUE_244_DIAG_TAG,
                "tmux chained command acquire wedged >${commandTimeoutMs}ms; " +
                    "degrading to error responses so the caller can fall back. " +
                    "n=${commands.size}",
            )
            return commands.map {
                CommandResponse(number = -1L, output = emptyList(), isError = true)
            }
        }
        return try {
            drainChainedUnderLock(commands, chained)
        } finally {
            sendMutex.unlock()
        }
    }

    /**
     * Issue #692/#702: the body of [sendChainedCommands] that runs WHILE the
     * single-flight [sendMutex] is held. Extracted so the ACQUIRE can be bounded
     * separately (#702) from the per-block drain. Returns one [CommandResponse]
     * per command in submission order; a slow/absent individual block degrades
     * to a synthetic error response (best-effort) rather than failing the whole
     * enumeration.
     */
    private suspend fun drainChainedUnderLock(
        commands: List<String>,
        chained: String,
    ): List<CommandResponse> {
        return run {
            if (closed) throw TmuxClientException("client is closed")
            if (!connected) throw TmuxClientException("client is not connected")
            val sh = shell ?: throw TmuxClientException("client has no active shell")

            // Register every expected response block BEFORE writing so none
            // can be lost if it arrives before the write returns. tmux answers
            // the chained commands in submission order, so the pending entries
            // correlate to the blocks one-for-one.
            val pendings = commands.map { PendingCommand(deferred = CompletableDeferred()) }
            synchronized(responseCorrelationLock) {
                pendings.forEach { pendingQueue.offer(it) }
            }

            val writeResult = CompletableDeferred<Unit>()
            val writeJob = clientScope.launch {
                try {
                    writeLine(sh.stdin, chained)
                    writeResult.complete(Unit)
                } catch (t: Throwable) {
                    writeResult.completeExceptionally(t)
                }
            }
            var writeCompleted = false

            try {
                val first = commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    writeResult.await()
                    writeCompleted = true
                    checkpoint.writeCompleted()
                    pendings.first().deferred.await()
                } ?: run {
                    cleanupChainedPending(pendings)
                    writeJob.cancel()
                    throw TmuxClientException(
                        "tmux chained command (first block) timed out after ${commandTimeoutMs}ms",
                    )
                }

                val responses = ArrayList<CommandResponse>(commands.size)
                responses += first
                // Remaining blocks are best-effort: a slow/absent block
                // degrades to a synthetic error response (and is dropped from
                // the queue so a late reply is treated as stale rather than
                // mis-correlating with the next command on the wire).
                for (pending in pendings.drop(1)) {
                    val response = commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                        checkpoint.writeCompleted()
                        pending.deferred.await()
                    }
                    if (response != null) {
                        responses += response
                    } else {
                        synchronized(responseCorrelationLock) {
                            val stillWaitingForBegin = pending.commandNumber < 0L
                            if (pendingQueue.remove(pending) && stillWaitingForBegin) {
                                staleResponseBlocksToIgnore += 1
                            }
                        }
                        responses += CommandResponse(
                            number = -1L,
                            output = emptyList(),
                            isError = true,
                        )
                    }
                }
                responses
            } catch (t: Throwable) {
                cleanupChainedPending(pendings)
                writeJob.cancel()
                if (!writeCompleted) {
                    close()
                    throw TmuxClientException(
                        "failed to write tmux chained command: ${t.message}",
                        t,
                    )
                }
                throw t
            }
        }
    }

    /**
     * Issue #692: remove every still-pending entry for a chained exchange,
     * accounting for any block tmux has already begun (so its late `%end`
     * drains as a stale block rather than mis-correlating with a later
     * command). Mirrors [cleanupCaptureWithCursorPending] for N blocks.
     */
    private fun cleanupChainedPending(pendings: List<PendingCommand>) {
        synchronized(responseCorrelationLock) {
            for (pending in pendings) {
                val startedBlock = pending.commandNumber >= 0L
                if (pendingQueue.remove(pending) && startedBlock) {
                    staleResponseBlocksToIgnore += 1
                }
            }
        }
    }

    private suspend fun sendCommandInternal(
        cmd: String,
        timeoutMode: CommandTimeoutMode,
    ): CommandResponse {
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        // Single-flight: tmux only honours one outstanding command at a
        // time. The deferred is registered (queued) BEFORE we write the
        // bytes so we can't lose a response that arrives before we've
        // returned from the write.
        return sendMutex.withLock {
            if (closed) throw TmuxClientException("client is closed")
            if (!connected) throw TmuxClientException("client is not connected")
            val sh = shell ?: throw TmuxClientException("client has no active shell")
            val deferred = CompletableDeferred<CommandResponse>()
            val pendingCmd = PendingCommand(deferred = deferred)
            synchronized(responseCorrelationLock) {
                pendingQueue.offer(pendingCmd)
            }

            val writeResult = CompletableDeferred<Unit>()
            val writeJob = clientScope.launch {
                try {
                    writeLine(sh.stdin, cmd)
                    writeResult.complete(Unit)
                } catch (t: Throwable) {
                    writeResult.completeExceptionally(t)
                }
            }
            var writeCompleted = false

            val response = try {
                commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    writeResult.await()
                    writeCompleted = true
                    checkpoint.writeCompleted()
                    deferred.await()
                }
            } catch (t: Throwable) {
                synchronized(responseCorrelationLock) {
                    pendingQueue.remove(pendingCmd)
                }
                writeJob.cancel()
                if (!writeCompleted) {
                    val kind = commandKind(cmd)
                    Log.w(
                        ISSUE_244_DIAG_TAG,
                        "tmux-command-write-failed kind=$kind cause=${t.javaClass.simpleName}",
                    )
                    close()
                    throw TmuxClientException("failed to write tmux command `$kind`: ${t.message}", t)
                }
                throw t
            }
            if (response != null) return@withLock response

            val kind = commandKind(cmd)
            val exception = TmuxClientException(
                "tmux command `$kind` timed out after ${commandTimeoutMs}ms",
            )
            val effectiveTimeoutMode = if (writeCompleted) {
                timeoutMode
            } else {
                CommandTimeoutMode.FatalClose
            }
            recordCommandTimeout(
                kind = kind,
                timeoutMode = effectiveTimeoutMode,
                writeCompleted = writeCompleted,
            )
            if (effectiveTimeoutMode != CommandTimeoutMode.FatalClose) {
                Log.w(
                    ISSUE_244_DIAG_TAG,
                    "tmux-command-${effectiveTimeoutMode.logName}-timeout kind=$kind timeoutMs=$commandTimeoutMs " +
                        "lateDrainMs=$BEST_EFFORT_LATE_RESPONSE_DRAIN_MS",
                )
                val lateResponse = commandTimeoutGate.run(BEST_EFFORT_LATE_RESPONSE_DRAIN_MS) { checkpoint ->
                    checkpoint.writeCompleted()
                    deferred.await()
                }
                if (lateResponse != null) {
                    Log.i(
                        ISSUE_244_DIAG_TAG,
                        "tmux-command-${effectiveTimeoutMode.logName}-late-drained kind=$kind " +
                            "number=${lateResponse.number}",
                    )
                    throw exception
                }
                Log.w(
                    ISSUE_244_DIAG_TAG,
                    "tmux-command-${effectiveTimeoutMode.logName}-quarantine-expired kind=$kind",
                )
                val waitingForBegin = synchronized(responseCorrelationLock) {
                    val waiting = pendingCmd.commandNumber < 0L
                    if (effectiveTimeoutMode == CommandTimeoutMode.FailOpenDrain && waiting) {
                        staleResponseBlocksToIgnore += 1
                    }
                    pendingQueue.remove(pendingCmd)
                    waiting
                }
                if (effectiveTimeoutMode == CommandTimeoutMode.FailOpenDrain && waitingForBegin) {
                    Log.w(
                        ISSUE_244_DIAG_TAG,
                        "tmux-command-fail-open-stale-response-queued kind=$kind",
                    )
                }
                throw exception
            }
            writeJob.cancel()
            deferred.completeExceptionally(exception)
            Log.w(
                ISSUE_244_DIAG_TAG,
                "tmux-command-timeout kind=$kind timeoutMs=$commandTimeoutMs",
            )
            synchronized(responseCorrelationLock) {
                pendingQueue.remove(pendingCmd)
            }
            closeInternal(
                ReaderExitIntent.CommandTimeout,
                commandKind = kind,
                timeoutMode = effectiveTimeoutMode,
            )
            throw exception
        }
    }

    override fun outputFor(paneId: String): Flow<ControlEvent.Output> {
        return paneOutputPipes.getOrPut(paneId) {
            PaneOutputPipe.create(
                scope = clientScope,
                onOverflow = { overflow ->
                    _outputBacklogOverflows.tryEmit(overflow)
                },
                diagnosticFields = {
                    commonDiagnosticFields() + mapOf("session" to sessionName)
                },
            )
        }.flow.asSharedFlow()
    }

    override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
        sendCommandInternal(
            "set-window-option -t '${escapeSingleQuoted(sessionId)}' window-size latest",
            timeoutMode = CommandTimeoutMode.FailOpenDrain,
        )

    override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse {
        if (cols <= 0 || rows <= 0) {
            return CommandResponse(
                number = -1L,
                output = listOf("refresh-client: non-positive dimensions ${cols}x${rows}"),
                isError = true,
            )
        }
        return sendCommandInternal(
            "refresh-client -C ${cols}x${rows}",
            timeoutMode = CommandTimeoutMode.FailOpenDrain,
        )
    }

    override suspend fun detachCleanly(timeoutMs: Long) {
        markReaderExitIntent(ReaderExitIntent.DetachOrReplace)
        // Idempotent — once we have already torn down, there is no
        // server-side state to release. Run [close] anyway so callers
        // can use this as their single teardown entry point without
        // tracking lifecycle state themselves.
        if (closed) return
        if (!connected) {
            close()
            return
        }
        val sendBudget = (timeoutMs / 2).coerceAtLeast(100L)
        // Step 1: ask tmux to detach this control client. tmux replies
        // with `%begin` / `%end` for the command response AND then
        // emits `%exit` plus closes the control channel — the reader
        // loop sees EOF and flips [_disconnected] to `true`. We do not
        // care whether the response was `isError` (it can be on an
        // already-detached client); the response existing means tmux
        // received the request, and the channel-close that follows is
        // what proves the server-side client entry is gone.
        //
        // `withTimeoutOrNull` returns null on timeout
        // so a wedged server cannot make us block forever. We swallow
        // [TmuxClientException] for the same reason — losing the
        // control channel mid-send is exactly the case where there is
        // nothing for the server to do for us; the unconditional
        // [close] below handles local-side cleanup.
        runCatching {
            withTimeoutOrNull(sendBudget) {
                sendCommand("detach-client")
            }
        }
        // Step 2: wait for the reader loop to observe EOF on the
        // control channel. This is the structural confirmation that
        // the server has dropped this `-CC` client — `%exit` lands on
        // the events bus, the reader's `readLine()` returns null on
        // the next iteration, and the `finally` block in
        // [readerLoop] sets [_disconnected] to `true`. We deliberately
        // observe [_disconnected] instead of subscribing to the events
        // SharedFlow because the SharedFlow has no end-of-stream
        // semantics — the `disconnected` StateFlow is the latched
        // signal designed for exactly this purpose (see issue #173).
        val remaining = (timeoutMs - sendBudget).coerceAtLeast(50L)
        runCatching {
            withTimeoutOrNull(remaining) {
                _disconnected.filter { it }.first()
            }
        }
        // Step 3: tear down the local SSH shell + scope. Safe to call
        // even when the server already closed the channel — [close]
        // is idempotent.
        close()
    }

    override fun close() {
        closeInternal(ReaderExitIntent.LocalClose)
    }

    private fun closeInternal(
        intent: ReaderExitIntent,
        commandKind: String? = null,
        timeoutMode: CommandTimeoutMode? = null,
    ) {
        if (closed) return
        markReaderExitIntent(intent, commandKind = commandKind, timeoutMode = timeoutMode)
        closed = true
        connected = false
        paneOutputPipes.values.forEach { it.close() }
        // Issue #173: signal disconnection BEFORE we tear the rest down
        // so observers like [TmuxSessionViewModel] can flip their
        // connection-status state without racing the scope cancel.
        publishDisconnectEvent(
            disconnectEventFor(
                cause = classifyReaderExit("local"),
                source = "local",
            ),
        )
        _disconnected.value = true
        readerJob?.cancel()
        readerJob = null
        runCatching { shell?.close() }
        shell = null
        // Fail any outstanding sendCommand waiters so callers don't block
        // forever after a teardown.
        val pending = synchronized(responseCorrelationLock) {
            staleResponseBlocksToIgnore = 0
            drainPendingCommands()
        }
        pending.forEach { cmd ->
            cmd.deferred.completeExceptionally(
                TmuxClientException("client closed while waiting for response"),
            )
        }
        clientScope.cancel()
    }

    private fun markReaderExitIntent(
        intent: ReaderExitIntent,
        commandKind: String? = null,
        timeoutMode: CommandTimeoutMode? = null,
    ) {
        if (readerExitIntent.priority > intent.priority) return
        readerExitIntent = intent
        readerExitCommandKind = commandKind
        readerExitTimeoutMode = timeoutMode
    }

    /**
     * Long-running coroutine that reads `tmux -CC`'s stdout line-by-line,
     * feeds each line to [ControlEventStream], and forwards events to
     * [eventBus] / pending-command waiters.
     */
    private suspend fun readerLoop(sh: SshShell) {
        // Issue #435: the control-mode reader is BYTE-oriented. tmux under a
        // UTF-8 locale emits raw high UTF-8 bytes inside `%output` data
        // (it does NOT octal-escape them) and can split a single multi-byte
        // character across two consecutive `%output` events. Decoding the
        // line to a String here (the old `InputStreamReader(UTF_8)` +
        // `readLine()` path) decoded each orphaned byte to `U+FFFD` and lost
        // the original byte — that was the Cyrillic-`??` corruption. So we
        // frame lines on the `0x0A` (LF) byte and hand each raw line, bytes
        // intact, to [ControlEventStream]. The protocol's structural bytes
        // (`%`, opcode, IDs, numbers, LF) are all 7-bit ASCII, so framing on
        // the LF byte is safe; high bytes only ever appear inside the
        // `%output` data tail, which the parser slices as raw bytes.
        val input = sh.stdout

        // Turn the blocking read loop into a Flow<ByteArray> of LF-framed
        // lines. We drive the read manually inside a cancellation-aware loop
        // because the underlying read is blocking. A growable buffer
        // accumulates the current line; we emit a copy on each LF and trim a
        // trailing CR (tmux on alpine emits LF-only, but be robust to CRLF).
        var readerExitSource = "eof"
        var readerFailureClass: String? = null
        var readerFailureMessage: String? = null
        val lines = flow {
            val buffer = java.io.ByteArrayOutputStream(DEFAULT_LINE_BUFFER_BYTES)
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = try {
                    input.read(chunk)
                } catch (t: Throwable) {
                    readerExitSource = "read_failure"
                    readerFailureClass = t.javaClass.simpleName
                    readerFailureMessage = t.message
                    // Channel torn down (close() called, transport drop,
                    // etc.). End the flow rather than re-throw so the reader
                    // loop completes cleanly. We surface the cause via the
                    // diagnostic tag so the reviewer can tell an SSH-transport
                    // drop apart from a clean close — the `-1` return below
                    // covers the clean "EOF without throw" case.
                    Log.w(
                        ISSUE_105_DIAG_TAG,
                        "ssh-read-failed cause=${t.javaClass.simpleName}: ${t.message}",
                    )
                    -1
                }
                if (read < 0) {
                    Log.i(ISSUE_105_DIAG_TAG, "ssh-read-eof")
                    break
                }
                var i = 0
                while (i < read) {
                    val b = chunk[i]
                    if (b == LF_BYTE) {
                        emit(takeLine(buffer))
                        buffer.reset()
                    } else {
                        buffer.write(b.toInt())
                    }
                    i++
                }
            }
        }

        // The command currently accumulating payload, if any. tmux's
        // protocol guarantees responses arrive in submission order and
        // that there's never more than one outstanding command, so we pop
        // the next pending entry from the queue on `%begin` and complete
        // it on `%end` / `%error`.
        var inflight: PendingCommand? = null
        var ignoredResponseNumber: Long = -1L
        val stream = ControlEventStream(
            onResponsePayload = { _, line ->
                inflight?.output?.add(line)
            },
        )

        try {
            stream.events(lines).collect { event ->
                // Issue #576 (P4): record reader-side progress on EVERY parsed
                // control event. The idle-deadline command gate re-arms off
                // this, so a command waiting behind a long `%output` backlog
                // does not self-inflict a fatal timeout while the link is
                // observably still delivering bytes. This is the "busy ≠ dead"
                // distinguisher: a blackholed link parses no events, so the
                // counter stalls and the deadline still fires (dead-peer
                // detection is unaffected).
                lastReaderActivityNanos = System.nanoTime()
                when (event) {
                    is ControlEvent.Begin -> {
                        val match = synchronized(responseCorrelationLock) {
                            if (staleResponseBlocksToIgnore > 0) {
                                staleResponseBlocksToIgnore -= 1
                                ignoredResponseNumber = event.number
                                null
                            } else {
                                // The next pending command — if any — is for this
                                // `%begin`. We capture the tmux-assigned number
                                // for the eventual CommandResponse.number field.
                                pendingQueue.poll()
                            }
                        }
                        if (match != null) {
                            match.commandNumber = event.number
                            inflight = match
                        }
                    }
                    is ControlEvent.End -> {
                        val match = inflight
                        if (ignoredResponseNumber == event.number) {
                            ignoredResponseNumber = -1L
                        } else if (match != null && match.commandNumber == event.number) {
                            match.deferred.complete(
                                CommandResponse(
                                    number = match.commandNumber,
                                    output = match.output.toList(),
                                    isError = false,
                                ),
                            )
                        }
                        if (match?.commandNumber == event.number) {
                            inflight = null
                        }
                    }
                    is ControlEvent.Error -> {
                        val match = inflight
                        if (ignoredResponseNumber == event.number) {
                            ignoredResponseNumber = -1L
                        } else if (match != null && match.commandNumber == event.number) {
                            match.deferred.complete(
                                CommandResponse(
                                    number = match.commandNumber,
                                    output = match.output.toList(),
                                    isError = true,
                                ),
                            )
                        }
                        if (match?.commandNumber == event.number) {
                            inflight = null
                        }
                    }
                    else -> Unit
                }
                // Always forward non-output events to the bus so external
                // observers (UI, session-list updater, etc.) see the same
                // structural events the response-correlator just consumed.
                // Pane output fanout is deliberately non-blocking: terminal
                // rendering and diagnostic collectors must not starve this
                // reader loop and delay command-response parsing.
                if (event is ControlEvent.Output) {
                    // Issue #105 diagnostics. We log BEFORE and AFTER the
                    // emit so a reviewer reading logcat can tell apart:
                    //   * tmux never produced %output for the external
                    //     write (no `tmux-output-received` line appears),
                    //   * the reader saw bytes but the bus emit suspended
                    //     longer than expected (gap between `received`
                    //     and `bus-emit`),
                    //   * downstream (parser + Compose invalidation) is
                    //     slow (the test's emulator-side checks fire long
                    //     after `bus-emit`).
                    // The byte count is enough to correlate with the
                    // external write without leaking the payload itself.
                    Log.i(
                        ISSUE_105_DIAG_TAG,
                        "tmux-output-received pane=${event.paneId} bytes=${event.data.size}",
                    )
                    emitOutput(event)
                    Log.i(
                        ISSUE_105_DIAG_TAG,
                        "tmux-output-bus-emit pane=${event.paneId} bytes=${event.data.size}",
                    )
                } else {
                    eventBus.emit(event)
                }
            }
        } finally {
            val disconnectCause = classifyReaderExit(readerExitSource)
            publishDisconnectEvent(
                disconnectEventFor(
                    cause = disconnectCause,
                    source = readerExitSource,
                    exceptionClass = readerFailureClass,
                    message = readerFailureMessage,
                ),
            )
            TmuxClientDiagnostics.record(
                "tmux_client_reader_exit",
                buildMap {
                    put("session", sessionName)
                    put("source", readerExitSource)
                    put("disconnectCause", disconnectCause.logValue)
                    put("disconnectReason", disconnectReasonFor(disconnectCause).logValue)
                    put("intent", readerExitIntent.logValue)
                    putAll(commonDiagnosticFields())
                    put("closed", closed)
                    put("connected", connected)
                    put("eventBusDroppedEvents", eventBusDroppedEvents.get())
                    readerExitCommandKind?.let { put("commandKind", it) }
                    readerExitTimeoutMode?.let { put("timeoutMode", it.logName) }
                    readerFailureClass?.let { put("cause", it) }
                    readerFailureMessage?.let { put("message", it) }
                },
            )
            // Issue #173: the reader has exited — either because [close]
            // already flipped `closed` (in which case `_disconnected` is
            // already true), or because the underlying SSH socket died
            // (e.g. Android tore the TCP connection down during a
            // backgrounded screenshot pause, the remote sshd was killed,
            // or the user's network dropped). In the latter case
            // `_disconnected.value` is still false; flip it here so
            // observers see the connection drop. The hot [eventBus]
            // SharedFlow cannot signal end-of-stream on its own, so this
            // is the only path to surface the drop without polling.
            _disconnected.value = true
            // Drain any in-flight pending command — the channel is gone.
            inflight?.deferred?.completeExceptionally(
                TmuxClientException("control channel closed mid-command"),
            )
            // Any commands that were queued but never saw a `%begin` also
            // need to fail so their callers unblock.
            val pending = synchronized(responseCorrelationLock) {
                staleResponseBlocksToIgnore = 0
                drainPendingCommands()
            }
            pending.forEach { cmd ->
                cmd.deferred.completeExceptionally(
                    TmuxClientException("control channel closed before response"),
                )
            }
        }
    }

    private fun drainPendingCommands(): List<PendingCommand> {
        val pending = mutableListOf<PendingCommand>()
        while (true) {
            pending += pendingQueue.poll() ?: break
        }
        return pending
    }

    private fun emitOutput(event: ControlEvent.Output) {
        paneOutputPipes[event.paneId]?.send(event)
        if (!eventBus.tryEmit(event)) {
            val dropped = eventBusDroppedEvents.incrementAndGet()
            if (eventBusOverflowDiagnosticEmitted.compareAndSet(false, true)) {
                TmuxClientDiagnostics.record(
                    "tmux_client_eventbus_overflow",
                    commonDiagnosticFields() + mapOf(
                        "session" to sessionName,
                        "pane" to event.paneId,
                        "bytes" to event.data.size,
                        "droppedEvents" to dropped,
                        "capacity" to EVENT_BUFFER,
                    ),
                )
            }
            Log.w(
                ISSUE_105_DIAG_TAG,
                "tmux-output-eventbus-drop pane=${event.paneId} bytes=${event.data.size} " +
                    "droppedEvents=$dropped capacity=$EVENT_BUFFER",
            )
        }
    }

    private fun recordCommandTimeout(
        kind: String,
        timeoutMode: CommandTimeoutMode,
        writeCompleted: Boolean,
    ) {
        TmuxClientDiagnostics.record(
            "tmux_client_command_timeout",
            commonDiagnosticFields() + mapOf(
                "session" to sessionName,
                "commandKind" to kind,
                "timeoutMode" to timeoutMode.logName,
                "timeoutMs" to commandTimeoutMs,
                "writeCompleted" to writeCompleted,
            ),
        )
    }

    private fun classifyReaderExit(source: String): ReaderDisconnectCause =
        when {
            readerExitIntent == ReaderExitIntent.CommandTimeout -> ReaderDisconnectCause.CommandTimeout
            readerExitIntent == ReaderExitIntent.DetachOrReplace -> ReaderDisconnectCause.DetachOrReplace
            closed && readerExitIntent == ReaderExitIntent.LocalClose -> ReaderDisconnectCause.LocalClose
            source == "read_failure" -> ReaderDisconnectCause.ReadFailure
            source == "eof" -> ReaderDisconnectCause.ReadEof
            closed -> ReaderDisconnectCause.LocalClose
            else -> ReaderDisconnectCause.Unknown
        }

    private fun disconnectEventFor(
        cause: ReaderDisconnectCause,
        source: String,
        exceptionClass: String? = null,
        message: String? = null,
    ): TmuxDisconnectEvent =
        TmuxDisconnectEvent(
            reason = disconnectReasonFor(cause),
            source = source,
            intent = readerExitIntent.logValue,
            commandKind = readerExitCommandKind,
            timeoutMode = readerExitTimeoutMode?.logName,
            exceptionClass = exceptionClass,
            message = message,
        )

    private fun disconnectReasonFor(cause: ReaderDisconnectCause): TmuxDisconnectReason =
        when (cause) {
            ReaderDisconnectCause.LocalClose -> TmuxDisconnectReason.ExplicitClose
            ReaderDisconnectCause.DetachOrReplace -> TmuxDisconnectReason.ExplicitDetach
            ReaderDisconnectCause.CommandTimeout -> TmuxDisconnectReason.CommandTimeout
            ReaderDisconnectCause.ReadEof -> TmuxDisconnectReason.ReaderEof
            ReaderDisconnectCause.ReadFailure -> TmuxDisconnectReason.ReaderException
            ReaderDisconnectCause.Unknown -> TmuxDisconnectReason.Unknown
        }

    private fun publishDisconnectEvent(event: TmuxDisconnectEvent) {
        val current = _disconnectEvent.value
        if (current == null || disconnectPriority(event.reason) >= disconnectPriority(current.reason)) {
            _disconnectEvent.value = event
        }
    }

    private fun disconnectPriority(reason: TmuxDisconnectReason): Int =
        when (reason) {
            TmuxDisconnectReason.Unknown -> 0
            TmuxDisconnectReason.ReaderEof -> 1
            TmuxDisconnectReason.ReaderException -> 2
            TmuxDisconnectReason.ExplicitClose -> 3
            TmuxDisconnectReason.ExplicitDetach -> 4
            TmuxDisconnectReason.CommandTimeout -> 5
        }

    private fun commonDiagnosticFields(): Map<String, Any?> =
        mapOf(
            "clientId" to clientId,
            "clientHash" to clientHash,
        )

    /**
     * Write a single command line to [stdin], appending the `\n` tmux
     * needs. Flushes so the bytes hit the wire immediately (sshj uses a
     * buffered output stream under the hood).
     */
    private suspend fun writeLine(stdin: OutputStream, line: String) =
        withContext(Dispatchers.IO) {
            stdin.write(line.toByteArray(Charsets.UTF_8))
            stdin.write('\n'.code)
            stdin.flush()
        }

    private companion object {
        private const val DEFAULT_SESSION_NAME = "pocketshell"
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L
        private const val BEST_EFFORT_LATE_RESPONSE_DRAIN_MS = 1_000L
        private val NEXT_CLIENT_ID = AtomicLong(0L)

        private fun escapeSingleQuoted(input: String): String =
            input.replace("'", "'\\''")

        private fun commandKind(command: String): String =
            command.trim().substringBefore(' ').ifBlank { "unknown" }

        /**
         * Issue #576 (P4): read-only / idempotent control commands whose late
         * reply means "the answer is slow," NOT "the transport is dead." On a
         * timeout these must fail OPEN (drain the late response, throw a
         * recoverable exception to the caller) instead of tearing down the
         * control channel. Defense-in-depth behind the idle-deadline gate: even
         * if one of these did time out on a genuinely slow-but-alive link, it
         * no longer self-inflicts an EOF + reconnect band.
         *
         * The structural / state-mutating remainder (e.g. `new-session`,
         * `attach-session`) stays [CommandTimeoutMode.FatalClose] — a lost
         * reply there genuinely desyncs the control channel, so a close is the
         * conservative response.
         */
        private val FAIL_OPEN_COMMAND_KINDS = setOf(
            "send-keys",
            "capture-pane",
            "display-message",
            "refresh-client",
            "list-panes",
            "list-windows",
            "list-sessions",
            "list-clients",
        )

        private fun timeoutModeForCommand(command: String): CommandTimeoutMode =
            if (commandKind(command) in FAIL_OPEN_COMMAND_KINDS) {
                CommandTimeoutMode.FailOpenDrain
            } else {
                CommandTimeoutMode.FatalClose
            }

        /**
         * Buffer slack in the event bus so a brief subscriber stall
         * doesn't drop events. Sized generously — events are tiny
         * structs, and dropping is much worse than holding a few KB of
         * heap.
         */
        private const val EVENT_BUFFER = 256
        private const val OUTPUT_BACKLOG_EVENTS = 4096

        /**
         * Logcat tag for issue #105 live-update diagnostics. Kept short
         * enough to satisfy `Log.isLoggable`'s 23-char limit on older
         * Android versions while remaining greppable.
         *
         * The reviewer of issue #105 reproduces the "tmux pane does not
         * repaint when another client writes to it" symptom by attaching
         * PocketShell to a tmux session and then writing into that
         * session from a separate SSH client. The four diagnostic
         * categories spelled out in the issue
         * (missing tmux output / SSH channel issue / parser issue /
         * Compose invalidation issue) are distinguished by:
         *   * `ssh-read-failed` / `ssh-read-eof` from this class — SSH
         *     channel issues,
         *   * `tmux-output-received` from this class — tmux DID produce
         *     bytes for the external write,
         *   * `tmux-output-bus-emit` from this class — the bytes reached
         *     the shared event bus (so any subscriber on `outputFor`
         *     should see them),
         *   * the absence of corresponding emulator visible-text /
         *     viewport changes in the issue #105 test harness — parser
         *     or Compose/View invalidation issue downstream of this
         *     class.
         */
        private const val ISSUE_105_DIAG_TAG = "issue105-diag"
        private const val ISSUE_244_DIAG_TAG = "issue244-diag"

        /** LF (`0x0A`) — the line delimiter in the control-mode stream. */
        private const val LF_BYTE: Byte = 0x0A

        /** CR (`0x0D`) — trimmed off a trailing CRLF when tmux emits one. */
        private const val CR_BYTE: Byte = 0x0D

        /** Initial per-line accumulation buffer. Grows as needed. */
        private const val DEFAULT_LINE_BUFFER_BYTES = 4096

        /** stdout read granularity for the control-mode reader. */
        private const val READ_CHUNK_BYTES = 8192

        /**
         * Snapshot the accumulated line bytes, trimming a single trailing
         * CR so a CRLF terminator normalizes to the same line a bare LF
         * would (issue #435 byte-oriented reader; replaces the implicit
         * `BufferedReader.readLine()` CRLF handling).
         */
        private fun takeLine(buffer: java.io.ByteArrayOutputStream): ByteArray {
            val bytes = buffer.toByteArray()
            return if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR_BYTE) {
                bytes.copyOf(bytes.size - 1)
            } else {
                bytes
            }
        }
    }

    private enum class CommandTimeoutMode {
        FatalClose,
        BestEffortDrain,
        FailOpenDrain,
        ;

        val logName: String
            get() = when (this) {
                FatalClose -> "fatal"
                BestEffortDrain -> "best-effort"
                FailOpenDrain -> "fail-open"
            }
    }

    private enum class ReaderExitIntent(
        val logValue: String,
        val priority: Int,
    ) {
        Unknown("unknown", 0),
        LocalClose("local_close", 1),
        DetachOrReplace("detach_or_replace", 2),
        CommandTimeout("command_timeout", 3),
    }

    private enum class ReaderDisconnectCause(val logValue: String) {
        LocalClose("local_close"),
        DetachOrReplace("detach_or_replace"),
        CommandTimeout("command_timeout"),
        ReadEof("read_eof"),
        ReadFailure("read_failure"),
        Unknown("unknown"),
    }

    private class PaneOutputPipe private constructor(
        val flow: MutableSharedFlow<ControlEvent.Output>,
        private val channel: Channel<ControlEvent.Output>,
        private val job: Job,
        private val onOverflow: (TmuxOutputBacklogOverflow) -> Unit,
        private val diagnosticFields: () -> Map<String, Any?>,
        private val droppedEvents: AtomicInteger = AtomicInteger(0),
        private val overflowDiagnosticEmitted: AtomicBoolean = AtomicBoolean(false),
    ) {
        fun send(event: ControlEvent.Output) {
            val result = channel.trySend(event)
            if (result.isFailure) {
                val dropped = droppedEvents.incrementAndGet()
                onOverflow(TmuxOutputBacklogOverflow(event.paneId, dropped))
                if (overflowDiagnosticEmitted.compareAndSet(false, true)) {
                    TmuxClientDiagnostics.record(
                        "tmux_client_pane_output_backlog_overflow",
                        diagnosticFields() + mapOf(
                            "pane" to event.paneId,
                            "bytes" to event.data.size,
                            "droppedEvents" to dropped,
                            "capacity" to OUTPUT_BACKLOG_EVENTS,
                        ),
                    )
                }
                Log.w(
                    ISSUE_105_DIAG_TAG,
                    "tmux-output-backlog-overflow pane=${event.paneId} " +
                        "bytes=${event.data.size} droppedEvents=$dropped " +
                        "capacity=$OUTPUT_BACKLOG_EVENTS",
                    result.exceptionOrNull(),
                )
            }
        }

        fun close() {
            channel.close()
            job.cancel()
        }

        companion object {
            fun create(
                scope: CoroutineScope,
                onOverflow: (TmuxOutputBacklogOverflow) -> Unit,
                diagnosticFields: () -> Map<String, Any?>,
            ): PaneOutputPipe {
                val flow = MutableSharedFlow<ControlEvent.Output>(
                    replay = 0,
                    extraBufferCapacity = EVENT_BUFFER,
                )
                val channel = Channel<ControlEvent.Output>(OUTPUT_BACKLOG_EVENTS)
                val job = scope.launch {
                    for (event in channel) {
                        flow.emit(event)
                    }
                }
                return PaneOutputPipe(
                    flow = flow,
                    channel = channel,
                    job = job,
                    onOverflow = onOverflow,
                    diagnosticFields = diagnosticFields,
                )
            }
        }
    }

    /**
     * Slot held while a single [sendCommand] call is awaiting its
     * response. The reader populates [output] line-by-line as payload
     * between `%begin` and `%end` / `%error` arrives, then completes
     * [deferred] when the closing event lands.
     *
     * [commandNumber] is filled in by the reader when the matching
     * `%begin` arrives — it's the tmux-assigned identifier we surface on
     * the eventual [CommandResponse.number].
     */
    private class PendingCommand(
        val deferred: CompletableDeferred<CommandResponse>,
    ) {
        @Volatile
        var commandNumber: Long = -1L
        val output: MutableList<String> = mutableListOf()
    }
}
