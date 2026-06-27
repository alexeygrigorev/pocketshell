package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import java.io.IOException
import java.io.InputStream

/**
 * EPIC #687 Phase-1 GATE — connect-coalescing characterization (the #620
 * learning at the SSH lease/transport layer).
 *
 * These tests PIN the CURRENT behavior of [SshLeaseManager]'s in-flight connect
 * coalescing so the Phase-2 hard-cut rewrite can be proven equivalent, not a
 * regression gamble. They MUST stay green on the current code. Where
 * [SshLeaseManagerTest] already covers a fact (happy-path coalesce,
 * `hasLiveOrConnectingLease`, the awaiter fallback), this file deliberately
 * pins the under-covered corners the EPIC brief calls out:
 *
 *  - the FIRST acquirer registers the in-flight `Deferred` under the mutex so a
 *    concurrent acquirer JOINS it (no second handshake);
 *  - owner FAILURE completes the slot and wakes awaiters;
 *  - owner CANCELLATION completes the slot and wakes awaiters (the
 *    `NonCancellable` `finally` cleanup at `runOwnedConnect`) — without this an
 *    awaiter would block forever on a deferred that never completes;
 *  - a fresh acquire AFTER an in-flight connect drains opens its own connect
 *    (the slot is cleared, not leaked).
 *
 * Learning map: #620 (connect coalescing / warm-lease first-open).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseCoalescingCharacterizationTest {

    @Test
    fun `the first acquirer owns the in-flight connect and a concurrent acquirer joins it`() = runTest {
        // #620: the FIRST acquire for a cold key registers the in-flight
        // Deferred under the mutex and OWNS the dial; a second acquire arriving
        // while that connect is in flight must NOT start a second handshake — it
        // parks on the same Deferred and reuses the one warm transport. A second
        // queued session is present only to prove it is never dialed.
        val shared = FakeSshSession()
        val neverDialed = FakeSshSession()
        val connector = GatedLeaseConnector(shared, neverDialed)
        val manager = leaseManager(connector)

        val owner = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals("the first acquirer owns exactly one in-flight connect", 1, connector.startedConnects)

        val joiner = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals(
            "a concurrent acquirer must JOIN the in-flight connect, not start a second",
            1,
            connector.startedConnects,
        )

        connector.releaseConnect()
        runCurrent()
        val ownerLease = owner.await()
        val joinerLease = joiner.await()

        assertEquals("only one SSH handshake for the coalesced opens", 1, connector.startedConnects)
        assertTrue("the owner holds the freshly dialed transport", ownerLease.isNewConnection)
        assertFalse("the joiner reuses, it never re-dials", joinerLease.isNewConnection)
        assertSame("both leases share the one warm transport", shared, ownerLease.session)
        assertSame(shared, joinerLease.session)
        assertFalse("the redundant queued transport is never dialed", neverDialed.closed)

        ownerLease.release()
        joinerLease.release()
    }

    @Test
    fun `owner connect failure completes the slot and wakes the joined awaiter with the same failure`() = runTest {
        // When the OWNING connect fails, waiters that joined it must not serially
        // retry. They receive the same failure, and a future fresh acquire can
        // decide when to dial again.
        val connector = GatedLeaseConnector(null, FakeSshSession())
        val manager = leaseManager(connector)

        val owner = async { manager.acquire(TARGET) }
        runCurrent()
        val joiner = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("the awaiter coalesced behind the in-flight connect", 1, connector.startedConnects)

        connector.releaseConnect() // resolve the owning connect as a failure
        runCurrent()
        val ownerResult = owner.await()
        assertTrue("the owning acquire surfaces the connect failure", ownerResult.isFailure)

        runCurrent()
        val joinerResult = joiner.await()
        assertTrue("the woken awaiter receives the shared failure", joinerResult.isFailure)
        assertEquals("the awaiter must not serially retry after shared failure", 1, connector.startedConnects)
    }

    @Test
    fun `owner cancellation completes the slot so a joined awaiter is not stranded`() = runTest {
        // #620: the owner's connect is CANCELLED mid-flight (its acquire scope
        // dies). The NonCancellable finally in runOwnedConnect must clear the
        // in-flight slot and complete the Deferred so the awaiter that parked on
        // it is woken with cancellation — never blocked forever on a Deferred
        // that would otherwise never complete, and never serially retrying.
        val connector = GatedLeaseConnector(FakeSshSession(), FakeSshSession())
        val manager = leaseManager(connector)

        val owner = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals(1, connector.startedConnects)

        val awaiter = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("the awaiter joined the in-flight connect", 1, connector.startedConnects)

        // Cancel the owner while its connect is still parked in the connector.
        // join() lets the owner's NonCancellable finally (which clears the
        // in-flight slot and completes the Deferred) settle before we assert.
        owner.cancel()
        owner.join()
        runCurrent()

        // The awaiter must now be unblocked with the same cancellation — proof
        // the slot was completed rather than stranded.
        assertEquals(
            "owner cancellation wakes the awaiter without a serial retry",
            1,
            connector.startedConnects,
        )

        runCurrent()
        val awaiterResult = awaiter.await()
        assertTrue("the woken awaiter completes with cancellation", awaiterResult.isFailure)
    }

    @Test
    fun `a fresh acquire after the in-flight connect drains opens its own connect`() = runTest {
        // #620: the in-flight slot is per-connect, not sticky. Once an owned
        // connect resolves and its lease is released back to idle, a later cold
        // acquire on the SAME key after the warm lease has expired must open a
        // brand-new connect (the slot was cleared, never leaked).
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(1_001) // let the warm lease expire and close
        runCurrent()
        assertTrue("the first warm lease expired and closed", first.closed)

        val fresh = manager.acquire(TARGET).getOrThrow()
        assertEquals("a cold acquire after drain dials a fresh connect", 2, connector.connectCount)
        assertSame(second, fresh.session)

        fresh.release()
    }

    // ---- harness (mirrors SshLeaseManagerTest's fakes; kept local so this
    // characterization file is self-contained) ----

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
     * Connector whose every `connect` parks until the test releases it, so a
     * test can observe the in-flight window. Each queued session resolves
     * successfully; a `null` slot resolves as a connect failure.
     */
    private class GatedLeaseConnector(
        private vararg val sessions: FakeSshSession?,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
        // A parked connect removes its own gate on completion OR cancellation so
        // a cancelled owner's gate can never be the one `releaseConnect()` later
        // wakes — otherwise a cancelled-owner test would release the orphaned
        // gate and leave the awaiter's own dial parked forever.
        private val gates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            try {
                gate.await()
            } finally {
                gates.remove(gate)
            }
            val session = sessions.getOrNull(index)
                ?: return Result.failure(IOException("connect $index failed"))
            return Result.success(session)
        }

        fun releaseConnect() {
            val gate = gates.firstOrNull()
                ?: error("no in-flight connect to release")
            gate.complete(Unit)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true

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
