package com.pocketshell.core.ssh

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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class RealSshSessionTransferStallTest {

    @Test
    fun `slow but progressing upload is not capped by total duration`() = runBlocking {
        val upload = RecordingUploadCommand()
        val client = ScriptedClient { command ->
            when {
                command.startsWith("cat > ") -> upload
                command.startsWith("wc -c < ") -> CompletedCommand(stdout = { "${upload.size}\n" })
                command.startsWith("mv -f ") -> CompletedCommand(stdout = { "" })
                else -> error("unexpected command: $command")
            }
        }
        val session = RealSshSession(client, uploadStallTimeoutMs = 120L)
        val before = session.lastOutboundActivityNanosForTest()

        try {
            val path = withTimeout(5_000L) {
                session.uploadStream(
                    input = SlowChunkInputStream(chunks = 8, bytesPerChunk = 32, delayMs = 45L),
                    length = 8L * 32L,
                    name = "slow.bin",
                    remotePath = "/tmp/slow.bin",
                )
            }

            assertEquals("/tmp/slow.bin", path)
            assertEquals(8 * 32, upload.size)
            assertTrue(
                "upload payload writes must record outbound activity",
                session.lastOutboundActivityNanosForTest() > before,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `wedged download read is bounded by stall timeout`() = runBlocking {
        val wedgedCat = WedgedReadCommand()
        val client = ScriptedClient { command ->
            when {
                command.contains(SIZE_PROBE_NO_FILE_SENTINEL) -> CompletedCommand(stdout = { "10\n" })
                command.startsWith("cat ") -> wedgedCat
                else -> error("unexpected command: $command")
            }
        }
        val session = RealSshSession(client, downloadStallTimeoutMs = 120L)

        try {
            val thrown = withTimeout(5_000L) {
                try {
                    session.downloadFile("/tmp/wedged.bin", maxBytes = 1_024L)
                    throw AssertionError("download must not return from a wedged read")
                } catch (e: SshException) {
                    e
                }
            }

            assertTrue(
                "download failure should identify the stall: ${thrown.message}",
                thrown.message?.contains("stalled for 120ms") == true,
            )
            assertTrue(
                "the wedged read must actually have started",
                wedgedCat.readStarted,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `upload non-zero stderr is byte capped instead of reading forever`() = runBlocking {
        val upload = NonZeroUploadCommand(InfiniteStderrInputStream())
        val client = ScriptedClient { command ->
            when {
                command.startsWith("cat > ") -> upload
                command.startsWith("rm -f ") -> CompletedCommand(stdout = { "" })
                else -> error("unexpected command: $command")
            }
        }
        val session = RealSshSession(client, uploadStallTimeoutMs = 120L)

        try {
            val thrown = withTimeout(5_000L) {
                try {
                    session.uploadStream(
                        input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                        length = 3L,
                        name = "bad.bin",
                        remotePath = "/tmp/bad.bin",
                    )
                    throw AssertionError("non-zero upload cat must fail")
                } catch (e: SshException) {
                    e
                }
            }

            assertTrue(
                "upload stderr should be capped and visibly truncated: ${thrown.message}",
                thrown.message?.contains("stderr truncated after") == true,
            )
        } finally {
            session.close()
        }
    }

    private class ScriptedClient(
        private val commandFactory: (String) -> Session.Command,
    ) : SSHClient() {
        @Volatile
        private var disconnected = false

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun startSession(): Session = ScriptedSessionChannel(commandFactory)
        override fun disconnect() {
            disconnected = true
        }
    }

    private class ScriptedSessionChannel(
        private val commandFactory: (String) -> Session.Command,
    ) : FakeChannel(), Session {
        override fun exec(command: String): Session.Command = commandFactory(command)
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

    private class RecordingUploadCommand : FakeChannel(), Session.Command {
        private val bytes = ByteArrayOutputStream()

        val size: Int
            get() = bytes.size()

        override fun getOutputStream(): OutputStream = bytes
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class NonZeroUploadCommand(
        private val stderr: InputStream,
    ) : FakeChannel(), Session.Command {
        private val bytes = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = bytes
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = stderr
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 1
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class CompletedCommand(
        private val stdout: () -> String,
        private val exit: Int = 0,
    ) : FakeChannel(), Session.Command {
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(stdout().toByteArray(Charsets.UTF_8))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = exit
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class WedgedReadCommand : FakeChannel(), Session.Command {
        @Volatile
        var readStarted: Boolean = false
            private set

        private val stdout = object : InputStream() {
            @Volatile
            private var closed = false

            override fun read(): Int {
                readStarted = true
                while (!closed) {
                    if (Thread.interrupted()) throw InterruptedIOException("wedged download interrupted")
                    Thread.sleep(10L)
                }
                return -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = read()

            override fun close() {
                closed = true
            }
        }

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

    private class SlowChunkInputStream(
        private val chunks: Int,
        private val bytesPerChunk: Int,
        private val delayMs: Long,
    ) : InputStream() {
        private var emitted = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= chunks) return -1
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("slow input interrupted")
            }
            val count = minOf(bytesPerChunk, len)
            b.fill(0x5a, off, off + count)
            emitted += 1
            return count
        }
    }

    private class InfiniteStderrInputStream : InputStream() {
        override fun read(): Int = 'x'.code

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val count = len
            b.fill('x'.code.toByte(), off, off + count)
            return count
        }
    }

    private abstract class FakeChannel : Channel {
        @Volatile
        private var closed = false

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
