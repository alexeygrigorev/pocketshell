package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.terminal.SessionScreen
import com.pocketshell.next.terminal.SessionUiState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the session screen's non-terminal chrome (task U-7).
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*SessionScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * Only the give-up failure is here, and that is a finding rather than an
 * omission: any state that HOSTS the terminal — `Live` and `Reconnecting` both
 * — renders as a blank frame on the JVM, because the vendored emulator paints
 * through `libtermux.so`, a device artifact, and its `AndroidView` takes the
 * whole capture with it. So the reconnect banner's real appearance is a device
 * screenshot from `J05ReconnectAfterDropJourney`, and its text is pinned by
 * `SessionScreenTest`. This render is the fast first look at the one state that
 * can be looked at fast, never the acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SessionScreenRenders {

    /** The ladder gave up: what is left is a message and a way to try again. */
    @Test
    fun sessionGaveUp() = render("u7-session-gave-up") {
        SessionScreen(
            state = SessionUiState.Failed(
                "Could not reconnect to the session. Tap Retry to try again.",
            ),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onRetry = {},
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
