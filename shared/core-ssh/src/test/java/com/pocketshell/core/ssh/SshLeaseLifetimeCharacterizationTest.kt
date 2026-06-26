package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * EPIC #687 Phase-1 GATE — lease lifetime / refcount characterization (the
 * warm-lease-lifetime half of the #620 learning, the lever the target
 * architecture wants made first-class: "one warm, process-singleton lease per
 * host; cached sessions show instantly").
 *
 * These PIN the CURRENT lifetime behavior of [SshLeaseManager] so the Phase-2
 * rewrite that collapses the three grace clocks down to ONE lease-anchored 60s
 * window can be proven equivalent at the transport-retention layer:
 *
 *  - acquire/release REFCOUNT: multiple holders, last-release closes;
 *  - WARM-LEASE REUSE (no fresh dial) vs FRESH DIAL after the warm window drains;
 *  - the warm lease stays live across a partial release (a remaining holder
 *    keeps the transport alive).
 *
 * (The dead `closeIdle()` API and its two characterization tests were removed in
 * #684 once the deletion was reviewed — a deliberate D22 hard-cut, not a silent
 * behavior drop.)
 *
 * These MUST stay green on current code.
 *
 * Learning map: #620 (warm-lease lifetime / first-open-instant).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseLifetimeCharacterizationTest {

    @Test
    fun `refcount keeps the transport alive until the last holder releases`() = runTest {
        // Two foreground holders share one warm transport (refcount = 2).
        // Releasing one keeps it alive; releasing the last lets the warm TTL
        // start. Pins the multi-holder refcount contract the warm lease relies
        // on so a switch/share does not tear down the active transport.
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        val a = manager.acquire(TARGET).getOrThrow()
        val b = manager.acquire(TARGET).getOrThrow()
        assertEquals("the two holders share one dial", 1, connector.connectCount)
        assertSame(session, a.session)
        assertSame(session, b.session)

        a.release()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse("a remaining holder keeps the warm transport alive past the TTL", session.closed)

        b.release()
        advanceTimeBy(999)
        runCurrent()
        assertFalse("the warm lease stays open within the idle TTL", session.closed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("the last release lets the warm lease expire after the TTL", session.closed)
    }

    @Test
    fun `a reacquire inside the warm window reuses the transport instead of dialing fresh`() = runTest {
        // The instant-first-open guarantee at the lifetime layer: a reacquire
        // BEFORE the warm window drains reuses the existing transport (no new
        // dial, isNewConnection == false). After the window drains, a fresh
        // acquire dials a new transport (isNewConnection == true). Pins the
        // warm-reuse vs fresh-dial boundary.
        val warm = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(warm, fresh)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        // First open + release: leaves the transport warm.
        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(500) // still inside the warm window
        runCurrent()

        // Reacquire inside the window: WARM REUSE, no fresh dial.
        val reused = manager.acquire(TARGET).getOrThrow()
        assertEquals("a reacquire inside the warm window must NOT dial again", 1, connector.connectCount)
        assertSame(warm, reused.session)
        assertFalse("the warm reacquire reuses the existing transport", reused.isNewConnection)
        reused.release()

        // Let the warm window fully drain so the transport closes.
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(warm.closed)

        // Now a fresh acquire dials a brand-new transport.
        val redialed = manager.acquire(TARGET).getOrThrow()
        assertEquals("after the warm window drains, a fresh dial opens a new transport", 2, connector.connectCount)
        assertSame(fresh, redialed.session)
        assertTrue("the post-drain acquire owns a fresh connection", redialed.isNewConnection)
        redialed.release()
    }

    @Test
    fun `switching across three hosts does not cold-redial a still-warm host`() = runTest {
        // Issue #996 (reproduce-first, D33/G10): the lease pool's idle cap must
        // cover a realistic multi-host working set. The maintainer's reported
        // journey is a 3-host round-trip A->B->C->A: at the moment the user
        // switches BACK to A, A's still-warm idle transport must NOT be trimmed
        // out from under them and cold-redialed.
        //
        // RED on the PRE-FIX cap of 2: after C goes idle the pool holds three
        // idle entries {A,B,C}; trimIdleLocked evicts the LRU (A) the instant it
        // exceeds the cap, so switching back to A is a fresh handshake
        // (isNewConnection == true, connectCount for A bumps).
        //
        // GREEN on the SHIPPED default cap (4): {A,B,C} (3) <= 4, no trim, A is
        // reused warm (isNewConnection == false, no extra handshake for A).
        //
        // The load-bearing, behavior-true assertions (G6) are BOTH the
        // per-host counting-connector handshake count AND isNewConnection ==
        // false — isNewConnection alone could be satisfied by a proxy.
        val connector = MultiHostCountingConnector()
        val manager = leaseManager(
            connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = SshLeaseManager.DEFAULT_MAX_IDLE_LEASES,
        )

        // Open + immediately release each host (each goes idle, warm, TTL armed).
        manager.acquire(TARGET_A).getOrThrow().release() // A idle
        manager.acquire(TARGET_B).getOrThrow().release() // B idle
        manager.acquire(TARGET_C).getOrThrow().release() // C idle -> trims A pre-fix
        runCurrent()

        assertEquals("three distinct hosts -> three cold dials so far", 3, connector.totalConnects)
        val aConnectsBefore = connector.connectsFor(TARGET_A.leaseKey)
        assertEquals("host A dialed exactly once on its first open", 1, aConnectsBefore)

        // Switch back to A. A was warm three steps ago and the network never
        // dropped: this must be a warm reuse, not a self-inflicted cold redial.
        val a2 = manager.acquire(TARGET_A).getOrThrow()
        assertFalse(
            "host A must reuse its still-warm lease on switch-back, not cold-redial",
            a2.isNewConnection,
        )
        assertEquals(
            "no extra handshake for a still-warm host on switch-back",
            aConnectsBefore,
            connector.connectsFor(TARGET_A.leaseKey),
        )
        a2.release()
    }

    @Test
    fun `the idle cap still trims the lru once the working set exceeds it`() = runTest {
        // Issue #996: raising the cap must NOT make the pool unbounded — the
        // existing LRU + TTL trim must still be the ceiling. With the shipped
        // default cap of 4, opening a FIFTH idle host pushes the idle set to 5,
        // exceeding the cap, so the LRU (the first-idled host) is IdleTrimmed and
        // a switch-back to it IS a genuine cold redial. Proves the bound holds.
        val connector = MultiHostCountingConnector()
        val manager = leaseManager(
            connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = SshLeaseManager.DEFAULT_MAX_IDLE_LEASES,
        )

        val trimmedKeys = mutableListOf<SshLeaseKey>()
        val collector = launch {
            manager.stateEvents.collect { event ->
                if (event.closeReason == SshLeaseCloseReason.IdleTrimmed) {
                    trimmedKeys += event.key
                }
            }
        }
        runCurrent()

        // Five distinct hosts, each opened then released (idled), in order.
        val hosts = listOf(TARGET_A, TARGET_B, TARGET_C, TARGET_D, TARGET_E)
        hosts.forEach { manager.acquire(it).getOrThrow().release() }
        runCurrent()

        // The cap is 4; the 5th idle host trips the trim, evicting the LRU (A,
        // the first-idled). Exactly one host is trimmed and it is the oldest.
        assertEquals("exactly one host is trimmed once the cap is exceeded", 1, trimmedKeys.size)
        assertEquals("the LRU (first-idled) host is the one trimmed", TARGET_A.leaseKey, trimmedKeys.single())

        // Switching back to the trimmed LRU host IS a genuine cold redial: the
        // cap correctly bounds the pool.
        val a2 = manager.acquire(TARGET_A).getOrThrow()
        assertTrue("the trimmed LRU host cold-redials on switch-back", a2.isNewConnection)
        assertEquals("the trimmed host dials a second time", 2, connector.connectsFor(TARGET_A.leaseKey))
        a2.release()

        collector.cancel()
    }

    @Test
    fun `the default idle ttl keeps a released lease warm for sixty seconds`() = runTest {
        // The warm window is exactly DEFAULT_IDLE_TTL_MILLIS = 60s (the value the
        // target architecture wants to make THE single grace clock — collapsing
        // the VM's separate 8s passive-grace into this one lease-anchored 60s).
        // Pins the constant + the boundary so a Phase-2 change to the grace clock
        // is a deliberate, visible edit.
        assertEquals(60_000L, SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS)

        val session = FakeSshSession()
        val manager = SshLeaseManager(
            connector = QueueLeaseConnector(session),
            scope = this,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

        manager.acquire(TARGET).getOrThrow().release()

        advanceTimeBy(59_999)
        runCurrent()
        assertFalse("the released lease stays warm up to the 60s boundary", session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("the released lease closes exactly at the 60s boundary", session.closed)
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

    /**
     * Issue #996: a connector that mints a fresh [FakeSshSession] per dial and
     * counts dials PER lease key, so a multi-host journey can assert exactly
     * which host got cold-redialed (vs reused warm). Distinct from
     * [QueueLeaseConnector] (single-host, global queue) — here every host has an
     * independent transport and an independent connect counter.
     */
    private class MultiHostCountingConnector : SshLeaseConnector {
        private val countsByKey: MutableMap<SshLeaseKey, Int> = mutableMapOf()
        var totalConnects: Int = 0
            private set

        fun connectsFor(key: SshLeaseKey): Int = countsByKey[key] ?: 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            countsByKey[target.leaseKey] = (countsByKey[target.leaseKey] ?: 0) + 1
            totalConnects += 1
            return Result.success(FakeSshSession())
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

        // Issue #996: distinct per-host targets for the multi-host journey.
        private fun targetForHost(host: String): SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = host,
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-$host",
            ),
            key = SshKey.Path(File("/tmp/key-$host")),
        )

        val TARGET_A: SshLeaseTarget = targetForHost("hostA")
        val TARGET_B: SshLeaseTarget = targetForHost("hostB")
        val TARGET_C: SshLeaseTarget = targetForHost("hostC")
        val TARGET_D: SshLeaseTarget = targetForHost("hostD")
        val TARGET_E: SshLeaseTarget = targetForHost("hostE")
    }
}
