package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #508 (supersedes #453's Auto-send toggle, and the #211/#210 UI flow):
 * emulator coverage of the redesigned "dictate then choose" path.
 *
 * The Recording row now offers two explicit stop actions instead of a
 * persistent Auto-send toggle:
 *  - **"To field"** ([COMPOSER_TO_FIELD_TAG]) — stop + transcribe, drop the
 *    text into the editable composer field; nothing is sent.
 *  - **"Send"** ([COMPOSER_STOP_SEND_TAG]) — stop + transcribe + send
 *    immediately. Also shown in Transcribing to arm the queued send for the
 *    in-flight clip.
 *
 * Renders [SheetContent] backed by a real [PromptComposerViewModel] with
 * in-memory fakes for the microphone and Whisper client. The `sendRequests`
 * flow is collected so the test can assert what the sheet would have routed
 * into the host's `onSend` callback. The millisecond-level FSM behaviour and
 * the #210 cancel-then-redictate regression are pinned at the unit level in
 * [com.pocketshell.app.composer.PromptComposerViewModelTest].
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSendWhileRecordingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        collectorScope.cancel()
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
        override fun stop(): ByteArray {
            stopCount++
            // Issue #452: real-speech WAV so the silence guard lets the
            // capture through to Whisper.
            return SpeechAudioGuard.speechWavForTesting()
        }
        override fun currentAmplitude(): Float = 0.5f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private fun setupContent(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val sent = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        compose.setContent {
            PocketShellTheme {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { withEnter -> vm.requestSend(withEnter) },
                    onCancelRecording = vm::cancelTranscription,
                )
            }
        }
        collectorScope.launch {
            vm.sendRequests.collect { sent += it }
        }
        return sent
    }

    @Test
    fun sendButtonStopsTranscribesAndSendsCombinedTranscript() {
        // Issue #508: tapping the "Send" stop button stops recording,
        // transcription runs, the transcript is appended to the existing
        // draft, and the send fires with the combined text.
        val mic = TestMicCapture()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> = Result.success("from dictation")
        }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )
        val sent = setupContent(vm)

        // 1. Type a preface.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("Tell me ")
        // 2. Start recording (mic trigger in Idle).
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        // 3. Tap the "Send" stop button → stop -> transcribe -> send.
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }

        // The send carries the combined draft and submits with Enter.
        assertEquals(1, sent.size)
        assertEquals("Tell me from dictation", sent[0].text)
        assertEquals(true, sent[0].withEnter)
        assertEquals(1, mic.stopCount)
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft == "" }
    }

    @Test
    fun sendWhileTranscribingFiresOnlyAfterWhisperSuccess() {
        // Issue #508: tapping "Send" while Transcribing arms the queued send;
        // it must NOT fire until Whisper completes.
        val release = CompletableDeferred<Unit>()
        val whisperCalls = AtomicInteger(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> {
                whisperCalls.incrementAndGet()
                release.await()
                return Result.success("queued result")
            }
        }
        val vm = PromptComposerViewModel(
            audioRecorder = TestMicCapture(),
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )
        val sent = setupContent(vm)

        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        // "To field" stop -> Transcribing (parked on the latch).
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Transcribing
        }

        // Nothing dispatched yet — the latch is still held.
        assertEquals(0, sent.size)
        // The "Send" action is still shown during transcription.
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        // Still nothing — Whisper hasn't returned.
        assertEquals(0, sent.size)

        // Release Whisper.
        release.complete(Unit)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }

        assertEquals(1, sent.size)
        assertEquals("queued result", sent[0].text)
        assertEquals(true, sent[0].withEnter)
    }

    @Test
    fun toFieldStopInsertsTranscriptIntoEditableDraftWithoutSending() {
        // Issue #508: tapping "To field" drops the completed dictation into
        // the editable input for review — no send fires.
        val mic = TestMicCapture()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> = Result.success("new dictation")
        }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )
        val sent = setupContent(vm)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("old draft ")
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        // Stop via "To field".
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle &&
                vm.uiState.value.draft.contains("new dictation")
        }

        // Nothing sent; the transcript is in the editable draft.
        assertEquals(0, sent.size)
        assertEquals("old draft new dictation", vm.uiState.value.draft)
        // Back on Idle the editable input + Send return.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed()
    }

    @Test
    fun sendButtonWhisperFailureDropsSendAndSurfacesError() {
        // Issue #508: if Whisper fails after the "Send" stop, the queued send
        // is cancelled, an error banner appears, and the typed draft survives.
        val mic = TestMicCapture()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> =
                Result.failure(
                    com.pocketshell.core.voice.WhisperException.Transport("simulated drop"),
                )
        }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
        )
        val sent = setupContent(vm)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("preface ")
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle &&
                vm.uiState.value.error != null
        }

        assertEquals(0, sent.size)
        assertNotNull(vm.uiState.value.error)
        assertEquals("preface ", vm.uiState.value.draft)
    }

    @Test
    fun sendInIdleWithTypedDraftFiresImmediatelyAndClearsDraft() {
        // Regression pin: the Idle-state Send path still works. Tap the
        // single Send button with a typed draft → dispatch fires (with
        // Enter), draft clears.
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
        val sent = setupContent(vm)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("hello shell")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }

        assertEquals(1, sent.size)
        assertEquals("hello shell", sent[0].text)
        assertEquals(true, sent[0].withEnter)
        // Mic was never started — pure-text send path.
        assertEquals(0, mic.startCount)
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft == "" }
    }
}
