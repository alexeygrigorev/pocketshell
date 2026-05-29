package com.pocketshell.app.composer

import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PromptComposerViewModel]'s recording FSM. Hits all
 * three transitions explicitly so the orchestrator's acceptance
 * criteria are demonstrably met without an emulator:
 *
 *  - Idle -> Recording -> (manual stop) -> Transcribing -> Idle
 *  - Idle -> Recording -> (5s silence auto-stop) -> Transcribing -> Idle
 *  - Whisper success appends to existing draft (never replaces)
 *  - Whisper failure surfaces an error and returns to Idle
 *
 * Robolectric is here only because [AndroidKeystoreApiKeyStorage] (a
 * production constructor argument) reaches into the Android KeyStore for
 * its master key. We construct a real-but-isolated instance per test —
 * see [com.pocketshell.app.composer.PromptComposerViewModelTest.newStorage].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * In-memory [ApiKeyVault] for the unit tests — avoids the
     * `AndroidKeyStore` JCA provider lookup that fails on the host JVM
     * without a Robolectric shim. Production code routes through
     * `EncryptedApiKeyVault` which delegates to
     * `AndroidKeystoreApiKeyStorage`; the storage layer is covered end-
     * to-end by `:shared:core-voice`'s own tests, so the ViewModel
     * tests can stay focused on the FSM.
     */
    private class FakeVault(initial: CharArray? = null) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    private fun newStorage(): ApiKeyVault = FakeVault()

    /**
     * Scriptable [PromptComposerViewModel.MicCapture]. Records each
     * `start` / `stop` call and serves a programmable amplitude queue
     * for the silence watchdog. `stop()` returns a single sentinel byte
     * so the captured WAV is non-empty.
     */
    private class FakeMicCapture(
        private val amplitudes: List<Float> = listOf(0.5f, 0.5f, 0.5f),
        private val startFailure: AudioRecorderException? = null,
        private val stopFailure: AudioRecorderException? = null,
    ) : PromptComposerViewModel.MicCapture {
        var startCount = 0
        var stopCount = 0
        private var ampIndex = 0
        private var running = false

        override fun start() {
            startFailure?.let { throw it }
            startCount++
            running = true
            ampIndex = 0
        }

        override fun stop(): ByteArray {
            stopFailure?.let { throw it }
            stopCount++
            running = false
            // Sentinel WAV-shaped non-empty payload; the real recorder
            // returns the 44-byte header plus PCM data.
            return ByteArray(44) { 0 }
        }

        override fun currentAmplitude(): Float {
            if (!running) return 0f
            val a = amplitudes.getOrElse(ampIndex) { amplitudes.lastOrNull() ?: 0f }
            ampIndex++
            return a
        }
    }

    /**
     * Build a [WhisperClient] that returns a scripted [Result] for every
     * call. The interface isn't a `fun interface` so we can't SAM it; the
     * anonymous object below keeps the test sites readable.
     */
    private fun fakeWhisperClient(result: () -> Result<String>): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> = result()
    }

    /**
     * In-memory [PromptComposerViewModel.VoiceSettingsSnapshot] for unit
     * tests. The default mirrors the historic 5s window + no language
     * hint so every existing assertion still holds — issue #125-specific
     * tests override the values via [SettingsRepositoryTest]'s explicit
     * factory call site below.
     */
    private class FakeVoiceSettings(
        private var window: Long = PromptComposerViewModel.SILENCE_WINDOW_MS,
        private var language: String? = null,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = window
        override fun whisperLanguageHint(): String? = language

        fun setWindow(ms: Long) { window = ms }
        fun setLanguage(code: String?) { language = code }
    }

    private fun newVm(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("hello world") },
        storage: ApiKeyVault = newStorage().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
    ): PromptComposerViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
        )
        if (samplerDispatcher != null) vm.samplerDispatcher = samplerDispatcher
        vm.clock = clock
        return vm
    }

    // -- FSM transitions ----------------------------------------------------

    @Test
    fun initialStateIsIdleWithEmptyDraft() {
        val vm = newVm()
        val state = vm.uiState.value
        assertEquals(RecordingState.Idle, state.recording)
        assertEquals("", state.draft)
        assertNull(state.error)
    }

    @Test
    fun micTapInIdleStartsRecording() = runTest {
        val mic = FakeMicCapture()
        val vm = newVm(mic = mic, samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals(1, mic.startCount)
        // Tap again to settle the recording job — `runTest` waits for
        // every coroutine on the test scheduler to terminate, so a
        // dangling silence-watchdog loop would hang the test forever.
        vm.onMicTap()
        advanceUntilIdle()
    }

    @Test
    fun secondMicTapStopsAndTranscribes() = runTest {
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f))
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("hello world") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
        assertEquals("hello world", vm.uiState.value.draft)
    }

    @Test
    fun transcriptionAppendsToExistingDraft() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("from the agent") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("Tell me ")
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Append (with no extra separator because the draft already ends
        // in a space).
        assertEquals("Tell me from the agent", vm.uiState.value.draft)
    }

    @Test
    fun transcriptionAppendsWithSeparatorWhenDraftDoesNotEndInSpace() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("from the agent") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("Tell me")
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals("Tell me from the agent", vm.uiState.value.draft)
    }

    // -- 5s silence auto-stop -----------------------------------------------

    @Test
    fun silenceForFiveSecondsAutoStopsRecording() = runTest {
        // Amplitudes: first three loud, then nothing.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        // Bind the ViewModel's clock to the virtual scheduler so the
        // 5s silence window advances in the same coordinate system the
        // test does via `advanceTimeBy`.
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("auto-stopped") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Burn three loud-amplitude polls so the silence watchdog has
        // something to time out against.
        advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()

        // Advance past the 5s silence window — auto-stop should fire.
        advanceTimeBy(PromptComposerViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
        // Auto-stop transcribed and appended.
        assertEquals("auto-stopped", vm.uiState.value.draft)
    }

    @Test
    fun silenceTimerResetsOnLoudAmplitude() = runTest {
        // Pattern: leading loud, then a long quiet stretch broken by a
        // single loud sample, then sustained silence. The loud sample in
        // the middle should reset the silence watchdog so the auto-stop
        // only fires after the *trailing* silence window expires.
        val pattern = List(4) { 0.5f } + List(50) { 0f } + listOf(0.5f) + List(500) { 0f }
        val mic = FakeMicCapture(amplitudes = pattern)

        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("late stop") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        vm.onMicTap()
        runCurrent()

        // Drain ~55 polls (2.75s) — that consumes the leading loud chunk,
        // the 50-sample quiet run, and the resetting loud sample. We
        // stop short of the 5s window so the silence watchdog has not
        // yet fired by this point.
        advanceTimeBy(55L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Now advance past the (post-reset) silence window. The
        // watchdog should fire on the trailing quiet stretch.
        advanceTimeBy(PromptComposerViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    // -- Whisper failure surfaced as error -----------------------------------

    @Test
    fun whisperFailureSetsErrorAndReturnsToIdle() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Auth("bad key"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNotNull(vm.uiState.value.error)
        assertTrue(
            vm.uiState.value.error!!.contains("API key", ignoreCase = true),
        )
        // Draft is untouched on failure (no transcription appended).
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun whisperTransportFailureMapsToNetworkError() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Transport("no DNS"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertTrue(
            vm.uiState.value.error!!.contains("Network", ignoreCase = true),
        )
    }

    // -- AudioRecorder failure surfaced as error ----------------------------

    @Test
    fun audioRecorderStartFailureSurfacesError() = runTest {
        val mic = FakeMicCapture(
            startFailure = AudioRecorderException.PermissionDenied("no mic"),
        )
        val vm = newVm(
            mic = mic,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNotNull(vm.uiState.value.error)
    }

    // -- API key gating -----------------------------------------------------

    @Test
    fun missingApiKeyBlocksRecording() = runTest {
        val storage = newStorage() // no save() -> empty
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = null,
            storage = storage,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun hasApiKeyReflectsStorage() {
        // Default storage in `newVm` pre-saves "sk-test" — the view model
        // should see it.
        val vm = newVm()
        assertTrue(vm.hasApiKey())
    }

    @Test
    fun saveApiKeyPersists() {
        val storage = newStorage()
        val vm = newVm(storage = storage)
        vm.saveApiKey("sk-fresh".toCharArray())
        val loaded = storage.load()
        assertNotNull(loaded)
        assertEquals("sk-fresh", String(loaded!!))
    }

    // -- Draft preservation -------------------------------------------------

    @Test
    fun draftSurvivesRecordingCycle() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("appended") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("Existing draft.")
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()
        assertEquals("Existing draft. appended", vm.uiState.value.draft)
    }

    @Test
    fun typedDraftIsEditableAndPreservedUntilCallerClearsIt() {
        val vm = newVm()

        vm.onDraftChange("first dictated idea")
        vm.onDraftChange("first dictated idea with an edit")

        assertEquals("first dictated idea with an edit", vm.uiState.value.draft)
    }

    @Test
    fun clearErrorRemovesBanner() {
        val vm = newVm()
        vm.surfacePermissionDenied()
        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    // -- Issue #174: cancel-recording affordance -----------------------------

    @Test
    fun cancelRecordingStopsRecorderDiscardsBufferAndReturnsToIdle() = runTest {
        // Counts the calls to the Whisper client so the test can prove
        // that cancel does NOT trigger a transcription round-trip.
        val whisperCalls = AtomicReference(0)
        val mic = FakeMicCapture()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls.set(whisperCalls.get() + 1)
                return Result.success("should not be appended")
            }
        }
        val vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        // Seed a typed draft the user expects to keep around.
        vm.onDraftChange("partial typed prompt")
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Cancel and let any scheduled work drain. The silence watchdog
        // job is cancelled inline; advanceUntilIdle just confirms no
        // lingering coroutine resurrected the recording state.
        vm.cancelRecording()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        // Recorder was stopped exactly once — by the cancel path.
        assertEquals(1, mic.stopCount)
        // Whisper must not have been called: the audio buffer was
        // discarded, the user explicitly chose to abandon the dictation.
        assertEquals(0, whisperCalls.get())
        // Pre-existing typed draft is preserved verbatim.
        assertEquals("partial typed prompt", vm.uiState.value.draft)
        // Cancel is a user-driven discard, not an interruption — no
        // banner.
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun cancelRecordingFromIdleIsNoOp() {
        val mic = FakeMicCapture()
        val vm = newVm(mic = mic)
        vm.cancelRecording()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0, mic.startCount)
        assertEquals(0, mic.stopCount)
    }

    @Test
    fun cancelRecordingDuringTranscribingIsNoOp() = runTest {
        // The cancel chip is hidden during Transcribing, but even if a
        // stale tap reaches the ViewModel the FSM must not jump back to
        // Idle while the Whisper response is in flight — that would
        // race the success path's draft append and drop the transcript.
        val mic = FakeMicCapture()
        // Gate the Whisper response on an explicit signal so the FSM
        // stays parked on Transcribing across the cancel call. Without
        // this latch the suspend function would return immediately on
        // the test scheduler and the FSM would already be back on Idle
        // by the time we asserted.
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                release.await()
                return Result.success("hello world")
            }
        }
        val vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        // Trigger stop-and-transcribe. The Whisper coroutine is now
        // suspended on the latch.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        // Stale cancel tap. Must be a no-op: the audio was already sent.
        vm.cancelRecording()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        // Release the gate; the transcript still lands in the draft
        // because cancel never bumped the FSM out of Transcribing.
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("hello world", vm.uiState.value.draft)
    }

    @Test
    fun cancelRecordingPreservesExistingDraftIncludingTrailingSpace() = runTest {
        val whisperCalls = AtomicReference(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls.set(whisperCalls.get() + 1)
                return Result.success("nope")
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        // The recording append logic strips a trailing-space separator;
        // cancel must not do anything similar — the draft is returned
        // verbatim, including whitespace.
        vm.onDraftChange("Tell me ")
        vm.onMicTap()
        runCurrent()
        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals("Tell me ", vm.uiState.value.draft)
        assertEquals(0, whisperCalls.get())
    }

    @Test
    fun cancelRecordingSurvivesAudioRecorderStopFailure() = runTest {
        // If the mic capture throws on stop (focus already gone, etc.)
        // we still want to land on Idle. The user pressed cancel; a
        // wedged Recording state would be worse than a small error
        // banner.
        val mic = FakeMicCapture(
            stopFailure = AudioRecorderException.Other("mic gone"),
        )
        val whisperCalls = AtomicReference(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls.set(whisperCalls.get() + 1)
                return Result.success("nope")
            }
        }
        val vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onDraftChange("draft kept")
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.cancelRecording()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0, whisperCalls.get())
        assertEquals("draft kept", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)
    }

    // -- Issue #125: VoiceSettingsSnapshot integration --------------------

    @Test
    fun voiceSettingsLanguageHintFlowsIntoWhisperTranscribe() = runTest {
        // Capture the language argument the ViewModel passes to Whisper.
        // The configured language must thread through; sentinel `null`
        // means "no hint".
        val captured = AtomicReference<String?>(null)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                captured.set(language)
                return Result.success("transcribed")
            }
        }
        val voice = FakeVoiceSettings(language = "ru")
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            voiceSettings = voice,
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals("ru", captured.get())
    }

    // -- Issue #195: hasDetectedSpeech sub-state transition ---------------

    @Test
    fun recordingStartsWithHasDetectedSpeechFalseUntilAmplitudeCrossesThreshold() = runTest {
        // Pattern: a stretch of sub-threshold samples (room noise),
        // then loud samples. The flag must stay false during the quiet
        // leading window and flip when the first loud sample arrives —
        // which proves the sheet's "LISTENING -> CAPTURING" relabel
        // fires on the first speech amplitude, not on mere mic-open.
        // With `SAMPLE_INTERVAL_MS = 50ms`, the user sees the relabel
        // within one poll of starting to speak, well inside the 200ms
        // responsiveness budget called out in the acceptance criteria.
        val belowThreshold = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD - 0.01f
        val aboveThreshold = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD + 0.1f
        // Eight below-threshold samples (~400ms of room noise) then a
        // long run of loud samples. The leading run is comfortably
        // shorter than the 5s silence window so auto-stop is not a
        // factor.
        val quietSamples = 8
        val mic = FakeMicCapture(
            amplitudes = List(quietSamples) { belowThreshold } + List(500) { aboveThreshold },
        )
        val vm = newVm(
            mic = mic,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            vm.onMicTap()
            // Initial Recording state: flag is false. The sampler has
            // not yet had a chance to poll the mic — `startRecording`
            // updates the FSM synchronously before launching the
            // amplitude loop.
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)
            assertEquals(false, vm.uiState.value.hasDetectedSpeech)

            // Drain a short stretch of sub-threshold polls (50ms each).
            // The flag must still be false — room noise alone must not
            // flip the label. We stay well inside the 8-sample quiet
            // window so no above-threshold sample has been consumed.
            advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            assertEquals(false, vm.uiState.value.hasDetectedSpeech)

            // Advance past the quiet leading run so the loud samples
            // get consumed. Flag flips — this is the moment "LISTENING"
            // becomes "CAPTURING" in the sheet.
            advanceTimeBy((quietSamples + 2L) * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            assertEquals(true, vm.uiState.value.hasDetectedSpeech)
        } finally {
            // Always cancel so `runTest` terminates even when an
            // assertion above fires — the sampler loop would otherwise
            // schedule another `delay` on the test scheduler and
            // `runTest`'s end-of-block `advanceUntilIdle` would hang.
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun hasDetectedSpeechRemainsTrueAcrossMidSentencePause() = runTest {
        // Once the user has spoken, a brief pause must not yank the UI
        // back to "speak when ready" — the user has already been heard
        // and the captured buffer still holds their words. This test
        // proves the flag is sticky for the lifetime of one recording.
        val below = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD - 0.01f
        val above = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD + 0.1f
        // First sample loud (flips the flag), then a quiet stretch
        // shorter than the 5s silence window so auto-stop doesn't fire.
        val mic = FakeMicCapture(
            amplitudes = listOf(above) + List(500) { below },
        )
        val vm = newVm(
            mic = mic,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            vm.onMicTap()
            // First poll: loud — flag flips to true.
            advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            assertEquals(true, vm.uiState.value.hasDetectedSpeech)

            // Drain several quiet polls (a mid-sentence pause, still
            // well under the 5s silence auto-stop window). The flag
            // must stay true — the label must keep reading "CAPTURING".
            advanceTimeBy(10L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)
            assertEquals(true, vm.uiState.value.hasDetectedSpeech)
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun hasDetectedSpeechResetsOnNextRecording() = runTest {
        // A second dictation in the same composer session must start
        // back at "LISTENING — speak when ready". Without this reset
        // the previous run's flag would persist and the second
        // recording would open straight on "CAPTURING", contradicting
        // the visual sub-state contract.
        val above = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD + 0.1f
        val mic = FakeMicCapture(amplitudes = List(500) { above })
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("first chunk") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            // First recording: poll once so the flag flips.
            vm.onMicTap()
            advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            assertEquals(true, vm.uiState.value.hasDetectedSpeech)

            // Stop + transcribe back to Idle.
            vm.onMicTap()
            advanceUntilIdle()
            assertEquals(RecordingState.Idle, vm.uiState.value.recording)

            // Second recording: flag must start false again. The check
            // runs *before* `runCurrent` so the sampler has not yet
            // polled — `startRecording` resets the flag synchronously.
            vm.onMicTap()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)
            assertEquals(false, vm.uiState.value.hasDetectedSpeech)
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun firstSpeechAmplitudeRelabelLandsWithinResponsivenessBudget() = runTest {
        // Acceptance criterion: "Transition is responsive (≤ 200ms after
        // first speech amplitude)." Pin the bound explicitly so a future
        // change to `SAMPLE_INTERVAL_MS` cannot silently regress past the
        // user-perceived budget without this test going red.
        val above = PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD + 0.1f
        val mic = FakeMicCapture(amplitudes = List(500) { above })
        val vm = newVm(
            mic = mic,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            vm.onMicTap()
            val startTime = testScheduler.currentTime
            assertEquals(false, vm.uiState.value.hasDetectedSpeech)

            // Single sampler poll is enough for an above-threshold
            // amplitude to flip the flag.
            advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            val elapsed = testScheduler.currentTime - startTime
            assertEquals(true, vm.uiState.value.hasDetectedSpeech)
            assertTrue(
                "Sub-state transition took ${elapsed}ms — must be within the 200ms responsiveness budget",
                elapsed <= 200L,
            )
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    // -- Issue #180: failed-transcription retry queue -----------------------

    /**
     * Hand-rolled in-memory [PromptComposerViewModel.PendingTranscriptionQueue].
     * Counts every method call so the FSM tests can assert that the
     * persist-before-Whisper invariant holds and that the success /
     * failure paths route through the store as documented.
     */
    private class FakePendingQueue(
        private val initialAudioMap: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : PromptComposerViewModel.PendingTranscriptionQueue {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow<List<com.pocketshell.app.voice.PendingTranscriptionItem>>(emptyList())
        override val items: kotlinx.coroutines.flow.Flow<List<com.pocketshell.app.voice.PendingTranscriptionItem>> = flow

        var enqueueCount = 0
            private set
        var succeededIds: MutableList<String> = mutableListOf()
        var failureIds: MutableList<Pair<String, String>> = mutableListOf()
        var discardedIds: MutableList<String> = mutableListOf()
        var savedIds: MutableList<String> = mutableListOf()
        var reconcileCount = 0
            private set
        var nextId: String = "pending-1"

        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): com.pocketshell.app.voice.PendingTranscriptionItem? {
            enqueueCount++
            val id = nextId
            initialAudioMap[id] = audio
            val item = com.pocketshell.app.voice.PendingTranscriptionItem(
                id = id,
                recordingTimestampMs = 0,
                destinationContext = destinationContext,
                retryCount = 0,
                lastErrorMessage = initialError,
                audioByteSize = audio.size.toLong(),
            )
            flow.value = listOf(item) + flow.value
            return item
        }

        override suspend fun snapshot(): List<com.pocketshell.app.voice.PendingTranscriptionItem> =
            flow.value

        override suspend fun loadAudio(id: String): ByteArray? = initialAudioMap[id]

        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun markFailure(
            id: String,
            errorMessage: String,
        ): com.pocketshell.app.voice.PendingTranscriptionItem? {
            failureIds += id to errorMessage
            val existing = flow.value.firstOrNull { it.id == id } ?: return null
            val updated = existing.copy(
                retryCount = existing.retryCount + 1,
                lastErrorMessage = errorMessage,
            )
            flow.value = flow.value.map { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun discard(id: String) {
            discardedIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun saveAsAudioFile(id: String): String? {
            savedIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
            return "/data/files/voice-exports/$id.wav"
        }

        override suspend fun reconcile() {
            reconcileCount++
        }
    }

    /**
     * Scriptable [PromptComposerViewModel.ConnectivityProbe]. The default
     * mirrors the legacy "always online" behaviour; tests flip the value
     * to exercise the offline-queue path.
     */
    private class FakeConnectivity(initial: Boolean = true) : PromptComposerViewModel.ConnectivityProbe {
        var online: Boolean = initial
        override fun refresh(): Boolean = online
    }

    private fun newVmWithQueue(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("hello world") },
        storage: ApiKeyVault = newStorage().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
        queue: FakePendingQueue = FakePendingQueue(),
        connectivity: FakeConnectivity = FakeConnectivity(initial = true),
    ): Pair<PromptComposerViewModel, FakePendingQueue> {
        val factory = WhisperClientFactory { whisper }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            pendingTranscriptionStore = queue,
            connectivity = connectivity,
        )
        if (samplerDispatcher != null) vm.samplerDispatcher = samplerDispatcher
        vm.clock = clock
        return vm to queue
    }

    @Test
    fun audioIsPersistedBeforeWhisperCall() = runTest {
        val (vm, queue) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("hello") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Persisted exactly once before the Whisper round-trip.
        assertEquals(1, queue.enqueueCount)
        // Whisper success deleted the row.
        assertEquals(listOf("pending-1"), queue.succeededIds)
        // Draft has the transcript.
        assertEquals("hello", vm.uiState.value.draft)
    }

    @Test
    fun whisperFailureKeepsAudioOnDiskAndBumpsRetryCount() = runTest {
        val (vm, queue) = newVmWithQueue(
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Transport("offline"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(0, queue.succeededIds.size)
        assertEquals(1, queue.failureIds.size)
        assertEquals("pending-1", queue.failureIds[0].first)
        // Error banner mirrors the failure reason.
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun offlineSkipsWhisperAndQueuesDirectly() = runTest {
        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, queue) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            connectivity = FakeConnectivity(initial = false),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(0, whisperCalls)
        // Banner reads "waiting for network".
        assertEquals(
            com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun retryPendingSuccessAppendsToDraftAndClearsQueue() = runTest {
        // Seed a queued item without going through the recording path.
        val queue = FakePendingQueue()
        queue.nextId = "retryable"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        // Bump it once to simulate a prior failure (so the retry path is
        // hit, not the initial-attempt path).
        queue.markFailure("retryable", "Whisper auth failed")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("retry transcript") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable")
        advanceUntilIdle()

        assertEquals("retry transcript", vm.uiState.value.draft)
        assertTrue(queue.succeededIds.contains("retryable"))
    }

    @Test
    fun retryPendingFailureBumpsRetryCountAndSurfacesError() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "retryable"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.failure(WhisperException.Server("oops", 500)) },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable")
        advanceUntilIdle()

        assertEquals(1, queue.failureIds.size)
        assertEquals("retryable", queue.failureIds[0].first)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun retryPendingAtCapShortCircuits() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "capped"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        // Bump to the cap.
        queue.markFailure("capped", "1")
        queue.markFailure("capped", "2")
        queue.markFailure("capped", "3")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("never") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        val priorFailures = queue.failureIds.size
        val priorSuccesses = queue.succeededIds.size
        vm.retryPending("capped")
        advanceUntilIdle()
        // Should not have called Whisper, so neither counter moved.
        assertEquals(priorFailures, queue.failureIds.size)
        assertEquals(priorSuccesses, queue.succeededIds.size)
    }

    @Test
    fun retryPendingWhileOfflineSkipsWhisperAndNudgesUser() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "offline-retry"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = false),
        )
        vm.retryPending("offline-retry")
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(
            com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun discardPendingClearsQueueEntry() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "to-discard"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        val (vm, _) = newVmWithQueue(queue = queue)
        vm.discardPending("to-discard")
        advanceUntilIdle()
        assertTrue(queue.discardedIds.contains("to-discard"))
    }

    @Test
    fun savePendingAsAudioFileExportsAndSurfacesPath() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "to-save"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        val (vm, _) = newVmWithQueue(queue = queue)
        vm.savePendingAsAudioFile("to-save")
        advanceUntilIdle()
        assertTrue(queue.savedIds.contains("to-save"))
        assertEquals(
            "/data/files/voice-exports/to-save.wav",
            vm.uiState.value.savedAudioPath,
        )
        vm.clearSavedAudioConfirmation()
        assertNull(vm.uiState.value.savedAudioPath)
    }

    @Test
    fun foregroundResumeAutoRetriesOnlyWaitingForNetworkItems() = runTest {
        val queue = FakePendingQueue()
        // Two queued rows: one waiting for network (auto-retry candidate),
        // one already failed (manual-only).
        queue.nextId = "offline-one"
        queue.enqueueAudio(
            ByteArray(8),
            "composer",
            initialError = com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
        queue.nextId = "failed-one"
        queue.enqueueAudio(ByteArray(8), "composer")
        queue.markFailure("failed-one", "Whisper auth failed")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("auto-retry success") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.onForegroundResume()
        advanceUntilIdle()

        // Only the offline row was retried successfully.
        assertTrue(queue.succeededIds.contains("offline-one"))
        assertFalse(queue.succeededIds.contains("failed-one"))
    }

    @Test
    fun foregroundResumeNoOpWhenOffline() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "offline-row"
        queue.enqueueAudio(
            ByteArray(8),
            "composer",
            initialError = com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = false),
        )
        vm.onForegroundResume()
        advanceUntilIdle()
        assertEquals("must not burn quota while offline", 0, whisperCalls)
    }

    // -- Issue #185: silence-threshold surfacing + stricter floor --------

    @Test
    fun startRecordingStampsSilenceThresholdSecondsFromVoiceSettings() = runTest {
        // The composer sheet renders the "Auto-stops after Xs silence"
        // hint from [UiState.silenceThresholdSeconds]. Stamping the
        // value at recording start (rather than reading the settings
        // every frame) keeps the displayed hint stable for the lifetime
        // of one recording — a slider drag during dictation must not
        // shorten the in-flight window underfoot, the watchdog and the
        // displayed value must agree.
        val customWindowMs = 4_000L
        val vm = newVm(
            mic = FakeMicCapture(),
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            voiceSettings = FakeVoiceSettings(window = customWindowMs),
        )
        assertEquals(
            "Idle state must not surface a threshold — the hint hides outside Recording",
            0f,
            vm.uiState.value.silenceThresholdSeconds,
            0f,
        )

        try {
            vm.onMicTap()
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)
            assertEquals(
                "stamped threshold must equal voiceSettings.silenceWindowMs / 1000",
                4f,
                vm.uiState.value.silenceThresholdSeconds,
                0.001f,
            )
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun watchdogHonoursThresholdStampedOnUiState() = runTest {
        // Issue #185: the watchdog reads its window from the UiState
        // snapshot, not from the live VoiceSettingsSnapshot, so a slider
        // drag during recording cannot shorten the in-flight window.
        // The fake reports a generous 8s window at recording start, then
        // flips to a hostile 1s window. The watchdog must still wait the
        // 8s it committed to.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        val voice = FakeVoiceSettings(window = 8_000L)
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("stable") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            voiceSettings = voice,
        )

        vm.onMicTap()
        runCurrent()
        // Mid-recording slider tug — must NOT affect the in-flight window.
        voice.setWindow(1_000L)

        // Burn loud polls to seed the lastLoud timer.
        advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()

        // At 2s well below the original 8s window AND above the new 1s
        // window the user tried to apply — proves the watchdog ignored
        // the live edit and is still counting against the stamped 8s.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(
            "watchdog must use the threshold stamped at recording start, " +
                "not the live VoiceSettingsSnapshot value",
            RecordingState.Recording,
            vm.uiState.value.recording,
        )

        // Advance past the originally-stamped 8s window — auto-stop
        // fires now because the watchdog committed to 8s, not 1s.
        advanceTimeBy(8_000L)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun silenceWindowAtMinimumFloorTwoSecondsKeepsRecordingThroughOneAndAHalfSecondSilence() = runTest {
        // Issue #185 raised the minimum bound to 2s. A 1.5s mid-sentence
        // pause is the canonical regression scenario: with a 2s floor
        // it stays inside the window and the recording continues.
        val mic = FakeMicCapture(
            amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(35) { 0f } + List(200) { 0.5f },
        )
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("never") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            voiceSettings = FakeVoiceSettings(window = 2_000L),
        )

        try {
            vm.onMicTap()
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)

            // Burn 3 loud polls so the lastLoud clock is seeded inside
            // the window.
            advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()

            // 1.5s of silence — still inside the 2s minimum-floor window.
            // This is the regression scenario the maintainer reported in
            // the v0.2.8 feedback note: a natural mid-sentence pause that
            // should not auto-stop.
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(
                "recording must survive a 1.5s pause when the threshold floor is 2s",
                RecordingState.Recording,
                vm.uiState.value.recording,
            )
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun voiceSettingsCustomSilenceWindowAutoStopsAtConfiguredThreshold() = runTest {
        // Configure a 1.5s window — well below the historic 5s default —
        // and verify the recording auto-stops just past the new bound.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        val customWindowMs = 1_500L
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("short") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            voiceSettings = FakeVoiceSettings(window = customWindowMs),
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        // At 0.15s we are still recording — well inside the 1.5s window.
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Advance past the configured 1.5s window — the watchdog should
        // fire even though the historic 5s constant has not elapsed.
        advanceTimeBy(customWindowMs + 500L)
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
    }

    // -- Issue #211: Send-while-Recording / Transcribing -------------------
    // (also pins the #210 regression: cancel-then-redictate-then-send sends
    // the NEW transcript, not the stale draft.)

    /**
     * Helper: collect every [PromptComposerViewModel.SendRequest] the
     * ViewModel emits into a thread-safe list so the test can assert
     * what the sheet would have routed into the host `onSend` callback.
     * The `replay = 0` SharedFlow means we must subscribe before the
     * production code emits; the test starts a collector under the
     * `runTest` scope and `runCurrent()`s once so the subscription is
     * registered before the action under test.
     */
    private fun kotlinx.coroutines.test.TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val collected = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch {
            vm.sendRequests.collect { collected += it }
        }
        runCurrent()
        return collected
    }

    @Test
    fun requestSendInIdleDispatchesDraftImmediatelyAndClearsDraft() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        vm.onDraftChange("hello shell")

        vm.requestSend(withEnter = false)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("hello shell", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        // After dispatch the draft is cleared so the next composer open
        // starts blank.
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun requestSendInIdleWithEmptyDraftEmitsNothing() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)

        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        assertEquals(0, sent.size)
    }

    @Test
    fun sendDispatchedDuringSubscriberGapIsDeliveredToNextCollector() = runTest {
        // Issue #254 root cause + regression pin. The composer sheet's
        // `sendRequests` collector lives in the sheet's composition: it is
        // torn down when the sheet is dismissed and re-created when the
        // sheet is re-opened. A Send dispatched into that subscriber gap —
        // which is exactly what happens on a *subsequent* "Send after
        // dictation" (the first send dismisses the sheet, the next send's
        // `dispatchSendNow` fires from the `viewModelScope` transcribe
        // coroutine while the new collector has not yet re-subscribed) —
        // MUST still reach the next collector.
        //
        // The pre-fix `MutableSharedFlow(replay = 0)` dropped this
        // emission silently (no active subscriber at emit time, no replay
        // for the late subscriber), which is the user-visible "works once,
        // then not" bug. A `Channel.receiveAsFlow()` buffers the item
        // until the next collector consumes it.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("first message")

        // No collector subscribed (the sheet is dismissed). Dispatch a send.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // The sheet re-opens: a brand-new collector subscribes only now.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(
            "a send dispatched during the dismiss→re-open subscriber gap must " +
                "reach the next collector (#254)",
            1,
            sent.size,
        )
        assertEquals("first message", sent[0].text)
        assertEquals(true, sent[0].withEnter)
    }

    @Test
    fun requestSendWhileRecordingStopsRecorderTranscribesThenSends() = runTest {
        // Acceptance #211: Send tap during Recording → recorder stops,
        // transcription runs, transcript is appended to draft, Send fires
        // with the combined draft.
        val mic = FakeMicCapture()
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("from dictation") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onDraftChange("Tell me ")
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // The user taps Send while still recording — the FSM owes them a
        // single-tap send.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // Send fired exactly once, with the COMBINED text (existing
        // draft + transcribed text), and with the user's withEnter flag.
        assertEquals(1, sent.size)
        assertEquals("Tell me from dictation", sent[0].text)
        assertEquals(true, sent[0].withEnter)
        // Mic was stopped by the queued-send path, not by a separate
        // mic-tap.
        assertEquals(1, mic.stopCount)
        // FSM lands back at Idle with the draft cleared (the SendRequest
        // is the official transfer of the text out of the composer).
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun requestSendWhileTranscribingQueuesAndFiresOnSuccess() = runTest {
        // Acceptance #211: Send tap during Transcribing → send is
        // queued, fires on Whisper success.
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                release.await()
                return Result.success("queued result")
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onMicTap()
        runCurrent()
        // Trigger stop → audio is now in Whisper flight; FSM is in
        // Transcribing until we release the latch.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        // User taps Send mid-Whisper. The send must NOT fire yet — no
        // transcript is available.
        vm.requestSend(withEnter = false)
        runCurrent()
        assertEquals(0, sent.size)
        // FSM still parked on Transcribing — the Send tap during
        // Transcribing does not auto-stop anything new.
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        // Whisper returns — the queued send fires with the transcript.
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("queued result", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun requestSendDuringRecordingWhisperFailureDropsSendAndSurfacesError() = runTest {
        // Acceptance #211: If Whisper fails, the queued Send is
        // cancelled and the transcript goes into the retry queue with
        // an error message.
        val mic = FakeMicCapture()
        val (vm, queue) = newVmWithQueue(
            mic = mic,
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Transport("simulated drop"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onDraftChange("preface ")
        vm.onMicTap()
        runCurrent()
        vm.requestSend(withEnter = false)
        advanceUntilIdle()

        // No send was emitted.
        assertEquals(0, sent.size)
        // Error banner is set.
        assertNotNull(vm.uiState.value.error)
        // Retry queue captured the failure — the user can retry the
        // audio later via the banner (#180).
        assertEquals(1, queue.failureIds.size)
        // FSM is back to Idle, draft preserved verbatim so the user can
        // resend manually.
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("preface ", vm.uiState.value.draft)
    }

    @Test
    fun requestSendWhileRecordingOfflineDropsQueuedSendAndKeepsDraft() = runTest {
        // Acceptance #211 corollary: offline path enqueues the audio
        // for later retry but does not fire the queued Send (no
        // transcript exists).
        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, queue) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            connectivity = FakeConnectivity(initial = false),
        )
        val sent = collectSendRequests(vm)

        vm.onDraftChange("typed prefix ")
        vm.onMicTap()
        runCurrent()
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(0, sent.size)
        // Audio is on disk waiting for network.
        assertEquals(1, queue.enqueueCount)
        // Typed draft is preserved.
        assertEquals("typed prefix ", vm.uiState.value.draft)
    }

    @Test
    fun cancelRecordingClearsQueuedSendFlagsAndEmitsNoSend() = runTest {
        // Acceptance #211: Cancel mid-flight — if user taps Cancel
        // while a queued Send is pending (or otherwise), no Send fires.
        // The maintainer-facing guarantee is: cancel always means "do
        // not send the in-flight buffer". The internal pending-send
        // flag is implementation detail; we observe the public-surface
        // contract: zero SendRequest emissions.
        val mic = FakeMicCapture()
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("must not send") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onDraftChange("typed draft")
        // First cycle: cancel without ever calling requestSend, to
        // prove the cancel path itself is send-free.
        vm.onMicTap()
        runCurrent()
        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(0, sent.size)
        assertEquals("typed draft", vm.uiState.value.draft)

        // Second cycle: same shape, fresh recording. The test pin is
        // belt-and-braces — a future regression that lets the cancel
        // path leak a SendRequest from a stale flag would be caught
        // here even if the test above passes by coincidence.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(0, sent.size)
        assertEquals("typed draft", vm.uiState.value.draft)
    }

    @Test
    fun issue210_cancelThenRedictateThenSendSendsTheNewTranscriptNotTheStaleDraft() = runTest {
        // Pins the maintainer-reported #210 bug:
        //
        //   "I was dictating, and then I clicked cross, so I stopped it,
        //    then I was dictating another message, I clicked send, and
        //    then the old message was sent instead of the new one."
        //
        // Sequence under test:
        //   1. User pre-types "old draft text" (or it lands there from a
        //      previous transcription — the test seeds it directly).
        //   2. User taps mic → Recording 1.
        //   3. User taps X cancel → FSM=Idle, draft preserved.
        //   4. User taps mic again → Recording 2.
        //   5. User taps Send mid-recording.
        //
        // Expected: the SendRequest carries the NEW recording's
        // transcript appended to the preserved draft, NOT the stale
        // draft on its own.
        val mic = FakeMicCapture()
        val whisper = fakeWhisperClient { Result.success("new dictation") }
        val vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        // 1. Pre-existing draft (could be typed text or a stale
        //    previous transcription).
        vm.onDraftChange("old draft text ")
        // 2. Recording 1.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        // 3. Cancel.
        vm.cancelRecording()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("old draft text ", vm.uiState.value.draft)
        // 4. Recording 2.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        // 5. Send while still recording.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // The Send carries "old draft text new dictation" — the new
        // transcription, NOT just "old draft text" (which was the bug).
        assertEquals(1, sent.size)
        assertEquals("old draft text new dictation", sent[0].text)
        assertEquals(true, sent[0].withEnter)
    }
}
