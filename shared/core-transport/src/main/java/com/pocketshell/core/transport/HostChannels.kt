package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
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

    override fun openExec(command: String): ExecChannel {
        // sshj's SessionChannel implements both Session and Command over the
        // same channel; a failure between startSession and exec must close the
        // half-opened session rather than leak it (that leak is one of the ways
        // #2120's budget could be consumed by channels nobody is using).
        val session = client.startSession()
        return try {
            SshjExecChannel(session, session.exec(command))
        } catch (failure: Throwable) {
            runCatching { session.close() }
            throw failure
        }
    }

    override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel =
        PtyChannelImpl.open(
            client = client,
            command = command,
            cols = cols,
            rows = rows,
            term = term,
            ioDispatcher = ioDispatcher,
        )

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
