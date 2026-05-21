package com.pocketshell.core.portfwd

/**
 * Tunables for [AutoForwarder]'s discover-and-forward loop.
 *
 * Defaults are picked to match the historical behaviour of
 * `ssh-auto-forward-android` (the JSch-based reference being ported here):
 *
 * - 10 s scan interval is responsive enough to catch a `python -m
 *   http.server` you just started while not flooding the remote with `ss`
 *   calls
 * - 10 000 caps the auto-forwarded port range at "user dev servers" — past
 *   that, ports tend to be ephemeral connections or system services
 * - 1024 skips well-known privileged ports (sshd, smtp, ...) the user
 *   never wants the phone forwarding
 * - Local port range starting at 3000 keeps allocations away from common
 *   conflicts (e.g. nothing on the phone is using 3000+)
 */
public data class AutoForwardConfig(
    /** How often to rescan the remote for listening ports. */
    public val scanIntervalSec: Int = 10,
    /** Inclusive upper bound on remote ports to auto-forward. */
    public val maxAutoPort: Int = 10_000,
    /** Exclusive lower bound — remote ports strictly below this are skipped. */
    public val skipPortsBelow: Int = 1024,
    /**
     * Local port allocation range. When a remote port falls inside
     * `[skipPortsBelow, maxAutoPort]` we reuse it as the local port (so e.g.
     * `localhost:3000 ↔ remote:3000`). Otherwise the forwarder picks the
     * next free port from this range.
     */
    public val localPortRange: IntRange = 3_000..3_999,
    /**
     * How long a remote port stays on the failed-attempts deny-list before
     * the scanner is allowed to retry it. Without a TTL, a transient
     * remote-side failure (channel briefly refused, ephemeral local port
     * collision, ...) would suppress the port until the process restarts.
     * 60 s is a sensible default: long enough that a flapping service
     * doesn't get retried every tick, short enough that recovery is
     * automatic on a human timescale.
     */
    public val failedPortTtlMs: Long = 60_000L,
)
