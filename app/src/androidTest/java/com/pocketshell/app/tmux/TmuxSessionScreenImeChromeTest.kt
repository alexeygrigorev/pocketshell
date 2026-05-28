package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #184 (Layers 1+2) + Issues #189 / #192 regression coverage for
 * the IME-aware top chrome on [TmuxSessionScreen].
 *
 * The screen itself is too heavy to mount in a Compose unit test — it
 * needs Hilt, a live tmux client, real Android views — so we exercise
 * the chrome region directly via the [ImeAwareChromeUnderTest] helper
 * below, which reproduces the exact `AnimatedVisibility` swap the screen
 * renders inline (consolidated chrome ↔ compact breadcrumb keyed on
 * IME visibility). We verify:
 *
 * 1. When `chromeCompressed = false` (IME hidden) the consolidated
 *    56dp top chrome ([TMUX_FULL_BREADCRUMB_TAG]) is displayed.
 * 2. When `chromeCompressed = true` (IME visible) the consolidated row
 *    is replaced by the compact breadcrumb ([TMUX_COMPACT_BREADCRUMB_TAG]).
 * 3. In a layout that mirrors [TmuxSessionScreen]'s vertical stack
 *    (chrome on top, weighted terminal area, bottom chrome at the
 *    bottom), the bottom of the terminal area — where the cursor row
 *    sits in a real session — stays strictly above the top of the
 *    bottom chrome on IME-up (the geometric assertion from #184).
 * 4. The consolidated chrome's IME-down footprint is ≤56dp tall — the
 *    Material toolbar height target.
 *
 * Per #192 the WindowStrip and the Terminal/Conversation toggle are
 * rendered by [TmuxSessionScreen] directly below the IME-aware chrome —
 * the strip is the primary window switcher and the toggle is per-window.
 * This test therefore asserts neither the strip nor the tab pill appear
 * inside the chrome region. The [TmuxSessionWindowNavigationE2eTest]
 * connected test owns the end-to-end strip-driven switch + kill journey.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionScreenImeChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chromeIsFullWhenImeHidden() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ImeAwareChromeUnderTest(chromeCompressed = false)
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsNotDisplayed()
        // Issue #192: the strip and the Terminal/Conversation toggle are
        // rendered by the screen, NOT this chrome region. Asserting they
        // are absent here locks the IA split in place.
        compose.onNodeWithTag(TMUX_TABS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun chromeIsCompressedWhenImeVisible() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ImeAwareChromeUnderTest(chromeCompressed = true)
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_TABS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertDoesNotExist()
    }

    @Test
    fun chromeFlipsLiveBetweenStates() {
        val compressedState = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column {
                    ImeAwareChromeUnderTest(chromeCompressed = compressedState.value)
                }
            }
        }

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()

        compose.runOnIdle { compressedState.value = true }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_COMPACT_BREADCRUMB_TAG).assertIsDisplayed()

        compose.runOnIdle { compressedState.value = false }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG).assertIsDisplayed()
    }

    @Test
    fun consolidatedChromeFitsInsideMaterialToolbarHeightWhenImeDown() {
        // Acceptance criterion: the IME-down header chrome must fit
        // inside one Material toolbar slot (≤56dp).
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column {
                    ImeAwareChromeUnderTest(chromeCompressed = false)
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
        // children are 36dp icons inside a 56dp Row. 0.5px is well under
        // one device pixel on every reasonable density bucket and
        // prevents flake from fractional rounding.
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
        // breadcrumb only), so the weighted middle band grows. The
        // bottom of that band is the proxy for the cursor row — the
        // vendored TerminalView paints the cursor row at its bottom-most
        // row when `mTopRow == 0`.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ImeAwareChromeUnderTest(chromeCompressed = true)
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

    /**
     * Reproduces the IME-aware top-chrome region exactly as
     * [TmuxSessionScreen] renders it inline today (Issues #189 / #192 /
     * #184): the consolidated 56dp chrome and the 40dp compact breadcrumb
     * swapped via [AnimatedVisibility] keyed on IME visibility, with the
     * same 200ms motion tokens. Kept local to the test because the screen
     * inlines this structure rather than exposing a wrapper composable.
     */
    @Composable
    private fun ImeAwareChromeUnderTest(chromeCompressed: Boolean) {
        val animEnter = expandVertically(
            animationSpec = tween(durationMillis = 200),
        ) + fadeIn(
            animationSpec = tween(durationMillis = 200),
        )
        val animExit = shrinkVertically(
            animationSpec = tween(durationMillis = 200),
        ) + fadeOut(
            animationSpec = tween(durationMillis = 200),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(visible = !chromeCompressed, enter = animEnter, exit = animExit) {
                ConsolidatedTopChrome(
                    hostLabel = "host.example",
                    sessionName = "claude-main",
                    onBack = {},
                    onMore = {},
                    modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
                )
            }
            AnimatedVisibility(visible = chromeCompressed, enter = animEnter, exit = animExit) {
                CompactBreadcrumb(
                    sessionName = "claude-main",
                    onBack = {},
                    onMore = {},
                    modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
                )
            }
        }
    }

    private companion object {
        const val TERMINAL_PROXY_TAG = "tmux:ime-chrome-test:terminal"
        const val BOTTOM_CHROME_PROXY_TAG = "tmux:ime-chrome-test:bottom-chrome"
    }
}
