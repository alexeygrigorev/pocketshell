package com.pocketshell.app.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.composer.COMPOSER_ATTACHMENT_CHIPS_TAG
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.composerAttachmentChipTestTag
import com.pocketshell.app.composer.composerAttachmentRemoveTestTag
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SESSION_MIC_FAB_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator validation hooks for issues #63 and #65. These render the same
 * key-bar dictation surface used by [SessionScreen] with mocked FSM states
 * so connected tests can assert the recording waveform and transcribing
 * spinner without depending on a real microphone or Whisper latency.
 */
@RunWith(AndroidJUnit4::class)
class InlineDictationUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rawSshTerminalKeyBarExposesEmergencyAndEnterKeysWhenRendered() {
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                KeyBar(
                    keys = SessionTerminalKeyBarLayout,
                    onKey = { taps += it.label },
                )
            }
        }

        listOf("Esc", "Ctrl-C", SessionKeyBarEnterLabel, "Ctrl-D").forEach { label ->
            compose.onNodeWithText(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
        }

        assertEquals(listOf("Esc", "Ctrl-C", SessionKeyBarEnterLabel, "Ctrl-D"), taps)
    }

    @Test
    fun rawSshKeyboardHiddenShowsEnterAndShowKeyboardWithoutKeyBar() {
        val keyTaps = mutableListOf<String>()
        var keyboardTaps = 0
        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = { keyboardTaps++ },
                    onAddSnippetTap = null,
                    onProjectNavigationTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-C").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-D").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText(SessionKeyBarEnterLabel).assertIsDisplayed()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SESSION_MIC_FAB_TAG).assertIsDisplayed()

        assertEquals(listOf(SessionKeyBarEnterLabel), keyTaps)
        assertEquals(1, keyboardTaps)
    }

    @Test
    fun rawSshStagedAttachmentRemainsRemovableAfterComposerSheetIsClosed() {
        val attachment = PromptComposerViewModel.StagedAttachment(
            remotePath = "~/.pocketshell/attachments/raw-host/shot.png",
            displayName = "shot.png",
        )
        var staged by mutableStateOf(listOf(attachment))

        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    onKey = {},
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = null,
                    onProjectNavigationTap = {},
                    stagedAttachments = staged,
                    onRemoveStagedAttachment = { remotePath ->
                        staged = staged.filterNot { it.remotePath == remotePath }
                    },
                )
            }
        }

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
    fun rawSshConversationWithImeDoesNotShowTerminalKeyBar() {
        val keyTaps = mutableListOf<String>()
        var dictateTaps = 0
        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = true,
                    showConversation = true,
                    sessionLive = true,
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = { dictateTaps++ },
                    onShowKeyboardTap = {},
                    onAddSnippetTap = null,
                    onProjectNavigationTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-C").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-D").assertDoesNotExist()
        compose.onNodeWithTag(SESSION_MIC_FAB_TAG).assertIsDisplayed().performClick()

        assertEquals(emptyList<String>(), keyTaps)
        assertEquals(1, dictateTaps)
    }

    @Test
    fun rawSshKeyboardOpenAccessoryKeepsStagedAttachmentRemovable() {
        val keyTaps = mutableListOf<String>()
        val attachment = PromptComposerViewModel.StagedAttachment(
            remotePath = "~/.pocketshell/attachments/raw-host/shot.png",
            displayName = "shot.png",
        )
        var staged by mutableStateOf(listOf(attachment))
        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = true,
                    showConversation = false,
                    sessionLive = true,
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = null,
                    onProjectNavigationTap = {},
                    stagedAttachments = staged,
                    onRemoveStagedAttachment = { remotePath ->
                        staged = staged.filterNot { it.remotePath == remotePath }
                    },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachment.remotePath))
            .assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentRemoveTestTag(attachment.remotePath))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachment.remotePath))
            .assertDoesNotExist()

        compose.onNodeWithText("Esc").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Ctrl-C").assertIsDisplayed()
        compose.onNodeWithText(SessionKeyBarEnterLabel).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Ctrl-D").assertIsDisplayed()
        compose.onNodeWithText("Tab").assertIsDisplayed()
        compose.onNodeWithText("Prompt").assertDoesNotExist()
        compose.onNodeWithText("Command").assertDoesNotExist()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.onNodeWithText("Speech capture ready").assertDoesNotExist()
        compose.onNodeWithTag(SESSION_MIC_FAB_TAG).assertDoesNotExist()

        assertEquals(listOf("Esc"), keyTaps)
    }

    @Test
    fun recordingStateShowsAmplitudeWaveformOnInlineMicSlot() {
        renderKeyBar(
            micState = InlineDictationViewModel.RecordingState.Recording,
            micAmplitude = 0.74f,
        )

        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .assert(hasContentDescription("Inline dictation recording waveform"))
        compose.onNodeWithTag(INLINE_DICTATION_WAVEFORM_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(INLINE_DICTATION_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Recording").assertIsDisplayed()
    }

    @Test
    fun transcribingStateShowsSpinnerAndBlocksDuplicateTap() {
        var micTaps = 0
        renderKeyBar(
            micState = InlineDictationViewModel.RecordingState.Transcribing,
            micAmplitude = 0f,
            onMicTap = { micTaps++ },
        )

        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .assert(hasContentDescription("Inline dictation transcribing"))
            .assert(hasNoClickAction())
        compose.onNodeWithTag(INLINE_DICTATION_TRANSCRIBING_TAG)
            .assertExists()
        compose.onNodeWithTag(INLINE_DICTATION_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Transcribing").assertIsDisplayed()
        assertEquals(0, micTaps)
    }

    @Test
    fun failedStateShowsInlineStatusInSpeechCaptureArea() {
        renderKeyBar(
            micState = InlineDictationViewModel.RecordingState.Idle,
            micAmplitude = 0f,
            dictationError = "Network error: no DNS",
        )

        compose.onNodeWithTag(INLINE_DICTATION_STATUS_TAG)
            .assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Network error: no DNS").assertIsDisplayed()
    }

    private fun renderKeyBar(
        micState: InlineDictationViewModel.RecordingState,
        micAmplitude: Float,
        dictationError: String? = null,
        onMicTap: () -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                KeyBarWithMic(
                    keys = testKeys,
                    onKey = {},
                    micState = micState,
                    micAmplitude = micAmplitude,
                    dictationError = dictationError,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = onMicTap,
                )
            }
        }
    }

    private companion object {
        val testKeys: List<KeyBinding> = listOf(
            KeyBinding(label = "Esc", kind = KeyKind.Regular),
            KeyBinding(label = "Tab", kind = KeyKind.Regular),
            KeyBinding(label = "Ctrl", kind = KeyKind.Modifier),
            KeyBinding(label = "Alt", kind = KeyKind.Modifier),
            KeyBinding(label = "<", kind = KeyKind.Arrow),
            KeyBinding(label = "v", kind = KeyKind.Arrow),
            KeyBinding(label = "^", kind = KeyKind.Arrow),
            KeyBinding(label = ">", kind = KeyKind.Arrow),
        )
    }
}
