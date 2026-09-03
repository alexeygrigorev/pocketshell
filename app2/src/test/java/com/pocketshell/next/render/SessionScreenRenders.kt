package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.terminal.SessionScreen
import com.pocketshell.next.terminal.SessionUiState
import com.pocketshell.next.terminal.createRemoteTerminalSession
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the session screen and its key bar (task U-5).
 *
 * Same harness as [HostScreenRenders]: Pixel-7-class viewport, the app's own
 * always-dark theme, PNGs under `app2/build/renders/`.
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*SessionScreenRenders*' --rerun-tasks
 * ```
 *
 * The terminal grid itself is deliberately absent from these frames: the
 * vendored renderer needs a device (`libtermux.so`), so what a render shows is
 * the CHROME around it — which is exactly what U-5 changed. The pixels of a
 * live terminal are J03's business, on a real emulator against a real host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SessionScreenRenders {

    /** Attaching, with the key bar already docked so the layout cannot jump. */
    @Test
    fun sessionAttaching() = render("u5-session-attaching") {
        SessionScreen(
            state = SessionUiState.Connecting,
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onSend = {},
        )
    }

    /** Attached: the four-slot bar under a full-bleed terminal. */
    @Test
    fun sessionLiveWithKeyBar() = render("u5-session-key-bar") {
        SessionScreen(
            state = SessionUiState.Live(createRemoteTerminalSession()),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onSend = {},
        )
    }

    /** A dead session: no key bar, because there is nothing to send to. */
    @Test
    fun sessionFailed() = render("u5-session-failed") {
        SessionScreen(
            state = SessionUiState.Failed("Session \"git-pocketshell\" ended (exit 3)."),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onSend = {},
        )
    }

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
