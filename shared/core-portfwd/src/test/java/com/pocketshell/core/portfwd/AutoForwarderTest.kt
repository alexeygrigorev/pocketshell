package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [AutoForwarder] using a fully-faked [SshSession]. The fake
 * lets us script which ports `ss`/`netstat` reports per scan and observe the
 * forwards the AutoForwarder asks for.
 *
 * We launch the scan loop on the [runTest] [kotlinx.coroutines.test.TestScope]
 * (which uses the virtual-time scheduler), so [runCurrent] and
 * [advanceTimeBy] deterministically drive the periodic loop. We use
 * [runCurrent] rather than `advanceUntilIdle` because the scan loop is
 * intentionally unbounded — `advanceUntilIdle` would loop forever advancing
 * virtual time through each `delay(scanIntervalSec)` tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoForwarderTest {

    @Test
    fun `first scan opens a forward for an in-window remote port`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertEquals(1, snapshot.size)
        val t = snapshot.single()
        assertEquals(3000, t.remotePort)
        assertEquals(3000, t.localPort) // in-window → mirrored locally
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals("app", t.process)
        assertEquals(1, session.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `port below skipPortsBelow is not forwarded but is reported AVAILABLE`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertEquals(1, snapshot.size)
        val t = snapshot.single()
        assertEquals(22, t.remotePort)
        assertEquals(TunnelInfo.Status.AVAILABLE, t.status)
        assertEquals("sshd", t.process)
        assertTrue("no forwards should have been requested", session.openForwards.isEmpty())

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `port disappearing between scans tears down its forward`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        assertEquals(1, forwarder.flowOfTunnels().first().size)

        // Service stops; next scan should observe it gone and tear the
        // local forward down.
        session.setListening("")
        advanceTimeBy(2_000L)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertTrue(
            "forward should be torn down once remote port disappears, got $snapshot",
            snapshot.none { it.status == TunnelInfo.Status.FORWARDING },
        )
        val firstForward = session.openForwards.values.first()
        assertFalse("underlying forward should have been closed", firstForward.isActive)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `togglePort manually forces a forward for an out-of-window port`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        // sshd on 22 isn't auto-forwarded because it's below skipPortsBelow.
        assertEquals(
            TunnelInfo.Status.AVAILABLE,
            forwarder.flowOfTunnels().first().single().status,
        )

        forwarder.togglePort(22)

        val snapshot = forwarder.flowOfTunnels().first()
        val t = snapshot.single { it.remotePort == 22 }
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        // 22 is below the auto-forward window so the allocator should have
        // handed out a port from localPortRange (not mirrored).
        assertTrue(
            "manually-forwarded port should get a localPortRange allocation, got ${t.localPort}",
            t.localPort in smallConfig().localPortRange,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `stop tears down every open forward`() = runTest {
        val session = FakeSession()
        session.setListening(
            """
            0.0.0.0:3000 users:(("a",pid=1,fd=4))
            0.0.0.0:4000 users:(("b",pid=1,fd=4))
            """.trimIndent(),
        )

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        assertEquals(2, session.openForwards.size)

        forwarder.stop()

        assertTrue(
            "all forwards should have been closed by stop()",
            session.openForwards.values.all { !it.isActive },
        )
        assertEquals(0, forwarder.flowOfTunnels().first().size)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `start is idempotent and returns the same job`() = runTest {
        val session = FakeSession()
        val forwarder = AutoForwarder(session, smallConfig())
        val first = forwarder.start(this)
        val second = forwarder.start(this)
        assertEquals(first, second)
        forwarder.stop()
        runCurrent()
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val session = FakeSession()
        val forwarder = AutoForwarder(session, smallConfig())
        forwarder.stop()
        forwarder.stop() // should not throw
    }

    @Test
    fun `start after stop is a no-op`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(session, smallConfig())
        forwarder.stop()

        val job = forwarder.start(this)
        runCurrent()
        assertTrue("stopped forwarder must not scan again", session.openForwards.isEmpty())
        job.cancel()
        runCurrent()
    }

    @Test
    fun `byte counts and speed flow through to TunnelInfo`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        // Simulate traffic on the forward between scans.
        val forward = session.openForwards.values.single() as FakeForward
        forward.bytesForwardedAtomic.set(1_000)
        forward.bytesReceivedAtomic.set(500)

        // Trigger exactly ONE more scan tick. scanIntervalSec=1, so we move
        // virtual time just past the delay boundary; advancing further would
        // fire a third iteration which would reset speedBps back to 0
        // (no new bytes between iter 2 and iter 3).
        advanceTimeBy(1_100L)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single()
        assertEquals(1_000L, t.bytesIn)
        assertEquals(500L, t.bytesOut)
        assertEquals(
            // (1_000 + 500) / scanIntervalSec=1 == 1500 bps
            1_500L,
            t.speedBps,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    private fun smallConfig() = AutoForwardConfig(
        scanIntervalSec = 1,
        maxAutoPort = 5_000,
        skipPortsBelow = 1024,
        localPortRange = 3_500..3_600,
    )

    /** Fake SSH session that lets a test script `ss`/`netstat` output. */
    private class FakeSession : SshSession {
        @Volatile private var output: String = ""
        val openForwards: MutableMap<Int, FakeForward> = mutableMapOf()
        private val nextChannelId = AtomicInteger(0)

        fun setListening(ssOutput: String) {
            output = ssOutput
        }

        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult {
            // Primary `ss -tlnp` returns our scripted output; we don't model
            // netstat / last-resort here — the dedicated PortScannerTest
            // covers the fallback chain.
            return if (command.startsWith("ss -tlnp")) {
                ExecResult(output, "", exitCode = 0)
            } else {
                ExecResult("", "", exitCode = 0)
            }
        }
        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used by AutoForwarder tests")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            val f = FakeForward(remoteHost, remotePort, localPort, nextChannelId.incrementAndGet())
            openForwards[remotePort] = f
            return f
        }
        override fun close() = Unit
    }

    private class FakeForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
        @Suppress("unused") val channelId: Int,
    ) : SshPortForward {
        val bytesForwardedAtomic = AtomicLong(0)
        val bytesReceivedAtomic = AtomicLong(0)
        @Volatile private var open = true
        override val isActive: Boolean get() = open
        override val bytesForwarded: Long get() = bytesForwardedAtomic.get()
        override val bytesReceived: Long get() = bytesReceivedAtomic.get()
        override fun close() { open = false }
    }
}
