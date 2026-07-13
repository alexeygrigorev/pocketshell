package com.pocketshell.app.tmux.connection

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeEffectsTest {

    @Test
    fun selector_inactiveApp_ignoresBeforeAnyOtherPredicate() {
        assertEquals(
            NetworkChangeArm.Ignore,
            selectNetworkChangeArm(
                change = handoffChange(),
                appActive = false,
                hasTarget = true,
                hasClientOrSession = true,
                autoReconnectActive = false,
                inlineConnected = true,
                lifecycleCoalesces = { error("must not inspect lifecycle coalesce") },
                transportKeepAliveProvenAlive = { error("must not inspect transport liveness") },
            ),
        )
    }

    @Test
    fun selector_networkLost_holdsWhenLeaseCanBeHeldAndKeepAliveCannotVouch() {
        // Issue #1522 (G6): this is the ONLY case where a bare loss still routes to the
        // band — the keepalive CANNOT vouch (default { false }), i.e. a genuine
        // sustained loss. Pre-#1522 this test asserted HoldNetworkLost for EVERY bare
        // loss regardless of keepalive, which encoded the flap bug (a transient blip on
        // a live socket painted the band). That unconditional expectation is now split:
        // keepalive-alive blip → SuppressNetworkLostTransportProvenAlive (no band; see
        // selector_networkLost_suppressesBandWhenTransportKeepAliveProvenAlive), and
        // ONLY the keepalive-unproven loss below still holds (then the VM debounces it).
        assertEquals(
            NetworkChangeArm.HoldNetworkLost,
            baseSelect(change = lostChange(), inlineConnected = false),
        )
    }

    @Test
    fun selector_networkLost_ignoresWhenReconnectAlreadyOwnsUi() {
        assertEquals(
            NetworkChangeArm.Ignore,
            baseSelect(change = lostChange(), autoReconnectActive = true),
        )
    }

    @Test
    fun selector_networkLost_suppressesBandWhenTransportKeepAliveProvenAlive() {
        // Issue #1522 (H1): a bare validation blip on a socket the keepalive still
        // vouches for is NOT a real transport death — mirror the ValidatedIdentityChange
        // arm and suppress the band (no flap), keeping the session Live.
        assertEquals(
            NetworkChangeArm.SuppressNetworkLostTransportProvenAlive,
            baseSelect(change = lostChange(), transportKeepAliveProvenAlive = { true }),
        )
    }

    @Test
    fun selector_networkLost_keepAliveGateIsCheckedAfterTargetAndReconnectGuards() {
        // Issue #1522: the keepalive suppress must not fire when there is no target /
        // client / when a reconnect already owns the UI — those Ignore paths win first.
        assertEquals(
            NetworkChangeArm.Ignore,
            baseSelect(change = lostChange(), hasTarget = false, transportKeepAliveProvenAlive = { true }),
        )
        assertEquals(
            NetworkChangeArm.Ignore,
            baseSelect(
                change = lostChange(),
                hasClientOrSession = false,
                transportKeepAliveProvenAlive = { true },
            ),
        )
        assertEquals(
            NetworkChangeArm.Ignore,
            baseSelect(
                change = lostChange(),
                autoReconnectActive = true,
                transportKeepAliveProvenAlive = { true },
            ),
        )
    }

    @Test
    fun selector_networkRestored_recoversEvenWhenInlineStatusIsNotConnected() {
        assertEquals(
            NetworkChangeArm.ScheduleNetworkReconnectOnRestore,
            baseSelect(change = restoredChange(), inlineConnected = false),
        )
    }

    @Test
    fun selector_validatedHandoff_requiresConnectedStateAndTargetAndLease() {
        assertEquals(NetworkChangeArm.Ignore, baseSelect(inlineConnected = false))
        assertEquals(NetworkChangeArm.Ignore, baseSelect(hasTarget = false))
        assertEquals(NetworkChangeArm.Ignore, baseSelect(hasClientOrSession = false))
    }

    @Test
    fun selector_validatedHandoff_sameIdentity_suppressesAsNotValidated() {
        assertEquals(
            NetworkChangeArm.SuppressNetworkNotValidated,
            baseSelect(change = sameIdentityChange()),
        )
    }

    @Test
    fun selector_validatedHandoff_missingPreviousValidated_suppressesAsNotValidated() {
        assertEquals(
            NetworkChangeArm.SuppressNetworkNotValidated,
            baseSelect(change = handoffChange(previousValidated = null)),
        )
    }

    @Test
    fun selector_validatedHandoff_lifecycleCoalesceWinsBeforeTransportProbe() {
        assertEquals(
            NetworkChangeArm.SuppressNetworkCoalesced,
            baseSelect(
                lifecycleCoalesces = { true },
                transportKeepAliveProvenAlive = { error("transport liveness is lower precedence") },
            ),
        )
    }

    @Test
    fun selector_validatedHandoff_provenAliveTransportSuppressesReconnect() {
        assertEquals(
            NetworkChangeArm.SuppressNetworkTransportProvenAlive,
            baseSelect(transportKeepAliveProvenAlive = { true }),
        )
    }

    @Test
    fun selector_validatedHandoff_realDeadTransportSchedulesReconnect() {
        assertEquals(
            NetworkChangeArm.ScheduleNetworkReconnect,
            baseSelect(transportKeepAliveProvenAlive = { false }),
        )
    }

    @Test
    fun controllerHandoffReporter_usesSameRealHandoffAndLivenessGate() {
        assertTrue(
            shouldReportValidatedHandoffToController(
                change = handoffChange(),
                transportKeepAliveProvenAlive = { false },
            ),
        )
        assertFalse(
            shouldReportValidatedHandoffToController(
                change = handoffChange(),
                transportKeepAliveProvenAlive = { true },
            ),
        )
        assertFalse(
            shouldReportValidatedHandoffToController(
                change = sameIdentityChange(),
                transportKeepAliveProvenAlive = { error("same identity should not probe liveness") },
            ),
        )
        assertFalse(
            shouldReportValidatedHandoffToController(
                change = lostChange(),
                transportKeepAliveProvenAlive = { error("loss is not a validated handoff") },
            ),
        )
    }

    @Test
    fun dispatcher_firesOnlySelectedArm() {
        val recorder = Recorder()
        val arm = effects(recorder, transportKeepAliveProvenAlive = { true })
            .dispatch(handoffChange())

        assertEquals(NetworkChangeArm.SuppressNetworkTransportProvenAlive, arm)
        assertEquals(
            Recorder(transportAlive = 1),
            recorder,
        )
    }

    @Test
    fun dispatcher_reReadsLivePredicatesEachCall() {
        val recorder = Recorder()
        var lifecycleCoalesces = true
        val effects = effects(
            recorder = recorder,
            lifecycleCoalesces = { lifecycleCoalesces },
            transportKeepAliveProvenAlive = { false },
        )

        assertEquals(NetworkChangeArm.SuppressNetworkCoalesced, effects.dispatch(handoffChange()))
        lifecycleCoalesces = false
        assertEquals(NetworkChangeArm.ScheduleNetworkReconnect, effects.dispatch(handoffChange()))
        assertEquals(
            Recorder(coalesced = 1, reconnect = 1),
            recorder,
        )
    }

    @Test
    fun dispatcher_networkLostProvenAlive_firesSuppressLostArmNotHold() {
        val recorder = Recorder()
        val arm = effects(recorder, transportKeepAliveProvenAlive = { true })
            .dispatch(lostChange())

        assertEquals(NetworkChangeArm.SuppressNetworkLostTransportProvenAlive, arm)
        assertEquals(Recorder(lostTransportAlive = 1), recorder)
    }

    private data class Recorder(
        var notValidated: Int = 0,
        var coalesced: Int = 0,
        var transportAlive: Int = 0,
        var lostTransportAlive: Int = 0,
        var reconnect: Int = 0,
        var lost: Int = 0,
        var restored: Int = 0,
    )

    private fun effects(
        recorder: Recorder,
        appActive: () -> Boolean = { true },
        hasTarget: () -> Boolean = { true },
        hasClientOrSession: () -> Boolean = { true },
        autoReconnectActive: () -> Boolean = { false },
        inlineConnected: () -> Boolean = { true },
        lifecycleCoalesces: () -> Boolean = { false },
        transportKeepAliveProvenAlive: () -> Boolean = { false },
    ): NetworkChangeEffects =
        NetworkChangeEffects(
            appActive = appActive,
            hasTarget = hasTarget,
            hasClientOrSession = hasClientOrSession,
            autoReconnectActive = autoReconnectActive,
            inlineConnected = inlineConnected,
            lifecycleCoalesces = lifecycleCoalesces,
            transportKeepAliveProvenAlive = transportKeepAliveProvenAlive,
            suppressNetworkNotValidated = { recorder.notValidated += 1 },
            suppressNetworkCoalesced = { recorder.coalesced += 1 },
            suppressNetworkTransportProvenAlive = { recorder.transportAlive += 1 },
            suppressNetworkLostTransportProvenAlive = { recorder.lostTransportAlive += 1 },
            scheduleNetworkReconnect = { recorder.reconnect += 1 },
            holdNetworkLost = { recorder.lost += 1 },
            scheduleNetworkReconnectOnRestore = { recorder.restored += 1 },
        )

    private fun baseSelect(
        change: TerminalNetworkChange = handoffChange(),
        appActive: Boolean = true,
        hasTarget: Boolean = true,
        hasClientOrSession: Boolean = true,
        autoReconnectActive: Boolean = false,
        inlineConnected: Boolean = true,
        lifecycleCoalesces: () -> Boolean = { false },
        transportKeepAliveProvenAlive: () -> Boolean = { false },
    ): NetworkChangeArm =
        selectNetworkChangeArm(
            change = change,
            appActive = appActive,
            hasTarget = hasTarget,
            hasClientOrSession = hasClientOrSession,
            autoReconnectActive = autoReconnectActive,
            inlineConnected = inlineConnected,
            lifecycleCoalesces = lifecycleCoalesces,
            transportKeepAliveProvenAlive = transportKeepAliveProvenAlive,
        )

    private fun handoffChange(
        previous: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? = previous,
        kind: TerminalNetworkChangeKind = TerminalNetworkChangeKind.ValidatedIdentityChange,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = "test",
            sequence = 1,
            kind = kind,
        )

    private fun sameIdentityChange(): TerminalNetworkChange =
        handoffChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("wifi"),
        )

    private fun lostChange(): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.NoValidatedNetwork,
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "lost",
            sequence = 2,
            kind = TerminalNetworkChangeKind.NetworkLost,
        )

    private fun restoredChange(): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = TerminalNetworkSnapshot.Validated("wifi"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "restored",
            sequence = 3,
            kind = TerminalNetworkChangeKind.NetworkRestored,
        )
}
