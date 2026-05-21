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
)
