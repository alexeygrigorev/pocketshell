package com.pocketshell.core.ssh

import com.pocketshell.testsupport.drainMainLooperUntil
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
import java.util.AbstractSet
import java.util.LinkedHashSet
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
                waitUntilReal { openCalls.get() >= 32 },
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
        val finished = CountDownLatch(4)
        repeat(4) { index ->
            startCopyThread.invoke(
                forward,
                "test-copy-$index",
                {
                    started.countDown()
                    try {
                        release.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        finished.countDown()
                    }
                    Unit
                } as Function0<Unit>,
            )
        }
        try {
            assertTrue("test copy threads should be running", started.await(2, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            forward.close()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(
                "close should spend one aggregate join budget, not one timeout per copy thread; " +
                    "elapsed=$elapsedMs ms",
                elapsedMs < 1_800L,
            )
            assertTrue(
                "an interruptible callback must finish before close returns",
                finished.await(2, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
            assertTrue(
                "all aggregate-budget callbacks must be released and awaited during cleanup",
                finished.await(2, TimeUnit.SECONDS),
            )
            forward.close()
        }
    }

    @Test
    fun `close leaves no live copy-thread registrations after its bounded cleanup`() {
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
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val startCopyThread = RealSshPortForward::class.java.getDeclaredMethod(
            "startCopyThread",
            String::class.java,
            Function0::class.java,
        ).apply { isAccessible = true }
        val copyThreadsField = RealSshPortForward::class.java.getDeclaredField("copyThreads")
            .apply { isAccessible = true }
        var copyThread: Thread? = null

        try {
            startCopyThread.invoke(
                forward,
                "test-copy-lifecycle",
                {
                    started.countDown()
                    try {
                        release.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        finished.countDown()
                    }
                    Unit
                } as Function0<Unit>,
            )
            assertTrue("test copy thread should be running", started.await(2, TimeUnit.SECONDS))

            @Suppress("UNCHECKED_CAST")
            val registered = copyThreadsField.get(forward) as Set<Thread>
            copyThread = registered.singleOrNull { it.name == "test-copy-lifecycle" }
            assertTrue("the running callback must be present in copyThreads", copyThread != null)
            assertTrue("the captured copy thread should be alive", copyThread!!.isAlive)

            forward.close()

            assertTrue(
                "close must wait for the callback's finally block",
                finished.await(2, TimeUnit.SECONDS),
            )
            assertTrue(
                "the actual copy thread captured before close must no longer be alive",
                !copyThread!!.isAlive,
            )
        } finally {
            release.countDown()
            assertTrue(
                "the live-thread callback must always be released and awaited during cleanup",
                finished.await(2, TimeUnit.SECONDS),
            )
            copyThread?.join(2_000L)
            forward.close()
        }
    }

    @Test
    fun `close reports a copy callback that stays alive after interrupt`() {
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
        val started = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val startCopyThread = RealSshPortForward::class.java.getDeclaredMethod(
            "startCopyThread",
            String::class.java,
            Function0::class.java,
        ).apply { isAccessible = true }
        val copyThreadsField = RealSshPortForward::class.java.getDeclaredField("copyThreads")
            .apply { isAccessible = true }
        var copyThread: Thread? = null

        try {
            startCopyThread.invoke(
                forward,
                "test-copy-non-terminating",
                {
                    started.countDown()
                    try {
                        while (stop.count > 0L) {
                            try {
                                stop.await(25, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                // Deliberately ignore close()'s interrupt: this
                                // callback is the non-terminating close case.
                            }
                        }
                    } finally {
                        finished.countDown()
                    }
                    Unit
                } as Function0<Unit>,
            )
            assertTrue("test callback should be running", started.await(2, TimeUnit.SECONDS))

            @Suppress("UNCHECKED_CAST")
            val registered = copyThreadsField.get(forward) as Set<Thread>
            copyThread = registered.singleOrNull { it.name == "test-copy-non-terminating" }
            assertTrue("the non-terminating callback must be registered", copyThread != null)

            var failure: IllegalStateException? = null
            try {
                forward.close()
            } catch (e: IllegalStateException) {
                failure = e
            }
            assertTrue("a still-live callback must make close fail explicitly", failure != null)
            assertTrue(
                "the failure must identify the live copy thread",
                failure!!.message.orEmpty().contains("live copy threads"),
            )
            assertTrue("the deliberately non-terminating callback should still be alive", copyThread!!.isAlive)
        } finally {
            stop.countDown()
            copyThread?.interrupt()
            assertTrue(
                "the non-terminating callback must be released and awaited during cleanup",
                finished.await(2, TimeUnit.SECONDS),
            )
            copyThread?.join(2_000L)
            forward.close()
        }
    }

    @Test
    fun `close snapshots injected active pairs under its lifecycle lock`() {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        val raceSet = LockAwareSnapshotRaceSet<Pair<Socket, DirectConnection>>()
        raceSet.add(Socket() to TestDirectConnection())
        val forward = RealSshPortForward(
            channels = object : PortForwardChannelTransport {
                override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection =
                    error("test does not accept local connections")

                override fun closeChannel(channel: DirectConnection) = Unit
            },
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
            activePairs = raceSet,
        )
        val lifecycleLock = requireNotNull(
            RealSshPortForward::class.java.getDeclaredField("lifecycleLock")
                .apply { isAccessible = true }
                .get(forward),
        )
        raceSet.attachLifecycleLock(lifecycleLock)

        try {
            raceSet.startMutator()
            forward.close()
            assertTrue(
                "the snapshot must execute while lifecycleLock is held",
                raceSet.snapshotObservedUnderLock,
            )
            assertTrue(
                "the injected mutation must always be released and awaited",
                raceSet.mutationFinished.await(2, TimeUnit.SECONDS),
            )
        } finally {
            raceSet.releaseMutator()
            assertTrue(
                "the injected race fixture must not leave its mutator behind",
                raceSet.mutationFinished.await(2, TimeUnit.SECONDS),
            )
            forward.close()
        }
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

    /**
     * A one-element set that makes an unlocked snapshot deterministic: it
     * reports its pre-race size, lets a mutator clear the set, and then lets
     * the old one-element `toList()` call `iterator().next()` on an empty set,
     * which throws NoSuchElementException. The production ArrayList snapshot
     * runs under lifecycleLock, so the mutator waits until the copy is made.
     */
    private class LockAwareSnapshotRaceSet<T> : AbstractSet<T>() {
        private val values = LinkedHashSet<T>()
        private val snapshotEntered = CountDownLatch(1)
        val mutationFinished = CountDownLatch(1)
        @Volatile
        var snapshotObservedUnderLock: Boolean = false
            private set

        private var lifecycleLock: Any? = null
        private var mutator: Thread? = null

        override val size: Int
            get() {
                val observedSize = values.size
                val lock = requireNotNull(lifecycleLock) { "lifecycle lock not attached" }
                snapshotObservedUnderLock = Thread.holdsLock(lock)
                snapshotEntered.countDown()
                if (!snapshotObservedUnderLock) {
                    check(mutationFinished.await(2, TimeUnit.SECONDS)) {
                        "race mutator did not clear the unlocked snapshot"
                    }
                }
                return observedSize
            }

        override fun iterator(): MutableIterator<T> = values.iterator()

        override fun add(element: T): Boolean = values.add(element)

        override fun clear() {
            values.clear()
        }

        fun attachLifecycleLock(lock: Any) {
            lifecycleLock = lock
        }

        fun startMutator() {
            val lock = requireNotNull(lifecycleLock) { "lifecycle lock not attached" }
            mutator = Thread({
                try {
                    snapshotEntered.await(2, TimeUnit.SECONDS)
                    synchronized(lock) {
                        values.clear()
                    }
                } finally {
                    mutationFinished.countDown()
                }
            }, "test-active-pairs-mutator").apply {
                isDaemon = true
                start()
            }
        }

        fun releaseMutator() {
            snapshotEntered.countDown()
            mutator?.join(2_000L)
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

        /**
         * Issue #2017: was a hand-rolled bounded poll whose caller spent a **2 s**
         * REAL-time budget — the tightest instance of the shape that made
         * `Issue1574DeadReconnectTest` red only under contention (the accept loop
         * here is a real background thread that a loaded box simply does not
         * schedule promptly). The loop and the ONE audited generous deadline now
         * come from the shared settle-pump; there is no per-tick drain to inject
         * (no looper, no test scheduler), so the helper's default no-op `onTick` is
         * the right pure poll. The returned boolean stays the caller's load-bearing
         * `assertTrue`, and the following exact-count assertion still bounds the
         * other side, so nothing is weakened.
         */
        fun waitUntilReal(predicate: () -> Boolean): Boolean =
            drainMainLooperUntil(sleepMs = 10L, condition = predicate)
    }
}
