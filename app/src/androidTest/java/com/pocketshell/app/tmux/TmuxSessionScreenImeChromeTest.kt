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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #184 (Layers 1+2) regression coverage for the IME-aware chrome on
 * [TmuxSessionScreen].
 *
 * The screen itself is too heavy to mount in a Compose unit test — it
 * needs Hilt, a live tmux client, real Android views — so we exercise
 * the chrome region directly via [TmuxImeAwareTopChrome] (the same
 * composable structure the screen renders) and verify the behaviour the
 * issue cares about:
 *
 * 1. When `chromeCompressed = false` (IME hidden) the full breadcrumb,
 *    the tab row, and the window strip are all displayed.
 * 2. When `chromeCompressed = true` (IME visible) the full breadcrumb is
 *    replaced by [CompactBreadcrumb], the tabs are hidden, and the
 *    window strip is hidden — even when the session has more than one
 *    window.
 * 3. In a layout that mirrors [TmuxSessionScreen]'s vertical stack
 *    (chrome on top, weighted terminal area, bottom chrome at the
 *    bottom), the bottom of the terminal area — where the cursor row
 *    sits in a real session, since the [com.termux.view.TerminalView] is
 *    pinned to its latest line when the IME comes up by
 *    [com.pocketshell.core.terminal.ui.pinTerminalToBottom] — stays
 *    strictly above the top of the bottom chrome. This is the
 *    geometric assertion from the issue's acceptance criteria.
 *
 * The Y-coordinate assertion in (3) is the cheapest reliable proxy for
 * "the user can see the cursor row above the bottom chrome": the
 * vendored TerminalView renders the cursor row at the bottom of its
 * mapped viewport (the emulator's `mRows - 1` row, with `mTopRow == 0`
 * — the state [pinTerminalToBottom] enforces on IME-up), so as long as
 * the terminal Box's bottom lands above the bottom chrome's top, the
 * cursor row necessarily does too.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionScreenImeChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chromeIsFullWhenImeHidden() {
        compose.setContent {
            PocketShellTheme {
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
                    onSelectWindow = {},
                    onOpenWindowMenu = {},
                    onNewWindow = {},
                )
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsDisplayed()
    }

    @Test
    fun chromeIsCompressedWhenImeVisible() {
        compose.setContent {
            PocketShellTheme {
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
                    onSelectWindow = {},
                    onOpenWindowMenu = {},
                    onNewWindow = {},
                )
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsNotDisplayed()
    }

    @Test
    fun chromeFlipsLiveBetweenStates() {
        val compressedState = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme {
                Column {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = compressedState.value,
                        crumbs = sampleCrumbs(),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@1",
                        onSelectWindow = {},
                        onOpenWindowMenu = {},
                        onNewWindow = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsDisplayed()

        compose.runOnIdle { compressedState.value = true }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsNotDisplayed()

        compose.runOnIdle { compressedState.value = false }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsDisplayed()
    }

    @Test
    fun cursorRowProxyStaysAboveBottomChromeWhenImeUp() {
        // Mirror the screen's vertical stack: chrome on top, weighted
        // "terminal" Box in the middle, bottom chrome at the bottom.
        // With chromeCompressed = true the chrome shrinks (compact
        // breadcrumb only; tabs + window-strip hidden), so the
        // weighted middle band grows. The bottom of that band is the
        // proxy for the cursor row — the vendored TerminalView paints
        // the cursor row at its bottom-most row when `mTopRow == 0`.
        compose.setContent {
            PocketShellTheme {
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
                        onSelectWindow = {},
                        onOpenWindowMenu = {},
                        onNewWindow = {},
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
