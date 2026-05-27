package com.pocketshell.app.sessions

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.components.SessionRow
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshot test for issue #202 — captures a session row with every
 * combination of indicator that [dashboardRowUi] can emit so the
 * implementer's status comment and the reviewer can inspect the
 * mixed-case label change, the activity-state dot, and the legend
 * without spinning up the full dashboard.
 *
 * Two artifacts:
 *  - `session-row-indicator-combinations.png` — the rendered session
 *    rows. Covers five rows together: Claude+Attached, Codex+Detached,
 *    OpenCode+Attached, Deploy+Detached, ML+Detached, plus a bare row
 *    with only an activity-state chip.
 *  - `session-row-indicator-legend.png` — the legend panel. Documents
 *    the meaning of every chip the dashboard can emit and the leading
 *    accent badge.
 *
 * Acceptance criteria satisfied:
 *  - "Screenshot test captures a session row with all indicator
 *    combinations."
 *  - "First-time user can interpret every indicator without external
 *    help OR via legend tap." (Legend panel artifact + content
 *    invariant test [dashboardRowUiAndLegendStayInSync].)
 *  - "No two indicators on the same row have similar shapes/colors
 *    that aren't trivially distinguishable." (Visible from the row
 *    artifact — activity-state chips lead with a coloured dot,
 *    classifier chips do not.)
 */
@RunWith(AndroidJUnit4::class)
class SessionRowIndicatorScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureIndicatorCombinations() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 12.dp, vertical = 16.dp)
                        .testTag(INDICATOR_SCREENSHOT_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SessionRow(
                        badge = "A",
                        name = "agent-main",
                        host = "hetzner",
                        preview = "claude conversation active",
                        time = "2m",
                        tags = listOf(
                            Tag("Claude", TagKind.Agent),
                            Tag("Attached", TagKind.Attached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "C",
                        name = "codex-poc",
                        host = "hetzner",
                        preview = "codex workspace ready",
                        time = "5m",
                        tags = listOf(
                            Tag("Codex", TagKind.Agent),
                            Tag("Detached", TagKind.Detached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "O",
                        name = "opencode-eval",
                        host = "gpu-box",
                        preview = "opencode conversation active",
                        time = "now",
                        tags = listOf(
                            Tag("OpenCode", TagKind.Agent),
                            Tag("Attached", TagKind.Attached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "D",
                        name = "deploy-watch",
                        host = "prod",
                        preview = "tmux session detached",
                        time = "14m",
                        tags = listOf(
                            Tag("Deploy", TagKind.Deploy),
                            Tag("Detached", TagKind.Detached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "T",
                        name = "training",
                        host = "gpu-box",
                        preview = "tmux session detached",
                        time = "1h",
                        tags = listOf(
                            Tag("ML", TagKind.Ml),
                            Tag("Detached", TagKind.Detached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "S",
                        name = "scratch",
                        host = "hetzner",
                        preview = "attached tmux client",
                        time = "now",
                        tags = listOf(Tag("Attached", TagKind.Attached)),
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(INDICATOR_SCREENSHOT_ROOT_TAG).assertExists()
        // Wait for each rendered chip to settle on screen before we
        // snap the screenshot — TalkBack-style content descriptions
        // are set via Modifier.semantics, but the rendered Text is
        // the same string so we can sanity-check by text match.
        compose.waitForIdle()
        compose.onAllNodesWithText("Claude").assertCountEquals(1)
        compose.onAllNodesWithText("Attached").assertCountEquals(3)
        compose.onAllNodesWithText("Detached").assertCountEquals(3)
        SystemClock.sleep(200)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "session-row-indicator-combinations.png"))
    }

    @Test
    fun captureLegendPanel() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .testTag(LEGEND_SCREENSHOT_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SessionsLegend()
                }
            }
        }

        compose.onNodeWithTag(LEGEND_SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        // Sanity-check every legend entry's description rendered before
        // we snap the screenshot — a stale or missing row would
        // produce a misleading artifact for the reviewer.
        SESSIONS_LEGEND_ENTRIES.forEach { entry ->
            compose.onAllNodesWithText(entry.description).assertCountEquals(1)
        }
        compose.onAllNodesWithText("What the indicators mean").assertCountEquals(1)
        compose.onAllNodesWithText("First letter of the session name (visual anchor only)")
            .assertCountEquals(1)
        SystemClock.sleep(200)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "session-row-indicator-legend.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/session-row-indicators")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create session-row indicator screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write session-row indicator screenshot: ${file.absolutePath}"
                }
            }
            println("SESSION_ROW_INDICATOR_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val INDICATOR_SCREENSHOT_ROOT_TAG: String = "session-row-indicator-screenshot"
        const val LEGEND_SCREENSHOT_ROOT_TAG: String = "session-row-indicator-legend"
    }
}
