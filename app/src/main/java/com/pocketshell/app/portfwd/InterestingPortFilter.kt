package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort

/**
 * Decides which discovered remote ports are worth surfacing to the user and in
 * what order.
 *
 * Issue #456 established the original "interesting range" intuition. Issue #602
 * tightens it for the port-forward table: by default the table hides noisy low
 * ports below `10000` (system services, Docker/test SSH ports, common local
 * daemons) and shows `10000+` ports, including higher app/dev servers such as
 * `11434`.
 *
 * Two filtering modes:
 *
 * - **Default ([filter] / `showAll = false`):** keep only ports in the
 *   inclusive [DEFAULT_RANGE] `10000-65535`. Everything below it is hidden.
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
     * Inclusive high-port range shown by default (#602). Ports below this band
     * are hidden unless "Show all ports" is enabled.
     */
    val DEFAULT_RANGE: IntRange = 10_000..65_535

    /** True when [port] is inside the default-shown [DEFAULT_RANGE]. */
    fun isInRange(port: Int): Boolean = port in DEFAULT_RANGE

    /**
     * Filter and order discovered ports for display.
     *
     * 1. De-duplicates by port, keeping the entry with a non-blank process
     *    name when available.
     * 2. When [showAll] is false (default), drops every port outside
     *    [DEFAULT_RANGE] — the low-port noise. When [showAll] is true, keeps
     *    them all.
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
