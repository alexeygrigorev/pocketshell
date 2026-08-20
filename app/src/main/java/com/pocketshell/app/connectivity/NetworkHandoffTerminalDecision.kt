package com.pocketshell.app.connectivity

import androidx.annotation.VisibleForTesting

/**
 * Issue #2001 — classify the completed terminal decision for a proven-alive
 * validated handoff.
 *
 * Nightly timed out waiting for `network_reconnect_skip` after a real
 * WIFI-A → CELL-X identity change. Diagnostics already had:
 *   - `change_suppressed_within_grace` / gate `suppress` (App lifecycle gate)
 *   - port-forward `network_ride_through` cause=`transport_proven_alive`
 *   - no VM `network_reconnect_skip`
 *
 * Those App-gate / port-forward events are completed decisions on *their*
 * paths, but they are NOT the #981 proven-alive terminal decision: the VM
 * never saw the change, so the answered probe never ran. Treating either as
 * a pass (or leaving them as "still waiting") makes an absent VM skip look
 * like a hang or a green — the G6 shape this helper exists to forbid.
 */
@VisibleForTesting
internal object NetworkHandoffTerminalDecision {

    const val SKIP_EVENT: String = "network_reconnect_skip"
    const val APP_GATE_SUPPRESS_EVENT: String = "change_suppressed_within_grace"
    const val PORT_FORWARD_RIDE_THROUGH_EVENT: String = "network_ride_through"

    val FORBIDDEN_RECONNECT_EVENTS: Set<String> = setOf(
        "reconnect_tapped",
        "reconnect_start",
        "network_reconnect_start",
        "foreground_reattach",
        "foreground_runtime_probe_failed",
    )

    data class NamedDiagnosticFields(
        val name: String,
        val fields: Map<String, Any?>,
    )

    sealed class Observation {
        data class ProvenAliveSkip(val event: NamedDiagnosticFields) : Observation()
        data class AppGateSwallowed(val event: NamedDiagnosticFields) : Observation()
        data class ReconnectStarted(val event: NamedDiagnosticFields) : Observation()
        data object Pending : Observation()
    }

    fun observe(
        events: List<NamedDiagnosticFields>,
        reason: String,
    ): Observation {
        val reconnect = events.firstOrNull { it.name in FORBIDDEN_RECONNECT_EVENTS }
        if (reconnect != null) return Observation.ReconnectStarted(reconnect)

        val skips = events.filter { event ->
            event.name == SKIP_EVENT && event.fields["reason"] == reason
        }
        check(skips.size <= 1) {
            "expected at most one terminal handoff decision for reason=$reason; matches=$skips"
        }
        skips.singleOrNull()?.let { return Observation.ProvenAliveSkip(it) }

        val swallowed = events.filter { event ->
            event.name == APP_GATE_SUPPRESS_EVENT && event.fields["reason"] == reason
        }
        check(swallowed.size <= 1) {
            "expected at most one App-gate suppress for reason=$reason; matches=$swallowed"
        }
        swallowed.singleOrNull()?.let { return Observation.AppGateSwallowed(it) }

        return Observation.Pending
    }
}
