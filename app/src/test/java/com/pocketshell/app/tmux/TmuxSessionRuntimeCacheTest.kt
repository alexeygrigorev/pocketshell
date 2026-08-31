package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionRuntimeCacheTest {

    @Test
    fun removeEvictsOnlyMatchingRuntimeKey() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val sessionA = cachedRuntime("work")
        val sessionB = cachedRuntime("other")
        val otherHost = cachedRuntime("work", hostId = 2L)

        assertTrue(cache.put(sessionA).isEmpty())
        assertTrue(cache.put(sessionB).isEmpty())
        assertTrue(cache.put(otherHost).isEmpty())

        assertSame(sessionB, cache.remove(sessionB.key))

        assertEquals(listOf(sessionA.key, otherHost.key), cache.snapshotKeys())
        assertTrue(cache.contains(sessionA.key))
        assertFalse(cache.contains(sessionB.key))
        assertTrue(cache.contains(otherHost.key))
    }

    @Test
    fun removeHostExplicitlyClearsEveryRuntimeForHost() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val sessionA = cachedRuntime("work")
        val sessionB = cachedRuntime("other")
        val otherHost = cachedRuntime("work", hostId = 2L)

        cache.put(sessionA)
        cache.put(sessionB)
        cache.put(otherHost)

        assertEquals(listOf(sessionA, sessionB), cache.removeHost(1L))

        assertEquals(listOf(otherHost.key), cache.snapshotKeys())
    }

    @Test
    fun removeSessionEvictsOnlyKilledGeneration() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val killed = cachedRuntime("work", durableSessionKey = "tmux:1:\$0:100")
        val sameNameSuccessor = cachedRuntime("work", durableSessionKey = "tmux:1:\$1:200")
        val sameNameOtherHost = cachedRuntime("work", hostId = 2L)
        val otherSession = cachedRuntime("deploy")

        cache.put(killed)
        cache.put(sameNameSuccessor)
        cache.put(sameNameOtherHost)
        cache.put(otherSession)

        assertEquals(
            listOf(killed),
            cache.removeSession(
                hostId = 1L,
                generation = TmuxSessionGeneration("\$0", 100L),
            ),
        )

        assertEquals(
            listOf(sameNameSuccessor.key, sameNameOtherHost.key, otherSession.key),
            cache.snapshotKeys(),
        )
        assertFalse(
            "a killed session's warm runtime must not be reusable by a same-name successor",
            cache.contains(killed.key),
        )
        assertTrue(cache.contains(sameNameOtherHost.key))
        assertTrue(cache.contains(sameNameSuccessor.key))
        assertTrue(cache.contains(otherSession.key))
    }

    @Test
    fun activateMissesSameNameRuntimeWithDifferentDurableIdentity() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val killed = cachedRuntime("work", durableSessionKey = "tmux:1:\$0:100")
        val successorKey = killed.key.copy(durableSessionKey = "tmux:1:\$1:200")

        cache.put(killed)

        val activation = cache.activate(successorKey)

        assertEquals(CacheActivation(runtime = null, evicted = emptyList()), activation)
        assertTrue(
            "different durable identities must not activate each other's cached runtime",
            cache.contains(killed.key),
        )
    }

    @Test
    fun nullDurableIdentityKeepsLegacyNameKeyedActivation() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val runtime = cachedRuntime("work")

        cache.put(runtime)

        assertEquals(CacheActivation(runtime = runtime, evicted = emptyList()), cache.activate(runtime.key))
    }

    @Test
    fun durableSelectionActivatesNameOnlyPrewarmForSameHostAndSession() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val nameOnlyPrewarm = cachedRuntime("work")
        val selectedKey = nameOnlyPrewarm.key.copy(durableSessionKey = "tmux:1:\$7:700")

        cache.put(nameOnlyPrewarm)

        assertEquals(
            CacheActivation(runtime = nameOnlyPrewarm, evicted = emptyList()),
            cache.activate(selectedKey),
        )
        assertEquals(emptyList<TmuxRuntimeKey>(), cache.snapshotKeys())
    }

    @Test
    fun durableSelectionCannotPromoteNameOnlyPrewarmFromOldHostTrust() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val oldTrustPrewarm = cachedRuntime(
            "work",
            trustedHostKeySha256 = "SHA256:old",
        )
        val selectedAfterRekey = oldTrustPrewarm.key.copy(
            durableSessionKey = "tmux:1:\$7:700",
            trustedHostKeySha256 = "SHA256:new",
        )

        cache.put(oldTrustPrewarm)

        assertFalse(cache.containsSession(1L, "work", "SHA256:new"))
        assertTrue(cache.containsSession(1L, "work", "SHA256:old"))
        assertEquals(
            CacheActivation(runtime = null, evicted = emptyList()),
            cache.activate(selectedAfterRekey),
        )
        assertEquals(listOf(oldTrustPrewarm.key), cache.snapshotKeys())
        assertEquals(
            listOf(oldTrustPrewarm),
            cache.removeHostTrustMismatches(1L, "SHA256:new"),
        )
        assertEquals(emptyList<TmuxRuntimeKey>(), cache.snapshotKeys())
    }

    @Test
    fun removeExactStaleBindingCannotRemoveSameSessionReplacement() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val old = cachedRuntime("work")
        val replacement = cachedRuntime("work")

        cache.put(old)
        assertEquals(listOf(old), cache.put(replacement))

        assertEquals(null, cache.removeExact(old.healthBinding))
        assertTrue(cache.containsExact(replacement.healthBinding))
        assertFalse(cache.containsExact(old.healthBinding))
        assertSame(replacement, cache.removeExact(replacement.healthBinding))
    }

    @Test
    fun expiredRuntimeDoesNotWaitForAnotherCacheOperation() = runTest {
        val cache = TmuxSessionRuntimeCache(
            maxEntries = 4,
            ttlMs = 100L,
            nowMs = { testScheduler.currentTime },
            expiryScope = backgroundScope,
        )
        val parked = cachedRuntime("parked")

        cache.onProcessForegrounded()
        cache.put(parked)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(
            "TTL cleanup must not depend on a later put/activate call",
            0,
            cache.size(),
        )
        assertTrue((parked.client as FakeTmuxClient).closed)
        assertEquals(
            TmuxRuntimeCleanupDiagnostic(
                parkAgeMs = 100L,
                reason = RuntimeCacheEvictionReason.TtlExpired,
                cleanupCompleted = true,
            ),
            cache.diagnosticSnapshot().lastCleanup,
        )
    }

    @Test
    fun activeOtherSessionDoesNotPreventParkedRuntimeExpiry() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val parked = cachedRuntime("parked")

        cache.onProcessForegrounded()
        cache.put(parked)
        // No subsequent cache operation: another session can remain active for
        // the whole interval without keeping this parked runtime alive.
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(0, cache.size())
        assertTrue((parked.client as FakeTmuxClient).closed)
    }

    @Test
    fun reactivationCancelsGenerationCleanupAndLeavesRuntimeLive() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val parked = cachedRuntime("parked")

        cache.onProcessForegrounded()
        cache.put(parked)
        advanceTimeBy(99L)
        assertSame(parked, cache.activate(parked.key).runtime)
        advanceTimeBy(1L)
        runCurrent()

        assertFalse((parked.client as FakeTmuxClient).closed)
        assertEquals(0, cache.diagnosticSnapshot().cleanupInFlightCount)
        assertEquals(null, cache.diagnosticSnapshot().lastCleanup)
    }

    @Test
    fun expiryClaimAtDeadlineWinsBeforeUserCanReclaimRuntime() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val parked = cachedRuntime("parked")
        cache.onProcessForegrounded()
        cache.put(parked)

        // Move the clock to the deadline without dispatching the scheduled job.
        // activate() and the job therefore contend through the same synchronized
        // ownership check; an already-expired generation cannot be reclaimed.
        advanceTimeBy(100L)
        assertEquals(null, cache.activate(parked.key).runtime)
        runCurrent()

        assertTrue((parked.client as FakeTmuxClient).closed)
        assertEquals(0, cache.diagnosticSnapshot().cleanupInFlightCount)
    }

    @Test
    fun staleExpiryGenerationCannotCloseSameKeyReplacement() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val old = cachedRuntime("parked")
        val replacement = cachedRuntime("parked")

        cache.onProcessForegrounded()
        cache.put(old)
        advanceTimeBy(50L)
        assertEquals(listOf(old), cache.put(replacement))
        advanceTimeBy(50L)
        runCurrent()

        assertTrue(cache.contains(replacement.key))
        assertFalse((replacement.client as FakeTmuxClient).closed)
        // Replacement eviction is still caller-owned; the stale TTL job must
        // not close either generation after its compare-and-remove loses.
        assertFalse((old.client as FakeTmuxClient).closed)
    }

    @Test
    fun backgroundPausesSchedulerAndForegroundClaimsOverdueRuntime() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val parked = cachedRuntime("parked")

        cache.onProcessForegrounded()
        cache.put(parked)
        advanceTimeBy(50L)
        cache.onProcessBackgrounded()
        advanceTimeBy(100L)
        runCurrent()

        assertTrue("D21: no cleanup work while backgrounded", cache.contains(parked.key))
        assertFalse((parked.client as FakeTmuxClient).closed)

        cache.onProcessForegrounded()
        runCurrent()

        assertFalse(cache.contains(parked.key))
        assertTrue((parked.client as FakeTmuxClient).closed)
    }

    @Test
    fun expiryCleanupCancelsOutputWorkBeforeClosingClient() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        var producerFinallyRan = false
        val producer = backgroundScope.launch {
            try {
                awaitCancellation()
            } finally {
                producerFinallyRan = true
            }
        }
        runCurrent()
        val parked = cachedRuntime("parked", paneProducerJobs = mapOf("%1" to producer))

        cache.onProcessForegrounded()
        cache.put(parked)
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(producerFinallyRan)
        assertFalse(producer.isActive)
        assertTrue((parked.client as FakeTmuxClient).closed)
        assertEquals(0, cache.diagnosticSnapshot().cleanupInFlightCount)
    }

    @Test
    fun saturatedExpiryClaimSubscriberCannotDelayBoundedRuntimeTeardown() = runTest {
        val cache = scheduledCache(ttlMs = 100L)
        val subscriberHoldingFirstClaim = CompletableDeferred<Unit>()
        val releaseSubscriber = CompletableDeferred<Unit>()
        val receivedBindings = mutableListOf<com.pocketshell.core.connection.RuntimeHealthBinding>()
        val subscriber = backgroundScope.launch {
            cache.expiryClaims.collect { claim ->
                receivedBindings += claim.healthBinding
                if (!subscriberHoldingFirstClaim.isCompleted) {
                    subscriberHoldingFirstClaim.complete(Unit)
                    releaseSubscriber.await()
                }
            }
        }
        runCurrent()

        val leaseManager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(ObservableLeaseSshSession()) },
            scope = backgroundScope,
            idleTtlMillis = 60_000L,
        )
        val leaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "alpha.example",
                port = 22,
                user = "alex",
                credentialId = "/keys/a",
            ),
            key = SshKey.Path(File("/keys/a")),
        )
        val leases = (0 until 12).map {
            leaseManager.acquire(leaseTarget).getOrThrow()
        }
        val producerCancelled = BooleanArray(12)
        val runtimes = (0 until 12).map { index ->
            val producer = backgroundScope.launch {
                try {
                    awaitCancellation()
                } finally {
                    producerCancelled[index] = true
                }
            }
            cachedRuntime(
                sessionName = "parked-$index",
                hostId = index.toLong() + 1L,
                paneProducerJobs = mapOf("%$index" to producer),
                lease = leases[index],
            )
        }
        runCurrent()

        cache.onProcessForegrounded()
        runtimes.forEach(cache::put)
        advanceTimeBy(100L)
        runCurrent()
        subscriberHoldingFirstClaim.await()

        assertEquals("every expiry must leave the cache", 0, cache.size())
        assertTrue(
            "SharedFlow backpressure must not delay any producer cancellation",
            producerCancelled.all { it },
        )
        assertTrue(
            "SharedFlow backpressure must not delay any client close",
            runtimes.all { (it.client as FakeTmuxClient).closed },
        )
        assertEquals(
            "resource teardown completion must not wait for the stalled subscriber",
            0,
            cache.diagnosticSnapshot().cleanupInFlightCount,
        )
        assertFalse(
            "lease completion must be observed before the stalled subscriber is released",
            releaseSubscriber.isCompleted,
        )
        assertTrue(
            "SharedFlow backpressure must not delay any of the 12 real lease releases",
            leases.all(::leaseReleased),
        )

        releaseSubscriber.complete(Unit)
        runCurrent()
        assertEquals(
            "every exact health binding must eventually reach the subscriber without broadening",
            runtimes.map { it.healthBinding }.toSet(),
            receivedBindings.toSet(),
        )
        assertEquals(runtimes.size, receivedBindings.size)
        subscriber.cancelAndJoin()
        leaseManager.close()
    }

    @Test
    fun expiryDiagnosticsContainAgeReasonAndCompletionButNoTerminalContent() = runTest {
        val events = mutableListOf<Map<String, Any?>>()
        DiagnosticEvents.install(object : DiagnosticEventSink {
            override fun record(category: String, event: String, fields: Map<String, Any?>) {
                if (category == "tmux_runtime_cache" && event == "parked_runtime_cleanup") {
                    events += fields
                }
            }
        })
        try {
            val cache = scheduledCache(ttlMs = 100L)
            cache.onProcessForegrounded()
            cache.put(cachedRuntime("secret-session-name"))

            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(false, true), events.map { it["cleanupCompleted"] })
            events.forEach { fields ->
                assertEquals(100L, fields["parkAgeMs"])
                assertEquals("ttl_expired", fields["reason"])
                assertEquals(
                    "diagnostics must expose cleanup metadata only",
                    setOf("parkAgeMs", "reason", "cleanupCompleted"),
                    fields.keys,
                )
                assertFalse(fields.values.any { it == "secret-session-name" })
            }
        } finally {
            DiagnosticEvents.install(DiagnosticEventSink.Noop)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.scheduledCache(ttlMs: Long) =
        TmuxSessionRuntimeCache(
            maxEntries = 4,
            ttlMs = ttlMs,
            nowMs = { testScheduler.currentTime },
            expiryScope = backgroundScope,
        )

    private fun cachedRuntime(
        sessionName: String,
        hostId: Long = 1L,
        durableSessionKey: String? = null,
        paneProducerJobs: Map<String, kotlinx.coroutines.Job> = emptyMap(),
        lease: SshLease? = null,
        trustedHostKeySha256: String? = null,
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = hostId,
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a",
                sessionName = sessionName,
                durableSessionKey = durableSessionKey,
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = emptyList(),
            paneRows = emptyMap(),
            paneProducerJobs = paneProducerJobs,
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = lease,
        )

    private fun leaseReleased(lease: SshLease): Boolean =
        SshLease::class.java
            .getDeclaredField("released")
            .apply { isAccessible = true }
            .getBoolean(lease)

    private class ObservableLeaseSshSession : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = error("not used")

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }
}
