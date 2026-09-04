package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.PortForward
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * Unit tests for [AutoForwarder] over a faked [HostConnection]. The fake wraps
 * core-transport's scripted [FakeHostConnection] and adds only what these tests
 * need on top of it: scripted `ss`/`netstat` output per scan, observable
 * forwards, and the two misbehaviours the teardown paths care about (an open
 * that hangs, a close that hangs).
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
    private val allLocalPortsAvailable = LocalPortAvailability { true }

    @Test
    fun `injected availability isolates allocation from a real occupied port`() = runTest {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { holder ->
            val occupiedPort = holder.localPort
            val connection = FakeConnection()
            connection.setListening("0.0.0.0:$occupiedPort users:((\"app\",pid=1,fd=4))")
            val availabilityQueries = mutableListOf<Int>()

            val forwarder = AutoForwarder(
                connection,
                smallConfig().copy(maxAutoPort = 65_535),
                localPortAvailability = LocalPortAvailability { port ->
                    availabilityQueries += port
                    true
                },
            )
            val job = forwarder.start(this)
            runCurrent()

            val tunnel = forwarder.flowOfTunnels().first().single()
            assertEquals(occupiedPort, tunnel.remotePort)
            assertEquals(occupiedPort, tunnel.localPort)
            assertEquals(listOf(occupiedPort), availabilityQueries)

            forwarder.stop()
            job.cancel()
            runCurrent()
        }
    }

    @Test
    fun `default availability rejects a controlled occupied ephemeral port`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { holder ->
            assertFalse(
                "production availability probe must reject the held port ${holder.localPort}",
                DefaultLocalPortAvailability.isAvailable(holder.localPort),
            )
        }
    }

    @Test
    fun `first scan opens a forward for an in-window remote port`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            localPortAvailability = allLocalPortsAvailable,
        )
        val job = forwarder.start(this)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertEquals(1, snapshot.size)
        val t = snapshot.single()
        assertEquals(3000, t.remotePort)
        assertEquals(3000, t.localPort) // in-window → mirrored locally
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals("app", t.process)
        assertEquals(1, connection.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `mirror port walks N N+1 N+2 until a free local port is found`() = runTest {
        // #602 ask 4: forwarding remote N defaults local N, but when N and the
        // next two candidates are busy the forwarder must keep walking upward and
        // surface the ACTUAL chosen local port (no failure, no broken mapping).
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(3000, 3001, 3002)
        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            localPortAvailability = LocalPortAvailability { port -> port !in occupied },
        )
        val job = forwarder.start(this)
        runCurrent()

        val tunnel = forwarder.flowOfTunnels().first().single()
        assertEquals(3000, tunnel.remotePort)
        assertEquals("local port should walk past the three busy candidates", 3003, tunnel.localPort)
        assertEquals(TunnelInfo.Status.FORWARDING, tunnel.status)
        assertEquals(3003, connection.openForwards.values.single().localPort)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `in-window mirror port increments when requested local port is occupied`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(3000)
        val forwarder = AutoForwarder(
            connection,
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
        assertEquals(3001, connection.openForwards.values.single().localPort)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `port below skipPortsBelow is not forwarded but is reported AVAILABLE`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(connection, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertEquals(1, snapshot.size)
        val t = snapshot.single()
        assertEquals(22, t.remotePort)
        assertEquals(TunnelInfo.Status.AVAILABLE, t.status)
        assertEquals("sshd", t.process)
        assertTrue("no forwards should have been requested", connection.openForwards.isEmpty())

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `port disappearing between scans tears down its forward`() = runTest {
        val connection = FakeConnection()
        // sshd is always listening on a host we can scan at all, so a
        // *successful* scan that no longer lists :3000 still lists :22. The
        // fixture keeps it that way so this exercises a genuine vanish rather
        // than the "scan produced nothing" case (issue #2489), which is a
        // failure, not an observation.
        connection.setListening(
            """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:3000 users:(("app",pid=1,fd=4))
            """.trimIndent(),
        )

        val forwarder = AutoForwarder(connection, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        assertEquals(1, connection.openForwards.size)

        // Service stops; next scan should observe it gone and tear the
        // local forward down.
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")
        advanceTimeBy(2_000L)
        runCurrent()

        val snapshot = forwarder.flowOfTunnels().first()
        assertTrue(
            "forward should be torn down once remote port disappears, got $snapshot",
            snapshot.none { it.status == TunnelInfo.Status.FORWARDING },
        )
        val firstForward = connection.openForwards.values.first()
        assertFalse("underlying forward should have been closed", firstForward.isActive)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `a failed scan does not tear down existing forwards`() = runTest {
        // Issue #2489 (bug 1): PortScanner reports "nothing found" when every
        // strategy fails — an exec error, a dead-ish shell, a hiccup on the
        // transport. Reading that as "every remote port vanished" closed every
        // auto-forwarded tunnel and reset the TCP connections running through
        // them, on ONE transient scan failure. RED on base: the forward is
        // closed by the next tick.
        val connection = FakeConnection()
        connection.setListening(
            """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:3000 users:(("app",pid=1,fd=4))
            """.trimIndent(),
        )

        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            localPortAvailability = allLocalPortsAvailable,
        )
        val job = forwarder.start(this)
        runCurrent()
        val forward = connection.openForwards.values.single()
        assertTrue("first scan should have opened the :3000 forward", forward.isActive)

        connection.failScan()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(
            "a failed scan must not close a live forward — nothing was observed to vanish",
            forward.isActive,
        )
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            forwarder.flowOfTunnels().first().single { it.remotePort == 3000 }.status,
        )

        // ...and the tunnel comes back to a healthy scan untouched: no
        // close/reopen churn, the same forward object is still in place.
        connection.setListening(
            """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:3000 users:(("app",pid=1,fd=4))
            """.trimIndent(),
        )
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, connection.openForwards.size)
        assertTrue("the forward should have survived the failed scan", forward.isActive)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `a timed-out scan is not treated as a shorter port list`() = runTest {
        // Issue #2489 (bug 2): exec does not throw on a wall-clock overrun —
        // it returns the partial stdout with timedOut=true. Parsing that
        // truncated `ss` listing made every port missing from the cut-off
        // output look vanished. RED on base: the :4000 forward, absent from
        // the truncated listing, is closed.
        val connection = FakeConnection()
        connection.setListening(
            """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:3000 users:(("app",pid=1,fd=4))
            0.0.0.0:4000 users:(("db",pid=2,fd=5))
            """.trimIndent(),
        )

        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            localPortAvailability = allLocalPortsAvailable,
        )
        val job = forwarder.start(this)
        runCurrent()
        assertEquals(2, connection.openForwards.size)
        val truncatedAway = connection.openForwards.getValue(4000)

        // The next scan is cut off mid-listing: :4000 never made it to stdout.
        connection.timeOutScan(
            """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:3000 users:(("app",pid=1,fd=4))
            """.trimIndent(),
        )
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(
            "a timed-out scan is truncated output, not evidence :4000 vanished",
            truncatedAway.isActive,
        )
        val snapshot = forwarder.flowOfTunnels().first()
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            snapshot.single { it.remotePort == 4000 }.status,
        )
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            snapshot.single { it.remotePort == 3000 }.status,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `togglePort manually forces a forward for an out-of-window port`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(connection, smallConfig())
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
        val connection = FakeConnection()
        connection.setListening(
            """
            0.0.0.0:3000 users:(("a",pid=1,fd=4))
            0.0.0.0:4000 users:(("b",pid=1,fd=4))
            """.trimIndent(),
        )

        val forwarder = AutoForwarder(connection, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        assertEquals(2, connection.openForwards.size)

        forwarder.stop()

        // stop() now closes forwards OFF the caller thread (on the teardown
        // dispatcher) so a wedged SSH socket can't freeze the UI — see
        // `stop() does not block the caller when a forward's close hangs`.
        // The teardown still happens; we just await it rather than asserting
        // it ran inline.
        assertTrue(
            "all forwards should have been closed by stop()",
            waitUntilReal(2_000L) { connection.openForwards.values.all { !it.isActive } },
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        connection.blockForwardClose(closeEntered, closeRelease)

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val loopScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        // Default teardownDispatcher = Dispatchers.IO, so the hung close runs
        // off the caller thread — exactly the production wiring.
        val forwarder = AutoForwarder(connection, smallConfig())
        try {
            forwarder.start(loopScope)
            assertTrue(
                "a forward should have been opened by the first scan",
                waitUntilReal(2_000L) { connection.openForwards.values.any { it.isActive } },
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        connection.blockNextOpen(openEntered, releaseOpen)

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        val forwarder = AutoForwarder(connection, smallConfig())
        try {
            val job = forwarder.start(scope)
            assertTrue(
                "openPortForward should be in flight",
                openEntered.await(2, TimeUnit.SECONDS),
            )

            forwarder.stop()
            releaseOpen.countDown()
            assertTrue(
                "late-opened forward must be closed by stop()",
                waitUntilReal(2_000L) {
                    connection.completedOpenAttempts.get() >= 1 &&
                        connection.openForwards.values.all { !it.isActive }
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
        val connection = FakeConnection()
        val forwarder = AutoForwarder(connection, smallConfig())
        val first = forwarder.start(this)
        val second = forwarder.start(this)
        assertEquals(first, second)
        forwarder.stop()
        runCurrent()
    }

    @Test(timeout = 5_000)
    fun `concurrent starts publish one shared loop job`() {
        val connection = FakeConnection()
        val forwarder = AutoForwarder(connection, smallConfig())
        val executor = Executors.newFixedThreadPool(2)
        val releaseDispatch = CountDownLatch(1)
        val firstDispatchEntered = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                firstDispatchEntered.countDown()
                check(releaseDispatch.await(2, TimeUnit.SECONDS)) {
                    "test dispatcher was not released"
                }
                executor.execute(block)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val first = AtomicReference<Job?>()
        val second = AtomicReference<Job?>()
        val ready = java.util.concurrent.CyclicBarrier(3)
        val firstThread = Thread {
            ready.await()
            first.set(forwarder.start(scope))
        }
        val secondThread = Thread {
            ready.await()
            second.set(forwarder.start(scope))
        }

        try {
            firstThread.start()
            secondThread.start()
            ready.await()
            assertTrue(
                "at least one start must reach the launch publication window",
                firstDispatchEntered.await(2, TimeUnit.SECONDS),
            )
            releaseDispatch.countDown()
            firstThread.join(2_000L)
            secondThread.join(2_000L)

            assertFalse("first start thread must finish", firstThread.isAlive)
            assertFalse("second start thread must finish", secondThread.isAlive)
            assertEquals(
                "concurrent starts must publish and return one loop job",
                first.get(),
                second.get(),
            )
        } finally {
            releaseDispatch.countDown()
            scope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 5_000)
    fun `stop racing loop publication cancels the startup job`() {
        val connection = FakeConnection()
        lateinit var forwarder: AutoForwarder
        val executor = Executors.newSingleThreadExecutor()
        val releaseBody = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                // This is the check-then-act window: start() has created the
                // coroutine but has not published its Job yet.
                forwarder.stop()
                executor.execute {
                    check(releaseBody.await(2, TimeUnit.SECONDS)) {
                        "test dispatcher was not released"
                    }
                    block.run()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        forwarder = AutoForwarder(connection, smallConfig())

        try {
            val job = forwarder.start(scope)
            assertTrue(
                "a stop racing start must cancel the unpublished startup job",
                job.isCancelled,
            )
            releaseBody.countDown()
        } finally {
            releaseBody.countDown()
            forwarder.stop()
            scope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000)
    fun `concurrent start stress never publishes duplicate loop jobs`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(100) {
                val forwarder = AutoForwarder(FakeConnection(), smallConfig())
                val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val ready = java.util.concurrent.CyclicBarrier(3)
                val first = executor.submit<Job> {
                    ready.await()
                    forwarder.start(scope)
                }
                val second = executor.submit<Job> {
                    ready.await()
                    forwarder.start(scope)
                }
                ready.await()

                assertEquals(
                    "every concurrent start pair must share one scan job",
                    first.get(2, TimeUnit.SECONDS),
                    second.get(2, TimeUnit.SECONDS),
                )
                forwarder.stop()
                scope.cancel()
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val connection = FakeConnection()
        val forwarder = AutoForwarder(connection, smallConfig())
        forwarder.stop()
        forwarder.stop() // should not throw
    }

    @Test
    fun `start after stop is a no-op`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(connection, smallConfig())
        forwarder.stop()

        val job = forwarder.start(this)
        runCurrent()
        assertTrue("stopped forwarder must not scan again", connection.openForwards.isEmpty())
        job.cancel()
        runCurrent()
    }

    @Test
    fun `byte counts and speed flow through to TunnelInfo`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(connection, smallConfig())
        val job = forwarder.start(this)
        runCurrent()
        // Simulate traffic on the forward between scans.
        val forward = connection.openForwards.values.single() as FakeForward
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
        // Reject the first openPortForward call, accept subsequent
        // ones. That lands the port on failedPorts after the first scan.
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        connection.failNextOpens(1)

        // Drive our own clock so we can step it past the TTL without
        // also stepping the coroutine virtual clock (which would fire
        // more scan ticks than we want).
        val now = AtomicLong(0L)
        val ttl = 5_000L
        val forwarder = AutoForwarder(
            connection,
            smallConfig().copy(failedPortTtlMs = ttl),
            clock = { now.get() },
        )
        val job = forwarder.start(this)
        runCurrent()

        // After the first scan the port should be on the deny-list, status FAILED.
        val afterFail = forwarder.flowOfTunnels().first()
        assertEquals(1, afterFail.size)
        assertEquals(TunnelInfo.Status.FAILED, afterFail.single().status)
        assertTrue("forward should NOT have been opened on the first scan", connection.openForwards.isEmpty())

        // Advance the scan-loop clock to the next tick but stay inside
        // the TTL: the port must still be denied.
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(
            "deny-list entry is still within TTL — must not retry",
            connection.openForwards.isEmpty(),
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
        assertEquals(1, connection.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `failed port still denied within TTL`() = runTest {
        // Hardcoded clock returns 0 forever so TTL never elapses.
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
        connection.failAllOpens()

        val forwarder = AutoForwarder(
            connection,
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
            connection.openForwards.isEmpty(),
        )
        // openPortForward was called at least once (first scan); the
        // TTL guard then suppresses further attempts.
        assertTrue(
            "openPortForward must have been called at least once (initial attempt)",
            connection.totalOpenAttempts.get() >= 1,
        )
        // ...but NOT once per scan tick — the TTL keeps it on the deny-list.
        // Six ticks elapsed (1 initial + 5 from 5.5 s @ 1 s); the deny-list
        // must hold so we see strictly fewer attempts than scans.
        assertTrue(
            "TTL must suppress retries — got ${connection.totalOpenAttempts.get()} attempts",
            connection.totalOpenAttempts.get() < 6,
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
        val connection = FakeConnection()
        // No listening ports — we use togglePort to force forwards for
        // out-of-window remote ports (22, 23, 25 are all < skipPortsBelow).
        connection.setListening("")

        val forwarder = AutoForwarder(
            connection,
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
        assertEquals(2, connection.openForwards.size)

        // Third toggle — allocator must throw. AutoForwarder catches it
        // inside forwardPortLocked and memos remote port 25 on
        // failedPorts. From the outside we observe: no new forward
        // opened, port 25 ends up in the FAILED set.
        forwarder.togglePort(25)
        runCurrent()

        assertEquals(
            "third manual toggle must not open a forward — range is exhausted",
            2,
            connection.openForwards.size,
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(
            connection,
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
            "openPortForward should have been called with the remapped local port",
            9000,
            connection.openForwards.values.single().localPort,
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val occupied = setOf(9000, 9001)
        val forwarder = AutoForwarder(
            connection,
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
            "openPortForward should have been called with the incremented local port",
            9002,
            connection.openForwards.values.single().localPort,
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(
            connection,
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
            "openPortForward should have been called with the remapped local port",
            2222,
            connection.openForwards.values.single().localPort,
        )

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `empty initialRemappings preserves default mirroring behaviour`() = runTest {
        // Regression check: existing callers that don't supply a
        // remappings map must continue to get the mirror behaviour.
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")

        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            localPortAvailability = allLocalPortsAvailable,
        )
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
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")

        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            initialManualPorts = setOf(22),
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single { it.remotePort == 22 }
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(1, connection.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `initialManualPorts re-forwards a port that is not currently listening`() = runTest {
        // The desired port may briefly not be listening right after a
        // reconnect (server restarting). The seeded set still re-opens it.
        val connection = FakeConnection() // nothing listening
        val forwarder = AutoForwarder(
            connection,
            smallConfig(),
            initialManualPorts = setOf(8080),
        )
        val job = forwarder.start(this)
        runCurrent()

        val t = forwarder.flowOfTunnels().first().single { it.remotePort == 8080 }
        assertEquals(TunnelInfo.Status.FORWARDING, t.status)
        assertEquals(1, connection.openForwards.size)

        forwarder.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `ensurePort enable then disable is idempotent and absolute`() = runTest {
        val connection = FakeConnection()
        connection.setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))")
        val forwarder = AutoForwarder(connection, smallConfig())
        val job = forwarder.start(this)
        runCurrent()

        forwarder.ensurePort(22, enabled = true)
        forwarder.ensurePort(22, enabled = true) // idempotent — no dup
        assertEquals(1, connection.openForwards.count { it.value.remotePort == 22 })
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

    /**
     * The transport the forwarder runs over: core-transport's scripted
     * [FakeHostConnection] (which answers exec and owns the transport state)
     * plus the port-forward behaviours these tests script — a hanging open, a
     * hanging close, and forwards whose byte counters a test can move.
     */
    private class FakeConnection(
        private val delegate: FakeHostConnection = FakeHostConnection(),
    ) : HostConnection by delegate {

        @Volatile
        private var output: String = ""

        val openForwards: MutableMap<Int, FakeForward> = java.util.concurrent.ConcurrentHashMap()
        val totalOpenAttempts = AtomicInteger(0)
        val completedOpenAttempts = AtomicInteger(0)
        private val failuresRemaining = AtomicInteger(0)
        private val failForever = AtomicBoolean(false)

        @Volatile
        private var blockedOpen: Pair<CountDownLatch, CountDownLatch>? = null

        @Volatile
        private var blockedClose: Pair<CountDownLatch, CountDownLatch>? = null

        // How the primary `ss -tlnp` strategy answers. `timedOut` models the
        // wall-clock overrun exec reports without throwing: partial stdout
        // plus timedOut=true (issue #2489).
        @Volatile
        private var timedOut: Boolean = false

        @Volatile
        private var exitCode: Int = 0

        init {
            // Primary `ss -tlnp` returns the scripted output; netstat /
            // last-resort are not modelled here — PortScannerTest owns the
            // fallback chain.
            delegate.onExecMatching("ss -tlnp ...", match = { it.startsWith("ss -tlnp") }) {
                ExecResult(exitCode = exitCode, stdout = output, stderr = "", timedOut = timedOut)
            }
            delegate.defaultExec = ExecResult(exitCode = 0, stdout = "", stderr = "", timedOut = false)
        }

        fun setListening(ssOutput: String) {
            output = ssOutput
            timedOut = false
            exitCode = 0
        }

        /**
         * Every scan strategy fails from here on: `ss` exits non-zero with no
         * output, and the fake's default exec answers netstat / last-resort
         * with nothing. This is a transport hiccup, NOT an observation that
         * the remote's ports went away (issue #2489).
         */
        fun failScan() {
            output = ""
            exitCode = 127
            timedOut = false
        }

        /**
         * The primary strategy overruns its wall-clock budget after emitting
         * [partialOutput]. `exec` does not throw for this — it returns the
         * partial stdout with `timedOut = true` (issue #2489).
         */
        fun timeOutScan(partialOutput: String) {
            output = partialOutput
            exitCode = 0
            timedOut = true
        }

        fun blockNextOpen(entered: CountDownLatch, release: CountDownLatch) {
            blockedOpen = entered to release
        }

        /**
         * Make every forward produced by [openPortForward] block inside its
         * `close()` — it counts down [entered] and waits on [release]. Lets a
         * test hold an SSH-channel teardown open while asserting the caller of
         * `stop()` is not blocked (#1085 F-E).
         */
        fun blockForwardClose(entered: CountDownLatch, release: CountDownLatch) {
            blockedClose = entered to release
        }

        /** Make the next [n] openPortForward calls throw. */
        fun failNextOpens(n: Int) {
            failuresRemaining.set(n)
        }

        /** Make every openPortForward call throw. */
        fun failAllOpens() {
            failForever.set(true)
        }

        override suspend fun openPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): PortForward {
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
            val forward = FakeForward(remoteHost, remotePort, localPort, closeBlock = blockedClose)
            openForwards[remotePort] = forward
            completedOpenAttempts.incrementAndGet()
            return forward
        }
    }

    private class FakeForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
        private val closeBlock: Pair<CountDownLatch, CountDownLatch>? = null,
    ) : PortForward {
        val bytesForwardedAtomic = AtomicLong(0)
        val bytesReceivedAtomic = AtomicLong(0)

        @Volatile
        private var open = true

        override val isActive: Boolean get() = open
        override val bytesForwarded: Long get() = bytesForwardedAtomic.get()
        override val bytesReceived: Long get() = bytesReceivedAtomic.get()

        override suspend fun close() {
            // Model a wedged SSH channel teardown: signal we entered close, then
            // block until released (or interrupted by the bounded teardown
            // timeout). Mirrors PortForwardImpl.close(), whose blocking join
            // runs interruptibly on the transport's IO dispatcher.
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
