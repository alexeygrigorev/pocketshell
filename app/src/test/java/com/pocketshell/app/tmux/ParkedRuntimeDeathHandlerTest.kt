package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.tmux.connection.ParkedRuntimeDeathSignal
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthBinding
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeInstanceToken
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1537 (option b) — the CATASTROPHIC-IF-WRONG safety property, given a
 * direct executing assertion (reviewer Blocker 1).
 *
 * [handleParkedRuntimeDeath] evicts a dead parked runtime and releases its lease
 * ref, and force-disconnects the pooled transport ONLY when no live holder still
 * shares the lease key (`stillShared` false). The load-bearing invariant: a
 * parked runtime is ALWAYS same-host as the foreground session that replaced it,
 * so it SHARES the refcounted transport — force-disconnecting that transport on
 * the parked death would kill the LIVE foreground session. This suite proves the
 * `stillShared` gate directly, not by inspection:
 *
 *  - same-host, the FOREGROUND still holds the key      -> disconnect WITHHELD,
 *    the shared live transport SURVIVES (Test A);
 *  - same-host, a SIBLING cached runtime still holds it -> disconnect WITHHELD
 *    (Test B, the `cachedRuntimesForHost` arm of `stillShared`);
 *  - foreign / sole-holder, NO live holder shares it    -> disconnect FIRES,
 *    the corpse transport is killed so the switch-back dials fresh (Test C).
 *
 * Reproduce-first (D33/G10): neutralizing the `stillShared` guard in
 * [handleParkedRuntimeDeath] (forcing it to `false`) makes Test A go RED — the
 * shared live transport gets force-disconnected. This is the exact regression
 * (foreground session's transport killed) the guard exists to prevent, so it has
 * an executing assertion here, never only inspection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ParkedRuntimeDeathHandlerTest {

    private val leaseKey = SshLeaseKey(
        host = "alpha.example",
        port = 22,
        user = "alex",
        credentialId = "1:/keys/a",
    )

    // The parked runtime that just died: session "alpha" on host 1.
    private val deadKey = RuntimeHealthKey(hostId = 1L, sessionName = "alpha")
    private val deadBinding = RuntimeHealthBinding(deadKey, RuntimeInstanceToken.create())

    // -------------------------------------------------------------------------
    // Test A — same-host, the FOREGROUND session still holds the shared lease
    // key: force-disconnect MUST be withheld; the live transport SURVIVES.
    // -------------------------------------------------------------------------
    @Test
    fun sameHostForegroundHoldsKey_forceDisconnectWithheld_sharedLiveTransportSurvives() = runTest {
        val sharedTransport = FakeSession()
        val disconnectedKeys = mutableListOf<SshLeaseKey>()
        val cache = TmuxSessionRuntimeCache().also {
            it.put(siblingRuntime("alpha", lease = null, healthBinding = deadBinding))
        }

        handleParkedRuntimeDeath(
            signal = deathSignal(deadBinding, leaseKey, RuntimeDeathCause.ReaderEof),
            runtimeCache = cache,
            // The FOREGROUND (or connecting) session shares the SAME host/key —
            // this is the always-true condition for a same-host parked runtime.
            foregroundLeaseKeys = setOf(leaseKey),
            disconnectLease = { k ->
                disconnectedKeys += k
                if (k == leaseKey) sharedTransport.close()
            },
            launchContained = { block -> launch { block() } },
        )
        advanceUntilIdle()

        assertTrue(
            "a parked death on a lease the FOREGROUND still holds must NOT force-disconnect it; " +
                "force-disconnected keys=$disconnectedKeys",
            disconnectedKeys.isEmpty(),
        )
        assertTrue(
            "the SHARED LIVE transport of the foreground session MUST survive a same-host " +
                "parked-runtime death — force-disconnecting it would kill the live session",
            sharedTransport.isConnected,
        )
    }

    // -------------------------------------------------------------------------
    // Test B — same-host, a SIBLING cached runtime (different session, same host)
    // still holds the shared lease key: force-disconnect MUST be withheld. This
    // exercises the `cachedRuntimesForHost(...).any { it.lease?.key == ... }` arm
    // of `stillShared` using a REAL acquired lease.
    // -------------------------------------------------------------------------
    @Test
    fun sameHostSiblingCachedRuntimeHoldsKey_forceDisconnectWithheld() = runTest {
        val transport = FakeSession()
        val leaseManager = testLeaseManager(
            connector = SshLeaseConnector { Result.success(transport) },
            scope = this,
        )
        val lease = leaseManager
            .acquire(SshLeaseTarget(leaseKey = leaseKey, key = SshKey.Path(File("/keys/a"))))
            .getOrThrow()
        advanceUntilIdle()

        val cache = TmuxSessionRuntimeCache()
        cache.put(siblingRuntime("alpha", lease = null, healthBinding = deadBinding))
        // A DIFFERENT same-host session ("beta") is parked holding the SAME
        // shared lease. Exact removal removes only alpha; beta must still
        // WITHHOLD the force-disconnect.
        cache.put(siblingRuntime(sessionName = "beta", lease = lease))

        val disconnectedKeys = mutableListOf<SshLeaseKey>()
        handleParkedRuntimeDeath(
            signal = deathSignal(deadBinding, lease.key, RuntimeDeathCause.ReaderEof),
            runtimeCache = cache,
            // The foreground does NOT hold it here — the ONLY live holder is the
            // sibling cached runtime, which must still block the disconnect.
            foregroundLeaseKeys = emptySet(),
            disconnectLease = { k -> disconnectedKeys += k },
            launchContained = { block -> launch { block() } },
        )
        advanceUntilIdle()

        assertTrue(
            "a sibling cached runtime still sharing the transport must WITHHOLD force-disconnect; " +
                "force-disconnected keys=$disconnectedKeys",
            disconnectedKeys.isEmpty(),
        )
        assertTrue(
            "the shared transport a sibling cached runtime still holds must survive",
            transport.isConnected,
        )
        lease.release()
    }

    // -------------------------------------------------------------------------
    // Test C — foreign / sole-holder: NO live holder shares the lease key, so the
    // parked corpse's pooled transport MUST be force-disconnected (the switch-back
    // then dials fresh instead of reusing a vouched corpse).
    // -------------------------------------------------------------------------
    @Test
    fun foreignSoleHolder_forceDisconnectFires_corpseTransportKilled() = runTest {
        val corpseTransport = FakeSession()
        val disconnectedKeys = mutableListOf<SshLeaseKey>()
        val cache = TmuxSessionRuntimeCache().also {
            it.put(siblingRuntime("alpha", lease = null, healthBinding = deadBinding))
        }

        handleParkedRuntimeDeath(
            signal = deathSignal(deadBinding, leaseKey, RuntimeDeathCause.KeepaliveDead),
            runtimeCache = cache,
            // No foreground/connecting session shares this key, and no sibling
            // cached runtime holds it -> sole holder -> disconnect must fire.
            foregroundLeaseKeys = emptySet(),
            disconnectLease = { k ->
                disconnectedKeys += k
                if (k == leaseKey) corpseTransport.close()
            },
            launchContained = { block -> launch { block() } },
        )
        advanceUntilIdle()

        assertEquals(
            "a foreign / sole-holder parked corpse MUST force-disconnect exactly its lease key",
            listOf(leaseKey),
            disconnectedKeys,
        )
        assertFalse(
            "a sole-holder corpse transport MUST be force-disconnected so the switch-back dials fresh",
            corpseTransport.isConnected,
        )
    }

    @Test
    fun staleOldRuntimeCallbackCannotRemoveNewSameSessionRuntime() = runTest {
        val cache = TmuxSessionRuntimeCache()
        val old = siblingRuntime(sessionName = "alpha", lease = null)
        val replacement = siblingRuntime(sessionName = "alpha", lease = null)
        cache.put(old)
        cache.put(replacement)
        val diagnostics = installRecordingDiagnosticSink()

        val handled = handleParkedRuntimeDeath(
            signal = deathSignal(old.healthBinding, leaseKey, RuntimeDeathCause.ReaderException),
            runtimeCache = cache,
            foregroundLeaseKeys = emptySet(),
            disconnectLease = { error("stale callback must not disconnect any lease") },
            launchContained = { block -> launch { block() } },
        )
        advanceUntilIdle()

        assertFalse("a callback for an evicted old runtime is explicitly ignored", handled)
        assertTrue("the exact replacement runtime must remain cached", cache.containsExact(replacement.healthBinding))
        assertFalse(cache.containsExact(old.healthBinding))
        val ignored = diagnostics.eventsNamed("parked_runtime_death_ignored").single()
        assertEquals("stale_callback", ignored.fields["outcome"])
        assertEquals(old.healthBinding.token.toString(), ignored.fields["boundRuntimeToken"])
        assertEquals(1234, ignored.fields["boundClientHash"])
        diagnostics.close()
    }

    @Test
    fun readerExceptionRemovesOnlyItsExactRuntime() = runTest {
        val cache = TmuxSessionRuntimeCache()
        val dead = siblingRuntime(sessionName = "alpha", lease = null)
        val sibling = siblingRuntime(sessionName = "beta", lease = null)
        cache.put(dead)
        cache.put(sibling)

        val handled = handleParkedRuntimeDeath(
            signal = deathSignal(
                dead.healthBinding,
                leaseKey = null,
                cause = RuntimeDeathCause.ReaderException,
            ),
            runtimeCache = cache,
            foregroundLeaseKeys = emptySet(),
            disconnectLease = { error("no lease key was attributed") },
            launchContained = { block -> launch { block() } },
        )
        advanceUntilIdle()

        assertTrue(handled)
        assertFalse(cache.containsExact(dead.healthBinding))
        assertTrue("unrelated exact sibling survives", cache.containsExact(sibling.healthBinding))
    }

    // ------------------------------- fakes -----------------------------------

    private fun siblingRuntime(
        sessionName: String,
        lease: com.pocketshell.core.ssh.SshLease?,
        hostId: Long = 1L,
        healthBinding: RuntimeHealthBinding? = null,
    ): CachedTmuxRuntime = CachedTmuxRuntime(
        key = TmuxRuntimeKey(
            hostId = hostId,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
        ),
        hostName = "alpha",
        startDirectory = null,
        session = null,
        client = FakeTmuxClient(),
        panes = emptyList(),
        paneRows = emptyMap(),
        paneProducerJobs = emptyMap(),
        paneInputQueues = emptyMap(),
        paneInputJobs = emptyMap(),
        paneAgentInputs = emptyMap(),
        agentConversations = emptyMap(),
        remoteColumns = 0,
        remoteRows = 0,
        lease = lease,
        healthBinding = healthBinding ?: RuntimeHealthBinding(
            RuntimeHealthKey(hostId, sessionName),
            RuntimeInstanceToken.create(),
        ),
    )

    private fun deathSignal(
        binding: RuntimeHealthBinding,
        leaseKey: SshLeaseKey?,
        cause: RuntimeDeathCause,
    ): ParkedRuntimeDeathSignal =
        ParkedRuntimeDeathSignal(
            binding = binding,
            leaseKey = leaseKey,
            cause = cause,
            boundClientIdentity = 1234,
            disconnectEvent = if (
                cause == RuntimeDeathCause.ReaderEof ||
                cause == RuntimeDeathCause.ReaderException
            ) {
                TmuxDisconnectEvent(
                    reason = if (cause == RuntimeDeathCause.ReaderEof) {
                        TmuxDisconnectReason.ReaderEof
                    } else {
                        TmuxDisconnectReason.ReaderException
                    },
                    source = if (cause == RuntimeDeathCause.ReaderEof) "eof" else "read_failure",
                    intent = "unknown",
                )
            } else {
                null
            },
            leaseCloseReason = if (cause == RuntimeDeathCause.KeepaliveDead) {
                com.pocketshell.core.ssh.SshLeaseCloseReason.KeepaliveDead
            } else {
                null
            },
        )

    private class FakeSession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

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

        override fun close() {
            closed = true
        }
    }
}
