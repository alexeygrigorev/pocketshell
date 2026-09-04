package com.pocketshell.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PortForwardImpl] — the accept/copy/teardown engine behind
 * [HostConnection.openPortForward].
 *
 * These drive REAL local sockets (the forward's own [ServerSocket] plus client
 * connections to it) against a fake [ForwardedChannel], so everything the
 * forward actually owns is exercised: the accept loop, both copy directions, the
 * byte counters, the active-connection cap and the deterministic close. Only the
 * SSH `direct-tcpip` channel is faked, because that is the one thing that needs
 * a server. The real-sshd half is covered by `PortForwardIntegrationTest` in
 * `core-portfwd`.
 */
class PortForwardImplTest {

    @Test
    fun `counters track bytes in both directions through a real local socket`() = runBlocking {
        val channel = PipedTestChannel()
        val forward = newForward(opener = { _, _ -> channel })
        try {
            Socket().use { client ->
                client.connect(java.net.InetSocketAddress("127.0.0.1", forward.localPort), 2_000)
                client.soTimeout = 5_000

                // local -> remote
                client.getOutputStream().write("ping".toByteArray())
                client.getOutputStream().flush()
                assertTrue(
                    "expected 4 forwarded bytes to reach the channel, got ${channel.receivedText()}",
                    waitUntil { channel.receivedText() == "ping" },
                )
                assertEquals(4L, forward.bytesForwarded)

                // remote -> local
                channel.pushToLocal("pong!".toByteArray())
                val buffer = ByteArray(5)
                var read = 0
                while (read < 5) {
                    val n = client.getInputStream().read(buffer, read, 5 - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals("pong!", String(buffer, 0, read))
                assertEquals(5L, forward.bytesReceived)
            }
        } finally {
            forward.close()
        }
    }

    @Test
    fun `close stops accepting, tears the channel down and joins every copy thread`() =
        runBlocking {
            val channel = PipedTestChannel()
            val forward = newForward(opener = { _, _ -> channel })
            val localPort = forward.localPort

            Socket().use { client ->
                client.connect(java.net.InetSocketAddress("127.0.0.1", localPort), 2_000)
                client.getOutputStream().write("x".toByteArray())
                client.getOutputStream().flush()
                assertTrue(waitUntil { channel.receivedText() == "x" })
                assertTrue(
                    "the copy threads must be running before close is meaningful",
                    liveForwardThreads(localPort).isNotEmpty(),
                )

                forward.close()

                assertFalse(forward.isActive)
                assertTrue("the channel must be closed with the forward", channel.isClosed)
                // Deterministic, not a sleep race: close() joins the copiers.
                assertEquals(
                    "no copy thread may outlive close()",
                    emptyList<String>(),
                    liveForwardThreads(localPort).map { it.name },
                )
                // The listener is gone, so a new client cannot connect.
                assertTrue(
                    "the local listener must be closed",
                    runCatching {
                        Socket().use {
                            it.connect(java.net.InetSocketAddress("127.0.0.1", localPort), 500)
                        }
                    }.isFailure,
                )
            }
        }

    /**
     * Regression for the CI-only failure of the test above (PR #2480): `close()`
     * returned while the local port was STILL bound and still completing TCP
     * handshakes, so the "a new client cannot connect" probe occasionally
     * connected.
     *
     * The cause is not this class's own bookkeeping but the JDK's: since the NIO
     * socket rewrite, [ServerSocket.close] is DEFERRED whenever another thread is
     * parked inside [ServerSocket.accept] — it flips the Java-level `closed` flag
     * and unparks the acceptor, but the listening file descriptor is only really
     * released when that acceptor unwinds out of `accept()`
     * (`NioSocketImpl.endAccept` → `tryFinishClose`). Until it is scheduled, the
     * kernel keeps the socket in its listen table and keeps accepting
     * connections. Measured standalone on this JDK (21.0.12): a probe connect
     * straight after `close()` succeeded 4/200 times with an acceptor parked in
     * `accept()`, and 0/200 with none — the deferral, not port reuse.
     *
     * That is a product defect, not a test artifact: `PortForward.close()`
     * promises the forward is gone, and callers act on it. Re-opening a forward
     * on the same local port right after closing it (a reconnect, or a user
     * toggling a forward off and on) races a listener that is still bound and
     * fails with "Address already in use", and a local client can still connect
     * to — and then hang on — a forward that was closed.
     *
     * Two oracles, so the fix is pinned by mechanism and by symptom:
     * - the accept thread must be dead when `close()` returns (that unwind IS
     *   the fd release, so joining it is what makes the release ordered);
     * - the port must be immediately re-bindable, which is the user-visible
     *   promise and fails hard with [java.net.BindException] if the descriptor
     *   is still around.
     *
     * Repeated, because the un-fixed defect is a scheduling race: one round is a
     * coin toss, [REBIND_ROUNDS] rounds make the un-fixed code fail essentially
     * every run while the fixed code is deterministic (each round is bounded and
     * blocking-free once close() joins).
     */
    @Test
    fun `close releases the local listener before it returns`() = runBlocking {
        repeat(REBIND_ROUNDS) { round ->
            val channel = PipedTestChannel()
            val forward = newForward(opener = { _, _ -> channel })
            val localPort = forward.localPort
            Socket().use { client ->
                client.connect(java.net.InetSocketAddress("127.0.0.1", localPort), 2_000)
                client.getOutputStream().write("x".toByteArray())
                client.getOutputStream().flush()
                assertTrue(waitUntil { channel.receivedText() == "x" })
                // The acceptor must be parked in accept() when close lands —
                // that is the only state in which the JDK defers the close.
                assertTrue(
                    "round $round: the accept thread must be running before close is meaningful",
                    acceptThreads(localPort).isNotEmpty(),
                )

                forward.close()
            }

            assertEquals(
                "round $round: close() must not return while its accept thread " +
                    "still owns the listening descriptor",
                emptyList<String>(),
                acceptThreads(localPort).map { it.name },
            )
            // The descriptor is really gone: rebinding the same port succeeds.
            val rebound = runCatching {
                ServerSocket(localPort, 1, InetAddress.getByName("127.0.0.1")).close()
            }
            assertTrue(
                "round $round: local port $localPort must be free once close() " +
                    "returned, rebinding failed with ${rebound.exceptionOrNull()}",
                rebound.isSuccess,
            )
        }
    }

    @Test
    fun `concurrent close calls are idempotent and all return`() = runBlocking {
        val channel = PipedTestChannel()
        val forward = newForward(opener = { _, _ -> channel })
        Socket().use { client ->
            client.connect(java.net.InetSocketAddress("127.0.0.1", forward.localPort), 2_000)
            client.getOutputStream().write("x".toByteArray())
            client.getOutputStream().flush()
            assertTrue(waitUntil { channel.receivedText() == "x" })

            (1..8).map { async(Dispatchers.IO) { forward.close() } }.awaitAll()

            assertFalse(forward.isActive)
        }
    }

    @Test
    fun `accepted connections are capped so a client burst cannot spawn unbounded channels`() =
        runBlocking {
            val opened = AtomicInteger(0)
            val release = CountDownLatch(1)
            val forward = newForward(
                opener = { _, _ ->
                    opened.incrementAndGet()
                    BlockingTestChannel(release)
                },
            )
            val clients = mutableListOf<Socket>()
            try {
                repeat(PortForwardImpl.MAX_ACTIVE_CONNECTIONS + 8) {
                    clients += Socket(InetAddress.getByName("127.0.0.1"), forward.localPort)
                }
                assertTrue(
                    "accept loop should fill the connection budget, opened=${opened.get()}",
                    waitUntil { opened.get() >= PortForwardImpl.MAX_ACTIVE_CONNECTIONS },
                )
                // Give the accept loop a window to (wrongly) exceed the cap.
                Thread.sleep(200)
                assertEquals(
                    "connections beyond the budget must be dropped before an SSH channel opens",
                    PortForwardImpl.MAX_ACTIVE_CONNECTIONS,
                    opened.get(),
                )
            } finally {
                release.countDown()
                clients.forEach { runCatching { it.close() } }
                forward.close()
            }
        }

    @Test
    fun `a channel-open failure drops that client without killing the accept loop`() = runBlocking {
        val attempts = AtomicInteger(0)
        val channels = ConcurrentLinkedQueue<PipedTestChannel>()
        val forward = newForward(
            opener = { _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    throw IOException("remote refused the direct-tcpip channel")
                }
                PipedTestChannel().also(channels::add)
            },
        )
        try {
            // First client: the open fails, so its socket is dropped.
            Socket().use { first ->
                first.connect(java.net.InetSocketAddress("127.0.0.1", forward.localPort), 2_000)
                first.soTimeout = 2_000
                assertTrue(waitUntil { attempts.get() >= 1 })
                assertTrue(
                    "a failed channel open must close the local socket",
                    waitUntil { runCatching { first.getInputStream().read() }.getOrDefault(-1) < 0 },
                )
            }

            // Second client on the SAME forward still works — the accept loop survived.
            Socket().use { second ->
                second.connect(java.net.InetSocketAddress("127.0.0.1", forward.localPort), 2_000)
                second.getOutputStream().write("again".toByteArray())
                second.getOutputStream().flush()
                assertTrue(
                    "the accept loop must keep serving after a failed open",
                    waitUntil { channels.firstOrNull()?.receivedText() == "again" },
                )
            }
            assertEquals(2, attempts.get())
        } finally {
            forward.close()
        }
    }

    @Test
    fun `binding a local port already in use fails the open instead of half-starting`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val failure = runCatching {
                PortForwardImpl(
                    channels = { _, _ -> error("never opened") },
                    remoteHost = "127.0.0.1",
                    remotePort = 5432,
                    localPort = squatter.localPort,
                    ioDispatcher = Dispatchers.IO,
                )
            }.exceptionOrNull()
            assertTrue(
                "expected an IOException for an occupied local port, got $failure",
                failure is IOException,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun newForward(opener: ForwardedChannelOpener): PortForwardImpl = PortForwardImpl(
        channels = opener,
        remoteHost = "remote.invalid",
        remotePort = 5432,
        localPort = freeLocalPort(),
        ioDispatcher = Dispatchers.IO,
    )

    private fun freeLocalPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun liveForwardThreads(localPort: Int): List<Thread> =
        liveThreadsNamed(
            "portfwd-l2r-$localPort",
            "portfwd-r2l-$localPort",
        )

    private fun acceptThreads(localPort: Int): List<Thread> =
        liveThreadsNamed("portfwd-accept-$localPort")

    private fun liveThreadsNamed(vararg prefixes: String): List<Thread> {
        val all = arrayOfNulls<Thread>(Thread.activeCount() * 2 + 16)
        val n = Thread.enumerate(all)
        return (0 until n).mapNotNull { all[it] }
            .filter { thread -> thread.isAlive && prefixes.any { thread.name.startsWith(it) } }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    /** Duplex fake channel: a pipe for remote→local, a byte sink for local→remote. */
    private class PipedTestChannel : ForwardedChannel {
        private val toLocal = PipedOutputStream()
        private val sink = ByteArrayOutputStream()

        override val inputStream: InputStream = PipedInputStream(toLocal, 64 * 1024)

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(sink) { sink.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) =
                synchronized(sink) { sink.write(b, off, len) }
        }

        @Volatile
        var isClosed: Boolean = false
            private set

        fun pushToLocal(bytes: ByteArray) {
            toLocal.write(bytes)
            toLocal.flush()
        }

        fun receivedText(): String = synchronized(sink) { sink.toByteArray() }.toString(Charsets.UTF_8)

        override fun close() {
            isClosed = true
            runCatching { toLocal.close() }
            runCatching { inputStream.close() }
        }
    }

    /** Channel whose remote side never speaks until [release] fires. */
    private class BlockingTestChannel(private val release: CountDownLatch) : ForwardedChannel {
        private val closed = AtomicBoolean(false)

        override val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                release.await(10, TimeUnit.SECONDS)
                return -1
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        override fun close() {
            closed.set(true)
        }
    }

    private companion object {
        /**
         * Open/close rounds in `close releases the local listener before it
         * returns`. The un-fixed deferred-close race measured ~2% per round on
         * this JDK, so 200 rounds reddens it with probability ~0.98 — high
         * enough that the regression cannot slip through a CI run, while the
         * fixed code passes deterministically (every round is a bounded,
         * non-sleeping open/close cycle, ~2s for the whole test).
         */
        const val REBIND_ROUNDS = 200
    }
}
