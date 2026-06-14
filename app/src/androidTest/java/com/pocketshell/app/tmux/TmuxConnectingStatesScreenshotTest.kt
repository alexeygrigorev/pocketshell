package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issues #757 + #750 screenshot evidence — captures the two tmux
 * connecting/attach states with the real session chrome above them so a
 * reviewer/maintainer can eyeball that:
 *
 *  - #757: the "waiting for tmux panes…" connecting state now shows the canonical
 *    centered [LoadingIndicator.Spinner] (Medium) — the SAME animated affordance
 *    the "Attaching…" state uses — instead of the old static text with no spinner.
 *  - #750: the "Attaching…" reattach state shows EXACTLY ONE indicator (this
 *    centered spinner). The thin under-header progress line that used to render at
 *    the same time during a [Switching] switch has been removed, so the reattach
 *    screen no longer shows two loading indicators.
 *
 * The placeholder composables ([EmptyPanesPlaceholder] /
 * [SwitchingLoadingPlaceholder]) are private to the screen, so this mounts the
 * real [ConsolidatedTopChrome] header + a byte-for-byte copy of each placeholder
 * body (the canonical labelled spinner on the same Surface/Background fill the
 * screen uses) to reproduce the exact on-screen layout. No top progress bar is
 * placed between the chrome and the centered spinner — proving the single-
 * indicator result.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConnectingStatesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureWaitingForPanesConnectingState() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "scratch",
                        onBack = {},
                        onMore = {},
                    )
                    // Exact body of EmptyPanesPlaceholder (#757): a full-surface
                    // box with the canonical centered labelled spinner. No top bar.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(color = PocketShellColors.Surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator.Spinner(
                            size = SpinnerSize.Medium,
                            label = "waiting for tmux panes…",
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("waiting for tmux panes…").assertExists()
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-connecting-waiting-for-panes.png"))
    }

    @Test
    fun captureAttachingReattachStateSingleIndicator() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                    )
                    // Exact body of SwitchingLoadingPlaceholder (#750): a
                    // full-surface box with the canonical centered labelled
                    // spinner — the SOLE attach indicator (no top progress line).
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(color = PocketShellColors.Background)
                            .testTag(TMUX_SWITCHING_LOADING_TAG),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator.Spinner(
                            size = SpinnerSize.Medium,
                            label = "Attaching…",
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Attaching…").assertExists()
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-reattach-attaching-single-indicator.png"))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/tmux-connecting-states")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create tmux-connecting-states screenshot dir: ${dir.absolutePath}"
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
                    "Could not write tmux-connecting-states screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_CONNECTING_STATES_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SCREENSHOT_ROOT_TAG = "tmux:connecting-states-screenshot"
    }
}
