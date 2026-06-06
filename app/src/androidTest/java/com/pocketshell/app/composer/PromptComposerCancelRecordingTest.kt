package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #453: emulator acceptance for the redesigned composer's Cancel
 * affordance.
 *
 * The recording UI was redesigned (issue #453 / #508): the Recording state
 * is stopped via two explicit actions ("Insert" / "Send"), and the Cancel
 * affordance lives in the **Transcribing** state — it cancels the in-flight
 * transcription and restores the composer to Idle with the typed draft
 * preserved. This test renders [SheetContent] in the Transcribing state and
 * exercises that Cancel affordance via its [COMPOSER_CANCEL_RECORDING_TAG]
 * tag and its `contentDescription`.
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
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()

        // Tap Cancel — it cancels the in-flight transcription and restores
        // the composer to Idle with the typed draft preserved.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
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
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed()
    }

    @Test
    fun cancelIsHiddenInIdleAndRecording() {
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "",
                recording = PromptComposerViewModel.RecordingState.Idle,
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
                    onCancelRecording = {},
                )
            }
        }

        // Idle: no Cancel; the mic trigger + (disabled) Send are shown.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed()

        // Recording: still no Cancel — the two explicit stop actions
        // ("Insert" / "Send") drive the stop->transcribe transition instead.
        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Recording,
                hasDetectedSpeech = true,
                recordingElapsedMs = 5_000L,
            )
        }
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
    }
}
