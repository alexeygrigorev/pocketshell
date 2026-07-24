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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.connection.FailureReason
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.showsCalmFailure
import com.pocketshell.core.connection.showsCenteredLoader
import com.pocketshell.core.connection.surfaceOwnsPrimary
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
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

    // Issue #1326 (S3): build the fused view state the screen renders, exactly as
    // the screen wires it, so these screenshot proofs drive the SAME single state.
    private val sid = SessionId("host/work")

    private fun surfaceOf(
        reveal: RevealState,
        status: TmuxSessionViewModel.ConnectionStatus,
    ): SessionSurfaceState = sessionSurfaceState(reveal, connectionPhaseOf(status), sid)

    /**
     * Issue #1684 / #750 recurrence 5: the primary "Attaching…" indicator must
     * have one screen-level mount whose bounds do not change as a stale pager
     * page gives way to the attaching surface.
     *
     * This composes both REAL production sites that existed on the reported
     * path: [TmuxTerminalPager]'s non-target-pane mask and the screen-level
     * [SwitchingLoadingPlaceholder]. The screen-level mount is intentionally
     * present for both phases, as required by the fused [SessionSurfaceState]
     * contract. Before the fix, the pager also mounts another labelled
     * [SwitchingLoadingPlaceholder] in its shorter, vertically-unbounded page:
     * two semantic nodes with different bounds expose the same top-to-centre
     * relocation seen on-device. After the fix the pager is a neutral mask, so
     * exactly one node remains at identical bounds in both frames.
     */
    @Test
    fun connectingToAttaching_keepsOneStableIndicatorMount_issue1684() {
        val midAttach = mutableStateOf(false)
        val stalePane = TmuxPaneState(
            paneId = "%1684",
            windowId = "@1684",
            sessionId = "\$1684",
            title = "previous-session",
            terminalState = TerminalSurfaceState(),
        )

        compose.setContent {
            val pagerState = rememberPagerState(pageCount = { 1 })
            val panes = remember { listOf(stalePane) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PocketShellColors.Background)
                    .padding(top = 24.dp)
                    .testTag(SCREENSHOT_ROOT_TAG),
            ) {
                ConsolidatedTopChrome(
                    sessionName = "git-dap",
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
                        showReconnectButton = false,
                    ) {
                        if (!midAttach.value) {
                            TmuxTerminalPager(
                                unifiedPanes = panes,
                                pagerState = pagerState,
                                sessionName = "git-dap",
                                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                                engineCommands = emptySet(),
                                isAgentPane = false,
                                // Force the real non-target-pane masking branch.
                                sessionNameForUnifiedPane = { "previous-session" },
                                onTerminalSizeChanged = { _, _ -> },
                                onSurfaceError = { _, _ -> },
                                onRecreateSurface = {},
                                onUrlTap = {},
                                onFilePathTap = { _, _ -> },
                                onEngineCommandTap = {},
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(PocketShellColors.Background),
                            )
                        }
                    }
                    // The one stable screen-level mount required for both
                    // Connecting and Attaching.
                    SwitchingLoadingPlaceholder()
                }
            }
        }

        compose.waitForIdle()
        val earlyNodes = compose
            .onAllNodesWithText("Attaching…", useUnmergedTree = true)
            .fetchSemanticsNodes()
        captureFullDevice(File(artifactDir(), "issue-1684-early-connect.png"))
        assertEquals(
            "early connect must expose exactly one primary Attaching indicator",
            1,
            earlyNodes.size,
        )
        val earlyBounds = earlyNodes.single().boundsInRoot

        compose.runOnIdle { midAttach.value = true }
        compose.waitForIdle()
        val attachNodes = compose
            .onAllNodesWithText("Attaching…", useUnmergedTree = true)
            .fetchSemanticsNodes()
        captureFullDevice(File(artifactDir(), "issue-1684-mid-attach.png"))
        assertEquals(
            "mid attach must expose exactly one primary Attaching indicator",
            1,
            attachNodes.size,
        )
        assertEquals(
            "Connecting → Attaching must keep the indicator at one stable mount",
            earlyBounds,
            attachNodes.single().boundsInRoot,
        )
        assertIndeterminateIndicatorCount(1)
    }

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
                            // Issue #890: mid-attach the visible Reconnect button is
                            // hidden (the system is already trying), matching prod.
                            showReconnectButton = false,
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
    fun captureBeyondGraceReconnectSingleIndicator_reopenRepro() {
        // Issue #750 (4th occurrence — the maintainer's exact reopen state,
        // 2026-07-03). A BEYOND-GRACE reconnect re-dials through the controller's
        // `Connecting` state, which projects to [ConnectionStatus.Connecting] — so
        // the REAL screen renders the top [ConnectingProgressOverlay] ("Connecting
        // to host… / Still working, this may be slow…") WHILE the id-keyed
        // [RevealStateMachine] holds the terminal (`effectiveHidesTerminal == true`)
        // and the surface paints the centered "Attaching…" hold. Pre-fix the
        // [ConnectingProgressOverlay] was ungated on `status is Connecting`, so BOTH
        // rendered = TWO loaders (the reopened symptom).
        //
        // This mounts the REAL production [TmuxTopConnectingBanner] (the actual
        // single render site for both top banners, driven by the real
        // [primaryLoadingSurface] gate) ABOVE the REAL [SwitchingLoadingPlaceholder]
        // centered hold — the exact production composition. It is a genuine WIRING
        // guard: revert the gate and the top overlay renders again, pushing the
        // indeterminate-indicator count to 2 (RED).
        val connecting =
            TmuxSessionViewModel.ConnectionStatus.Connecting(
                host = "135.181.114.209",
                port = 22,
                user = "alexey",
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
                        sessionName = "git-pocketshell",
                        onBack = {},
                        onMore = {},
                    )
                    // The REAL top-banner render site. With `effectiveHidesTerminal =
                    // true` (the reconnect re-dial reality) the gate suppresses BOTH
                    // banners → this renders NOTHING.
                    TmuxTopConnectingBanner(
                        surfaceState = surfaceOf(RevealState.Seeding(sid, "git-pocketshell"), connecting),
                        surfaceOwnsPrimary = true,
                        sessionName = "git-pocketshell",
                        onCancelConnect = {},
                        onRetryNow = {},
                    )
                    // The REAL centered "Attaching…" hold — the SOLE loader that
                    // must remain.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        SwitchingLoadingPlaceholder()
                    }
                }
            }
        }
        compose.onNodeWithText("Attaching…").assertExists()
        // The top "Connecting to host…" banner (and its slow-hint line) MUST be
        // absent while the terminal is held — the fix.
        compose
            .onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        compose
            .onAllNodesWithTag(TMUX_CONNECTING_SLOW_HINT_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        // The centered "Attaching…" hold is present exactly once.
        compose
            .onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // EXACTLY ONE animated indicator: the centered "Attaching…" spinner. Pre-fix
        // the ungated top overlay's linear bar added a second → count 2 (the RED
        // reproduction of the maintainer's beyond-grace reconnect symptom).
        assertIndeterminateIndicatorCount(1)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-beyond-grace-reconnect-single-indicator.png"))
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
                        user = reconnecting.user,
                        host = reconnecting.host,
                        port = reconnecting.port,
                        attempt = reconnecting.attempt,
                        maxAttempts = reconnecting.maxAttempts,
                        retryDelayMs = reconnecting.retryDelayMs,
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
                            // Issue #890: a steady Reconnecting state is still an
                            // in-progress reconnect, so the visible button is hidden.
                            showReconnectButton = false,
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
    fun captureFailedStateSingleReconnectControlNoSpinner_screenshotA() {
        // Issue #1322 (coordinator screenshot A): the Failed/Disconnected state must
        // render EXACTLY ONE reconnect affordance — the calm [FailedConnectionRow]
        // "Tap to reconnect" band — and NO animated spinner. The maintainer's
        // screenshot showed TWO reconnect controls at once (the band link AND the
        // bottom surface "Reconnect" button) plus an "Attaching…" spinner.
        //
        // This mounts the REAL production composition with `showReconnectButton`
        // driven by the REAL [surfaceReconnectButtonVisible] gate for a `Failed`
        // status. RED on base: the #890 gate did not exclude `Failed`, so the bottom
        // [TMUX_SURFACE_RECONNECT_BUTTON_TAG] button rendered on top of the band →
        // TWO reconnect controls (the assertCountEquals(0) below fails).
        val failed = TmuxSessionViewModel.ConnectionStatus.Failed(
            "Connection lost. Tap Reconnect to retry.",
        )
        // Issue #1521 (AC2, gated): tapping the band's "Reconnect" control MUST invoke
        // the existing reconnect action. This tap→reconnect assertion is asserted HERE,
        // inside the CI-wired [TmuxConnectingStatesScreenshotTest]
        // (`scripts/ci-journey-suite.sh` → emulator-journey), so the affordance is
        // guarded per-push (not only in the un-gated component test).
        var reconnectCalls = 0
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
                        message = failed.message,
                        onReconnect = { reconnectCalls += 1 },
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
                            // The REAL gate — false for Failed (the band owns the
                            // single affordance), so the bottom button is suppressed.
                            showReconnectButton = surfaceReconnectButtonVisible(
                                surfaceOf(RevealState.Error(sid, "claude-main", retrying = false, reason = FailureReason.Unreachable(true)), failed),
                            ),
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
        // Issue #1521: the band's prominent, always-present "Reconnect" button is the
        // SOLE reconnect affordance (replacing the old borderless "Tap to reconnect"
        // text link). Exactly one on-screen reconnect control.
        compose.onNodeWithText("Reconnect").assertExists()
        compose.onNodeWithText("Tap to reconnect").assertDoesNotExist()
        compose
            .onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        // The DUPLICATE bottom surface "Reconnect" button MUST be gone (screenshot A).
        compose
            .onAllNodesWithTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // ZERO animated indicators — the settled Failed state must not spin.
        assertIndeterminateIndicatorCount(0)
        // Issue #1521 (AC2): the affordance is not just present — tapping it routes to
        // the SAME existing reconnect action. RED without the always-present button
        // (nothing tappable → no callback); GREEN with the fix.
        compose.onNodeWithTag(TMUX_SESSION_RECONNECT_TAG).performClick()
        assertEquals(
            "Tapping the disconnected-band Reconnect button must invoke the reconnect action",
            1,
            reconnectCalls,
        )
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-failed-single-reconnect-control.png"))
    }

    @Test
    fun captureHardRevealFailureCalmPlaceholderNoAttaching_1321Repro() {
        // Issue #1322 (defect A / the #1321 screenshot): a past-grace transport drop
        // yields RevealState.Error(retrying=false) → the terminal is held, but the
        // surface must paint the CALM [RevealFailurePlaceholder] (no spinner), NEVER
        // the centered "Attaching…" hold. RED on base: every non-Live reveal
        // collapsed to the "Attaching…" [SwitchingLoadingPlaceholder] — the spinner
        // that contradicted the "Disconnected" pill + "Tap to reconnect" band.
        val failed = TmuxSessionViewModel.ConnectionStatus.Failed(
            "Connection lost. Tap Reconnect to retry.",
        )
        // The #1321 exact state: a past-grace transport drop → RevealState.Error
        // (retry exhausted) fused with the Failed phase → SessionSurfaceState.Failed.
        val hardFailureState = surfaceOf(
            RevealState.Error(sid, "claude-main", retrying = false, reason = FailureReason.Unreachable(true)),
            failed,
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
                    FailedConnectionRow(
                        message = failureReasonSentence((hardFailureState as SessionSurfaceState.Failed).reason),
                        onReconnect = {},
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
                            showReconnectButton = surfaceReconnectButtonVisible(hardFailureState),
                            // The surface centered loader flag driven by the REAL
                            // decision: a settled hard failure is NOT a loader.
                            surfaceShowsCenteredLoader = hardFailureState.showsCenteredLoader(panesEmpty = true),
                        ) {
                            // The REAL surface branch decision: a hard reveal failure
                            // paints the calm placeholder, NEVER "Attaching…".
                            if (hardFailureState.showsCalmFailure) {
                                RevealFailurePlaceholder()
                            } else {
                                SwitchingLoadingPlaceholder()
                            }
                        }
                    }
                }
            }
        }
        // The calm failure placeholder is shown; the "Attaching…" hold is NOT.
        compose
            .onAllNodesWithTag(TMUX_REVEAL_FAILURE_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose
            .onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        // Issue #1521 (AC3, gated): the calm center placeholder now reads a bare
        // "Disconnected." status — the misleading "tap to reconnect above." pointer
        // (which gestured at a non-obvious target) is GONE, since the recovery
        // affordance is the prominent, always-present "Reconnect" button in the band.
        // Asserted here in the CI-wired [TmuxConnectingStatesScreenshotTest] so the copy
        // change is guarded per-push. RED on base: the placeholder said
        // "Disconnected — tap to reconnect above." → the substring assert below fails.
        compose.onNodeWithText("Disconnected.").assertExists()
        compose
            .onAllNodesWithText("tap to reconnect above", substring = true)
            .assertCountEquals(0)
        // Exactly ONE reconnect control (the band) and ZERO spinners.
        compose
            .onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose
            .onAllNodesWithTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.waitForIdle()
        assertIndeterminateIndicatorCount(0)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-hard-reveal-failure-calm-placeholder.png"))
    }

    @Test
    fun captureReconnectingWaitingForPanesSingleIndicator_screenshotB() {
        // Issue #1322 (coordinator screenshot B, #750 regression): a Reconnecting
        // state where the reveal is live (`effectiveHidesTerminal == false`) but no
        // panes have arrived yet stacked THREE indicators — the top
        // [ReconnectingProgressRow] bar, the centered "waiting for tmux panes…" ring,
        // and the pull box spinner. The waiting ring is the SOLE loader, so the top
        // banner AND the box spinner are suppressed via the REAL broadened
        // [surfaceOwnsPrimaryIndicator] / [surfaceShowsCenteredLoader] gates. RED on
        // base: surfaceShowsCenteredLoader only counted `effectiveHidesTerminal`, so
        // all three fired → 3 indeterminate nodes.
        val reconnecting = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
            host = "hetzner.example",
            port = 22,
            user = "alex",
            attempt = 2,
            maxAttempts = 3,
            retryDelayMs = 4_000L,
            reason = "Reconnecting…",
        )
        // Reveal LIVE (terminal not held) but no panes yet → the "waiting for tmux
        // panes…" ring is the SOLE loader; the top banner + box spinner are suppressed.
        val waitingState = surfaceOf(RevealState.Live(sid, "claude-main", panes = emptyList()), reconnecting)
        val ownsPrimary = waitingState.surfaceOwnsPrimary(panesEmpty = true)
        val centeredLoader = waitingState.showsCenteredLoader(panesEmpty = true)
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
                    // The REAL top-banner render site, driven by the broadened gate —
                    // suppressed because the surface owns the waiting ring.
                    TmuxTopConnectingBanner(
                        surfaceState = waitingState,
                        surfaceOwnsPrimary = ownsPrimary,
                        sessionName = "claude-main",
                        onCancelConnect = {},
                        onRetryNow = {},
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
                            // The box spinner is suppressed because the surface shows
                            // the waiting ring (the broadened #1322 flag).
                            surfaceShowsCenteredLoader = centeredLoader,
                            showReconnectButton = surfaceReconnectButtonVisible(waitingState),
                        ) {
                            // The "waiting for tmux panes…" ring (EmptyPanesPlaceholder
                            // body) — the SOLE loader for this state.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
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
            }
        }
        compose.onNodeWithText("waiting for tmux panes…").assertExists()
        // The top Reconnecting band must be suppressed (no "Retry now" line stacked).
        compose.onAllNodesWithText("Retry now").assertCountEquals(0)
        compose
            .onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.waitForIdle()
        // EXACTLY ONE animated indicator: the centered "waiting for tmux panes…"
        // ring. Pre-fix the top bar + box spinner stacked two more → count 3.
        assertIndeterminateIndicatorCount(1)
        SystemClock.sleep(300)
        captureFullDevice(File(artifactDir(), "tmux-reconnecting-waiting-single-indicator.png"))
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
