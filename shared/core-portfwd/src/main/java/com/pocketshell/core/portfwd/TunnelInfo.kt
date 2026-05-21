package com.pocketshell.core.portfwd

/**
 * Snapshot of one active (or previously active) port forward, as observed by
 * [AutoForwarder].
 *
 * Emitted via [AutoForwarder.flowOfTunnels]; consumed by the UI to render the
 * port-forward table. Pure data — no live references to the underlying
 * channel, so safely shared across coroutine contexts.
 */
public data class TunnelInfo(
    /** Remote port the forward targets. */
    public val remotePort: Int,
    /** Loopback port on the device that the user connects to. */
    public val localPort: Int,
    /** Process name discovered on the remote side, or empty if unknown. */
    public val process: String,
    /** Lifecycle state of this tunnel. */
    public val status: Status,
    /** Bytes pushed device → remote since the tunnel opened. */
    public val bytesIn: Long = 0,
    /** Bytes pulled remote → device since the tunnel opened. */
    public val bytesOut: Long = 0,
    /**
     * Instantaneous throughput in bytes per second, smoothed over the last
     * scan interval. Zero until the second scan tick observes traffic.
     */
    public val speedBps: Long = 0,
) {
    public enum class Status {
        /** Forward is up and accepting connections locally. */
        FORWARDING,

        /**
         * Remote port is listening but is outside the auto-forward window
         * (above `maxAutoPort`, below `skipPortsBelow`) and the user hasn't
         * manually opted it in. No local socket bound.
         */
        AVAILABLE,

        /**
         * The forward failed to start (local port already in use, channel
         * open rejected by the remote, ...). Held in this state so the UI
         * can offer a retry without re-scanning.
         */
        FAILED,

        /**
         * Forward was previously open but the underlying SSH session
         * dropped. Will be recreated when the session reconnects.
         */
        STOPPED,
    }
}
