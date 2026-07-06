package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.ssh.ExecResult
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
     * stream.
     *
     * Issue #1224: this bus carries STRUCTURAL control events only
     * (`%window-close`, `%session-changed`, `%layout-change`, `%window-add`,
     * `%exit`, …). Per-pane `%output` is deliberately NOT multiplexed here —
     * subscribe to the dedicated [outputFor] demux for pane bytes. Copying every
     * high-rate `%output` onto this shared bus used to let an output burst fill
     * the buffer and silently drop a burst-tail structural event.
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
     * Issue #640 / #1297: capture a pane's content AND its cursor
     * (`#{cursor_x},#{cursor_y}`, see #259) so the seed path can repaint a
     * freshly-attached — or stale/black — pane correctly.
     *
     * Issue #1297 (heal-capture SPOF): the real client runs this on a DEDICATED
     * `exec` channel (the `SshSession.exec` lane the `tmux has-session` preflight
     * already uses), NOT the shared per-host `-CC` control-mode `sendMutex`. A
     * busy Claude agent saturating the `-CC` channel used to wedge every
     * recovery-net capture (stale-render heal, reveal/switch reseed, overflow
     * reseed) behind that one mutex, so the 2.5s seed ceiling fired exactly when
     * a pane was black (#470/#835). The exec lane reads its own channel's stdout
     * independently of the `-CC` reader, so a heal capture succeeds while a burst
     * saturates the control channel. Both `capture-pane` and `display-message`
     * run in one exec, split on a sentinel line; a missing/failed cursor degrades
     * to a null [CaptureWithCursor.cursorReply] rather than failing the capture.
     *
     * The default implementation falls back to two separate best-effort
     * `-CC` commands for [TmuxClient] doubles that do not model an exec lane.
     */
    public suspend fun captureWithCursor(
        paneId: String,
        scrollbackLines: Int,
        // Issue #926: optional SHORT ceiling for the attach/switch/reattach seed
        // capture. When non-null, the implementation bounds the capture+cursor
        // round-trip by THIS value instead of the full per-command
        // `commandTimeoutMs` (10 s), so a wedged-but-alive control channel makes
        // the seed FALL THROUGH to the blank watchdog in ~2-3 s rather than
        // parking the caller for the full 10 s timeout. `null` preserves the
        // original full-ceiling behaviour for non-seed callers. The default
        // best-effort implementation has no per-call timeout to bound, so it
        // ignores this hint (the real client honours it).
        timeoutMs: Long? = null,
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
     * Issue #1205: discard every `%output` frame currently queued in [paneId]'s
     * per-pane delivery backlog and return the number of frames dropped.
     *
     * After a backlog overflow (a sustained high-output burst outran the local
     * renderer and [outputBacklogOverflows] fired), the buffered burst frames
     * still queued in the pane's channel are STALE deltas: the recovery path
     * repaints the pane from an authoritative `capture-pane` snapshot, and if
     * those queued deltas were allowed to replay AFTER the snapshot they would
     * double-apply on top of the already-current grid and corrupt it. Draining
     * the backlog before the reseed is what makes "one reseed, not a dead pane"
     * correct. No-op (returns 0) when the pane has no registered pipe.
     */
    public fun drainPaneOutputBacklog(paneId: String): Int

    /**
     * Issue #1297: freeze `%output` delivery for [paneId] so frames landing while
     * the sole collector is momentarily detached are HELD in the bounded backlog
     * channel instead of being emitted into a zero-subscriber `replay = 0`
     * SharedFlow (where they vanish).
     *
     * The overflow-reseed producer swap
     * ([TmuxSessionViewModel.launchPaneOverflowReseed]) tears the sole pane
     * collector down and reattaches a FRESH one; every `%output` emitted in that
     * teardown/reattach gap used to be lost, leaving recovery to depend SOLELY on
     * the step-4 `capture-pane` (the same SPOF this issue closes). With the pause
     * held across the swap, the held frames are replayed to the fresh collector
     * on [resumeOutputDelivery] when the capture fails, and dropped
     * ([drainPaneOutputBacklog]) when the authoritative capture succeeds.
     *
     * No-op when the pane has no registered pipe. The default implementation is a
     * no-op for [TmuxClient] doubles that do not model per-pane delivery.
     */
    public fun pauseOutputDelivery(paneId: String) {}

    /**
     * Issue #1297: thaw `%output` delivery for [paneId] previously frozen by
     * [pauseOutputDelivery]. Any frames held in the bounded backlog channel drain
     * to the (re)attached collector in arrival order. No-op when the pane has no
     * registered pipe or delivery was never paused.
     */
    public fun resumeOutputDelivery(paneId: String) {}

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
     * Issue #927: milliseconds since the control-mode reader last parsed ANY
     * control event (`%begin` / `%end` / `%error` / `%output`) — the
     * "busy ≠ dead" discriminator the [probeLiveness] tolerance leans on.
     *
     * A genuinely dead / half-open link delivers ZERO bytes, so this value grows
     * without bound. A flaky-but-ALIVE link that is mid-`%output`-burst keeps
     * advancing it on every parsed block even while a `refresh-client` reply is
     * parked behind the control-mode FIFO. [probeLiveness] therefore treats
     * recent reader activity as positive liveness evidence so a probe parked
     * behind a legitimate output burst is NOT mis-counted as a dead-channel miss.
     *
     * The default returns [Long.MAX_VALUE] (no activity known) for doubles that
     * do not model a reader; the production client tracks the real timestamp.
     */
    public fun millisSinceLastReaderActivity(): Long = Long.MAX_VALUE

    /**
     * EPIC #792 Slice D (#822/V7a): a lightweight, NON-fatal liveness ping for
     * the proactive mid-session drop probe (`LivenessProbe`).
     *
     * Sends a single best-effort control command and reports whether the channel
     * answered. Crucially this uses the BEST-EFFORT, drain-on-timeout path
     * ([sendBestEffortCommand]) — a slow / momentarily-busy but HEALTHY channel
     * is NOT torn down by this probe (it must never self-inflict the very drop it
     * is trying to detect). The caller (`LivenessProbe`) wraps this in its own
     * generous per-probe timeout and an N-consecutive-failure criterion, so a
     * single slow reply never declares a drop.
     *
     * Issue #927 — busy-vs-dead distinction: BEFORE counting a missed
     * `refresh-client` round-trip as a failure, the channel is checked for recent
     * reader activity ([millisSinceLastReaderActivity] within
     * [readerActivityLivenessWindowMs]). On a flaky-but-alive link a heavy
     * `%output` burst can park the `refresh-client` reply behind the control-mode
     * FIFO for longer than the probe's budget — but the reader is still parsing
     * `%output` blocks, which is unambiguous proof the channel is alive. So if the
     * reader showed activity within the window, the probe reports ALIVE even when
     * the `refresh-client` reply itself did not arrive. A genuinely dead half-open
     * link parses NOTHING, so this guard never masks a real drop.
     *
     * Returns `true` if the command round-tripped OR the reader showed recent
     * activity (the channel is alive), `false` if it timed out best-effort with no
     * reader activity, errored, or the client is closed/disconnected. Never throws
     * for a transport failure — a dead channel is a `false`, not an exception, so
     * the probe loop treats it as one failure tick.
     *
     * The default implementation sends `refresh-client` (a no-op idempotent
     * control command with a small reply, already on tmux's best-effort
     * allow-list) and returns whether a non-error response came back, falling back
     * to the recent-reader-activity evidence when the reply did not arrive.
     *
     * Issue #1193 — [requireAnsweredRoundTrip] hardens the probe for a
     * NETWORK-TRANSITION call site (a WiFi↔cellular restore / handoff). On a
     * transition the recent reader bytes crossed the OLD socket's 4-tuple, so they
     * do NOT prove the NEW default network's path is alive — the #927
     * reader-activity fallback would let a silently-dead post-handoff socket pass as
     * "alive" (the maintainer's cellular spurious drop, where a fresh keepalive
     * timestamp masked a dead socket until the reader threw ~157ms later). When
     * `true`, the probe requires an actual ANSWERED round-trip over the (possibly
     * new) path and ignores the reader-activity fallback, so a dead cellular socket
     * is detected UP-FRONT before the restore arm rides through onto it. The
     * steady-state periodic drop probe keeps the default `false` (the #927
     * busy-vs-dead tolerance a live-but-slow link needs).
     */
    public suspend fun probeLiveness(requireAnsweredRoundTrip: Boolean = false): Boolean =
        if (disconnected.value) {
            false
        } else {
            val answered = runCatching { sendBestEffortCommand("refresh-client") }
                .map { !it.isError }
                .getOrDefault(false)
            if (requireAnsweredRoundTrip) {
                // Issue #1193: a network-transition probe demands proof the NEW path
                // round-trips. Recent reader bytes crossed the OLD socket, so they are
                // NOT evidence of the new path's liveness — require an answered
                // round-trip only.
                answered
            } else {
                // Busy ≠ dead (#927): a parked/failed reply over a channel that is
                // STILL delivering `%output` (or any control block) is alive, not a
                // miss. A dead half-open link parses nothing, so this stays false.
                answered || millisSinceLastReaderActivity() <= readerActivityLivenessWindowMs
            }
        }

    /**
     * Issue #927: how recent reader activity must be to count as positive
     * liveness evidence inside [probeLiveness]. A `%output` block parsed within
     * this window means the channel is demonstrably alive even if a
     * `refresh-client` reply is parked behind the FIFO. Kept short relative to the
     * dead-peer detection budget so it only absorbs an active burst, never a
     * genuinely silent half-open link.
     */
    public val readerActivityLivenessWindowMs: Long
        get() = DEFAULT_READER_ACTIVITY_LIVENESS_WINDOW_MS

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

    public companion object {
        /**
         * Issue #927: default window for [readerActivityLivenessWindowMs]. A
         * reader event parsed within ~3s of the probe is treated as positive
         * liveness evidence even when the `refresh-client` reply is parked behind
         * a `%output` burst. Short relative to the ~52s dead-peer budget so it
         * only ever absorbs an actively-streaming-but-slow channel, never a
         * silent half-open link (which parses zero bytes).
         */
        public const val DEFAULT_READER_ACTIVITY_LIVENESS_WINDOW_MS: Long = 3_000L
    }
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

/**
 * Issue #998: thrown by [TmuxClient.connect] when a reattach to an
 * expected-existing session finds that the remote tmux *server* itself is
 * gone — the host rebooted, OOM-killed tmux, or someone ran `kill-server`.
 * The `tmux has-session` / `list-sessions` preflight reports
 * `no server running on <socket>` (exit ≠ 0) in this case.
 *
 * This is distinct from [TmuxSessionNotFoundException] (one session ended but
 * the server is still up): a dead server means EVERY session vanished. It must
 * NOT be resurrected via `new-session -A`, which on a dead server would boot a
 * brand-new empty server+session and silently strand the user in a blank
 * "Connected" shell with their work gone. Callers catch this to surface
 * "the tmux server restarted — all sessions ended" and drop the user to the
 * host/session list instead of the silent resurrection.
 */
public class TmuxServerDeadException(
    message: String = "tmux server is no longer running",
) : RuntimeException(message)

/**
 * Issue #998: the stderr signature tmux prints to both `has-session` and
 * `list-sessions` when the control socket has no server behind it (the host
 * rebooted / OOM / `kill-server`). Matched case-insensitively so a future tmux
 * wording tweak around the same phrase still classifies as server-death.
 */
internal const val TMUX_NO_SERVER_RUNNING_SIGNATURE: String = "no server running"

/**
 * Issue #998: returns true when [stderr] carries the tmux "server is gone"
 * signature. Centralised so the reattach preflight, the reader-loop classifier,
 * and the host session-list gateway all agree on what "server-death" means.
 */
internal fun isTmuxServerDeadStderr(stderr: String?): Boolean =
    stderr?.contains(TMUX_NO_SERVER_RUNNING_SIGNATURE, ignoreCase = true) == true

/**
 * Issue #998: true when a `%exit` control event's reason announces the SERVER
 * shutting down (`%exit server exited`), as opposed to a plain client `%exit`.
 * tmux emits the bare `%exit` for an ordinary client detach and
 * `%exit server exited` when the server itself is going away. We only treat the
 * latter as server-death.
 */
internal fun isTmuxServerExitReason(reason: String?): Boolean =
    reason?.contains("server exited", ignoreCase = true) == true

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
    // Issue #998: the remote tmux SERVER announced shutdown in-band
    // (`%exit server exited`) before the channel EOFed — host reboot / OOM /
    // `kill-server`. The reconnect path treats this as server-death (drop to
    // the list) rather than a transport blip (silent `new-session -A`).
    ServerExited("server_exited"),
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
         * Issue #886: the default absolute wall-clock ceiling for a single
         * control-mode command, applied ALONGSIDE the per-command idle gate.
         * Deliberately far larger than `DEFAULT_COMMAND_TIMEOUT_MS` (10 s) so it
         * never preempts a healthy busy-but-alive redraw backlog (the #576/#794
         * regression guard) and only catches a command whose reply genuinely
         * never lands while the channel keeps streaming `%output` (the #886
         * infinite-"Attaching…" / #470/#835 enumeration-stall class).
         */
        const val DEFAULT_ABSOLUTE_COMMAND_CEILING_MS: Long = 30_000L

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
         * ## Issue #886: the absolute wall-clock ceiling (ADDITIVE)
         * The idle gate above is, by design, defeated by a continuously busy
         * channel: an agent/Codex TUI that streams `%output` forever keeps
         * [readerActivityNanos] advancing, so the idle deadline re-arms on every
         * tick and NEVER fires even when THIS command's own reply is stuck
         * behind the redraw backlog and will never land (the attach `capture-pane`
         * seed on a streaming agent pane — the infinite "Attaching…" of #886, and
         * the #470/#835 enumeration stall). The idle gate alone therefore cannot
         * bound a command on a busy channel.
         *
         * [absoluteCeilingMs] adds a SECOND, independent watchdog that fires after
         * a fixed wall-clock window measured from the command's own dispatch,
         * regardless of reader activity. It is deliberately generous and DISTINCT
         * from the (much shorter) idle [timeoutMs]: a healthy command answers in
         * milliseconds, a busy-but-alive backlog answers within the ceiling, and
         * only a genuinely-wedged command (reply never lands while the channel
         * stays busy) reaches the ceiling and is cancelled. This preserves the
         * #576/#794 "busy link tears down a healthy session" protection — the
         * ceiling is far longer than any legitimate redraw — while guaranteeing a
         * command can never wait forever. Whichever watchdog (idle OR ceiling)
         * trips first cancels the body and returns `null`.
         *
         * @param readerActivityNanos supplies the latest reader-progress
         *   timestamp (`System.nanoTime()` units). Read on each poll tick.
         * @param absoluteCeilingMs the absolute wall-clock ceiling from command
         *   dispatch. `null` disables it (pure idle-gate behaviour, for tests that
         *   characterise the idle gate in isolation).
         */
        fun realTime(
            readerActivityNanos: () -> Long,
            absoluteCeilingMs: Long? = DEFAULT_ABSOLUTE_COMMAND_CEILING_MS,
        ): CommandTimeoutGate =
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
                        // #886: the absolute wall-clock ceiling watchdog. Fires
                        // after a fixed window from dispatch even if the idle gate
                        // keeps re-arming on a continuously-streaming channel. Runs
                        // ALONGSIDE the idle watchdog — neither replaces the other.
                        val ceilingWatchdog = absoluteCeilingMs?.let { ceilingMs ->
                            async { delay(ceilingMs) }
                        }
                        try {
                            select {
                                bodyDeferred.onAwait { result ->
                                    watchdog.cancel()
                                    ceilingWatchdog?.cancel()
                                    result
                                }
                                watchdog.onAwait {
                                    bodyDeferred.cancel()
                                    ceilingWatchdog?.cancel()
                                    null
                                }
                                ceilingWatchdog?.onAwait {
                                    bodyDeferred.cancel()
                                    watchdog.cancel()
                                    null
                                }
                            }
                        } finally {
                            watchdog.cancel()
                            ceilingWatchdog?.cancel()
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
    // Issue #998: this connect is a *reattach to an expected-existing* session
    // (a reconnect / lifecycle-reattach / network-reconnect), so before issuing
    // the `new-session -A` spawn we probe whether the tmux SERVER is still
    // running. If it is dead (`no server running on <socket>`), `new-session -A`
    // would boot a brand-new empty server and silently resurrect a blank
    // session — data-loss-looking. We throw [TmuxServerDeadException] instead so
    // the caller drops to the host/session list. The default `false` keeps the
    // explicit user "new session" intent (and every test that never preflights)
    // unchanged: a brand-new session legitimately wants a fresh server.
    private val probeServerLiveness: Boolean = false,
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
    // Issue #1212: grace window a registered pane pipe waits for its first
    // collector before abandoning its (bounded) pre-registration replay list
    // and proceeding to drain the live channel. Prevents a
    // registered-but-never-collected pane from parking its replay job — and
    // pinning up to [PRE_REGISTRATION_MAX_BYTES] of replay — for the life of
    // the connection. Generous by default so the normal path (the ViewModel
    // collects within milliseconds) is unaffected; tests inject a short value.
    private val firstSubscriberReplayGraceMs: Long = DEFAULT_FIRST_SUBSCRIBER_REPLAY_GRACE_MS,
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
        commandTimeoutGate
            ?: CommandTimeoutGate.realTime(readerActivityNanos = { lastReaderActivityNanos })
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

    // Shared flow of STRUCTURAL events. extraBufferCapacity absorbs short
    // bursts without blocking the reader.
    //
    // Issue #1224: pane `%output` is NO LONGER emitted onto this bus. It used to
    // be (best-effort, so the ViewModel could log first-frame-per-pane), which
    // meant a dense output burst filled the 256-slot buffer and could silently
    // drop a burst-tail structural event (`%window-close` / `%session-changed`)
    // — leaving a stale window node or wrong active session in the UI. Output
    // now rides ONLY the per-pane [outputFor] pipes (the ViewModel's
    // first-visible-output log taps that stream); this bus carries structural
    // events ONLY, so an output burst can never crowd them out.
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

    // Issue #1204: bounded pre-registration replay buffers, keyed by pane id.
    //
    // Pane pipes are created lazily by [outputFor]. Any `%output` tmux emits
    // for a pane BEFORE its pipe is registered (fresh-session prewarm,
    // attach/switch races, window-add materialization) used to be discarded by
    // the null-safe `paneOutputPipes[paneId]?.send(event)` no-op — no counter,
    // no diagnostic, no recovery. On a Claude pane, whose TUI only repaints
    // incrementally, a lost first frame stays black indefinitely. We now hold
    // those frames in a small bounded buffer per pane and replay them, in
    // order, when [outputFor] finally registers the pipe. Overflow/eviction is
    // bounded and fires the `tmux_client_preregistration_output_drop`
    // diagnostic so the loss is never invisible again.
    //
    // [preRegistrationBuffers] is guarded by [preRegistrationLock]. The lock is
    // taken ONLY on the cold paths (an `%output` for an as-yet-unregistered
    // pane, and pane registration) — the hot path (pipe already registered)
    // stays lock-free.
    //
    // Issue #1212 lifecycle hardening:
    //  * The map is access-order (LRU): every `%output` for a pane moves it to
    //    the most-recent end, so the eldest entry is the least-recently-active
    //    pane — the right global-eviction victim.
    //  * [totalPreRegistrationBytes] tracks the aggregate retained bytes across
    //    ALL panes so a flood of never-registered background panes can't
    //    accumulate 256 KB × pane-count for the connection's lifetime. Bounded
    //    by BOTH [PRE_REGISTRATION_TOTAL_MAX_BYTES] and
    //    [PRE_REGISTRATION_MAX_PANES] (evict eldest whole buffer on overflow).
    //  * [windowPanes] maps a window id to the pane ids last seen in its
    //    `%layout-change` layout, so when a `%window-close` arrives the dead
    //    panes' pre-registration buffers are released instead of lingering.
    private val preRegistrationLock = Any()
    private val preRegistrationBuffers = LinkedHashMap<String, PreRegistrationOutputBuffer>(
        16,
        0.75f,
        /* accessOrder = */ true,
    )
    private var totalPreRegistrationBytes = 0L
    private val windowPanes = HashMap<String, MutableSet<String>>()
    private val preRegistrationDroppedEvents = AtomicInteger(0)

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

    // Issue #998: latched when the reader parses a `%exit server exited` (the
    // tmux SERVER announcing shutdown) before the channel EOFs. The reader-exit
    // classifier reads this so a server-death drop is reported as
    // [ReaderDisconnectCause.ServerExited] — categorically distinct from an
    // ordinary [ReaderDisconnectCause.ReadEof] transport blip. An ordinary
    // `%exit` with no "server exited" reason (e.g. a plain client exit) does NOT
    // set this.
    @Volatile
    private var serverExitedInBand: Boolean = false

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
        // Issue #666 (attach-only cold-restore) + Issue #998 (reattach
        // server-death). Run the `has-session` preflight whenever we are
        // reattaching to a session we EXPECT to already exist — either an
        // attach-only cold restore (`!createIfMissing`) or a reconnect/lifecycle
        // reattach (`probeServerLiveness`). We do NOT run it for the explicit
        // user "new session" intent (`createIfMissing && !probeServerLiveness`),
        // which legitimately wants a fresh server if none is running.
        if (!createIfMissing || probeServerLiveness) {
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
                // Issue #998: a dead SERVER (`no server running on <socket>`) is
                // categorically different from one gone SESSION (`can't find
                // session`). On a dead server EVERY session vanished, so a
                // `new-session -A` reattach would silently boot a fresh empty
                // server — the resurrection bug. Surface it as server-death so
                // the caller drops to the list and never recreates. We classify
                // server-death FIRST because it dominates: even on the
                // attach-only restore path a dead server is server-death, not a
                // single-session-ended.
                if (isTmuxServerDeadStderr(probe.stderr)) {
                    Log.i(
                        ISSUE_105_DIAG_TAG,
                        "tmux-server-dead session=$resolvedSessionName exit=${probe.exitCode} " +
                            "stderr=${probe.stderr.trim()}",
                    )
                    throw TmuxServerDeadException()
                }
                // Server alive but the TARGET session is gone. Issue #666 REOPEN
                // (2026-07-06): a session that no longer exists at reattach time
                // ENDED — a reattach must NEVER recreate it. Previously ONLY the
                // attach-only cold-restore path (`!createIfMissing`) refused to
                // recreate here; the reattach path (`createIfMissing &&
                // probeServerLiveness`: LifecycleReattach / AutoReconnect /
                // Reconnect / NetworkReconnect) FELL THROUGH to `new-session -A`
                // (attach-OR-create) and silently resurrected the killed session —
                // the exact dogfood bug ("I removed it on the computer, but the app
                // created it again"). We hard-cut that branch (D22): whenever the
                // preflight ran (`!createIfMissing || probeServerLiveness`) and the
                // specific session is gone on a LIVE server, throw
                // [TmuxSessionNotFoundException] so the caller drops to the list —
                // identically for cold-restore AND every reattach. The only path
                // that legitimately create-if-missing is the explicit user "new
                // session" intent (`createIfMissing && !probeServerLiveness`), which
                // never enters this preflight at all.
                Log.i(
                    ISSUE_105_DIAG_TAG,
                    "tmux-has-session-gone session=$resolvedSessionName exit=${probe.exitCode} " +
                        "createIfMissing=$createIfMissing probeServerLiveness=$probeServerLiveness " +
                        "— refusing to recreate, dropping to list",
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
                sh.writeStdin(command.toByteArray(Charsets.UTF_8))
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
     * Issue #927: real "busy ≠ dead" discriminator for the liveness probe.
     * [lastReaderActivityNanos] advances on EVERY parsed control block
     * (`%begin`/`%end`/`%error`/`%output`); a dead half-open link parses nothing
     * so this grows without bound, while a flaky-but-alive link mid-`%output`-burst
     * keeps it fresh. [probeLiveness] uses this so a `refresh-client` reply parked
     * behind a legitimate output burst is not mis-counted as a dead-channel miss.
     */
    override fun millisSinceLastReaderActivity(): Long {
        val deltaNanos = System.nanoTime() - lastReaderActivityNanos
        return if (deltaNanos <= 0L) 0L else deltaNanos / 1_000_000L
    }

    /**
     * Issue #1297: capture a pane + its cursor on a DEDICATED `exec` channel, NOT
     * the shared per-host `-CC` control-mode [sendMutex].
     *
     * The heal-capture SPOF (audit finding 4, #1208): every recovery net —
     * stale-render heal, reveal/switch reseed, overflow reseed — funnelled every
     * `capture-pane` through the ONE `sendMutex`. A busy Claude agent saturating
     * the `-CC` channel wedged that mutex, so a heal capture timed out at the
     * 2.5s ceiling exactly when a pane was black (#470/#835). This runs
     * `capture-pane` + `display-message` in a SINGLE [SshSession.exec] — the same
     * independent-channel lane the `tmux has-session` preflight already uses
     * (#666) — whose stdout is read OUTSIDE the transport dispatcher (see
     * `RealSshSession.exec` phase 2), so a heal capture completes while a burst
     * saturates the `-CC` reader. The two tmux sub-commands are joined with a
     * sentinel line so the single stdout splits cleanly into cursor + capture; a
     * missing/failed cursor degrades to a null [CaptureWithCursor.cursorReply].
     *
     * Failure signalling (§4 req 5): a wedged/half-open transport surfaces as a
     * thrown [TmuxClientException] (timeout) — distinct from a capture that
     * returned on a live transport but was empty (a non-error [CommandResponse]
     * with an empty [CommandResponse.output]), so the caller can distinguish
     * "timed out → stay hot" from "empty → never-seeded".
     */
    override suspend fun captureWithCursor(
        paneId: String,
        scrollbackLines: Int,
        timeoutMs: Long?,
    ): CaptureWithCursor {
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        // Issue #926/#1297: bound the heal/seed capture by the caller's short
        // ceiling (≈2.5s) instead of the full per-command `commandTimeoutMs`
        // (10s). Clamp so a caller can only SHORTEN, never lengthen, the bound; a
        // null (non-seed) caller keeps the full ceiling.
        val effectiveTimeoutMs = timeoutMs?.coerceIn(1L, commandTimeoutMs) ?: commandTimeoutMs
        val quotedPane = "'${escapeSingleQuoted(paneId)}'"
        // One exec, three shell commands: cursor FIRST, then a sentinel line,
        // then the (multi-line) capture LAST. Splitting on the FIRST sentinel
        // occurrence means capture content that happens to contain the sentinel
        // token cannot corrupt the split (it lands after the split point).
        val command = buildString {
            append("tmux display-message -p -t ")
            append(quotedPane)
            append(" '#{cursor_x},#{cursor_y}'; printf '%s\\n' '")
            append(HEAL_CAPTURE_SPLIT_MARKER)
            append("'; tmux capture-pane -p -e -S -")
            append(scrollbackLines)
            append(" -t ")
            append(quotedPane)
        }
        val execResult =
            try {
                // The exec lane is independent of the busy `-CC` channel, so this
                // returns fast under a burst. The ceiling is the safety bound for
                // a genuinely wedged/half-open transport (both channels dead);
                // cancelling the exec closes its own channel (RealSshSession.exec
                // cancellation handler), never the `-CC` shell.
                withTimeoutOrNull(effectiveTimeoutMs) {
                    session.exec(command)
                }
            } catch (t: Throwable) {
                throw TmuxClientException(
                    "tmux heal capture exec failed for pane $paneId: ${t.message}",
                    t,
                )
            }
        if (execResult == null) {
            Log.w(
                ISSUE_244_DIAG_TAG,
                "tmux captureWithCursor exec timed out >${effectiveTimeoutMs}ms " +
                    "(heal lane); surfacing a best-effort failure. paneId=$paneId",
            )
            throw TmuxClientException(
                "tmux capture-pane (exec heal lane) timed out after ${effectiveTimeoutMs}ms",
            )
        }
        return parseHealCaptureResult(execResult)
    }

    /**
     * Issue #1297: split a heal-capture [ExecResult] into the raw cursor reply
     * and the `capture-pane` lines around the [HEAL_CAPTURE_SPLIT_MARKER]
     * sentinel line. A non-zero exit (pane/session gone) surfaces as an error
     * [CommandResponse] carrying the stderr; a live-but-blank pane surfaces as a
     * non-error response so the caller distinguishes error from empty.
     */
    private fun parseHealCaptureResult(result: ExecResult): CaptureWithCursor {
        val stdout = result.stdout
        val markerIdx = stdout.indexOf(HEAL_CAPTURE_SPLIT_MARKER)
        val cursorRaw: String
        val captureRaw: String
        if (markerIdx >= 0) {
            cursorRaw = stdout.substring(0, markerIdx)
            // Drop the newline that terminates the sentinel line so the capture
            // starts on its own first line.
            captureRaw = stdout.substring(markerIdx + HEAL_CAPTURE_SPLIT_MARKER.length)
                .removePrefix("\n")
        } else {
            // Sentinel missing (unexpected shell state): no reliable split. Treat
            // the whole stdout as capture and degrade to a null cursor.
            cursorRaw = ""
            captureRaw = stdout
        }
        val cursorReply = cursorRaw.trim().ifEmpty { null }
        val captureLines = splitCaptureLines(captureRaw)
        val isError = result.exitCode != 0
        val outputLines =
            if (isError && captureLines.isEmpty()) {
                result.stderr.trim().let { if (it.isEmpty()) emptyList() else it.split("\n") }
            } else {
                captureLines
            }
        return CaptureWithCursor(
            capture = CommandResponse(number = -1L, output = outputLines, isError = isError),
            cursorReply = cursorReply,
        )
    }

    /**
     * Split the `capture-pane` payload into lines, dropping the single trailing
     * empty line the terminal newline produces so the output matches the
     * per-line list the old `-CC` block drain returned.
     */
    private fun splitCaptureLines(capture: String): List<String> {
        if (capture.isEmpty()) return emptyList()
        val lines = capture.split("\n")
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
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
            val writeCompleted = AtomicBoolean(false)
            val writeJob = clientScope.launch {
                try {
                    writeLine(sh, chained)
                    writeCompleted.set(true)
                    writeResult.complete(Unit)
                } catch (t: Throwable) {
                    writeResult.completeExceptionally(t)
                }
            }

            try {
                val first = commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    writeResult.await()
                    checkpoint.writeCompleted()
                    pendings.first().deferred.await()
                } ?: run {
                    cleanupChainedPending(
                        pendings = pendings,
                        commandWasWritten = writeCompleted.get(),
                    )
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
                            abandonPendingResponse(pending, commandWasWritten = true)
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
                cleanupChainedPending(
                    pendings = pendings,
                    commandWasWritten = writeCompleted.get(),
                )
                writeJob.cancel()
                if (!writeCompleted.get()) {
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
     * accounting for blocks tmux has already begun, plus every not-yet-begun
     * block still owed by a written chained line. The N-block generalisation of
     * the old combined-capture pending cleanup.
     */
    private fun cleanupChainedPending(
        pendings: List<PendingCommand>,
        commandWasWritten: Boolean,
    ) {
        synchronized(responseCorrelationLock) {
            for (pending in pendings) {
                abandonPendingResponse(pending, commandWasWritten = commandWasWritten)
            }
        }
    }

    private fun abandonPendingResponse(
        pending: PendingCommand,
        commandWasWritten: Boolean,
    ) {
        if (pending.commandNumber >= 0L) {
            pending.abandoned = true
            pending.deferred.completeExceptionally(
                TmuxClientException("tmux command abandoned while response block was open"),
            )
            return
        }
        if (pendingQueue.remove(pending) && commandWasWritten) {
            staleResponseBlocksToIgnore += 1
            pending.deferred.completeExceptionally(
                TmuxClientException("tmux command abandoned before response block began"),
            )
        }
    }

    private suspend fun sendCommandInternal(
        cmd: String,
        timeoutMode: CommandTimeoutMode,
    ): CommandResponse {
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        // Issue #470: bound the single-flight ACQUIRE itself, matching
        // sendChainedCommands/captureWithCursor. A wedged current holder used to
        // leave bare sendCommand callers parked forever before any bytes were
        // written, outside the command timeout gate. On acquire timeout we have
        // not touched the wire, so surface a recoverable command failure without
        // closing an otherwise healthy shell.
        val acquired = withTimeoutOrNull(commandTimeoutMs) {
            sendMutex.lock()
            true
        }
        if (acquired != true) {
            val kind = commandKind(cmd)
            Log.w(
                ISSUE_244_DIAG_TAG,
                "tmux-command-acquire-wedged kind=$kind timeoutMs=$commandTimeoutMs",
            )
            throw TmuxClientException(
                "tmux command `$kind` acquire wedged after ${commandTimeoutMs}ms",
            )
        }
        // Single-flight: tmux only honours one outstanding command at a
        // time. The deferred is registered (queued) BEFORE we write the
        // bytes so we can't lose a response that arrives before we've
        // returned from the write.
        return try {
            if (closed) throw TmuxClientException("client is closed")
            if (!connected) throw TmuxClientException("client is not connected")
            val sh = shell ?: throw TmuxClientException("client has no active shell")
            val deferred = CompletableDeferred<CommandResponse>()
            val pendingCmd = PendingCommand(deferred = deferred)
            synchronized(responseCorrelationLock) {
                pendingQueue.offer(pendingCmd)
            }

            val writeResult = CompletableDeferred<Unit>()
            val writeCompleted = AtomicBoolean(false)
            val writeJob = clientScope.launch {
                try {
                    writeLine(sh, cmd)
                    writeCompleted.set(true)
                    writeResult.complete(Unit)
                } catch (t: Throwable) {
                    writeResult.completeExceptionally(t)
                }
            }

            val response = try {
                commandTimeoutGate.run(commandTimeoutMs) { checkpoint ->
                    writeResult.await()
                    checkpoint.writeCompleted()
                    deferred.await()
                }
            } catch (t: Throwable) {
                // Issue #875 (Angle A — liveness-probe FIFO desync): this catch
                // fires when the awaiting coroutine is CANCELLED (the common case:
                // an OUTER `withTimeoutOrNull` around `probeLiveness`/`sendBestEffort`
                // fires while we are parked in `deferred.await()`), as well as on a
                // genuine write error. If the command line was already WRITTEN to the
                // wire (`writeCompleted`) but we abandon it before its `%begin` arrived
                // (`commandNumber < 0L`), tmux WILL still emit a `%begin/%end` block for
                // it. Without accounting for that orphaned block the reader binds it to
                // the NEXT command (`pendingQueue.poll()` at the `%begin` branch),
                // desyncing the response-correlation FIFO by one — and the later
                // mis-correlated command rides its full timeout → `TransportDropped` →
                // a spurious ~1s reconnect. Mirror the fail-open-drain accounting
                // (`staleResponseBlocksToIgnore += 1`) so a cancelled-after-write
                // command leaves the FIFO consistent.
                synchronized(responseCorrelationLock) {
                    abandonPendingResponse(pendingCmd, commandWasWritten = writeCompleted.get())
                }
                writeJob.cancel()
                if (!writeCompleted.get()) {
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
            if (response != null) return response

            val kind = commandKind(cmd)
            val exception = TmuxClientException(
                "tmux command `$kind` timed out after ${commandTimeoutMs}ms",
            )
            val wasWritten = writeCompleted.get()
            val effectiveTimeoutMode = if (wasWritten) {
                timeoutMode
            } else {
                CommandTimeoutMode.FatalClose
            }
            recordCommandTimeout(
                kind = kind,
                timeoutMode = effectiveTimeoutMode,
                writeCompleted = wasWritten,
            )
            if (effectiveTimeoutMode != CommandTimeoutMode.FatalClose) {
                val lateDrainMs = bestEffortLateResponseDrainMs()
                Log.w(
                    ISSUE_244_DIAG_TAG,
                    "tmux-command-${effectiveTimeoutMode.logName}-timeout kind=$kind timeoutMs=$commandTimeoutMs " +
                        "lateDrainMs=$lateDrainMs",
                )
                val lateResponse = withTimeoutOrNull(lateDrainMs) {
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
                    abandonPendingResponse(pendingCmd, commandWasWritten = writeCompleted.get())
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
            // Issue #979: a structural (`FatalClose`) command whose `%begin/%end`
            // reply is delayed past the budget must NOT, by itself, tear down the
            // SSH shell channel when the transport is otherwise ALIVE. The 10s
            // idle / 30s ceiling timeout is NOT proof the transport is dead — on a
            // stable-but-momentarily-busy wifi link a `select-window`/`kill-window`
            // reply can park behind a `%output` burst (the #886 streaming class)
            // while the link is fine. Closing `shell?.close()` here escalates a
            // slow control-mode reply into a hard SSH transport disconnect — the
            // self-inflicted "No live SSH session" drop on stable wifi (#974).
            //
            // Consult the single transport-liveness oracle (#986/#964):
            // `isTransportProvenAliveWithinKeepAliveWindow()` is true iff the
            // always-on keepalive has observed INBOUND server bytes within its
            // ride-through window — i.e. the transport is PROVABLY alive right now.
            // The line for THIS command was written (`writeCompleted` is true on
            // this arm; a never-written command was reclassified to FatalClose
            // above and closed because the bytes never reached tmux), so an
            // unanswered structural command on a proven-alive link is "the answer
            // is slow," not "the transport is dead." Account for the orphaned block
            // (so a late `%begin/%end` drains as stale rather than mis-correlating
            // the FIFO by one) and throw a recoverable exception WITHOUT closing the
            // SSH shell. The transport-death oracle (keepalive / LivenessProbe)
            // stays the sole authority for closing the SSH session — a control-mode
            // command timeout never gets to kill a link the keepalive is still
            // proving alive.
            //
            // When the transport is NOT proven alive (no recent inbound activity —
            // a genuinely dead/half-open link), the timeout IS evidence of death,
            // so the original FatalClose teardown still runs: the genuine-death
            // recovery path is preserved.
            val transportProvenAlive = session.isTransportProvenAliveWithinKeepAliveWindow()
            if (transportProvenAlive) {
                Log.w(
                    ISSUE_244_DIAG_TAG,
                    "tmux-command-fatal-timeout-rode-through kind=$kind timeoutMs=$commandTimeoutMs " +
                        "transportProvenAlive=true (not closing SSH shell — #979)",
                )
                recordFatalTimeoutRodeThrough(kind = kind)
                deferred.completeExceptionally(exception)
                synchronized(responseCorrelationLock) {
                    // The line was written; if tmux still owes us a `%begin/%end`
                    // for it, count that block as stale so the reader discards it
                    // instead of binding it to the next command (FIFO desync).
                    val waitingForBegin = pendingCmd.commandNumber < 0L
                    abandonPendingResponse(pendingCmd, commandWasWritten = writeCompleted.get())
                }
                throw exception
            }
            deferred.completeExceptionally(exception)
            Log.w(
                ISSUE_244_DIAG_TAG,
                "tmux-command-timeout kind=$kind timeoutMs=$commandTimeoutMs transportProvenAlive=false",
            )
            synchronized(responseCorrelationLock) {
                abandonPendingResponse(pendingCmd, commandWasWritten = writeCompleted.get())
            }
            closeInternal(
                ReaderExitIntent.CommandTimeout,
                commandKind = kind,
                timeoutMode = effectiveTimeoutMode,
            )
            throw exception
        } finally {
            sendMutex.unlock()
        }
    }

    override fun outputFor(paneId: String): Flow<ControlEvent.Output> {
        // Fast path: pipe already registered.
        paneOutputPipes[paneId]?.let { return it.flow.asSharedFlow() }
        // Cold path: register the pipe, seeding it with any output buffered
        // before this first registration (issue #1204). Everything happens
        // under [preRegistrationLock] and the pipe is published into
        // [paneOutputPipes] only AFTER it is built with its replay set, so a
        // concurrent [emitOutput] either (a) still sees no pipe and appends to
        // the pre-registration buffer we are about to drain, or (b) sees the
        // published pipe and sends strictly after the replayed frames (the
        // pipe drains its live channel only after replaying — FIFO order is
        // preserved end to end).
        val pipe = synchronized(preRegistrationLock) {
            paneOutputPipes[paneId] ?: run {
                val drainedBuffer = preRegistrationBuffers.remove(paneId)
                if (drainedBuffer != null) {
                    totalPreRegistrationBytes -= drainedBuffer.bufferedBytes
                }
                val buffered = drainedBuffer?.drain().orEmpty()
                val created = PaneOutputPipe.create(
                    scope = clientScope,
                    paneId = paneId,
                    preRegistrationReplay = buffered,
                    firstSubscriberReplayGraceMs = firstSubscriberReplayGraceMs,
                    onOverflow = { overflow ->
                        _outputBacklogOverflows.tryEmit(overflow)
                    },
                    diagnosticFields = {
                        commonDiagnosticFields() + mapOf("session" to sessionName)
                    },
                )
                paneOutputPipes[paneId] = created
                created
            }
        }
        return pipe.flow.asSharedFlow()
    }

    // Issue #1205: empty the pane's queued delivery backlog so a post-overflow
    // `capture-pane` reseed is not clobbered by stale burst frames replaying on
    // top of it. Best-effort: only touches the live channel, never the reader.
    override fun drainPaneOutputBacklog(paneId: String): Int =
        paneOutputPipes[paneId]?.drainBacklog() ?: 0

    override fun pauseOutputDelivery(paneId: String) {
        paneOutputPipes[paneId]?.pauseDelivery()
    }

    override fun resumeOutputDelivery(paneId: String) {
        paneOutputPipes[paneId]?.resumeDelivery()
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
        // Issue #1204/#1212: drop any pre-registration buffers that never got a
        // pipe, and the window→pane bookkeeping that drives pane-death cleanup.
        synchronized(preRegistrationLock) {
            preRegistrationBuffers.clear()
            totalPreRegistrationBytes = 0L
            windowPanes.clear()
        }
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
                        if (buffer.size() >= MAX_LINE_BUFFER_BYTES) {
                            // Issue #1231 T2: an LF-starved stream (a binary MOTD
                            // that lands before the `-CC` handshake, a degraded
                            // non-control-mode byte stream, or a wedged server that
                            // stops emitting LFs) would otherwise grow this buffer
                            // one byte at a time with no ceiling until the process
                            // OOMs — silently, with no diagnostic. Cap the
                            // in-progress line: flush what we have as a (truncated)
                            // line and reset, so framing stays bounded and the
                            // overflow is observable. A truncated non-`%`-line just
                            // parses to null downstream and is skipped.
                            TmuxClientDiagnostics.record(
                                "tmux_client_line_overflow",
                                buildMap {
                                    put("session", sessionName)
                                    put("bytes", buffer.size())
                                    put("maxBytes", MAX_LINE_BUFFER_BYTES)
                                },
                            )
                            emit(takeLine(buffer))
                            buffer.reset()
                        }
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
                        } else if (match?.abandoned == true && match.commandNumber == event.number) {
                            // The waiter timed out or was cancelled after `%begin`.
                            // Drain the open block to its close marker, then let the
                            // next `%begin` bind to the next queued command.
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
                        } else if (match?.abandoned == true && match.commandNumber == event.number) {
                            // See the `%end` branch: this closes an abandoned
                            // response block without poisoning the FIFO.
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
                    is ControlEvent.Exit -> {
                        // Issue #998: the tmux server is shutting down. tmux
                        // emits `%exit server exited` when the SERVER itself dies
                        // (host reboot / OOM / `kill-server`) — distinct from a
                        // plain `%exit` (this client detaching). Latch the
                        // server-death case so the reader-exit classifier reports
                        // it as ServerExited and the reconnect path drops to the
                        // list instead of resurrecting via `new-session -A`.
                        if (isTmuxServerExitReason(event.reason)) {
                            serverExitedInBand = true
                            Log.i(
                                ISSUE_105_DIAG_TAG,
                                "tmux-exit-server-died session=$sessionName reason=${event.reason}",
                            )
                        }
                    }
                    is ControlEvent.LayoutChange -> {
                        // Issue #1212: learn which panes this window owns so a
                        // later `%window-close` can release their
                        // pre-registration buffers. Synchronous + lock-guarded;
                        // never suspends the reader.
                        synchronized(preRegistrationLock) {
                            trackWindowPanesLocked(event.windowId, event.layout)
                        }
                    }
                    is ControlEvent.WindowClose -> {
                        // Issue #1212: the window (and every pane it owned) is
                        // gone — release those panes' pre-registration buffers
                        // so a never-viewed background pane can't retain up to
                        // 256 KB for the connection's lifetime.
                        synchronized(preRegistrationLock) {
                            releasePreRegistrationBuffersForWindowLocked(event.windowId)
                        }
                    }
                    else -> Unit
                }
                // Forward public events without ever suspending the reader.
                // Response framing markers are internal to correlation and are
                // deliberately not exposed to UI collectors.
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
                    emitPublicEvent(event)
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
        // The authoritative per-pane render path. Issue #1224: `%output` is NO
        // LONGER copied onto the structural [eventBus]. That shared 256-slot bus
        // silently dropped a burst-tail `%window-close` / `%session-changed`
        // under output volume (a GC-stalled collector let 256 output copies fill
        // the buffer, so the trailing structural event's `tryEmit` failed and was
        // lost — leaving a stale window node / wrong active session). The ONLY
        // consumer of output on the bus was the first-visible-output milestone
        // log, which is fed instead by the ViewModel's existing per-pane
        // [outputFor] tap. Output now rides ONLY the per-pane pipes here, so an
        // output burst can never crowd out a structural event.
        deliverToPaneStream(event)
    }

    /**
     * Deliver a pane `%output` frame to its per-pane terminal stream.
     *
     * Fast path (issue #1204): if the pane pipe is already registered, hand the
     * frame straight to it — lock-free, unchanged from before.
     *
     * Cold path: no pipe yet. Hold the frame in a bounded per-pane
     * pre-registration buffer so it can be replayed when [outputFor] registers
     * the pipe, instead of being silently dropped. The re-check under
     * [preRegistrationLock] closes the register/append race with [outputFor]:
     * if registration published the pipe in the meantime we send directly (the
     * frame then lands strictly after any replayed frames).
     */
    private fun deliverToPaneStream(event: ControlEvent.Output) {
        paneOutputPipes[event.paneId]?.let {
            it.send(event)
            return
        }
        val registered = synchronized(preRegistrationLock) {
            val existing = paneOutputPipes[event.paneId]
            if (existing != null) {
                existing
            } else {
                // `getOrPut` on the access-order map also moves this pane to the
                // most-recent end, so global eviction never targets the pane we
                // are actively buffering for.
                val buffer = preRegistrationBuffers.getOrPut(event.paneId) {
                    PreRegistrationOutputBuffer(event.paneId)
                }
                val bytesBefore = buffer.bufferedBytes
                buffer.add(event) { droppedForPane, evictedBytes ->
                    recordPreRegistrationDrop(event.paneId, droppedForPane, evictedBytes)
                }
                totalPreRegistrationBytes += buffer.bufferedBytes - bytesBefore
                // Issue #1212: bound the AGGREGATE across all panes so many
                // orphaned/background pane-ids can't each pin a full per-pane
                // buffer for the connection's lifetime.
                enforceGlobalPreRegistrationBoundsLocked(protectPane = event.paneId)
                null
            }
        }
        registered?.send(event)
    }

    /**
     * Issue #1212: keep the total retained pre-registration bytes and the
     * number of distinct retained pane buffers bounded across ALL panes, not
     * just per pane. Evicts the least-recently-active pane's whole buffer
     * (access-order LRU) — never the pane we are currently buffering for
     * ([protectPane]) — until both caps are satisfied.
     *
     * Caller must hold [preRegistrationLock].
     */
    private fun enforceGlobalPreRegistrationBoundsLocked(protectPane: String) {
        while (preRegistrationBuffers.size > PRE_REGISTRATION_MAX_PANES) {
            val victim = firstEvictablePaneLocked(protectPane) ?: break
            evictPreRegistrationBufferLocked(victim, reason = "pane_count")
        }
        while (totalPreRegistrationBytes > PRE_REGISTRATION_TOTAL_MAX_BYTES &&
            preRegistrationBuffers.size > 1
        ) {
            val victim = firstEvictablePaneLocked(protectPane) ?: break
            evictPreRegistrationBufferLocked(victim, reason = "total_bytes")
        }
    }

    /**
     * The least-recently-active pane id that is NOT [protectPane], or null if
     * only the protected pane remains. Iterating the access-order map's keys
     * does not itself reorder, so the first non-protected key is the LRU
     * victim. Caller must hold [preRegistrationLock].
     */
    private fun firstEvictablePaneLocked(protectPane: String): String? {
        for (key in preRegistrationBuffers.keys) {
            if (key != protectPane) return key
        }
        return null
    }

    /** Drop a whole pre-registration buffer and account its bytes. Caller must hold the lock. */
    private fun evictPreRegistrationBufferLocked(paneId: String, reason: String) {
        val removed = preRegistrationBuffers.remove(paneId) ?: return
        totalPreRegistrationBytes -= removed.bufferedBytes
        val total = preRegistrationDroppedEvents.addAndGet(removed.eventCount)
        TmuxClientDiagnostics.record(
            "tmux_client_preregistration_global_evict",
            commonDiagnosticFields() + mapOf(
                "session" to sessionName,
                "pane" to paneId,
                "bytes" to removed.bufferedBytes,
                "reason" to reason,
                "droppedEvents" to removed.eventCount,
                "totalDroppedEvents" to total,
                "retainedPanes" to preRegistrationBuffers.size,
                "retainedBytes" to totalPreRegistrationBytes,
                "maxPanes" to PRE_REGISTRATION_MAX_PANES,
                "maxTotalBytes" to PRE_REGISTRATION_TOTAL_MAX_BYTES,
            ),
        )
        Log.w(
            ISSUE_105_DIAG_TAG,
            "tmux-preregistration-global-evict pane=$paneId reason=$reason " +
                "bytes=${removed.bufferedBytes} droppedEvents=${removed.eventCount} " +
                "retainedPanes=${preRegistrationBuffers.size} retainedBytes=$totalPreRegistrationBytes",
        )
    }

    /**
     * Issue #1212: record which pane ids a window owns, parsed from its
     * `%layout-change` layout string. A pane belongs to exactly one window, so
     * a pane appearing in this window's layout is removed from any other
     * window's set (handles a pane moved between windows). Caller must hold
     * [preRegistrationLock].
     */
    private fun trackWindowPanesLocked(windowId: String, layout: String) {
        val panes = extractLayoutPaneIds(layout)
        if (panes.isEmpty()) return
        for (entry in windowPanes) {
            if (entry.key != windowId) entry.value.removeAll(panes)
        }
        windowPanes.getOrPut(windowId) { HashSet() }.addAll(panes)
    }

    /**
     * Issue #1212: a window closed — release the pre-registration buffers of
     * every pane it owned and forget the window. Caller must hold
     * [preRegistrationLock].
     */
    private fun releasePreRegistrationBuffersForWindowLocked(windowId: String) {
        val panes = windowPanes.remove(windowId) ?: return
        for (paneId in panes) {
            val removed = preRegistrationBuffers.remove(paneId) ?: continue
            totalPreRegistrationBytes -= removed.bufferedBytes
            TmuxClientDiagnostics.record(
                "tmux_client_preregistration_window_close_evict",
                commonDiagnosticFields() + mapOf(
                    "session" to sessionName,
                    "pane" to paneId,
                    "window" to windowId,
                    "bytes" to removed.bufferedBytes,
                    "droppedEvents" to removed.eventCount,
                    "retainedPanes" to preRegistrationBuffers.size,
                    "retainedBytes" to totalPreRegistrationBytes,
                ),
            )
            Log.i(
                ISSUE_105_DIAG_TAG,
                "tmux-preregistration-window-close-evict pane=$paneId window=$windowId " +
                    "bytes=${removed.bufferedBytes} retainedPanes=${preRegistrationBuffers.size}",
            )
        }
    }

    /**
     * Extract the pane ids from a tmux layout string. A leaf cell is
     * `<w>x<h>,<x>,<y>,<paneId>`; container cells (`{...}` / `[...]`) have no
     * trailing pane id, so the regex matches only leaves. tmux layouts use bare
     * pane numbers; we `%`-prefix them to match [outputFor] / `%output` keys.
     * Over-matching a phantom id is harmless (no such buffer exists);
     * under-matching just misses a cleanup that the global cap still bounds.
     */
    private fun extractLayoutPaneIds(layout: String): Set<String> =
        LAYOUT_PANE_REGEX.findAll(layout)
            .map { "%" + it.groupValues[1] }
            .toSet()

    /** Issue #1212 test seam: aggregate retained pre-registration bytes across all panes. */
    internal fun preRegistrationRetainedBytesForTest(): Long =
        synchronized(preRegistrationLock) { totalPreRegistrationBytes }

    /** Issue #1212 test seam: number of distinct panes with a retained pre-registration buffer. */
    internal fun preRegistrationBufferCountForTest(): Int =
        synchronized(preRegistrationLock) { preRegistrationBuffers.size }

    /**
     * Issue #1204: a pre-registration frame was evicted (buffer overflow). Count
     * it and — on the first eviction for this pane's buffer — surface the
     * `tmux_client_preregistration_output_drop` diagnostic into the exportable
     * JSONL so this data-loss class feeds the #1175 export and is never
     * invisible again.
     */
    private fun recordPreRegistrationDrop(paneId: String, droppedForPane: Int, evictedBytes: Int) {
        val total = preRegistrationDroppedEvents.incrementAndGet()
        if (droppedForPane == 1) {
            TmuxClientDiagnostics.record(
                "tmux_client_preregistration_output_drop",
                commonDiagnosticFields() + mapOf(
                    "session" to sessionName,
                    "pane" to paneId,
                    "bytes" to evictedBytes,
                    "droppedEvents" to droppedForPane,
                    "totalDroppedEvents" to total,
                    "maxEvents" to PRE_REGISTRATION_MAX_EVENTS,
                    "maxBytes" to PRE_REGISTRATION_MAX_BYTES,
                ),
            )
        }
        Log.w(
            ISSUE_105_DIAG_TAG,
            "tmux-preregistration-output-drop pane=$paneId bytes=$evictedBytes " +
                "droppedEvents=$droppedForPane totalDroppedEvents=$total " +
                "maxEvents=$PRE_REGISTRATION_MAX_EVENTS maxBytes=$PRE_REGISTRATION_MAX_BYTES",
        )
    }

    private fun emitPublicEvent(event: ControlEvent) {
        if (event is ControlEvent.Begin || event is ControlEvent.End || event is ControlEvent.Error) {
            return
        }
        if (!eventBus.tryEmit(event)) {
            val dropped = eventBusDroppedEvents.incrementAndGet()
            if (eventBusOverflowDiagnosticEmitted.compareAndSet(false, true)) {
                TmuxClientDiagnostics.record(
                    "tmux_client_eventbus_overflow",
                    commonDiagnosticFields() + mapOf(
                        "session" to sessionName,
                        "event" to event.javaClass.simpleName,
                        "droppedEvents" to dropped,
                        "capacity" to EVENT_BUFFER,
                    ),
                )
            }
            Log.w(
                ISSUE_105_DIAG_TAG,
                "tmux-eventbus-drop event=${event.javaClass.simpleName} " +
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

    private fun bestEffortLateResponseDrainMs(): Long =
        BEST_EFFORT_LATE_RESPONSE_DRAIN_MS.coerceAtMost(commandTimeoutMs).coerceAtLeast(1L)

    /**
     * Issue #979: a `FatalClose` command timed out, but the transport-liveness
     * oracle (#986/#964) proves the SSH link is still alive, so we did NOT close
     * the SSH shell — the slow reply rode through. Recorded so the reviewer can
     * tell a self-inflicted-drop-avoided event apart from a genuine command
     * timeout that DID tear the channel down.
     */
    private fun recordFatalTimeoutRodeThrough(kind: String) {
        TmuxClientDiagnostics.record(
            "tmux_client_fatal_timeout_rode_through",
            commonDiagnosticFields() + mapOf(
                "session" to sessionName,
                "commandKind" to kind,
                "timeoutMs" to commandTimeoutMs,
                "transportProvenAlive" to true,
            ),
        )
    }

    private fun classifyReaderExit(source: String): ReaderDisconnectCause =
        when {
            readerExitIntent == ReaderExitIntent.CommandTimeout -> ReaderDisconnectCause.CommandTimeout
            readerExitIntent == ReaderExitIntent.DetachOrReplace -> ReaderDisconnectCause.DetachOrReplace
            closed && readerExitIntent == ReaderExitIntent.LocalClose -> ReaderDisconnectCause.LocalClose
            // Issue #998: the server announced shutdown in-band before the EOF.
            // This dominates the generic ReadEof/ReadFailure classification —
            // it's a confirmed server-death, not an ordinary transport blip —
            // but stays below our own intentional teardowns (close / detach /
            // command-timeout) above so a clean local close is never mislabelled.
            serverExitedInBand -> ReaderDisconnectCause.ServerExited
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
            ReaderDisconnectCause.ServerExited -> TmuxDisconnectReason.ServerExited
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
            // Issue #998: a confirmed in-band server-death is the most
            // authoritative drop cause — it must not be downgraded to a generic
            // ReaderEof if the EOF event lands a moment later.
            TmuxDisconnectReason.ServerExited -> 6
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
    private suspend fun writeLine(shell: SshShell, line: String) {
        shell.writeStdin("$line\n".toByteArray(Charsets.UTF_8))
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
         * Issue #1204: per-pane pre-registration replay buffer caps. `%output`
         * arriving before a pane's pipe is registered is held here (bounded by
         * BOTH limits, evict-oldest) and replayed on registration. Small on
         * purpose: this only needs to bridge the brief prewarm/attach window,
         * not act as unbounded scrollback.
         */
        private const val PRE_REGISTRATION_MAX_EVENTS = 256
        private const val PRE_REGISTRATION_MAX_BYTES = 256L * 1024L

        /**
         * Issue #1212: GLOBAL caps across all pre-registration buffers so many
         * orphaned/never-registered background pane-ids can't each pin a full
         * per-pane buffer for the connection's lifetime. When either is
         * exceeded the least-recently-active pane's whole buffer is evicted.
         */
        private const val PRE_REGISTRATION_TOTAL_MAX_BYTES = 1024L * 1024L
        private const val PRE_REGISTRATION_MAX_PANES = 64

        /**
         * Issue #1212: default grace a registered pane pipe waits for its first
         * collector before abandoning its pre-registration replay. Generous so
         * the ViewModel's prompt collect is never affected; a pane left
         * uncollected this long is treated as unviewed and its replay released.
         */
        private const val DEFAULT_FIRST_SUBSCRIBER_REPLAY_GRACE_MS = 30_000L

        /**
         * Issue #1212: pane-id extractor for a tmux layout string. Matches a
         * leaf cell's trailing `<w>x<h>,<x>,<y>,<paneId>`; container cells
         * (`{...}`/`[...]`) have no trailing id so are skipped. Non-overlapping
         * left-to-right, so adjacent leaves each yield exactly their own id.
         */
        private val LAYOUT_PANE_REGEX = Regex("""\d+x\d+,\d+,\d+,(\d+)""")

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

        /**
         * Issue #1297: sentinel line printed between the cursor reply and the
         * `capture-pane` payload in the single heal-capture `exec` so the one
         * stdout splits cleanly into cursor + capture. Distinctive enough that a
         * collision with real capture content is implausible, and the split takes
         * the FIRST occurrence so even a colliding capture body cannot corrupt it.
         */
        private const val HEAL_CAPTURE_SPLIT_MARKER = "__PS_HEAL_CAPTURE_SPLIT_9c3f__"

        /** LF (`0x0A`) — the line delimiter in the control-mode stream. */
        private const val LF_BYTE: Byte = 0x0A

        /** CR (`0x0D`) — trimmed off a trailing CRLF when tmux emits one. */
        private const val CR_BYTE: Byte = 0x0D

        /** Initial per-line accumulation buffer. Grows as needed. */
        private const val DEFAULT_LINE_BUFFER_BYTES = 4096

        /**
         * Hard ceiling on a single in-progress LF-framed line (issue #1231
         * T2). A legit `-CC` line — even a wide `%output` batch with escape
         * sequences — is well under this; the cap only trips on an LF-starved
         * stream, where without it the accumulation buffer grows unbounded to
         * an OOM. On overflow the buffer is flushed-and-reset so framing stays
         * bounded and the event is recorded via `tmux_client_line_overflow`.
         */
        private const val MAX_LINE_BUFFER_BYTES = 512 * 1024

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
        // Issue #998: the in-band `%exit server exited` arrived before the EOF —
        // the tmux SERVER announced it is shutting down (host reboot / kill).
        // This is server-death, NOT an ordinary transport blip, so a reattach
        // must drop to the list instead of resurrecting via `new-session -A`.
        ServerExited("server_exited"),
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
        // Issue #1297: when `true` the drain job stops emitting to [flow] and
        // frames accumulate in the bounded [channel] instead of being dropped
        // into a zero-subscriber flow. Driven by [pauseDelivery]/[resumeDelivery]
        // across the overflow-reseed producer swap.
        private val paused: MutableStateFlow<Boolean>,
        private val droppedEvents: AtomicInteger = AtomicInteger(0),
        private val overflowDiagnosticEmitted: AtomicBoolean = AtomicBoolean(false),
    ) {
        /** Issue #1297: freeze delivery so a collector gap holds, not drops. */
        fun pauseDelivery() {
            paused.value = true
        }

        /** Issue #1297: thaw delivery; held frames drain to the collector. */
        fun resumeDelivery() {
            paused.value = false
        }

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

        /**
         * Issue #1205: discard every frame currently buffered in the live
         * channel (best-effort, non-blocking) and return the count drained.
         * The pipe's drain job keeps running; only the queued-but-undelivered
         * burst frames are dropped so a post-overflow reseed is authoritative.
         */
        fun drainBacklog(): Int {
            var drained = 0
            while (channel.tryReceive().isSuccess) {
                drained++
            }
            return drained
        }

        companion object {
            fun create(
                scope: CoroutineScope,
                paneId: String,
                preRegistrationReplay: List<ControlEvent.Output> = emptyList(),
                firstSubscriberReplayGraceMs: Long = DEFAULT_FIRST_SUBSCRIBER_REPLAY_GRACE_MS,
                onOverflow: (TmuxOutputBacklogOverflow) -> Unit,
                diagnosticFields: () -> Map<String, Any?>,
            ): PaneOutputPipe {
                val flow = MutableSharedFlow<ControlEvent.Output>(
                    replay = 0,
                    extraBufferCapacity = EVENT_BUFFER,
                )
                val channel = Channel<ControlEvent.Output>(OUTPUT_BACKLOG_EVENTS)
                val paused = MutableStateFlow(false)
                val job = scope.launch {
                    // Issue #1204: replay any output buffered before this pipe
                    // was registered, IN ORDER, ahead of the live channel. The
                    // flow has replay = 0, so a value emitted before the first
                    // subscriber attaches is dropped — so wait for the first
                    // collector before replaying (the pipe's own live channel,
                    // capacity OUTPUT_BACKLOG_EVENTS, holds new frames in order
                    // meanwhile). This gate applies ONLY when there is buffered
                    // output to replay; the normal path (no pre-registration
                    // frames) drains the live channel immediately, unchanged.
                    var replay: List<ControlEvent.Output>? = preRegistrationReplay
                    if (!replay.isNullOrEmpty()) {
                        // Issue #1212: bound the wait for the first collector.
                        // If the returned flow is NEVER collected, this job used
                        // to park here forever, pinning the replay list for the
                        // life of the connection. Time-box the wait: on timeout
                        // abandon (release) the replay and fall through to the
                        // live drain so the pane is never wedged and its replay
                        // bytes are freed.
                        val gotSubscriber = withTimeoutOrNull(firstSubscriberReplayGraceMs) {
                            flow.subscriptionCount.first { it > 0 }
                            true
                        } == true
                        if (gotSubscriber) {
                            for (event in replay) {
                                flow.emit(event)
                            }
                        } else {
                            var abandonedBytes = 0L
                            for (event in replay) abandonedBytes += event.data.size
                            TmuxClientDiagnostics.record(
                                "tmux_client_preregistration_replay_abandoned",
                                diagnosticFields() + mapOf(
                                    "pane" to paneId,
                                    "bytes" to abandonedBytes,
                                    "droppedEvents" to replay.size,
                                    "graceMs" to firstSubscriberReplayGraceMs,
                                ),
                            )
                            Log.w(
                                ISSUE_105_DIAG_TAG,
                                "tmux-preregistration-replay-abandoned pane=$paneId " +
                                    "bytes=$abandonedBytes droppedEvents=${replay.size} " +
                                    "graceMs=$firstSubscriberReplayGraceMs",
                            )
                        }
                        replay = null // release the (bounded) replay list either way
                    }
                    while (true) {
                        val event = channel.receiveCatching().getOrNull() ?: break
                        // Issue #1297: HOLD delivery while paused. The overflow-
                        // reseed producer swap tears the sole collector down and
                        // reattaches a fresh one; without this gate every %output
                        // emitted in that window used to be emitted into the
                        // zero-subscriber (replay = 0) flow and vanish, leaving
                        // recovery to depend SOLELY on the step-4 capture (#1297's
                        // SPOF). The just-received frame is HELD in `event` (never
                        // dropped) and every later frame stays queued in the bounded
                        // [channel] (its own OUTPUT_BACKLOG_EVENTS overflow bound
                        // still applies); on resume the held frame emits first, then
                        // the queued frames drain — arrival order preserved. Checked
                        // AFTER the receive so a frame parked in [receiveCatching]
                        // when the pause lands is held, not emitted into a paused
                        // (subscriber-detached) flow.
                        if (paused.value) {
                            paused.first { !it }
                        }
                        flow.emit(event)
                    }
                }
                return PaneOutputPipe(
                    flow = flow,
                    channel = channel,
                    job = job,
                    onOverflow = onOverflow,
                    diagnosticFields = diagnosticFields,
                    paused = paused,
                )
            }
        }
    }

    /**
     * Issue #1204: bounded FIFO buffer holding `%output` frames that arrive for
     * a pane BEFORE its [PaneOutputPipe] is registered by [outputFor]. Capped at
     * [PRE_REGISTRATION_MAX_EVENTS] frames AND [PRE_REGISTRATION_MAX_BYTES]
     * bytes; on overflow the oldest frame is evicted (evict-oldest), the drop
     * is reported via the `onDrop` callback (counted + surfaced as a
     * diagnostic), and the single most recent frame is always retained so a
     * lone large first frame is never dropped to nothing.
     *
     * NOT thread-safe on its own — every access is serialised by the client's
     * [preRegistrationLock].
     */
    private class PreRegistrationOutputBuffer(
        private val paneId: String,
    ) {
        private val events = ArrayDeque<ControlEvent.Output>()

        /** Running total of retained bytes — read by the client's global-bound accounting (#1212). */
        var bufferedBytes: Long = 0L
            private set
        private var droppedEvents = 0

        /** Number of frames currently retained — reported on whole-buffer eviction (#1212). */
        val eventCount: Int
            get() = events.size

        /**
         * Append [event], evicting the oldest frame(s) while the buffer exceeds
         * either cap (but never the single most recent frame). [onDrop] is
         * invoked once per eviction with the running per-pane drop count and the
         * evicted frame's byte size.
         */
        fun add(event: ControlEvent.Output, onDrop: (droppedForPane: Int, evictedBytes: Int) -> Unit) {
            events.addLast(event)
            bufferedBytes += event.data.size
            while (events.size > 1 &&
                (events.size > PRE_REGISTRATION_MAX_EVENTS || bufferedBytes > PRE_REGISTRATION_MAX_BYTES)
            ) {
                val evicted = events.removeFirst()
                bufferedBytes -= evicted.data.size
                droppedEvents++
                onDrop(droppedEvents, evicted.data.size)
            }
        }

        /** Snapshot the buffered frames in arrival order for replay. */
        fun drain(): List<ControlEvent.Output> = events.toList()
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
        @Volatile
        var abandoned: Boolean = false
        val output: MutableList<String> = mutableListOf()
    }
}
