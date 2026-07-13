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
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelNetworkLifecycleTest : TmuxSessionViewModelTestBase() {
    @Test
    fun networkChangeLifecycleHookEntersReconnectingWithoutConnectionError() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
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

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()

        assertEquals(
            "network change should not wait for the tmux reader EOF",
            0,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "network change must show reconnect-in-progress, not a connection error; got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertTrue(
            "reconnect reason should avoid misleading manual retry wording",
            "Tap Reconnect" !in (status as TmuxSessionViewModel.ConnectionStatus.Reconnecting).reason,
        )
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
        assertTrue("manual reconnect remains available during proactive reconnect", vm.canReconnect.value)
        assertTrue(
            "proactive reconnect should remove the stale active client from the registry",
            registry.clients.value.isEmpty(),
        )
    }

    @Test
    fun sameNetworkHandleWithDifferentTransportsDoesNotEnterReconnecting() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        TMUX_CONNECT_ATTEMPTS.set(0)
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setAutoReconnectDelaysForTest(listOf(0L))
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

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI")),
                    current = TerminalNetworkSnapshot.Validated("same-handle", setOf("VPN", "WIFI")),
                    previousValidated = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI")),
                    reason = "same-handle-transport-metadata",
                ),
            )
            advanceUntilIdle()

            assertTrue(
                "same network handle with changed transports is metadata churn, got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(0, connector.connectCount)
            assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
            assertSame(client, registry.clients.value[7L]?.client)
            val skip = diagnostics.eventsNamed("network_reconnect_skip").single()
            assertEquals("no_real_validated_handoff", skip.fields["cause"])
            assertEquals("network_identity_unchanged", skip.fields["classification"])
            assertEquals(false, skip.fields["reconnect"])
            assertEquals(false, skip.fields["realValidatedIdentityChange"])
            assertEquals("same-handle", skip.fields["currentNetworkHandle"])
            assertEquals("VPN,WIFI", skip.fields["currentTransports"])
            val trail = diagnostics.eventsNamed("cause_trail")
                .single { it.fields["stage"] == "network_reconnect_decision" }
            assertEquals("suppress", trail.fields["outcome"])
            assertEquals("no_real_validated_handoff", trail.fields["cause"])
            assertEquals("network_identity_unchanged", trail.fields["classification"])
            assertEquals(7L, trail.fields["hostId"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun differentNetworkHandleStillEntersReconnecting() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
            )
            runCurrent()

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
                    current = TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR")),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
                    reason = "different-network-handle",
                ),
            )
            runCurrent()

            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
            assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
            assertEquals(0, connector.connectCount)
            assertTrue("network reconnect removes stale active client", registry.clients.value.isEmpty())
            val start = diagnostics.eventsNamed("network_reconnect_start").single()
            assertEquals("proactive_network_handoff", start.fields["classification"])
            assertEquals(true, start.fields["reconnect"])
            assertEquals(true, start.fields["realValidatedIdentityChange"])
            assertEquals("wifi", start.fields["previousValidatedNetworkHandle"])
            assertEquals("cell", start.fields["currentNetworkHandle"])
            val trail = diagnostics.eventsNamed("cause_trail")
                .single { it.fields["stage"] == "network_reconnect_decision" }
            assertEquals("schedule_reconnect", trail.fields["outcome"])
            assertEquals("proactive_network_handoff", trail.fields["cause"])
            assertEquals("proactive_network_handoff", trail.fields["classification"])
            assertEquals(7L, trail.fields["hostId"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun restoredSameNetworkHintDuringActiveTerminalOutputDoesNotShowReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
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
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val slowSideChannelCollector = launch {
            state.output.collect {
                delay(60_000)
            }
        }
        runCurrent()

        try {
            client.emittedEvents.emit(ControlEvent.Output("%0", issue576BackpressureOutputChunks().first()))
            runCurrent()

            val hook = registry.lifecycleHooksSnapshot().single()
            repeat(4) { index ->
                hook.onNetworkChanged(
                    networkChange(
                        previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                        current = TerminalNetworkSnapshot.Validated("wifi"),
                        previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                        reason = "validated-network-during-output-$index",
                    ),
                )
                runCurrent()
            }

            assertTrue(
                "active terminal output proves the tmux stream is alive; network hints must not show reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "network hints during active output must not enqueue reconnect attempts",
                0,
                connector.connectCount,
            )
            assertEquals(
                "network hints during active output must not increment the reconnect counter",
                0,
                TMUX_CONNECT_ATTEMPTS.get(),
            )
            assertFalse(
                "terminal-side backpressure must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
        } finally {
            slowSideChannelCollector.cancel()
        }
    }

    @Test
    fun outputForOnlyTerminalOutputKeepsNetworkHintFromShowingReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val client = FakeTmuxClient()
        client.decoupleOutputForFromEvents = true
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
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        client.emittedPaneOutputs.emit(ControlEvent.Output("%0", "visible via outputFor".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "same-network-hint-after-outputFor-only-output",
            ),
        )
        runCurrent()

        assertTrue(
            "visible output delivered without client.events %output must still suppress same-network reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun firstValidatedNetworkHintDoesNotReconnectIdleStableTerminal() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = null,
                reason = "first-validated-network",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "first validated callback is startup state discovery, not proof that SSH died",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun realNetworkIdentityChangeDuringActiveTerminalOutputStillReconnects() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
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
        client.emittedEvents.emit(ControlEvent.Output("%0", "still streaming".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "wifi-cellular-handoff",
            ),
        )
        runCurrent()

        assertEquals(
            "real validated identity change should still use proactive reconnect despite recent output",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
        assertEquals(0, connector.connectCount)
    }

    // ---- Issue #981: network handoff rides through while the transport is proven alive ----

    @Test
    fun issue981ValidatedNetworkFlipDoesNotTearDownTransportProvenAliveLink() = runTest(scheduler) {
        // Issue #981 reproduce-first (red on base): a REAL validated default-network
        // identity flip (WIFI→CELLULAR) arrives while the live SSH transport is
        // provably alive (its keepalive saw inbound bytes within the ride-through
        // window — the #974 stable-wifi case where -CC traffic keeps the link warm).
        // The reactive handoff path must NOT tear down + redial the healthy socket.
        // On base (no liveness gate) this asserts Reconnecting + 1 connect and FAILS;
        // with the fix it rides through (Connected, zero connects).
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        // A live session whose transport keepalive proves the link is alive.
        val session = FakeSshSession().apply { transportProvenAlive = true }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        advanceUntilIdle()
        assertTrue(
            "precondition: the link is the proven-alive transport the gate must ride through",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "transient-wifi-cellular-flip-on-stable-wifi",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "a validated network flip while the transport is PROVEN ALIVE must ride through, " +
                "not tear down the healthy socket (#981 / #974 stable-wifi drop)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "no redial may be scheduled while the old transport is provably alive",
            0,
            connector.connectCount,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
        assertNotEquals(
            "the proactive-handoff redial intent must NOT fire on a proven-alive link",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
    }

    @Test
    fun issue981ValidatedNetworkFlipStillReconnectsWhenTransportIsDead() = runTest(scheduler) {
        // Issue #981 class-coverage (G2): the gate must NOT mask a GENUINE handoff.
        // Same WIFI→CELLULAR flip, but the transport keepalive has aged out (the old
        // socket is really dead) → the path must STILL reconnect, exactly as before.
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        // A live session whose transport keepalive has stopped proving liveness.
        val session = FakeSshSession().apply { transportProvenAlive = false }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        runCurrent()
        assertFalse(
            "precondition: the dead transport must NOT report proven-alive",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "genuine-wifi-cellular-handoff-dead-link",
            ),
        )
        runCurrent()

        assertEquals(
            "a genuine handoff on a DEAD link must still proactively reconnect (gate must not mask recovery)",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
    }

    @Test
    fun issue981SameIdentityReassocStillSuppressedRegardlessOfTransportLiveness() = runTest(scheduler) {
        // Issue #981 class-coverage: a same-identity reassoc (#875 pure-{WIFI} roam)
        // is still suppressed by hasSameNetworkIdentityAs BEFORE the liveness gate,
        // so the new gate does not change that path — proven-alive or not.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val session = FakeSshSession().apply { transportProvenAlive = false }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "same-identity-reassoc",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "a same-identity reassoc is suppressed upstream (#875) and never reaches the redial",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun issue981ReducerSuppressesProvenAliveButSchedulesDeadLinkFlip() = runTest(scheduler) {
        // Issue #981 reducer-level proof: the decision classifier itself returns
        // SuppressNetworkTransportProvenAlive when proven alive and
        // ScheduleNetworkReconnect when the link is dead, using the explicit seam.
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        // Proven alive → ride through.
        vm.forceTransportProvenAliveForTest = true
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "flip-while-proven-alive"),
        )
        runCurrent()
        assertTrue(
            "proven-alive flip rides through (no Reconnecting band)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)

        // Aged out (dead) → reconnect.
        vm.forceTransportProvenAliveForTest = false
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "flip-while-dead", sequence = 2L),
        )
        runCurrent()
        assertEquals(
            "once the keepalive is dead the same flip must schedule the proactive reconnect",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)

        vm.forceTransportProvenAliveForTest = null
    }

    // ---- Issue #997: bare network LOSS → hold (no churn) → RESTORE → fast reconnect.
    // Pre-#997 a clean loss produced NO proactive change at all (the detector
    // swallowed it), so a loss-suspended session never fast-recovered — it waited
    // ~90s for the keepalive ride-through. The reducer arms here are the
    // ViewModel half of the fix: a NetworkLost holds the lease + surfaces the calm
    // band without churning; a NetworkRestored drives `scheduleAutoReconnect` even
    // from a non-Connected state and bypasses the proven-alive gate.

    @Test
    fun issue997BareNetworkLossHoldsTheLeaseWithoutChurningOrTearingDown() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()

        // Issue #1522 (H1): the band is now DEBOUNCED — a sub-second cellular
        // validation blip must NOT flap the UI. Right after the loss (before the
        // debounce elapses) the session is STILL Connected, not Reconnecting.
        assertTrue(
            "a bare loss must NOT paint the Reconnecting band immediately (it is debounced) — #1522",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals("no redial during the loss window — lease is held", 0, connector.connectCount)

        // Once the loss OUTLASTS the debounce (and the keepalive still cannot vouch —
        // default false here) the honest calm band surfaces so the user is not left on
        // a dead-but-live session, still WITHOUT a redial and WITHOUT tearing the lease.
        advanceTimeBy(NETWORK_LOSS_BAND_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(
            "a sustained bare loss surfaces the calm Reconnecting band after the debounce",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals("no redial during the loss window — lease is held", 0, connector.connectCount)

        // No churn: even after more time passes nothing redials (no ladder running).
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals(0, connector.connectCount)
    }

    @Test
    fun issue1522RapidValidationBlipsOnStableLinkNeverFlapTheBandOrRedial() = runTest(scheduler) {
        // Issue #1522 REPRODUCE-FIRST (D33/G10): the maintainer's EXACT stable-LTE
        // flap — "it connects, it disconnects, it connects, it disconnects." Cellular
        // drops NET_CAPABILITY_VALIDATED for sub-second windows constantly at full
        // signal (RAT handovers, periodic re-validation, v4↔v6 flips) while the TCP /
        // -CC socket stays perfectly alive. Each blip is a NoValidatedNetwork →
        // Validated pair SHORTER than the loss-band debounce.
        //
        // RED on base (no debounce, no keepalive check on the loss arm): each loss
        // paints the Reconnecting band SYNCHRONOUSLY in holdNetworkLost, so the
        // "still Connected right after the loss" assertion fails on the very first
        // blip and the band flaps N times.
        // GREEN with #1522: the band is debounced and the restore cancels it before
        // it can paint, so the ConnectionState NEVER leaves Connected across the whole
        // storm and reconnect_events == 0 (no teardown).
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient().withSinglePane("work", "%1") }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

        // ADVERSARIAL: pin the keepalive so it CANNOT vouch (proven-alive=false) — this
        // proves the DEBOUNCE alone holds the line, not the immediate keepalive
        // shortcut. The socket is alive (probe-dead seam OFF) so each restore rides
        // through with NO redial, isolating the band flap as the symptom (not a real
        // teardown, which the connectCount assertion also guards).
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = false

        val hook = registry.lifecycleHooksSnapshot().single()
        val blips = 8
        for (i in 0 until blips) {
            hook.onNetworkChanged(networkLoss(sequence = (i * 2 + 1).toLong()))
            runCurrent()
            // LOAD-BEARING (RED on base = Reconnecting): a sub-second validation blip
            // must NOT flap the band to Reconnecting.
            assertTrue(
                "blip #$i: a transient validation loss must NOT flap the band to Reconnecting " +
                    "(#1522); got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            // The blip clears well within the debounce window.
            advanceTimeBy(400L)
            hook.onNetworkChanged(networkRestore(sequence = (i * 2 + 2).toLong()))
            advanceUntilIdle()
            assertTrue(
                "blip #$i: the restore keeps the session Connected (no flap); " +
                    "got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        }

        // LOAD-BEARING: zero teardown across the whole stable-LTE churn — the
        // long-running-session gate's own criterion (reconnect_events == 0).
        assertEquals(
            "a stable link's validation churn must never redial (reconnect_events == 0) — #1522",
            0,
            connector.connectCount,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())

        vm.forceTransportProvenAliveForTest = null
    }

    @Test
    fun issue1522SustainedFlapAmortizesBandPaintsViaBackoffNotEverySecond() = runTest(scheduler) {
        // Issue #1522 (amortization, REPRODUCE-FIRST): the maintainer's second symptom —
        // on a flaky link the loss reconnect "reloads the window every ~1s with NO
        // amortization" because holdNetworkLost painted the band with retryDelayMs = 0
        // (instant reload). The fix gives the loss band a GRACE (debounce) that ESCALATES
        // via the connection's own auto-reconnect ladder on each actual reload, so a
        // persistently flapping link reloads progressively LESS often, not every second.
        //
        // RED on base (retryDelayMs = 0, no debounce/backoff): the 2nd sustained loss
        // paints the Reconnecting band IMMEDIATELY → the "still Connected at the base
        // grace after the 2nd loss" assertion fails (base reloads every loss).
        // GREEN: the 2nd loss must wait base + ladder[0] before it can paint — proving
        // the reload is amortized (grace + backoff), not instant.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient().withSinglePane("work", "%1") }
        // The auto-reconnect ladder IS the loss-band backoff ladder (#1522).
        vm.setAutoReconnectDelaysForTest(listOf(3_000L, 6_000L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        // Keepalive can NOT vouch (so a sustained loss paints); the socket is alive so
        // each restore rides through with no redial (isolating the reload cadence).
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = false
        val hook = registry.lifecycleHooksSnapshot().single()

        // --- Reload #1: the FIRST sustained loss paints after the BASE grace. ---
        hook.onNetworkChanged(networkLoss(sequence = 1L))
        advanceTimeBy(NETWORK_LOSS_BAND_DEBOUNCE_MS - 100)
        runCurrent()
        assertTrue(
            "1st loss must not paint before the base grace",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        advanceTimeBy(200)
        runCurrent()
        assertTrue(
            "1st sustained loss paints the band after the base grace",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        // Link flaps back up briefly (ride-through, no redial). Use runCurrent so the
        // 30s quiet-reset does NOT fire and reset the backoff before the next loss.
        hook.onNetworkChanged(networkRestore(sequence = 2L))
        runCurrent()
        assertTrue(
            "the restore rides through back to Connected (no redial)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // --- Reload #2: the SECOND loss must wait base + ladder[0] (BACKOFF). ---
        hook.onNetworkChanged(networkLoss(sequence = 3L))
        advanceTimeBy(NETWORK_LOSS_BAND_DEBOUNCE_MS + 100)
        runCurrent()
        // LOAD-BEARING (RED on base = Reconnecting instantly): after the base grace the
        // 2nd loss must STILL be Connected — the backoff extended the grace so the flaky
        // link is NOT reloading at the base cadence.
        assertTrue(
            "2nd sustained loss must be AMORTIZED — still Connected at the base grace because " +
                "the backoff (base + ladder[0]) extended it; base reloads instantly (#1522)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        // Only after the additional ladder[0] backoff does the 2nd band paint.
        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(
            "the 2nd band paints only after base + ladder[0] — proving grace + backoff",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )

        // The whole flap never tore the transport down (amortized, not a redial storm).
        advanceUntilIdle()
        assertEquals(
            "a flapping link's amortized reloads must never redial (reconnect_events == 0) — #1522",
            0,
            connector.connectCount,
        )

        vm.forceTransportProvenAliveForTest = null
    }

    @Test
    fun issue997NetworkRestoreDrivesFastReconnectFromLossSuspendedState() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        // Issue #1042: this case proves a restore CAN redial FROM the loss-suspended
        // (non-Connected) state — the #997 gap. Under #1042 the restore is now
        // liveness-first, so inject a GENUINELY-DEAD transport (keepalive not proven
        // alive by default + the bounded restore probe DEAD) so the liveness gate
        // falls through to the fresh-lease redial being asserted here.
        vm.forceLivenessProbeDeadForTest = true

        // Loss: hold + (issue #1522) after the debounce elapses — the keepalive is
        // pinned DEAD here so it cannot vouch — flip to the loss-suspended Reconnecting
        // state.
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        advanceTimeBy(NETWORK_LOSS_BAND_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(
            "loss leaves the session in the loss-suspended Reconnecting state after the debounce",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(0, connector.connectCount)

        // Restore: must drive a FAST reconnect even though status is NOT Connected
        // (the #997 gap — the Connected-only ScheduleNetworkReconnect path would
        // have Ignored this).
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "restore must redial via the auto-reconnect ladder with the network trigger",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals("exactly one fresh-lease redial on restore", 1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun issue1042NetworkRestoreRidesThroughWhenTransportProvenAlive() = runTest(scheduler) {
        // Issue #1042 (cause #1) REFINES the pre-#1042 #997 restore behaviour. The old
        // assumption — "a loss means the socket is DEAD, so always redial" — fired a
        // spurious fresh-lease reconnect on every brief cellular dip even when the TCP
        // socket survived. The restore is now LIVENESS-FIRST: when the existing
        // transport is proven alive it RIDES THROUGH with NO redial. Pin
        // proven-alive=true and confirm the restore does NOT redial and the session
        // returns to Connected. (The genuinely-dead case still redials —
        // issue997NetworkRestoreDrivesFastReconnectFromLossSuspendedState guards it.)
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        vm.forceTransportProvenAliveForTest = true
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "a restore over a PROVEN-ALIVE transport must NOT redial (rides through) — #1042",
            0,
            connector.connectCount,
        )
        assertNull(
            "a ride-through restore must not schedule a network-reconnect redial",
            vm.latestRestoreIntentSnapshot(),
        )
        assertTrue(
            "the ride-through clears the loss-hold band back to Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        vm.forceTransportProvenAliveForTest = null
    }

    @Test
    fun issue1042NetworkRestoreRidesThroughWhenBoundedProbeAnswers() = runTest(scheduler) {
        // Issue #1042 (cause #1) ARM 2 — the headline symptom path: a quiet/idle
        // cellular session whose transport keepalive has AGED OUT of its ride-through
        // window (so the proven-alive fast gate does NOT fire) but whose TCP socket is
        // still alive. The restore must then issue ONE bounded control-channel probe
        // and, when it ANSWERS, RIDE THROUGH with no redial — NOT redial. This arm is
        // distinct from arm 1 (keepalive proven alive) and arm 3 (neither answers ->
        // fresh-lease redial). Asserted via the `cause="probe_answered"` device trail
        // so it cannot be confused with the proven-alive arm.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        // Keepalive NOT proven alive (the fast gate must MISS so the bounded-probe arm
        // runs) but the bounded probe ANSWERS (healthy attached client, probe-dead seam
        // off) — the surviving-but-quiet-link case.
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = false

        val diagnostics = installRecordingDiagnosticSink()
        try {
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
            runCurrent()
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
            advanceUntilIdle()

            assertEquals(
                "a restore whose bounded probe ANSWERS must NOT redial (rides through) — #1042 arm 2",
                0,
                connector.connectCount,
            )
            assertNull(
                "a probe-answered ride-through must not schedule a network-reconnect redial",
                vm.latestRestoreIntentSnapshot(),
            )
            assertTrue(
                "the probe-answered ride-through clears the loss-hold band back to Connected",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertTrue(
                "no redial diagnostic may fire on the probe-answered arm; events=" +
                    diagnostics.events.map { it.name },
                diagnostics.eventsNamed("network_restore_reconnect_start").isEmpty() &&
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
            val rideThrough = diagnostics.eventsNamed("network_restore_ride_through")
            assertTrue(
                "expected a network_restore_ride_through (proves arm 2 fired, not a vacuous " +
                    "pass); events=${diagnostics.events.map { it.name }}",
                rideThrough.isNotEmpty(),
            )
            assertEquals(
                "the ride-through must be attributed to the bounded probe answering " +
                    "(distinguishes arm 2 from the proven-alive arm 1)",
                "probe_answered",
                rideThrough.single().fields["cause"],
            )
        } finally {
            diagnostics.close()
            vm.forceTransportProvenAliveForTest = null
        }
    }

    @Test
    fun issue1193NetworkRestoreFreshKeepaliveButDeadSocketRedialsViaProbeNotPassiveRideThrough() =
        runTest(scheduler) {
            // Issue #1193 REPRODUCE-FIRST (D33/G10): the maintainer's 2026-07-02
            // cellular spurious drop. On a WiFi→cellular restore the OLD socket is
            // silently dead (the new radio's IP/NAT invalidates the 4-tuple) but the
            // PASSIVE keepalive TIMESTAMP is still fresh (< 90s). Pre-#1193 branch 1 of
            // scheduleNetworkReconnectOnRestore rode through on that timestamp ALONE
            // (isTransportKeepAliveProvenAliveRecently — a pure clock comparison, NO
            // round-trip) → committed to the dead transport, flipped back to Live, and
            // the `-CC` reader threw ~157ms later → passive_disconnect + reconnect
            // churn. This pins BOTH the fresh keepalive (forceTransportProvenAliveForTest
            // = true, the maintainer's EXACT state) AND a genuinely dead socket
            // (forceLivenessProbeDeadForTest = true — the bounded probe won't answer).
            //
            // RED (branch 1 present): records a network_restore_ride_through
            //   cause=transport_proven_alive and does NOT redial (connectCount == 0) —
            //   the app commits to the dead transport.
            // GREEN (#1193, branch 1 deleted → all restores go through the bounded
            //   probe): the probe requires an answered round-trip, finds the socket
            //   dead, and cleanly redials — network_restore_reconnect_start fires,
            //   connectCount == 1, and there is NO network_restore_ride_through.
            TMUX_CONNECT_ATTEMPTS.set(0)
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
            )
            runCurrent()

            // The maintainer's exact cellular state: keepalive timestamp FRESH (the
            // passive check would say "proven alive") but the post-handoff socket
            // GENUINELY DEAD (the bounded probe cannot round-trip).
            vm.forceTransportProvenAliveForTest = true
            vm.forceLivenessProbeDeadForTest = true

            val diagnostics = installRecordingDiagnosticSink()
            try {
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
                runCurrent()
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
                advanceUntilIdle()

                // GREEN load-bearing #1: the restore must NOT ride through onto the
                // dead transport on a fresh-keepalive timestamp — that passive
                // ride-through is the #1193 bug.
                assertTrue(
                    "a fresh-keepalive-but-DEAD-socket restore must NOT ride through the dead " +
                        "transport on the passive timestamp (the #1193 cellular spurious drop); " +
                        "events=${diagnostics.events.map { it.name }}",
                    diagnostics.eventsNamed("network_restore_ride_through").isEmpty(),
                )
                // GREEN load-bearing #2: the bounded probe detected the dead socket and
                // cleanly redialled via the #997 fresh lease.
                assertEquals(
                    "the dead-socket restore must redial via the bounded-probe fresh lease",
                    1,
                    connector.connectCount,
                )
                assertEquals(
                    "the redial fires with the network-reconnect trigger",
                    TmuxConnectTrigger.NetworkReconnect,
                    vm.latestRestoreIntentSnapshot()?.trigger,
                )
                assertTrue(
                    "the redial records the fast restore signal (not a vacuous pass); " +
                        "events=${diagnostics.events.map { it.name }}",
                    diagnostics.eventsNamed("network_restore_reconnect_start").isNotEmpty(),
                )
            } finally {
                diagnostics.close()
                vm.forceTransportProvenAliveForTest = null
            }
        }

    @Test
    fun issue1193NetworkRestoreFreshKeepaliveAndAliveSocketStillRidesThroughViaProbe() =
        runTest(scheduler) {
            // Issue #1193 NO-REGRESSION (G2 class coverage): the genuine happy cellular
            // dip — a fresh keepalive timestamp AND a truly-alive socket. With the
            // passive fast-path deleted the restore now proves liveness with the bounded
            // ACTIVE probe; on a live socket the probe ANSWERS fast (well inside the 2s
            // budget) so the session still RIDES THROUGH with no redial — the
            // #974/#981/#1042 spurious-drop-suppression win is preserved. The
            // distinguishing assertion is cause == "probe_answered" (NOT
            // "transport_proven_alive"): even with a fresh keepalive the decision now
            // runs through the probe, never the deleted passive gate.
            TMUX_CONNECT_ATTEMPTS.set(0)
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
            )
            runCurrent()

            // Fresh keepalive timestamp AND a live socket (probe-dead seam OFF, so the
            // FakeTmuxClient's refresh-client round-trip answers).
            vm.forceTransportProvenAliveForTest = true
            vm.forceLivenessProbeDeadForTest = false

            val diagnostics = installRecordingDiagnosticSink()
            try {
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
                runCurrent()
                registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
                advanceUntilIdle()

                assertEquals(
                    "a fresh-keepalive + ALIVE-socket restore must still ride through (no redial)",
                    0,
                    connector.connectCount,
                )
                assertNull(
                    "a ride-through restore must not schedule a network-reconnect redial",
                    vm.latestRestoreIntentSnapshot(),
                )
                assertTrue(
                    "the ride-through clears the loss-hold band back to Connected",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "no redial diagnostic may fire on the alive-socket ride-through; events=" +
                        diagnostics.events.map { it.name },
                    diagnostics.eventsNamed("network_restore_reconnect_start").isEmpty() &&
                        diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )
                val rideThrough = diagnostics.eventsNamed("network_restore_ride_through")
                assertTrue(
                    "expected a network_restore_ride_through (proves the ride-through fired, " +
                        "not a vacuous pass); events=${diagnostics.events.map { it.name }}",
                    rideThrough.isNotEmpty(),
                )
                assertEquals(
                    "even with a fresh keepalive the restore now rides through via the bounded " +
                        "PROBE (cause=probe_answered), NOT the deleted passive transport_proven_alive " +
                        "fast-path — this is the #1193 behavior change",
                    "probe_answered",
                    rideThrough.single().fields["cause"],
                )
            } finally {
                diagnostics.close()
                vm.forceTransportProvenAliveForTest = null
            }
        }

    // ---- Issue #1080: Doze/app-standby wake must NOT ride through a dead socket ----
    //
    // Android deep Doze suspends the network so the keepalive cannot keep the NAT
    // mapping alive; the socket is silently dead on wake. The KEYSTONE fix is that
    // the transport-liveness oracle (RealSshSession.isTransportProvenAliveWithin-
    // KeepAliveWindow) now uses the wall-elapsed boot clock that COUNTS deep sleep,
    // so after a real Doze gap it correctly reports STALE (proven at the core-ssh
    // layer by RealSshSessionDozeClockTest). These VM tests pin the DOWNSTREAM
    // consequence: when the oracle reports stale on a post-Doze restore the
    // #1042/#1078 restore path must redial (issue ONE bounded probe, then a fresh
    // lease), NOT ride through the dead socket; and when the oracle is still alive
    // (a short background, not deep sleep) the restore must still ride through.

    @Test
    fun issue1080PostDozeWakeStaleTransportRedialsInsteadOfRidingThrough() = runTest(scheduler) {
        // Post-deep-Doze wake: the NAT mapping died during sleep, and because the
        // oracle now counts deep sleep (the #1080 boot-clock fix) it correctly
        // reports STALE — model that with transportProvenAlive=false (oracle aged
        // out) AND a DEAD bounded probe (the dead socket cannot answer). The restore
        // path MUST fall through to a fresh-lease redial, NOT ride through. Without
        // the #1080 clock fix the real on-device oracle would have falsely reported
        // ALIVE here (frozen monotonic clock under-counting the sleep) and the
        // restore would have ridden through the dead socket — the bug this guards.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        // The live session whose keepalive aged out across the Doze gap (oracle stale).
        val session = FakeSshSession().apply { transportProvenAlive = false }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        runCurrent()
        // The dead post-Doze socket cannot answer the bounded restore probe either.
        vm.forceLivenessProbeDeadForTest = true
        assertFalse(
            "precondition: a post-Doze stale transport must NOT report proven-alive",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // The Doze interval: network drops while backgrounded, then restores on wake.
        // Issue #1522: the band is debounced — the keepalive is dead here so it cannot
        // vouch, so once the loss OUTLASTS the debounce the loss-suspended Reconnecting
        // state surfaces.
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        advanceTimeBy(NETWORK_LOSS_BAND_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(
            "the loss leaves the session in the loss-suspended Reconnecting state",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "a post-Doze restore over a STALE/dead transport (oracle aged out + probe dead) " +
                "MUST redial — it must NOT ride through the dead socket (#1080)",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals("exactly one fresh-lease redial on the post-Doze restore", 1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)

        vm.forceLivenessProbeDeadForTest = false
    }

    @Test
    fun issue1080ShortBackgroundWakeAliveTransportStillRidesThrough() = runTest(scheduler) {
        // Class-coverage (G2): the #1080 fix must NOT turn every background wake into
        // a reconnect. A short background (screen-off, app-switch — NOT deep sleep)
        // leaves the keepalive proving the link alive; the oracle still reports ALIVE
        // and the restore must RIDE THROUGH with zero redials, exactly as #1042.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val session = FakeSshSession().apply { transportProvenAlive = true }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        runCurrent()
        assertTrue(
            "precondition: a short-background link is still proven-alive (no deep sleep)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "a wake over a PROVEN-ALIVE transport must ride through with no redial (#1080 must " +
                "not over-reconnect a healthy short-background link)",
            0,
            connector.connectCount,
        )
        assertNull(
            "a ride-through wake must not schedule a network-reconnect redial",
            vm.latestRestoreIntentSnapshot(),
        )
        assertTrue(
            "the ride-through clears the loss-hold band back to Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun issue1078HandoffProbesAndRedialsWhenSocketDeadButPassivelyProvenAlive() = runTest(scheduler) {
        // Issue #1078 reproduce-first (RED on base): the headline residual cellular
        // stall. A REAL validated WIFI→CELLULAR handoff arrives while the transport is
        // only PASSIVELY proven alive — the keepalive's last-inbound-byte age is still
        // under the ~90s budget (forceTransportProvenAliveForTest=true → the reducer
        // returns SuppressNetworkTransportProvenAlive) — but the old socket is GENUINELY
        // DEAD post-handoff (an active probe fails, forceLivenessProbeDeadForTest=true).
        //
        // BASE BEHAVIOUR (the bug): the handoff suppress arm rode through on the passive
        // timestamp ALONE with no active probe, so the session showed Live-but-FROZEN for
        // up to ~90s until the keepalive budget finally tripped — no redial was ever
        // scheduled. This test asserts a redial DOES fire, so it FAILS red on base.
        //
        // FIXED BEHAVIOUR (#1078, mirroring the #1042 restore arm): the suppress arm now
        // issues ONE bounded active probe; it does not answer (dead socket), so it redials
        // promptly via the SAME dead-link handoff authority (scheduleNetworkReconnect)
        // within the probe budget instead of waiting out the ~90s keepalive.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        assertTrue(
            "precondition: a healthy live session before the handoff",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Passively proven alive (recent inbound byte) but the active probe is DEAD:
        // exactly the dead-socket-after-handoff state the keepalive timestamp masks.
        vm.forceTransportProvenAliveForTest = true
        vm.forceLivenessProbeDeadForTest = true

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "wifi-cellular-handoff-dead-socket-recent-inbound",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "a handoff whose bounded probe does NOT answer must redial promptly (not freeze " +
                "Live for ~90s) — #1078",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals(
            "exactly one fresh-lease redial after the dead-socket handoff probe fails",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)

        vm.forceTransportProvenAliveForTest = null
        vm.forceLivenessProbeDeadForTest = false
    }

    @Test
    fun issue1078HandoffRidesThroughWhenProbeAnswersOnAliveSocket() = runTest(scheduler) {
        // Issue #1078 class-coverage (D32 G2): the alive-socket handoff case. Same
        // WIFI→CELLULAR validated handoff while passively proven alive
        // (forceTransportProvenAliveForTest=true), but here the bounded active probe
        // ANSWERS (forceLivenessProbeDeadForTest=false → the FakeTmuxClient refresh-client
        // round-trips). The genuine ride-through win (#981/#974/#1058) MUST be preserved:
        // NO spurious redial, the session stays Connected, and the ride-through is
        // attributed to the probe (probeConfirmed=true) so the pass is not vacuous.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        vm.forceTransportProvenAliveForTest = true
        vm.forceLivenessProbeDeadForTest = false

        val diagnostics = installRecordingDiagnosticSink()
        try {
            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("wifi"),
                    current = TerminalNetworkSnapshot.Validated("cell"),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                    reason = "wifi-cellular-handoff-alive-socket",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "a handoff whose bounded probe ANSWERS must NOT redial (rides through) — #1078",
                0,
                connector.connectCount,
            )
            assertNull(
                "a probe-confirmed handoff ride-through must not schedule a network-reconnect redial",
                vm.latestRestoreIntentSnapshot(),
            )
            assertTrue(
                "the ride-through keeps the session Connected (no Reconnecting overlay)",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertTrue(
                "no redial diagnostic may fire on the probe-confirmed handoff ride-through; events=" +
                    diagnostics.events.map { it.name },
                diagnostics.eventsNamed("network_reconnect_start").isEmpty() &&
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
            val rideThrough = diagnostics.eventsNamed("network_reconnect_skip").filter {
                it.fields["cause"] == "transport_proven_alive"
            }
            assertTrue(
                "expected a transport_proven_alive network_reconnect_skip (proves the ride-through " +
                    "arm fired, not a vacuous pass); events=${diagnostics.events.map { it.name }}",
                rideThrough.isNotEmpty(),
            )
            assertEquals(
                "the ride-through must be attributed to the bounded ACTIVE probe answering, not " +
                    "the passive keepalive timestamp alone (distinguishes #1078 from a passive suppress)",
                true,
                rideThrough.single().fields["probeConfirmed"],
            )
        } finally {
            diagnostics.close()
            vm.forceTransportProvenAliveForTest = null
        }
    }

    @Test
    fun networkReconnectAndPassiveDisconnectAreCoalescedIntoOneAttempt() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val oldClient = FakeTmuxClient()
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
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        runCurrent()
        oldClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "passive EOF during a scheduled network reconnect must not replace it with a second loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals(
            "only the already scheduled network reconnect should open a transport",
            1,
            connector.connectCount,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun networkChangeLifecycleHookProactivelyReattachesTmuxSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
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

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertTrue("old tmux control client must be detached during reconnect", oldClient.detachCleanlyCalled)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }


    private fun networkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            previous as? TerminalNetworkSnapshot.Validated,
        reason: String = "validated-default-network-changed",
        sequence: Long = 1L,
        deferredFromBackground: Boolean = false,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            deferredFromBackground = deferredFromBackground,
        )

    // Issue #997: a bare network LOSS change (Validated -> NoValidatedNetwork).
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

    // Issue #997: a network RESTORE change (NoValidatedNetwork -> Validated).
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

    private fun issue576BackpressureOutputChunks(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        chunks += "\u001b[31mISSUE576-VM-START\u001b[0m\r\n".toByteArray(Charsets.UTF_8)
        chunks += ("VM-LONG-LINE-" + "B".repeat(10_000) + "\r\n").toByteArray(Charsets.UTF_8)
        chunks += "\u001b[".toByteArray(Charsets.UTF_8)
        chunks += "?25l".toByteArray(Charsets.UTF_8)
        repeat(180) { index ->
            chunks += buildString {
                append("\u001b[38;5;")
                append(index % 256)
                append('m')
                append("vm-frag-")
                append(index.toString().padStart(3, '0'))
                append(' ')
                append(('A'.code + (index % 26)).toChar().toString().repeat(700))
                if (index % 7 == 0) append("\u001b[2K")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
        }
        chunks += "\u001b[?25h\u001b[0m\r\nISSUE576-VM-DONE\r\n".toByteArray(Charsets.UTF_8)
        return chunks
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

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        @Volatile
        var transportProvenAlive: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

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
