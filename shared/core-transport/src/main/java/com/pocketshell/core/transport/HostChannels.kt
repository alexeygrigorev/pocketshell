package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException
import java.io.InputStream

/**
 * One exec channel: exactly the sshj surface [RealHostConnection.exec] uses.
 *
 * Same seam idea as [ForwardedChannel] in [PortForwardImpl] — the machinery
 * worth testing (here: the channel budget's accounting across success, remote
 * failure, timeout and cancellation) can then be driven on the host JVM with no
 * SSH transport, constructor-injected rather than through a `*ForTest` back door.
 */
internal interface ExecChannel {
    val stdout: InputStream
    val stderr: InputStream

    /** Blocks until the remote closes the channel — the event carrying `exit-status`. */
    fun join()

    /** The remote exit status, or `null` when the server never sent one. */
    val exitStatus: Int?

    /** Best-effort blocking teardown of the command and its session. Never throws. */
    fun close()
}

/**
 * The host refused to open a channel because it had no room for one — the
 * server side of issue #2120's exhaustion, as opposed to
 * [ChannelBudgetExhaustedException] which is our own side of it.
 *
 * Raised by [SshjHostChannels] instead of letting sshj's `open failed` escape,
 * so [ChannelBudget.openRetryingHostRefusal] has a transport-neutral signal to
 * retry on and a fake [HostChannels] can reproduce the condition on the JVM
 * without an SSH server. See [sessionRefusalAware] for exactly which refusals
 * map to this and why.
 */
internal class ChannelRefusedException(
    val channelType: String,
    cause: Throwable,
) : IOException("host refused a $channelType channel: ${cause.message}", cause)

/**
 * Translates a refused SESSION-channel open into [ChannelRefusedException].
 *
 * Only [OpenFailException.Reason.UNKNOWN_CHANNEL_TYPE] is left alone: it says
 * the server does not implement `session` at all, which no amount of waiting
 * fixes. Every other reason is treated as "not right now".
 *
 * That is deliberately broad, because the reason code is not a reliable
 * discriminator and the *channel type* is. OpenSSH answers a `MaxSessions`
 * overflow (`session_open`'s "no more sessions" in `session.c`) with
 * `SSH2_OPEN_CONNECT_FAILED` and the message `open failed` — verified against
 * `tests/docker/Dockerfile.ssh` while reproducing this, where sshd's own log
 * line count matched the client's refusal count exactly. `CONNECT_FAILED` is
 * meaningful for a `direct-tcpip` channel, where it means the far end would not
 * accept the connection; on a `session` channel there is nothing to connect to,
 * so it can only mean the server declined to create the session. This helper is
 * therefore applied to session opens only, never to forwarded channels.
 *
 * `internal` rather than private so the reason-code mapping — the part that was
 * wrong the first time this was written, and that only a real sshd revealed —
 * is pinned directly by a unit test instead of only through the Docker lane.
 */
internal inline fun <T> sessionRefusalAware(block: () -> T): T =
    try {
        block()
    } catch (failure: OpenFailException) {
        if (failure.reason == OpenFailException.Reason.UNKNOWN_CHANNEL_TYPE) {
            throw failure
        }
        throw ChannelRefusedException(SESSION_CHANNEL_TYPE, failure)
    }

internal const val SESSION_CHANNEL_TYPE = "session"

/**
 * The channel-opening primitives a [HostConnection] needs from its transport.
 *
 * [RealHostConnection] owns the *budget* (issue #2120) and the connection
 * lifecycle; this interface owns the "actually open a channel" step, so the
 * budget's accounting is testable without an SSH server and the sshj specifics
 * stay in [SshjHostChannels].
 */
internal interface HostChannels {
    /** Opens ONE session channel running [command]. Blocking; call on an IO dispatcher. */
    fun openExec(command: String): ExecChannel

    suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel

    /** Creates the connection's SFTP channel. Called once — [RealHostConnection] caches it. */
    fun sftp(): SftpChannel

    suspend fun openPortForward(remoteHost: String, remotePort: Int, localPort: Int): PortForward
}

/** The production implementation: every channel comes off the one shared [client]. */
internal class SshjHostChannels(
    private val client: SSHClient,
    private val ioDispatcher: CoroutineDispatcher,
) : HostChannels {

    override fun openExec(command: String): ExecChannel = sessionRefusalAware {
        // sshj's SessionChannel implements both Session and Command over the
        // same channel; a failure between startSession and exec must close the
        // half-opened session rather than leak it (that leak is one of the ways
        // #2120's budget could be consumed by channels nobody is using).
        val session = client.startSession()
        try {
            SshjExecChannel(session, session.exec(command))
        } catch (failure: Throwable) {
            runCatching { session.close() }
            throw failure
        }
    }

    override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel =
        sessionRefusalAware {
            PtyChannelImpl.open(
                client = client,
                command = command,
                cols = cols,
                rows = rows,
                term = term,
                ioDispatcher = ioDispatcher,
            )
        }

    override fun sftp(): SftpChannel = SftpChannelImpl(client, ioDispatcher)

    override suspend fun openPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): PortForward = PortForwardImpl(
        channels = PortForwardImpl.sshjOpener(client),
        remoteHost = remoteHost,
        remotePort = remotePort,
        localPort = localPort,
        ioDispatcher = ioDispatcher,
    )

    private class SshjExecChannel(
        private val session: Session,
        private val command: Session.Command,
    ) : ExecChannel {
        override val stdout: InputStream get() = command.inputStream
        override val stderr: InputStream get() = command.errorStream
        override val exitStatus: Int? get() = command.exitStatus

        override fun join() {
            command.join()
        }

        override fun close() {
            runCatching { command.close() }
            runCatching { session.close() }
        }
    }
}
