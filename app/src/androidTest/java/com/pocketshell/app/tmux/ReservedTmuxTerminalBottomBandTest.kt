package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #887 recurrence — production-layout proof for the bottom reservation.
 *
 * The real keyboard-down child remains measured while the IME is visible but is
 * not placed. This test drives the framework inset sequence that caused the
 * regression: visible navigation-bar inset drops to zero as the IME rises,
 * while navigationBarsIgnoringVisibility retains the device footprint.
 */
@RunWith(AndroidJUnit4::class)
class ReservedTmuxTerminalBottomBandTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @OptIn(ExperimentalLayoutApi::class)
    fun imeKeepsMeasuredDownFootprintAndHiddenContentRemeasures() {
        val imeVisible = mutableStateOf(false)
        val controlHeightDp = mutableStateOf(56f)
        val observedImePx = mutableIntStateOf(0)
        val observedVisibleNavPx = mutableIntStateOf(0)
        val observedStableNavPx = mutableIntStateOf(0)

        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImePx.intValue = WindowInsets.ime.getBottom(density)
                observedVisibleNavPx.intValue = WindowInsets.navigationBars.getBottom(density)
                observedStableNavPx.intValue =
                    WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(PocketShellColors.Background)
                                .testTag(TERMINAL_PROXY_TAG),
                        )
                        ReservedTmuxTerminalBottomBand(
                            isImeVisible = imeVisible.value,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(RESERVATION_TAG),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(controlHeightDp.value.dp)
                                    .background(PocketShellColors.Surface)
                                    .testTag(KEYBOARD_DOWN_CHILD_TAG),
                            )
                        }
                    }

                    if (imeVisible.value) {
                        TmuxTerminalImeHotkeysLauncher(
                            onShowHotkeysTap = {},
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .imePadding(),
                        )
                    }
                }
            }
        }

        dispatchInsets(
            imeBottomPx = 0,
            visibleNavBottomPx = px(24f),
            stableNavBottomPx = px(24f),
        )
        compose.waitForIdle()
        assertTrue("stable nav inset must reach Compose", observedStableNavPx.intValue > 0)
        val downTerminal = bounds(TERMINAL_PROXY_TAG)
        val downReservation = bounds(RESERVATION_TAG)
        compose.onNodeWithTag(KEYBOARD_DOWN_CHILD_TAG).assertExists()

        // Real show sequence: nav visibility animates away while the IME and its
        // much larger bottom inset become visible.
        dispatchInsets(
            imeBottomPx = px(300f),
            visibleNavBottomPx = 0,
            stableNavBottomPx = px(24f),
        )
        compose.runOnIdle { imeVisible.value = true }
        compose.waitForIdle()

        assertTrue("synthetic IME must reach Compose", observedImePx.intValue > 0)
        assertEquals("visible nav inset must be gone under IME", 0, observedVisibleNavPx.intValue)
        assertEquals(
            "stable nav footprint must survive IME show",
            px(24f),
            observedStableNavPx.intValue,
        )
        assertRectEquals("terminal bounds across IME show", downTerminal, bounds(TERMINAL_PROXY_TAG))
        assertRectEquals(
            "reservation bounds across IME show",
            downReservation,
            bounds(RESERVATION_TAG),
        )
        // The clear-and-set boundary removes the hidden controls from the
        // merged accessibility/input semantics tree. Compose deliberately
        // retains unplaced implementation nodes in its unmerged test tree.
        compose.onNodeWithTag(KEYBOARD_DOWN_CHILD_TAG).assertDoesNotExist()
        assertEquals(
            "exactly one IME-only launcher must replace the unplaced down controls",
            1,
            compose.onAllNodesWithTag(
                TERMINAL_HOTKEYS_LAUNCHER_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )

        // No cached height: a different target/content shape remeasures even
        // though the keyboard-down child remains unplaced.
        compose.runOnIdle { controlHeightDp.value = 80f }
        compose.waitForIdle()
        val tallerReservation = bounds(RESERVATION_TAG)
        assertTrue(
            "hidden production controls must remeasure after content change; " +
                "before=$downReservation after=$tallerReservation",
            tallerReservation.height > downReservation.height + px(20f),
        )
        assertTrue(
            "terminal proxy must consume the corresponding smaller remainder",
            bounds(TERMINAL_PROXY_TAG).height < downTerminal.height - px(20f),
        )
        compose.onNodeWithTag(KEYBOARD_DOWN_CHILD_TAG).assertDoesNotExist()

        // Likewise a device/config nav-footprint change naturally remeasures;
        // the visible nav inset stays zero throughout.
        dispatchInsets(
            imeBottomPx = px(300f),
            visibleNavBottomPx = 0,
            stableNavBottomPx = px(36f),
        )
        compose.waitForIdle()
        val widerStableInsetReservation = bounds(RESERVATION_TAG)
        assertTrue(
            "stable nav-inset change must remeasure the hidden child; " +
                "before=$tallerReservation after=$widerStableInsetReservation",
            widerStableInsetReservation.height > tallerReservation.height + px(8f),
        )
    }

    @Test
    fun conversationImeOpenReservesZeroBlankBottomRows() {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONVERSATION_CONTAINER_TAG),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag(CONVERSATION_CONTENT_TAG),
                        )
                        TmuxSessionBottomBandPlacement(
                            isImeVisible = true,
                            onConversationTab = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(CONVERSATION_BAND_TAG),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag(CONVERSATION_DOWN_CHILD_TAG),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(CONVERSATION_BAND_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CONVERSATION_DOWN_CHILD_TAG).assertDoesNotExist()
        assertRectEquals(
            "Conversation content must consume the entire screen column with IME open",
            bounds(CONVERSATION_CONTAINER_TAG),
            bounds(CONVERSATION_CONTENT_TAG),
        )
    }

    private fun dispatchInsets(
        imeBottomPx: Int,
        visibleNavBottomPx: Int,
        stableNavBottomPx: Int,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.ime(),
                    Insets.of(0, 0, 0, imeBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, visibleNavBottomPx),
                )
                .setInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, stableNavBottomPx),
                )
                .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
                .setVisible(
                    WindowInsetsCompat.Type.navigationBars(),
                    visibleNavBottomPx > 0,
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun assertRectEquals(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left, actual.left, SLOP_PX)
        assertEquals("$label top", expected.top, actual.top, SLOP_PX)
        assertEquals("$label right", expected.right, actual.right, SLOP_PX)
        assertEquals("$label bottom", expected.bottom, actual.bottom, SLOP_PX)
    }

    private fun px(dp: Float): Int = with(compose.density) { dp.dp.roundToPx() }

    private companion object {
        const val CONTAINER_TAG = "issue887-reserved-band-container"
        const val TERMINAL_PROXY_TAG = "issue887-reserved-band-terminal"
        const val RESERVATION_TAG = "issue887-reserved-band"
        const val KEYBOARD_DOWN_CHILD_TAG = "issue887-reserved-band-down-child"
        const val CONVERSATION_CONTAINER_TAG = "issue887-conversation-container"
        const val CONVERSATION_CONTENT_TAG = "issue887-conversation-content"
        const val CONVERSATION_BAND_TAG = "issue887-conversation-band"
        const val CONVERSATION_DOWN_CHILD_TAG = "issue887-conversation-down-child"
        const val CONTAINER_WIDTH_DP = 392f
        const val CONTAINER_HEIGHT_DP = 740f
        const val SLOP_PX = 0.5f
    }
}
