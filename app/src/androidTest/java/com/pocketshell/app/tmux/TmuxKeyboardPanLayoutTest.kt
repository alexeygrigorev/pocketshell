package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #457 (Part 1): deterministic layout proof that the terminal column's
 * PAN approach keeps the terminal region's MEASURED HEIGHT constant when the
 * soft keyboard appears — i.e. it pans, it does not resize.
 *
 * Why this matters: the embedded vendored [com.termux.view.TerminalView]
 * recomputes its grid (rows = viewHeight / fontLineSpacing) in `onSizeChanged`
 * and pushes the new grid to tmux via `resizeRemotePty`, causing a full
 * reflow + redraw. The fix keeps the terminal region's pixel height stable and
 * translates the whole column up by the keyboard overlap instead. If the
 * terminal region's measured height stays identical across an IME flip, the
 * TerminalView never resizes and tmux never reflows.
 *
 * The test mounts the exact vertical stack [TmuxSessionScreen] uses — a
 * full-size [Column] holding a weighted "terminal" Box above a fixed bottom
 * "key bar" — under two modifiers:
 *
 *  - [panModifier]: `graphicsLayer { translationY = -overlap }` (the new
 *    behaviour). The terminal region height must NOT change between IME-down
 *    (overlap 0) and IME-up (overlap > 0).
 *  - [imePaddingControl]: the OLD behaviour, simulated by shrinking the column
 *    with a bottom pad equal to the overlap. The terminal region height MUST
 *    change (shrink) — this is the regression the fix removes, asserted here
 *    so the contrast is explicit and the test would fail loudly if someone
 *    reverted to padding.
 *
 * Fully deterministic: no Docker, no live tmux, no soft keyboard — the IME
 * overlap is driven directly so the assertion is stable on any emulator.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyboardPanLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panKeepsTerminalRegionHeightConstantAcrossImeFlip() {
        var imeOverlapPx by mutableStateOf(0)
        compose.setContent {
            PocketShellTheme {
                TerminalColumnUnderTest(
                    modifier = Modifier
                        .fillMaxSize()
                        // The pan: translate up by the keyboard overlap. This
                        // is exactly what TmuxSessionScreen applies on the root
                        // Column.
                        .graphicsLayer { translationY = -imeOverlapPx.toFloat() },
                )
            }
        }

        val heightImeDown = terminalRegionHeight()

        // Raise the "keyboard": a large overlap (≈ a real soft keyboard).
        compose.runOnIdle { imeOverlapPx = 800 }
        compose.waitForIdle()

        val heightImeUp = terminalRegionHeight()

        assertEquals(
            "Terminal region height must be IDENTICAL across the IME flip when " +
                "panning (no resize -> no tmux reflow). down=$heightImeDown up=$heightImeUp",
            heightImeDown,
            heightImeUp,
            0.5f,
        )
    }

    @Test
    fun imePaddingControlShrinksTerminalRegion() {
        // Contrast case: the OLD `.imePadding()`-style behaviour shrinks the
        // column, so the weighted terminal region loses height. This proves the
        // assertion above is meaningful (a no-op layout would pass both).
        var bottomPadPx by mutableStateOf(0)
        compose.setContent {
            PocketShellTheme {
                val padDp = with(compose.density) { bottomPadPx.toDp() }
                TerminalColumnUnderTest(
                    modifier = Modifier
                        .fillMaxSize()
                        // Simulate `.imePadding()` deterministically by padding
                        // the bottom by the same overlap the keyboard would
                        // consume.
                        .padding(bottom = padDp),
                )
            }
        }

        val heightImeDown = terminalRegionHeight()
        compose.runOnIdle { bottomPadPx = 800 }
        compose.waitForIdle()
        val heightImeUp = terminalRegionHeight()

        assertTrue(
            "Sanity: the padding (old) approach MUST shrink the terminal region " +
                "(down=$heightImeDown up=$heightImeUp); if it does not, the constant-" +
                "height assertion in the pan test is vacuous.",
            heightImeUp < heightImeDown - 1f,
        )
    }

    @Test
    fun tmuxImeAccessoryKeepsUsableHeight() {
        // Issue #755 (PR2, D22 hard-cut): the terminal hotkey key bar moved out
        // of `TmuxTerminalBottomControls` (which the keyboard occluded) and INTO
        // the composer's inset-anchored column. This guards that the bar layout
        // itself — the SAME `tmuxKeyBarLayout` the composer now renders — keeps a
        // usable tap height and is not crushed to a thin strip.
        compose.setContent {
            PocketShellTheme {
                KeyBar(
                    keys = tmuxKeyBarLayout(expanded = false),
                    onKey = {},
                    modifier = Modifier.testTag(TMUX_KEY_BAR_TAG),
                )
            }
        }

        val keyBarHeight = compose
            .onNodeWithTag(TMUX_KEY_BAR_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        assertTrue(
            "tmux keybar itself must stay visible and usable",
            keyBarHeight >= 56f,
        )
    }

    private fun terminalRegionHeight(): Float {
        val bounds = compose
            .onNodeWithTag(TERMINAL_REGION_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        return bounds.bottom - bounds.top
    }

    private companion object {
        const val TERMINAL_REGION_TAG = "tmux:pan-test:terminal-region"
        const val KEYBAR_TAG = "tmux:pan-test:keybar"
    }

    @androidx.compose.runtime.Composable
    private fun TerminalColumnUnderTest(modifier: Modifier = Modifier) {
        Column(modifier = modifier) {
            // Weighted "terminal" region — the analogue of the
            // HorizontalPager / TerminalSurface in TmuxSessionScreen. Its
            // measured height is the proxy for the TerminalView pixel height
            // that drives the grid recompute.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PocketShellColors.SurfaceElev)
                    .testTag(TERMINAL_REGION_TAG),
            )
            // Fixed-height bottom "key bar" — the analogue of KeyBarWithMic.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(PocketShellColors.Surface)
                    .testTag(KEYBAR_TAG),
            )
        }
    }
}
