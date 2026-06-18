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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
        // #750 (post-#766 regression): a reconnect/reattach projects
        // [ConnectionStatus.Reconnecting] (the top [ReconnectingProgressRow] bar)
        // WHILE the id-keyed reveal machine holds the terminal — the centered
        // "Attaching…" spinner. This mounts the screen's REAL two-render-site
        // layout (the actual [ReconnectingProgressRow] guarded by the screen's
        // real [shouldShowReconnectingProgressRow] gate, plus the centered
        // spinner) so the capture proves the GATE — not a copied body — leaves a
        // single indicator. `effectiveHidesTerminal = true` is the reconnect/
        // reattach reality (reveal machine maps Reattaching/Reconnecting →
        // Seeding), so the top bar must be suppressed.
        val reconnecting = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
            host = "hetzner.example",
            port = 22,
            user = "alex",
            attempt = 1,
            maxAttempts = 3,
            retryDelayMs = 0L,
            reason = "Reconnecting…",
        )
        val effectiveHidesTerminal = true
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
                    // The screen's REAL top-bar render site, behind the REAL gate.
                    // With the terminal held this evaluates false → no top line.
                    if (shouldShowReconnectingProgressRow(reconnecting, effectiveHidesTerminal)) {
                        ReconnectingProgressRow(
                            status = reconnecting,
                            sessionLabel = "tmux claude-main",
                            onRetryNow = {},
                            onCancel = {},
                        )
                    }
                    // The centered "Attaching…" hold (SwitchingLoadingPlaceholder
                    // body) — the SOLE indicator that should remain visible.
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
        // Hard wiring assertion: the top under-header progress line is ABSENT
        // while the centered hold is up — exactly one indicator on screen.
        compose
            .onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        compose
            .onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-reattach-attaching-single-indicator.png"))
    }

    @Test
    fun capturePullToReconnectDisconnectedSurface() {
        // Issue #823 (Slice 1): screenshot evidence that the disconnected /
        // "Reconnecting" session surface carries BOTH the existing visible
        // reconnect controls (the amber [ReconnectingProgressRow] "Retry now"
        // band and the calm [FailedConnectionRow] "Tap to reconnect" band) AND
        // the new pull-to-reconnect gesture wrapper ([SessionSurfaceReconnectWrapper]
        // with `pullToReconnectActive = true`) around the dropped-session
        // placeholder. This reproduces the maintainer's "dropped session, give me
        // a way to reconnect" state with every reconnect affordance present.
        val reconnecting = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
            host = "hetzner.example",
            port = 22,
            user = "alex",
            attempt = 2,
            maxAttempts = 3,
            retryDelayMs = 4_000L,
            reason = "Reconnecting…",
        )
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
                    // The existing visible reconnect controls (#685 / #720) that
                    // remain available in a non-Connected state — the amber band's
                    // "Retry now" and the calm "Tap to reconnect" band.
                    ReconnectingProgressRow(
                        status = reconnecting,
                        sessionLabel = "tmux claude-main",
                        onRetryNow = {},
                        onCancel = {},
                    )
                    FailedConnectionRow(
                        message = "Connection lost — tap to reconnect",
                        onReconnect = {},
                        canReconnect = true,
                    )
                    // The #823 pull-to-reconnect surface wrapper around the
                    // dropped-session placeholder. Pulling down here fires the same
                    // reconnect() entrypoint the bands above call.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        SessionSurfaceReconnectWrapper(
                            pullToReconnectActive = true,
                            isReconnecting = true,
                            onReconnect = {},
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color = PocketShellColors.Surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator.Spinner(
                                    size = SpinnerSize.Medium,
                                    label = "Pull down to reconnect…",
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Retry now").assertExists()
        compose.onNodeWithText("Tap to reconnect").assertExists()
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-pull-to-reconnect-disconnected.png"))
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
