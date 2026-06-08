package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort

/**
 * Decides which discovered remote ports are worth surfacing to the user and in
 * what order.
 *
 * Issue #456 established the original "interesting range" intuition. Issue #602
 * tightens it for the port-forward table: by default the table shows the
 * user-useful application/dev-server range in [DEFAULT_VISIBLE_RANGE]
 * (`1000..10000`) and hides low/system plus high/ephemeral-style ports unless
 * the user explicitly asks to show hidden/noisy rows.
 *
 * Two filtering modes:
 *
 * - **Default ([filter] / `showAll = false`):** keep ports in
 *   [DEFAULT_VISIBLE_RANGE].
 * - **Show-all ([filter] with `showAll = true`):** keep every discovered port,
 *   including hidden/noisy ones outside that range. Default-visible ports still
 *   sort first so the useful rows stay at the top.
 *
 * Duplicate rows for the same port (the scanner can emit one per bound address
 * family, e.g. `0.0.0.0:3000` and `[::]:3000`) are always de-duplicated,
 * preferring the entry that carries a non-blank process name — in both modes.
 */
object InterestingPortFilter {

    /**
     * Inclusive user-useful port range shown by default (#602). Low/system
     * ports below this and high/noisy ports above it remain available behind
     * "Show hidden/noisy ports".
     */
    val DEFAULT_VISIBLE_RANGE: IntRange = 1_000..10_000

    /** True when [port] is hidden unless "Show hidden/noisy ports" is enabled. */
    fun isNoisy(port: Int): Boolean = port !in DEFAULT_VISIBLE_RANGE

    /** True when [port] should be shown in the default filtered table. */
    fun isVisibleByDefault(port: Int): Boolean = port in DEFAULT_VISIBLE_RANGE

    /**
     * Filter and order discovered ports for display.
     *
     * 1. De-duplicates by port, keeping the entry with a non-blank process
     *    name when available.
     * 2. When [showAll] is false (default), drops every port outside
     *    [DEFAULT_VISIBLE_RANGE]. When [showAll] is true, keeps them all.
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
     * hidden/noisy ports that "Show hidden/noisy ports" would reveal. Zero
     * when every discovered port is already default-visible.
     */
    fun hiddenCount(ports: List<RemotePort>): Int =
        count(ports, showAll = true) - count(ports, showAll = false)
}
