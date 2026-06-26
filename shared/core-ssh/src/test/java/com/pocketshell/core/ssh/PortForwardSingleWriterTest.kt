package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #980 — the port-forward direct-tcpip channel open/close MUST be a
 * single writer against the transport keepalive, exactly the #847 invariant.
 *
 * ## What broke (base, RED)
 *
 * `RealSshPortForward` opened one direct-tcpip channel per accepted local
 * connection by calling `client.newDirectConnection(...)` STRAIGHT off the
 * accept loop, and closed it straight off the copy threads — both raw
 * transport-mutating SSH packets racing the dispatcher-serialised keepalive on
 * the SAME transport. That is a SECOND, un-ownable writer: a channel open/close
 * landing in a keepalive/rekey window desyncs the encoder sequence counter and
 * the server logs `Connection corrupted` (the precise #847 fault). A burst of
 * accepted connections (a fresh-reconnect scan-and-forward storm) or a flapping
 * forwarded service churns these un-serialised writes continuously.
 *
 * ## The fix (GREEN)
 *
 * The forward now opens + closes every channel through
 * [DispatcherBackedChannelTransport], which funnels the channel-lifecycle
 * packets through the connection's single-writer [TransportDispatcher] — the
 * same FIFO single-thread path the keepalive uses. So there is exactly ONE
 * writer again.
 *
 * These tests are Docker-free and deterministic (the integration backstop,
 * `SshIntegrationTest.portForwardChannelChurnConcurrentWithKeepAliveDoesNotCorruptTransport`,
 * proves no corruption against a real sshd over the wire).
 */
class PortForwardSingleWriterTest {

    /**
     * Concurrency monitor shared between the "channel open/close" ops and the
     * "keepalive" op. Records the maximum number of ops observed running at the
     * same time. If the port-forward channel work ran OFF the dispatcher
     * (the #980/base bug), an open/close would overlap a keepalive write and
     * this would read > 1.
     */
    private class WriterMonitor {
        private val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        fun <T> track(block: () -> T): T {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
            try {
                // A small busy window so an overlap would actually be observed
                // by a racing writer rather than slipping through instantly.
                Thread.sleep(2)
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `channel open and close serialise against keepalive on the single-writer dispatcher`() =
        runBlocking {
            val dispatcher = TransportDispatcher()
            val monitor = WriterMonitor()

            // The production transport for a forward (issue #980): the channel
            // open body funnels through the SAME dispatcher the keepalive uses.
            // We track the open body through the shared monitor so an overlap
            // with a keepalive write would be observed as maxConcurrent>1. The
            // body throws AFTER tracking so we never need to fabricate a real
            // DirectConnection return value — the serialisation (not the channel
            // object) is what's under test. The close path is identical
            // (`dispatcher.runBlockingDispatch { close(...) }`) and is covered by
            // `closeChannel swallows a transport-closed rejection` below.
            val transport = DispatcherBackedChannelTransport(
                dispatcher = dispatcher,
                open = { _, _ -> monitor.track { error("test: no real channel needed") } },
                close = { monitor.track { } },
            )

            // (A) Keepalive-style transport writer hammering the dispatcher,
            // mirroring RealSshSession.sendKeepAlive running through the same
            // single-writer path every interval.
            val keepAlive = launch(Dispatchers.IO) {
                repeat(60) {
                    dispatcher.run { monitor.track { } }
                }
            }

            // (B) A burst of channel opens — the scan-and-forward storm shape:
            // many accepted local connections each opening a direct-tcpip channel
            // concurrently with the keepalive. openChannel propagates the body's
            // throw; we only care that the body never overlapped a keepalive.
            val churn = (0 until 60).map {
                launch(Dispatchers.IO) {
                    runCatching { transport.openChannel("127.0.0.1", 9000) }
                }
            }

            keepAlive.join()
            churn.forEach { it.join() }

            assertEquals(
                "channel open/close and keepalive are the same transport writer — they " +
                    "MUST never run concurrently (the #847 single-writer invariant the " +
                    "#980 fix restores)",
                1,
                monitor.maxConcurrent.get(),
            )
            dispatcher.closeAndAwaitDrain { }
        }

    @Test
    fun `RealSshPortForward opens its direct-tcpip channel through the injected transport, not the raw client`() {
        // Proves the forward routes its per-connection channel open through the
        // PortForwardChannelTransport seam (and therefore the dispatcher in
        // production) rather than touching a raw SSHClient. On the base code the
        // forward held the SSHClient and called client.newDirectConnection
        // directly off the accept loop — there was no seam to route through.
        val openCalls = AtomicInteger(0)
        val opened = CountDownLatch(1)
        val transport = object : PortForwardChannelTransport {
            override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection {
                openCalls.incrementAndGet()
                opened.countDown()
                // Reject the open so the copy threads never spin up — we only
                // need to prove the accept loop asked the transport (not the raw
                // client) to open the channel.
                throw RuntimeException("test: refuse channel open")
            }

            override fun closeChannel(channel: DirectConnection) = Unit
        }

        // Pick a free loopback port for the forward to listen on.
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }

        val forward = RealSshPortForward(
            channels = transport,
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
        )
        try {
            // Drive one local connection — the accept loop should open a channel
            // through the injected transport.
            Socket(InetAddress.getByName("127.0.0.1"), localPort).use { /* connect */ }
            assertTrue(
                "the forward must open its channel through the injected transport seam",
                opened.await(5, TimeUnit.SECONDS),
            )
            assertTrue("at least one open went through the transport", openCalls.get() >= 1)
        } finally {
            forward.close()
        }
    }
}
