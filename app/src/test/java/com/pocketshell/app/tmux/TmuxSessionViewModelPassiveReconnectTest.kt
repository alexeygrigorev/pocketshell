package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelPassiveReconnectTest : TmuxSessionViewModelTestBase() {
    @Test
    fun eofDisconnectUnregistersDeadClientAndPreservesReconnectTarget() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        assertSame(client, registry.clients.value[7L]?.client)

        client.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "dead active tmux client must be removed from dashboard registry",
            registry.clients.value.isEmpty(),
        )
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("Reconnect must remain available after EOF disconnect", vm.canReconnect.value)
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after EOF disconnect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun readerEofDisconnectStartsAutoReconnectAfterSilentGrace() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        try {
            val deadClient = FakeTmuxClient()
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )

            deadClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "eof",
                    intent = "unknown",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "structured transport EOF should enter the bounded auto-reconnect loop",
                1,
                connector.connectCount,
            )
            assertEquals("work", vm.activeSessionNameForTest())
            assertSame(reconnectClient, registry.clients.value[7L]?.client)
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

            val passive = diagnostics.eventsNamed("passive_disconnect").single()
            assertEquals("tmux_client_disconnected", passive.fields["source"])
            assertEquals("real_tmux_control_channel_closed", passive.fields["classification"])
            assertEquals("reader_eof", passive.fields["disconnectReason"])
            assertEquals("eof", passive.fields["disconnectSource"])
            assertEquals("unknown", passive.fields["disconnectIntent"])
            assertEquals("alpha.example", passive.fields["host"])
            assertEquals("work", passive.fields["session"])
            assertTrue(
                "passive EOF must not be logged as terminal render overflow",
                diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
            )
            val autoDecision = diagnostics.eventsNamed("auto_reconnect_decision").single()
            assertEquals("scheduled", autoDecision.fields["decision"])
            assertEquals("retryable", autoDecision.fields["cause"])
            assertEquals("tmux_eof_or_reader_disconnect", autoDecision.fields["reconnectSourceCandidate"])
            assertEquals("reader_eof", autoDecision.fields["disconnectReason"])
            assertTrue(
                "passive EOF should emit an automatic reconnect_start",
                diagnostics.eventsNamed("reconnect_start").any {
                    it.fields["trigger"] == TmuxConnectTrigger.AutoReconnect.logValue &&
                        it.fields["session"] == "work"
                },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun withinGraceForegroundHealExportsSuppressedDisconnectCauseToTrailAndFallback() =
        runTest(scheduler) {
            val diagnostics = installRecordingDiagnosticSink()
            val registry = ActiveTmuxClients()
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = FailingLeaseConnector(SshException("synthetic heal failure")),
                    scope = this,
                    idleTtlMillis = 0L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 1L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            try {
                val backgroundDeadClient = FakeTmuxClient()
                vm.replaceClientForTest(
                    hostId = 7L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = backgroundDeadClient,
                )
                runCurrent()

                vm.setProcessForegroundForClearedForTest(false)
                backgroundDeadClient.markDisconnectedForTest(
                    TmuxDisconnectEvent(
                        reason = TmuxDisconnectReason.ReaderEof,
                        source = "reader_loop",
                        intent = "unexpected_eof",
                    ),
                )
                runCurrent()

                vm.setProcessForegroundForClearedForTest(true)
                vm.onAppForegrounded(resumedWithinGrace = true)
                runCurrent()
                advanceTimeBy(1L)
                runCurrent()

                val healTrail = diagnostics.eventsNamed(ReconnectCauseTrail.NAME).single {
                    it.fields["stage"] == "foreground_reattach" &&
                        it.fields["outcome"] == "silent_heal_within_grace"
                }
                assertEquals("reader_eof", healTrail.fields["cause"])
                assertEquals("reader_eof", healTrail.fields["disconnectReason"])
                assertEquals("reader_loop", healTrail.fields["disconnectSource"])
                assertEquals("unexpected_eof", healTrail.fields["disconnectIntent"])

                val foregroundHeal = diagnostics.eventsNamed("foreground_reattach").single {
                    it.fields["outcome"] == "silent_heal_within_grace"
                }
                assertEquals("reader_eof", foregroundHeal.fields["cause"])
                assertEquals("reader_eof", foregroundHeal.fields["disconnectReason"])

                val scheduledFallback = diagnostics.eventsNamed("auto_reconnect_decision").single {
                    it.fields["decision"] == "scheduled" &&
                        it.fields["cause"] == "retryable"
                }
                assertEquals("reader_eof", scheduledFallback.fields["originationCause"])
                assertEquals("reader_eof", scheduledFallback.fields["disconnectReason"])
                assertEquals("reader_loop", scheduledFallback.fields["disconnectSource"])
            } finally {
                vm.setProcessForegroundForClearedForTest(null)
                diagnostics.close()
            }
        }

    @Test
    fun withinGraceForegroundDoesNotReseedDisconnectedHeldClient() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> replacementClient }
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val staleClient = FakeTmuxClient().withSinglePane("work", "%0")
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = staleClient,
            session = FakeSshSession(),
        )
        vm.markActiveLeaseWarmForTest()
        runCurrent()

        staleClient.disconnectedSignal.value = true
        vm.onAppForegrounded(resumedWithinGrace = true)
        advanceUntilIdle()

        assertTrue(
            "within-grace foreground must not run capture/reseed over a disconnected held client",
            staleClient.sentCommands.none { it.startsWith("capture-pane") },
        )
    }

    @Test
    fun livenessProbeDeclaredDropClosesTheClientAndDrivesTheSingleReconnectEntrypoint() =
        runTest(scheduler) {
            // EPIC #792 Slice D (#822/V7a): the LivenessProbe's confirmed-drop body
            // must CLOSE the dead client + drive the EXISTING tested recovery path
            // (the single TransportEffects reconnect entrypoint) — never a second
            // reconnect writer. This pins that wiring at the production VM layer.
            val diagnostics = installRecordingDiagnosticSink()
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 0L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            try {
                val liveClient = FakeTmuxClient()
                val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
                vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                    assertEquals("work", sessionName)
                    reconnectClient
                }
                vm.replaceClientForTest(
                    hostId = 7L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = liveClient,
                )

                // Pre-condition: settled Connected/Live, so the probe gate is OPEN.
                assertTrue(
                    "expected Connected before the probe-declared drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "the probe gate must be open while foregrounded + Live",
                    vm.shouldRunLivenessProbeForTest(),
                )
                assertFalse(
                    "the live client must not be disconnected before the probe fires",
                    liveClient.disconnected.value,
                )

                // The probe declared a sustained silent drop. Drive its confirmed-
                // drop body directly (the body the periodic loop fires) — this is the
                // detection→recovery wiring under test, independent of probe cadence.
                vm.triggerLivenessProbeDropForTest(consecutiveFailures = 2)
                advanceUntilIdle()

                // 1) The dead client was CLOSED by the probe (the single action that
                //    drives the whole existing recovery path).
                assertTrue(
                    "the probe must CLOSE the dead client (flips disconnected → " +
                        "existing recovery)",
                    liveClient.disconnected.value,
                )
                // 2) The drop was DETECTED (a passive_disconnect fired off the closed
                //    client) and a liveness_probe_silent_drop diagnostic was recorded.
                assertTrue(
                    "the probe must record its silent-drop detection",
                    diagnostics.eventsNamed("liveness_probe_silent_drop").any {
                        it.fields["session"] == "work" &&
                            it.fields["source"] == "liveness_probe"
                    },
                )
                // 3) Recovery ran through the SINGLE reconnect entrypoint (the same
                //    AutoReconnect ladder a real EOF drives) — NOT a second writer.
                assertEquals(
                    "the probe-closed client must drive ONE reconnect dial via the " +
                        "single TransportEffects entrypoint",
                    1,
                    connector.connectCount,
                )
                assertSame(reconnectClient, registry.clients.value[7L]?.client)
                // 4) The SAME session auto-recovered to Connected — no switch dance.
                assertEquals("work", vm.activeSessionNameForTest())
                assertTrue(
                    "the SAME session must auto-recover to Connected after the probe drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
            } finally {
                diagnostics.close()
            }
        }

    @Test
    fun silentDropAutoReconnectForceEvictsPoisonedHalfOpenLeaseAndDialsFresh() =
        runTest(scheduler) {
            // EPIC #792 / #822 (THE WEDGE REGRESSION, D31 durable-fix gate).
            //
            // The on-device #822 symptom: on a SILENT half-open Wi-Fi drop (no
            // network reason, mid voice-recording) the header stuck amber
            // "Reconnecting" forever, and the ONLY recovery was the switch-away-
            // and-back dance. The root cause is in the lease pool: sshj's
            // `isConnected` LIES (stays true ~60s until the keep-alive miss-counter
            // trips), so [SshLeaseManager.acquire] REUSES the poisoned warm entry,
            // and EVERY auto-reconnect attempt re-dials the SAME dead socket. The
            // switch dance recovered only because re-entering connect() to a
            // DIFFERENT host eventually evicted the poisoned lease.
            //
            // The fix ([shouldForceFreshLease] now includes
            // [TmuxConnectTrigger.AutoReconnect]) force-evicts the poisoned idle
            // entry before each ladder re-dial, so the SAME session recovers onto a
            // FRESH transport WITHOUT the switch dance.
            //
            // The sibling test
            // `livenessProbeDeclaredDropClosesTheClientAndDrivesTheSingleReconnectEntrypoint`
            // pins the detection→recovery WIRING but uses an EMPTY lease pool, so it
            // passes EVEN IF the wedge fix is reverted (an empty pool always dials
            // fresh). THIS test closes that gap: it pre-seeds the pool with the
            // poisoned half-open lease the real device holds, so reverting the
            // `AutoReconnect` line of [shouldForceFreshLease] makes the ladder reuse
            // the poisoned entry (connectCount stays 1, the SAME dead session) and
            // this assertion fails — the red→green proof of the actual wedge.
            val diagnostics = installRecordingDiagnosticSink()
            val registry = ActiveTmuxClients()
            // First dial yields the POISONED half-open session (isConnected lies
            // true); the second dial — only reached if the ladder force-evicts —
            // yields a FRESH session.
            val poisoned = FakeSshSession()
            val fresh = FakeSshSession()
            val connector = QueueLeaseConnector(poisoned, fresh)
            // Keep a real (virtual-clock) lease pool with a NON-zero idle TTL so the
            // released poisoned lease sits WARM/idle in the pool (the exact on-device
            // state) rather than closing on release — that is what makes the next
            // acquire reuse it unless force-evicted.
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            )
            val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            try {
                // Pre-seed the pool with the poisoned half-open lease for the EXACT
                // key the ladder will reconstruct from the target (hostId=1 + keyPath
                // /keys/a -> credentialId "1:/keys/a"). Acquire then release so it
                // sits warm/idle (refCount 0) and `isConnected` still lies true.
                // NOTE: drive the seed with runCurrent(), NOT advanceUntilIdle() —
                // the latter would advance the virtual clock past the 60s idle TTL
                // and let the idle-close timer fire, closing the poisoned lease and
                // emptying the pool (which would make the later reuse-vs-evict
                // distinction vacuous). runCurrent() settles the bounded dial without
                // advancing time, so the released lease sits warm/idle exactly as it
                // does on-device within the warm window.
                leaseManager.acquire(testLeaseTarget()).getOrThrow().release()
                runCurrent()
                assertEquals(
                    "the poisoned lease is dialed once and pooled warm",
                    1,
                    connector.connectCount,
                )
                assertTrue(
                    "the poisoned half-open lease still LIES isConnected==true while pooled " +
                        "(the deceptive #822 case)",
                    leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
                )

                val liveClient = FakeTmuxClient()
                val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
                vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                    assertEquals("work", sessionName)
                    reconnectClient
                }
                vm.replaceClientForTest(
                    hostId = 1L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = liveClient,
                )

                assertTrue(
                    "expected Connected before the silent half-open drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "the probe gate must be open while foregrounded + Live",
                    vm.shouldRunLivenessProbeForTest(),
                )

                // The liveness probe declared a sustained SILENT drop (the #822
                // scenario: no send, half-open Wi-Fi). Drive its confirmed-drop body
                // directly — it walks the controller Live -> Reattaching (the
                // immediate indicator) and drives the single AutoReconnect ladder.
                vm.triggerLivenessProbeDropForTest(consecutiveFailures = 2)
                advanceUntilIdle()

                // THE WEDGE ASSERTION (red→green): the AutoReconnect ladder must have
                // FORCE-EVICTED the poisoned half-open lease and dialed a SECOND,
                // FRESH transport. Without the fix the ladder reuses the poisoned
                // pooled entry, connectCount stays 1, and the session stays wedged.
                assertEquals(
                    "the silent-drop AutoReconnect ladder must FORCE-EVICT the poisoned " +
                        "half-open lease and dial a FRESH transport (the #822 wedge fix). " +
                        "connectCount==1 means it reused the poisoned lease — the wedge.",
                    2,
                    connector.connectCount,
                )
                assertTrue(
                    "the poisoned half-open SSH session must be CLOSED by the force-evict " +
                        "(not silently reused under the new client)",
                    poisoned.closed,
                )

                // Recovery is on the SAME session, no switch dance.
                assertEquals("work", vm.activeSessionNameForTest())
                assertSame(reconnectClient, registry.clients.value[1L]?.client)
                assertTrue(
                    "the SAME session must auto-recover to Connected after the silent " +
                        "half-open drop — WITHOUT a switch-to-another-session dance",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )

                // The drop was DETECTED proactively (the headline #822 requirement:
                // surface it, don't discover it only when a send fails).
                assertTrue(
                    "the probe must record its proactive silent-drop detection",
                    diagnostics.eventsNamed("liveness_probe_silent_drop").any {
                        it.fields["session"] == "work" &&
                            it.fields["source"] == "liveness_probe"
                    },
                )
                // The force-fresh-lease eviction was recorded for the AutoReconnect
                // trigger (the mechanism that breaks the wedge).
                assertTrue(
                    "the AutoReconnect ladder must record the force-fresh-lease eviction " +
                        "that breaks the #822 wedge",
                    diagnostics.eventsNamed("tmux_force_fresh_ssh_lease").any {
                        it.fields["trigger"] == TmuxConnectTrigger.AutoReconnect.logValue &&
                            it.fields["evictedLease"] == true
                    },
                )
            } finally {
                diagnostics.close()
            }
        }

    @Test
    fun manualReconnectForceEvictsPoisonedHalfOpenLeaseAndDialsFresh() = runTest(scheduler) {
        // EPIC #792 / #822 — the SECOND seam of the wedge class (D31 class coverage).
        //
        // The same poisoned-half-open-lease pathology must not strand the MANUAL /
        // send-triggered reconnect either (the [reconnect] affordance #823 will call
        // the SAME single TransportEffects entrypoint). The manual `Reconnect`
        // trigger already force-evicts ([shouldForceFreshLease]); this pins that it
        // dials a FRESH transport over the poisoned warm lease so a user tap (or a
        // send-while-not-Connected) recovers the SAME session without the switch
        // dance — covering the recovery-without-switch path for the manual seam.
        val registry = ActiveTmuxClients()
        val poisoned = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(poisoned, fresh)
        val leaseManager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        try {
            // runCurrent() (not advanceUntilIdle) so the released poisoned lease sits
            // warm/idle in the pool rather than being closed by the 60s idle timer —
            // otherwise the reuse-vs-evict distinction below is vacuous.
            leaseManager.acquire(testLeaseTarget()).getOrThrow().release()
            runCurrent()
            assertEquals(1, connector.connectCount)
            assertTrue(
                "the poisoned half-open lease must sit warm/idle (the deceptive #822 case)",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            val deadClient = FakeTmuxClient()
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )

            // The user taps Reconnect (the manual affordance / #823 entrypoint).
            val started = vm.reconnect()
            assertTrue("reconnect() must start with a target present", started)
            advanceUntilIdle()

            assertEquals(
                "the manual reconnect must FORCE-EVICT the poisoned half-open lease and " +
                    "dial a FRESH transport (no switch dance)",
                2,
                connector.connectCount,
            )
            assertTrue("the poisoned half-open session must be CLOSED by the force-evict", poisoned.closed)
            assertEquals("work", vm.activeSessionNameForTest())
            assertTrue(
                "the SAME session must recover to Connected after a manual reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            vm.clearForTest()
        }
    }

    @Test
    fun livenessProbeGateIsClosedWhenNotConnected() = runTest(scheduler) {
        // The probe must NOT run when there is no live session (no false-positive
        // teardown of a not-yet-connected / idle VM).
        val vm = newVm()
        assertFalse(
            "the probe gate must be closed with no attached client",
            vm.shouldRunLivenessProbeForTest(),
        )
    }

    @Test
    fun livenessProbePingReportsDeadWhenSyntheticSeamIsArmed() = runTest(scheduler) {
        // The synthetic-drop seam makes the ping report DEAD without touching the
        // wire — the deterministic per-PR detection lever (also proven on the
        // emulator by SilentDropSyntheticSeamJourneyE2eTest).
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        assertTrue(
            "a healthy attached client must ping alive",
            vm.runLivenessProbePingForTest(),
        )
        vm.forceLivenessProbeDeadForTest = true
        assertFalse(
            "the synthetic-drop seam must make the ping report dead",
            vm.runLivenessProbePingForTest(),
        )
        vm.forceLivenessProbeDeadForTest = false
        assertTrue(
            "clearing the seam restores the live ping",
            vm.runLivenessProbePingForTest(),
        )
    }

    @Test
    fun issue964KeepAliveCoordinationGuardReflectsTheLiveSessionKeepalive() = runTest(scheduler) {
        // Issue #964 — the VM wiring of the keepalive-coordination guard the
        // LivenessProbe defers to. The probe's ProbeIo.transportProvenAliveRecently
        // is wired to the live SshSession's keepalive-liveness signal, so on a
        // slow-but-live link (control probe failing, keepalive still proving the
        // transport alive) the probe DEFERS and does NOT force a redial.
        val vm = newVm()

        // No live session yet → no keepalive signal → the probe keeps its own
        // authority (guard reports NOT-alive), exactly as before.
        assertFalse(
            "with no live session there is no keepalive signal to defer to",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // Attach a live session whose transport keepalive is still riding through
        // (a slow-but-live link): the guard must report ALIVE so the probe defers.
        val session = FakeSshSession().apply { transportProvenAlive = true }
        val liveClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = liveClient,
            session = session,
        )
        runCurrent()
        assertTrue(
            "while the transport keepalive proves the link alive the guard must report " +
                "ALIVE so the probe defers (no spurious redial on a slow-but-live link)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // The transport genuinely dies — the keepalive stops proving liveness, so
        // the guard reports NOT-alive and the probe regains authority (no infinite
        // deferral / hang on a real death).
        session.transportProvenAlive = false
        assertFalse(
            "once the keepalive stops proving liveness the guard must report NOT-alive " +
                "so the probe can declare the real drop (#964 — deferral is not a hang)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // The explicit test seam pins the verdict independently of the session.
        vm.forceTransportProvenAliveForTest = true
        assertTrue(
            "the #964 test seam pins the keepalive-alive verdict",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )
        vm.forceTransportProvenAliveForTest = null
        assertFalse(
            "clearing the seam falls back to the (now dead) session signal",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        vm.clearForTest()
    }

    @Test
    fun briefPassiveEofSilentlyReattachesWithoutDisconnectBandOrConnectAttempt() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals("work", vm.activeSessionNameForTest())
        assertEquals(
            "silent same-SSH reattach must not count as a logical reconnect attempt",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue("stale client should be closed after silent reattach", deadClient.closed)
        assertTrue("replacement control client should be opened", replacementClient.connectCalled)
        assertTrue(
            "silent same-SSH reattach expects the session to exist, so it must probe server liveness",
            vm.lastProbeServerLivenessForTest(),
        )
    }

    @Test
    fun passiveEofShowsReconnectOnlyAfterSilentGraceExpires() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(IOException("network still unavailable"))
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 500L, silentReattachTimeoutMs = 500L)
        // Issue #1654: `listOf(60_000L)` no longer expresses what this fixture MEANS, because
        // #1654 gives the single ladder two jobs this test depends on independently:
        //
        //  1. its LENGTH is now the real attempt BUDGET (the VM's `autoReconnectDelaysMs` IS
        //     the controller's ladder; it used to never reach the controller, so the length was
        //     inert). A 1-rung ladder means "give up after ONE failure", so the grace loop's
        //     very first rung-failure feed exhausted it and painted the honest Failed band
        //     499ms into a 500ms grace window — breaking the calm-band assertion below.
        //  2. its DELAYS now pace the grace loop's own cycles (#1654's A2 fix: the loop read a
        //     flat 250ms constant and so could never back off). 60s rungs would space the
        //     cycles 60s apart, so the loop would dial ONCE in this 500ms window — breaking the
        //     #833 re-dial assertion below.
        //
        // Both jobs are satisfied by the ladder this test always wanted: FOUR rungs (a budget
        // as real as production's 8, so a couple of grace cycles cannot exhaust it) at ZERO
        // delay, which `passiveGraceCycleRetryDelayMs` floors to the SAME 250ms spacing the
        // #833 comment below describes. Production's own value is the 8-rung
        // DEFAULT_AUTO_RECONNECT_DELAYS_MS, so this is a fixture artifact being corrected, not
        // a behaviour change — #1539's fixture comment predicted exactly this trap. Every
        // assertion below is UNCHANGED and still load-bearing.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(499L)
        runCurrent()

        // EPIC #687 slice 1c-iv-a — APPROVED #685 divergence #1 (silent recovery):
        // a recoverable live-channel drop now surfaces a CALM Reconnecting band
        // (the controller heals through Reattaching/Reconnecting), NOT the scary
        // Failed/"Tap Reconnect" band and NOT the old silently-held Connected frame.
        // Only the displayed status is the calm Reconnecting (was: Connected held).
        assertTrue(
            "recoverable drop must show the calm Reconnecting band, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertSame(
            "dashboard registry should still point at the held client during grace",
            deadClient,
            registry.clients.value[7L]?.client,
        )
        // EPIC #792 #833: the silent-reattach grace loop must KEEP re-dialling a
        // fresh transport across a SUSTAINED clean outage (the old one-shot latch
        // wedged after a single attempt). With a 500ms grace + 250ms retry spacing
        // the loop re-dials more than once before grace expires — proving the
        // resilience fix — while still BOUNDED by the grace window (no hot loop /
        // infinite re-dial: the count is small, paced by the retry delay).
        assertTrue(
            "silent fresh-transport probing must RE-DIAL across a sustained outage " +
                "(resilience #833), got connectCount=${connector.connectCount}",
            connector.connectCount >= 2,
        )
        assertTrue(
            "silent fresh-transport probing must stay BOUNDED by the grace window " +
                "(no hot loop), got connectCount=${connector.connectCount}",
            connector.connectCount <= 4,
        )

        advanceTimeBy(2L)
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "sustained passive EOF should surface the manual reconnect state after grace, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(registry.clients.value.isEmpty())
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    /**
     * Issue #685 (Bug A): the grace-clock collapse. The maintainer's #1 daily
     * pain ("lots of reconnects on stable home Wi-Fi") was caused by THREE
     * disagreeing grace clocks, the worst being a stray 8s VM
     * `PASSIVE_DISCONNECT_GRACE_MS` that tore the held Connected frame down long
     * before the single 60s lease/keepalive would. This pins the VM grace to the
     * ONE lease-anchored 60s window so the divergent 8s clock can never be
     * reintroduced — a sub-60s background/blip must hold without a reconnect.
     */
    @Test
    fun passiveDisconnectGraceIsAnchoredToTheSingle60sLeaseWindow() {
        assertEquals(
            "VM passive-disconnect grace must defer to the single 60s lease TTL " +
                "(no divergent 8s VM clock); collapsing the clocks is the #685 " +
                "Bug A reconnect-on-stable-Wi-Fi fix",
            SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
            PASSIVE_DISCONNECT_GRACE_MS,
        )
        assertEquals(
            "the single lease-anchored grace window is 60s",
            60_000L,
            PASSIVE_DISCONNECT_GRACE_MS,
        )
    }

    /**
     * Issue #685 (Bug A): a passive transport blip WITHIN the lease-anchored 60s
     * grace must reattach silently with ZERO logical reconnect, using the REAL
     * production grace default (no test override) so the regression is caught at
     * the shipped value — not a convenient short test grace. The held Connected
     * frame stays; the dashboard registry keeps pointing at the live client.
     */
    @Test
    fun withinDefaultGracePassiveBlipReattachesWithZeroReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        // Deliberately NO setPassiveDisconnectRecoveryForTest override: exercise
        // the shipped 60s grace so a regressed short clock would fail here.
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        // Resolve the silent reattach WITHIN the 60s grace.
        advanceTimeBy(5_000L)
        runCurrent()
        advanceUntilIdle()

        assertSame(
            "a within-grace blip must hand off to the freshly reattached client, " +
                "not strand on the dead one",
            replacementClient,
            registry.clients.value[7L]?.client,
        )
        assertTrue(
            "within the 60s grace the indicator stays green — no reconnect band",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "a within-grace silent reattach must NOT count as a logical reconnect",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
    }

    // ------------------------------------------------------------------
    // Issue #1177 (black-screen GAP B) — the silent-reattach paths (:8077
    // warm same-SSH reattach, :8319 fresh-transport reattach) arm the blank
    // watchdog with surfaceErrorOnExhaustion=false. When the reattach SEED
    // stays blank for the whole blank-watchdog window the watchdog used to
    // exit SILENTLY, leaving a PERMANENT BLACK pane on a live (green) transport
    // (the maintainer's #874 fully-black-on-green). Class coverage: both real
    // reattach call sites must now hand off to the lifetime stale-render heal
    // watchdog on exhaustion (never silent black), and it must actually heal
    // the pane once tmux carries a frame.
    // ------------------------------------------------------------------

    @Test
    fun passiveSilentReattachThatSeedsBlankArmsStaleRenderHealNotSilentBlack() = runTest(scheduler) {
        // :8077 — the WARM same-SSH silent reattach. The replacement client's
        // list-panes row resolves (reattach reaches Live) but every capture is
        // empty, so the reattached active pane is BLACK on a live transport.
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
        // Foreground + screen-on so the armed stale-render watchdog actually
        // captures (the #1166 heat gate) and can heal.
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        // Issue #1258 (CI-flake fix, sibling of #1250/#1259): pin the
        // TerminalSurfaceState external-producer dispatcher to the SHARED
        // virtual-clock scheduler. The default factory builds
        // `TerminalSurfaceState()` on the production `Dispatchers.IO` (a real
        // off-Main thread — correct on-device). But this warm silent reattach
        // REBINDS each pane's producer several times (initial attach → reattach
        // rebindVisiblePaneProducersToClient → each connected-blank-watchdog
        // reseed), and every rebind CANCELS the prior producer whose `finally`
        // runs `detachCompletedExternalProducer` (nulling `bridge`/`_session`)
        // via a `Dispatchers.Main.immediate` hop OFF that real IO thread. Under
        // `runTest`'s virtual clock that real-thread hop lands at a
        // non-deterministic point, so the pane's emulator was sometimes DETACHED
        // (isAttached=false) at assertion time — and `visibleScreenIsBlank()`
        // returns false with NO emulator, spuriously failing the "seed stayed
        // empty -> black" precondition under load. Confining the producer to the
        // shared scheduler makes attach/detach step deterministically under
        // `advanceTimeBy`/`runCurrent` (same pin as the sibling heal tests).
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(scheduler))
        }
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient =
            FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        // Complete the warm reattach (reaches Live) but DON'T drain the lifetime
        // watchdog: advance a bounded window that (a) settles the reattach and
        // (b) exhausts the ~17s blank-watchdog run on the still-black pane.
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS * CONNECTED_BLANK_WATCHDOG_MAX_TICKS * 4)
        runCurrent()

        assertSame(
            "the warm reattach reached Live on the fresh client",
            replacementClient,
            registry.clients.value[7L]?.client,
        )
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "the reattached pane's seed stayed empty -> black on a live transport",
            pane.terminalState.visibleScreenIsBlank(),
        )
        // GAP B fix: on blank-watchdog exhaustion the :8077 path hands off to the
        // stale-render heal watchdog instead of exiting silently into permanent
        // black. RED (base): no watchdog armed (job null). GREEN: it is running.
        val staleJob = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1177 (:8077 warm reattach): a blank reattach seed must arm the " +
                "stale-render heal watchdog, never a permanent silent black. job=$staleJob",
            staleJob != null && staleJob.isActive,
        )
        // ...and it HEALS the black pane once tmux carries a real frame.
        replacementClient.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = issue1177RecoveredFrame(), isError = false),
        )
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertFalse(
            "Issue #1177 (:8077): the stale-render heal must repaint the black " +
                "reattached pane — never a permanent black on a live transport.",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    @Test
    fun transportSilentReattachThatSeedsBlankArmsStaleRenderHealNotSilentBlack() = runTest(scheduler) {
        // :8319 — the FRESH-TRANSPORT silent reattach. The old session is broken
        // (isConnected=false) so the warm reattach fails and a fresh transport is
        // reacquired via the lease connector; its replacement client's row resolves
        // but every capture is empty, so the reattached pane is BLACK on green.
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        // Issue #1250 (CI-flake fix): pin the TerminalSurfaceState external-producer
        // dispatcher to the SHARED virtual-clock scheduler. The default factory builds
        // `TerminalSurfaceState()` on the production `Dispatchers.IO` (a real off-Main
        // thread — correct for the on-device feed). But this fresh-transport reattach
        // REBINDS each pane's producer several times (initial attach → reattach
        // rebindVisiblePaneProducersToClient → each connected-blank-watchdog reseed),
        // and every rebind CANCELS the prior producer whose `finally` runs
        // `detachCompletedExternalProducer` (nulling `bridge`/`_session`) via a
        // `Dispatchers.Main.immediate` hop OFF that real IO thread. Under `runTest`'s
        // virtual clock that real-thread hop lands at a non-deterministic point, so the
        // pane's emulator was sometimes DETACHED (isAttached=false) at assertion time —
        // and `visibleScreenIsBlank()` returns false with NO emulator, spuriously
        // failing the "seed stayed empty -> black" precondition (~70% in isolation).
        // Confining the producer to the shared scheduler makes attach/detach step
        // deterministically under `advanceTimeBy`/`runCurrent` (same pin as the sibling
        // heal tests, e.g. PartialBlackPaneHealTest / ReseedBlankWatchdogCharacterizationTest).
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(scheduler))
        }
        val deadClient = FakeTmuxClient()
        val replacementClient =
            FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS * CONNECTED_BLANK_WATCHDOG_MAX_TICKS * 4)
        runCurrent()

        assertSame(
            "the fresh-transport reattach reached Live on the fresh client",
            replacementClient,
            registry.clients.value[7L]?.client,
        )
        assertEquals("a fresh transport was reacquired", 1, connector.connectCount)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "the fresh-transport reattach's seed stayed empty -> black on a live transport",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val staleJob = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1177 (:8319 fresh-transport reattach): a blank reattach seed must " +
                "arm the stale-render heal watchdog, never a permanent silent black. job=$staleJob",
            staleJob != null && staleJob.isActive,
        )
        replacementClient.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = issue1177RecoveredFrame(), isError = false),
        )
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertFalse(
            "Issue #1177 (:8319): the stale-render heal must repaint the black " +
                "reattached pane — never a permanent black on a live transport.",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    /**
     * Issue #685 (Bug B): the "control channel closed before response /
     * mid-command" error in the maintainer's screenshot is the beyond-grace
     * foreground-reattach symptom. It must be classified as a stale-channel
     * symptom so it routes through the transparent re-dial (calm Reconnecting +
     * automatic pane reseed) instead of the scary `Failed` band with "Tap
     * Reconnect to retry." + a stuck spinner.
     */
    @Test
    fun controlChannelClosedIsTreatedAsAStaleChannelSymptom() {
        val vm = newVm()
        assertTrue(
            "'control channel closed before response' must heal via transparent " +
                "re-dial, not the scary manual-tap band",
            vm.isStaleChannelSymptom(
                TmuxClientException("control channel closed before response"),
            ),
        )
        assertTrue(
            "'control channel closed mid-command' must also heal transparently",
            vm.isStaleChannelSymptom(
                RuntimeException(
                    "list-panes failed",
                    TmuxClientException("control channel closed mid-command"),
                ),
            ),
        )
        assertFalse(
            "an ordinary error must NOT be misclassified as a stale-channel heal",
            vm.isStaleChannelSymptom(IllegalStateException("some unrelated failure")),
        )
    }

    /**
     * Issue #685 (honest indicator, false-negative direction — the "no
     * indication I'm disconnected" complaint): a sustained passive disconnect
     * that the silent grace CANNOT recover must flip the indicator OFF green
     * within the bounded grace — never keep showing a stale "connected" green
     * dot over a confirmed-dead channel. After the bounded grace the status is a
     * non-Connected (honest) state.
     */
    @Test
    fun sustainedPassiveDisconnectFlipsIndicatorOffGreenWithinBoundedGrace() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(IOException("network still unavailable"))
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Bounded test grace so the assertion is deterministic; production uses
        // the 60s lease-anchored window asserted separately above.
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 500L, silentReattachTimeoutMs = 500L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(501L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(
            "a confirmed-dead channel must NOT keep showing a stale green " +
                "'connected' indicator past the bounded grace (the #685 " +
                "'no indication I'm disconnected' false-negative)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun passiveEofSilentlyReacquiresTransportWhenOldSessionIsBroken() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            "hidden fresh-transport recovery must not increment the user-visible connect counter",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue(replacementClient.connectCalled)
    }

    @Test
    fun failedSilentTransportReattachEvictsLeaseSoManualReconnectUsesFreshSession() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val failedReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(failedReconnectSession, manualReconnectSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 100L, silentReattachTimeoutMs = 1L)
        val deadClient = FakeTmuxClient()
        val failedAttachClient = FakeTmuxClient()
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val clients = ArrayDeque(listOf(failedAttachClient, manualReconnectClient))
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertTrue(
            "failed hidden reattach should surface manual reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "failed hidden reattach must close the half-attached tmux client",
            failedAttachClient.closed,
        )
        assertTrue(
            "failed hidden reattach must evict the acquired SSH lease instead of idling it",
            failedReconnectSession.closed,
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be available after failed hidden reattach", vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "manual reconnect must open a fresh SSH session, not reuse the failed hidden lease",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(failedReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun manualReconnectCancelsInFlightSilentTransportReattachAndUsesFreshSession() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val hiddenReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(hiddenReconnectSession, manualReconnectSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 60_000L)
        val deadClient = FakeTmuxClient()
        val hiddenAttachGate = CompletableDeferred<Unit>()
        val hiddenAttachClient = FakeTmuxClient().apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = hiddenAttachGate
        }
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val clients = ArrayDeque(listOf(hiddenAttachClient, manualReconnectClient))
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "hidden reattach should be stalled while waiting for panes",
            hiddenAttachClient.sentCommands.any { it.startsWith("list-panes") },
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be accepted while hidden reattach is in flight", vm.reconnect())
        advanceUntilIdle()

        assertTrue(
            "interrupted hidden reattach must close its half-attached tmux client",
            hiddenAttachClient.closed,
        )
        assertTrue(
            "interrupted hidden reattach must evict its fresh SSH lease",
            hiddenReconnectSession.closed,
        )
        assertEquals(
            "manual reconnect must open a fresh SSH session after interrupting hidden reattach",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(hiddenReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }


    // ------------------------------------------------------------------
    // Issue #1568 — the #1562 reconnect STORM, at the app layer.
    //  P0-2: a -CC reader read_failure must NOT evict the warm lease + dial a
    //        fresh transport when the transport is VOUCHED ALIVE — recover the
    //        channel over the LIVE transport instead (no self-storm).
    //  P0-5: an ExplicitClose (self-inflicted TmuxClient.close()) must NOT re-arm
    //        recovery — that is the dial->close->re-arm->dial loop.
    // The class-coverage of the pure RUNG/Ignore selectors lives in the sibling
    // connection.PassiveTransportDropEffectsTest; these are the real-path VM proofs.
    // ------------------------------------------------------------------

    @Test
    fun issue1568_readerFailureOverVouchedAliveTransport_recoversOverLiveTransport_noLeaseEviction() =
        runTest(scheduler) {
            // P0-2 red->green (recover-not-evict). A -CC reader read_failure (ReaderException)
            // while the SSH transport is VOUCHED ALIVE (isConnected && !isCloseInitiated) must
            // recover the channel over the LIVE transport. BASE: preferFreshTransport =
            // leaseRef != null = true -> the grace loop dials a FRESH lease-evicting transport
            // FIRST (connectCount 1 -> 2, warm session closed) — the seq-66866 self-storm.
            // GREEN: the vouch flips preferFreshTransport false -> warm channel-only reattach
            // over the SAME live session (NO fresh dial, NO lease eviction).
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val warmSession = FakeSshSession() // isConnected=true, isCloseInitiated=false -> vouched alive
            val freshSession = FakeSshSession() // only reached if the buggy fresh dial runs
            val connector = QueueLeaseConnector(warmSession, freshSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
            var factoryCalls = 0
            val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                factoryCalls += 1
                assertEquals("work", sessionName)
                assertSame(
                    "the vouched-alive recovery must reuse the LIVE warm transport, not a fresh dial",
                    warmSession,
                    session,
                )
                replacementClient
            }
            val deadClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
                session = warmSession,
            )
            // Hold a warm lease — the exact #1562 precondition (a warm per-host transport).
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals("the warm lease is dialed exactly once before the drop", 1, connector.connectCount)

            deadClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderException,
                    source = "read_failure",
                    intent = "unknown",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "vouched-alive -CC death must recover over the LIVE transport — NO fresh lease " +
                    "dial. connectCount==2 means the lease-evicting fresh dial ran (the #1562 storm).",
                1,
                connector.connectCount,
            )
            assertFalse(
                "the warm lease/transport must NOT be evicted on a channel-only recovery " +
                    "(evicting it is what severs the conversation loader / upload sidecar — #1562)",
                warmSession.closed,
            )
            assertSame(replacementClient, registry.clients.value[7L]?.client)
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
            assertEquals("work", vm.activeSessionNameForTest())
            assertTrue(
                "the channel-only reattach over the SAME live session probes server liveness",
                vm.lastProbeServerLivenessForTest(),
            )
            assertEquals("exactly one recovery client is created (the warm reattach)", 1, factoryCalls)
        }

    @Test
    fun issue1568_readerFailureOverDeadTransport_escalatesToFreshDial_notMasked() = runTest(scheduler) {
        // P0-2 non-masking (G2): a GENUINE transport death (sshj flips isConnected false) must
        // still evict the lease and dial a FRESH transport — the vouch fails, so the fix never
        // masks a real death. Identical behavior on base and fix (the guard that the vouch
        // degrades correctly).
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val leaseSession = FakeSshSession() // the warm-lease dial (connectCount 1)
        val freshSession = FakeSshSession() // the escalation fresh dial (connectCount 2)
        val connector = QueueLeaseConnector(leaseSession, freshSession)
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false), // DEAD transport -> vouch fails
        )
        vm.setActiveLeaseRefWarmForTest()
        runCurrent()
        assertEquals(1, connector.connectCount)

        deadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderException,
                source = "read_failure",
                intent = "unknown",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "a genuine transport death must escalate to a FRESH dial — the vouch must NOT mask it",
            2,
            connector.connectCount,
        )
        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun issue1568_readerFailureWhileCloseInitiated_escalatesToFreshDial_notMasked() = runTest(scheduler) {
        // P0-2 non-masking (G2), the #1222 async-close staleness window: isConnected still LIES
        // true for ~2s after a close was initiated. The vouch must consult isCloseInitiated and
        // FAIL here (a vouch that only checked isConnected would recover over the doomed session
        // and mask the death — connectCount would stay 1). With the fix the vouch fails ->
        // fresh dial -> connectCount 2.
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val leaseSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(leaseSession, freshSession)
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            // isConnected LIES true, but the close is already initiated -> vouch must fail.
            session = FakeSshSession(isConnectedValue = true, isCloseInitiatedValue = true),
        )
        vm.setActiveLeaseRefWarmForTest()
        runCurrent()
        assertEquals(1, connector.connectCount)

        deadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderException,
                source = "read_failure",
                intent = "unknown",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "a close-initiated (doomed) transport must escalate to a FRESH dial — the vouch must " +
                "consult isCloseInitiated, not just isConnected (which lies true in the #1222 window)",
            2,
            connector.connectCount,
        )
        assertSame(replacementClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun issue1568_explicitCloseOnCurrentClientDoesNotRearmRecovery() = runTest(scheduler) {
        // P0-5 red->green (no re-arm). A self-inflicted ExplicitClose on the CURRENT client must
        // NOT re-arm recovery. BASE: ExplicitClose is not ignored -> SilentReattachWithinGrace
        // -> a recovery client is dialed (the dial->close->re-arm->dial STORM). GREEN: it is
        // classified Ignore -> no recovery client, no dial, and a passive_disconnect_ignored
        // diagnostic is recorded.
        val diagnostics = installRecordingDiagnosticSink()
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val warmSession = FakeSshSession()
        val connector = QueueLeaseConnector(warmSession, FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
        var factoryCalls = 0
        vm.setTmuxClientFactoryForTest { _, _, _ ->
            factoryCalls += 1
            FakeTmuxClient().withSinglePane("work", "%1")
        }
        val currentClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = currentClient,
            session = warmSession,
        )
        vm.setActiveLeaseRefWarmForTest()
        runCurrent()
        val connectsBefore = connector.connectCount
        try {
            currentClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ExplicitClose,
                    source = "local_close",
                    intent = "explicit_close",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "a self-inflicted ExplicitClose must NOT dial a recovery transport (that is the " +
                    "#1562 dial->close->re-arm->dial storm)",
                connectsBefore,
                connector.connectCount,
            )
            assertEquals(
                "a self-inflicted ExplicitClose must NOT create a recovery client (no re-arm)",
                0,
                factoryCalls,
            )
            assertTrue(
                "an ExplicitClose of the current client must classify Ignore (no re-arm)",
                diagnostics.eventsNamed("passive_disconnect_ignored").any {
                    it.fields["disconnectReason"] == TmuxDisconnectReason.ExplicitClose.logValue
                },
            )
        } finally {
            diagnostics.close()
        }
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

    private class FailingLeaseConnector(
        private val failure: Throwable,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.failure(failure)
        }
    }

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

    /**
     * Issue #1177 (black-screen GAP B): a replacement/reattach client whose
     * `list-panes` row resolves but whose `capture-pane` seed keeps coming back
     * empty, leaving the reattached active pane black on a live transport.
     */
    private fun FakeTmuxClient.withSinglePaneRowButEmptyCapture(
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
        // No capturePaneResponses queued: every capture falls back to the default
        // empty response -> the reattached pane stays black.
    }

    private fun issue1177RecoveredFrame(): List<String> = buildList {
        add("ISSUE1177-REATTACH-HEALED — recovered viewport after silent-reattach")
        repeat(24) { add("recovered context row $it xxxxxxxxxxxxxxxxxxxxxxxxxxxx") }
    }

    private fun testLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "alpha.example",
                port = 22,
                user = "alex",
                credentialId = "1:/keys/a",
            ),
            key = SshKey.Path(File("/keys/a")),
        )

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        // Issue #1568 (P0-2): the #1222 async-close staleness window — `isConnected` may still
        // lie true while a close has already been initiated. The transport vouch must fail here.
        private val isCloseInitiatedValue: Boolean = false,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        @Volatile
        var transportProvenAlive: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override val isCloseInitiated: Boolean
            get() = isCloseInitiatedValue

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean =
            transportProvenAlive && isConnected

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
