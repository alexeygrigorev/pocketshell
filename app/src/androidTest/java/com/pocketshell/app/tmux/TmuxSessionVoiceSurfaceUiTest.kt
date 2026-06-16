package com.pocketshell.app.tmux

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.assistant.AssistantAgentLoop
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.composer.COMPOSER_ATTACHMENT_CHIPS_TAG
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.ADD_PROMPT_CHIP_LABEL
import com.pocketshell.app.voice.ASSISTANT_CORRECTION_MIC_TAG
import com.pocketshell.app.voice.ASSISTANT_RETRY_TAG
import com.pocketshell.app.voice.AssistantCorrectionDictation
import com.pocketshell.app.voice.AssistantDictationTextEvent
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.HOTKEYS_CHIP_LABEL
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.app.voice.SnippetsChipIcon
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator validation for the tmux terminal bottom controls. The full
 * `TmuxSessionScreen` requires Hilt + a live tmux connect, so this test
 * isolates the input strips the screen renders and verifies the terminal
 * accessory and agent-vs-shell prompt/command affordances.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionVoiceSurfaceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tmuxKeyboardOpenTerminalSurfaceRendersNoKeyGrid() {
        // Issue #784 (D22 hard-cut): with the soft keyboard UP on the Terminal
        // tab, this bottom-controls surface never renders a crammed key GRID
        // above the IME (the #755 cram the maintainer rejected). The full hotkeys
        // grid is the dedicated `TerminalHotkeysPanel` sheet. When no
        // `onShowHotkeysTap` is wired (this test), nothing renders here at all.
        // This guards the hard-cut: no key grid, no Esc/^C/Tab keys, no
        // attachment grid, no chip band when the IME is up on a terminal pane.
        compose.setContent {
            PocketShellTheme {
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
                    modifier = Modifier.testTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        // The surface renders nothing in this state (no container, no key grid).
        compose.onNodeWithTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("^C").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()

        // Issue #673: still no staged composer attachment grid in the session
        // bottom area.
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertDoesNotExist()

        // None of the keyboard-down chip affordances appear with the IME up.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG).assertDoesNotExist()
    }

    @Test
    fun tmuxKeyboardOpenTerminalSurfaceShowsHotkeysLauncherWhenWired() {
        // Issue #784: with the IME up on a terminal pane AND a hotkeys-launch
        // callback wired, the surface shows the slim "⌨ Terminal hotkeys"
        // launcher above the keyboard (one tap opens the dedicated panel) — but
        // still NO crammed key grid. This is the un-cram: a single launcher, not
        // a wall of keys, above the IME.
        var launched = false
        compose.setContent {
            PocketShellTheme {
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
                    onShowHotkeysTap = { launched = true },
                    modifier = Modifier.testTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG).assertExists().performClick()
        compose.waitForIdle()
        assertTrue("Tapping the launcher should request opening the hotkeys panel", launched)
        // The grid panel itself is a separate sheet — not rendered inline here.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithText("^B").assertDoesNotExist()
    }

    @Test
    fun tmuxKeyboardUpHotkeysLauncherIsCompactNotFullWidthBar() {
        // Issue #789: with the IME up on a terminal pane, the launcher is a
        // COMPACT chip (the deleted #784 full-width bar is gone). It carries the
        // `hotkeys` label and the stable `tmux:hotkeys-launcher` tag, is fully
        // within the host root (not pushed off any edge), and is narrower than the
        // full host width — proving the dedicated full-width bar's space is
        // reclaimed. The old bar's "Terminal hotkeys" caption no longer appears.
        compose.setContent {
            PocketShellTheme {
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
                    modifier = Modifier.testTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        // The old full-width bar's caption is gone (hard-cut, D22).
        compose.onNodeWithText("Terminal hotkeys").assertDoesNotExist()

        // The compact launcher chip is present, labelled, tappable, and FULLY
        // within the host root (issue #657 / F1 containment, not a bare displayed).
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG)
            .assertExists()
            .assertHasClickAction()
            .assertTextContains(HOTKEYS_CHIP_LABEL)
        compose.assertNodeFullyWithinRoot(TERMINAL_HOTKEYS_LAUNCHER_TAG)

        // The compact chip is meaningfully narrower than the host band — proving
        // it is NOT the old full-width bar. (Sanity: <70% of the host width.)
        val bandBounds = compose.onNodeWithTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG)
            .getUnclippedBoundsInRoot()
        val chipBounds = compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG)
            .getUnclippedBoundsInRoot()
        val bandWidth = bandBounds.right - bandBounds.left
        val chipWidth = chipBounds.right - chipBounds.left
        assertTrue(
            "Hotkeys launcher must be a compact chip, not a full-width bar. " +
                "chipWidth=$chipWidth bandWidth=$bandWidth",
            chipWidth < bandWidth * 0.7f,
        )
    }

    @Test
    fun tmuxKeyboardDownHotkeysLauncherChipInlineInChipRowOpensSheet() {
        // Issue #789: with the keyboard DOWN on a terminal pane, the compact
        // hotkeys launcher chip lives INLINE in the bottom chip row (next to
        // Enter / show keyboard / snippets). Tapping it requests opening the same
        // dedicated hotkeys sheet, and the chip is fully on-screen — not the old
        // dedicated full-width bar row above the chips (that row is reclaimed).
        var launched = false
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    isAgentPane = false,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    onShowHotkeysTap = { launched = true },
                )
            }
        }

        captureViewportArtifact("issue789-kbdown-compact-hotkeys-chip.png")

        // No old full-width bar caption.
        compose.onNodeWithText("Terminal hotkeys").assertDoesNotExist()

        // The compact chip sits inline alongside the other primary chips, fully
        // within the root and tappable.
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTextContains(HOTKEYS_CHIP_LABEL)
        compose.assertNodeFullyWithinRoot(TERMINAL_HOTKEYS_LAUNCHER_TAG)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG).performClick()
        compose.waitForIdle()
        assertTrue("Tapping the compact chip should request opening the sheet", launched)
    }

    @Test
    fun tmuxConversationImeOpenDoesNotRenderAccessoryStrip() {
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = true,
                    showConversation = true,
                    sessionLive = true,
                    isAgentPane = true,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    modifier = Modifier.testTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithText("show keyboard").assertDoesNotExist()
        compose.onNodeWithText("Prompt").assertDoesNotExist()
        compose.onNodeWithText("Command").assertDoesNotExist()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.onNodeWithText("Speech capture ready").assertDoesNotExist()
    }

    @Test
    fun tmuxConversationImeOpenRendersNoAttachmentStrip() {
        // Issue #673: the conversation IME-open mode renders no accessory at
        // all. #669 had it render the staged-attachment strip; the maintainer
        // reversed that — attachments are only visible inside the Prompt
        // Composer sheet, never in the session view (even in conversation mode).
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = true,
                    showConversation = true,
                    sessionLive = true,
                    isAgentPane = true,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    modifier = Modifier.testTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_IME_BOTTOM_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
    }

    /**
     * Issue #641: a plain **shell** (non-agent) pane with the IME hidden must
     * still surface the Prompt Composer launcher so the user can compose /
     * dictate input in a shell, not only when an agent is detected. This is
     * the exact state from the dogfood screenshot (shell pane, hidden IME) where
     * only `/ commands` / `Enter` / `show keyboard` / `snippets` were shown and
     * the launcher was missing. The launcher (`onDictateTap`) is wired
     * unconditionally for shell panes too, so it must be displayed and tappable.
     */
    @Test
    fun shellKeyboardHiddenShowsComposerLauncher() {
        var dictateTaps = 0
        var enterTaps = 0
        var keyboardTaps = 0
        var snippetTaps = 0
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    // Shell (non-agent) pane — the regression case from #641.
                    isAgentPane = false,
                    onChipTap = {},
                    onDictateTap = { dictateTaps++ },
                    onEnterTap = { enterTaps++ },
                    onShowKeyboardTap = { keyboardTaps++ },
                    onAddSnippetTap = { snippetTaps++ },
                )
            }
        }

        captureViewportArtifact("issue641-shell-bottom-bar-with-composer-launcher.png")

        // No raw key bar without the IME.
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("^C").assertDoesNotExist()

        // The shell pane keeps Enter / show-keyboard / snippets …
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        // … AND the Prompt Composer launcher (the #641 fix): visible + tappable
        // in shell mode just like an agent pane.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertEquals(1, dictateTaps)
        assertEquals(1, enterTaps)
        assertEquals(1, keyboardTaps)
        assertEquals(1, snippetTaps)
    }

    /**
     * Issue #641 (reopened): the exact dogfood state — a shell band with the
     * full primary cluster (`Enter` + `show keyboard` + `snippets`) plus the
     * launcher present. (Issue #787 hard-cut the former `/ commands` chip, so it
     * is no longer part of the cluster.)
     *
     * Round 1 fixed the launcher being pushed OFF the right edge. The reopened
     * symptom is that the rightmost primary chip (`snippets`) was left
     * HALF-CLIPPED at the cluster's cap boundary — partly hidden behind the
     * launcher. This test asserts the strict acceptance bar: EVERY primary chip
     * AND the launcher are fully inside the viewport (both edges, the FULL
     * unclipped bounds visible), and the `snippets` chip's right edge does not
     * cross the launcher's left edge — i.e. nothing hides behind the launcher.
     */
    @Test
    fun shellBandWithAllPrimaryChipsKeepsComposerLauncherOnScreen() {
        var dictateTaps = 0
        compose.setContent {
            PocketShellTheme {
                BottomChipControls(
                    chips = DefaultSessionChips,
                    onChipTap = {},
                    onDictateTap = { dictateTaps++ },
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                    addSnippetIcon = SnippetsChipIcon,
                    onProjectNavigationTap = null,
                )
            }
        }

        captureViewportArtifact("issue641-shell-band-all-primary-chips.png")

        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()

        // The composer launcher must be FULLY on-screen (both edges inside the
        // root) and tappable — not pushed off the right edge by the four primary
        // chips.
        val launcherBounds = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "composer launcher must be fully inside the viewport, not clipped past " +
                "the right edge; launcher=[${launcherBounds.left}..${launcherBounds.right}] " +
                "root=[${rootBounds.left}..${rootBounds.right}]",
            launcherBounds.left >= rootBounds.left && launcherBounds.right <= rootBounds.right,
        )

        // Every primary chip must be FULLY visible (unclipped bounds inside the
        // root) and tappable — the reopened symptom was a half-clipped chip
        // hidden behind the launcher.
        listOf(
            SESSION_ENTER_CHIP_TAG,
            SHOW_KEYBOARD_CHIP_TAG,
            SESSION_ADD_SNIPPET_CHIP_TAG,
        ).forEach { tag ->
            val bounds = compose.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .getUnclippedBoundsInRoot()
            assertTrue(
                "primary chip '$tag' must be fully inside the viewport, not clipped; " +
                    "chip=[${bounds.left}..${bounds.right}] root=[${rootBounds.left}..${rootBounds.right}]",
                bounds.left >= rootBounds.left && bounds.right <= rootBounds.right,
            )
        }

        // The rightmost primary chip (`snippets`) must NOT overlap the launcher —
        // it cannot be hidden behind / under the composer button. This is the
        // load-bearing assertion for the reopened occlusion symptom.
        val snippetsBounds = compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "snippets chip must not be occluded behind the launcher; its right edge " +
                "(${snippetsBounds.right}) must be at or before the launcher's left edge " +
                "(${launcherBounds.left})",
            snippetsBounds.right <= launcherBounds.left,
        )

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        assertEquals(1, dictateTaps)
    }

    @Test
    fun tmuxKeyboardHiddenShowsControlsWithoutHotkeyBar() {
        val keyTaps = mutableListOf<String>()
        var enterTaps = 0
        var keyboardTaps = 0
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    isAgentPane = true,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = { enterTaps++ },
                    onShowKeyboardTap = { keyboardTaps++ },
                    onAddSnippetTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("^C").assertDoesNotExist()
        compose.onNodeWithText("^D").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()

        assertEquals(emptyList<String>(), keyTaps)
        assertEquals(1, enterTaps)
        assertEquals(1, keyboardTaps)
    }

    @Test
    fun composerLauncherIconKeepsAccentInkInsideSafeArea() {
        compose.setContent {
            PocketShellTheme {
                BottomChipControls(
                    chips = AgentExitChips,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        val bitmap = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .assertIsDisplayed()
            .captureToImage()
            .asAndroidBitmap()
        val ink = accentInkBounds(bitmap)
        assertTrue("composer launcher should render the accent mark", ink.count > 0)

        val minEdgeMargin = minOf(
            ink.left,
            ink.top,
            bitmap.width - 1 - ink.right,
            bitmap.height - 1 - ink.bottom,
        )
        val requiredMargin = bitmap.width * 0.25f
        assertTrue(
            "composer launcher accent mark should stay inside the circular-button safe area; " +
                "ink=$ink bitmap=${bitmap.width}x${bitmap.height} requiredMargin=$requiredMargin",
            minEdgeMargin >= requiredMargin,
        )
    }

    @Test
    fun noStagedAttachmentGridInSessionViewAfterComposerSheetIsClosed() {
        // Issue #673: after the user stages an attachment in the Prompt
        // Composer and closes the sheet, the session/terminal bottom area must
        // NOT show a staged-attachment chip/grid. #669 asserted the opposite
        // (the grid stayed visible/removable in-session); the maintainer
        // reversed that decision. The attachment STATE still lives in the
        // composer ViewModel (covered by the JVM unit test
        // `stagedAttachmentsAndDraftPersistAcrossComposerCloseAndSessionSwitch`),
        // so re-opening the composer shows it again — but the session view
        // stays clean.
        var composerOpen by mutableStateOf(true)

        compose.setContent {
            PocketShellTheme {
                if (!composerOpen) {
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = false,
                        sessionLive = true,
                        isAgentPane = true,
                        onChipTap = {},
                        onDictateTap = { composerOpen = true },
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                    )
                }
            }
        }

        compose.runOnIdle { composerOpen = false }

        // The composer has closed: the session band renders, but no staged
        // attachment grid appears in it.
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()
        captureViewportArtifact("issue673-tmux-closed-composer-no-attachment-chip.png")
    }

    @Test
    fun tmuxHotkeysPanelExposesCtrlCAndCtrlDKeys() {
        // Issue #784: the dedicated panel shows ^C / ^D (and the rest) as direct
        // buttons; tapping fires the binding for the host to route.
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                TerminalHotkeysPanel(
                    sections = TmuxHotkeyPanelSections,
                    onKey = { taps += it.label },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("^C").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^D").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(listOf("^C", "^D"), taps)
    }

    @Test
    fun tmuxHotkeysPanelExposesEmergencyKeysAndRestoredCtrlB() {
        // Issue #784: Esc / ^C / ^D plus the restored ^B (tmux prefix) all show
        // as direct, tappable buttons — no `…` overflow, no lone Ctrl.
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                TerminalHotkeysPanel(
                    sections = TmuxHotkeyPanelSections,
                    onKey = { taps += it.label },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^B").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^C").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^D").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(listOf("Esc", "^B", "^C", "^D"), taps)
    }

    @Test
    fun shellBottomChipControlsRenderCommandsWithMic() {
        var snippetTaps = 0
        var keyboardTaps = 0
        var dictateTaps = 0
        val chipTaps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                BottomChipControls(
                    chips = DefaultSessionChips,
                    onChipTap = { chipTaps += it },
                    onDictateTap = { dictateTaps++ },
                    onShowKeyboardTap = { keyboardTaps += 1 },
                    onAddSnippetTap = { snippetTaps += 1 },
                    addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                    // Issue #454: production (TmuxSessionScreen) renders the
                    // saved-snippet picker chip with the list glyph; capture
                    // the same icon so the artifact matches what the user sees.
                    addSnippetIcon = SnippetsChipIcon,
                    onProjectNavigationTap = null,
                )
            }
        }

        captureViewportArtifact("shell-snippets-bottom-strip.png")

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Dictate").assertDoesNotExist()
        assertEquals(1, dictateTaps)
        compose.onNodeWithText("dictate").assertDoesNotExist()

        // Issue #131 / #221 (round 2): the show-keyboard and picker
        // chips live in a sticky right cluster *outside* the scrolling
        // chip strip, so they are visible without any horizontal scroll.
        // `assertIsDisplayed()` here is a real
        // visibility check; the round-1 implementation kept the primary
        // chips inside the horizontalScroll Row and this assertion
        // caught them being pushed off-screen by the four wide leading
        // static chips. Both chips are located by their stable test tag
        // so the assertion survives a future caption rename.
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertIsDisplayed().performClick()
        assertEquals(1, keyboardTaps)
        compose.onNodeWithText("show keyboard").assertIsDisplayed()
        compose.onNodeWithText("keyboard").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG).assertIsDisplayed().performClick()
        assertEquals(1, snippetTaps)
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertIsDisplayed()
        compose.onNodeWithText("+ snippet").assertDoesNotExist()

        compose.onNodeWithText("git status").assertHasClickAction().performClick()
        assertEquals(listOf("git status"), chipTaps)
    }

    @Test
    fun agentBottomChipBandHasNoSlashCommandsChipButKeepsComposerLauncher() {
        // Issue #787 (D22 hard-cut): the bottom `/ commands` chip is GONE — slash
        // entry now lives only in the composer (its `/` button + type-`/`
        // autocomplete). The agent-pane band keeps the Prompt Composer launcher.
        // `AgentExitChips` is empty; there is no snippet chip on agent panes (the
        // composer's `{}` inserts prompts). The former `Ctrl-C ×2` / `Ctrl-D ×2`
        // interrupt/EOF controls were re-homed into the hotkeys panel, not here.
        var dictateTaps = 0
        val chipTaps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                BottomChipControls(
                    chips = AgentExitChips,
                    onChipTap = { chipTaps += it },
                    onDictateTap = { dictateTaps++ },
                    onShowKeyboardTap = null,
                    onAddSnippetTap = null,
                    addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        // The `/ commands` chip is gone entirely.
        compose.onNodeWithText("/ commands").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed().performClick()
        assertEquals(1, dictateTaps)
        // No snippet / shell-command chips in the agent band.
        compose.onNodeWithText(ADD_PROMPT_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText("git status").assertDoesNotExist()
        captureViewportArtifact("agent-command-and-composer-bottom-strip.png")

        // No scrollable band chips — only sticky primary controls are interactive.
        assertEquals(emptyList<String>(), chipTaps)
    }

    // ─── Issue #459: Conversation shares the unified composer bottom with
    //     Terminal; the key bar is Terminal-only ────────────────────────

    /**
     * Issue #459: in the Conversation tab the bottom band is the unified
     * composer entry — the launcher that opens the shared
     * `PromptComposerSheet`. The Terminal-only "show keyboard" chip (raw-key
     * entry, the gateway to the key bar) must NOT appear. This mirrors how
     * [com.pocketshell.app.tmux.TmuxSessionScreen] wires `BottomChipControls`
     * for an agent pane on its Conversation tab (`onShowKeyboardTap = null`).
     */
    @Test
    fun conversationBottomIsUnifiedComposerWithoutShowKeyboardChip() {
        var dictateTaps = 0
        compose.setContent {
            PocketShellTheme {
                // Exactly how TmuxSessionScreen renders the bottom band when
                // the focused agent pane is showing its Conversation tab:
                // agent exit chips, the snippet/prompt chip, the composer
                // launcher, and NO show-keyboard chip (raw keys aren't sent from
                // Conversation — that's Terminal-only, #459).
                BottomChipControls(
                    chips = AgentExitChips,
                    onChipTap = {},
                    onDictateTap = { dictateTaps++ },
                    onShowKeyboardTap = null,
                    onAddSnippetTap = {},
                    addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        captureViewportArtifact("issue459-conversation-bottom-unified-composer.png")

        // The unified composer entry is present — Conversation's
        // only send affordance.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed().performClick()
        assertEquals(1, dictateTaps)
        // The Terminal-only "show keyboard" chip is absent in Conversation.
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithText("show keyboard").assertDoesNotExist()
        // No key bar (Esc/Tab/Ctrl raw-key row) in Conversation.
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()
    }

    /**
     * Issue #459/#584/#610: the Terminal tab keeps the same unified composer launcher
     * AND the Terminal-only "show keyboard" chip (which raises the soft
     * keyboard / key bar for raw-key entry), plus standalone Enter while the
     * IME is hidden. Paired with
     * [conversationBottomIsUnifiedComposerWithoutShowKeyboardChip] this proves
     * the two bottoms share the composer while the show-keyboard / key-bar
     * affordance is Terminal-only.
     */
    @Test
    fun terminalBottomKeepsEnterAndShowKeyboardWithoutKeyBar() {
        var enterTaps = 0
        compose.setContent {
            PocketShellTheme {
                // How TmuxSessionScreen renders the bottom band on the
                // Terminal tab of an agent pane when the IME is hidden:
                // same composer launcher, plus standalone Enter and show-keyboard.
                BottomChipControls(
                    chips = AgentExitChips,
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = { enterTaps++ },
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        captureViewportArtifact("issue459-terminal-bottom-unified-composer.png")

        val launcherBounds = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "composer launcher should align with the bottom-control bar instead of rendering as a large FAB",
            (launcherBounds.right - launcherBounds.left).value <= 48.5f &&
                (launcherBounds.bottom - launcherBounds.top).value <= 48.5f,
        )
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("show keyboard").assertIsDisplayed()
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("^C").assertDoesNotExist()
        compose.onNodeWithText("^D").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()
        assertEquals(1, enterTaps)
    }

    @Test
    fun assistantStripConfirmOrCorrectRoutesThroughCallbacks() {
        val events = mutableListOf<String>()
        var micTaps = 0
        val dictated = mutableStateOf<AssistantDictationTextEvent?>(null)
        compose.setContent {
            PocketShellTheme {
                AssistantStrip(
                    state = AssistantUiState.Confirming(
                        AssistantAgentLoop.Candidate(
                            toolName = "run_command",
                            summary = "git status --short",
                        ),
                    ),
                    onConfirm = { events += "confirm" },
                    onCorrect = { events += "correct:$it" },
                    onCancel = { events += "cancel" },
                    onDismiss = { events += "dismiss" },
                    correctionDictation = AssistantCorrectionDictation(
                        recording = InlineDictationViewModel.RecordingState.Idle,
                        dictatedText = dictated.value,
                        onDictatedTextConsumed = { dictated.value = null },
                        onMicTap = { micTaps += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithText("Is this what you want me to execute?").assertIsDisplayed()
        compose.onNodeWithText("git status --short").assertIsDisplayed()

        // Confirm-or-correct: choose "No, do something else", type a
        // correction, and send it — proving the rejection path feeds a
        // correction back rather than aborting.
        compose.onNodeWithTag("assistant:correct").performClick()
        compose.onNodeWithTag(ASSISTANT_CORRECTION_MIC_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle {
            dictated.value = AssistantDictationTextEvent(1L, "show the last 5 commits")
        }
        compose.onNodeWithTag("assistant:correction-field")
            .assertTextContains("show the last 5 commits")
        compose.onNodeWithTag("assistant:send-correction").performClick()

        assertEquals(listOf("correct:show the last 5 commits"), events)
        assertEquals(1, micTaps)
    }

    @Test
    fun assistantStripConfirmRunsCandidate() {
        val events = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                AssistantStrip(
                    state = AssistantUiState.Confirming(
                        AssistantAgentLoop.Candidate(
                            toolName = "run_command",
                            summary = "git status --short",
                        ),
                    ),
                    onConfirm = { events += "confirm" },
                    onCorrect = { events += "correct:$it" },
                    onCancel = { events += "cancel" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        compose.onNodeWithTag("assistant:confirm").performClick()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun assistantStripShowsRetryOnlyForRetryableErrors() {
        val events = mutableListOf<String>()
        val retryableState = mutableStateOf(true)
        compose.setContent {
            PocketShellTheme {
                AssistantStrip(
                    state = AssistantUiState.Error(
                        message = "The assistant model transport failed. Try again.",
                        reason = com.pocketshell.app.assistant.AssistantFailureReason.ModelTransport,
                        retryable = retryableState.value,
                    ),
                    onConfirm = { events += "confirm" },
                    onCorrect = { events += "correct:$it" },
                    onCancel = { events += "cancel" },
                    onDismiss = { events += "dismiss" },
                    onRetry = { events += "retry" },
                )
            }
        }

        compose.onNodeWithTag(ASSISTANT_RETRY_TAG).assertIsDisplayed().performClick()
        assertEquals(listOf("retry"), events)

        compose.runOnIdle { retryableState.value = false }
        compose.onNodeWithTag(ASSISTANT_RETRY_TAG).assertDoesNotExist()
    }

    @Test
    fun inlineDictationErrorStripDismissesOnTap() {
        var dismissed = false
        compose.setContent {
            PocketShellTheme {
                InlineDictationErrorStrip(
                    message = "Microphone permission denied.",
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Microphone permission denied.").assertIsDisplayed()
        compose.onNodeWithText("Microphone permission denied.").performClick()
        assertTrue(dismissed)
    }

    private fun captureViewportArtifact(fileName: String) {
        // `additional_test_output/` is pulled by connectedDebugAndroidTest.
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
            val dir = java.io.File(mediaRoot, "additional_test_output/issue-283-bottom-strip")
            if (dir.exists() || dir.mkdirs()) {
                val outFile = java.io.File(dir, fileName)
                outFile.outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                println("ISSUE283_VIEWPORT ${outFile.absolutePath}")
            }
        }
    }

    private fun accentInkBounds(bitmap: android.graphics.Bitmap): InkBounds {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = pixel ushr 24 and 0xFF
                val red = pixel ushr 16 and 0xFF
                val green = pixel ushr 8 and 0xFF
                val blue = pixel and 0xFF
                if (alpha > 180 && red <= 90 && green >= 175 && blue >= 190) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                    count++
                }
            }
        }
        return InkBounds(left = left, top = top, right = right, bottom = bottom, count = count)
    }

    private data class InkBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val count: Int,
    )

    private companion object {
        const val CONVERSATION_IME_BOTTOM_CONTROLS_TAG = "tmux:test:conversation-ime-bottom-controls"
    }
}
