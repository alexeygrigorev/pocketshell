package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `default idle ttl keeps released lease warm for sixty seconds`() = runTest {
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
    fun `hasLiveLease reflects active idle and closed transports without mutating the pool`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 1_000)

        // No lease yet.
        assertFalse(
            "no lease acquired: pool must report no live lease for the key",
            manager.hasLiveLease(TARGET.leaseKey),
        )
        assertFalse(
            "an unrelated key must report no live lease",
            manager.hasLiveLease(OTHER_TARGET.leaseKey),
        )

        // Actively leased -> live.
        val lease = manager.acquire(TARGET).getOrThrow()
        assertTrue("actively leased transport is live", manager.hasLiveLease(TARGET.leaseKey))

        // Released but warm/idle within TTL -> still live, and the probe itself
        // must NOT close it (no mutation).
        lease.release()
        advanceTimeBy(999)
        runCurrent()
        assertTrue("warm idle lease within TTL is live", manager.hasLiveLease(TARGET.leaseKey))
        assertFalse("hasLiveLease must not close the idle transport", session.closed)

        // After the idle TTL expires -> the transport closes -> not live.
        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.closed)
        assertFalse(
            "closed transport must report no live lease",
            manager.hasLiveLease(TARGET.leaseKey),
        )
    }

    @Test
    fun `concurrent acquires for the same key coalesce into one connect`() = runTest {
        // Issue #620: host detail's warm-lease acquire and the FIRST
        // session-open tap dial the SAME key concurrently. They must share ONE
        // SSH handshake so the tap reuses the warm transport instead of racing
        // a second 3-4s connect (the bug behind the maintainer's "3-4s on the
        // first open"). A second FakeSshSession is queued only to prove it is
        // NEVER dialed.
        val shared = FakeSshSession()
        val neverUsed = FakeSshSession()
        val connector = GatedLeaseConnector(shared, neverUsed)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        // First acquire owns the connect and parks inside connector.connect().
        val firstDeferred = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals(1, connector.startedConnects)

        // Second acquire arrives while the first connect is still in flight. It
        // must NOT start a second connect — it joins the in-flight one.
        val secondDeferred = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals(
            "second concurrent acquire must not dial a second connect",
            1,
            connector.startedConnects,
        )

        // Let the single shared connect complete.
        connector.releaseConnect()
        runCurrent()
        val lease1 = firstDeferred.await()
        val lease2 = secondDeferred.await()

        assertEquals("only one SSH handshake for the coalesced opens", 1, connector.startedConnects)
        assertSame("both acquires share the same warm transport", shared, lease1.session)
        assertSame(shared, lease2.session)
        assertTrue("exactly one of the two acquires owns the fresh transport", lease1.isNewConnection)
        assertFalse("the coalesced acquire reuses, it does not re-dial", lease2.isNewConnection)
        assertFalse("the redundant queued session must never be dialed", neverUsed.closed)

        // Both holders own a ref: releasing one keeps the transport warm.
        lease1.release()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse("a remaining holder keeps the shared transport alive", shared.closed)
        lease2.release()
    }

    @Test
    fun `hasLiveOrConnectingLease is true while a connect is in flight and emits Connecting`() = runTest {
        // Issue #620: the FIRST session open from host detail must read the
        // host's in-flight warm handshake as "warm" so it shows Attaching, not
        // a cold Connecting overlay. hasLiveOrConnectingLease reports true for an
        // in-flight connect; a Connecting state event lets a synchronous consumer
        // (the tmux warm-open hint) pick the same warm affordance.
        val session = FakeSshSession()
        val connector = GatedLeaseConnector(session)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val events = mutableListOf<SshLeaseConnectionState>()
        val eventsCollector = launch { manager.stateEvents.collect { events.add(it.state) } }
        runCurrent()

        // No connect yet: not live, not connecting.
        assertFalse(manager.hasLiveLease(TARGET.leaseKey))
        assertFalse(manager.hasLiveOrConnectingLease(TARGET.leaseKey))

        // Start the connect; it parks in the gated connector (in flight).
        val acquireJob = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertFalse("transport not up yet: hasLiveLease must be false", manager.hasLiveLease(TARGET.leaseKey))
        assertTrue(
            "an in-flight connect must read as live-or-connecting",
            manager.hasLiveOrConnectingLease(TARGET.leaseKey),
        )
        assertTrue("starting a connect emits Connecting", events.contains(SshLeaseConnectionState.Connecting))

        // Complete it; now genuinely live.
        connector.releaseConnect()
        runCurrent()
        val lease = acquireJob.await()
        assertTrue(manager.hasLiveLease(TARGET.leaseKey))
        assertTrue(manager.hasLiveOrConnectingLease(TARGET.leaseKey))
        assertTrue("a completed connect emits Connected", events.contains(SshLeaseConnectionState.Connected))

        lease.release()
        eventsCollector.cancel()
    }

    @Test
    fun `failed in-flight connect retracts the connecting hint`() = runTest {
        // Issue #620: a Connecting hint for a key whose handshake FAILS must be
        // retracted (Closed) so a synchronous consumer drops the stale Attaching
        // hint instead of treating a dead key as warm.
        val connector = GatedLeaseConnector(null)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val events = mutableListOf<SshLeaseConnectionState>()
        val eventsCollector = launch { manager.stateEvents.collect { events.add(it.state) } }
        runCurrent()

        val acquireJob = async { manager.acquire(TARGET) }
        runCurrent()
        assertTrue(manager.hasLiveOrConnectingLease(TARGET.leaseKey))
        assertTrue(events.contains(SshLeaseConnectionState.Connecting))

        connector.releaseConnect()
        runCurrent()
        val result = acquireJob.await()
        assertTrue("a failed connect surfaces failure", result.isFailure)
        assertFalse(
            "a failed connect must retract the connecting hint",
            manager.hasLiveOrConnectingLease(TARGET.leaseKey),
        )
        assertTrue(
            "a failed in-flight connect emits Closed to retract the hint",
            events.contains(SshLeaseConnectionState.Closed),
        )
        eventsCollector.cancel()
    }

    @Test
    fun `awaiter falls back to its own connect when the shared connect fails`() = runTest {
        // Issue #620: if the coalesced (owning) connect fails, the acquire that
        // joined it must NOT silently fail — it dials its own transport so the
        // caller still gets a usable lease.
        val good = FakeSshSession()
        val connector = GatedLeaseConnector(null, good)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val firstDeferred = async { manager.acquire(TARGET) }
        runCurrent()
        val secondDeferred = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("awaiter coalesced behind the in-flight connect", 1, connector.startedConnects)

        // The shared connect resolves as a failure.
        connector.releaseConnect()
        runCurrent()

        val first = firstDeferred.await()
        assertTrue("the owning acquire surfaces the connect failure", first.isFailure)

        // The awaiter falls back to its own dial and succeeds.
        connector.releaseConnect()
        runCurrent()
        val second = secondDeferred.await()
        assertTrue("the awaiter recovers with its own connect", second.isSuccess)
        assertSame(good, second.getOrThrow().session)
        assertEquals("exactly two connects: failed shared + awaiter fallback", 2, connector.startedConnects)
    }

    @Test
    fun `hasLiveLease is false for a transport that dropped while pooled`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        assertTrue(manager.hasLiveLease(TARGET.leaseKey))

        // The transport silently dies while still pooled (network drop). The
        // pooled entry remains, but the session is no longer connected.
        session.closed = true
        assertFalse(
            "a dropped pooled transport must report no live lease",
            manager.hasLiveLease(TARGET.leaseKey),
        )
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
    fun `evict idle closes connected retained lease so next acquire opens fresh session`() = runTest {
        val stale = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(stale, fresh)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        manager.acquire(TARGET).getOrThrow().release()

        assertFalse(stale.closed)
        assertTrue(manager.evictIdle(TARGET.leaseKey))

        val replacement = manager.acquire(TARGET).getOrThrow()

        assertTrue(stale.closed)
        assertEquals(1, stale.closeCount)
        assertEquals(2, connector.connectCount)
        assertSame(fresh, replacement.session)

        replacement.release()
    }

    @Test
    fun `evict idle ignores active lease`() = runTest {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        val active = manager.acquire(TARGET).getOrThrow()

        assertFalse(manager.evictIdle(TARGET.leaseKey))
        assertFalse(session.closed)

        val shared = manager.acquire(TARGET).getOrThrow()
        assertEquals(1, connector.connectCount)
        assertSame(session, shared.session)

        active.release()
        shared.release()
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
     * Issue #620: a connector whose every `connect` parks until the test
     * releases it, so a test can observe the in-flight window where a second
     * acquire for the same key would race a redundant handshake. Each queued
     * session resolves successfully; a `null` slot resolves as a connect
     * failure (used to exercise the awaiter-fallback path).
     */
    private class GatedLeaseConnector(
        private vararg val sessions: FakeSshSession?,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
        private val gates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
            val session = sessions.getOrNull(index)
                ?: return Result.failure(java.io.IOException("connect $index failed"))
            return Result.success(session)
        }

        fun releaseConnect() {
            val gate = gates.removeFirstOrNull()
                ?: error("no in-flight connect to release")
            gate.complete(Unit)
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
