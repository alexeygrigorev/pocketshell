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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.DisconnectListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

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
 *   [TransportState.Closed] — carrying WHICH deliberate path closed it, see
 *   [CloseReason] — on [close] or on the grace scheduler's deadline. Both are
 *   terminal: a spent instance is replaced by dialling a new one via the
 *   factory.
 *
 * [openPty] delegates to [PtyChannelImpl] (T-3), [sftp] to a cached
 * [SftpChannelImpl] (T-4), and [scheduleGraceClose] to [GraceCloseScheduler]
 * (T-5) — each owns its own logic in its own file; this class only wires the
 * shared [client]/[ioDispatcher] into them.
 *
 * ## Channel budget (issue #2120)
 *
 * Because D28 shares ONE connection per host, every channel this class opens
 * competes for the server's per-connection `MaxSessions` (OpenSSH default: 10).
 * Exceeding it is what produced the reported `open failed` crash on
 * session-create with 14 sessions open. Every opening path here therefore takes
 * a [ChannelBudget] permit before the open request goes out and holds it for the
 * channel's life, so the client never asks for a channel the server would
 * refuse. See [ChannelBudget] for the sizing rationale and the exhaustion
 * behaviour.
 *
 * [ioDispatcher] exists so tests can substitute a controllable dispatcher;
 * [Dispatchers.IO] appears only as the constructor default. [channels] and
 * [budget] are injectable for the same reason — production always uses the
 * sshj-backed opener and the default budget.
 */
