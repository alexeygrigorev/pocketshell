package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
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

@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseManagerTest {
    @Test
    fun `same host and credentials reuse a live leased session`() = runTest {
        val first = FakeSshSession()
        val connector = QueueLeaseConnector(first)
        val manager = leaseManager(connector)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET).getOrThrow()

        assertEquals(1, connector.connectCount)
        assertSame(first, lease1.session)
        assertSame(first, lease2.session)

        lease1.release()
        assertFalse(first.closed)
        lease2.release()
    }

    @Test
    fun `different credential ids do not share a host connection`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET.copy(leaseKey = TARGET.leaseKey.copy(credentialId = "key-b"))).getOrThrow()

        assertEquals(2, connector.connectCount)
        assertNotSame(lease1.session, lease2.session)

        lease1.release()
        lease2.release()
    }

    @Test
    fun `released idle session closes after ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 1_000)

        val lease = manager.acquire(TARGET).getOrThrow()
        lease.release()

        advanceTimeBy(999)
        runCurrent()
        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.closed)
    }

    @Test
    fun `reacquire before ttl cancels idle close`() = runTest {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(500)
        val lease = manager.acquire(TARGET).getOrThrow()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, connector.connectCount)
        assertFalse(session.closed)

        lease.release()
    }

    @Test
    fun `active lease is not closed by another holder release or ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 100)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET).getOrThrow()
        lease1.release()
        advanceTimeBy(100)
        runCurrent()

        assertFalse(session.closed)

        lease2.release()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(session.closed)
    }

    @Test
    fun `idle cap closes oldest released session`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = 1,
        )

        manager.acquire(TARGET).getOrThrow().release()
        manager.acquire(OTHER_TARGET).getOrThrow().release()

        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `process stop closes all idle warm hosts`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = 2,
        )

        manager.acquire(TARGET).getOrThrow().release()
        manager.acquire(OTHER_TARGET).getOrThrow().release()

        assertFalse(first.closed)
        assertFalse(second.closed)

        manager.onProcessStopped()

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun `active lease released after process stop closes without idle ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 60_000,
        )

        val lease = manager.acquire(TARGET).getOrThrow()
        manager.onProcessStopped()

        assertFalse("active foreground owner still holds the lease", session.closed)

        lease.release()
        advanceTimeBy(1)
        runCurrent()

        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `process start restores warm ttl behavior`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 1_000,
        )

        manager.onProcessStopped()
        manager.onProcessStarted()
        val lease = manager.acquire(TARGET).getOrThrow()
        lease.release()
        advanceTimeBy(999)
        runCurrent()

        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()

        assertTrue(session.closed)
    }

    @Test
    fun `explicit disconnect closes host once and stale release is ignored`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 60_000,
        )

        val lease = manager.acquire(TARGET).getOrThrow()
        manager.disconnect(TARGET.leaseKey)
        lease.release()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `state events include idle expiry and lifecycle close reasons`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(first, second),
            idleTtlMillis = 1_000,
            maxIdleLeases = 2,
        )
        val events = mutableListOf<SshLeaseStateEvent>()
        val collectJob = backgroundScope.launch {
            manager.stateEvents.toList(events)
        }
        runCurrent()

        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(1_000)
        runCurrent()
        manager.acquire(OTHER_TARGET).getOrThrow().release()
        manager.onProcessStopped()
        runCurrent()
        collectJob.cancel()

        assertTrue(
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Idle
            },
        )
        assertTrue(
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.IdleExpired
            },
        )
        assertTrue(
            events.any {
                it.key == OTHER_TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.ProcessStopped
            },
        )
    }

    @Test
    fun `disconnected idle entry is replaced on next acquire`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        first.connected = false
        val lease = manager.acquire(TARGET).getOrThrow()

        assertEquals(2, connector.connectCount)
        assertSame(second, lease.session)
    }

    @Test
    fun `adopted session is released through normal idle policy`() = runTest {
        val adopted = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(FakeSshSession()),
            idleTtlMillis = 0,
        )

        val lease = manager.adopt(TARGET, adopted).getOrThrow()

        assertSame(adopted, lease.session)
        assertFalse(lease.isNewConnection)
        assertFalse(adopted.closed)

        lease.release()

        assertTrue(adopted.closed)
    }

    @Test
    fun `adopt closes supplied session when matching lease already exists`() = runTest {
        val existing = FakeSshSession()
        val adopted = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(existing), idleTtlMillis = 1_000)
        val existingLease = manager.acquire(TARGET).getOrThrow()

        val adoptedLease = manager.adopt(TARGET, adopted).getOrThrow()

        assertSame(existing, adoptedLease.session)
        assertTrue("redundant adopted session must not leak", adopted.closed)
        assertFalse(existing.closed)

        existingLease.release()
        adoptedLease.release()
    }

    @Test
    fun `release from replaced disconnected lease does not mutate active replacement`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        val staleLease = manager.acquire(TARGET).getOrThrow()
        first.connected = false
        val replacementLease = manager.acquire(TARGET).getOrThrow()

        staleLease.release()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, connector.connectCount)
        assertSame(second, replacementLease.session)
        assertFalse(second.closed)

        replacementLease.release()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(second.closed)
    }

    @Test
    fun `release completes ref count update when caller is cancelled`() = runTest {
        val releaseCanFinish = CompletableDeferred<Unit>()
        var startedReleaseCount = 0
        var completedReleaseCount = 0
        val lease = SshLease(
            key = TARGET.leaseKey,
            session = FakeSshSession(),
            isNewConnection = true,
            entryId = 1L,
        ) { _, _ ->
            startedReleaseCount += 1
            releaseCanFinish.await()
            completedReleaseCount += 1
        }

        val releaseJob = launch { lease.release() }
        runCurrent()

        assertEquals(1, startedReleaseCount)
        assertEquals(0, completedReleaseCount)

        releaseJob.cancel()
        runCurrent()
        releaseCanFinish.complete(Unit)
        releaseJob.join()

        assertEquals(1, completedReleaseCount)

        lease.release()
        assertEquals(1, startedReleaseCount)
        assertEquals(1, completedReleaseCount)
    }

    @Test
    fun `closing manager closes retained sessions and rejects new acquires`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        manager.close()
        val result = manager.acquire(TARGET)

        assertTrue(session.closed)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SshLeaseManagerClosedException)
    }

    private fun TestScope.leaseManager(
        connector: SshLeaseConnector,
        idleTtlMillis: Long = 1_000,
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

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

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

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

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
