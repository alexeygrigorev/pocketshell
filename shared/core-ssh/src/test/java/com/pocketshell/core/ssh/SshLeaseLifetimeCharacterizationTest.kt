package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 *    keeps the transport alive);
 *  - `closeIdle()` behavior — NOTE: this entry point has NO production caller and
 *    NO sibling unit test; per the #684 A4 audit it is a DEAD/superseded API and
 *    a Phase-2 hard-cut candidate. We pin its current behavior here so the
 *    deletion is a deliberate, reviewed removal, NOT a silent behavior drop.
 *
 * These MUST stay green on current code.
 *
 * Learning map: #620 (warm-lease lifetime / first-open-instant); D22 hard-cut
 * candidate flag for `closeIdle`.
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

    @Test
    fun `closeIdle closes only zero-ref leases and leaves active holders untouched`() = runTest {
        // DEAD-API CHARACTERIZATION (#684 A4): closeIdle() has no production
        // caller and no sibling unit test — it is a Phase-2 hard-cut deletion
        // candidate. We pin its CURRENT contract so the removal is reviewed, not
        // silent: it closes every zero-ref (idle) lease immediately, and leaves
        // an actively-held lease alone.
        val active = FakeSshSession()
        val idle = FakeSshSession()
        val connector = QueueLeaseConnector(active, idle)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val activeLease = manager.acquire(TARGET).getOrThrow() // refcount 1, stays
        manager.acquire(OTHER_TARGET).getOrThrow().release() // refcount 0, idle/warm

        assertFalse("the idle lease is still warm before closeIdle", idle.closed)

        manager.closeIdle()

        assertTrue("closeIdle closes the zero-ref idle lease immediately", idle.closed)
        assertFalse("closeIdle must leave an actively-held lease open", active.closed)

        // The active holder's transport is still usable and still warm.
        assertTrue(manager.hasLiveLease(TARGET.leaseKey))
        activeLease.release()
    }

    @Test
    fun `closeIdle emits an explicit-disconnect close event for the evicted idle lease`() = runTest {
        // Companion characterization of the dead closeIdle() API: it surfaces the
        // idle lease's close as ExplicitDisconnect on the state-event stream. Pin
        // it so a Phase-2 deletion that re-routes this signal is a deliberate
        // edit (the #329/#679 lifecycle-signal seam must stay honest).
        val idle = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(idle), idleTtlMillis = 60_000)

        val events = mutableListOf<SshLeaseStateEvent>()
        val collector = backgroundScope.launch { manager.stateEvents.collect { events.add(it) } }
        runCurrent()

        manager.acquire(TARGET).getOrThrow().release() // -> warm/idle
        manager.closeIdle()
        runCurrent()
        collector.cancel()

        assertTrue(
            "closeIdle emits Closed/ExplicitDisconnect for the evicted idle lease",
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.ExplicitDisconnect
            },
        )
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

        val OTHER_TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "example.test",
                port = 22,
                user = "deploy",
                credentialId = "/tmp/key-b",
            ),
            key = SshKey.Path(File("/tmp/key-b")),
        )
    }
}
