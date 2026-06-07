package com.pocketshell.app.tmux

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.composerAttachmentChipTestTag
import com.pocketshell.app.composer.composerAttachmentRemoveTestTag
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
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.app.voice.SnippetsChipIcon
import com.pocketshell.uikit.components.KeyBar
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
    fun tmuxKeyboardOpenAccessoryShowsHotkeysOnly() {
        val keyTaps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                TmuxTerminalBottomControls(
                    isImeVisible = true,
                    showConversation = false,
                    sessionLive = true,
                    isAgentPane = false,
                    keyBarExpanded = false,
                    onKeyBarExpandedChange = {},
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^C").assertIsDisplayed()
        compose.onNodeWithText("^D").assertIsDisplayed()
        compose.onNodeWithText("Tab").assertIsDisplayed()

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithText("show keyboard").assertDoesNotExist()
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Prompt").assertDoesNotExist()
        compose.onNodeWithText("Command").assertDoesNotExist()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.onNodeWithText("Speech capture ready").assertDoesNotExist()

        assertEquals(listOf("Esc"), keyTaps)
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
                    keyBarExpanded = false,
                    onKeyBarExpandedChange = {},
                    onKey = { keyTaps += it.label },
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
    fun stagedAttachmentRemainsRemovableAfterComposerSheetIsClosed() {
        val attachment = PromptComposerViewModel.StagedAttachment(
            remotePath = "~/.pocketshell/attachments/host-1-git-pocketshell-c/shot.png",
            displayName = "shot.png",
        )
        var staged by mutableStateOf(listOf(attachment))
        var composerOpen by mutableStateOf(true)

        compose.setContent {
            PocketShellTheme {
                if (!composerOpen) {
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = false,
                        sessionLive = true,
                        isAgentPane = true,
                        keyBarExpanded = false,
                        onKeyBarExpandedChange = {},
                        onKey = {},
                        onChipTap = {},
                        onDictateTap = { composerOpen = true },
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                        stagedAttachments = staged,
                        onRemoveStagedAttachment = { remotePath ->
                            staged = staged.filterNot { it.remotePath == remotePath }
                        },
                    )
                }
            }
        }

        compose.runOnIdle { composerOpen = false }

        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachment.remotePath))
            .assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentRemoveTestTag(attachment.remotePath))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachment.remotePath))
            .assertDoesNotExist()
    }

    @Test
    fun tmuxKeyBarExposesCtrlCAndCtrlDKeys() {
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                KeyBar(
                    keys = tmuxKeyBarLayout(expanded = false),
                    onKey = { taps += it.label },
                )
            }
        }

        compose.onNodeWithText("^C").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^D").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(listOf("^C", "^D"), taps)
    }

    @Test
    fun tmuxTerminalKeyBarExposesEmergencyKeysWithoutIme() {
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                KeyBar(
                    keys = tmuxKeyBarLayout(expanded = false),
                    onKey = { taps += it.label },
                )
            }
        }

        compose.onNodeWithText("Esc").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^C").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("^D").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(listOf("Esc", "^C", "^D"), taps)
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
    fun agentBottomChipBandIsJustTheComposerLauncher() {
        // Issue #454/#610: the agent-pane band is decluttered to JUST the
        // Prompt Composer launcher.
        // The former "/ commands" bottom chip is gone — the dedicated "/"
        // command-palette button in the session header (issue #462) is the
        // single, obvious entry to that palette, so the bottom chip was a
        // redundant duplicate. The former `Ctrl-C x2` / `Ctrl-D x2` chips
        // already moved into the palette (session-control rows), and there is
        // no snippet chip on agent panes (the composer's `{}` inserts prompts).
        // `AgentExitChips` is now empty.
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

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed().performClick()
        assertEquals(1, dictateTaps)
        // The "/ commands" bottom chip is gone (it lives in the header now).
        compose.onNodeWithText(AgentCommandsChip).assertDoesNotExist()
        // The interrupt/EOF chips are no longer in the band (they live in the palette).
        compose.onNodeWithText(CtrlC2Chip).assertDoesNotExist()
        compose.onNodeWithText(CtrlD2Chip).assertDoesNotExist()
        // No snippet / shell-command chips in the agent band.
        compose.onNodeWithText(ADD_PROMPT_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText("git status").assertDoesNotExist()
        captureViewportArtifact("agent-composer-launcher-only-bottom-strip.png")

        // No band chips at all — only the composer launcher is interactive.
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

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()
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
}
