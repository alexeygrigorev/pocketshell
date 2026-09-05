package com.pocketshell.next.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The RECORD_AUDIO gate (#2521) and the Insert vs Send chrome on the
 * Prompt Composer sheet.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerPermissionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a mic tap without permission requests it and does not start dictation`() {
        var micTaps = 0
        var requested = 0
        setContent(
            hasPermission = false,
            onMicTap = { micTaps += 1 },
            onPermissionDenied = { requested += 1 },
        )

        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals("the recognizer must not start before RECORD_AUDIO is granted", 0, micTaps)
        assertEquals(1, requested)
    }

    @Test
    fun `a mic tap with permission starts dictation`() {
        var micTaps = 0
        setContent(hasPermission = true, onMicTap = { micTaps += 1 })

        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(1, micTaps)
    }

    @Test
    fun `the sheet is titled Prompt Composer and offers Insert and Send`() {
        var inserts = 0
        var sends = 0
        setContent(
            state = ComposerUiState(draft = "hello", micAvailable = true),
            onInsert = { inserts += 1 },
            onSend = { sends += 1 },
        )

        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        assertEquals(1, inserts)
        assertEquals(1, sends)
    }

    @Test
    fun `the title hides when the ime is up`() {
        composeRule.setContent {
            PocketShellTheme {
                PromptComposerContent(
                    state = ComposerUiState(draft = "hello", micAvailable = true),
                    onClose = {},
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
                    onDiscard = {},
                    imeVisible = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
    }

    @Test
    fun `the title returns when the ime is down`() {
        composeRule.setContent {
            PocketShellTheme {
                PromptComposerContent(
                    state = ComposerUiState(draft = "hello", micAvailable = true),
                    onClose = {},
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
                    onDiscard = {},
                    imeVisible = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun `decideMicTap requests permission only when starting without it`() {
        assertEquals(MicTapAction.RequestPermission, decideMicTap(hasRecordAudioPermission = false, recording = false))
        assertEquals(MicTapAction.StartOrStop, decideMicTap(hasRecordAudioPermission = true, recording = false))
        assertEquals(MicTapAction.StartOrStop, decideMicTap(hasRecordAudioPermission = false, recording = true))
        assertEquals(MicTapAction.StartOrStop, decideMicTap(hasRecordAudioPermission = true, recording = true))
    }

    private fun setContent(
        state: ComposerUiState = ComposerUiState(micAvailable = true),
        hasPermission: Boolean = true,
        onMicTap: () -> Unit = {},
        onInsert: () -> Unit = {},
        onSend: () -> Unit = {},
        onPermissionDenied: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                PromptComposerContent(
                    state = state,
                    onClose = {},
                    onDraftChange = {},
                    onSend = onSend,
                    onInsert = onInsert,
                    onAttach = {},
                    onMicTap = {
                        when (
                            decideMicTap(
                                hasRecordAudioPermission = hasPermission,
                                recording = state.recording == RecordingState.Recording,
                            )
                        ) {
                            MicTapAction.StartOrStop -> onMicTap()
                            MicTapAction.RequestPermission -> onPermissionDenied()
                        }
                    },
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscard = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertIsDisplayed()
    }
}
