package com.pocketshell.next.terminal

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
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2533 (D31 reopen of #887) — terminal stays FIXED when the soft
 * keyboard shows: NEITHER resized NOR panned. The keyboard simply OVERLAYS
 * the bottom rows of the terminal, and the composer (when open) floats above
 * the keyboard.
 *
 * ## What this proves (and the maintainer's reported bug)
 *
 * The rewrite put [Modifier.imePadding] back on [SessionScreen]'s session
 * column, so raising the keyboard shrinks the column, [onResized] fires, and
 * tmux reflows. After the keyboard hides, the view can be left with a large
 * empty void (the 2026-09-05 screenshot). #887 already forbade that: the
 * window is `SOFT_INPUT_ADJUST_NOTHING` and the session column is a plain
 * `Modifier.fillMaxSize()`.
 *
 * ## The load-bearing assertion (terminal bounds UNCHANGED)
 *
 * [terminalDoesNotMoveOrResizeWhenImeUp] composes the PRODUCTION
 * [SessionScreen] (Connecting — the terminal slot is the weight(1f) box,
 * no native `TerminalSession` required), captures the slot's `boundsInRoot`
 * keyboard-DOWN, dispatches a SYNTHETIC `ime()` inset (the #780 model —
 * environment-independent, HARD-asserted to apply, never an `assumeTrue`
 * skip), and asserts those bounds are IDENTICAL keyboard-UP. It also
 * asserts a composer stand-in that DOES apply `.imePadding()` sits fully
 * above the synthetic keyboard.
 *
 * This is the reproduce-first proof: on the unfixed `imePadding()` column
 * the height shrinks (RED); after the #887 flags the height is unchanged
 * (GREEN).
 *
 * ## Red→green guard (the assertion is load-bearing, not vacuous)
 *
 * [imePaddingOnTheColumnWouldShrinkTheTerminal_provesAssertionIsLoadBearing]
 * composes the CURRENT (buggy) column shape — `fillMaxSize().imePadding()` —
 * under the SAME synthetic inset and asserts the terminal slot DID shrink.
 * Together the two cases are the red→green proof for #2533/#887.
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
                ProductionSessionColumn()
            }
        }
        compose.waitForIdle()

        val terminalDown = boundsOf(SESSION_CONNECTING_TAG)
        val columnDown = boundsOf(SESSION_SCREEN_TAG)

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

        val terminalUp = boundsOf(SESSION_CONNECTING_TAG)
        val columnUp = boundsOf(SESSION_SCREEN_TAG)

        println(
            "ISSUE887_TERMINAL terminalDown=$terminalDown terminalUp=$terminalUp " +
                "columnDown=$columnDown columnUp=$columnUp " +
                "imeBottomPx=$imeBottomPx navBottomPx=${observedNavBottomPx.value}",
        )

        // LOAD-BEARING: the terminal slot's boundsInRoot must be IDENTICAL with
        // vs without the keyboard — same top (no pan up) AND same size (no
        // resize / reflow). This is the exact #887/#2533 acceptance criterion.
        assertUnchanged("Terminal", terminalDown, terminalUp)
        assertUnchanged("Session column", columnDown, columnUp)

        // The composer (which DOES apply `.imePadding()`) must sit fully above
        // the synthetic keyboard — independent of the fixed terminal. The
        // keyboard top in root coords is decorHeight - (ime - navBars).
        val rootBottom = boundsOf(ROOT_TAG).bottom
        val keyboardIntrusionPx = (imeBottomPx - observedNavBottomPx.value).coerceAtLeast(0)
        val keyboardTopPx = rootBottom - keyboardIntrusionPx
        assertNodeFullyAboveKeyboard(
            tag = COMPOSER_TAG,
            keyboardTopPx = keyboardTopPx,
        )
    }

    @Test
    fun imePaddingOnTheColumnWouldShrinkTheTerminal_provesAssertionIsLoadBearing() {
        setUpEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                ObserveInsets()
                LegacyImePaddedTerminalColumn()
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
            "ISSUE887_IME_PADDING terminalDown=$terminalDown terminalUp=$terminalUp " +
                "imeBottomPx=$imeBottomPx navBottomPx=${observedNavBottomPx.value}",
        )

        // The rewrite's session-column `imePadding()` DID shrink the terminal
        // (height drops by the IME inset). This is the bug the production fix
        // removes; asserting the height moved here proves the bounds-UNCHANGED
        // assertion in the production test is load-bearing (it can tell
        // padded from fixed) — the red the fix turns green.
        assertTrue(
            "imePadding on the session column was expected to shrink the terminal " +
                "when the keyboard showed, but its height did not change — the " +
                "red→green guard for #2533/#887 is no longer meaningful. " +
                "down=$terminalDown up=$terminalUp",
            terminalUp.height < terminalDown.height - BOUNDS_SLOP_PX,
        )
    }

    // ------------------------------------------------------------------
    // Harness composables — production SessionScreen vs the deleted
    // imePadding column shape.
    // ------------------------------------------------------------------

    /**
     * The PRODUCTION screen. Connecting is enough: the terminal slot is the
     * weight(1f) box whose size change is what [SessionScreen] reports through
     * `onResized`, and that is the quantity `imePadding` on the column used
     * to steal. A composer stand-in with `.imePadding()` is overlaid so AC2
     * (Send/mic above the IME) is proven independently of the sheet window.
     *
     * The stand-in is F2-justified the same way #887's was: the real
     * [com.pocketshell.next.composer.PromptComposerSheet] is a separate
     * `ModalBottomSheet` dialog window with its own inset handling; this
     * proof's concern is that `.imePadding()` still lifts content above a
     * dispatched `ime()` inset under `ADJUST_NOTHING`.
     */
    @Composable
    private fun ProductionSessionColumn() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ROOT_TAG)
                .background(PocketShellColors.Background),
        ) {
            SessionScreen(
                state = SessionUiState.Connecting,
                composerState = ComposerUiState(),
                sessionName = SESSION,
                onBack = {},
                onResized = { _, _ -> },
                onRetry = {},
                onHotkeySend = {},
                onDraftChange = {},
                onSend = {},
                onInsert = {},
                onAttach = {},
                onMicTap = {},
                onCancelRecording = {},
                onToggleHistory = {},
                onTogglePreview = {},
                onRemoveAttachment = {},
                onDismissNotice = {},
                onDiscardDraft = {},
                onUseHistoryEntry = {},
            )
            ComposerStandIn(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding(),
            )
        }
    }

    /**
     * The rewrite's buggy column shape, kept ONLY in this test as the red
     * baseline: `fillMaxSize().imePadding()` on the session column. Production
     * no longer has this; it exists here so the bounds-unchanged assertion
     * above is provably load-bearing (red→green).
     */
    @Composable
    private fun LegacyImePaddedTerminalColumn() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ROOT_TAG)
                .background(PocketShellColors.Background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
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

    private fun assertUnchanged(what: String, down: Bounds, up: Bounds) {
        assertEquals(
            "$what TOP moved when the keyboard showed (#887: must NOT pan up). " +
                "down=$down up=$up",
            down.top,
            up.top,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "$what HEIGHT changed when the keyboard showed (#457/#887: must NOT " +
                "resize/reflow). down=$down up=$up",
            down.height,
            up.height,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "$what LEFT moved when the keyboard showed. down=$down up=$up",
            down.left,
            up.left,
            BOUNDS_SLOP_PX,
        )
        assertEquals(
            "$what WIDTH changed when the keyboard showed. down=$down up=$up",
            down.width,
            up.width,
            BOUNDS_SLOP_PX,
        )
    }

    private fun assertNodeFullyAboveKeyboard(tag: String, keyboardTopPx: Float) {
        val density = compose.density.density
        val slopPx = CONTAINMENT_SLOP_DP * density
        val bounds = boundsOf(tag)
        assertTrue(
            "Node '$tag' is not fully above the keyboard (Send/mic must stay " +
                "reachable). nodeBounds=$bounds keyboardTopPx=$keyboardTopPx " +
                "slopPx=$slopPx.",
            bounds.bottom <= keyboardTopPx + slopPx,
        )
    }

    private fun density(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val ROOT_TAG = "issue887-root"
        const val TERMINAL_TAG = "issue887-terminal"
        const val COMPOSER_TAG = "issue887-composer"
        const val SESSION = "issue887-shell"

        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val COMPOSER_HEIGHT_DP = 56f

        // Density-scaled slop so sub-pixel rounding never flips the bounds-equal
        // assertion. 1.5px is well below the ~750px shrink a 300dp keyboard produces.
        const val BOUNDS_SLOP_PX = 1.5f
        const val CONTAINMENT_SLOP_DP = 1f
    }
}
