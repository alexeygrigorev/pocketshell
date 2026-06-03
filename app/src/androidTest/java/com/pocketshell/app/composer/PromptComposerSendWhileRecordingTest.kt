package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Issue #211 (and #210 regression pin): emulator coverage of the
 * "Send while dictating" shortcut and the
 * "cancel-then-redictate-then-send sends the NEW transcript" guarantee.
 *
 * Renders [SheetContent] backed by a real [PromptComposerViewModel] with
 * in-memory fakes for the microphone, Whisper client, and pending-
 * transcription queue. The test exercises the public surface a real
 * user touches: tapping the mic, tapping cancel, tapping Insert/Send. The
 * `sendRequests` SharedFlow is collected so the test can assert what
 * the sheet would have routed into the host's `onSend` callback.
 *
 * The connected test path is intentionally Docker-free: the composer's
 * Send action is a one-shot dispatch into a `SharedFlow` — it does not
 * itself round-trip the bytes to a remote shell. The shell-side
 * round-trip is already exercised by
 * [PromptComposerSmokeTest.typedDraftSendEnterReachesDockerShell].
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSendWhileRecordingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Per-test collector scope so the SharedFlow subscriber tears down
    // cleanly after each test — important on the AVD where leaked
    // coroutines accumulate quickly and confuse downstream tests.
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
            // capture through to Whisper — this test exercises the
            // send-after-transcribe path, not silence detection.
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

    /**
     * Helper: wire `SheetContent` into a viewmodel + a synchronized
     * list of sent requests. The `onSend` callback dispatches through
     * `viewModel.requestSend` so the FSM (#211) decides whether to
     * fire immediately or queue. The flow collector mirrors what the
     * public `PromptComposerSheet` LaunchedEffect does in production.
     */
    private fun setupContent(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val sent = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { withEnter -> vm.requestSend(withEnter) },
                    onCancelRecording = vm::cancelRecording,
                )
            }
        }
        // Start collecting AFTER setContent so the SharedFlow has a
        // live subscriber by the time the first user gesture lands.
        // `MutableSharedFlow(replay = 0, extraBufferCapacity = 1)`
        // means the producer side won't drop the first emission if the
        // collector is briefly behind; we still pin the order via the
        // synchronized list below. The collector lives in
        // [collectorScope] which the @After hook cancels after every
        // test so coroutines don't leak across the AVD's per-class run.
        collectorScope.launch {
            vm.sendRequests.collect { sent += it }
        }
        return sent
    }

    @Test
    fun sendWhileRecordingAutoStopsTranscribesThenFiresSendWithTranscript() {
        // Acceptance #211: Send tap during Recording → recorder stops,
        // transcription runs, transcript is appended to draft, Send fires
        // with the combined draft.
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
        // 2. Start recording.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }

        // The Insert button is enabled while recording — even though the
        // draft is "Tell me " (would-be-empty case for the original
        // pre-#211 gate, still non-empty here for safety). The queued
        // labels spell out whether the post-Whisper action inserts into
        // the prompt or submits with Enter.
        compose.onNodeWithText("Insert after transcribe").assertIsDisplayed()
        compose.onNodeWithText("Send after transcribe").assertIsDisplayed()

        // 3. Tap Insert while still recording.
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }

        // The Send carries the combined draft (existing text +
        // transcript).
        assertEquals(1, sent.size)
        assertEquals("Tell me from dictation", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        // Mic was stopped exactly once.
        assertEquals(1, mic.stopCount)
        // Draft is cleared after the dispatch.
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft == "" }
    }

    @Test
    fun sendWhileTranscribingQueuesAndFiresOnWhisperSuccess() {
        // Acceptance #211: Send tap during Transcribing → send is
        // queued, fires on Whisper success.
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

        // Drive recording → Transcribing the regular way (mic tap then
        // mic tap), so the FSM is parked in Transcribing waiting on
        // the latch.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Transcribing
        }

        compose.onNodeWithText("Send after transcribe").assertIsDisplayed()
        // User taps Send mid-Whisper. The send must NOT fire yet.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()
        compose.waitForIdle()
        // Nothing dispatched yet — the latch is still held.
        assertEquals(0, sent.size)
        // FSM still in Transcribing.
        assertEquals(
            PromptComposerViewModel.RecordingState.Transcribing,
            vm.uiState.value.recording,
        )

        // Release Whisper.
        release.complete(Unit)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }

        assertEquals(1, sent.size)
        assertEquals("queued result", sent[0].text)
        // Send → withEnter = true.
        assertEquals(true, sent[0].withEnter)
    }

    @Test
    fun cancelThenRedictateThenSendSendsTheNewTranscriptNotTheStaleDraft() {
        // Pin for issue #210: the maintainer's reported
        // "cancel-then-redictate sends the OLD message" bug.
        //
        // Sequence under test:
        //   1. User pre-types "old draft".
        //   2. User taps mic → Recording 1.
        //   3. User taps X cancel → FSM=Idle, draft preserved.
        //   4. User taps mic again → Recording 2.
        //   5. User taps Send while still recording.
        //
        // Expected: the SendRequest carries the NEW recording's
        // transcript appended to the preserved draft.
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

        // 1. Pre-existing draft.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("old draft ")
        // 2. Recording 1.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        // 3. Cancel — draft preserved, FSM Idle.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle
        }
        assertEquals("old draft ", vm.uiState.value.draft)
        // 4. Recording 2.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        // 5. Send while still recording. The Send button is fine
        //    here — the test pins the text content, not the enter flag.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }

        // The fix: the new transcription lands and the SendRequest
        // carries "old draft new dictation", NOT just "old draft ".
        assertEquals(1, sent.size)
        assertEquals("old draft new dictation", sent[0].text)
        assertEquals(true, sent[0].withEnter)
    }

    @Test
    fun sendWhileRecordingWhisperFailureDropsSendAndSurfacesError() {
        // Acceptance #211: If Whisper fails, the queued Send is
        // cancelled. The audio is in the retry queue (#180); the
        // error banner explains why.
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
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.waitForIdle()
        // Wait for the FSM to settle back at Idle with an error banner.
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle &&
                vm.uiState.value.error != null
        }

        // No send emitted.
        assertEquals(0, sent.size)
        // Banner explains the failure.
        assertNotNull(vm.uiState.value.error)
        // Draft preserved so the user can resend manually after retry.
        assertEquals("preface ", vm.uiState.value.draft)
    }

    @Test
    fun sendInIdleWithTypedDraftFiresImmediatelyAndClearsDraft() {
        // Regression pin: the historic Idle-state Send path still
        // works after #211. Tap Send with a typed draft → dispatch
        // fires, draft clears.
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
        compose.onNodeWithText("Insert").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsDisplayed()
        // Insert (no enter).
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }

        assertEquals(1, sent.size)
        assertEquals("hello shell", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        // Mic was never started — pure-text send path.
        assertEquals(0, mic.startCount)
        // Draft cleared.
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft == "" }
    }
}
