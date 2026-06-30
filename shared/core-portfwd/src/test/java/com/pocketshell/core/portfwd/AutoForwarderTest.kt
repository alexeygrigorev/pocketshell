package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `mirror port walks N N+1 N+2 until a free local port is found`() = runTest {
        // #602 ask 4: forwarding remote N defaults local N, but when N and the
        // next two candidates are busy the forwarder must keep walking upward and
        // surface the ACTUAL chosen local port (no failure, no broken mapping).
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(3000, 3001, 3002)
        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            localPortAvailability = LocalPortAvailability { port -> port !in occupied },
        )
        val job = forwarder.start(this)
        runCurrent()

        val tunnel = forwarder.flowOfTunnels().first().single()
        assertEquals(3000, tunnel.remotePort)
        assertEquals("local port should walk past the three busy candidates", 3003, tunnel.localPort)
        assertEquals(TunnelInfo.Status.FORWARDING, tunnel.status)
        assertEquals(3003, session.openForwards.values.single().localPort)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `in-window mirror port increments when requested local port is occupied`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(3000)
        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            localPortAvailability = LocalPortAvailability { port -> port !in occupied },
        )
        val job = forwarder.start(this)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        val tunnel = snapshot.single()
        assertEquals(3000, tunnel.remotePort)
        assertEquals(3001, tunnel.localPort)
        assertEquals(TunnelInfo.Status.FORWARDING, tunnel.status)
        assertEquals(3001, session.openForwards.values.single().localPort)

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

        // stop() now closes forwards OFF the caller thread (on the teardown
        // dispatcher) so a wedged SSH socket can't freeze the UI — see
        // `stop() does not block the caller when a forward's close hangs`.
        // The teardown still happens; we just await it rather than asserting
        // it ran inline.
        assertTrue(
            "all forwards should have been closed by stop()",
            waitUntilReal(2_000L) { session.openForwards.values.all { !it.isActive } },
        )
        assertEquals(0, forwarder.flowOfTunnels().first().size)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `stop() does not block the caller when a forwards close hangs`() {
        // #1085 freeze-hunt F-E: stop() is reached on the Android Main thread
        // (panel auto-forward toggle-off / foreground-service ACTION_STOP),
        // and a forward's close() drives an SSH channel-teardown packet that
        // can block on a wedged socket. Before the fix stop() closed forwards
        // inline on the caller's thread, so a hung close froze the caller.
        // This test holds a forward's close() open and asserts stop() returns
        // promptly anyway, while still confirming the close is dispatched
        // (teardown not dropped). RED on base (stop() blocks until the latch
        // releases), GREEN with the off-thread teardown.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        session.blockForwardClose(closeEntered, closeRelease)

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val loopScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        // Default teardownDispatcher = Dispatchers.IO, so the hung close runs
        // off the caller thread — exactly the production wiring.
        val forwarder = AutoForwarder(session, smallConfig())
        try {
            forwarder.start(loopScope)
            assertTrue(
                "a forward should have been opened by the first scan",
                waitUntilReal(2_000L) { session.openForwards.values.any { it.isActive } },
            )

            val stopThread = Thread { forwarder.stop() }
            stopThread.start()
            stopThread.join(2_000L)
            assertFalse(
                "stop() must return without blocking on the hung forward close",
                stopThread.isAlive,
            )
            assertTrue(
                "the forward's close() must still be invoked (teardown not dropped)",
                closeEntered.await(2, TimeUnit.SECONDS),
            )
        } finally {
            closeRelease.countDown()
            loopScope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop closes a forward that finishes opening after stop`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        session.blockNextOpen(openEntered, releaseOpen)

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        val forwarder = AutoForwarder(session, smallConfig())
        try {
            val job = forwarder.start(scope)
            assertTrue(
                "openLocalPortForward should be in flight",
                openEntered.await(2, TimeUnit.SECONDS),
            )

            forwarder.stop()
            releaseOpen.countDown()
            assertTrue(
                "late-opened forward must be closed by stop()",
                waitUntilReal(2_000L) {
                    session.completedOpenAttempts.get() >= 1 &&
                        session.openForwards.values.all { !it.isActive }
                },
            )
            job.cancel()

            assertTrue(
                "stopped forwarder must not publish a late FORWARDING row",
                forwarder.flowOfTunnels().first().isEmpty(),
            )
        } finally {
            releaseOpen.countDown()
            forwarder.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
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

    @Test
    fun `failed port is retried after TTL elapses`() = runTest {
        // Reject the first openLocalPortForward call, accept subsequent
        // ones. That lands the port on failedPorts after the first scan.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        session.failNextOpens(1)

        // Drive our own clock so we can step it past the TTL without
        // also stepping the coroutine virtual clock (which would fire
        // more scan ticks than we want).
        val now = AtomicLong(0L)
        val ttl = 5_000L
        val forwarder = AutoForwarder(
            session,
            smallConfig().copy(failedPortTtlMs = ttl),
            clock = { now.get() },
        )
        val job = forwarder.start(this)
        runCurrent()

        // After the first scan the port should be on the deny-list, status FAILED.
        val afterFail = forwarder.flowOfTunnels().first()
        assertEquals(1, afterFail.size)
        assertEquals(TunnelInfo.Status.FAILED, afterFail.single().status)
        assertTrue("forward should NOT have been opened on the first scan", session.openForwards.isEmpty())

        // Advance the scan-loop clock to the next tick but stay inside
        // the TTL: the port must still be denied.
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(
            "deny-list entry is still within TTL — must not retry",
            session.openForwards.isEmpty(),
        )
        assertEquals(
            TunnelInfo.Status.FAILED,
            forwarder.flowOfTunnels().first().single().status,
        )

        // Step the wall clock past the TTL. Next scan should evict
        // the entry and successfully open the forward.
        now.set(ttl + 1)
        advanceTimeBy(1_100L)
        runCurrent()

        val afterTtl = forwarder.flowOfTunnels().first()
        assertEquals(1, afterTtl.size)
        assertEquals(
            "TTL elapsed — port must be retried and forwarded",
            TunnelInfo.Status.FORWARDING,
            afterTtl.single().status,
        )
        assertEquals(1, session.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `failed port still denied within TTL`() = runTest {
        // Hardcoded clock returns 0 forever so TTL never elapses.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        session.failAllOpens()

        val forwarder = AutoForwarder(
            session,
            smallConfig().copy(failedPortTtlMs = 60_000L),
            clock = { 0L },
        )
        val job = forwarder.start(this)
        runCurrent()
        // Advance several scan ticks; entry stays on the deny-list.
        advanceTimeBy(5_500L)
        runCurrent()

        assertTrue(
            "all opens must have failed; forward count is the number of *successful* opens",
            session.openForwards.isEmpty(),
        )
        // openLocalPortForward was called at least once (first scan); the
        // TTL guard then suppresses further attempts.
        assertTrue(
            "openLocalPortForward must have been called at least once (initial attempt)",
            session.totalOpenAttempts.get() >= 1,
        )
        // ...but NOT once per scan tick — the TTL keeps it on the deny-list.
        // Six ticks elapsed (1 initial + 5 from 5.5 s @ 1 s); the deny-list
        // must hold so we see strictly fewer attempts than scans.
        assertTrue(
            "TTL must suppress retries — got ${session.totalOpenAttempts.get()} attempts",
            session.totalOpenAttempts.get() < 6,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `allocator throws when localPortRange is exhausted`() = runTest {
        // Local port range of size 2; we'll manually toggle three
        // out-of-window ports so the third forces the allocator to walk
        // the whole range and trip the fail-fast guard.
        val session = FakeSession()
        // No listening ports — we use togglePort to force forwards for
        // out-of-window remote ports (22, 23, 25 are all < skipPortsBelow).
        session.setListening("")

        val forwarder = AutoForwarder(
            session,
            AutoForwardConfig(
                scanIntervalSec = 1,
                maxAutoPort = 5_000,
                skipPortsBelow = 1024,
                // Two-slot range. After two manual toggles every slot is
                // taken, so the third must throw.
                localPortRange = 3_500..3_501,
            ),
        )
        val job = forwarder.start(this)
        runCurrent()

        forwarder.togglePort(22)
        forwarder.togglePort(23)
        // First two should have succeeded — each got its own slot.
        assertEquals(2, session.openForwards.size)

        // Third toggle — allocator must throw. AutoForwarder catches it
        // inside forwardPortLocked and memos remote port 25 on
        // failedPorts. From the outside we observe: no new forward
        // opened, port 25 ends up in the FAILED set.
        forwarder.togglePort(25)
        runCurrent()

        assertEquals(
            "third manual toggle must not open a forward — range is exhausted",
            2,
            session.openForwards.size,
        )
        val snapshot = forwarder.flowOfTunnels().first()
        val twentyFive = snapshot.singleOrNull { it.remotePort == 25 }
        assertTrue(
            "port 25 should appear in the snapshot as FAILED, got $snapshot",
            twentyFive != null && twentyFive.status == TunnelInfo.Status.FAILED,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialRemappings override mirror allocation for an in-window port`() = runTest {
        // Issue #203 expanded scope: a persisted remapping must override
        // the natural "mirror remote port onto same local port" rule.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            initialRemappings = mapOf(3000 to 9000),
            localPortAvailability = LocalPortAvailability { true },
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single()
        assertEquals(3000, t.remotePort)
        // 3000 is inside the auto-forward window so the default would
        // be to mirror it to 3000 locally. The remap entry must win.
        assertEquals(9000, t.localPort)
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(
            "openLocalPortForward should have been called with the remapped local port",
            9000,
            session.openForwards.values.single().localPort,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialRemappings increment when requested local port is occupied`() = runTest {
        // #602: remapped ports still use the conflict-aware allocator. If the
        // requested local port is busy, the tunnel should open on the next
        // available local port and surface that actual mapping.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(9000, 9001)
        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            initialRemappings = mapOf(3000 to 9000),
            localPortAvailability = LocalPortAvailability { port -> port !in occupied },
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single()
        assertEquals(3000, t.remotePort)
        assertEquals(9002, t.localPort)
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(
            "openLocalPortForward should have been called with the incremented local port",
            9002,
            session.openForwards.values.single().localPort,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialRemappings override allocator for an out-of-window port`() = runTest {
        // sshd on port 22 is normally below skipPortsBelow, so the
        // allocator would hand it a port from localPortRange when
        // manually toggled. A persisted remap entry must take priority.
        val session = FakeSession()
        session.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            initialRemappings = mapOf(22 to 2222),
            localPortAvailability = LocalPortAvailability { true },
        )
        val job = forwarder.start(this)
        runCurrent()
        // First scan reports sshd AVAILABLE (out of auto-forward window).
        assertEquals(
            TunnelInfo.Status.AVAILABLE,
            forwarder.flowOfTunnels().first().single().status,
        )

        forwarder.togglePort(22)

        val snapshot = forwarder.flowOfTunnels().first()
        val sshd = snapshot.single { it.remotePort == 22 }
        assertEquals(TunnelInfo.Status.FORWARDING, sshd.status)
        // The remap entry must override the allocator's localPortRange
        // pick (which would otherwise land in 3_500..3_600).
        assertEquals(2222, sshd.localPort)
        assertEquals(
            "openLocalPortForward should have been called with the remapped local port",
            2222,
            session.openForwards.values.single().localPort,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `empty initialRemappings preserves default mirroring behaviour`() = runTest {
        // Regression check: existing callers that don't supply a
        // remappings map must continue to get the mirror behaviour.
        val session = FakeSession()
        session.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single()
        assertEquals(3000, t.localPort)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialManualPorts re-forwards an out-of-window port on first scan`() = runTest {
        // Issue #439: the supervisor seeds a fresh forwarder with the
        // user's desired manual ports after a reconnect. :22 is below the
        // auto window and the only way it comes up is via the seeded set.
        val session = FakeSession()
        session.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            initialManualPorts = setOf(22),
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single { it.remotePort == 22 }
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(1, session.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialManualPorts re-forwards a port that is not currently listening`() = runTest {
        // The desired port may briefly not be listening right after a
        // reconnect (server restarting). The seeded set still re-opens it.
        val session = FakeSession() // nothing listening
        val forwarder = AutoForwarder(
            session,
            smallConfig(),
            initialManualPorts = setOf(8080),
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single { it.remotePort == 8080 }
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(1, session.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `ensurePort enable then disable is idempotent and absolute`() = runTest {
        val session = FakeSession()
        session.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")
        val forwarder = AutoForwarder(session, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        forwarder.ensurePort(22, enabled = true)
        forwarder.ensurePort(22, enabled = true) // idempotent — no dup
        assertEquals(1, session.openForwards.count { it.value.remotePort == 22 })
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            forwarder.flowOfTunnels().first().single { it.remotePort == 22 }.status,
        )

        forwarder.ensurePort(22, enabled = false)
        assertTrue(
            "ensurePort(false) must tear down the forward",
            forwarder.flowOfTunnels().first()
                .none { it.remotePort == 22 && it.status == TunnelInfo.Status.FORWARDING },
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

    private fun waitUntilReal(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    /** Fake SSH session that lets a test script `ss`/`netstat` output. */
    private class FakeSession : SshSession {
        @Volatile private var output: String = ""
        val openForwards: MutableMap<Int, FakeForward> = mutableMapOf()
        val totalOpenAttempts = AtomicInteger(0)
        val completedOpenAttempts = AtomicInteger(0)
        private val failuresRemaining = AtomicInteger(0)
        private val failForever = AtomicBoolean(false)
        private val nextChannelId = AtomicInteger(0)
        @Volatile private var blockedOpen: Pair<CountDownLatch, CountDownLatch>? = null
        @Volatile private var blockedClose: Pair<CountDownLatch, CountDownLatch>? = null

        fun setListening(ssOutput: String) {
            output = ssOutput
        }

        fun blockNextOpen(entered: CountDownLatch, release: CountDownLatch) {
            blockedOpen = entered to release
        }

        /**
         * Make every forward produced by [openLocalPortForward] block inside
         * its `close()` — it counts down [entered] and waits on [release].
         * Lets a test hold an SSH-channel teardown open while asserting the
         * caller of `stop()` is not blocked (#1085 F-E).
         */
        fun blockForwardClose(entered: CountDownLatch, release: CountDownLatch) {
            blockedClose = entered to release
        }

        /** Make the next [n] openLocalPortForward calls throw. */
        fun failNextOpens(n: Int) {
            failuresRemaining.set(n)
        }

        /** Make every openLocalPortForward call throw. */
        fun failAllOpens() {
            failForever.set(true)
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
        override fun startShell(): com.pocketshell.core.ssh.SshShell =
            error("startShell not used by AutoForwarder tests")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            totalOpenAttempts.incrementAndGet()
            blockedOpen?.let { (entered, release) ->
                blockedOpen = null
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            if (failForever.get()) {
                throw RuntimeException("fake open failure (forever)")
            }
            if (failuresRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                throw RuntimeException("fake open failure (countdown)")
            }
            val f = FakeForward(
                remoteHost,
                remotePort,
                localPort,
                nextChannelId.incrementAndGet(),
                closeBlock = blockedClose,
            )
            openForwards[remotePort] = f
            completedOpenAttempts.incrementAndGet()
            return f
        }
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used by AutoForwarder tests")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used by AutoForwarder tests")
        override fun close() = Unit
    }

    private class FakeForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
        @Suppress("unused") val channelId: Int,
        private val closeBlock: Pair<CountDownLatch, CountDownLatch>? = null,
    ) : SshPortForward {
        val bytesForwardedAtomic = AtomicLong(0)
        val bytesReceivedAtomic = AtomicLong(0)
        @Volatile private var open = true
        override val isActive: Boolean get() = open
        override val bytesForwarded: Long get() = bytesForwardedAtomic.get()
        override val bytesReceived: Long get() = bytesReceivedAtomic.get()
        override fun close() {
            // Model a wedged SSH channel teardown: signal we entered close,
            // then block until released (or interrupted by the bounded
            // teardown timeout). Mirrors RealSshPortForward.close() funnelling
            // its channel-close packet through a transport that can stall.
            closeBlock?.let { (entered, release) ->
                entered.countDown()
                try {
                    release.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            open = false
        }
    }
}
