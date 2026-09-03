package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.PortForward
import com.pocketshell.core.transport.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Unit tests for [AutoForwarderSupervisor] — the reconnect / backoff
 * layer on top of [AutoForwarder]. Ported from
 * `ssh-auto-forward-android/.../ssh/AutoForwarderReconnectTest.kt` but
 * adapted to use fakes instead of Docker (the Docker-driven version
 * lives in `core-portfwd/src/integrationTest/`).
 *
 * The supervisor's loop runs on the [runTest] [kotlinx.coroutines.test.TestScope]
 * virtual clock, so [runCurrent] and [advanceTimeBy] deterministically
 * drive both the per-scan delay inside the wrapped [AutoForwarder] and
 * the supervisor's exponential-backoff sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoForwarderSupervisorTest {

    @Test
    fun `initial connect mounts a forwarder and emits Connected`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection {
                setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
            }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
        )

        val job = supervisor.start(this)
        runCurrent()

        val snapshot = supervisor.flowOfTunnels().first()
        assertEquals(1, snapshot.size)
        assertEquals(3000, snapshot.single().remotePort)
        assertEquals(TunnelInfo.Status.FORWARDING, snapshot.single().status)
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Connected,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `connection drop triggers reconnect and reopens forwards`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
        )

        val job = supervisor.start(this)
        runCurrent()
        val firstConnection = requireNotNull(factory.last)
        assertEquals(1, firstConnection.openForwards.size)

        // Drop the first connection — supervisor should notice via the
        // transport-state watch, tear down the forwarder, sleep through
        // the backoff window, then build a fresh connection from the
        // factory and re-open the same forward.
        firstConnection.simulateDrop()
        // Poll cadence (100ms) + backoff (200ms) + first scan (1s) -> ~1400ms.
        advanceTimeBy(2_500L)
        runCurrent()

        val secondConnection = requireNotNull(factory.last)
        assertNotSame(
            "supervisor must build a fresh connection after drop",
            firstConnection,
            secondConnection,
        )
        assertEquals(2, factory.attempts())
        // The second connection must have an open forward for port 3000.
        assertEquals(1, secondConnection.openForwards.size)
        val newSnapshot = supervisor.flowOfTunnels().first()
        assertTrue(
            "tunnel should be forwarding again on the reconnected connection, got $newSnapshot",
            newSnapshot.any { it.status == TunnelInfo.Status.FORWARDING },
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `exponential backoff doubles on each failed connect`() = runTest {
        // Factory throws three times, then succeeds. Supervisor should
        // back off 100, 200, 400 ms between attempts (capped at the max).
        val factory = SequentialConnectionFactory()
        factory.failNext(3)
        factory.addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }

        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 100L,
            maxReconnectDelayMs = 10_000L,
        )
        val job = supervisor.start(this)

        // First attempt fails immediately; backoff = 100 ms.
        runCurrent()
        assertEquals(1, factory.attempts())

        // After 100 ms, attempt 2 fires (and fails); backoff -> 200 ms.
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(2, factory.attempts())

        // After +200 ms, attempt 3 fires (and fails); backoff -> 400 ms.
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(3, factory.attempts())

        // After +400 ms, attempt 4 fires and succeeds.
        advanceTimeBy(450L)
        runCurrent()
        assertEquals(4, factory.attempts())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Connected,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `reconnectNow cancels backoff and retries immediately`() = runTest {
        val factory = SequentialConnectionFactory()
        factory.failNext(1)
        factory.addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }

        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            // Long initial delay — without reconnectNow() the test would
            // have to wait the full backoff for attempt 2.
            initialReconnectDelayMs = 60_000L,
            maxReconnectDelayMs = 60_000L,
        )
        val job = supervisor.start(this)
        runCurrent()
        assertEquals(1, factory.attempts())

        // We're now inside the 60 s backoff. Hit reconnectNow() and
        // assert the supervisor wakes up and tries again.
        supervisor.reconnectNow()
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(2, factory.attempts())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Connected,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `forced reconnect rebuilds a stale connected connection without waiting for backoff`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 60_000L,
            maxReconnectDelayMs = 60_000L,
        )
        val job = supervisor.start(this)
        runCurrent()
        val firstConnection = requireNotNull(factory.last)
        assertEquals(1, firstConnection.openForwards.size)

        supervisor.reconnectNow(force = true)
        advanceTimeBy(150L)
        runCurrent()

        val secondConnection = requireNotNull(factory.last)
        assertNotSame(firstConnection, secondConnection)
        assertEquals(
            "forced reconnect should skip the long post-drop backoff",
            2,
            factory.attempts(),
        )
        assertTrue("old connection must be closed by force reconnect", !firstConnection.isConnected)
        assertEquals(1, secondConnection.openForwards.count { it.remotePort == 3000 })
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Connected,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `tunnel snapshots flip to STOPPED while supervisor reconnects`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
            // Second connection ready but never used in this test — we
            // assert the snapshot inside the backoff window.
            failNext(1)
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 10_000L,
            maxReconnectDelayMs = 10_000L,
        )
        val job = supervisor.start(this)
        runCurrent()
        // First connection connected, port 3000 forwarding.
        assertEquals(TunnelInfo.Status.FORWARDING, supervisor.flowOfTunnels().first().single().status)

        val firstConnection = requireNotNull(factory.last)
        firstConnection.simulateDrop()
        // Wait long enough for the supervisor to notice the drop, tear
        // down the forwarder, fail the next connect, and enter backoff.
        advanceTimeBy(500L)
        runCurrent()

        val backoffSnapshot = supervisor.flowOfTunnels().first()
        // Tunnels should now be marked STOPPED (not FORWARDING) until
        // a successful reconnect re-opens them.
        assertTrue(
            "tunnels should be marked STOPPED during backoff, got $backoffSnapshot",
            backoffSnapshot.all { it.status == TunnelInfo.Status.STOPPED },
        )
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Reconnecting,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `maxReconnectAttempts surfaces ConnectionLost then parks`() = runTest {
        // Exactly 3 scripted failures so the queue is empty when the
        // supervisor enters the Lost park — that way a successful
        // connection added after reconnectNow() is the very next thing the
        // factory hands out, not buried behind extra scripted failures.
        val factory = SequentialConnectionFactory().apply { failNext(3) }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 50L,
            maxReconnectDelayMs = 50L,
            maxReconnectAttempts = 3,
        )
        val job = supervisor.start(this)

        // Burn through the three allowed attempts.
        runCurrent() // attempt 1
        advanceTimeBy(60L); runCurrent() // attempt 2
        advanceTimeBy(60L); runCurrent() // attempt 3

        // After the 3rd consecutive failure the supervisor parks in Lost
        // and emits ConnectionLost. Advance well past any nominal
        // backoff window — the factory must NOT be called again.
        advanceTimeBy(10_000L); runCurrent()
        assertEquals(3, factory.attempts())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Lost,
            supervisor.flowOfConnectionState().value,
        )

        // reconnectNow() must wake the park and retry. Queue a success
        // for that next attempt — the queue is empty at this point, so
        // the supervisor will see the queued connection immediately.
        factory.addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        supervisor.reconnectNow()
        runCurrent()
        // Successful connect doesn't go through the backoff sleep, so no
        // advanceTimeBy is needed — runCurrent flushes the wake-up +
        // first scan tick.
        assertEquals(4, factory.attempts())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Connected,
            supervisor.flowOfConnectionState().value,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `stop is idempotent and closes the current connection`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
        )
        val job = supervisor.start(this)
        runCurrent()
        val connection = requireNotNull(factory.last)
        assertTrue("connection should be open after initial connect", connection.isConnected)

        supervisor.stop()
        supervisor.stop() // idempotent

        // stop() now closes the live SSH connection OFF the caller thread (on
        // the teardown dispatcher) so a wedged disconnect socket can't freeze
        // the UI — see `stop() does not block the caller when the connection
        // close hangs`. The disconnect still happens; we await it.
        assertTrue(
            "stop() must close the live connection",
            waitUntilReal(2_000L) { !connection.isConnected },
        )
        assertEquals(emptyList<TunnelInfo>(), supervisor.flowOfTunnels().first())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Idle,
            supervisor.flowOfConnectionState().value,
        )
        job.cancel()
        runCurrent()
    }

    @Test
    fun `stop during a non cooperative connect closes the late connection`() = runTest {
        val connectEntered = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        val lateConnection = FakeConnection()
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = {
                connectEntered.complete(Unit)
                withContext(NonCancellable) { releaseConnect.await() }
                lateConnection
            },
            config = smallConfig(),
        )

        val job = supervisor.start(this)
        runCurrent()
        connectEntered.await()

        // The factory models a real connect body that cannot be interrupted at
        // its final socket boundary. Stop must fence the result before it can
        // publish a new connection/forwarder, then close that late result.
        supervisor.stop()
        releaseConnect.complete(Unit)
        runCurrent()

        assertFalse("a late connection returned after stop must be closed", lateConnection.isConnected)
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Idle,
            supervisor.flowOfConnectionState().value,
        )
        assertTrue("stop must join the cancelled supervisor loop", job.isCompleted)
    }

    @Test(timeout = 5_000)
    fun `stop before supervisor job publication cannot resurrect the loop`() {
        lateinit var supervisor: AutoForwarderSupervisor
        val executor = Executors.newSingleThreadExecutor()
        val releaseBody = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                // Force stop() to run after launch has created the coroutine
                // but before start() can publish supervisorJob.
                supervisor.stop()
                executor.execute {
                    check(releaseBody.await(2, TimeUnit.SECONDS)) {
                        "test dispatcher was not released"
                    }
                    block.run()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        supervisor = AutoForwarderSupervisor(
            connectionFactory = {
                attempts.incrementAndGet()
                FakeConnection()
            },
            config = smallConfig(),
        )

        try {
            val job = supervisor.start(scope)
            supervisor.reconnectNow(force = true)
            assertTrue(
                "a stop racing supervisor start must cancel the unpublished job",
                job.isCancelled,
            )
            releaseBody.countDown()
            assertEquals("final stop must prevent a late connect", 0, attempts.get())
            assertEquals(
                AutoForwarderSupervisor.ConnectionState.Idle,
                supervisor.flowOfConnectionState().value,
            )
        } finally {
            releaseBody.countDown()
            supervisor.stop()
            scope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `virtual time supervisor stop stress never retries after final cancellation`() = runTest {
        repeat(50) {
            val attempts = AtomicInteger(0)
            val supervisor = AutoForwarderSupervisor(
                connectionFactory = {
                    attempts.incrementAndGet()
                    error("offline")
                },
                config = smallConfig(),
                initialReconnectDelayMs = 100L,
                maxReconnectDelayMs = 100L,
            )
            val job = supervisor.start(this)
            runCurrent()
            assertEquals("each round must reach its first connect attempt", 1, attempts.get())

            supervisor.stop()
            val attemptsAtStop = attempts.get()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(
                "final stop must prevent a delayed reconnect in every round",
                attemptsAtStop,
                attempts.get(),
            )
            assertTrue("stop must cancel and join the supervisor job", job.isCompleted)
            assertEquals(
                AutoForwarderSupervisor.ConnectionState.Idle,
                supervisor.flowOfConnectionState().value,
            )
        }
    }

    @Test
    fun `stop() does not block the caller when the connection close hangs`() {
        // #1085 freeze-hunt F-E: supervisor.stop() is reached on the Android
        // Main thread (panel auto-forward toggle-off / foreground-service
        // ACTION_STOP → ForwardingController → ActiveHost.stopOwnedSupervisor
        // → supervisor.stop()), and a real HostConnection.close() blocks the caller
        // until the SSH_MSG_DISCONNECT socket write finishes — on a wedged
        // socket that froze the UI. Before the fix stop() closed the connection
        // inline. This holds the connection's close() open and asserts stop()
        // returns promptly anyway, while still confirming the disconnect is
        // dispatched (teardown not dropped). RED on base (stop() blocks until
        // the latch releases), GREEN with the off-thread teardown.
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        factory.blockClose(closeEntered, closeRelease)

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val loopScope = CoroutineScope(SupervisorJob() + dispatcher)
        // Default teardownDispatcher = Dispatchers.IO, so the hung connection
        // close runs off the caller thread — exactly the production wiring.
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
        )
        try {
            supervisor.start(loopScope)
            assertTrue(
                "supervisor should reach Connected with a live connection",
                waitUntilReal(2_000L) {
                    factory.last?.isConnected == true &&
                        supervisor.flowOfConnectionState().value ==
                        AutoForwarderSupervisor.ConnectionState.Connected
                },
            )

            val stopThread = Thread { supervisor.stop() }
            stopThread.start()
            stopThread.join(2_000L)
            assertFalse(
                "stop() must return without blocking on the hung connection close",
                stopThread.isAlive,
            )
            assertTrue(
                "the connection's close() must still be invoked (teardown not dropped)",
                closeEntered.await(2, TimeUnit.SECONDS),
            )
        } finally {
            closeRelease.countDown()
            loopScope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun waitUntilReal(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    @Test
    fun `togglePort forwards manual toggle to the active forwarder`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
        )
        val job = supervisor.start(this)
        runCurrent()
        // sshd:22 is below skipPortsBelow so the auto path leaves it
        // AVAILABLE. Manual toggle should force a forward.
        assertEquals(
            TunnelInfo.Status.AVAILABLE,
            supervisor.flowOfTunnels().first().single().status,
        )

        supervisor.togglePort(22)
        runCurrent()

        assertEquals(
            TunnelInfo.Status.FORWARDING,
            supervisor.flowOfTunnels().first().single().status,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `manual forward survives drop+reconnect and is auto-restored`() = runTest {
        // sshd:22 is below skipPortsBelow, so it is NEVER auto-forwarded:
        // the only way it stays up across a reconnect is if the user's
        // desired-state opt-in survives the AutoForwarder swap (issue
        // #439). Both connections report only :22 listening.
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
        )

        val job = supervisor.start(this)
        runCurrent()

        // User opts port 22 in. It is out of the auto window, so this is
        // the user's explicit desired state.
        supervisor.togglePort(22)
        runCurrent()
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())
        val firstConnection = requireNotNull(factory.last)
        assertEquals(1, firstConnection.openForwards.size)
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            supervisor.flowOfTunnels().first().single { it.remotePort == 22 }.status,
        )

        // Drop the transport. Supervisor reconnects and must re-open :22
        // even though it is outside the auto-forward window.
        firstConnection.simulateDrop()
        advanceTimeBy(2_500L)
        runCurrent()

        val secondConnection = requireNotNull(factory.last)
        assertNotSame(firstConnection, secondConnection)
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())
        assertTrue(
            "manual forward must auto-restore on the reconnected connection",
            secondConnection.openForwards.any { it.remotePort == 22 },
        )
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            supervisor.flowOfTunnels().first().single { it.remotePort == 22 }.status,
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `no duplicate forwards after multiple reconnect cycles`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            repeat(4) { addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") } }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
        )

        val job = supervisor.start(this)
        runCurrent()
        supervisor.togglePort(22)
        runCurrent()

        // Three drop+reconnect cycles.
        repeat(3) {
            val connection = requireNotNull(factory.last)
            connection.simulateDrop()
            advanceTimeBy(2_500L)
            runCurrent()
        }

        val latest = requireNotNull(factory.last)
        // Each fresh connection must hold exactly ONE forward for :22 — the
        // desired-state set is a Set, and the scan loop de-dupes by
        // `port !in tunnels`, so cycles can't leak duplicates.
        assertEquals(
            "each reconnected connection must hold exactly one :22 forward",
            1,
            latest.openForwards.count { it.remotePort == 22 },
        )
        assertEquals(
            1,
            supervisor.flowOfTunnels().first().count { it.remotePort == 22 },
        )
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `user-disabled port is not restored on reconnect`() = runTest {
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
        )

        val job = supervisor.start(this)
        runCurrent()
        // Enable, then disable :22 — desired state should be empty again.
        supervisor.togglePort(22)
        runCurrent()
        supervisor.togglePort(22)
        runCurrent()
        assertEquals(emptySet<Int>(), supervisor.desiredManualPortsSnapshot())

        val firstConnection = requireNotNull(factory.last)
        firstConnection.simulateDrop()
        advanceTimeBy(2_500L)
        runCurrent()

        val secondConnection = requireNotNull(factory.last)
        assertNotSame(firstConnection, secondConnection)
        // :22 is below the auto window, so a user-disabled port must NOT
        // be re-forwarded on the reconnected connection.
        assertTrue(
            "user-disabled out-of-window port must not be restored",
            secondConnection.openForwards.none { it.remotePort == 22 },
        )
        assertEquals(emptySet<Int>(), supervisor.desiredManualPortsSnapshot())

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `togglePort during backoff still records desired state for next reconnect`() = runTest {
        // First connect succeeds, then the connection drops and the next
        // connect fails so the supervisor sits in backoff. Toggling a
        // port during that window must record desired state and restore
        // it once a connection comes back.
        val factory = SequentialConnectionFactory().apply {
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            failNext(1)
            addConnection { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 300L,
            maxReconnectDelayMs = 300L,
        )

        val job = supervisor.start(this)
        runCurrent()
        val firstConnection = requireNotNull(factory.last)
        firstConnection.simulateDrop()
        // Notice the drop (50ms poll) + sleep the post-drop backoff
        // (300ms) + the next connect fails -> a second 300ms backoff. We
        // land squarely inside that second backoff with no forwarder
        // mounted.
        advanceTimeBy(450L)
        runCurrent()
        assertEquals(2, factory.attempts())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Reconnecting,
            supervisor.flowOfConnectionState().value,
        )

        // No forwarder mounted right now, but the toggle must still be
        // recorded as desired state.
        supervisor.togglePort(22)
        runCurrent()
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())

        // Let the backoff elapse; the queued success connects and the
        // seeded desired-state set restores :22 on the first scan.
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(3, factory.attempts())
        val restored = requireNotNull(factory.last)
        assertTrue(
            "port toggled during backoff must be restored on reconnect",
            restored.openForwards.any { it.remotePort == 22 },
        )

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    private fun smallConfig() = AutoForwardConfig(
        scanIntervalSec = 1,
        maxAutoPort = 5_000,
        skipPortsBelow = 1024,
        localPortRange = 3_500..3_600,
    )

    /**
     * Hands out a fresh [FakeConnection] per [next] call from a queued
     * script of initial-state lambdas + failure injections.
     */
    private class SequentialConnectionFactory {
        private val queue: ArrayDeque<Lambda> = ArrayDeque()
        private val attempts = AtomicInteger(0)

        @Volatile var last: FakeConnection? = null
        @Volatile private var closeBlock: Pair<CountDownLatch, CountDownLatch>? = null

        fun addConnection(init: FakeConnection.() -> Unit) {
            queue += Lambda.Connected(init)
        }

        /**
         * Make every connection this factory hands out block inside its
         * `close()` — it counts down [entered] and waits on [release]. Lets a
         * test hold an SSH disconnect open while asserting the caller of
         * `stop()` is not blocked (#1085 F-E).
         */
        fun blockClose(entered: CountDownLatch, release: CountDownLatch) {
            closeBlock = entered to release
        }

        fun failNext(n: Int) {
            repeat(n) { queue += Lambda.Failure }
        }

        fun next(): FakeConnection {
            attempts.incrementAndGet()
            val entry = queue.removeFirstOrNull()
                ?: throw IllegalStateException("factory script exhausted (attempt $attempts)")
            return when (entry) {
                is Lambda.Failure -> throw RuntimeException("scripted factory failure")
                is Lambda.Connected -> {
                    val s = FakeConnection().apply(entry.init)
                    s.closeBlock = closeBlock
                    last = s
                    s
                }
            }
        }

        fun attempts(): Int = attempts.get()

        sealed class Lambda {
            object Failure : Lambda()
            data class Connected(val init: FakeConnection.() -> Unit) : Lambda()
        }
    }

    /**
     * The transport the supervisor drives, built on core-transport's scripted
     * [FakeHostConnection]. The supervisor only needs the transport state, exec
     * (for `ss -tlnp`), [openPortForward] and close, so this adds just two
     * things the shared fake should not carry: a wedged close (#1085 F-E) and
     * [simulateDrop], which flips the transport to Lost the way a real network
     * failure does — distinct from a deliberate [close].
     */
    private class FakeConnection(
        private val delegate: FakeHostConnection = FakeHostConnection(),
    ) : HostConnection by delegate {

        @Volatile
        private var output: String = ""

        val openForwards: MutableList<FakeForward> = java.util.concurrent.CopyOnWriteArrayList()

        // When set, close() signals [first] then blocks on [second] — models a
        // wedged SSH_MSG_DISCONNECT socket write (#1085 F-E).
        @Volatile
        var closeBlock: Pair<CountDownLatch, CountDownLatch>? = null

        init {
            delegate.onExecMatching("ss -tlnp ...", match = { it.startsWith("ss -tlnp") }) {
                ExecResult(exitCode = 0, stdout = output, stderr = "", timedOut = false)
            }
            delegate.defaultExec = ExecResult(exitCode = 0, stdout = "", stderr = "", timedOut = false)
        }

        /** True while the transport has not settled into Lost/Closed. */
        val isConnected: Boolean
            get() = when (delegate.state.value) {
                is TransportState.Lost, TransportState.Closed -> false
                else -> true
            }

        fun setListening(ssOutput: String) {
            output = ssOutput
        }

        /** A network-level drop: the transport dies without anyone calling close. */
        fun simulateDrop() {
            delegate.markLost("simulated network drop")
            runBlocking { openForwards.forEach { it.close() } }
        }

        override suspend fun openPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): PortForward {
            val forward = FakeForward(remoteHost, remotePort, localPort)
            // Delegate first so a spent transport refuses the open exactly as a
            // real one does; only a successful open is recorded.
            delegate.openPortForward(remoteHost, remotePort, localPort)
            openForwards += forward
            return forward
        }

        override suspend fun close() {
            closeBlock?.let { (entered, release) ->
                entered.countDown()
                try {
                    release.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            delegate.close()
            openForwards.forEach { it.close() }
        }
    }

    private class FakeForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
    ) : PortForward {
        @Volatile
        private var open = true

        override val isActive: Boolean get() = open
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0

        override suspend fun close() {
            open = false
        }
    }
}
