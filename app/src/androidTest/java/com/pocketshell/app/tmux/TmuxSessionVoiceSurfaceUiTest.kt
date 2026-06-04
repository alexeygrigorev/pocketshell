package com.pocketshell.app.tmux

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.assistant.AssistantAgentLoop
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.session.INLINE_DICTATION_MIC_SLOT_TAG
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.KeyBarWithMic
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
import com.pocketshell.app.voice.SESSION_MIC_FAB_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator validation for the tmux terminal bottom controls. The full
 * `TmuxSessionScreen` requires Hilt + a live tmux connect, so this test
 * isolates the input strips the screen renders and verifies #311's
 * session-open controls: terminal chrome keeps dictation on the right
 * while agent-vs-shell sessions expose different prompt/command affordances.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionVoiceSurfaceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tmuxKeyBarExposesInlineMicSlot() {
        var micTaps = 0
        compose.setContent {
            PocketShellTheme {
                KeyBarWithMic(
                    keys = TmuxKeyBarLayout,
                    onKey = {},
                    micState = InlineDictationViewModel.RecordingState.Idle,
                    micAmplitude = 0f,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = { micTaps++ },
                )
            }
        }

        compose.onNodeWithText("Ctrl-C").assertIsDisplayed()
        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG).assertIsDisplayed().performClick()
        assertEquals(1, micTaps)
    }

    @Test
    fun tmuxKeyBarExposesCtrlCAndCtrlDKeys() {
        val taps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                KeyBarWithMic(
                    keys = TmuxKeyBarLayout,
                    onKey = { taps += it.label },
                    micState = InlineDictationViewModel.RecordingState.Idle,
                    micAmplitude = 0f,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = {},
                )
            }
        }

        compose.onNodeWithText("Ctrl-C").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Ctrl-D").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(listOf("Ctrl-C", "Ctrl-D"), taps)
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
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        captureViewportArtifact("shell-command-bottom-strip.png")

        compose.onNodeWithTag(SESSION_MIC_FAB_TAG).assertIsDisplayed().performClick()
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
    fun agentBottomChipControlsRenderPromptsAndExitChipsWithoutShellCommands() {
        var snippetTaps = 0
        var dictateTaps = 0
        val chipTaps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                BottomChipControls(
                    chips = AgentExitChips,
                    onChipTap = { chipTaps += it },
                    onDictateTap = { dictateTaps++ },
                    onShowKeyboardTap = null,
                    onAddSnippetTap = { snippetTaps += 1 },
                    addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                    addSnippetIcon = null,
                    onProjectNavigationTap = null,
                )
            }
        }

        compose.onNodeWithTag(SESSION_MIC_FAB_TAG).assertIsDisplayed().performClick()
        assertEquals(1, dictateTaps)
        compose.onNodeWithText(CtrlC2Chip).assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText(CtrlD2Chip).assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText(ADD_PROMPT_CHIP_LABEL).assertIsDisplayed()
        compose.onNodeWithText("git status").assertDoesNotExist()
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertDoesNotExist()
        captureViewportArtifact("agent-prompt-bottom-strip.png")

        assertEquals(listOf(CtrlC2Chip, CtrlD2Chip), chipTaps)
        assertEquals(1, snippetTaps)
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
            val mediaRoot = instrumentation.targetContext.externalMediaDirs
                .firstOrNull { it != null }
                ?: instrumentation.targetContext.getExternalFilesDir(null)
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
