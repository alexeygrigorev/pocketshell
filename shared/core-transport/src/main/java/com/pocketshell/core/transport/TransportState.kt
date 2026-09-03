package com.pocketshell.core.transport

/**
 * Lifecycle of one [HostConnection].
 *
 * [Lost] and [Closed] are terminal for a given connection instance: a
 * connection never self-heals. Reconnecting means the factory dials a *new*
 * [HostConnection]; the UI-level connection identity outlives the transport
 * object, the transport object does not resurrect itself.
 */
sealed interface TransportState {
    data object Connecting : TransportState

    data object Connected : TransportState

    /** The transport dropped unexpectedly. [cause] is a human-readable reason. */
    data class Lost(val cause: String) : TransportState

    /** The transport was closed deliberately (via [HostConnection.close]). */
    data object Closed : TransportState
}
