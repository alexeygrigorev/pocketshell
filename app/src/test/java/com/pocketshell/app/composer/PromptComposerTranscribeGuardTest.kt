package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #939 / audit #935 S5-2 (reproduce-first, D33/G10).
 *
 * The OpenAI-Whisper transcribe launch flips the FSM to
 * [RecordingState.Transcribing] then ran an UNGUARDED body (no `try/finally`, no
 * watchdog). Two stuck-spinner classes:
 *
 *  1. A throw OUTSIDE the inner `result.fold` — `pendingTranscriptionStore.enqueueAudio`
 *     (a Room/filesystem write), `connectivity.refresh()`, or a WhisperClient impl
 *     that throws instead of returning `Result.failure` — left the FSM stuck on
 *     `Transcribing` with the mic locked, recoverable only by killing the app.
 *  2. A Whisper round-trip that WEDGES (never returns) had NO time bound at all —
 *     unlike the send path's #891 watchdog — so it locked the mic indefinitely.
 *
 * RED on base: the FSM stays `Transcribing` forever after the throw / past any
 * bound.
 * GREEN with the fix: the FSM returns to Idle, the mic is unlocked
 * (`recordingLocked = false`), and a retryable error banner surfaces; the
 * watchdog bounds the wedged case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerTranscribeGuardTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    // -- S5-2 case 1: a throw in the unguarded transcribe body -----------------

    @Test
    fun throwInEnqueueAudioClearsTranscribingAndUnlocksMicWithRetryableError() = runTest {
        val mic = FakeMic()
        // `enqueueAudio` throws — the exact unguarded Room-write fault class S5-2
        // calls out. Before the fix this escaped the launch with the FSM already
        // on Transcribing, so the mic stayed locked forever.
        val store = ThrowingTranscriptionQueue(
            throwOnEnqueue = IllegalStateException("simulated SQLite write fault"),
        )
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisper { Result.success("hello") },
            pendingStore = store,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap() // Idle -> Recording
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap() // Recording -> Transcribing -> (throwing body)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the unguarded throw must drive the FSM back to Idle — before the fix it " +
                "stayed Transcribing with the mic locked forever",
            RecordingState.Idle,
            state.recording,
        )
        assertFalse(
            "the mic must be unlocked after the throw so the user can record again",
            state.recordingLocked,
        )
        assertNotNull(
            "a retryable error banner must surface so the user sees it failed",
            state.error,
        )

        // The composer is usable again: a fresh record cycle reaches Recording.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        vm.onMicTap()
        advanceUntilIdle()
    }

    // -- S5-2 case 2: a wedged Whisper round-trip with no watchdog --------------

    @Test
    fun wedgedTranscribeIsBoundedByWatchdogInsteadOfLockingMicForever() = runTest {
        val mic = FakeMic()
        val wedge = CompletableDeferred<Result<String>>()
        val vm = newVm(
            mic = mic,
            // The Whisper call parks on an unresolved deferred — it never returns
            // and never throws, so ONLY a watchdog can free the FSM.
            whisper = object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    wedge.await()
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        // Drive the transcribe watchdog deterministically under virtual time.
        vm.setTranscribeWatchdogTimeoutForTest(5_000L)

        vm.onMicTap() // Idle -> Recording
        runCurrent()
        vm.onMicTap() // Recording -> Transcribing (then wedges on Whisper)
        runCurrent()
        assertEquals(
            "while the wedged Whisper call is in flight the FSM is Transcribing",
            RecordingState.Transcribing,
            vm.uiState.value.recording,
        )

        // Advance past the watchdog bound: it fires and frees the FSM instead of
        // an indefinite mic lock (before the fix there was NO bound at all).
        advanceTimeBy(6_000L)
        runCurrent()

        val state = vm.uiState.value
        assertEquals(
            "a wedged transcribe must be bounded by the watchdog back to Idle, not " +
                "lock the mic forever",
            RecordingState.Idle,
            state.recording,
        )
        assertFalse("the mic is unlocked after the watchdog fires", state.recordingLocked)
        assertEquals(
            PromptComposerViewModel.TRANSCRIBE_TIMEOUT_MESSAGE,
            state.error,
        )

        // Let the wedged call settle so runTest doesn't hang on a live coroutine.
        wedge.complete(Result.success("late"))
        advanceUntilIdle()
    }

    // -- fixtures --------------------------------------------------------------

    private fun fakeWhisper(result: () -> Result<String>): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> = result()
    }

    private class FakeVault(initial: CharArray? = "sk-test".toCharArray()) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    /**
     * Minimal mic: returns real-signal audio so the recording passes the silence
     * guard and reaches the MAIN transcribe launch (the S5-2 site).
     */
    private class FakeMic : PromptComposerViewModel.MicCapture {
        private val audio: ByteArray = SpeechAudioGuard.speechWavForTesting()
        override fun start() {}
        override fun stop(): ByteArray = audio
        override fun currentAmplitude(): Float = 0.5f
    }

    private class FakeVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider() =
            com.pocketshell.app.settings.VoiceTranscriptionProvider.OpenAiWhisper
    }

    /**
     * [PromptComposerViewModel.PendingTranscriptionQueue] whose [enqueueAudio]
     * throws on demand — the unguarded Room-write fault that strands Transcribing.
     */
    private class ThrowingTranscriptionQueue(
        private val throwOnEnqueue: Throwable? = null,
    ) : PromptComposerViewModel.PendingTranscriptionQueue {
        override val items = flowOf(emptyList<PendingTranscriptionItem>())
        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): PendingTranscriptionItem? {
            throwOnEnqueue?.let { throw it }
            return null
        }
        override suspend fun snapshot(): List<PendingTranscriptionItem> = emptyList()
        override suspend fun loadAudio(id: String): ByteArray? = null
        override suspend fun markSucceeded(id: String) {}
        override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? = null
        override suspend fun discard(id: String) {}
        override suspend fun saveAsAudioFile(id: String): String? = null
        override suspend fun reconcile() {}
    }

    private fun TestScope.newVm(
        mic: PromptComposerViewModel.MicCapture,
        whisper: WhisperClient,
        pendingStore: PromptComposerViewModel.PendingTranscriptionQueue =
            DisabledPendingTranscriptionQueue,
        samplerDispatcher: TestDispatcher,
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = FakeVault(),
            voiceSettings = FakeVoiceSettings(),
            pendingTranscriptionStore = pendingStore,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = samplerDispatcher
        // Default the watchdogs off; individual tests opt in. The send watchdog is
        // irrelevant here, the transcribe watchdog test re-enables its own.
        vm.setSendWatchdogTimeoutForTest(null)
        vm.setTranscribeWatchdogTimeoutForTest(null)
        createdViewModels += vm
        return vm
    }
}
