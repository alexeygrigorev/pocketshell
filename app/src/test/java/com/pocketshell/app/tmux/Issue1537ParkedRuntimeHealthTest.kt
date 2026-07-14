package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
 * Issue #1537 (option b): the parked-runtime blind spot — a session parked in
 * the runtime cache while another is foreground had no machine watching its
 * liveness, so its death was discovered only at switch-back as an attach EOF
 * that forced a visible fresh redial
 * (`stage=stale_lease_auto_recover cause=stale_lease_attach_eof
 * outcome=fast_switch_fresh_redial`). This suite proves the two halves of the
 * fix reproduce-first:
 *
 *  - the parked-client health subscriber marks the ledger Dead and evicts the
 *    corpse PROACTIVELY while parked (Test B), and
 *  - the residual silent-TCP-death race (no edge inside the keepalive window)
 *    still EOFs on switch-back but now routes into the SINGLE ladder as a CALM
 *    hold — never the jarring full-screen `Connecting` overlay of the deleted
 *    bespoke two-counter seam (Test A, spike test v).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1537ParkedRuntimeHealthTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun newVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
        runtimeCache: TmuxSessionRuntimeCache,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = runtimeCache,
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
    }

    // ---------------------------------------------------------------------
    // Test A — silent-corpse fallback (spike test v): the SAME-HOST fast switch
    // over a silently-dead shared lease still EOFs, but the fallback is CALM
    // (single ladder, no full-screen Connecting overlay, no infinite loop).
    // ---------------------------------------------------------------------
    @Test
    fun sameHostFastSwitchSilentCorpseFallbackIsCalmSingleLadder() = runVmTest {
        val firstSession = AlwaysConnectedSession(id = "first")
        val freshSession = AlwaysConnectedSession(id = "fresh")
        val connector = TwoSessionConnector(firstSession, freshSession)
        val vm = newVm(
            registry = ActiveTmuxClients(),
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
            runtimeCache = TmuxSessionRuntimeCache(),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val sessionAClient = FakeTmuxClient().withSinglePaneRow("alpha", "%1")
        val recoveredBClient = FakeTmuxClient().withSinglePaneRow("beta", "%2")
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            when {
                session === firstSession && sessionName == "alpha" -> sessionAClient
                session === firstSession && sessionName == "beta" ->
                    // The silently-dead shared transport EOFs on the switch attach.
                    FakeTmuxClient().apply {
                        closeAndThrowOnCommandPrefix = "list-panes"
                        closeAndThrowException = TmuxClientException(
                            "failed to write tmux command `list-panes`: Getting data on EOF'ed stream",
                        )
                    }
                session === freshSession && sessionName == "beta" -> recoveredBClient
                else -> error("unexpected client request: $sessionName over $session")
            }
        }

        vm.connect(
            hostId = 1L, hostName = "alpha", host = "alpha.example", port = 22,
            user = "alex", keyPath = "/keys/a", passphrase = null, sessionName = "alpha",
        )
        advanceUntilIdle()
        assertTrue(
            "session A must connect first",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val diagnostics = installRecordingDiagnosticSink()
        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }

        // Switch to B over the silently-dead shared lease.
        vm.connect(
            hostId = 1L, hostName = "alpha", host = "alpha.example", port = 22,
            user = "alex", keyPath = "/keys/a", passphrase = null, sessionName = "beta",
        )
        advanceUntilIdle()
        collector.cancel()

        // Recovers to B on a single fresh transport — no loop.
        assertEquals("exactly one fresh redial (single ladder, no loop)", 2, connector.connectCount)
        assertTrue(
            "switch over a silent corpse must auto-recover to Connected on B, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(listOf("%2"), vm.panes.value.map { it.paneId })

        // The fallback DID fire (spike test v — the residual race is covered).
        val trail = diagnostics.eventsNamed("cause_trail").filter {
            it.fields["cause"] == "stale_lease_attach_eof"
        }
        assertEquals(
            "the silent-corpse fallback fires exactly once (no bespoke counter loop)",
            1,
            trail.size,
        )

        // GREEN: the fallback is CALM — it NEVER surfaces the jarring full-screen
        // Connecting overlay (the deleted first-arm behaviour). Only the calm
        // Switching hold, then Connected.
        assertFalse(
            "the silent-corpse fallback must NOT flash the full-screen Connecting overlay; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Connecting },
        )
        assertFalse(
            "no Disconnected/Failed band on a recoverable silent corpse; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
        diagnostics.close()
    }

    // ---------------------------------------------------------------------
    // Test B — proactive parked-runtime health: a same-host session parked while
    // another is foreground has its DEATH known via the health-event subscriber
    // and its corpse EVICTED before any switch-back.
    // ---------------------------------------------------------------------
    @Test
    fun parkedRuntimeDeathIsDetectedAndEvictedWhileParked() = runVmTest {
        val session = AlwaysConnectedSession(id = "shared")
        val connector = TwoSessionConnector(session, AlwaysConnectedSession(id = "unused"))
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(
            registry = ActiveTmuxClients(),
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
            runtimeCache = runtimeCache,
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val alphaClient = FakeTmuxClient().withSinglePaneRow("alpha", "%1")
        val betaClient = FakeTmuxClient().withSinglePaneRow("beta", "%2")
        vm.setTmuxClientFactoryForTest { s, sessionName, _ ->
            when (sessionName) {
                "alpha" -> alphaClient
                "beta" -> betaClient
                else -> error("unexpected $sessionName over $s")
            }
        }

        vm.connect(
            hostId = 1L, hostName = "alpha", host = "alpha.example", port = 22,
            user = "alex", keyPath = "/keys/a", passphrase = null, sessionName = "alpha",
        )
        advanceUntilIdle()

        // Same-host switch to beta: alpha is PARKED into the runtime cache with
        // its own client + a shared lease ref.
        vm.connect(
            hostId = 1L, hostName = "alpha", host = "alpha.example", port = 22,
            user = "alex", keyPath = "/keys/a", passphrase = null, sessionName = "beta",
        )
        advanceUntilIdle()
        val alphaKey = TmuxRuntimeKey(
            hostId = 1L, hostname = "alpha.example", port = 22, username = "alex",
            keyPath = "/keys/a", sessionName = "alpha",
        )
        assertTrue("alpha must be parked in the cache after the same-host switch", runtimeCache.contains(alphaKey))

        val diagnostics = installRecordingDiagnosticSink()

        // The parked alpha client's -CC reader EOFs while parked (the blind spot).
        alphaClient.disconnectedSignal.value = true
        advanceUntilIdle()

        // The health subscriber marks it dead and evicts the corpse PROACTIVELY —
        // before the user ever switches back.
        assertFalse(
            "the parked corpse must be evicted from the cache proactively (no switch-back needed)",
            runtimeCache.contains(alphaKey),
        )
        val deaths = diagnostics.eventsNamed("parked_runtime_death")
        assertEquals("the parked-health subscriber records exactly one death", 1, deaths.size)
        assertEquals("ClientDisconnected", deaths.single().fields["cause"])
        assertEquals(1L, deaths.single().fields["hostId"])
        diagnostics.close()
    }

    // ---------------------------------------------------------------------
    // Deleted-path proof (D28 single active path): the bespoke
    // `staleLeaseAutoRecoverAttempts` two-counter seam is GONE.
    // ---------------------------------------------------------------------
    @Test
    fun bespokeStaleLeaseCounterSeamIsDeleted() {
        val fields = TmuxSessionViewModel::class.java.declaredFields.map { it.name }
        assertFalse(
            "the bespoke stale-lease two-counter field must be deleted (D22 hard-cut); found: $fields",
            fields.any { it == "staleLeaseAutoRecoverAttempts" },
        )
    }

    // ------------------------------- fakes -------------------------------

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            com.pocketshell.core.tmux.CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class TwoSessionConnector(
        private val first: SshSession,
        private val second: SshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = when (connectCount) {
                0 -> first
                1 -> second
                else -> error("unexpected lease connect $connectCount for ${target.leaseKey}")
            }
            connectCount += 1
            return Result.success(next)
        }
    }

    private class AlwaysConnectedSession(val id: String) : SshSession {
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