internal class RealHostConnection(
    override val target: HostTarget,
    private val client: SSHClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val channels: HostChannels = SshjHostChannels(client, ioDispatcher),
    private val budget: ChannelBudget = ChannelBudget(),
) : HostConnection {

    private val _state = MutableStateFlow<TransportState>(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /**
     * Non-null once [closeWith] started, holding WHY — which both makes the
     * close one-shot (the CAS from null is the whole idempotence gate) and
     * lets the disconnect listener report `Closed(thatReason)` rather than
     * `Lost`.
     *
     * One atomic rather than a boolean plus a separate field because the
     * listener can fire from sshj's own thread at any instant: a two-step
     * "flag, then reason" would leave a window where a deliberate close is
     * seen with the wrong (default) reason and settles the sticky terminal
     * state to it forever.
     */
    private val closeReason = AtomicReference<CloseReason?>(null)

    init {
        // Installed after connect+auth (the factory hands over a live client),
        // so a drop in the tiny window before this line is caught by the
        // isConnected re-check below rather than silently missed. sshj fires
        // this listener from BOTH TransportImpl.die(...) (network failure /
        // far-end close / reader EOF) and an explicit local disconnect —
        // `closeReason` disambiguates the deliberate path, and says which
        // deliberate path it was (issue #2487).
        client.transport.setDisconnectListener(
            DisconnectListener { reason, message ->
                settle(
                    when (val deliberate = closeReason.get()) {
                        null -> TransportState.Lost(describeDisconnect(reason?.toString(), message))
                        else -> TransportState.Closed(deliberate)
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
        // #2120: the permit is taken BEFORE the channel-open request goes out and
        // released only after the bounded teardown below has run, so a burst of
        // execs queues here instead of getting `open failed` from the server.
        return budget.withPermit("exec") { execOnChannel(command, timeoutMs) }
    }

    private suspend fun execOnChannel(command: String, timeoutMs: Long): ExecResult =
        withContext(ioDispatcher) {
            // One session channel per call. sshj's SessionChannel implements
            // both Session and Command over the same channel. The open is
            // retried while the HOST refuses it: our permit says we are within
            // our own budget, but the server can still be holding slots for
            // channels we already finished with (see [ChannelBudget]).
            val channel = budget.openRetryingHostRefusal("exec") {
                channels.openExec(command)
            }
            try {
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                coroutineScope {
                    // Blocking channel-stream reads. sshj's ChannelInputStream
                    // parks on an interruptible monitor wait, so runInterruptible
                    // makes job cancellation interrupt (and thus unpark) them.
                    val outReader = launch { runInterruptible { drain(channel.stdout, stdout) } }
                    val errReader = launch { runInterruptible { drain(channel.stderr, stderr) } }
                    val finished = withTimeoutOrNull(timeoutMs) {
                        outReader.join()
                        errReader.join()
                        // Waits for the channel close that carries exit-status.
                        runInterruptible { channel.join() }
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
                            exitCode = channel.exitStatus ?: -1,
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
                // even when the caller cancelled us. The budget permit is only
                // released once this has returned (withPermit's finally wraps
                // this whole call), so a queued caller never overlaps a channel
                // we are still tearing down.
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                        runInterruptible { channel.close() }
                    }
                }
            }
        }

    override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel {
        requireUsable("openPty")
        // All the PTY machinery lives in PtyChannelImpl (rewrite task T-3);
        // this connection only owns the client and the dispatcher it runs on.
        // A PTY holds its channel until it ends, so it holds its #2120 permit
        // for the same span — released by BudgetedPtyChannel.
        val permit = budget.acquire("openPty")
        val opened = try {
            // Same host-refusal retry as exec: session-create is exactly the
            // action #2120 reported dying on, so it must not be the one that
            // surfaces a raw refusal.
            budget.openRetryingHostRefusal("openPty") {
                channels.openPty(command, cols, rows, term)
            }
        } catch (failure: Throwable) {
            permit.release()
            throw failure
        }
        return BudgetedPtyChannel(opened, permit)
    }

    /**
     * T-4: the single SFTP channel for this connection. `by lazy` gives the
     * caching the [HostConnection.sftp] contract requires, thread-safely and
     * without any I/O here — [SftpChannelImpl] opens the remote subsystem
     * itself on first use.
     */
    private val sftpChannel: SftpChannel by lazy { channels.sftp() }

    /** Guards the one-shot SFTP permit reservation below. */
    private val sftpLock = Mutex()
    private var sftpPermit: ChannelPermit? = null

    override suspend fun sftp(): SftpChannel {
        requireUsable("sftp")
        // Exactly ONE permit, however many times this is called: the cached
        // channel opens at most one SFTP subsystem channel for the whole life of
        // the connection. It has no close() of its own (it dies with the
        // connection), so [close] is what returns the permit.
        sftpLock.withLock {
            if (sftpPermit == null) sftpPermit = budget.acquire("sftp")
        }
        return sftpChannel
    }

    /**
     * P-4: a local-to-remote forward over this connection. The listener bind and
     * the accept loop live in [PortForwardImpl]; this class only supplies the
     * client-backed channel opener and the dispatcher its close runs on.
     *
     * Binding the local [ServerSocket] happens on [ioDispatcher] because it is a
     * blocking syscall that fails (address in use, permission) rather than a
     * pure allocation — the caller must see that failure as a thrown
     * [java.io.IOException] from this suspend call, not from a background thread.
     */
    override suspend fun openPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): PortForward {
        requireUsable("openPortForward")
        // One permit for the forward's whole life, released when it closes.
        // Deliberately NOT one per accepted client: `direct-tcpip` channels are
        // not what `MaxSessions` counts, and D21 gives forwarding its own
        // connection anyway (that policy lives in the app layer, so this method
        // cannot assume it). A single conservative reservation keeps the shared
        // budget honest if a forward is ever opened on an interactive
        // connection, without pretending to police a different limit.
        val permit = budget.acquire("openPortForward")
        return try {
            withContext(ioDispatcher) {
                BudgetedPortForward(
                    delegate = channels.openPortForward(remoteHost, remotePort, localPort),
                    permit = permit,
                )
            }
        } catch (failure: Throwable) {
            permit.release()
            throw failure
        }
    }

    /**
     * T-5: owns the single pending delayed close (D21). All of its logic lives
     * in [GraceCloseScheduler]; it runs on this connection's [ioDispatcher], so
     * a test that injects a virtual-time dispatcher controls the grace timer.
     */
    private val grace = GraceCloseScheduler(ioDispatcher) { closeWith(CloseReason.GraceExpired) }

    override fun scheduleGraceClose(graceMs: Long): GraceHandle = grace.schedule(graceMs)

    /**
     * Closes because someone ASKED for this connection to end. The grace
     * scheduler above is the other caller of [closeWith], and the difference
     * between the two is exactly what [CloseReason] exists to record — see it
     * for why a consumer must not treat them alike (issue #2487).
     */
    override suspend fun close() = closeWith(CloseReason.Requested)

    private suspend fun closeWith(reason: CloseReason) {
        // Idempotent: only the first call touches the transport. Recording the
        // reason IS the gate, and it happens BEFORE disconnecting so the
        // disconnect listener classifies the drop as deliberate — and as the
        // right KIND of deliberate.
        if (!closeReason.compareAndSet(null, reason)) return
        // Issue #2477: settle [state] to Closed BEFORE disconnecting, not
        // after. `client.disconnect()` cascades into closing every open
        // channel (sshj tears each one down as part of the same transport
        // teardown), and each channel's OWN close is what resolves a
        // [PtyChannel.exit] deferred — on whatever internal sshj/dispatcher
        // thread that channel's watcher happens to run on, with NO guaranteed
        // ordering against this function getting back around to updating
        // `state`. A caller such as `SessionViewModel` that reacts to a
        // channel ending by checking `state` to tell a deliberate close apart
        // from a lost link can therefore observe the channel already ended
        // while `state` still reads `Connected` — misclassifying a DELIBERATE
        // close as a dropped link and reconnecting into a fresh, orphaned
        // connection nobody asked for (exactly the cross-journey pollution
        // #2477 reports). Setting the terminal state FIRST, before any
        // teardown that could resolve a channel's exit even begins, makes that
        // observation impossible by construction rather than by timing.
        settle(TransportState.Closed(reason))
        withContext(ioDispatcher) {
            runCatching { client.disconnect() }
        }
        // The SFTP channel has no close() of its own — it dies with the
        // transport — so this is where its permit comes back. Every other
        // permit is owned by something with its own end (exec's finally, the
        // PTY/forward decorators), and a spent connection is never reused.
        sftpLock.withLock { sftpPermit?.release() }
    }

    /**
     * Holds one [ChannelBudget] permit for the life of a PTY channel.
     *
     * Released on [close] AND when the channel's [PtyChannel.exit] completes: a
     * remote process that exits ends the server's session channel whether or not
     * the app ever calls close(), and a permit stranded on a dead channel is
     * exactly how #2120's budget would silently shrink back to nothing.
     * [ChannelPermit.release] is idempotent, so both firing is fine.
     */
    private class BudgetedPtyChannel(
        private val delegate: PtyChannel,
        private val permit: ChannelPermit,
    ) : PtyChannel by delegate {

        init {
            delegate.exit.invokeOnCompletion { permit.release() }
        }

        override suspend fun close() {
            try {
                delegate.close()
            } finally {
                permit.release()
            }
        }
    }

    /** Holds one [ChannelBudget] permit for the life of a port forward. */
    private class BudgetedPortForward(
        private val delegate: PortForward,
        private val permit: ChannelPermit,
    ) : PortForward by delegate {

        override suspend fun close() {
            try {
                delegate.close()
            } finally {
                permit.release()
            }
        }
    }

    /** Terminal states are sticky: the first Lost/Closed wins, later ones are ignored. */
    private fun settle(terminal: TransportState) {
        _state.update { current ->
            when (current) {
                is TransportState.Lost, is TransportState.Closed -> current
                else -> terminal
            }
        }
    }

    private fun requireUsable(operation: String) {
        when (val current = _state.value) {
            is TransportState.Lost ->
                throw IOException("$operation: connection lost (${current.cause})")
            is TransportState.Closed ->
                throw IOException("$operation: connection closed (${current.reason})")
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
