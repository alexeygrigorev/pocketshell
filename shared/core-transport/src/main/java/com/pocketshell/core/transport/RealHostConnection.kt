package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.DisconnectListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sshj-backed [HostConnection] (rewrite task T-2).
 *
 * Constructed by [RealHostConnectionFactory] around an [SSHClient] that is
 * ALREADY connected and authenticated — this class never dials. Its jobs are:
 *
 * - [exec]: one session channel per call, full stdout/stderr capture, a
 *   wall-clock timeout that returns [ExecResult.timedOut] instead of throwing,
 *   and a non-zero remote exit that is a normal result, never an exception.
 * - [state]: flips to [TransportState.Lost] from sshj's disconnect listener the
 *   moment the transport drops (network failure or a far-end close), and to
 *   [TransportState.Closed] on a deliberate [close]. Both are terminal — a
 *   spent instance is replaced by dialling a new one via the factory.
 *
 * [openPty] (T-3), [sftp] (T-4) and [scheduleGraceClose] (T-5) are future
 * tasks and deliberately throw [NotImplementedError] here.
 *
 * [ioDispatcher] exists so tests can substitute a controllable dispatcher;
 * [Dispatchers.IO] appears only as the constructor default.
 */
internal class RealHostConnection(
    override val target: HostTarget,
    private val client: SSHClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HostConnection {

    private val _state = MutableStateFlow<TransportState>(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /** True once [close] started; makes the disconnect listener report Closed, not Lost. */
    private val closing = AtomicBoolean(false)

    init {
        // Installed after connect+auth (the factory hands over a live client),
        // so a drop in the tiny window before this line is caught by the
        // isConnected re-check below rather than silently missed. sshj fires
        // this listener from BOTH TransportImpl.die(...) (network failure /
        // far-end close / reader EOF) and an explicit local disconnect —
        // the `closing` flag disambiguates the deliberate path.
        client.transport.setDisconnectListener(
            DisconnectListener { reason, message ->
                settle(
                    if (closing.get()) {
                        TransportState.Closed
                    } else {
                        TransportState.Lost(describeDisconnect(reason?.toString(), message))
                    },
                )
            },
        )
        if (!client.isConnected) {
            settle(TransportState.Lost("transport dropped during connection handover"))
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        requireUsable("exec")
        return withContext(ioDispatcher) {
            // One session channel per call. sshj's SessionChannel implements
            // both Session and Command over the same channel.
            val session = client.startSession()
            var cmd: net.schmizz.sshj.connection.channel.direct.Session.Command? = null
            try {
                val running = session.exec(command)
                cmd = running
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                coroutineScope {
                    // Blocking channel-stream reads. sshj's ChannelInputStream
                    // parks on an interruptible monitor wait, so runInterruptible
                    // makes job cancellation interrupt (and thus unpark) them.
                    val outReader = launch { runInterruptible { drain(running.inputStream, stdout) } }
                    val errReader = launch { runInterruptible { drain(running.errorStream, stderr) } }
                    val finished = withTimeoutOrNull(timeoutMs) {
                        outReader.join()
                        errReader.join()
                        // Waits for the channel close that carries exit-status.
                        runInterruptible { running.join() }
                        true
                    }
                    if (finished == null) {
                        // Timed out. Interrupt the blocked reads so the reader
                        // jobs finish with whatever partial output was captured;
                        // do NOT call the blocking Channel.close() here — with a
                        // still-running remote command it parks up to the
                        // channel timeout (~30s) waiting for the remote's
                        // CHANNEL_CLOSE. The channel is torn down without
                        // blocking in the finally.
                        outReader.cancel()
                        errReader.cancel()
                        withTimeoutOrNull(DRAIN_AFTER_TIMEOUT_MS) {
                            outReader.join()
                            errReader.join()
                        }
                        ExecResult(
                            exitCode = -1,
                            stdout = snapshot(stdout),
                            stderr = snapshot(stderr),
                            timedOut = true,
                        )
                    } else {
                        ExecResult(
                            // Null when the server reported no exit status; -1
                            // mirrors the old core-ssh behaviour.
                            exitCode = running.exitStatus ?: -1,
                            stdout = snapshot(stdout),
                            stderr = snapshot(stderr),
                            timedOut = false,
                        )
                    }
                }
            } finally {
                // Bounded, interruptible, uncancellable best-effort teardown: a
                // stuck remote must not let Channel.close() wedge the exec path
                // for the full channel timeout, and the teardown must still run
                // even when the caller cancelled us.
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                        runInterruptible {
                            runCatching { cmd?.close() }
                            runCatching { session.close() }
                        }
                    }
                }
            }
        }
    }

    override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel =
        throw NotImplementedError("PTY channels are rewrite task T-3, not part of T-2")

    /**
     * T-4: the single SFTP channel for this connection. `by lazy` gives the
     * caching the [HostConnection.sftp] contract requires, thread-safely and
     * without any I/O here — [SftpChannelImpl] opens the remote subsystem
     * itself on first use.
     */
    private val sftpChannel: SftpChannelImpl by lazy { SftpChannelImpl(client, ioDispatcher) }

    override suspend fun sftp(): SftpChannel {
        requireUsable("sftp")
        return sftpChannel
    }

    override fun scheduleGraceClose(graceMs: Long): GraceHandle =
        throw NotImplementedError("Grace-close is rewrite task T-5, not part of T-2")

    override suspend fun close() {
        // Idempotent: only the first call touches the transport. Set `closing`
        // BEFORE disconnecting so the disconnect listener classifies the drop
        // as deliberate.
        if (!closing.compareAndSet(false, true)) return
        withContext(ioDispatcher) {
            runCatching { client.disconnect() }
        }
        settle(TransportState.Closed)
    }

    /** Terminal states are sticky: the first Lost/Closed wins, later ones are ignored. */
    private fun settle(terminal: TransportState) {
        _state.update { current ->
            when (current) {
                is TransportState.Lost, TransportState.Closed -> current
                else -> terminal
            }
        }
    }

    private fun requireUsable(operation: String) {
        when (val current = _state.value) {
            is TransportState.Lost ->
                throw IOException("$operation: connection lost (${current.cause})")
            TransportState.Closed ->
                throw IOException("$operation: connection closed")
            else -> Unit
        }
    }

    private fun drain(input: InputStream, sink: ByteArrayOutputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (_: IOException) {
                // Stream torn down (timeout-close or transport drop): whatever
                // was captured so far is the answer for this stream. A real
                // transport failure still surfaces via Command.join().
                -1
            }
            if (n < 0) return
            if (n > 0) {
                synchronized(sink) { sink.write(buffer, 0, n) }
            }
        }
    }

    private fun snapshot(sink: ByteArrayOutputStream): String =
        synchronized(sink) { sink.toByteArray() }.toString(Charsets.UTF_8)

    private fun describeDisconnect(reason: String?, message: String?): String {
        val parts = listOfNotNull(
            reason?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            message?.takeIf { it.isNotBlank() },
        )
        return if (parts.isEmpty()) "transport disconnected" else parts.joinToString(": ")
    }

    private companion object {
        const val READ_BUFFER_BYTES = 8192

        /** How long a timed-out exec waits for the interrupted readers to finish. */
        const val DRAIN_AFTER_TIMEOUT_MS = 2_000L

        /** Wall-clock cap on the best-effort channel/session teardown. */
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
