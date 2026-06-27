package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduce-first regression for #935 S4-2: [RealSshSession.exec]'s Phase-2
 * stdout/stderr read was UNBOUNDED. A half-open / wedged transport leaves the
 * blocking JDK `readBytes()` parked forever, hanging the calling coroutine (and
 * every caller — six gateways — that did not wrap `exec` in its own timeout).
 *
 * On BASE (no fix) `exec` against a wedged read NEVER returns, so the
 * `withTimeout(...)` wrapping the call below trips and the test fails (red). With
 * the boundary read-timeout bound the exec fast-fails with a clear, retryable
 * [SshExecTimeoutException] and closes the wedged session so the lease pool
 * self-heals (green).
 */
class RealSshSessionExecReadTimeoutTest {

    @Test
    fun `wedged exec read fast-fails with SshExecTimeoutException instead of hanging`() =
        runBlocking {
            val command = WedgedReadCommand()
            val sessionChannel = RecordingSessionChannel(command)
            val client = ConnectedClient(sessionChannel)
            // Short bound so the test exercises the wedged-read ceiling without a
            // real 30s wait. On base (no bound) this constructor param doesn't
            // exist / is ignored and the read hangs forever.
            val session = RealSshSession(client, execReadTimeoutMs = 300L)

            try {
                // On BASE (no bound) `exec` never returns and the surrounding
                // `withTimeout(10s)` trips → test fails (red). With the boundary
                // read-timeout bound `exec` throws fast (green).
                val thrown: SshExecTimeoutException = withTimeout(10_000L) {
                    try {
                        session.exec("cat /tmp/wedged")
                        throw AssertionError("exec must not return on a wedged read")
                    } catch (e: SshExecTimeoutException) {
                        e
                    }
                }

                assertTrue(
                    "the wedged read must actually have started (not failed on open)",
                    command.readStarted.isCompleted,
                )
                assertTrue(
                    "timeout exception must carry the wedged command",
                    thrown.command.contains("cat /tmp/wedged"),
                )

                // The bound CLOSED the wedged session so the lease pool self-heals:
                // a disconnected session is discarded + re-dialed on next acquire.
                assertFalse(
                    "wedged exec timeout must close the session (lease self-heal)",
                    session.isConnected,
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `exec drains stdout and stderr concurrently`() = runBlocking {
        val command = CoordinatedStdoutStderrCommand()
        val session = RealSshSession(
            ConnectedClient(RecordingSessionChannel(command)),
            execReadTimeoutMs = 2_000L,
        )

        try {
            val result = withTimeout(5_000L) {
                session.exec("writes-both-streams")
            }

            assertEquals("stdout\n", result.stdout)
            assertEquals("stderr\n", result.stderr)
            assertEquals(0, result.exitCode)
            assertTrue("stderr reader must have started", command.stderrReadStarted.await(1, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
    }

    @Test
    fun `exec caps buffered stdout`() = runBlocking {
        val command = LargeStdoutCommand()
        val session = RealSshSession(
            ConnectedClient(RecordingSessionChannel(command)),
            execReadTimeoutMs = 2_000L,
        )

        try {
            val thrown = try {
                session.exec("too-much-output")
                throw AssertionError("exec must reject unbounded stdout")
            } catch (e: com.pocketshell.core.ssh.SshException) {
                e
            }

            assertTrue(
                "error should mention capped stdout",
                thrown.message.orEmpty().contains("stdout exceeded"),
            )
            assertTrue("command channel should be closed", command.closed)
        } finally {
            session.close()
        }
    }

    private class ConnectedClient(
        private val sessionChannel: Session,
    ) : SSHClient() {
        @Volatile
        var disconnected: Boolean = false
            private set

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun startSession(): Session = sessionChannel
        override fun disconnect() {
            // No socket is opened by this test client; flip the flag so
            // `session.isConnected` reports the post-close state.
            disconnected = true
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

    private class WedgedReadCommand : FakeChannel(), Session.Command {
        val readStarted = CompletableDeferred<Unit>()
        private val stdout = WedgedInputStream(readStarted)

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

    private class CoordinatedStdoutStderrCommand : FakeChannel(), Session.Command {
        val stderrReadStarted = CountDownLatch(1)
        private val stdout = object : InputStream() {
            private val delegate = ByteArrayInputStream("stdout\n".toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int {
                stderrReadStarted.await(5, TimeUnit.SECONDS)
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stderrReadStarted.await(5, TimeUnit.SECONDS)
                return delegate.read(b, off, len)
            }
        }
        private val stderr = object : InputStream() {
            private val delegate = ByteArrayInputStream("stderr\n".toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int {
                stderrReadStarted.countDown()
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stderrReadStarted.countDown()
                return delegate.read(b, off, len)
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class LargeStdoutCommand : FakeChannel(), Session.Command {
        private val stdout = ByteArrayInputStream(ByteArray(EXEC_STREAM_MAX_BYTES + 1) { 'x'.code.toByte() })

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    /**
     * Models the half-open transport: `read()` parks indefinitely (never reaches
     * EOF) until the channel is closed by the timeout's session teardown, OR the
     * thread is interrupted by the `runInterruptible` cancellation the bound
     * raises. Either way the read unparks only because the BOUND acted — never on
     * its own.
     */
    private class WedgedInputStream(
        private val readStarted: CompletableDeferred<Unit>,
    ) : InputStream() {
        @Volatile
        private var closed = false

        override fun read(): Int {
            readStarted.complete(Unit)
            while (!closed) {
                // Honour interrupt so the bound's runInterruptible teardown can
                // unpark this thread (the real JDK socket read throws on
                // close/interrupt; we emulate that here).
                if (Thread.interrupted()) throw java.io.InterruptedIOException("wedged read interrupted")
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
