package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #789 / F2-F3 — the COMPACT terminal-hotkeys launcher chip stays ABOVE
 * the soft keyboard and reachable when the IME is up.
 *
 * The #784 full-width launcher BAR was replaced (#789, hard-cut D22) by a
 * compact chip. The maintainer's concern was the bar's wasted vertical space;
 * the regression risk of any keyboard-up bottom-chrome change is that the
 * affordance ends up OCCLUDED by the soft keyboard (the recurring #567/#615/#641
 * class). This proof asserts the compact launcher chip's rect lies fully above
 * the keyboard top — `assertNodeFullyAboveImeOrKeyboard`, NOT a bare
 * `assertIsDisplayed()` (process.md F2/F3).
 *
 * ## Why this is CI-deterministic (the #780 model)
 *
 * The CI swiftshader AVD cannot reliably raise a real soft IME, so a test that
 * waits on a real keyboard goes green locally and red (or vacuously-skipped) on
 * CI. Instead we DISPATCH a synthetic `Type.ime()` inset to the decor view and
 * read the inset Compose actually consumed from INSIDE the composition. The
 * keyboard-up band ([TmuxTerminalBottomControls] with `isImeVisible = true`)
 * is hosted at the bottom of a fixed-height container and wrapped in
 * `Modifier.imePadding()` — exactly the production behaviour: the band is lifted
 * to sit above the keyboard. There is NO `assumeTrue` / self-skip: the synthetic
 * inset is HARD-asserted to have applied before any geometry is judged.
 */
@RunWith(AndroidJUnit4::class)
class TmuxHotkeysLauncherImeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    @Test
    fun compactHotkeysLauncherStaysAboveKeyboardWhenImeUp() {
        compose.activityRule.scenario.onActivity { activity ->
            // Edge-to-edge so the dispatched synthetic insets are honoured the
            // same way a real device honours the IME inset.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Fixed-height host modelling the session column. The
                    // keyboard-up bottom band is pinned to the bottom and wrapped
                    // in `imePadding()` so it is lifted above the synthetic IME —
                    // the exact production lift. Geometry below is measured
                    // relative to this tagged container, not the device decor.
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TmuxTerminalBottomControls(
                            isImeVisible = true,
                            showConversation = false,
                            sessionLive = true,
                            isAgentPane = false,
                            onChipTap = {},
                            onDictateTap = {},
                            onEnterTap = {},
                            onShowKeyboardTap = {},
                            onAddSnippetTap = {},
                            onShowHotkeysTap = {},
                            modifier = Modifier.imePadding(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value
        val navBottomPx = observedNavBottomPx.value

        // The synthetic IME inset MUST have reached Compose, else we would be
        // judging a keyboard-DOWN layout and the containment check would pass
        // vacuously. HARD assertion, never a skip (#736).
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #789 keyboard-up launcher reachability. observedImeBottomPx=" +
                "$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        // The keyboard intrudes into this window by (ime - navBars); its top edge
        // in the container coordinate space is containerBottom minus that overlap.
        val containerBounds = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val keyboardTopPx = containerBounds.bottom - keyboardIntrusionPx

        // The compact launcher chip's rect must lie fully ABOVE the keyboard top
        // and within the window — the F1 containment check, not a bare displayed.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = TERMINAL_HOTKEYS_LAUNCHER_TAG,
            keyboardTopPx = keyboardTopPx,
            slopDp = SLOP_DP,
        )
    }

    private fun applySyntheticInsets(imeBottomPx: Int, navBarBottomPx: Int) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val CONTAINER_TAG = "issue789-hotkeys-band-host"
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val SLOP_DP = 4f
    }
}
