package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #174: emulator acceptance for the cancel-recording chip.
 *
 * Renders [SheetContent] backed by a real [PromptComposerViewModel] with
 * in-memory fakes for the microphone and Whisper client. The test
 * sequence reproduces the bug-report journey:
 *
 *  1. User types a partial prompt.
 *  2. User taps mic -> composer enters `Recording`.
 *  3. User taps the new `X` cancel chip rendered next to the mic FAB.
 *  4. Composer returns to `Idle`, the typed text is preserved verbatim,
 *     and the Whisper client is never called (so no API cost is paid).
 *
 * The cancel chip is located by its [COMPOSER_CANCEL_RECORDING_TAG] test
 * tag and its `contentDescription = "Cancel recording"`, exercising both
 * the testing-affordance and accessibility surfaces in the same run.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerCancelRecordingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * In-memory mic for the connected test. Behaves like
     * [com.pocketshell.app.composer.PromptComposerViewModelTest.FakeMicCapture]
     * but is duplicated here because Robolectric-only test classes are
     * not visible to androidTest.
     */
    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        var startCount = 0
        var stopCount = 0

        override fun start() {
            startCount++
        }

        override fun stop(): ByteArray {
            stopCount++
            return ByteArray(44) { 0 }
        }

        override fun currentAmplitude(): Float = 0.5f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }

        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    @Test
    fun cancelChipDuringRecordingReturnsToIdleWithoutWhisperCall() {
        val mic = TestMicCapture()
        val whisperCalls = AtomicInteger(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> {
                whisperCalls.incrementAndGet()
                return Result.success("should-never-be-appended")
            }
        }

        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = {
                        // Bypass the runtime permission gate the activity
                        // normally drives — the connected test grants
                        // RECORD_AUDIO via the AndroidJUnit runner harness.
                        vm.onMicTap()
                    },
                    onSend = { _ -> },
                    onCancelRecording = vm::cancelRecording,
                )
            }
        }

        // 1. Type the partial prompt the user is about to abandon.
        val typedDraft = "draft the user wants to keep"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(typedDraft)

        // 2. Cancel chip is hidden in Idle — sanity check the affordance
        //    is gated on Recording.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()

        // 3. Start recording.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }

        // 4. Cancel chip is now visible and labelled for screen readers.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel recording").assertIsDisplayed()
        // Issue #195: `TestMicCapture.currentAmplitude()` returns 0.5f on
        // every poll — well above [SILENCE_AMPLITUDE_THRESHOLD] — so the
        // sampler loop flips `hasDetectedSpeech` to true within one poll
        // and the status label moves from "LISTENING" to "CAPTURING".
        // Either reading proves we are in Recording for the cancel
        // affordance under test; `COMPOSER_STATUS_TAG` is the stable
        // anchor that survives the sub-state split.
        compose.onNodeWithTag(COMPOSER_STATUS_TAG).assertIsDisplayed()

        // 5. Tap cancel. The chip dispatches into
        //    PromptComposerViewModel.cancelRecording(), which stops the
        //    mic, discards the buffer, and lands the FSM on Idle.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle
        }

        // 6. Composer is back on Idle, the typed draft is intact, the
        //    Whisper API was never called, and no error banner was
        //    raised — cancel is a user-driven discard, not an
        //    interruption.
        assertEquals(
            PromptComposerViewModel.RecordingState.Idle,
            vm.uiState.value.recording,
        )
        assertEquals(typedDraft, vm.uiState.value.draft)
        assertEquals(0, whisperCalls.get())
        assertNull(vm.uiState.value.error)
        // Recorder was stopped exactly once — by the cancel path, not
        // by stopAndTranscribe.
        assertEquals(1, mic.stopCount)

        // 7. After cancel, the chip is hidden again — the FSM is back
        //    where it started.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelChipIsHiddenDuringTranscribing() {
        // Reproduces the "stale tap" case: once audio has been sent to
        // Whisper, the cancel chip must not be rendered (the API cost
        // is already paid). Implemented by rendering SheetContent
        // directly with a hand-built Transcribing UiState — exercising
        // the visual gate without standing up a suspended coroutine,
        // which would risk wedging the instrumentation runner.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SheetContent(
                    state = PromptComposerViewModel.UiState(
                        draft = "in-flight prompt",
                        recording = PromptComposerViewModel.RecordingState.Transcribing,
                        amplitude = 0f,
                    ),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = { _ -> },
                    onCancelRecording = {},
                )
            }
        }

        compose.onNodeWithText("TRANSCRIBING").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelRecordingFromIdleIsHiddenAndDoesNothing() {
        // Defensive: tap can't reach the chip when it isn't rendered,
        // but we still want a tracer that the FSM stays put if
        // cancelRecording() is invoked outside Recording.
        val mic = TestMicCapture()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> = Result.success("never")
        }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { _ -> },
                    onCancelRecording = vm::cancelRecording,
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertDoesNotExist()
        compose.runOnUiThread { vm.cancelRecording() }
        compose.waitForIdle()
        assertEquals(
            PromptComposerViewModel.RecordingState.Idle,
            vm.uiState.value.recording,
        )
        assertEquals(0, mic.stopCount)
    }
}
