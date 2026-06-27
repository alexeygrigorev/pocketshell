package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

class RealSshSessionTeardownOrderingTest {

    @Test
    fun `close is bounded when dispatcher is occupied by a wedged transport op`() = runBlocking {
        val client = WedgedStartSessionClient()
        val session = RealSshSession(client)
        val exec = async(Dispatchers.IO) {
            runCatching { session.exec("true") }
        }

        assertTrue(
            "the synthetic startSession must enter before close queues behind it",
            client.startSessionEntered.await(5, TimeUnit.SECONDS),
        )

        val elapsedMs = measureTimeMillis {
            session.close()
        }

        assertTrue(
            "close() must return within the session close budget instead of waiting " +
                "for the dispatcher's full per-op ceiling; elapsed=${elapsedMs}ms",
            elapsedMs < 3_500L,
        )
        assertFalse("close must mark the session disconnected", session.isConnected)
        assertTrue(
            "forced close must hard-disconnect the raw SSH client",
            client.rawDisconnected.await(1, TimeUnit.SECONDS),
        )

        withTimeout(5_000L) {
            exec.await()
        }
        assertTrue(
            "force-closing the dispatcher must interrupt the wedged startSession",
            client.startSessionInterrupted.get(),
        )
    }

    @Test
    fun `close with active tail queues channel closes before session disconnect`() = runBlocking {
        val events = RecordingEvents()
        val command = BlockingTailCommand(events)
        val channel = RecordingSessionChannel(command, events)
        val client = RecordingClient(channel, events)
        val session = RealSshSession(client)

        val tail = session.tail("/tmp/log") { }
        assertTrue(
            "tail read must start before cancellation",
            command.readStarted.await(5, TimeUnit.SECONDS),
        )

        session.close()
        tail.cancelAndJoin()

        val snapshot = events.snapshot()
        val commandClose = snapshot.indexOf("command.close")
        val channelClose = snapshot.indexOf("session.close")
        val disconnect = snapshot.indexOf("disconnect")

        assertTrue("command.close must be recorded: $snapshot", commandClose >= 0)
        assertTrue("session.close must be recorded: $snapshot", channelClose >= 0)
        assertTrue("disconnect must be recorded: $snapshot", disconnect >= 0)
        assertTrue(
            "tail command close must be queued before transport disconnect: $snapshot",
            commandClose < disconnect,
        )
        assertTrue(
            "tail session channel close must be queued before transport disconnect: $snapshot",
            channelClose < disconnect,
        )
    }

    @Test
    fun `keepalive-dead teardown disconnects through independent teardown path`() {
        KeepAliveTestOverride.setForTest(intervalMs = 10L, countMax = 1)
        val client = KeepAliveDeadClient()
        val session = RealSshSession(client)
        try {
            assertTrue(
                "keepalive death must close the transport",
                client.disconnected.await(5, TimeUnit.SECONDS),
            )
            assertFalse("closed keepalive-dead session must report disconnected", session.isConnected)
        } finally {
            session.close()
            KeepAliveTestOverride.clear()
        }
    }

    private class WedgedStartSessionClient : SSHClient() {
        val startSessionEntered = CountDownLatch(1)
        val startSessionInterrupted = AtomicBoolean(false)
        val rawDisconnected = CountDownLatch(1)

        override fun isConnected(): Boolean = rawDisconnected.count != 0L
        override fun isAuthenticated(): Boolean = rawDisconnected.count != 0L

        override fun startSession(): Session {
            startSessionEntered.countDown()
            try {
                while (true) {
                    if (Thread.interrupted()) {
                        startSessionInterrupted.set(true)
                        throw InterruptedException("wedged startSession interrupted")
                    }
                    Thread.sleep(10L)
                }
            } catch (e: InterruptedException) {
                startSessionInterrupted.set(true)
                throw e
            }
        }

        override fun disconnect() {
            rawDisconnected.countDown()
        }
    }

    private class KeepAliveDeadClient : SSHClient() {
        val disconnected = CountDownLatch(1)

        @Volatile
        private var closed = false

        override fun isConnected(): Boolean = !closed
        override fun isAuthenticated(): Boolean = !closed

        override fun disconnect() {
            closed = true
            disconnected.countDown()
        }
    }

    private class RecordingClient(
        private val sessionChannel: Session,
        private val events: RecordingEvents,
    ) : SSHClient() {
        @Volatile
        private var disconnected = false

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun startSession(): Session = sessionChannel

        override fun disconnect() {
            events.add("disconnect")
            disconnected = true
        }
    }

    private class RecordingSessionChannel(
        private val command: Session.Command,
        private val events: RecordingEvents,
    ) : FakeChannel(), Session {
        override fun exec(command: String): Session.Command = this.command

        override fun close() {
            events.add("session.close")
            super.close()
        }

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

    private class BlockingTailCommand(
        private val events: RecordingEvents,
    ) : FakeChannel(), Session.Command {
        val readStarted = CountDownLatch(1)
        private val stdout = BlockingInputStream(readStarted)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            events.add("command.close")
            super.close()
            stdout.close()
        }
    }

    private class BlockingInputStream(
        private val readStarted: CountDownLatch,
    ) : InputStream() {
        @Volatile
        private var closed = false

        override fun read(): Int {
            readStarted.countDown()
            while (!closed) {
                Thread.sleep(10L)
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingEvents {
        private val events = Collections.synchronizedList(mutableListOf<String>())

        fun add(event: String) {
            events += event
        }

        fun snapshot(): List<String> = synchronized(events) {
            events.toList()
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
