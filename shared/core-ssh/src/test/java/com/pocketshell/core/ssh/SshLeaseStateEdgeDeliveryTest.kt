package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Issue #2314 — a saturated [SshLeaseManager.stateEvents] collector must never
 * make a critical transport edge disappear without an immediate diagnostic.
 *
 * These tests exercise only public lease operations. The collector deliberately
 * stops inside its callback while enough unique live leases are opened to exceed
 * the production 64-slot shared-flow buffer. The selected first lease has a
 * known successfully published `Connected` state before saturation, so its
 * later `Closed` edge has an unambiguous previous state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseStateEdgeDeliveryTest {
    private val diagnostics = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val diagnosticSignal = Channel<Pair<String, Map<String, Any?>>>(capacity = 128)

    @Before
    fun installDiagnostics() {
        diagnostics.clear()
        SshDiagnostics.install { event, fields ->
            val record = event to fields
            synchronized(diagnostics) { diagnostics += record }
            diagnosticSignal.trySend(record)
        }
    }

    @After
    fun resetDiagnostics() {
        SshDiagnostics.reset()
    }

    @Test
    fun `saturated slow collector records a dropped critical Closed edge with reconstructable telemetry`() =
        runTest {
            val manager = leaseManager()
            val collectorGate = CompletableDeferred<Unit>()
            val firstCollected = CompletableDeferred<SshLeaseStateEvent>()
            val collector = backgroundScope.launch {
                manager.stateEvents.collect { event ->
                    firstCollected.complete(event)
                    collectorGate.await()
                }
            }
            runCurrent()

            val held = mutableListOf<SshLease>()
            repeat(SATURATING_LEASE_COUNT) { index ->
                held += manager.acquire(target(index)).getOrThrow()
            }
            runCurrent()
            assertTrue("the collector must be actively blocked, not absent", firstCollected.isCompleted)

            // Exercise the diagnostic-only Idle classification while the same
            // bounded buffer is saturated. The exact Closed sequence below
            // proves this transition happened before the critical edge.
            held[1].release()
            val droppedKey = held.first().key
            manager.disconnect(droppedKey)

            val dropped = awaitDiagnostic { (event, fields) ->
                event == "lease_state_edge_dropped" &&
                    fields["key"] == droppedKey.hashCode().toLong() &&
                    fields["intendedState"] == SshLeaseConnectionState.Closed.name
            }
            assertNotNull(
                "the saturated critical Closed edge must be delivered or diagnosed immediately",
                dropped,
            )
            val fields = dropped!!.second
            assertEquals(droppedKey.host, fields["host"])
            assertEquals(droppedKey.port, fields["port"])
            assertEquals(droppedKey.user, fields["user"])
            assertEquals(droppedKey.credentialId, fields["credentialId"])
            assertEquals(droppedKey.knownHostsId, fields["knownHostsCredentialId"])
            assertEquals(SshLeaseConnectionState.Connected.name, fields["previousState"])
            assertEquals(SshLeaseConnectionState.Closed.name, fields["intendedState"])
            assertEquals(SshLeaseCloseReason.ExplicitDisconnect.name, fields["closeReason"])
            assertEquals("buffer_saturated", fields["dropReason"])
            assertEquals(
                SATURATING_LEASE_COUNT * 2L + 2L,
                fields["sequence"],
            )
            assertTrue(
                "drop telemetry must carry a positive manager-local edge sequence",
                (fields["sequence"] as? Long ?: 0L) > 0L,
            )
            assertTrue(
                "drop sequence must follow the first successfully published event",
                (fields["sequence"] as Long) > firstCollected.await().sequence,
            )
            val connectedDrop = awaitDiagnostic { (event, connectedFields) ->
                event == "lease_state_edge_dropped" &&
                    connectedFields["intendedState"] == SshLeaseConnectionState.Connected.name
            }
            assertNotNull(
                "saturation must also make a dropped Connected up-edge immediately observable",
                connectedDrop,
            )
            assertFalse(
                "best-effort Connecting and Idle diagnostics are classified separately from critical edges",
                synchronized(diagnostics) {
                    diagnostics.any { (event, diagnosticFields) ->
                        event == "lease_state_edge_dropped" &&
                            diagnosticFields["intendedState"] in setOf(
                                SshLeaseConnectionState.Connecting.name,
                                SshLeaseConnectionState.Idle.name,
                            )
                    }
                },
            )

            collectorGate.complete(Unit)
            collector.cancelAndJoin()

            // The final key's Connected attempt was beyond the saturated
            // buffer. Once the old collector is gone, reusing that still-live
            // transport must try Connected again. If failed tryEmit had falsely
            // advanced lastPublishedState, this reuse would be deduped away.
            val retriedPublication = CompletableDeferred<SshLeaseStateEvent>()
            val retryCollector = backgroundScope.launch {
                manager.stateEvents.collect { retriedPublication.complete(it) }
            }
            runCurrent()
            val retryLease = manager.acquire(target(SATURATING_LEASE_COUNT - 1)).getOrThrow()
            val retried = withTimeout(5_000L) { retriedPublication.await() }
            assertEquals(SshLeaseConnectionState.Connected, retried.state)
            assertTrue(retried.sequence > (fields["sequence"] as Long))
            retryCollector.cancelAndJoin()

            retryLease.release()
            held.drop(1).forEach { it.release() }
            manager.close()
        }

    @Test
    fun `saturation and slow collector cancellation never block lease IO or teardown`() = runTest {
        withTimeout(5_000L) {
            val manager = leaseManager()
            val collectorGate = CompletableDeferred<Unit>()
            val collector = backgroundScope.launch {
                manager.stateEvents.collect { collectorGate.await() }
            }
            runCurrent()

            val held = mutableListOf<SshLease>()
            repeat(SATURATING_LEASE_COUNT) { index ->
                held += manager.acquire(target(index)).getOrThrow()
            }

            collector.cancelAndJoin()
            manager.disconnect(held.first().key)
            held.drop(1).forEach { it.release() }
            manager.close()
        }
    }

    @Test
    fun `collector cancellation after tryEmit before publication decision is diagnosed as a dropped critical edge`() = runTest {
        val manager = leaseManager()
        val collectorGate = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            manager.stateEvents.collect { collectorGate.await() }
        }
        runCurrent()

        val lease = manager.acquire(target(0)).getOrThrow()
        runCurrent()
        manager.stateEventAfterTryEmitHookForTest = {
            manager.stateEventAfterTryEmitHookForTest = null
            collector.cancel()
            runCurrent()
        }

        manager.disconnect(lease.key)
        val dropped = awaitDiagnostic { (event, fields) ->
            event == "lease_state_edge_dropped" &&
                fields["intendedState"] == SshLeaseConnectionState.Closed.name
        }
        assertEquals("no_subscribers", dropped.second["dropReason"])
        assertEquals(SshLeaseConnectionState.Connected.name, dropped.second["previousState"])

        lease.release()
        manager.close()
    }

    @Test
    fun `direct diagnostic record returns before a blocking installed sink is released`() = runTest {
        val sinkEntered = CountDownLatch(1)
        val sinkRelease = CountDownLatch(1)
        val directReturned = CountDownLatch(1)
        SshDiagnostics.install { _, _ ->
            sinkEntered.countDown()
            sinkRelease.await(5, TimeUnit.SECONDS)
        }

        val directThread = thread(isDaemon = true, name = "issue-2314-direct-record-test") {
            try {
                SshDiagnostics.record("issue-2314-direct-blocking-probe")
            } finally {
                directReturned.countDown()
            }
        }
        try {
            assertTrue(
                "direct record must reach the installed sink",
                withContext(Dispatchers.IO) { sinkEntered.await(5, TimeUnit.SECONDS) },
            )
            assertTrue(
                "direct record must return while the sink remains blocked",
                withContext(Dispatchers.IO) { directReturned.await(1, TimeUnit.SECONDS) },
            )
        } finally {
            sinkRelease.countDown()
            directThread.join(5_000)
        }
    }

    @Test
    fun `direct diagnostic record contains a throwing sink and the worker remains usable`() = runTest {
        SshDiagnostics.install { _, _ -> throw IllegalStateException("issue-2314 direct sink") }
        val directResult = runCatching {
            SshDiagnostics.record("issue-2314-direct-throwing-probe")
        }
        assertTrue("direct record must contain an installed sink exception", directResult.isSuccess)

        val delivered = CompletableDeferred<Unit>()
        SshDiagnostics.install { event, _ ->
            if (event == "issue-2314-after-throw-probe") delivered.complete(Unit)
        }
        SshDiagnostics.record("issue-2314-after-throw-probe")
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { delivered.await() }
        }
    }

    @Test
    fun `blocking diagnostic sink cannot hold lease mutex during teardown`() = runTest {
        val sinkEntered = CountDownLatch(1)
        val sinkRelease = CountDownLatch(1)
        SshDiagnostics.install { _, _ ->
            sinkEntered.countDown()
            sinkRelease.await(5, TimeUnit.SECONDS)
        }
        val manager = leaseManager()
        // Occupy the bounded diagnostics worker independently so the manager
        // path is measured while an installed sink is genuinely blocked. This
        // keeps the teardown proof load-bearing even under a mutation that
        // suppresses the manager's particular edge emission.
        SshDiagnostics.recordNonBlocking("issue-2314-blocking-sink-probe")
        val acquireJob = launch(Dispatchers.Default) {
            manager.acquire(target(0))
        }
        var closeThread: Thread? = null
        try {
            assertTrue(
                "the test sink must actually be blocking before teardown is measured",
                withContext(Dispatchers.IO) { sinkEntered.await(5, TimeUnit.SECONDS) },
            )
            val closeFinished = CompletableFuture<Unit>()
            closeThread = thread(isDaemon = true, name = "issue-2314-close-test") {
                runCatching { manager.close() }
                    .onSuccess { closeFinished.complete(Unit) }
                    .onFailure { closeFinished.completeExceptionally(it) }
            }
            assertEquals(
                "manager teardown must not wait for an installed diagnostic sink",
                Unit,
                closeFinished.get(1, TimeUnit.SECONDS),
            )
            acquireJob.join()
        } finally {
            sinkRelease.countDown()
            closeThread?.join(5_000)
            acquireJob.cancelAndJoin()
            manager.close()
        }
    }

    @Test
    fun `throwing diagnostic sink is contained without failing lease operations`() = runTest {
        SshDiagnostics.install { _, _ -> throw IllegalStateException("issue-2314 test sink") }
        val manager = leaseManager()

        val result = withTimeout(1_000L) { manager.acquire(target(0)) }
        assertTrue("a diagnostic sink exception must not fail the lease", result.isSuccess)
        result.getOrThrow().release()
        withTimeout(1_000L) { manager.close() }
    }

    @Test
    fun `late collector does not make a no-subscriber Connected edge look published`() = runTest {
        val manager = leaseManager()
        val target = target(0)

        // SharedFlow(replay=0) accepts and immediately discards this pair while
        // no collector exists. Connected is critical, so that loss must be
        // diagnosed and must not advance lastPublishedState.
        val firstLease = manager.acquire(target).getOrThrow()
        val dropped = awaitDiagnostic { (event, fields) ->
            event == "lease_state_edge_dropped" &&
                fields["key"] == target.leaseKey.hashCode().toLong() &&
                fields["intendedState"] == SshLeaseConnectionState.Connected.name
        }
        assertEquals("no_subscribers", dropped.second["dropReason"])
        assertEquals(null, dropped.second["previousState"])

        val delivered = CompletableDeferred<SshLeaseStateEvent>()
        val collector = backgroundScope.launch {
            manager.stateEvents.collect { delivered.complete(it) }
        }
        runCurrent()

        val reused = manager.acquire(target).getOrThrow()
        val event = withTimeout(5_000L) { delivered.await() }
        assertEquals(
            "the first collector must still receive the live transport up-edge",
            SshLeaseConnectionState.Connected,
            event.state,
        )
        assertEquals(null, event.previousState)
        assertTrue(event.sequence > (dropped.second["sequence"] as Long))

        collector.cancelAndJoin()
        firstLease.release()
        reused.release()
        manager.close()
    }

    private fun kotlinx.coroutines.test.TestScope.leaseManager(): SshLeaseManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SshLeaseManager(
            connector = SshLeaseConnector { Result.success(FakeSshSession()) },
            scope = this,
            idleTtlMillis = 60_000L,
            connectTimeoutContext = dispatcher,
            abortTimeoutContext = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
    }

    private suspend fun awaitDiagnostic(
        predicate: (Pair<String, Map<String, Any?>>) -> Boolean,
    ): Pair<String, Map<String, Any?>> = withContext(Dispatchers.Default) {
        withTimeout(5_000L) { awaitDiagnosticUntil(predicate) }
    }

    private suspend fun awaitDiagnosticUntil(
        predicate: (Pair<String, Map<String, Any?>>) -> Boolean,
    ): Pair<String, Map<String, Any?>> {
        while (true) {
            val found = synchronized(diagnostics) { diagnostics.firstOrNull(predicate) }
            if (found != null) return found
            diagnosticSignal.receive()
        }
    }

    private fun target(index: Int): SshLeaseTarget {
        val keyPath = "/tmp/issue-2314-key-$index"
        return SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "issue-2314-$index.test",
                port = 22,
                user = "tester",
                credentialId = keyPath,
                knownHostsId = "issue-2314-known-hosts-$index",
            ),
            key = SshKey.Path(File(keyPath)),
        )
    }

    private class FakeSshSession : SshSession {
        private var closed = false

        override val isConnected: Boolean
            get() = !closed

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
        // 40 leases emit Connecting + Connected = 80 attempted events while the
        // first callback is blocked: deterministically beyond the fixed 64 slots.
        const val SATURATING_LEASE_COUNT: Int = 40
    }
}
