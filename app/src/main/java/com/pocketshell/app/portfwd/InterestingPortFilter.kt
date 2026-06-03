package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort

/**
 * Decides which discovered remote ports are worth surfacing to the user and in
 * what order.
 *
 * Issue #456: on a real host `ss -tlnp` returns ~80 listening ports, most of
 * which are noise the user never wants to forward — `22` (sshd), `53` (dns),
 * `80` (http), and the docker/test `222x` family. Dumping every one of those
 * into the host card and the panel table makes the feature unusable.
 *
 * The filter mirrors `ssh-auto-forward`'s "interesting range" intuition:
 *
 * - **Interesting** ports (dev servers, app backends, etc.) live in
 *   `1000-9999` and the high `49xxx` dynamic range. These are surfaced first.
 * - **System/noise** ports (`22`, `53`, `80`) are hidden entirely — they are
 *   never something the user wants the phone to forward.
 * - Everything else (e.g. a service on `11434` or `5432`-adjacent ranges that
 *   falls outside the interesting bands) is still shown, but de-prioritised
 *   below the interesting ports so the readable rows come first.
 *
 * Duplicate rows for the same port (the scanner can emit one per bound address
 * family, e.g. `0.0.0.0:3000` and `[::]:3000`) are de-duplicated, preferring
 * the entry that carries a non-blank process name.
 */
object InterestingPortFilter {

    /** Inclusive primary "user dev server" band. */
    val PRIMARY_RANGE: IntRange = 1_000..9_999

    /** Inclusive high dynamic band (`49xxx`). */
    val DYNAMIC_RANGE: IntRange = 49_000..49_999

    /**
     * Well-known system/service ports that are pure noise for port-forwarding.
     * Hidden from both the host card and the panel.
     */
    val HIDDEN_PORTS: Set<Int> = setOf(
        22, // sshd — the control connection itself
        53, // dns
        80, // http (system / reverse proxy, not a dev server)
    )

    /** True when [port] is in one of the prioritised interesting bands. */
    fun isInteresting(port: Int): Boolean =
        port in PRIMARY_RANGE || port in DYNAMIC_RANGE

    /** True when [port] is hidden noise that should never be surfaced. */
    fun isHidden(port: Int): Boolean = port in HIDDEN_PORTS

    /**
     * Filter and order discovered ports for display.
     *
     * 1. Drops [HIDDEN_PORTS] noise.
     * 2. De-duplicates by port, keeping the entry with a non-blank process
     *    name when available.
     * 3. Sorts interesting ports ([PRIMARY_RANGE] then [DYNAMIC_RANGE]) ahead
     *    of everything else; within each group, ascending by port number.
     */
    fun filter(ports: List<RemotePort>): List<RemotePort> =
        ports
            .filterNot { isHidden(it.port) }
            .groupBy { it.port }
            .map { (_, group) ->
                group.firstOrNull { it.processName.isNotBlank() } ?: group.first()
            }
            .sortedWith(
                compareBy<RemotePort> { if (isInteresting(it.port)) 0 else 1 }
                    .thenBy { it.port },
            )

    /**
     * Count of ports that survive the filter — the "N ports" the host card
     * summarises. Counts de-duplicated, non-hidden ports.
     */
    fun count(ports: List<RemotePort>): Int = filter(ports).size
}
