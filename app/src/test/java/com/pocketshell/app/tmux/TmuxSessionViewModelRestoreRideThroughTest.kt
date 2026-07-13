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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1533 (the connection "V2" busy-session flap) — REPRODUCE-FIRST (D33/G10).
 *
 * ## The reported defect (reproduced RED on base)
 *
 * `scheduleNetworkReconnectOnRestore` ran an unconditional 5s
 * `requireAnsweredRoundTrip=true` probe on EVERY validated-bit restore. When an
 * agent (Codex/Claude) emits a `%output` burst the probe reply parks behind the
 * control-mode FIFO past the 5s budget → the probe "fails" → a provably-alive
 * transport is TORN DOWN and redialed (un-amortized). The maintainer runs agents
 * constantly, so busy sessions flap.
 *
 * ## The fix these tests pin (GREEN)
 *
 * A SAME-IDENTITY restore whose control channel is still delivering bytes (the
 * #927 recent-reader-activity vouch — the very `%output` burst that parked the
 * probe) RIDES THROUGH with no teardown. Strict round-trip probing stays scoped to
 * a real identity CHANGE (#1193). A genuinely dead same-identity socket still
 * redials, but the redial is AMORTIZED (the #928/#1522 debounce shape) so a
 * flapping host widens its cadence instead of hammering.
 *
 * Class coverage (G2): same-identity-alive-busy (ride through),
 * same-identity-actually-dead (amortized redial), identity-change (strict probe).
 * No `assumeTrue`/CI-skip: the parked-probe state is injected SYNTHETICALLY
 * (`suspendForeverOnBestEffortCommandPrefix` + the reader-activity seam), the #780
 * model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelRestoreRideThroughTest : TmuxSessionViewModelTestBase() {

    private companion object {
        /** Ladder shared by the reconnect path AND the restore-redial amortizer. */
        val LADDER_MS = listOf(0L, 1_000L, 2_000L)

        /** Long enough that no quiet-reset fires between the amortization episodes. */
        const val QUIET_RESET_MS = 300_000L
    }

    // ---- 1. same-identity, alive-but-busy → ride through, NO teardown (the headline) ----

    @Test
    fun sameIdentityAliveButBusyRestoreRidesThroughInsteadOfTearingDownTheTransport() =
        runTest(scheduler) {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            // If the (buggy) base path decides to redial, it does so IMMEDIATELY.
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            // The alive-but-busy transport: a `%output` burst parks the round-trip
            // probe reply FOREVER (past the 5s budget) while the SAME socket's reader
            // stays active (recent reader-activity vouch) — the maintainer's exact state.
            val busyClient = FakeTmuxClient().apply {
                suspendForeverOnBestEffortCommandPrefix = "refresh-client"
                millisSinceLastReaderActivityValue = 100L
            }
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = busyClient,
            )
            runCurrent()

            val diagnostics = installRecordingDiagnosticSink()
            try {
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
                runCurrent()
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
                runCurrent()
                // Give the (base) parked probe its full 5s budget to time out and redial.
                advanceTimeBy(RESTORE_LIVENESS_PROBE_BUDGET_MS + 1)
                runCurrent()

                // GREEN load-bearing #1: the provably-alive busy transport is NOT torn
                // down. On base this is 1 (a spurious teardown + redial). This is the
                // reported "V2" flap.
                assertEquals(
                    "a same-identity, alive-but-busy restore (`%output` burst parking the probe) " +
                        "must RIDE THROUGH — NOT tear down + redial a provably-alive transport (#1533)",
                    0,
                    connector.connectCount,
                )
                // GREEN load-bearing #2: transport identity/generation preserved — the
                // SAME `-CC` client is still active (never unregistered).
                assertSame(
                    "the same-identity ride-through must preserve the live client (no teardown)",
                    busyClient,
                    registry.clients.value[7L]?.client,
                )
                assertTrue(
                    "the ride-through clears the loss-hold band back to Connected",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                val rideThrough = diagnostics.eventsNamed("network_restore_ride_through")
                assertTrue(
                    "expected a network_restore_ride_through (proves the vouch fired, not a " +
                        "vacuous pass); events=${diagnostics.events.map { it.name }}",
                    rideThrough.isNotEmpty(),
                )
                assertEquals(
                    "the ride-through must be attributed to the same-identity reader-activity vouch",
                    "same_identity_reader_active",
                    rideThrough.single().fields["cause"],
                )
                assertTrue(
                    "no teardown/redial diagnostic may fire on the busy ride-through; events=" +
                        diagnostics.events.map { it.name },
                    diagnostics.eventsNamed("network_restore_reconnect_start").isEmpty(),
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- 2. same-identity, actually-dead → AMORTIZED redial (not immediate unconditional) ----

    @Test
    fun sameIdentityGenuinelyDeadRestoreRedialIsAmortizedAcrossEpisodesNotHammered() =
        runTest(scheduler) {
            val diagnostics = installRecordingDiagnosticSink()
            try {
                val rig = buildDeadRestoreRig()
                // The socket is genuinely dead every episode (probe cannot round-trip)
                // and no reader activity vouches it — the fresh-lease redial path.
                rig.vm.forceLivenessProbeDeadForTest = true

                // Episode 1: the FIRST same-identity dead restore redials INSTANTLY
                // (a one-off drop keeps today's seamless recovery).
                driveDeadRestore(rig, sequence = 1L)
                assertEquals(
                    "the FIRST same-identity dead restore must redial instantly (amortizer grace 0)",
                    1,
                    rig.connector.connectCount,
                )
                awaitConnected(rig)

                // Episode 2: the SECOND consecutive dead restore must NOT redial
                // instantly — it waits the amortized grace. On base (no restore-redial
                // amortizer) this redials immediately → connectCount == 2 right here.
                driveDeadRestore(rig, sequence = 3L)
                assertEquals(
                    "issue #1533 — the 2nd consecutive same-identity dead restore must WAIT the " +
                        "amortized grace (${LADDER_MS[1]}ms) before redialing. An immediate redial is " +
                        "the un-amortized hammer this fix kills.",
                    1,
                    rig.connector.connectCount,
                )
                advanceTimeBy(LADDER_MS[1] - 1)
                runCurrent()
                assertEquals(
                    "the 2nd redial must still be held 1ms before its amortized grace elapses",
                    1,
                    rig.connector.connectCount,
                )
                advanceTimeBy(1)
                runCurrent()
                awaitCondition { rig.connector.connectCount >= 2 }
                assertEquals(
                    "the 2nd episode still auto-recovers once its amortized grace elapses " +
                        "(amortized, not abandoned)",
                    2,
                    rig.connector.connectCount,
                )
                awaitConnected(rig)

                // The amortization is observable in HONEST field logs (not mislabelled
                // as a keepalive death): widening grace per episode.
                val episodes = diagnostics.eventsNamed("network_restore_redial_amortized")
                assertEquals(
                    "every same-identity dead restore records its amortization decision",
                    listOf(0L, LADDER_MS[1]),
                    episodes.map { it.fields["graceMs"] },
                )
                assertEquals(listOf(1, 2), episodes.map { it.fields["episode"] })
            } finally {
                diagnostics.close()
            }
        }

    // ---- 3. identity-change → strict probe still runs (do NOT regress #1193) ----

    @Test
    fun identityChangeRestoreStillStrictProbesAndRedialsDeadSocketDespiteReaderActivity() =
        runTest(scheduler) {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            // A handoff whose OLD socket is dead — but with RECENT reader activity
            // present (bytes crossed the OLD socket). The reader-activity vouch must
            // NOT short-circuit an identity change (that would reintroduce #1193 — a
            // ride-through onto a dead post-handoff socket).
            val oldClient = FakeTmuxClient().apply { millisSinceLastReaderActivityValue = 100L }
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
            vm.forceLivenessProbeDeadForTest = true

            val diagnostics = installRecordingDiagnosticSink()
            try {
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
                runCurrent()
                // The restore arrives on a DIFFERENT validated identity (WiFi→cellular).
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                    networkRestore(current = TerminalNetworkSnapshot.Validated("cell")),
                )
                advanceUntilIdle()

                assertTrue(
                    "an identity-change restore over a DEAD socket must NOT ride through on the " +
                        "reader-activity vouch (that is the #1193 dead-post-handoff-socket bug); events=" +
                        diagnostics.events.map { it.name },
                    diagnostics.eventsNamed("network_restore_ride_through").isEmpty(),
                )
                assertEquals(
                    "the identity-change dead-socket restore must redial via the strict-probe fresh lease",
                    1,
                    connector.connectCount,
                )
                assertEquals(
                    "the redial fires with the network-reconnect trigger",
                    TmuxConnectTrigger.NetworkReconnect,
                    vm.latestRestoreIntentSnapshot()?.trigger,
                )
                assertTrue(
                    "the strict-probe redial records the fast restore signal (not a vacuous pass); events=" +
                        diagnostics.events.map { it.name },
                    diagnostics.eventsNamed("network_restore_reconnect_start").isNotEmpty(),
                )
                assertTrue(
                    "an identity-change redial is prompt (fresh lease), NOT routed through the " +
                        "same-identity restore-redial amortizer; events=${diagnostics.events.map { it.name }}",
                    diagnostics.eventsNamed("network_restore_redial_amortized").isEmpty(),
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- rig for the amortization journey (dead-socket restore, recovers between episodes) ----

    private class DeadRestoreRig(
        val vm: TmuxSessionViewModel,
        val registry: ActiveTmuxClients,
        val connector: QueueLeaseConnector,
    )

    private fun TestScope.buildDeadRestoreRig(): DeadRestoreRig {
        val registry = ActiveTmuxClients()
        val sessions = List(4) { FakeSshSession() }
        val connector = QueueLeaseConnector(*sessions.toTypedArray())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )
        vm.setAutoReconnectDelaysForTest(LADDER_MS)
        vm.setPassiveDisconnectRecoveryForTest(keepaliveDeathQuietResetMs = QUIET_RESET_MS)
        val replacementClients = ArrayDeque(List(4) { i -> FakeTmuxClient().withSinglePane("work", "%${i + 1}") })
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        runCurrent()
        return DeadRestoreRig(vm = vm, registry = registry, connector = connector)
    }

    /** One same-identity loss→restore round for the dead-socket amortization journey. */
    private suspend fun TestScope.driveDeadRestore(rig: DeadRestoreRig, sequence: Long) {
        val hook = rig.registry.lifecycleHooksSnapshot().single()
        hook.onNetworkChanged(networkLoss(sequence = sequence))
        runCurrent()
        hook.onNetworkChanged(networkRestore(sequence = sequence + 1L))
        runCurrent()
    }

    private fun TestScope.awaitConnected(rig: DeadRestoreRig) {
        awaitCondition {
            rig.vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
        }
    }

    // ---- network-change helpers (same-identity restore by default) ----

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
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        var closed: Boolean = false

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
