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
import androidx.compose.foundation.layout.width
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
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1235 (AC "Row never occludes the composer/terminal controls" — F2/F3
 * containment, keyboard up where relevant).
 *
 * The agent-approval quick-reply band ([AgentQuickReplyRow]) is a REGULAR bottom
 * band — a [Column] sibling that reserves its own height directly ABOVE the tmux
 * bottom controls (never an overlay z-stacked over them). This proof composes the
 * PRODUCTION band + the PRODUCTION [TmuxTerminalBottomControls] in the same
 * bottom-anchored Column the session screen uses, and HARD-asserts (F1 viewport
 * containment via `assertNodeFullyWithinRoot` / `assertNodeFullyAboveImeOrKeyboard`,
 * NOT a bare `assertIsDisplayed()`):
 *
 *  - the quick-reply row lies fully within the window (and above the keyboard when
 *    the IME is up), and
 *  - the composer launcher + the terminal controls (show-keyboard chip / the
 *    compact hotkeys launcher) remain fully within the window / above the keyboard
 *    and are NOT overlapped by the band (`band.bottom <= control.top`).
 *
 * ## Why this is CI-deterministic (the #780 / #789 model)
 *
 * The CI swiftshader AVD cannot reliably raise a real soft IME, so a real-keyboard
 * test goes green locally and red/vacuously-skipped on CI. Instead we DISPATCH a
 * synthetic `Type.ime()` inset to the decor view (the exact shape of
 * [TmuxHotkeysLauncherImeProofTest] / [com.pocketshell.app.composer.PromptComposerImeSquishProofTest]),
 * read the inset Compose actually consumed from inside the composition, and lift
 * the keyboard-up bottom cluster with `Modifier.imePadding()` — the same
 * production lift the sibling hotkeys proof pins. There is NO `assumeTrue` /
 * self-skip: the synthetic inset is HARD-asserted to have applied before any
 * geometry is judged, and the containment assertions then run unconditionally.
 */
@RunWith(AndroidJUnit4::class)
class AgentQuickReplyBandContainmentProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    private val sampleReplies = listOf(
        AgentQuickReply(label = "Yes", payload = "y"),
        AgentQuickReply(label = "No", payload = "n"),
        AgentQuickReply(label = "Enter", payload = "\r"),
    )

    /**
     * Keyboard UP: the band + the compact terminal-hotkeys launcher must both sit
     * fully above the synthetic keyboard, and the band must not overlap the
     * launcher.
     */
    @Test
    fun quickReplyBandStaysAboveKeyboardAndDoesNotOccludeControlsWhenImeUp() {
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
                    // Fixed-height host modelling the session column so the
                    // room-above-keyboard math is a device-independent constant.
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        // The exact production bottom-section stacking: a weighted
                        // terminal fills the top, then the quick-reply band, then
                        // the bottom controls. The keyboard-up cluster is lifted via
                        // `imePadding()` — the #789 production lift.
                        BottomSection(
                            isImeVisible = true,
                            clusterModifier = Modifier.imePadding(),
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

        // The synthetic IME inset MUST have reached Compose, else we'd judge a
        // keyboard-DOWN layout and the containment check would pass vacuously.
        // HARD assertion, never a skip (#736).
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #1235 keyboard-up quick-reply containment. observedImeBottomPx=" +
                "$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        val containerBounds = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val keyboardTopPx = containerBounds.bottom - keyboardIntrusionPx

        // 1) The quick-reply band lies fully above the keyboard and within root.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = TMUX_AGENT_QUICK_REPLY_ROW_TAG,
            keyboardTopPx = keyboardTopPx,
            slopDp = SLOP_DP,
        )
        // 2) The terminal controls (compact hotkeys launcher) stay above the
        //    keyboard too — the band did not push them under it.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = TERMINAL_HOTKEYS_LAUNCHER_TAG,
            keyboardTopPx = keyboardTopPx,
            slopDp = SLOP_DP,
        )
        // 3) The band must not OCCLUDE the controls: it reserves its own height
        //    strictly above them (Column sibling, not an overlay).
        assertBandDoesNotOverlap(TERMINAL_HOTKEYS_LAUNCHER_TAG)
    }

    /**
     * Keyboard DOWN: the band + the full bottom controls (composer launcher +
     * show-keyboard chip) must all be fully within the window, and the band must
     * not overlap the composer launcher or the show-keyboard chip.
     */
    @Test
    fun quickReplyBandDoesNotOccludeComposerOrTerminalControlsWhenImeDown() {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        BottomSection(
                            isImeVisible = false,
                            clusterModifier = Modifier,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Every affordance is fully inside the window viewport (not clipped off
        // any edge by the band being present above them).
        compose.assertNodeFullyWithinRoot(TMUX_AGENT_QUICK_REPLY_ROW_TAG, slopDp = SLOP_DP)
        compose.assertNodeFullyWithinRoot(SESSION_COMPOSER_LAUNCHER_TAG, slopDp = SLOP_DP)
        compose.assertNodeFullyWithinRoot(SHOW_KEYBOARD_CHIP_TAG, slopDp = SLOP_DP)

        // The band reserves its own height strictly above the controls — it never
        // overlaps the composer launcher or the show-keyboard chip.
        assertBandDoesNotOverlap(SESSION_COMPOSER_LAUNCHER_TAG)
        assertBandDoesNotOverlap(SHOW_KEYBOARD_CHIP_TAG)
    }

    @Composable
    private fun BottomSection(isImeVisible: Boolean, clusterModifier: Modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Weighted faux terminal that absorbs the leftover space, exactly like
            // the session screen's `weight(1f)` pager Box — so the band + controls
            // are pinned to the bottom and the band's presence shrinks the TERMINAL,
            // never the controls.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "alex@pocketshell:~$ claude\n> Run tests? (y/n)",
                    color = PocketShellColors.Text,
                )
            }
            // The band + controls form ONE bottom cluster, lifted above the
            // keyboard together via a single `imePadding()` (the #789 production
            // lift). The band is the Column sibling directly ABOVE the controls —
            // it reserves its own height and never z-overlaps them.
            Column(modifier = Modifier.fillMaxWidth().then(clusterModifier)) {
                AgentQuickReplyRow(
                    replies = sampleReplies,
                    onReply = {},
                )
                TmuxTerminalBottomControls(
                    isImeVisible = isImeVisible,
                    showConversation = false,
                    sessionLive = true,
                    isAgentPane = false,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    onShowHotkeysTap = {},
                )
            }
        }
    }

    private fun assertBandDoesNotOverlap(controlTag: String) {
        val band = compose.onNodeWithTag(TMUX_AGENT_QUICK_REPLY_ROW_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val control = compose.onNodeWithTag(controlTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val slopPx = SLOP_DP * displayDensity()
        assertTrue(
            "Quick-reply band must reserve its own height ABOVE '$controlTag' and " +
                "never occlude it. band=$band control=$control slopPx=$slopPx",
            band.bottom <= control.top + slopPx,
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
        const val CONTAINER_TAG = "issue1235-quick-reply-host"
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val SLOP_DP = 4f
    }
}
