package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServicesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `off state contains no invented services and keeps add action reachable`() {
        setContent(state(enabled = false))

        composeRule.onNodeWithText("No active tunnels").assertIsDisplayed()
        composeRule.onNodeWithText("Port 5173").assertDoesNotExist()
        composeRule.onNodeWithTag(SERVICES_ADD_TUNNEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `active service opens detail and available service opens the real add flow`() {
        val opened = mutableListOf<Int>()
        val added = mutableListOf<Int?>()
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                rows = listOf(
                    tunnel(5173, "vite", TunnelInfo.Status.FORWARDING, localPort = 35173),
                    tunnel(8000, "python", TunnelInfo.Status.AVAILABLE, localPort = 8000),
                ),
            ),
            onOpenTunnel = { opened += it },
            onAddTunnel = { added += it },
        )

        composeRule.onNodeWithTag(servicesRowTag(5173)).performClick()
        composeRule.onNodeWithTag(servicesRowTag(8000)).performClick()

        assertEquals(listOf(5173), opened)
        assertEquals(listOf(8000), added)
    }

    @Test
    fun `discovery control reports the requested state`() {
        val requested = mutableListOf<Boolean>()
        setContent(state(enabled = false), onSetDiscovery = { requested += it })

        composeRule.onNodeWithTag("$SERVICES_DISCOVERY_TAG-on").performClick()

        assertEquals(listOf(true), requested)
    }

    @Test
    fun `services includes ports hidden from the legacy port list`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                discoveredRows = listOf(tunnel(22, "sshd", TunnelInfo.Status.AVAILABLE, localPort = 22)),
            ),
        )

        composeRule.onNodeWithTag(servicesRowTag(22)).assertIsDisplayed()
    }

    @Test
    fun `a mapped tunnel opens detail even when its current state is not forwarding`() {
        val opened = mutableListOf<Int>()
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                discoveredRows = listOf(tunnel(22, "sshd", TunnelInfo.Status.AVAILABLE, localPort = 7_432)),
                manualRemotePorts = setOf(22),
            ),
            onOpenTunnel = { opened += it },
        )

        composeRule.onNodeWithTag(servicesRowTag(22)).performClick()

        assertEquals(listOf(22), opened)
    }

    @Test
    fun `lost connection shows an actionable error instead of an empty catalog`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Lost,
            ).copy(attention = "Confirm the host key"),
        )

        composeRule.onNodeWithText("Connection needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm the host key").assertIsDisplayed()
    }

    private fun setContent(
        state: PortForwardUiState,
        onSetDiscovery: (Boolean) -> Unit = {},
        onOpenTunnel: (Int) -> Unit = {},
        onAddTunnel: (Int?) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                ServicesScreen(
                    state = state,
                    onBack = {},
                    onSetDiscovery = onSetDiscovery,
                    onOpenTunnel = onOpenTunnel,
                    onAddTunnel = onAddTunnel,
                )
            }
        }
    }

    private fun state(
        enabled: Boolean,
        connection: ConnectionState = ConnectionState.Idle,
        rows: List<TunnelInfo> = emptyList(),
        discoveredRows: List<TunnelInfo> = emptyList(),
        manualRemotePorts: Set<Int> = emptySet(),
    ) = PortForwardUiState(
        hostId = 1,
        hostName = "hetzner",
        hostSubtitle = "alexey@hetzner:22",
        enabled = enabled,
        connection = connection,
        rows = rows,
        discoveredRows = discoveredRows,
        manualRemotePorts = manualRemotePorts,
        loading = false,
    )

    private fun tunnel(
        remotePort: Int,
        process: String,
        status: TunnelInfo.Status,
        localPort: Int,
    ) = TunnelInfo(
        remotePort = remotePort,
        localPort = localPort,
        process = process,
        status = status,
    )
}
