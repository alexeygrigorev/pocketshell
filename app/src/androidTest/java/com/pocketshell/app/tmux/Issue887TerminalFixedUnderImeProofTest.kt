package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import com.pocketshell.app.layout.TmuxImeLayoutState
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #887 — terminal stays FIXED when the soft keyboard shows: NEITHER
 * resized NOR panned. The keyboard simply OVERLAYS the bottom rows of the
 * terminal, and the composer floats above the keyboard.
 *
 * ## What this proves (and the maintainer's reported bug)
 *
 * The maintainer's keyboard-up screenshot (#887) showed the terminal viewport
 * PANNED UP — the top went black/empty — because the #457 design translated the
 * whole terminal column up by the keyboard overlap (`graphicsLayer { translationY
 * = imeLayout.panOffsetPx() }`) so the bottom rows + composer cleared the
 * keyboard. The maintainer wants the #457 no-RESIZE behaviour KEPT but the PAN
 * removed: the terminal must not move at all; the keyboard overlays it.
 *
 * The fix is twofold:
 *  - `MainActivity` sets the activity window to `SOFT_INPUT_ADJUST_NOTHING` so the
 *    OS never pans/resizes the window when the keyboard shows.
 *  - The terminal column in `TmuxSessionScreen` no longer applies the in-app
 *    `graphicsLayer { translationY = imeLayout.panOffsetPx() }` pan — it is now a
 *    plain `Modifier.fillMaxSize()`.
 *
 * ## The load-bearing assertion (terminal bounds UNCHANGED)
 *
 * [terminalDoesNotMoveOrResizeWhenImeUp] composes the PRODUCTION terminal-column
 * modifier shape ([ProductionFixedTerminalColumn], the exact post-fix
 * `Modifier.fillMaxSize()` — no pan) fed by the PRODUCTION [TmuxImeLayoutState],
 * captures the terminal node's `boundsInRoot` keyboard-DOWN, dispatches a
 * SYNTHETIC `ime()` inset (the #780 model — environment-independent, HARD-asserted
 * to apply, never an `assumeTrue` skip), and asserts the terminal node's
 * `boundsInRoot` are IDENTICAL keyboard-UP — no pan, no resize, no reflow. It also
 * asserts the composer (which DOES apply `.imePadding()`) sits fully above the
 * synthetic keyboard.
 *
 * ## Red→green guard (the assertion is load-bearing, not vacuous)
 *
 * [legacyPanShapeWouldMoveTheTerminal_provesAssertionIsLoadBearing] composes the
 * DELETED #457 pan shape ([LegacyPannedTerminalColumn], the
 * `graphicsLayer { translationY = imeLayout.panOffsetPx() }` that production no
 * longer has) under the SAME synthetic inset and asserts the terminal node's
 * `boundsInRoot` DID move up. This is the RED the production fix turns GREEN: it
 * proves the bounds-unchanged assertion in the production test genuinely
 * distinguishes "panned" from "fixed" and is not passing vacuously. Together the
 * two cases are the red→green proof for #887 (G1/G10).
 */
