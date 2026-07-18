package com.pocketshell.core.connection

/**
 * Issue #1683 — the minimal diagnostics seam for the connection core so the
 * dead-detection INPUTS (per-tick liveness misses + latency, and every
 * `transportProvenAliveRecently` consultation with its result) land on the SAME
 * correlated diagnostics timeline as the connection VERDICTS the app already
 * records.
 *
 * ## Why this exists
 *
 * The connection log has recorded VERDICTS for a while
 * (`liveness_probe_silent_drop`, `passive_disconnect classification=...`) but not
 * the INPUTS behind them — so an over-eager false-dead is indistinguishable from
 * a real drop in the exported trace. [LivenessProbe] is where the miss run and
 * the keepalive-coordination consultation happen, but core-connection is a pure
 * module with no app / android dependency, so it cannot reach the app's
 * `DiagnosticEvents` directly.
 *
 * This is the inversion: a fire-and-forget sink the app installs once at startup
 * (forwarding into the real recorder under the `connection` category, so these
 * inputs are mirrored to the host alongside the verdicts). The default is a
 * no-op, so the pure [LivenessProbe] virtual-clock tests keep running with zero
 * wiring and the loop's determinism is unchanged — recording is fire-and-forget,
 * never awaited, so it never perturbs the probe cadence.
 */
fun interface ConnectionDiagnosticsSink {
    fun record(event: String, fields: Map<String, Any?>)
}

object ConnectionDiagnostics {
    private val noop = ConnectionDiagnosticsSink { _, _ -> }

    @Volatile
    private var sink: ConnectionDiagnosticsSink = noop

    fun install(installed: ConnectionDiagnosticsSink) {
        sink = installed
    }

    /** Test-only reset back to the no-op sink. */
    fun reset() {
        sink = noop
    }

    fun record(event: String, vararg fields: Pair<String, Any?>) {
        sink.record(event, fields.toMap())
    }
}
