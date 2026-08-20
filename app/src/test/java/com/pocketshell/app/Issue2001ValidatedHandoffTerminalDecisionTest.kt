package com.pocketshell.app

import com.pocketshell.app.connectivity.NetworkHandoffTerminalDecision
import com.pocketshell.app.connectivity.NetworkHandoffTerminalDecision.NamedDiagnosticFields
import com.pocketshell.app.connectivity.NetworkHandoffTerminalDecision.Observation
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2001 — the Nightly proven-alive handoff journey timed out waiting for
 * `network_reconnect_skip` after the App lifecycle gate had already suppressed
 * the WIFI-A → CELL-X change (`change_suppressed_within_grace`) and the
 * port-forward path had independently recorded `network_ride_through`.
 *
 * An absent VM skip must never look like a pass or a hang (G6).
 */
class Issue2001ValidatedHandoffTerminalDecisionTest {

    @Test
    fun issue2001NightlySuppressShapeIsNotAProvenAliveTerminalDecision() {
        // Exact Nightly diagnostic shape: validated identity change reached the
        // App gate, port-forward rode through, VM never emitted skip.
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    "validated_default_changed",
                    "reason" to REASON,
                    "previousNetworkHandle" to "wifi-A",
                    "currentNetworkHandle" to "cell-X",
                ),
                event(
                    NetworkHandoffTerminalDecision.APP_GATE_SUPPRESS_EVENT,
                    "reason" to REASON,
                    "gateDecision" to "suppress",
                    "gateReason" to "post_resume_within_grace_live_runtime",
                ),
                event(
                    NetworkHandoffTerminalDecision.PORT_FORWARD_RIDE_THROUGH_EVENT,
                    "reason" to REASON,
                    "cause" to "transport_proven_alive",
                ),
            ),
            reason = REASON,
        )
        assertTrue(
            "Nightly swallow shape must be a completed App-gate swallow, not " +
                "Pending (old 7s hang) and not ProvenAliveSkip (vacuous #981 pass). " +
                "observed=$observation",
            observation is Observation.AppGateSwallowed,
        )
        val swallowed = observation as Observation.AppGateSwallowed
        assertEquals(REASON, swallowed.event.fields["reason"])
        assertEquals("suppress", swallowed.event.fields["gateDecision"])
        assertEquals(
            "post_resume_within_grace_live_runtime",
            swallowed.event.fields["gateReason"],
        )
    }

    @Test
    fun portForwardRideThroughAloneIsNotTheTerminalHandoffDecision() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    NetworkHandoffTerminalDecision.PORT_FORWARD_RIDE_THROUGH_EVENT,
                    "reason" to REASON,
                    "cause" to "transport_proven_alive",
                ),
            ),
            reason = REASON,
        )
        assertEquals(
            "port-forward network_ride_through is a sibling path, not the VM " +
                "terminal decision — treating it as skip would green Nightly vacuously",
            Observation.Pending,
            observation,
        )
    }

    @Test
    fun absentEventsStayPendingAndAreNotAPass() {
        assertEquals(
            Observation.Pending,
            NetworkHandoffTerminalDecision.observe(emptyList(), REASON),
        )
    }

    @Test
    fun provenAliveSkipWithMatchingReasonIsTheTerminalDecision() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    NetworkHandoffTerminalDecision.SKIP_EVENT,
                    "reason" to REASON,
                    "source" to "network_observer",
                    "trigger" to "network-reconnect",
                    "cause" to "transport_proven_alive",
                    "classification" to "network_handoff_transport_alive",
                    "reconnect" to false,
                    "probeConfirmed" to true,
                    "realValidatedIdentityChange" to true,
                ),
            ),
            reason = REASON,
        )
        assertTrue(observation is Observation.ProvenAliveSkip)
        val skip = (observation as Observation.ProvenAliveSkip).event
        assertEquals("transport_proven_alive", skip.fields["cause"])
        assertEquals(true, skip.fields["probeConfirmed"])
        assertEquals(true, skip.fields["realValidatedIdentityChange"])
        assertEquals(false, skip.fields["reconnect"])
    }

    @Test
    fun provenAliveSkipWinsOverAppGateSuppressForTheSameReason() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    NetworkHandoffTerminalDecision.APP_GATE_SUPPRESS_EVENT,
                    "reason" to REASON,
                    "gateDecision" to "suppress",
                ),
                event(
                    NetworkHandoffTerminalDecision.SKIP_EVENT,
                    "reason" to REASON,
                    "cause" to "transport_proven_alive",
                    "probeConfirmed" to true,
                ),
            ),
            reason = REASON,
        )
        assertTrue(
            "if the VM did emit skip, that is the #981 terminal decision even if " +
                "the App gate also recorded a suppress",
            observation is Observation.ProvenAliveSkip,
        )
    }

    @Test
    fun skipForADifferentReasonStaysPending() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    NetworkHandoffTerminalDecision.SKIP_EVENT,
                    "reason" to "other-handoff",
                    "cause" to "transport_proven_alive",
                ),
            ),
            reason = REASON,
        )
        assertEquals(Observation.Pending, observation)
    }

    @Test
    fun reconnectStartIsObservedImmediately() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event("network_reconnect_start", "reason" to REASON),
            ),
            reason = REASON,
        )
        assertTrue(observation is Observation.ReconnectStarted)
        assertEquals(
            "network_reconnect_start",
            (observation as Observation.ReconnectStarted).event.name,
        )
    }

    @Test
    fun appGateSuppressForADifferentReasonDoesNotSwallowThisHandoff() {
        val observation = NetworkHandoffTerminalDecision.observe(
            events = listOf(
                event(
                    NetworkHandoffTerminalDecision.APP_GATE_SUPPRESS_EVENT,
                    "reason" to "other-handoff",
                    "gateDecision" to "suppress",
                ),
            ),
            reason = REASON,
        )
        assertEquals(Observation.Pending, observation)
    }

    @Test
    fun issue2001ExpiringPostResumeWindowLetsACurrentValidatedHandoffDispatch() {
        var now = 10_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val swallowed = terminalNetworkChange(
            reason = "issue2001-swallowed-wifi-cellular",
        )
        val current = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("cell"),
            current = TerminalNetworkSnapshot.Validated("wifi-2"),
            previousValidated = TerminalNetworkSnapshot.Validated("cell"),
            reason = REASON,
            sequence = 2L,
        )

        gate.onBackground()
        gate.onForegroundResumeStarted()
        gate.onForegroundResumeFinished(
            resumedWithinGrace = true,
            hasLiveTerminalRuntime = true,
        )

        now += 1_000L
        val beforeExpire = gate.onNetworkChange(swallowed)
        assertTrue(
            "Nightly leftover: a current WIFI→CELLULAR flip inside the post-resume " +
                "window is suppressed so the VM never emits network_reconnect_skip",
            beforeExpire is TerminalNetworkDecision.Suppress,
        )
        assertEquals(
            "post_resume_within_grace_live_runtime",
            beforeExpire.gateDiagnostics.reason,
        )

        gate.expirePostResumeNetworkSuppressionForTest()
        val afterExpire = gate.onNetworkChange(current)
        assertTrue(
            "expiring the leftover post-resume window must dispatch a CURRENT " +
                "validated handoff (mutation: no-op expire leaves this Suppress). " +
                "observed=$afterExpire",
            afterExpire is TerminalNetworkDecision.Dispatch,
        )
        assertEquals("foreground_active", afterExpire.gateDiagnostics.reason)
        assertEquals(REASON, (afterExpire as TerminalNetworkDecision.Dispatch).change.reason)
    }

    private fun event(name: String, vararg fields: Pair<String, Any?>): NamedDiagnosticFields =
        NamedDiagnosticFields(name = name, fields = fields.toMap())

    private fun terminalNetworkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            TerminalNetworkSnapshot.Validated("wifi"),
        reason: String,
        sequence: Long = 1L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
        )

    private companion object {
        const val REASON: String = "issue981-transient-wifi-cellular-flip"
    }
}
