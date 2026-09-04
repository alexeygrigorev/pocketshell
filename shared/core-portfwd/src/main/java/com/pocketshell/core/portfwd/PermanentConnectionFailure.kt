package com.pocketshell.core.portfwd

import java.io.IOException

/**
 * Thrown by an [AutoForwarderSupervisor]'s connection factory when the dial can
 * never succeed on its own: repeating the exact same connect is guaranteed to
 * fail again until something OUTSIDE the supervisor changes — the user confirms
 * a host key, re-creates a deleted host row, and so on.
 *
 * The supervisor treats it as terminal on the spot: it publishes
 * [AutoForwarderSupervisor.ConnectionState.Lost], emits
 * [AutoForwarderSupervisor.Event.ConnectionLost] carrying this message, and
 * parks until [AutoForwarderSupervisor.reconnectNow] (issue #2491). Before this
 * existed, an auto-forward for a host whose key needed confirming re-ran the
 * whole SSH handshake every 5-60 s forever, holding the foreground service and
 * its notification alive with the row stuck on "Reconnecting".
 *
 * Every OTHER throwable stays transient and keeps the ordinary exponential
 * backoff — a phone that loses its network in a tunnel must still self-heal
 * without anyone tapping anything.
 */
public class PermanentConnectionFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
