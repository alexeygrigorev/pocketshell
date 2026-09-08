package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.next.ports.ServicesScreen
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Services & tunnels composables at the compact phone anchor. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ServicesScreenRenders {

    @Test
    fun servicesEmpty() = render("services-empty") {
        ServicesScreen(
            state = state(enabled = false),
            onBack = {},
            onSetDiscovery = {},
            onOpenTunnel = {},
            onAddTunnel = {},
        )
    }

    @Test
    fun servicesActive() = render("services-active") {
        ServicesScreen(
            state = state(
                enabled = true,
                connection = ConnectionState.Connected,
                rows = listOf(
                    TunnelInfo(5173, 35173, "vite", TunnelInfo.Status.FORWARDING),
                    TunnelInfo(8000, 8000, "python", TunnelInfo.Status.AVAILABLE),
                ),
            ),
            onBack = {},
            onSetDiscovery = {},
            onOpenTunnel = {},
            onAddTunnel = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }

    private fun state(
        enabled: Boolean,
        connection: ConnectionState = ConnectionState.Idle,
        rows: List<TunnelInfo> = emptyList(),
    ) = PortForwardUiState(
        hostId = 1,
        hostName = "hetzner",
        hostSubtitle = "alexey@hetzner:22",
        enabled = enabled,
        connection = connection,
        rows = rows,
        loading = false,
    )
}
