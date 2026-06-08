package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class RealSshSessionExecCancellationTest {

    @Test
    fun `exec cancellation closes wedged command and session channel`() = runBlocking {
        val command = BlockingCommand()
        val sessionChannel = RecordingSessionChannel(command)
        val session = RealSshSession(ConnectedClient(sessionChannel))

        try {
            val job = launch {
                runCatching { session.exec("cat /tmp/wedged") }
            }

            withTimeout(5_000L) {
                command.readStarted.await()
            }

            withTimeout(5_000L) {
                job.cancelAndJoin()
            }

            assertTrue("cancelling exec must close the command channel", command.closed)
            assertTrue("cancelling exec must close the SSH session channel", sessionChannel.closed)
        } finally {
            session.close()
        }
    }

    private class ConnectedClient(
        private val sessionChannel: Session,
    ) : SSHClient() {
        override fun isConnected(): Boolean = true
        override fun isAuthenticated(): Boolean = true
        override fun startSession(): Session = sessionChannel
        override fun disconnect() {
            // No socket is opened by this test client.
        }
    }

    private class RecordingSessionChannel(
        private val command: Session.Command,
    ) : FakeChannel(), Session {
        override fun exec(command: String): Session.Command = this.command
        override fun allocateDefaultPTY() = Unit
        override fun allocatePTY(
            term: String,
            cols: Int,
            rows: Int,
            width: Int,
            height: Int,
            modes: MutableMap<PTYMode, Int>,
        ) = Unit

        override fun reqX11Forwarding(host: String, proto: String, cookie: Int) = Unit
        override fun setEnvVar(name: String, value: String) = Unit
        override fun startShell(): Session.Shell = throw UnsupportedOperationException("not used")
        override fun startSubsystem(name: String): Session.Subsystem =
            throw UnsupportedOperationException("not used")
    }

    private class BlockingCommand : FakeChannel(), Session.Command {
        val readStarted = CompletableDeferred<Unit>()
        private val stdout = BlockingInputStream(readStarted)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            stdout.close()
        }
    }

    private class BlockingInputStream(
        private val readStarted: CompletableDeferred<Unit>,
    ) : InputStream() {
        @Volatile
        private var closed = false

        override fun read(): Int {
            readStarted.complete(Unit)
            while (!closed) {
                Thread.sleep(10L)
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private abstract class FakeChannel : Channel {
        @Volatile
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }

        override fun getAutoExpand(): Boolean = false
        override fun getID(): Int = 1
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getLocalMaxPacketSize(): Int = 32 * 1024
        override fun getLocalWinSize(): Long = 0L
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getRecipient(): Int = 1
        override fun getRemoteCharset(): Charset = StandardCharsets.UTF_8
        override fun getRemoteMaxPacketSize(): Int = 32 * 1024
        override fun getRemoteWinSize(): Long = 0L
        override fun getType(): String = "session"
        override fun isOpen(): Boolean = !closed
        override fun setAutoExpand(autoExpand: Boolean) = Unit
        override fun join() = Unit
        override fun join(timeout: Long, unit: TimeUnit) = Unit
        override fun isEOF(): Boolean = false
        override fun getLoggerFactory(): LoggerFactory = LoggerFactory.DEFAULT
        override fun handle(message: Message, packet: SSHPacket) = Unit
        override fun notifyError(error: SSHException) = Unit
    }
}
