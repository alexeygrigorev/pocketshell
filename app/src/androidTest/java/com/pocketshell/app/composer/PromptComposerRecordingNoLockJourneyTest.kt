package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.MicCapture
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.composer.PromptComposerViewModel.VoiceSettingsSnapshot
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1245: the hands-free "lock" was removed from the Prompt Composer
 * ENTIRELY — both the inline lock icon AND all its functionality (the
 * swipe-up-to-lock gesture, the lock toggle, the "recording locked" indicator,
 * the "tap the lock" hint). Recording now works with NO lock concept: a mic tap
 * starts recording, the panel shows the timer + waveform + Discard/Insert/Send,
 * and those stop actions end it. This drives the REAL [PromptComposerSheet]
 * inside its [androidx.compose.material3.ModalBottomSheet] with a real
 * [PromptComposerViewModel].
 *
 * This is the durable regression for #1245 (a REOPEN — the prior round merely
 * RELOCATED the lock inline instead of removing it):
 *  - [recordingHasNoLockControlIndicatorOrHint] fails RED on the unfixed code
 *    (the lock toggle / indicator / hint nodes still exist) and passes GREEN once
 *    the lock is gone. It references the OLD tag strings directly (the constants
 *    were deleted) so it compiles against both the fixed and unfixed trees.
 *  - [discardSitsInOneRowWithInsertAndSend] fails RED on the unfixed layout
 *    (Discard was a separate button ABOVE Insert/Send — no vertical overlap) and
 *    passes GREEN once Discard rides the SAME balanced action row as Insert and
 *    Send (vertical overlap + Discard to the left).
 *  - [recordingStartsAndStopsWithoutAnyLockStep] proves recording still works end
 *    to end without a lock (tap mic → Recording → Discard → Idle).
 *
 * No Docker fixture (fakes only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerRecordingNoLockJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantRecordAudio() {
        val targetPackage = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant $targetPackage android.permission.RECORD_AUDIO",
        ).close()
    }

    private class FakeMicCapture : MicCapture {
        var startCount = 0
        var stopCount = 0
        private var running = false

        override fun start() {
            startCount++
            running = true
        }

        override fun stop(): ByteArray {
            stopCount++
            running = false
            return SpeechAudioGuard.speechWavForTesting()
        }

        override fun currentAmplitude(): Float = if (running) 0.65f else 0f
    }

    private class FakeVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class FakeVoiceSettings : VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = 600_000L
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(mic: MicCapture): PromptComposerViewModel {
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                Result.success("dictation")
        }
        return PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = FakeVault(),
            voiceSettings = FakeVoiceSettings(),
            savedStateHandle = SavedStateHandle(),
        )
    }

    private fun renderComposer(viewModel: PromptComposerViewModel) {
        var sheetVisible by mutableStateOf(true)
        compose.setContent {
            PocketShellTheme {
                if (sheetVisible) {
                    PromptComposerSheet(
                        onDismiss = { sheetVisible = false },
                        onSend = { _ -> true },
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_MIC_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun startRecording(viewModel: PromptComposerViewModel) {
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording
        }
        compose.waitForIdle()
    }

    // Old (deleted) lock tags — referenced as raw strings so this test compiles
    // against both the fixed tree (where they no longer render) and the unfixed
    // tree (where they still do, making the assertions RED).
    private val oldLockControlTag = "prompt-composer-lock-recording"
    private val oldLockedIndicatorTag = "prompt-composer-recording-locked"
    private val oldLockHintTag = "prompt-composer-lock-hint"

    @Test
    fun recordingHasNoLockControlIndicatorOrHint() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderComposer(viewModel)
        startRecording(viewModel)

        // The lock is GONE (#1245): no tappable lock toggle, no "recording locked"
        // indicator, and no "tap the lock / swipe up" hint anywhere in the
        // recording UI. On the unfixed code the toggle + hint render, so these are
        // the load-bearing RED→GREEN assertions.
        compose.onNodeWithTag(oldLockControlTag, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(oldLockedIndicatorTag, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(oldLockHintTag, useUnmergedTree = true).assertDoesNotExist()

        // Recording UI is otherwise intact.
        compose.onNodeWithTag(COMPOSER_TIMER_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG, useUnmergedTree = true).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-1245-recording-no-lock")
    }

    @Test
    fun discardSitsInOneRowWithInsertAndSend() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderComposer(viewModel)
        startRecording(viewModel)

        val discard = compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val insert = compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        // Discard rides the SAME horizontal action row as Insert and Send — it
        // vertically overlaps both. On the unfixed layout Discard was a separate
        // button stacked ABOVE Insert/Send (Discard.bottom <= Insert.top, no
        // overlap), so this is the load-bearing RED→GREEN for "Discard moved next
        // to Insert/Send".
        val overlapsInsert = discard.top < insert.bottom && discard.bottom > insert.top
        val overlapsSend = discard.top < send.bottom && discard.bottom > send.top
        assertTrue(
            "Discard is NOT on the same action row as Insert/Send (#1245). " +
                "discard=$discard insert=$insert send=$send",
            overlapsInsert && overlapsSend,
        )
        // Reading order in the balanced row: Discard · Insert · Send.
        assertTrue(
            "Discard should sit to the LEFT of Insert/Send in the action row. " +
                "discard=$discard insert=$insert send=$send",
            discard.left <= insert.left && insert.left <= send.left,
        )
        WalkthroughScreenshotArtifacts.capture("issue-1245-discard-in-action-row")
    }

    @Test
    fun recordingStartsAndStopsWithoutAnyLockStep() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderComposer(viewModel)

        // A plain mic tap starts recording — no press-and-hold, no swipe-to-lock.
        startRecording(viewModel)
        assertEquals("Tap must start exactly one capture", 1, mic.startCount)
        assertEquals("Capture must still be live", 0, mic.stopCount)
        assertEquals(RecordingState.Recording, viewModel.uiState.value.recording)

        // All three stop actions are present and reachable.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()

        // Discard ends the recording and returns to Idle (the mic disc reappears).
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle
        }
        assertEquals("Discard should stop the capture once", 1, mic.stopCount)
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
    }
}
