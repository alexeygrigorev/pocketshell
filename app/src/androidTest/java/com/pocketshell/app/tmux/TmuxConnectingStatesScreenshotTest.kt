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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
 * Issue #750 (3rd occurrence — class-wide single-indicator regression guard).
 *
 * The maintainer's recurring symptom is TWO loading indicators stacked on the
 * tmux reconnect/reattach screen. It has now shipped three times, so this test
 * is the durable, CI-wired (`scripts/ci-journey-suite.sh` → emulator-journey)
 * guard that EVERY non-Connected state shows EXACTLY ONE animated
 * (indeterminate-progress) indicator — never two.
 *
 * The canonical single indicator PER state (after the class-wide #750 fix):
 *  - **Attaching / reattach hold** (`effectiveHidesTerminal == true`): the
 *    centered "Attaching…" [SwitchingLoadingPlaceholder] spinner. The top
 *    [ReconnectingProgressRow] is suppressed by [shouldShowReconnectingProgressRow]
 *    AND the [PullToRefreshBox] box spinner is suppressed via
 *    `surfaceShowsCenteredLoader = true`.
 *  - **Steady Reconnecting** (`effectiveHidesTerminal == false`): the
 *    [SessionSurfaceReconnectWrapper]'s [PullToRefreshBox] circular spinner. The
 *    [ReconnectingProgressRow] band is still shown (its text + Retry now / Cancel
 *    actions) but NO LONGER carries its own linear bar — so the box spinner is
 *    the SOLE animated indicator. (Pre-fix the band's linear bar stacked a second
 *    indeterminate node on top of the box spinner — the reviewer-caught failure.)
 *  - **Disconnected** (no active reconnect): the calm [FailedConnectionRow]
 *    "Tap to reconnect" band is the sole affordance and there is intentionally
 *    NO animated spinner (the state is idle, waiting for the user) — assert ZERO
 *    indeterminate-progress nodes so a future spinner regression is caught.
 *
 * Each capture HARD-asserts the indeterminate-progress count via
 * [SemanticsProperties.ProgressBarRangeInfo] == [ProgressBarRangeInfo.Indeterminate]
 * (a bare `assertIsDisplayed()` would not catch a duplicate), wires the REAL
 * production composables ([ReconnectingProgressRow], [FailedConnectionRow],
 * [SessionSurfaceReconnectWrapper]) with the exact production flags, and writes a
 * full-device screenshot for visual review.
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
        // EXACTLY ONE animated indicator — the centered spinner.
        assertIndeterminateIndicatorCount(1)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-connecting-waiting-for-panes.png"))
    }

    @Test
    fun captureAttachingReattachStateSingleIndicator() {
        // Attaching / reattach hold: the session surface is wrapped in the REAL
        // #823 [SessionSurfaceReconnectWrapper] (pull-to-reconnect [PullToRefreshBox])
        // AND the surface content paints the centered "Attaching…"
        // [SwitchingLoadingPlaceholder] spinner. This is the state where
        // `pullToReconnectActive` (`!sessionLive && canReconnect`) AND
        // `isReconnecting` AND `surfaceShowsCenteredLoader` (`effectiveHidesTerminal`)
        // are ALL true. The box spinner is suppressed via
        // `surfaceShowsCenteredLoader = true` while the pull GESTURE stays mounted
        // (#823 preserved), so the centered "Attaching…" spinner is the SOLE
        // animated indicator.
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        SessionSurfaceReconnectWrapper(
                            pullToReconnectActive = true,
                            isReconnecting = true,
                            onReconnect = {},
                            surfaceShowsCenteredLoader = true,
                        ) {
                            // The centered "Attaching…" hold
                            // (SwitchingLoadingPlaceholder body) — the SOLE
                            // indicator that must remain.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
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
            }
        }
        compose.onNodeWithText("Attaching…").assertExists()
        // #823's pull-to-reconnect wrapper is still mounted (gesture preserved).
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        // The centered "Attaching…" hold is present exactly once.
        compose
            .onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // EXACTLY ONE animated indicator: the centered "Attaching…" spinner. The
        // box spinner is suppressed; a regression that re-runs it (or re-adds a top
        // linear bar) would push this count to 2 and fail.
        assertIndeterminateIndicatorCount(1)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-reattach-attaching-single-indicator.png"))
    }

    @Test
    fun captureSteadyReconnectingSurfaceSingleIndicator() {
        // STEADY reconnecting state (`effectiveHidesTerminal == false`): the REAL
        // production composition mounts the [ReconnectingProgressRow] band (text +
        // Retry now / Cancel) ON TOP of the [SessionSurfaceReconnectWrapper] whose
        // [PullToRefreshBox] runs its circular spinner (`isReconnecting = true`,
        // `surfaceShowsCenteredLoader` false). Pre-#750-class-wide-fix the band ALSO
        // carried a linear bar → TWO indeterminate-progress nodes (the reviewer's
        // deterministic failure). After the fix the band has NO bar, so the box
        // spinner is the SOLE animated indicator — exactly ONE.
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
                    // The REAL under-header reconnect band — text + Retry now /
                    // Cancel, no linear bar after the #750 class-wide fix.
                    ReconnectingProgressRow(
                        status = reconnecting,
                        sessionLabel = "tmux claude-main",
                        onRetryNow = {},
                        onCancel = {},
                    )
                    // The #823 pull-to-reconnect surface wrapper around the
                    // dropped-session placeholder. In the steady state the box's own
                    // circular spinner IS shown (driven by `isReconnecting = true`)
                    // as the SOLE animated indicator.
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
                                // A STATIC hint (no spinner) — the box's own
                                // pull-to-refresh indicator is the sole spinner.
                                androidx.compose.material3.Text("Pull down to reconnect…")
                            }
                        }
                    }
                }
            }
        }
        // The band's text + actions are present (band kept, only the bar dropped).
        compose.onNodeWithText("Retry now").assertExists()
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // EXACTLY ONE animated indicator: the [PullToRefreshBox] circular spinner.
        // Pre-fix the band's linear bar added a second → count 2 (the failure).
        assertIndeterminateIndicatorCount(1)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-steady-reconnecting-single-indicator.png"))
    }

    @Test
    fun captureDisconnectedSurfaceNoSpinner() {
        // DISCONNECTED / idle state: there is NO active reconnect, so the surface
        // shows the calm [FailedConnectionRow] "Tap to reconnect" affordance and
        // NO animated spinner (a spinner would falsely imply work in flight). The
        // pull wrapper runs with `isReconnecting = false`, so its box spinner is
        // also off. Assert ZERO indeterminate-progress nodes so a regression that
        // re-introduces a spinner in the idle disconnected state is caught.
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
                    FailedConnectionRow(
                        message = "Connection lost — tap to reconnect",
                        onReconnect = {},
                        canReconnect = true,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        SessionSurfaceReconnectWrapper(
                            pullToReconnectActive = true,
                            isReconnecting = false,
                            onReconnect = {},
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color = PocketShellColors.Surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Text("Pull down to reconnect…")
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Tap to reconnect").assertExists()
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // ZERO animated indicators — the idle disconnected state must not spin.
        assertIndeterminateIndicatorCount(0)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-disconnected-no-spinner.png"))
    }

    /**
     * HARD single-indicator assertion: counts EVERY indeterminate-progress
     * semantics node on screen (linear bar + circular spinner alike) and asserts
     * the expected total. This is the load-bearing #750 invariant — it catches a
     * second stacked indicator that a bare `assertIsDisplayed()` would miss.
     */
    private fun assertIndeterminateIndicatorCount(expected: Int) {
        compose
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
                useUnmergedTree = true,
            )
            .assertCountEquals(expected)
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
