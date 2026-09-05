package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.SessionTreeScreen
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.groupSessionsIntoRoots
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the session-tree header after issue #2532: Back on
 * the leading slot, Usage (and the glance pill when a reading exists) in
 * trailing next to Files/Ports.
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*SessionTreeScreenRenders*' --rerun-tasks
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SessionTreeScreenRenders {

    @Test
    fun sessionTreeHeaderBackAndUsage() = render("i2532-session-tree-header") {
        SessionTreeScreen(
            state = state(),
            onRefresh = {},
            onOpenSession = {},
            nowSec = NOW,
        )
    }

    @Test
    fun sessionTreeHeaderWithGlancePill() = render("i2532-session-tree-header-pill") {
        SessionTreeScreen(
            state = state(),
            onRefresh = {},
            onOpenSession = {},
            usagePillState = UsageGlancePillState(
                percent = 72,
                provider = "Codex",
                window = "7d",
                kind = PillKind.Warn,
                stale = false,
                fetchedClock = "13:40",
            ),
            nowSec = NOW,
        )
    }

    private fun state() = SessionTreeUiState(
        hostId = 7,
        loaded = true,
        roots = groupSessionsIntoRoots(
            listOf(
                SessionRow(
                    name = "claude-main",
                    backend = Backend.TMUX,
                    id = null,
                    workspace = "/home/a/git/pocketshell",
                    tag = null,
                    engine = "claude",
                    profile = null,
                    agentState = null,
                    agentStateSource = null,
                    attached = true,
                    createdEpoch = null,
                    activityEpoch = NOW - 120,
                ),
            ),
        ),
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

    private companion object {
        const val NOW: Long = 1_788_409_253
    }
}
