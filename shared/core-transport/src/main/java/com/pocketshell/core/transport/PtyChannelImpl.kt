package com.pocketshell.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sshj-backed [PtyChannel] (rewrite task T-3): one session channel with a
 * PTY allocated on it, running one remote command (or the login shell when the
 * command is blank).
 *
 * ## Shape
 *
 * - [output] is a cold-to-hot flow over a BOUNDED [Channel] of
 *   [OUTPUT_FRAME_CAPACITY] frames, fed by a scope-owned reader coroutine that
 *   starts at [open] (so bytes arriving before the first collector are not
 *   lost) and completes the flow at remote EOF. Single-consumer, per the
 *   [PtyChannel] contract.
 * - Backpressure is suspend-based end to end: the reader `send`s into the
 *   bounded channel, so a slow collector stops the reader, which stops draining
 *   sshj's `ChannelInputStream`, which stops replenishing the SSH channel
 *   window (sshj's `autoExpand` is off by default and deliberately left off),
 *   which finally blocks the REMOTE writer. Nothing on this path grows without
 *   bound — the only buffering is 64 frames here plus sshj's fixed 2 MiB
 *   channel window.
 * - [write] and [resize] both mutate the transport, so both serialize through
 *   one [writeMutex]; concurrent unserialized writes to the same
 *   `ChannelOutputStream` would interleave and corrupt the remote's stdin.
 * - [exit] is completed by a second scope-owned coroutine that waits for the
 *   channel close (which is what carries `exit-status`) and then reports
 *   sshj's status — `null` when the server never sent one.
 *
 * Every blocking sshj call runs on [ioDispatcher] inside [runInterruptible], so
 * cancelling this channel's scope interrupts (and thus unparks) a thread parked
 * in a channel-stream read or a windowed write.
 *
 * Not this class's job: terminal emulation (the app's `TerminalPtyBridge`) and
 * reconnect (the session layer). A spent PTY channel is replaced, never revived.
 */
