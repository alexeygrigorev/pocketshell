package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
        val factory = SequentialSessionFactory().apply {
            addSession {
                setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))")
            }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            sessionHealthPollMs = 100L,
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
    fun `session drop triggers reconnect and reopens forwards`() = runTest {
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
            addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
            sessionHealthPollMs = 100L,
        )

        val job = supervisor.start(this)
        runCurrent()
        val firstSession = requireNotNull(factory.last)
        assertEquals(1, firstSession.openForwards.size)

        // Drop the first session — supervisor should notice via the
        // session-health poll, tear down the forwarder, sleep through
        // the backoff window, then build a fresh session from the
        // factory and re-open the same forward.
        firstSession.simulateDrop()
        // Poll cadence (100ms) + backoff (200ms) + first scan (1s) -> ~1400ms.
        advanceTimeBy(2_500L)
        runCurrent()

        val secondSession = requireNotNull(factory.last)
        assertNotSame(
            "supervisor must build a fresh session after drop",
            firstSession,
            secondSession,
        )
        assertEquals(2, factory.attempts())
        // The second session must have an open forward for port 3000.
        assertEquals(1, secondSession.openForwards.size)
        val newSnapshot = supervisor.flowOfTunnels().first()
        assertTrue(
            "tunnel should be forwarding again on the reconnected session, got $newSnapshot",
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
        val factory = SequentialSessionFactory()
        factory.failNext(3)
        factory.addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }

        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 100L,
            maxReconnectDelayMs = 10_000L,
            sessionHealthPollMs = 50L,
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
        val factory = SequentialSessionFactory()
        factory.failNext(1)
        factory.addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }

        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            // Long initial delay — without reconnectNow() the test would
            // have to wait the full backoff for attempt 2.
            initialReconnectDelayMs = 60_000L,
            maxReconnectDelayMs = 60_000L,
            sessionHealthPollMs = 50L,
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
    fun `tunnel snapshots flip to STOPPED while supervisor reconnects`() = runTest {
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
            // Second session ready but never used in this test — we
            // assert the snapshot inside the backoff window.
            failNext(1)
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 10_000L,
            maxReconnectDelayMs = 10_000L,
            sessionHealthPollMs = 50L,
        )
        val job = supervisor.start(this)
        runCurrent()
        // First session connected, port 3000 forwarding.
        assertEquals(TunnelInfo.Status.FORWARDING, supervisor.flowOfTunnels().first().single().status)

        val firstSession = requireNotNull(factory.last)
        firstSession.simulateDrop()
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
        // session added after reconnectNow() is the very next thing the
        // factory hands out, not buried behind extra scripted failures.
        val factory = SequentialSessionFactory().apply { failNext(3) }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 50L,
            maxReconnectDelayMs = 50L,
            maxReconnectAttempts = 3,
            sessionHealthPollMs = 50L,
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
        // the supervisor will see the queued session immediately.
        factory.addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
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
    fun `stop is idempotent and closes the current session`() = runTest {
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:3000 users:((\"app\",pid=1,fd=4))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            sessionHealthPollMs = 100L,
        )
        val job = supervisor.start(this)
        runCurrent()
        val session = requireNotNull(factory.last)
        assertTrue("session should be open after initial connect", session.isConnected)

        supervisor.stop()
        supervisor.stop() // idempotent

        assertTrue("stop() must close the live session", !session.isConnected)
        assertEquals(emptyList<TunnelInfo>(), supervisor.flowOfTunnels().first())
        assertEquals(
            AutoForwarderSupervisor.ConnectionState.Idle,
            supervisor.flowOfConnectionState().value,
        )
        job.cancel()
        runCurrent()
    }

    @Test
    fun `togglePort forwards manual toggle to the active forwarder`() = runTest {
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            sessionHealthPollMs = 100L,
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
        // #439). Both sessions report only :22 listening.
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
            sessionHealthPollMs = 100L,
        )

        val job = supervisor.start(this)
        runCurrent()

        // User opts port 22 in. It is out of the auto window, so this is
        // the user's explicit desired state.
        supervisor.togglePort(22)
        runCurrent()
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())
        val firstSession = requireNotNull(factory.last)
        assertEquals(1, firstSession.openForwards.size)
        assertEquals(
            TunnelInfo.Status.FORWARDING,
            supervisor.flowOfTunnels().first().single { it.remotePort == 22 }.status,
        )

        // Drop the transport. Supervisor reconnects and must re-open :22
        // even though it is outside the auto-forward window.
        firstSession.simulateDrop()
        advanceTimeBy(2_500L)
        runCurrent()

        val secondSession = requireNotNull(factory.last)
        assertNotSame(firstSession, secondSession)
        assertEquals(setOf(22), supervisor.desiredManualPortsSnapshot())
        assertTrue(
            "manual forward must auto-restore on the reconnected session",
            secondSession.openForwards.any { it.remotePort == 22 },
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
        val factory = SequentialSessionFactory().apply {
            repeat(4) { addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") } }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
            sessionHealthPollMs = 100L,
        )

        val job = supervisor.start(this)
        runCurrent()
        supervisor.togglePort(22)
        runCurrent()

        // Three drop+reconnect cycles.
        repeat(3) {
            val session = requireNotNull(factory.last)
            session.simulateDrop()
            advanceTimeBy(2_500L)
            runCurrent()
        }

        val latest = requireNotNull(factory.last)
        // Each fresh session must hold exactly ONE forward for :22 — the
        // desired-state set is a Set, and the scan loop de-dupes by
        // `port !in tunnels`, so cycles can't leak duplicates.
        assertEquals(
            "each reconnected session must hold exactly one :22 forward",
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
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 200L,
            maxReconnectDelayMs = 200L,
            sessionHealthPollMs = 100L,
        )

        val job = supervisor.start(this)
        runCurrent()
        // Enable, then disable :22 — desired state should be empty again.
        supervisor.togglePort(22)
        runCurrent()
        supervisor.togglePort(22)
        runCurrent()
        assertEquals(emptySet<Int>(), supervisor.desiredManualPortsSnapshot())

        val firstSession = requireNotNull(factory.last)
        firstSession.simulateDrop()
        advanceTimeBy(2_500L)
        runCurrent()

        val secondSession = requireNotNull(factory.last)
        assertNotSame(firstSession, secondSession)
        // :22 is below the auto window, so a user-disabled port must NOT
        // be re-forwarded on the reconnected session.
        assertTrue(
            "user-disabled out-of-window port must not be restored",
            secondSession.openForwards.none { it.remotePort == 22 },
        )
        assertEquals(emptySet<Int>(), supervisor.desiredManualPortsSnapshot())

        supervisor.stop()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `togglePort during backoff still records desired state for next reconnect`() = runTest {
        // First connect succeeds, then the session drops and the next
        // connect fails so the supervisor sits in backoff. Toggling a
        // port during that window must record desired state and restore
        // it once a session comes back.
        val factory = SequentialSessionFactory().apply {
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
            failNext(1)
            addSession { setListening("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))") }
        }
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = { factory.next() },
            config = smallConfig(),
            initialReconnectDelayMs = 300L,
            maxReconnectDelayMs = 300L,
            sessionHealthPollMs = 50L,
        )

        val job = supervisor.start(this)
        runCurrent()
        val firstSession = requireNotNull(factory.last)
        firstSession.simulateDrop()
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
     * Hands out a fresh [FakeSession] per [next] call from a queued
     * script of initial-state lambdas + failure injections.
     */
    private class SequentialSessionFactory {
        private val queue: ArrayDeque<Lambda> = ArrayDeque()
        private val attempts = AtomicInteger(0)

        @Volatile var last: FakeSession? = null

        fun addSession(init: FakeSession.() -> Unit) {
            queue += Lambda.Session(init)
        }

        fun failNext(n: Int) {
            repeat(n) { queue += Lambda.Failure }
        }

        fun next(): FakeSession {
            attempts.incrementAndGet()
            val entry = queue.removeFirstOrNull()
                ?: throw IllegalStateException("factory script exhausted (attempt $attempts)")
            return when (entry) {
                is Lambda.Failure -> throw RuntimeException("scripted factory failure")
                is Lambda.Session -> {
                    val s = FakeSession().apply(entry.init)
                    last = s
                    s
                }
            }
        }

        fun attempts(): Int = attempts.get()

        sealed class Lambda {
            object Failure : Lambda()
            data class Session(val init: FakeSession.() -> Unit) : Lambda()
        }
    }

    /**
     * Fake [SshSession] copied in spirit from
     * [AutoForwarderTest.FakeSession] — the supervisor only needs
     * `isConnected`, `exec` (for `ss -tlnp`), `openLocalPortForward`,
     * and `close`. We surface `simulateDrop()` so tests can flip
     * `isConnected` to false without going through `close()` (close()
     * does the right thing too, but `simulateDrop()` is clearer about
     * intent in reconnect tests).
     */
    private class FakeSession : SshSession {
        @Volatile private var output: String = ""
        @Volatile private var connected: Boolean = true
        val openForwards: MutableList<FakeForward> = mutableListOf()

        fun setListening(ssOutput: String) {
            output = ssOutput
        }

        fun simulateDrop() {
            connected = false
            openForwards.forEach { it.close() }
        }

        override val isConnected: Boolean
            get() = connected
        override suspend fun exec(command: String): ExecResult {
            if (!connected) throw RuntimeException("session is closed")
            return if (command.startsWith("ss -tlnp")) {
                ExecResult(output, "", exitCode = 0)
            } else {
                ExecResult("", "", exitCode = 0)
            }
        }
        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used by supervisor tests")
        override fun startShell(): SshShell =
            error("startShell not used by supervisor tests")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            if (!connected) throw RuntimeException("session is closed")
            val f = FakeForward(remoteHost, remotePort, localPort)
            openForwards += f
            return f
        }
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used by supervisor tests")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used by supervisor tests")
        override fun close() {
            connected = false
            openForwards.forEach { it.close() }
        }
    }

    private class FakeForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
    ) : SshPortForward {
        @Volatile private var open = true
        override val isActive: Boolean get() = open
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0
        override fun close() { open = false }
    }

    @Suppress("unused")
    private fun debug(forwarder: AutoForwarder?) {
        // placeholder to make sure the test file doesn't drop the
        // AutoForwarder symbol when running on minimal JDKs that fail
        // imported-but-unused lints — not currently used.
        assertNotNull(forwarder)
    }
}
