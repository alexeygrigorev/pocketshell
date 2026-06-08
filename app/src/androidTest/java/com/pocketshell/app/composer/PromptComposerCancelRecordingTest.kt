package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #174 / #453: emulator acceptance for the composer's recording
 * Discard and transcribing Cancel affordances.
 *
 * Recording has a distinct **Discard** affordance that stops the mic or
 * Android recognizer and drops the live buffer. Transcribing keeps its
 * **Cancel** affordance for aborting an already-started round-trip or
 * final-result wait. Both preserve the typed draft and keep the composer open.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerCancelRecordingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cancelDuringTranscribingRestoresComposerWithDraftPreserved() {
        var cancelCalls = 0
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "draft the user wants to keep",
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
            ),
        )

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = { _ -> },
                    onCancelRecording = {
                        throw AssertionError("recording discard should not be called while transcribing")
                    },
                    onCancelTranscription = {
                        cancelCalls += 1
                        // Simulate the ViewModel's cancelTranscription():
                        // restore to Idle with the draft intact.
                        state = state.copy(
                            recording = PromptComposerViewModel.RecordingState.Idle,
                        )
                    },
                )
            }
        }

        // The Transcribing surface + Cancel are visible.
        compose.onNodeWithText("Transcribing…").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_TRANSCRIPTION_TAG).assertIsDisplayed()

        // Tap Cancel — it cancels the in-flight transcription and restores
        // the composer to Idle with the typed draft preserved.
        compose.onNodeWithTag(COMPOSER_CANCEL_TRANSCRIPTION_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, cancelCalls)
        assertEquals(
            PromptComposerViewModel.RecordingState.Idle,
            state.recording,
        )
        assertEquals("draft the user wants to keep", state.draft)
        assertNull(state.error)
        // Back on Idle the Cancel affordance is gone and the editable input
        // + Send return.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_CANCEL_TRANSCRIPTION_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed()
    }

    @Test
    fun discardIsHiddenInIdleAndVisibleInRecording() {
        var discardCalls = 0
        val attachmentPath = "~/.pocketshell/attachments/host-1/keep-this.png"
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "typed draft",
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                attachments = listOf(
                    PromptComposerViewModel.StagedAttachment(
                        remotePath = attachmentPath,
                        displayName = "keep-this.png",
                    ),
                ),
            ),
        )

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = { _ -> },
                    onCancelRecording = {
                        discardCalls += 1
                        state = state.copy(recording = PromptComposerViewModel.RecordingState.Idle)
                    },
                )
            }
        }

        // Idle: no Cancel; the mic trigger + (disabled) Send are shown.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Start dictation. Swipe up to lock recording")
            .assertIsDisplayed()

        // Recording: explicit Discard is visible inside the recording panel,
        // separate from the header close `×` and separate from the two
        // stop+transcribe actions.
        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Recording,
                hasDetectedSpeech = true,
                recordingElapsedMs = 5_000L,
                liveTranscript = "android partial words",
            )
        }
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Discard recording without transcribing").assertIsDisplayed()
        val discardBounds = compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "Discard recording touch target must be at least 48dp tall",
            discardBounds.bottom - discardBounds.top >= 48.dp,
        )
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_LIVE_TRANSCRIPT_TAG).assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, discardCalls)
        assertEquals(PromptComposerViewModel.RecordingState.Idle, state.recording)
        assertEquals("typed draft", state.draft)
        assertEquals(1, state.attachments.size)
        assertEquals(attachmentPath, state.attachments.single().remotePath)
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachmentPath)).assertExists()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
    }
}