internal class PtyChannelImpl private constructor(
    private val channel: SessionChannel,
    private val ioDispatcher: CoroutineDispatcher,
) : PtyChannel {

    /** Owns the reader and the exit waiter. Supervisor: one failing does not kill the other. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Bounded on purpose — see the backpressure note in the class doc. */
    private val frames = Channel<ByteArray>(capacity = OUTPUT_FRAME_CAPACITY)

    private val exitDeferred = CompletableDeferred<Int?>()

    /** Serializes stdin writes AND window-change requests on this channel. */
    private val writeMutex = Mutex()

    private val closing = AtomicBoolean(false)

    private lateinit var readerJob: Job

    override val output: Flow<ByteArray> = frames.receiveAsFlow()

    override val exit: Deferred<Int?> get() = exitDeferred

    /** Started by [open] once construction finished, never from an initializer. */
    private fun start() {
        readerJob = scope.launch { readLoop() }
        scope.launch { awaitExit() }
    }

    private suspend fun readLoop() {
        val stream: InputStream = channel.inputStream
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val read = runInterruptible { readOrEof(stream, buffer) }
                if (read < 0) break
                // Suspends when the collector is behind: this send IS the
                // backpressure, and it must copy because `buffer` is reused.
                if (read > 0) frames.send(buffer.copyOf(read))
            }
        } finally {
            // Remote EOF, a torn-down stream, or cancellation — all of them end
            // the flow. Idempotent.
            frames.close()
        }
    }

    private suspend fun awaitExit() {
        try {
            // Returns when the channel is closed, which is the event that
            // carries the remote `exit-status` request.
            runInterruptible { channel.join() }
        } catch (_: Throwable) {
            // Transport drop, local close, or cancellation: whatever status the
            // server managed to send is still the best answer below.
        } finally {
            exitDeferred.complete(runCatching { channel.exitStatus }.getOrNull())
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (closing.get()) throw IOException("write on a closed PTY channel")
        if (bytes.isEmpty()) return
        val payload = bytes.copyOf()
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val stdin: OutputStream = channel.outputStream
                runInterruptible {
                    stdin.write(payload)
                    stdin.flush()
                }
            }
        }
    }

    override suspend fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (closing.get()) throw IOException("resize on a closed PTY channel")
        withContext(ioDispatcher) {
            // `window-change` is another transport write on this channel, so it
            // takes the same lock as stdin — a window-change interleaved into a
            // half-written stdin payload would corrupt both.
            writeMutex.withLock {
                runInterruptible { channel.changeWindowDimensions(cols, rows, 0, 0) }
            }
        }
    }

    override suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            // Bounded: a wedged remote must not let Channel.close() park the
            // caller for the full channel timeout.
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                withContext(ioDispatcher) {
                    runInterruptible { runCatching { channel.close() } }
                }
            }
            // Give the reader a moment to observe the torn-down stream so
            // `output` completes the normal way; then stop everything. The
            // reader can be parked in `send` with nobody collecting, hence the
            // bound and the explicit close/complete below.
            withTimeoutOrNull(READER_SETTLE_MS) { readerJob.join() }
            scope.cancel()
            frames.close()
            // Last word on `exit`: whatever status the close handshake managed
            // to collect, or null when the server never sent one. Both this and
            // the exit waiter go through complete(), which is first-wins, so a
            // close racing a normal end cannot downgrade a real status.
            exitDeferred.complete(runCatching { channel.exitStatus }.getOrNull())
        }
    }

    private fun readOrEof(stream: InputStream, buffer: ByteArray): Int = try {
        stream.read(buffer)
    } catch (_: IOException) {
        // sshj tears the stream down with an IOException subclass on local
        // close and on transport loss; both mean "no more output".
        -1
    }

    companion object {
        /**
         * Bounded output buffer, in frames (rewrite plan T-3). 64 x 8 KiB is
         * roughly half a megabyte of slack for a collector that briefly falls
         * behind, and a hard ceiling on what this class will hold.
         */
        const val OUTPUT_FRAME_CAPACITY = 64

        private const val READ_BUFFER_BYTES = 8192

        /** Wall-clock cap on the blocking channel close. */
        private const val CLOSE_TIMEOUT_MS = 2_000L

        /** How long [close] waits for the reader to finish before cancelling it. */
        private const val READER_SETTLE_MS = 1_000L

        /**
         * Opens a PTY channel on [client]: session channel → `pty-req` at
         * [cols] x [rows] advertising [term] → `exec` for [command] (or `shell`
         * when [command] is blank). A failure at any step closes the
         * half-opened channel instead of leaking it.
         */
        suspend fun open(
            client: SSHClient,
            command: String,
            cols: Int,
            rows: Int,
            term: String,
            ioDispatcher: CoroutineDispatcher,
        ): PtyChannel {
            require(cols > 0 && rows > 0) { "PTY size must be positive, got ${cols}x$rows" }
            return withContext(ioDispatcher) {
                val session = runInterruptible { client.startSession() }
                // sshj's startSession always hands back a SessionChannel, which
                // is simultaneously the Session, the Command and the Shell —
                // the one object that exposes streams, window-change AND exit
                // status. Fail loudly rather than silently losing resize/exit
                // if that ever changes.
                val sessionChannel = session as? SessionChannel ?: run {
                    runCatching { session.close() }
                    throw IOException(
                        "openPty: expected a SessionChannel, got ${session.javaClass.name}",
                    )
                }
                try {
                    runInterruptible {
                        sessionChannel.allocatePTY(
                            /* term = */ term,
                            /* cols = */ cols,
                            /* rows = */ rows,
                            /* widthPx = */ 0,
                            /* heightPx = */ 0,
                            /* modes = */ emptyMap(),
                        )
                        if (command.isBlank()) {
                            sessionChannel.startShell()
                        } else {
                            sessionChannel.exec(command)
                        }
                    }
                    PtyChannelImpl(sessionChannel, ioDispatcher).also { it.start() }
                } catch (failure: Throwable) {
                    runCatching { sessionChannel.close() }
                    throw failure
                }
            }
        }
    }
}
