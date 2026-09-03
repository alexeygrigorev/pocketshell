package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.TunnelInfo

/**
 * Decides which discovered remote ports are worth surfacing and in what order.
 *
 * Ported unchanged (behaviour, thresholds and rationale) from the old client's
 * `portfwd/InterestingPortFilter.kt`, retargeted from `RemotePort` to
 * [TunnelInfo] because app2's port table is driven by the forwarder's tunnel
 * snapshot rather than a separate scan result.
 *
 * The `3000` lower bound is the maintainer's v0.3.30 feedback: the default list
 * was dominated by docker/agent/test SSH proxies in the `222x`/`2240` family (and
 * other sub-3000 infra ports), pure noise for this workflow. The dev-server ports
 * he actually wanted (`3000`, `4000`, `4001`, `5173`, `8080`) all live at or above
 * `3000`, so the floor hides the infra noise while keeping the useful rows. The
 * hidden ports stay one checkbox away.
 */
object InterestingPortFilter {

    /**
     * Inclusive user-useful port range shown by default. Low/system ports below
     * it and high/noisy ports above it remain available behind
     * "Show hidden/noisy ports".
     */
    val DEFAULT_VISIBLE_RANGE: IntRange = 3_000..10_000

    /** True when [port] is hidden unless "Show hidden/noisy ports" is enabled. */
    fun isNoisy(port: Int): Boolean = port !in DEFAULT_VISIBLE_RANGE

    /** True when [port] should be shown in the default filtered table. */
    fun isVisibleByDefault(port: Int): Boolean = port in DEFAULT_VISIBLE_RANGE

    /**
     * A tunnel row is default-visible only when BOTH ends are: a forward from a
     * useful remote port onto an ephemeral local port is still a row about a
     * noisy port from the user's point of view.
     */
    fun isVisibleByDefault(tunnel: TunnelInfo): Boolean =
        isVisibleByDefault(tunnel.remotePort) && isVisibleByDefault(tunnel.localPort)

    /**
     * Filter and order tunnel rows for display.
     *
     * When [showAll] is false (the default) rows outside
     * [DEFAULT_VISIBLE_RANGE] are dropped; when it is true they are kept but
     * sorted after the default-visible ones, so the useful rows stay on top.
     * Ordering within each group is ascending by remote port.
     *
     * This never touches the forwarder: a hidden row's tunnel keeps running.
     */
    fun filter(tunnels: List<TunnelInfo>, showAll: Boolean = false): List<TunnelInfo> =
        (if (showAll) tunnels else tunnels.filter(::isVisibleByDefault))
            .sortedWith(
                compareBy<TunnelInfo> { if (isVisibleByDefault(it)) 0 else 1 }
                    .thenBy { it.remotePort },
            )

    /**
     * Number of rows the default filter hides — the "(N hidden)" the checkbox
     * label surfaces so the user knows the table is filtered. Zero when every row
     * is already default-visible.
     */
    fun hiddenCount(tunnels: List<TunnelInfo>): Int = tunnels.count { !isVisibleByDefault(it) }
}
