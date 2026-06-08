package com.pocketshell.app.session

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
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Raw SSH mirror of the tmux IME-pan proof. The keyboard-open layout must pan
 * above the soft keyboard without shrinking the weighted terminal viewport.
 */
@RunWith(AndroidJUnit4::class)
class RawSessionKeyboardPanLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panKeepsTerminalRegionHeightConstantAcrossImeFlip() {
        var imeOverlapPx by mutableStateOf(0)
        compose.setContent {
            PocketShellTheme {
                RawTerminalColumnUnderTest(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = -imeOverlapPx.toFloat() },
                )
            }
        }

        val heightImeDown = terminalRegionHeight()

        compose.runOnIdle { imeOverlapPx = 800 }
        compose.waitForIdle()

        val heightImeUp = terminalRegionHeight()

        assertEquals(
            "raw SSH terminal region height must stay constant when panning above the IME",
            heightImeDown,
            heightImeUp,
            0.5f,
        )
    }

    @Test
    fun imePaddingControlShrinksTerminalRegion() {
        var bottomPadPx by mutableStateOf(0)
        compose.setContent {
            PocketShellTheme {
                val padDp = with(compose.density) { bottomPadPx.toDp() }
                RawTerminalColumnUnderTest(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = padDp),
                )
            }
        }

        val heightImeDown = terminalRegionHeight()
        compose.runOnIdle { bottomPadPx = 800 }
        compose.waitForIdle()
        val heightImeUp = terminalRegionHeight()

        assertTrue(
            "sanity: padding must shrink the raw SSH terminal region",
            heightImeUp < heightImeDown - 1f,
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
        const val TERMINAL_REGION_TAG = "raw:pan-test:terminal-region"
        const val BOTTOM_CONTROLS_TAG = "raw:pan-test:bottom-controls"
    }

    @androidx.compose.runtime.Composable
    private fun RawTerminalColumnUnderTest(modifier: Modifier = Modifier) {
        Column(modifier = modifier) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PocketShellColors.SurfaceElev)
                    .testTag(TERMINAL_REGION_TAG),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(PocketShellColors.Surface)
                    .testTag(BOTTOM_CONTROLS_TAG),
            )
        }
    }
}
