package com.pocketshell.app.tmux

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelNetworkProbeTest : TmuxSessionViewModelTestBase() {

    @Test
    fun networkRestoreProbeCompletionIsIgnoredAfterNewerReconnectGeneration() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val restoreProbeGate = CompletableDeferred<Unit>()
        val oldClient = FakeTmuxClient().apply {
            sendCommandGatePrefix = "refresh-client"
            sendCommandGate = restoreProbeGate
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        val diagnostics = installRecordingDiagnosticSink()
        try {
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
            runCurrent()
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
            runCurrent()
            assertTrue(
                "precondition: restore liveness probe is parked on the old control client",
                oldClient.sentCommands.contains("refresh-client"),
            )

            assertTrue(vm.reconnect())
            runCurrent()
            restoreProbeGate.complete(Unit)
            advanceUntilIdle()

            assertSame(
                "newer manual reconnect must remain the active client after the stale restore probe completes",
                reconnectClient,
                registry.clients.value[7L]?.client,
            )
            assertTrue(
                "stale restore probe completion must be recorded and ignored; events=" +
                    diagnostics.events.map { it.name },
                diagnostics.eventsNamed("network_transition_probe_stale").any {
                    it.fields["source"] == "network_restore_probe"
                },
            )
            assertTrue(
                "stale restore probe must not ride through over the newer reconnect generation",
                diagnostics.eventsNamed("network_restore_ride_through").isEmpty(),
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun networkHandoffProbeCompletionIsIgnoredAfterNewerReconnectGeneration() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val handoffProbeGate = CompletableDeferred<Unit>()
        val oldClient = FakeTmuxClient().apply {
            sendCommandGatePrefix = "refresh-client"
            sendCommandGate = handoffProbeGate
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()
        vm.forceTransportProvenAliveForTest = true

        val diagnostics = installRecordingDiagnosticSink()
        try {
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("wifi"),
                    current = TerminalNetworkSnapshot.Validated("cell"),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                    reason = "wifi-cellular-handoff-stale-probe",
                ),
            )
            runCurrent()
            assertTrue(
                "precondition: handoff liveness probe is parked on the old control client",
                oldClient.sentCommands.contains("refresh-client"),
            )

            assertTrue(vm.reconnect())
            runCurrent()
            handoffProbeGate.complete(Unit)
            advanceUntilIdle()

            assertSame(
                "newer manual reconnect must remain the active client after the stale handoff probe completes",
                reconnectClient,
                registry.clients.value[7L]?.client,
            )
            assertTrue(
                "stale handoff probe completion must be recorded and ignored; events=" +
                    diagnostics.events.map { it.name },
                diagnostics.eventsNamed("network_transition_probe_stale").any {
                    it.fields["source"] == "network_handoff_probe"
                },
            )
            assertTrue(
                "stale handoff probe must not emit the old ride-through decision",
                diagnostics.eventsNamed("network_reconnect_skip").none {
                    it.fields["cause"] == "transport_proven_alive"
                },
            )
        } finally {
            diagnostics.close()
            vm.forceTransportProvenAliveForTest = null
        }
    }

    private fun networkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            previous as? TerminalNetworkSnapshot.Validated,
        reason: String = "validated-default-network-changed",
        sequence: Long = 1L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
        )

    private fun networkLoss(
        previousValidated: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        reason: String = "default-network-lost",
        sequence: Long = 1L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previousValidated,
            current = TerminalNetworkSnapshot.NoValidatedNetwork,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            kind = TerminalNetworkChangeKind.NetworkLost,
        )

    private fun networkRestore(
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        previousValidated: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        reason: String = "default-network-available",
        sequence: Long = 2L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            kind = TerminalNetworkChangeKind.NetworkRestored,
        )

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(sessions.firstOrNull() ?: FakeSshSession())
    }

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }
}
