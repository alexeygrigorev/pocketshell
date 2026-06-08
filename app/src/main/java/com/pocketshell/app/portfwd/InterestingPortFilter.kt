package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort

/**
 * Decides which discovered remote ports are worth surfacing to the user and in
 * what order.
 *
 * Issue #456 established the original "interesting range" intuition. Issue #602
 * tightens it for the port-forward table: by default the table hides noisy
 * application/dev-server ports in [HIDDEN_NOISY_RANGE] (`1000..9999`) and keeps
 * system ports such as 80/443 plus high app ports such as 11434 visible.
 *
 * Two filtering modes:
 *
 * - **Default ([filter] / `showAll = false`):** hide ports in
 *   [HIDDEN_NOISY_RANGE].
 * - **Show-all ([filter] with `showAll = true`):** keep every discovered port,
 *   including the hidden/noisy ones. Default-visible ports still sort first so
 *   the useful rows stay at the top.
 *
 * Duplicate rows for the same port (the scanner can emit one per bound address
 * family, e.g. `0.0.0.0:3000` and `[::]:3000`) are always de-duplicated,
 * preferring the entry that carries a non-blank process name — in both modes.
 */
object InterestingPortFilter {

    /**
     * Inclusive noisy port range hidden by default (#602). This intentionally
     * does not hide privileged ports like 80/443, because those are often the
     * actual service the user wants to forward.
     */
    val HIDDEN_NOISY_RANGE: IntRange = 1_000..9_999

    /** True when [port] is hidden unless "Show hidden/noisy ports" is enabled. */
    fun isNoisy(port: Int): Boolean = port in HIDDEN_NOISY_RANGE

    /** True when [port] should be shown in the default filtered table. */
    fun isVisibleByDefault(port: Int): Boolean = !isNoisy(port)

    /**
     * Filter and order discovered ports for display.
     *
     * 1. De-duplicates by port, keeping the entry with a non-blank process
     *    name when available.
     * 2. When [showAll] is false (default), drops every port in
     *    [HIDDEN_NOISY_RANGE]. When [showAll] is true, keeps them all.
     * 3. Sorts default-visible ports ahead of hidden/noisy ones; within each
     *    group, ascending by port number.
     */
    fun filter(ports: List<RemotePort>, showAll: Boolean = false): List<RemotePort> =
        ports
            .groupBy { it.port }
            .map { (_, group) ->
                group.firstOrNull { it.processName.isNotBlank() } ?: group.first()
            }
            .let { deduped ->
                if (showAll) deduped else deduped.filter { isVisibleByDefault(it.port) }
            }
            .sortedWith(
                compareBy<RemotePort> { if (isVisibleByDefault(it.port)) 0 else 1 }
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
     * hidden/noisy ports that "Show all ports" would reveal. Zero when every
     * discovered port is already default-visible.
     */
    fun hiddenCount(ports: List<RemotePort>): Int =
        count(ports, showAll = true) - count(ports, showAll = false)
}
