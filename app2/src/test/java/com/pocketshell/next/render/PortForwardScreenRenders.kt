package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.next.ports.ForwardingController
import com.pocketshell.next.ports.PortForwardScreen
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the port-forward screen's connection states, in the
 * same shape as [HostScreenRenders] (issue #555's harness, app2 half).
 *
 * They exist for issue #2491: a host that has STOPPED retrying has to look
 * different from one that is still trying, and "different" is a thing you look
 * at, not a thing an assertion settles. The behaviour itself is asserted in
 * `PortForwardScreenTest`; nothing here is the only check on anything.
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*PortForwardScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class PortForwardScreenRenders {

    /** Terminal: the host key is unconfirmed, so nothing more will be tried. */
    @Test
    fun portForwardNeedsAttention() = render("i2491-port-forward-needs-attention") {
        PortForwardScreen(
            state = state(
                connection = ConnectionState.Lost,
                attention = ForwardingController.NEEDS_TRUST_ATTENTION,
            ),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    /** Transient: the dial keeps retrying on its own, so the screen says so. */
    @Test
    fun portForwardReconnecting() = render("i2491-port-forward-reconnecting") {
        PortForwardScreen(
            state = state(connection = ConnectionState.Reconnecting),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    /** Healthy, for the side-by-side comparison. */
    @Test
    fun portForwardConnected() = render("i2491-port-forward-connected") {
        PortForwardScreen(
            state = state(
                connection = ConnectionState.Connected,
                rows = listOf(
                    TunnelInfo(
                        remotePort = 3_000,
                        localPort = 3_000,
                        process = "vite",
                        status = TunnelInfo.Status.FORWARDING,
                        bytesIn = 2_048,
                        bytesOut = 8_192,
                    ),
                ),
            ),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    private fun state(
        connection: ConnectionState,
        attention: String? = null,
        rows: List<TunnelInfo> = emptyList(),
    ) = PortForwardUiState(
        hostId = 1L,
        hostName = "rmthz",
        hostSubtitle = "alexey@135.181.114.209:22",
        enabled = true,
        connection = connection,
        attention = attention,
        rows = rows,
        loading = false,
    )

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }
}
