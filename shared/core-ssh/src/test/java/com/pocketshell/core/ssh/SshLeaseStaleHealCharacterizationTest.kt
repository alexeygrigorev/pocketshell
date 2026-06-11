package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * EPIC #687 Phase-1 GATE — stale-channel heal at the SSH lease/transport layer
 * (the lease-side half of the #665/#680/#621 heal).
 *
 * The app-layer matchers `isStaleChannelSymptom` / `isSessionNotConnected`
 * (in `FolderListGateway` / `TmuxSessionViewModel`) classify a transient
 * stale-channel fault and then drive an EVICT-AND-RETRY-ONCE recovery: evict the
 * poisoned warm lease so the next acquire dials a FRESH transport, instead of
 * surfacing a false "not connected" banner over a host that is actually
 * connectable. Those matchers live in `app/` (owned by sibling issues), but the
 * lease-layer primitives they depend on live HERE in core-ssh:
 *
 *  - a pooled transport can silently drop (sshj's `isConnected` lies until the
 *    keepalive trips), and the lease pool reports it as no-longer-live so the
 *    heal knows to evict;
 *  - `evictIdle(key)` closes the poisoned zero-ref warm lease and makes the NEXT
 *    acquire dial a fresh transport — the evict-and-retry-once mechanism;
 *  - `evictIdle` leaves an ACTIVELY-held lease alone (the heal never yanks a
 *    transport out from under a live foreground holder);
 *  - a stale/disconnected pooled entry is auto-replaced on the next acquire even
 *    WITHOUT an explicit evict (the natural heal path).
 *
 * These PIN the CURRENT behavior and MUST stay green on current code.
 *
 * Learning map: #665 / #680 (stale-channel heal, false "not connected"), #621 /
 * #553 (stale SSH reattach reseed — lease eviction is the transport-side half).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseStaleHealCharacterizationTest {

    @Test
    fun `a silently-dropped pooled transport stops reporting as a live lease`() = runTest {
        // #680: the warm lease's transport dies silently while pooled (network
        // handoff; sshj keeps `isConnected` true until keepalive trips, but the
        // FakeSshSession models the eventual flip). The pool must report it as no
        // longer live so the heal knows the channel is stale and an evict+redial
        // is warranted — NOT keep claiming a dead channel is connected.
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session))

        manager.acquire(TARGET).getOrThrow().release()
        assertTrue("warm released lease is live within TTL", manager.hasLiveLease(TARGET.leaseKey))

        session.connected = false // silent transport drop while pooled

        assertFalse(
            "a silently-dropped pooled transport must not report as a live lease (#680)",
            manager.hasLiveLease(TARGET.leaseKey),
        )
        assertFalse(
            "a probe must never resurrect the dead channel as live-or-connecting",
            manager.hasLiveOrConnectingLease(TARGET.leaseKey),
        )
    }

    @Test
    fun `evict idle then reacquire heals onto a fresh transport (evict-and-retry-once)`() = runTest {
        // #665/#680: the lease-layer evict-and-retry-once. The poisoned warm
        // lease still reports isConnected (the deceptive case the heal exists
        // for), so the heal cannot rely on it auto-dropping; it EVICTS the idle
        // lease, and the subsequent acquire dials a brand-new transport. Pins:
        // evict closes the stale transport exactly once, and the retry yields a
        // different (fresh) session.
        val stale = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(stale, fresh)
        val manager = leaseManager(connector)

        manager.acquire(TARGET).getOrThrow().release()
        assertFalse("the warm lease is not yet evicted", stale.closed)
        assertTrue("the deceptive stale lease still claims connected", manager.hasLiveLease(TARGET.leaseKey))

        // The heal evicts the poisoned warm lease (retry step 1).
        assertTrue("evictIdle closes the retained zero-ref lease", manager.evictIdle(TARGET.leaseKey))
        assertTrue("the stale transport is closed by the evict", stale.closed)
        assertEquals(1, stale.closeCount)

        // The retry's fresh acquire dials a NEW transport (retry step 2).
        val healed = manager.acquire(TARGET).getOrThrow()
        assertEquals("the heal re-dialed a fresh transport", 2, connector.connectCount)
        assertNotSame("the healed lease is a brand-new session, not the stale one", stale, healed.session)
        assertSame(fresh, healed.session)
        assertTrue("the healed acquire owns a fresh connection", healed.isNewConnection)

        healed.release()
    }

    @Test
    fun `evict idle leaves an actively-held lease alone so the heal never yanks a live holder`() = runTest {
        // #665/#680: the heal's evict-and-retry-once must only ever evict an
        // IDLE (zero-ref) lease. A lease a foreground holder is actively using is
        // left in place — evictIdle returns false and the transport stays open —
        // so a discovery-path heal can't tear the active session's transport out
        // from under it.
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(connector)

        val active = manager.acquire(TARGET).getOrThrow()

        assertFalse("evictIdle must refuse an actively-held lease", manager.evictIdle(TARGET.leaseKey))
        assertFalse("the active transport stays open through the heal attempt", session.closed)

        // A concurrent acquire still reuses the live transport (no re-dial).
        val shared = manager.acquire(TARGET).getOrThrow()
        assertEquals(1, connector.connectCount)
        assertSame(session, shared.session)

        active.release()
        shared.release()
    }

    @Test
    fun `a disconnected pooled entry is auto-replaced on the next acquire without an explicit evict`() = runTest {
        // #680/#621: the natural heal path. When the pooled transport has
        // genuinely flipped disconnected, the next acquire for the same key drops
        // the dead entry and dials a fresh transport on its own — the caller does
        // not have to call evictIdle first. This is the no-EOF-on-reattach
        // guarantee at the lease layer.
        val dead = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(dead, fresh)
        val manager = leaseManager(connector)

        manager.acquire(TARGET).getOrThrow().release()
        dead.connected = false // transport genuinely down while pooled

        val replacement = manager.acquire(TARGET).getOrThrow()
        assertEquals("a dead pooled entry forces a fresh dial on next acquire", 2, connector.connectCount)
        assertSame(fresh, replacement.session)
        assertTrue("the replacement is a brand-new connection", replacement.isNewConnection)

        replacement.release()
    }

    @Test
    fun `releasing the evicted stale lease never mutates the healed replacement`() = runTest {
        // #665: after an evict-and-redial, a late release() arriving from the now
        // dead/evicted lease handle must be a no-op against the fresh
        // replacement entry — the stale holder's bookkeeping must not close or
        // ref-decrement the healed transport. (Entry identity is keyed by id.)
        val stale = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(stale, fresh)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        val staleLease = manager.acquire(TARGET).getOrThrow()
        stale.connected = false
        val healedLease = manager.acquire(TARGET).getOrThrow() // auto-replaces the dead entry

        // The stale holder releases late — must not disturb the fresh entry.
        staleLease.release()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, connector.connectCount)
        assertSame(fresh, healedLease.session)
        assertFalse("a stale release must not close the healed replacement", fresh.closed)

        healedLease.release()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("the healed lease still honors its own TTL close", fresh.closed)
    }

    // ---- harness ----

    private fun TestScope.leaseManager(
        connector: SshLeaseConnector,
        idleTtlMillis: Long = 60_000,
        maxIdleLeases: Int = 2,
    ): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = idleTtlMillis,
            maxIdleLeases = maxIdleLeases,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true
        var closeCount: Int = 0

        override val isConnected: Boolean
            get() = connected && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closeCount += 1
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )
    }
}
