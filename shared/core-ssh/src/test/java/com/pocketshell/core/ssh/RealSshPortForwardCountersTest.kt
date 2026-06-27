package com.pocketshell.core.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RealSshPortForwardCountersTest {

    @Test
    fun `copy counts bytes as soon as they are read before local write can observe them`() {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        val forward = RealSshPortForward(
            channels = object : PortForwardChannelTransport {
                override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection =
                    error("test does not accept local connections")

                override fun closeChannel(channel: DirectConnection) = Unit
            },
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
        )
        try {
            val counter = AtomicLong(0)
            val input = SingleReadInputStream(byteArrayOf(1, 2, 3, 4))
            var counterObservedInWrite = -1L
            val output = object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun write(b: ByteArray, off: Int, len: Int) {
                    counterObservedInWrite = counter.get()
                }
            }

            val copy = RealSshPortForward::class.java.getDeclaredMethod(
                "copy",
                InputStream::class.java,
                OutputStream::class.java,
                AtomicLong::class.java,
            )
            copy.isAccessible = true
            copy.invoke(forward, input, output, counter)

            assertEquals(4, counter.get())
            assertEquals(4, counterObservedInWrite)
            assertTrue(input.exhausted)
        } finally {
            forward.close()
        }
    }

    @Test
    fun `accepted local connections are capped to bound copy thread pressure`() {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        val openCalls = AtomicInteger(0)
        val forward = RealSshPortForward(
            channels = object : PortForwardChannelTransport {
                override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection {
                    openCalls.incrementAndGet()
                    return TestDirectConnection()
                }

                override fun closeChannel(channel: DirectConnection) {
                    channel.close()
                }
            },
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
        )
        val clients = mutableListOf<Socket>()
        try {
            repeat(40) {
                clients += Socket(InetAddress.getByName("127.0.0.1"), localPort)
            }
            assertTrue(
                "accept loop should fill the active connection budget",
                waitUntilReal(2_000L) { openCalls.get() >= 32 },
            )
            Thread.sleep(150)
            assertEquals(
                "connections beyond the active budget must be rejected before opening SSH channels",
                32,
                openCalls.get(),
            )
        } finally {
            forward.close()
            clients.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `close uses an aggregate copy thread join budget`() {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        val forward = RealSshPortForward(
            channels = object : PortForwardChannelTransport {
                override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection =
                    error("test does not accept local connections")

                override fun closeChannel(channel: DirectConnection) = Unit
            },
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
        )
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val startCopyThread = RealSshPortForward::class.java.getDeclaredMethod(
            "startCopyThread",
            String::class.java,
            Function0::class.java,
        )
        startCopyThread.isAccessible = true
        repeat(4) { index ->
            startCopyThread.invoke(
                forward,
                "test-copy-$index",
                {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    Unit
                } as Function0<Unit>,
            )
        }
        assertTrue("test copy threads should be running", started.await(2, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        try {
            forward.close()
        } finally {
            release.countDown()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(
            "close should spend one aggregate join budget, not one timeout per copy thread; elapsed=$elapsedMs ms",
            elapsedMs < 1_800L,
        )
    }

    private class SingleReadInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var exhausted = false
            private set
        private var read = false

        override fun read(): Int = error("bulk read expected")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read) {
                exhausted = true
                return -1
            }
            read = true
            bytes.copyInto(b, off, 0, bytes.size)
            return bytes.size
        }
    }

    private class TestDirectConnection : DirectConnection(fakeConnection(), "remote.example", 5432) {
        private val input = BlockingInputStream()
        private val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output

        override fun close() {
            input.close()
        }
    }

    private class BlockingInputStream : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            awaitClosed()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            awaitClosed()
            return -1
        }

        override fun close() {
            closed.countDown()
        }

        private fun awaitClosed() {
            try {
                closed.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
        }
    }

    private companion object {
        fun fakeConnection(): Connection {
            val config = DefaultConfig()
            val transport = proxy<Transport> { methodName, returnType ->
                when (methodName) {
                    "getConfig" -> config
                    "getTimeoutMs" -> 0
                    "getRemoteHost" -> "remote.example"
                    "getRemotePort" -> 22
                    "isRunning" -> true
                    else -> defaultValue(returnType)
                }
            }
            val nextId = AtomicInteger(0)
            return proxy { methodName, returnType ->
                when (methodName) {
                    "getTransport" -> transport
                    "nextID" -> nextId.incrementAndGet()
                    "getWindowSize" -> 1_048_576L
                    "getMaxPacketSize" -> 32 * 1024
                    "getTimeoutMs" -> 0
                    else -> defaultValue(returnType)
                }
            }
        }

        inline fun <reified T> proxy(
            crossinline handler: (methodName: String, returnType: Class<*>) -> Any?,
        ): T {
            return Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ -> handler(method.name, method.returnType) } as T
        }

        fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Void.TYPE -> null
            else -> null
        }

        fun waitUntilReal(timeoutMs: Long, predicate: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (predicate()) return true
                Thread.sleep(10)
            }
            return predicate()
        }
    }
}
