package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #585: a Telegram-style hold-and-pull-up on the Prompt Composer mic
 * must start recording and lock it hands-free in one gesture. This drives the
 * real [PromptComposerSheet] inside its [androidx.compose.material3.ModalBottomSheet]
 * with a real [PromptComposerViewModel] so the mic gesture competes with the
 * same sheet drag detector that regressed on device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerMicSwipeLockJourneyTest {

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
                Result.success("locked dictation")
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

    @Test
    fun holdMicThenSwipeUpStartsAndLocksHandsFreeRecording() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderComposer(viewModel)

        compose.onNodeWithTag(COMPOSER_MIC_TAG, useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(80L)
                repeat(8) {
                    moveBy(Offset(0f, -28f))
                    advanceEventTime(16L)
                }
                up()
            }

        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording &&
                viewModel.uiState.value.recordingLocked
        }
        compose.waitForIdle()

        assertEquals("Swipe-up lock should start exactly one capture", 1, mic.startCount)
        assertEquals("Finger-up after locking must not stop capture", 0, mic.stopCount)
        assertEquals(RecordingState.Recording, viewModel.uiState.value.recording)
        assertTrue(viewModel.uiState.value.recordingLocked)
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-585-mic-swipe-locked")

        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle &&
                !viewModel.uiState.value.recordingLocked
        }
        assertEquals("Discard should stop the locked capture once", 1, mic.stopCount)
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
    }

    @Test
    fun plainTapStartsRecordingWithoutLocking() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderComposer(viewModel)

        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording
        }
        compose.waitForIdle()

        assertEquals(1, mic.startCount)
        assertEquals(0, mic.stopCount)
        assertEquals(RecordingState.Recording, viewModel.uiState.value.recording)
        assertFalse(viewModel.uiState.value.recordingLocked)
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertDoesNotExist()

        viewModel.cancelRecording()
        compose.waitForIdle()
    }
}
