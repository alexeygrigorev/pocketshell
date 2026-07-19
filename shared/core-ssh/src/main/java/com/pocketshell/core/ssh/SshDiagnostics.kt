package com.pocketshell.core.ssh

/**
 * Issue #1683 — the minimal transport-level diagnostics seam for core-ssh.
 *
 * core-ssh previously emitted NOTHING to the diagnostics timeline (the #1642
 * finding, restated by the #1680 Track A audit): keepalive misses, the
 * ride-through window state, and every transport-liveness input were recorded
 * only as `Level.FINE` logger lines that never reach the exported connection
 * trace. So a transport-keepalive-driven death showed up in the log as a bare
 * VERDICT (`KeepaliveDead` close) with none of the INPUTS (which tick missed,
 * how many consecutive) that would let us tell an over-eager false-dead from a
 * real silent-peer death.
 *
 * Like [com.pocketshell.core.connection.ConnectionDiagnostics], this is a
 * fire-and-forget sink the app installs once at startup, forwarding into the
 * real recorder under the `connection` category (so keepalive inputs are
 * mirrored to the host alongside the verdicts). The default is a no-op, so the
 * pure [TransportKeepAlive] virtual-clock tests keep running with zero wiring
 * and the loop cadence is unchanged — recording is never awaited.
 */
fun interface SshDiagnosticsSink {
    fun record(event: String, fields: Map<String, Any?>)
}

object SshDiagnostics {
    private val noop = SshDiagnosticsSink { _, _ -> }

    @Volatile
    private var sink: SshDiagnosticsSink = noop

    fun install(installed: SshDiagnosticsSink) {
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
