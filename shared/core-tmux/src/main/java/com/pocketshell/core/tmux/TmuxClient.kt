package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.core.tmux.protocol.ControlEventStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedDeque

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
     * Tear down the tmux control channel and the underlying SSH shell.
     * Idempotent. After [close] the [events] flow completes and any
     * pending [sendCommand] calls fail with [TmuxClientException].
     */
    override fun close()
}

/**
 * Thrown by [TmuxClient] for transport- and protocol-level failures
 * (SSH shell teardown, client close while waiting for a response, etc.).
 */
public class TmuxClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

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
 */
internal class RealTmuxClient(
    private val session: SshSession,
    scope: CoroutineScope,
    private val sessionName: String = DEFAULT_SESSION_NAME,
    private val startDirectory: String? = null,
) : TmuxClient {

    // Child scope rooted under the caller's scope. SupervisorJob() so a
    // failing reader doesn't tear down peers; Dispatchers.IO because the
    // reader blocks on the SSH input stream.
    private val clientScope = CoroutineScope(
        scope.coroutineContext +
            SupervisorJob(scope.coroutineContext[Job]) +
            Dispatchers.IO,
    )

    // Shared flow of all events. extraBufferCapacity absorbs short bursts
    // without blocking the reader. If consumers fall behind for longer than
    // that, readerLoop suspends instead of dropping terminal bytes; losing a
    // `%output` chunk corrupts the pane emulator state.
    //
    // replay = 0 because subscribers only care about events that arrive
    // after they start collecting; tmux's structural state (sessions /
    // windows / panes) is re-derivable by issuing `list-sessions` etc.
    private val eventBus = MutableSharedFlow<ControlEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )

    override val events: Flow<ControlEvent> = eventBus.asSharedFlow()

    // Issue #173: latched signal that the reader loop has exited (or
    // [close] was called). The reader sets this from its `finally` block
    // so subscribers (notably [TmuxSessionViewModel.attachClient]) can
    // observe a socket tear-down even though the hot [eventBus]
    // SharedFlow never completes on its own.
    private val _disconnected: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val disconnected: StateFlow<Boolean> = _disconnected.asStateFlow()

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
            val resolvedSessionName = sessionName.trim().ifBlank { DEFAULT_SESSION_NAME }
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

    override suspend fun sendCommand(cmd: String): CommandResponse {
        if (closed) throw TmuxClientException("client is closed")
        if (!connected) throw TmuxClientException("client is not connected")
        val sh = shell ?: throw TmuxClientException("client has no active shell")
        // Single-flight: tmux only honours one outstanding command at a
        // time. The deferred is registered (queued) BEFORE we write the
        // bytes so we can't lose a response that arrives before we've
        // returned from the write.
        return sendMutex.withLock {
            val deferred = CompletableDeferred<CommandResponse>()
            val pendingCmd = PendingCommand(deferred = deferred)
            pendingQueue.offer(pendingCmd)

            try {
                writeLine(sh.stdin, cmd)
            } catch (t: Throwable) {
                pendingQueue.remove(pendingCmd)
                throw TmuxClientException("failed to write command `$cmd`: ${t.message}", t)
            }

            try {
                deferred.await()
            } catch (t: Throwable) {
                pendingQueue.remove(pendingCmd)
                throw t
            }
        }
    }

    override fun outputFor(paneId: String): Flow<ControlEvent.Output> {
        // Reuse the same shared eventBus — filtering on the consumer side
        // is fine because tmux emits a single %output per pane write, so
        // there's no fan-out savings from a per-pane flow. Filtering at
        // the call site also means callers can late-subscribe without us
        // having to materialise a flow per pane up-front.
        return eventBus
            .asSharedFlow()
            .filterIsInstance<ControlEvent.Output>()
            .filter { it.paneId == paneId }
    }

    override fun close() {
        if (closed) return
        closed = true
        connected = false
        // Issue #173: signal disconnection BEFORE we tear the rest down
        // so observers like [TmuxSessionViewModel] can flip their
        // connection-status state without racing the scope cancel.
        _disconnected.value = true
        readerJob?.cancel()
        readerJob = null
        runCatching { shell?.close() }
        shell = null
        // Fail any outstanding sendCommand waiters so callers don't block
        // forever after a teardown.
        while (true) {
            val cmd = pendingQueue.poll() ?: break
            cmd.deferred.completeExceptionally(
                TmuxClientException("client closed while waiting for response"),
            )
        }
        clientScope.cancel()
    }

    /**
     * Long-running coroutine that reads `tmux -CC`'s stdout line-by-line,
     * feeds each line to [ControlEventStream], and forwards events to
     * [eventBus] / pending-command waiters.
     */
    private suspend fun readerLoop(sh: SshShell) {
        // BufferedReader.readLine() handles `\r\n` / `\n` normalisation —
        // tmux on alpine emits LF-only but we don't want to be brittle
        // about it. UTF-8 is the only sensible decoding: tmux escapes
        // non-printables inside %output data anyway, so the surrounding
        // protocol stream is plain ASCII / UTF-8.
        val reader = BufferedReader(InputStreamReader(sh.stdout, Charsets.UTF_8))

        // Turn the blocking readLine() loop into a Flow<String>. We
        // intentionally don't use `lineSequence().asFlow()` because that
        // doesn't play nicely with coroutine cancellation — the underlying
        // read is blocking. Instead we drive the read manually inside a
        // cancellation-aware loop.
        val lines = flow {
            while (currentCoroutineContext().isActive) {
                val line = try {
                    reader.readLine()
                } catch (t: Throwable) {
                    // Channel torn down (close() called, transport drop,
                    // etc.). End the flow rather than re-throw so the
                    // reader loop completes cleanly. We surface the
                    // cause via the diagnostic tag so the reviewer can
                    // tell an SSH-transport drop apart from a clean
                    // close — the bare `null` return also covers the
                    // "EOF without throw" case below.
                    Log.w(
                        ISSUE_105_DIAG_TAG,
                        "ssh-read-failed cause=${t.javaClass.simpleName}: ${t.message}",
                    )
                    null
                }
                if (line == null) {
                    Log.i(ISSUE_105_DIAG_TAG, "ssh-read-eof")
                    break
                }
                emit(line)
            }
        }

        // The command currently accumulating payload, if any. tmux's
        // protocol guarantees responses arrive in submission order and
        // that there's never more than one outstanding command, so we pop
        // the next pending entry from the queue on `%begin` and complete
        // it on `%end` / `%error`.
        var inflight: PendingCommand? = null
        val stream = ControlEventStream(
            onResponsePayload = { _, line ->
                inflight?.output?.add(line)
            },
        )

        try {
            stream.events(lines).collect { event ->
                when (event) {
                    is ControlEvent.Begin -> {
                        // The next pending command — if any — is for this
                        // `%begin`. We capture the tmux-assigned number
                        // for the eventual CommandResponse.number field.
                        val match = pendingQueue.poll()
                        if (match != null) {
                            match.commandNumber = event.number
                            inflight = match
                        }
                    }
                    is ControlEvent.End -> {
                        val match = inflight
                        if (match != null && match.commandNumber == event.number) {
                            match.deferred.complete(
                                CommandResponse(
                                    number = match.commandNumber,
                                    output = match.output.toList(),
                                    isError = false,
                                ),
                            )
                        }
                        inflight = null
                    }
                    is ControlEvent.Error -> {
                        val match = inflight
                        if (match != null && match.commandNumber == event.number) {
                            match.deferred.complete(
                                CommandResponse(
                                    number = match.commandNumber,
                                    output = match.output.toList(),
                                    isError = true,
                                ),
                            )
                        }
                        inflight = null
                    }
                    else -> Unit
                }
                // Always forward the event to the bus so external
                // observers (UI, session-list updater, etc.) see the same
                // events the response-correlator just consumed. Use
                // suspending emit: tmux pane output is terminal state, not
                // disposable telemetry.
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
                    eventBus.emit(event)
                    Log.i(
                        ISSUE_105_DIAG_TAG,
                        "tmux-output-bus-emit pane=${event.paneId} bytes=${event.data.size}",
                    )
                } else {
                    eventBus.emit(event)
                }
            }
        } finally {
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
            while (true) {
                val cmd = pendingQueue.poll() ?: break
                cmd.deferred.completeExceptionally(
                    TmuxClientException("control channel closed before response"),
                )
            }
        }
    }

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

        private fun escapeSingleQuoted(input: String): String =
            input.replace("'", "'\\''")

        /**
         * Buffer slack in the event bus so a brief subscriber stall
         * doesn't drop events. Sized generously — events are tiny
         * structs, and dropping is much worse than holding a few KB of
         * heap.
         */
        private const val EVENT_BUFFER = 256

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