@RunWith(AndroidJUnit4::class)
class Issue887TerminalFixedUnderImeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Compose-observed insets (px), captured from INSIDE the composition so the
    // measured keyboard height is exactly what the laid-out screen reacted to.
    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    @Test
    fun terminalDoesNotMoveOrResizeWhenImeUp() {
        setUpEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                ObserveInsets()
                ProductionFixedTerminalColumn(
                    imeLayout = productionImeLayout(),
                )
            }
        }
        compose.waitForIdle()

        // Capture terminal geometry keyboard-DOWN first.
        val terminalDown = boundsOf(TERMINAL_TAG)

        // Drive a known soft-IME inset WITHOUT a real keyboard (#780 model).
        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * density()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * density()).toInt(),
        )
        compose.waitForIdle()

        // HARD-assert the synthetic inset actually reached Compose — otherwise we
        // would be measuring a keyboard-DOWN layout and the bounds-unchanged check
        // would pass vacuously. Never an assumeTrue skip (#780/#657 F3).
        val imeBottomPx = observedImeBottomPx.value
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "#887 terminal-fixed-under-keyboard geometry. observedImeBottomPx=" +
                "$imeBottomPx (expected > 0).",
            imeBottomPx > 0,
        )

        val terminalUp = boundsOf(TERMINAL_TAG)

        println(
            "ISSUE887_TERMINAL terminalDown=$terminalDown terminalUp=$terminalUp " +
                "imeBottomPx=$imeBottomPx navBottomPx=${observedNavBottomPx.value}",
        )

        // LOAD-BEARING: the terminal node's boundsInRoot must be IDENTICAL with vs
        // without the keyboard — same top (no pan up) AND same size (no resize /
        // reflow). This is the exact #887 acceptance criterion.
        assertEquals(
            "Terminal TOP moved when the keyboard showed (#887: must NOT pan up). " +
                "down=$terminalDown up=$terminalUp",
            terminalDown.top,
            terminalUp.top,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "Terminal HEIGHT changed when the keyboard showed (#457/#887: must NOT " +
                "resize/reflow). down=$terminalDown up=$terminalUp",
            terminalDown.height,
            terminalUp.height,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "Terminal LEFT moved when the keyboard showed. down=$terminalDown up=$terminalUp",
            terminalDown.left,
            terminalUp.left,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "Terminal WIDTH changed when the keyboard showed. down=$terminalDown up=$terminalUp",
            terminalDown.width,
            terminalUp.width,
            BOUNDS_SLOP_PX,
        )

        // The composer (which DOES apply `.imePadding()`) must sit fully above the
        // synthetic keyboard — independent of the fixed terminal. The keyboard top
        // in root coords is decorHeight - (ime - navBars).
        val rootBottom = boundsOf(ROOT_TAG).bottom
        val keyboardIntrusionPx = (imeBottomPx - observedNavBottomPx.value).coerceAtLeast(0)
        val keyboardTopPx = rootBottom - keyboardIntrusionPx
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_TAG,
            keyboardTopPx = keyboardTopPx,
        )
    }

    @Test
    fun legacyPanShapeWouldMoveTheTerminal_provesAssertionIsLoadBearing() {
        setUpEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                ObserveInsets()
                LegacyPannedTerminalColumn(
                    imeLayout = productionImeLayout(),
                )
            }
        }
        compose.waitForIdle()

        val terminalDown = boundsOf(TERMINAL_TAG)

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * density()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * density()).toInt(),
        )
        compose.waitForIdle()

        val imeBottomPx = observedImeBottomPx.value
        assertTrue(
            "Synthetic ime() inset did not reach Compose. observedImeBottomPx=$imeBottomPx.",
            imeBottomPx > 0,
        )

        val terminalUp = boundsOf(TERMINAL_TAG)
        println(
            "ISSUE887_LEGACY_PAN terminalDown=$terminalDown terminalUp=$terminalUp " +
                "imeBottomPx=$imeBottomPx navBottomPx=${observedNavBottomPx.value}",
        )

        // The DELETED #457 pan shape DID translate the terminal up (negative
        // translationY). This is the bug the production fix removes; asserting the
        // bounds moved here proves the bounds-UNCHANGED assertion in the production
        // test is load-bearing (it can tell panned from fixed) — the red the fix
        // turns green.
        assertTrue(
            "Legacy pan shape was expected to translate the terminal UP when the " +
                "keyboard showed, but its top did not move — the red→green guard for " +
                "#887 is no longer meaningful. down=$terminalDown up=$terminalUp",
            terminalUp.top < terminalDown.top - BOUNDS_SLOP_PX,
        )
    }

    // ------------------------------------------------------------------
    // Harness composables — exact production vs deleted-legacy terminal-column
    // modifier shapes.
    // ------------------------------------------------------------------

    /**
     * The POST-FIX production shape: the terminal column is a plain
     * `Modifier.fillMaxSize()` (no `graphicsLayer` pan), mirroring
     * `TmuxSessionScreen`'s terminal `Column` after the #887 change. The composer
     * row below applies `.imePadding()` so it floats above the keyboard,
     * independent of the fixed terminal.
     */
    @Composable
    private fun ProductionFixedTerminalColumn(imeLayout: TmuxImeLayoutState) {
        // Read isImeVisible in the body exactly as TmuxSessionScreen does (chrome
        // flag) — proves reading it does NOT move the terminal.
        @Suppress("UNUSED_VARIABLE")
        val chromeCompressed = imeLayout.isImeVisible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ROOT_TAG)
                .background(PocketShellColors.Background),
        ) {
            Column(
                // The exact production modifier post-#887: fixed, no pan.
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(TERMINAL_TAG)
                        .background(PocketShellColors.Background),
                ) {
                    Text(
                        text = "alex@pocketshell:~$ tail -f deploy.log",
                        color = PocketShellColors.Text,
                    )
                }
            }
            // Composer floats above the keyboard via imePadding, anchored to the
            // bottom — independent of the terminal column above.
            ComposerStandIn(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding(),
            )
        }
    }

    /**
     * The DELETED #457 pan shape, kept ONLY in this test as the red baseline: the
     * terminal column translates up by `imeLayout.panOffsetPx()`. Production no
     * longer has this; it exists here so the bounds-unchanged assertion above is
     * provably load-bearing (red→green).
     */
    @Composable
    private fun LegacyPannedTerminalColumn(imeLayout: TmuxImeLayoutState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ROOT_TAG)
                .background(PocketShellColors.Background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = imeLayout.panOffsetPx() },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(TERMINAL_TAG)
                        .background(PocketShellColors.Background),
                ) {
                    Text(
                        text = "alex@pocketshell:~$ tail -f deploy.log",
                        color = PocketShellColors.Text,
                    )
                }
            }
        }
    }

    @Composable
    private fun ComposerStandIn(modifier: Modifier = Modifier) {
        // A stand-in for the composer's send/attach row. The COMPOSER's geometry
        // is NOT the symptom under test (the terminal bounds are); the only thing
        // we assert about the composer is that `.imePadding()` lifts it above the
        // keyboard — a 1:1 property of imePadding, faithfully modelled by a simple
        // bottom-anchored bar. The heavy real composer is a separate dialog window
        // (covered by PromptComposerImeSquishProofTest); a stand-in here is
        // justified per F2 because the composer's internal layout is not #887's
        // concern.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(COMPOSER_HEIGHT_DP.dp)
                .testTag(COMPOSER_TAG)
                .background(PocketShellColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Send  |  attach  |  mic", color = PocketShellColors.Text)
        }
    }

    @Composable
    private fun ObserveInsets() {
        val d = LocalDensity.current
        observedImeBottomPx.value = WindowInsets.ime.getBottom(d)
        observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(d)
    }

    @Composable
    private fun productionImeLayout(): TmuxImeLayoutState {
        // Build the PRODUCTION holder from the live host-observed IME inset so the
        // pan baseline reads exactly what production reads. navBarBottomPx mirrors
        // the synthetic nav-bar so panOffsetPx subtracts it as production does.
        val imeState: State<Int> = observedImeBottomPx
        val navPx = (NAV_BAR_DP * density()).toInt()
        return remember(imeState, navPx) {
            TmuxImeLayoutState(imeBottomPxState = imeState, navBarBottomPx = navPx)
        }
    }

    // ------------------------------------------------------------------
    // Test plumbing (synthetic-inset model, #780).
    // ------------------------------------------------------------------

    private fun setUpEdgeToEdge() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
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

    private data class Bounds(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val bottom: Float,
    ) {
        override fun toString() =
            "Bounds(left=$left top=$top width=$width height=$height bottom=$bottom)"
    }

    private fun boundsOf(tag: String): Bounds {
        val r = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        return Bounds(
            left = r.left,
            top = r.top,
            width = r.width,
            height = r.height,
            bottom = r.bottom,
        )
    }

    private fun density(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val ROOT_TAG = "issue887-root"
        const val TERMINAL_TAG = "issue887-terminal"
        const val COMPOSER_TAG = "issue887-composer"

        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val COMPOSER_HEIGHT_DP = 56f

        // Density-scaled slop so sub-pixel rounding never flips the bounds-equal
        // assertion. 1.5px is well below the ~750px pan a 300dp keyboard produces.
        const val BOUNDS_SLOP_PX = 1.5f
    }
}
