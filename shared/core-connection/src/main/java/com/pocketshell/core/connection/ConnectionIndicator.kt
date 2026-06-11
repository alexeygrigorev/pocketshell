package com.pocketshell.core.connection

/**
 * The header connection-state indicator, derived 1:1 from [ConnectionState].
 *
 * The maintainer's locked acceptance criterion (issue #687): the header indicator
 * must accurately render the CURRENT state machine — a false-negative (no
 * indication while actually disconnected) is as bad as the false-positive scary
 * banner. So the indicator is a pure projection of the single [ConnectionState]
 * source, never a second source that can drift. Live = connected (green),
 * Reconnecting/Reattaching = a calm working indicator (no manual tap),
 * Unreachable = the only honest error, Gone = session ended.
 */
enum class ConnectionIndicator {
    /** No session shown. */
    Idle,

    /** Cold dial in progress — overlay-worthy. */
    Connecting,

    /** Warm attach / select-window + seed — a thin working indicator, no overlay. */
    Attaching,

    /** Attached, healthy — the green "connected" dot. */
    Connected,

    /** App backgrounded; lease warm. A subdued "paused" indicator. */
    Backgrounded,

    /** Silent heal-and-retry — a CALM spinner, never a scary band. */
    Reconnecting,

    /** Session deleted elsewhere (#666) — "ended", not an error. */
    Gone,

    /** The ONLY honest error — a real outage after retries exhausted. */
    Unreachable,
}

/** Map the single [ConnectionState] source to its header [ConnectionIndicator]. */
fun ConnectionState.toIndicator(): ConnectionIndicator = when (this) {
    is ConnectionState.Idle -> ConnectionIndicator.Idle
    is ConnectionState.Connecting -> ConnectionIndicator.Connecting
    is ConnectionState.Attaching -> ConnectionIndicator.Attaching
    is ConnectionState.Live -> ConnectionIndicator.Connected
    is ConnectionState.Backgrounded -> ConnectionIndicator.Backgrounded
    // Both silent-recovery states map to the same calm "reconnecting" surface:
    // the user never sees "Tap Reconnect", only a calm working indicator.
    is ConnectionState.Reattaching -> ConnectionIndicator.Reconnecting
    is ConnectionState.Reconnecting -> ConnectionIndicator.Reconnecting
    is ConnectionState.Gone -> ConnectionIndicator.Gone
    is ConnectionState.Unreachable -> ConnectionIndicator.Unreachable
}
