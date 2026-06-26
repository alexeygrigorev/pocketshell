package com.pocketshell.core.ssh

/**
 * Issue #970 — test-only override for the always-on transport keepalive's timing
 * knobs ([TransportKeepAlive.DEFAULT_INTERVAL_MS] / [TransportKeepAlive.DEFAULT_COUNT_MAX]),
 * the core-ssh analogue of `LivenessProbeTestOverride`.
 *
 * The realistic-wifi stability gate (#970, the durable proof for #964) holds a
 * jittery-but-LIVE link LONGER than the slowest detection budget. The transport
 * keepalive's production ride-through window
 * ([TransportKeepAlive.DEFAULT_INTERVAL_MS] × [TransportKeepAlive.DEFAULT_COUNT_MAX]
 * = 30s × 3 = 90s) is far longer than a deterministic CI test can afford to keep
 * the keepalive's inbound-activity timestamp fresh by relying on real
 * `keepalive@openssh.com` replies. This seam lets a connected proof SHORTEN the
 * keepalive interval so a real reply lands every couple of seconds — keeping the
 * `lastInboundActivityNanos` timestamp (and therefore
 * [RealSshSession.isTransportProvenAliveWithinKeepAliveWindow], the signal the
 * app-level `LivenessProbe` defers to in #964) demonstrably fresh across a long
 * hold — WITHOUT weakening any assertion or self-skipping. Production keeps the
 * 30s / 3 defaults.
 *
 * The override is read once when a [RealSshSession] constructs its keepalive, so a
 * proof sets it BEFORE the session connects. It is a process-global, like the
 * other test-override seams in this codebase; it must always be [clear]ed in the
 * test's teardown so it never leaks into a sibling test on the shared AVD.
 */
public object KeepAliveTestOverride {
    @Volatile
    private var intervalMsOverride: Long? = null

    @Volatile
    private var countMaxOverride: Int? = null

    /**
     * Shorten (or restore) the keepalive timing. A `null` for either knob keeps
     * the production default for that knob. The override is read at keepalive
     * construction time (each new [RealSshSession]).
     */
    public fun setForTest(intervalMs: Long?, countMax: Int?) {
        require(intervalMs == null || intervalMs > 0) { "intervalMs must be > 0" }
        require(countMax == null || countMax >= 1) { "countMax must be >= 1" }
        intervalMsOverride = intervalMs
        countMaxOverride = countMax
    }

    /** Reset to the production keepalive defaults. */
    public fun clear() {
        setForTest(null, null)
    }

    internal fun intervalMs(): Long =
        intervalMsOverride ?: TransportKeepAlive.DEFAULT_INTERVAL_MS

    internal fun countMax(): Int =
        countMaxOverride ?: TransportKeepAlive.DEFAULT_COUNT_MAX
}
