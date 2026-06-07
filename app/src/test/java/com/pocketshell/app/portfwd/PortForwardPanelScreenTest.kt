package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.TunnelInfo
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
}
