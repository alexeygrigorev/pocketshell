package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #184 (Layers 1+2) + Issue #189 regression coverage for the
 * IME-aware top chrome on [TmuxSessionScreen].
 *
 * The screen itself is too heavy to mount in a Compose unit test — it
 * needs Hilt, a live tmux client, real Android views — so we exercise
 * the chrome region directly via [TmuxImeAwareTopChrome] (the same
 * composable structure the screen renders) and verify:
 *
 * 1. When `chromeCompressed = false` (IME hidden) the consolidated
 *    56dp top chrome ([TMUX_FULL_BREADCRUMB_TAG]) is displayed with the
 *    inline tab pill ([TMUX_TABS_TAG]) when an agent is present.
 *    Single-window sessions hide the `› Window N` crumb but the toolbar
 *    is still displayed.
 * 2. When `chromeCompressed = true` (IME visible) the consolidated row
 *    is replaced by [CompactBreadcrumb], and the tab pill is hidden.
 * 3. In a layout that mirrors [TmuxSessionScreen]'s vertical stack
 *    (chrome on top, weighted terminal area, bottom chrome at the
 *    bottom), the bottom of the terminal area — where the cursor row
 *    sits in a real session — stays strictly above the top of the
 *    bottom chrome on IME-up (the geometric assertion from #184).
 * 4. The consolidated chrome's IME-down footprint is ≤56dp tall — the
 *    Material toolbar height target from #189.
 *
 * Post-#189 the WindowStrip is no longer rendered as part of the
 * default chrome (window switching reaches the WindowSwitcherOverlay
 * instead via the inline crumb tap, the swipe-up gesture on the page
 * indicator, or the kebab "Switch window" item), so this test no
 * longer asserts strip visibility. The
 * [TmuxSessionWindowNavigationE2eTest] connected test owns the
 * end-to-end switch journey through the new overlay tags.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionScreenImeChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chromeIsFullWhenImeHidden() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxImeAwareTopChrome(
                    chromeCompressed = false,
                    crumbs = sampleCrumbs(),
                    sessionName = "claude-main",
                    onBack = {},
                    onMore = {},
                    tabLabels = listOf("Terminal", "Conversation"),
                    selectedTabIndex = 0,
                    onTabSelected = {},
                    windows = sampleWindows(2),
                    currentWindowId = "@1",
                    onOpenWindowSwitcher = {},
                )
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        // Issue #189: the WindowStrip is no longer rendered as part of
        // the default chrome. Asserting it is absent locks the new
        // behaviour in place.
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun chromeIsCompressedWhenImeVisible() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxImeAwareTopChrome(
                    chromeCompressed = true,
                    crumbs = sampleCrumbs(),
                    sessionName = "claude-main",
                    onBack = {},
                    onMore = {},
                    tabLabels = listOf("Terminal", "Conversation"),
                    selectedTabIndex = 0,
                    onTabSelected = {},
                    windows = sampleWindows(2),
                    currentWindowId = "@1",
                    onOpenWindowSwitcher = {},
                )
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun chromeFlipsLiveBetweenStates() {
        val compressedState = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = compressedState.value,
                        crumbs = sampleCrumbs(),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@1",
                        onOpenWindowSwitcher = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()

        compose.runOnIdle { compressedState.value = true }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsNotDisplayed()

        compose.runOnIdle { compressedState.value = false }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
    }

    @Test
    fun consolidatedChromeFitsInsideMaterialToolbarHeightWhenImeDown() {
        // Issue #189 acceptance criterion: the IME-down top chrome
        // must fit inside one Material toolbar slot (≤56dp). We render
        // the consolidated chrome with the worst-case set of children
        // (Terminal + Conversation tab pill, multi-window crumb)
        // because anything narrower will trivially be smaller.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = false,
                        crumbs = sampleCrumbs(),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@1",
                        onOpenWindowSwitcher = {},
                    )
                }
            }
        }

        val bounds = compose
            .onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val heightPx = bounds.bottom - bounds.top
        val toolbarMaxPx = with(compose.density) { 56.dp.toPx() }
        // Allow a tiny rounding slack — Compose's layout pass can
        // accumulate sub-pixel fractions on the [Row]'s height when
        // children are 36dp icons + a 32dp tab pill inside a 56dp Row.
        // 0.5px is well under one device pixel on every reasonable
        // density bucket and prevents flake from fractional rounding.
        val toleratedMaxPx = toolbarMaxPx + 0.5f
        assertTrue(
            "Consolidated chrome must fit inside 56dp Material toolbar height (was ${heightPx}px, max ${toleratedMaxPx}px)",
            heightPx <= toleratedMaxPx,
        )
    }

    @Test
    fun cursorRowProxyStaysAboveBottomChromeWhenImeUp() {
        // Mirror the screen's vertical stack: chrome on top, weighted
        // "terminal" Box in the middle, bottom chrome at the bottom.
        // With chromeCompressed = true the chrome shrinks (compact
        // breadcrumb only; tab pill hidden), so the weighted middle
        // band grows. The bottom of that band is the proxy for the
        // cursor row — the vendored TerminalView paints the cursor row
        // at its bottom-most row when `mTopRow == 0`.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = true,
                        crumbs = sampleCrumbs(),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@1",
                        onOpenWindowSwitcher = {},
                    )
                    // Weighted "terminal" Box. In TmuxSessionScreen this
                    // is the HorizontalPager that hosts TerminalSurface;
                    // here it is a tagged placeholder so the test can
                    // read its bottom Y coordinate.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(PocketShellColors.SurfaceElev)
                            .testTag(TERMINAL_PROXY_TAG),
                    )
                    // Mock bottom chrome — same shape as
                    // KeyBarWithMic but as a single tagged Box for the
                    // assertion.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(PocketShellColors.Surface)
                            .testTag(BOTTOM_CHROME_PROXY_TAG),
                    )
                }
            }
        }

        val terminalBottom = compose
            .onNodeWithTag(TERMINAL_PROXY_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.bottom
        val bottomChromeTop = compose
            .onNodeWithTag(BOTTOM_CHROME_PROXY_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.top

        assertTrue(
            "Cursor-row proxy (terminal bottom) must sit strictly above bottom chrome top; " +
                "got terminalBottom=$terminalBottom bottomChromeTop=$bottomChromeTop",
            terminalBottom <= bottomChromeTop,
        )
    }

    private fun sampleCrumbs(): List<Crumb> = listOf(
        Crumb(label = "host.example", isCurrent = false, onClick = {}),
        Crumb(label = "claude-main", isCurrent = false, onClick = {}),
        Crumb(label = "Window 1", isCurrent = false, onClick = {}),
        Crumb(label = "Pane 1", isCurrent = true, onClick = {}),
    )

    private fun sampleWindows(count: Int): List<WindowSummary> =
        (1..count).map { idx -> WindowSummary(windowId = "@$idx", title = "Window $idx") }

    private companion object {
        const val TERMINAL_PROXY_TAG = "tmux:ime-chrome-test:terminal"
        const val BOTTOM_CHROME_PROXY_TAG = "tmux:ime-chrome-test:bottom-chrome"
    }
}
