package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort

/**
 * Decides which discovered remote ports are worth surfacing to the user and in
 * what order.
 *
 * Issue #456 established the original "interesting range" intuition. Issue #492
 * tightens it for the port-forward table: by default the table shows only the
 * **useful dev-port range** `[1000, 10000]` (typical app/dev servers — 3000,
 * 5000, 8000, 8080, …). Ports below `1000` (system) and above `10000`
 * (high/ephemeral, e.g. the `49xxx` dynamic range) are hidden by default and
 * revealed only when the user ticks the panel's "Show all ports" checkbox.
 *
 * Two filtering modes:
 *
 * - **Default ([filter] / `showAll = false`):** keep only ports in the
 *   inclusive [DEFAULT_RANGE] `1000-10000`. Everything outside it — system
 *   ports `< 1000` and high/ephemeral ports `> 10000` — is hidden. This is the
 *   readable, low-noise view.
 * - **Show-all ([filter] with `showAll = true`):** keep every discovered port,
 *   including the out-of-range ones. The in-range ports still sort first so the
 *   useful rows stay at the top.
 *
 * Duplicate rows for the same port (the scanner can emit one per bound address
 * family, e.g. `0.0.0.0:3000` and `[::]:3000`) are always de-duplicated,
 * preferring the entry that carries a non-blank process name — in both modes.
 */
object InterestingPortFilter {

    /**
     * Inclusive useful dev-server range shown by default (#492). Ports inside
     * this band are the ones the user almost always wants to forward; ports
     * outside it are hidden unless "Show all ports" is enabled.
     */
    val DEFAULT_RANGE: IntRange = 1_000..10_000

    /** True when [port] is inside the default-shown [DEFAULT_RANGE]. */
    fun isInRange(port: Int): Boolean = port in DEFAULT_RANGE

    /**
     * Filter and order discovered ports for display.
     *
     * 1. De-duplicates by port, keeping the entry with a non-blank process
     *    name when available.
     * 2. When [showAll] is false (default), drops every port outside
     *    [DEFAULT_RANGE] — the `< 1000` system ports and `> 10000`
     *    high/ephemeral ports. When [showAll] is true, keeps them all.
     * 3. Sorts in-range ports ahead of out-of-range ones; within each group,
     *    ascending by port number.
     */
    fun filter(ports: List<RemotePort>, showAll: Boolean = false): List<RemotePort> =
        ports
            .groupBy { it.port }
            .map { (_, group) ->
                group.firstOrNull { it.processName.isNotBlank() } ?: group.first()
            }
            .let { deduped ->
                if (showAll) deduped else deduped.filter { isInRange(it.port) }
            }
            .sortedWith(
                compareBy<RemotePort> { if (isInRange(it.port)) 0 else 1 }
                    .thenBy { it.port },
            )

    /**
     * Count of ports that survive the filter — the "N ports" the host card and
     * panel summary surface. Counts de-duplicated ports honouring [showAll].
     */
    fun count(ports: List<RemotePort>, showAll: Boolean = false): Int =
        filter(ports, showAll).size

    /**
     * Number of de-duplicated ports hidden by the default filter — i.e. the
     * out-of-range ports that "Show all ports" would reveal. Zero when every
     * discovered port is already in [DEFAULT_RANGE].
     */
    fun hiddenCount(ports: List<RemotePort>): Int =
        count(ports, showAll = true) - count(ports, showAll = false)
}
