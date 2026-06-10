package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.terminal.selection.LocalhostUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortForwardPanelScreenTest {

    @Test
    fun `localForwardedUrlFor uses actual forwarded local port`() {
        val tunnels = listOf(
            TunnelInfo(
                remotePort = 5173,
                localPort = 15173,
                process = "node",
                status = TunnelInfo.Status.FORWARDING,
            ),
        )

        assertEquals("http://127.0.0.1:15173", localForwardedUrlFor(tunnels, remotePort = 5173))
    }

    @Test
    fun `localForwardedUrlFor preserves tapped localhost url target`() {
        val tunnels = listOf(
            TunnelInfo(
                remotePort = 8443,
                localPort = 18443,
                process = "vite",
                status = TunnelInfo.Status.FORWARDING,
            ),
        )
        val localhostUrl = LocalhostUrl(
            remotePort = 8443,
            scheme = "https",
            pathAndQuery = "/admin?tab=logs#tail",
        )

        assertEquals(
            "https://127.0.0.1:18443/admin?tab=logs#tail",
            localForwardedUrlFor(tunnels, localhostUrl),
        )
    }

    @Test
    fun `localForwardedUrlFor ignores non-forwarding target ports`() {
        val tunnels = listOf(
            TunnelInfo(
                remotePort = 5173,
                localPort = 15173,
                process = "node",
                status = TunnelInfo.Status.FAILED,
            ),
            TunnelInfo(
                remotePort = 3000,
                localPort = 3000,
                process = "vite",
                status = TunnelInfo.Status.FORWARDING,
            ),
        )

        assertNull(localForwardedUrlFor(tunnels, remotePort = 5173))
    }

    @Test
    fun `visibleTunnelRows hides rows outside useful local or remote port range by default`() {
        val tunnels = listOf(
            forwardingTunnel(remotePort = 22),
            forwardingTunnel(remotePort = 80),
            forwardingTunnel(remotePort = 443),
            forwardingTunnel(remotePort = 3000),
            forwardingTunnel(remotePort = 8080),
            forwardingTunnel(remotePort = 10000),
            forwardingTunnel(remotePort = 11434),
            forwardingTunnel(remotePort = 49152),
            forwardingTunnel(remotePort = 9000, localPort = 11435),
        )

        assertEquals(
            listOf(3000, 8080, 10000),
            visibleTunnelRows(tunnels, showAllPorts = false).map { it.remotePort },
        )
        assertEquals(6, hiddenTunnelRowCount(tunnels))
    }

    @Test
    fun `visibleTunnelRows reveals hidden forwarded ports in show-all mode`() {
        val tunnels = listOf(
            forwardingTunnel(remotePort = 8080),
            forwardingTunnel(remotePort = 49152),
            forwardingTunnel(remotePort = 22),
            forwardingTunnel(remotePort = 443),
            forwardingTunnel(remotePort = 11434),
            forwardingTunnel(remotePort = 3000),
            forwardingTunnel(remotePort = 9000, localPort = 11435),
        )

        assertEquals(
            listOf(3000, 8080, 22, 443, 9000, 11434, 49152),
            visibleTunnelRows(tunnels, showAllPorts = true).map { it.remotePort },
        )
    }

    @Test
    fun `maintainer dogfood 222x forwarded ports are hidden by default`() {
        // Mirrors issue-602-portfwd-clutter.png: the active forwarding panel was
        // dominated by docker/agent/test SSH proxies (2222/2224/2226/2228/2229/
        // 2230/2240) mirrored 1:1 to local ports. After #602 those rows are hidden
        // by default; only the meaningful app ports (4000/4001) stay visible, and
        // the hidden count is exactly the 222x/2240 family.
        val tunnels = listOf(
            forwardingTunnel(remotePort = 2222),
            forwardingTunnel(remotePort = 2224),
            forwardingTunnel(remotePort = 2226),
            forwardingTunnel(remotePort = 2228),
            forwardingTunnel(remotePort = 2229),
            forwardingTunnel(remotePort = 2230),
            forwardingTunnel(remotePort = 2240),
            forwardingTunnel(remotePort = 4000),
            forwardingTunnel(remotePort = 4001),
        )

        assertEquals(
            listOf(4000, 4001),
            visibleTunnelRows(tunnels, showAllPorts = false).map { it.remotePort },
        )
        assertEquals(7, hiddenTunnelRowCount(tunnels))
        // Show-all surfaces the meaningful ports first, then the hidden 222x noise.
        assertEquals(
            listOf(4000, 4001, 2222, 2224, 2226, 2228, 2229, 2230, 2240),
            visibleTunnelRows(tunnels, showAllPorts = true).map { it.remotePort },
        )
    }

    @Test
    fun `hiddenNoisyPortsToggleLabel names hidden noisy ports with count`() {
        assertEquals(
            "Show hidden/noisy ports (3 hidden)",
            hiddenNoisyPortsToggleLabel(checked = false, hiddenCount = 3),
        )
    }

    @Test
    fun `hiddenNoisyPortsToggleLabel omits count when enabled or empty`() {
        assertEquals(
            "Show hidden/noisy ports",
            hiddenNoisyPortsToggleLabel(checked = true, hiddenCount = 3),
        )
        assertEquals(
            "Show hidden/noisy ports",
            hiddenNoisyPortsToggleLabel(checked = false, hiddenCount = 0),
        )
    }

    @Test
    fun `shouldClearPendingForwardAutoOpen clears after requested port fails`() {
        val state = PortForwardPanelState(
            connectionState = PortForwardConnectionState.Connected,
            tunnels = listOf(
                TunnelInfo(
                    remotePort = 5173,
                    localPort = 15173,
                    process = "node",
                    status = TunnelInfo.Status.FAILED,
                ),
            ),
        )

        assertEquals(true, shouldClearPendingForwardAutoOpen(state, remotePort = 5173))
    }

    @Test
    fun `shouldClearPendingForwardAutoOpen clears after connection error`() {
        val state = PortForwardPanelState(
            connectionState = PortForwardConnectionState.Error,
            tunnels = emptyList(),
        )

        assertEquals(true, shouldClearPendingForwardAutoOpen(state, remotePort = 5173))
    }

    @Test
    fun `shouldClearPendingForwardAutoOpen keeps waiting while other ports fail`() {
        val state = PortForwardPanelState(
            connectionState = PortForwardConnectionState.Connected,
            tunnels = listOf(
                TunnelInfo(
                    remotePort = 3000,
                    localPort = 3000,
                    process = "vite",
                    status = TunnelInfo.Status.FAILED,
                ),
            ),
        )

        assertEquals(false, shouldClearPendingForwardAutoOpen(state, remotePort = 5173))
    }

    @Test
    fun `shouldClearPendingForwardAutoOpen keeps waiting before requested tunnel is ready`() {
        val state = PortForwardPanelState(
            connectionState = PortForwardConnectionState.Connecting,
            tunnels = emptyList(),
        )

        assertEquals(false, shouldClearPendingForwardAutoOpen(state, remotePort = 5173))
    }

    private fun forwardingTunnel(remotePort: Int, localPort: Int = remotePort): TunnelInfo =
        TunnelInfo(
            remotePort = remotePort,
            localPort = localPort,
            process = "server",
            status = TunnelInfo.Status.FORWARDING,
        )
}
