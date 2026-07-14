package com.pocketshell.app.tmux

import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
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

    // -------------------------------------------------------------------------
    // Test A — same-host, the FOREGROUND session still holds the shared lease
    // key: force-disconnect MUST be withheld; the live transport SURVIVES.
    // -------------------------------------------------------------------------
    @Test
    fun sameHostForegroundHoldsKey_forceDisconnectWithheld_sharedLiveTransportSurvives() = runTest {
        val sharedTransport = FakeSession()
        val disconnectedKeys = mutableListOf<SshLeaseKey>()

        handleParkedRuntimeDeath(
            key = deadKey,
            leaseKey = leaseKey,
            cause = RuntimeDeathCause.ClientDisconnected,
            runtimeCache = TmuxSessionRuntimeCache(),
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
        // A DIFFERENT same-host session ("beta") is parked holding the SAME
        // shared lease. The dying runtime is "alpha" (not cached here) — so
        // removeSession(1,"alpha") returns nothing and the beta corpse-share
        // must still WITHHOLD the force-disconnect.
        cache.put(siblingRuntime(sessionName = "beta", lease = lease))

        val disconnectedKeys = mutableListOf<SshLeaseKey>()
        handleParkedRuntimeDeath(
            key = deadKey,
            leaseKey = lease.key,
            cause = RuntimeDeathCause.ClientDisconnected,
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

        handleParkedRuntimeDeath(
            key = deadKey,
            leaseKey = leaseKey,
            cause = RuntimeDeathCause.KeepaliveDead,
            runtimeCache = TmuxSessionRuntimeCache(),
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

    // ------------------------------- fakes -----------------------------------

    private fun siblingRuntime(
        sessionName: String,
        lease: com.pocketshell.core.ssh.SshLease,
        hostId: Long = 1L,
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
        paneAgentJobs = emptyMap(),
        paneAgentInputs = emptyMap(),
        agentConversations = emptyMap(),
        remoteColumns = 0,
        remoteRows = 0,
        lease = lease,
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
