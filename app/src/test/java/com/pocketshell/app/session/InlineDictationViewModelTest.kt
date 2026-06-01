package com.pocketshell.app.session

import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.session.InlineDictationViewModel.DictationMode
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.InlineDictationViewModel.RecordingState
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [InlineDictationViewModel] — the FSM that drives the
 * key-bar mic slot (issue #16).
 *
 * The shape closely mirrors `PromptComposerViewModelTest` because the two
 * FSMs share an amplitude / silence watchdog and an API-key gate. The
 * differences that matter for these tests:
 *
 *  - There is no editable draft; successful transcriptions are emitted
 *    on a SharedFlow that the screen pipes into the terminal.
 *  - Empty / whitespace-only transcriptions are dropped before emission
 *    (they would be a footgun on the shell prompt — see
 *    [InlineDictationViewModel.stopAndTranscribe]).
 *
 * Robolectric is here because [InlineDictationViewModel] is a
 * `@HiltViewModel` whose default scheduler ends up reaching for
 * `Dispatchers.Main.immediate`, the same path the composer tests already
 * traverse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InlineDictationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * In-memory [PromptComposerViewModel.ApiKeyVault] — mirrors the
     * composer test's `FakeVault`. We deliberately reuse the composer's
     * interface (not a parallel one) because the production wiring shares
     * the singleton binding from `VoiceModule`.
     */
    private class FakeVault(initial: CharArray? = null) : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    /**
     * Scriptable [PromptComposerViewModel.MicCapture]. Same shape as the
     * composer test's fake — single sentinel WAV-ish payload on stop, a
     * programmable amplitude queue, and `start` / `stop` counters so we
     * can assert the recorder was driven exactly once per cycle.
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
            // 44 bytes — same sentinel as the composer's WAV-shaped fake;
            // any non-empty array would do but matching keeps the two
            // test suites visually consistent.
            return ByteArray(44) { 0 }
        }

        override fun currentAmplitude(): Float {
            if (!running) return 0f
            val a = amplitudes.getOrElse(ampIndex) { amplitudes.lastOrNull() ?: 0f }
            ampIndex++
            return a
        }
    }

    private fun fakeWhisperClient(result: () -> Result<String>): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> = result()
    }

    /**
     * In-memory [PromptComposerViewModel.VoiceSettingsSnapshot] for unit
     * tests. Defaults preserve the production long-dictation silence
     * window and the "no language hint" behaviour so the FSM assertions
     * keep passing without per-test wiring.
     */
    private class FakeVoiceSettings(
        private var window: Long = InlineDictationViewModel.SILENCE_WINDOW_MS,
        private var language: String? = null,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = window
        override fun whisperLanguageHint(): String? = language

        fun setWindow(ms: Long) { window = ms }
        fun setLanguage(code: String?) { language = code }
    }

    private fun newVm(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("git status") },
        storage: PromptComposerViewModel.ApiKeyVault = FakeVault().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
    ): InlineDictationViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = InlineDictationViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
        )
        if (samplerDispatcher != null) vm.samplerDispatcher = samplerDispatcher
        vm.clock = clock
        return vm
    }

    // -- Initial state ------------------------------------------------------

    @Test
    fun initialStateIsIdleWithNoError() {
        val vm = newVm()
        val state = vm.uiState.value
        assertEquals(DictationMode.Prompt, state.mode)
        assertEquals(RecordingState.Idle, state.recording)
        assertEquals(0f, state.amplitude, 0.0001f)
        assertNull(state.error)
    }

    @Test
    fun selectModeUpdatesUiState() {
        val vm = newVm()
        vm.selectMode(DictationMode.Command)
        assertEquals(DictationMode.Command, vm.uiState.value.mode)

        vm.selectMode(DictationMode.Prompt)
        assertEquals(DictationMode.Prompt, vm.uiState.value.mode)
    }

    // -- FSM transitions ----------------------------------------------------

    @Test
    fun micTapInIdleStartsRecording() = runTest {
        val mic = FakeMicCapture()
        val vm = newVm(mic = mic, samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals(1, mic.startCount)
        // Tap again to wind down — runTest will otherwise hang on the
        // dangling silence-watchdog coroutine.
        vm.onMicTap()
        advanceUntilIdle()
    }

    @Test
    fun recordingPollsAmplitudeIntoUiStateAndClearsOnStop() = runTest {
        val mic = FakeMicCapture(amplitudes = listOf(0.2f, 0.75f, 0.35f))
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals(0.2f, vm.uiState.value.amplitude, 0.0001f)

        advanceTimeBy(InlineDictationViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(0.75f, vm.uiState.value.amplitude, 0.0001f)

        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0f, vm.uiState.value.amplitude, 0.0001f)
    }

    @Test
    fun samplerDoesNotPublishAmplitudeAfterStopTransition() = runTest {
        val transcript = CompletableDeferred<Result<String>>()
        lateinit var vm: InlineDictationViewModel
        val mic = object : PromptComposerViewModel.MicCapture {
            var startCount = 0
            var stopCount = 0
            var stopFromSampler = false

            override fun start() {
                startCount++
            }

            override fun stop(): ByteArray {
                stopCount++
                return ByteArray(44) { 0 }
            }

            override fun currentAmplitude(): Float {
                if (!stopFromSampler) {
                    stopFromSampler = true
                    vm.onMicTap()
                }
                return 0.92f
            }
        }
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                return transcript.await()
            }
        }
        vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)
        assertEquals(0f, vm.uiState.value.amplitude, 0.0001f)
        assertEquals(1, mic.stopCount)

        transcript.complete(Result.success("git status"))
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0f, vm.uiState.value.amplitude, 0.0001f)
    }

    @Test
    fun secondMicTapStopsAndEmitsTranscription() = runTest {
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f))
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        // Collect the first transcription off the SharedFlow on a child
        // coroutine — we have to start the collector before the emission
        // because the flow is `replay = 0`.
        val received = mutableListOf<String>()
        val collector = launch {
            received += vm.transcriptions.first()
        }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
        assertEquals(listOf("git status"), received)
        collector.join()
    }

    @Test
    fun selectedModeSurvivesRecordingAndTranscription() = runTest {
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val collector = launch { vm.transcriptions.first() }
        runCurrent()

        vm.selectMode(DictationMode.Command)
        vm.onMicTap()
        runCurrent()
        assertEquals(DictationMode.Command, vm.uiState.value.mode)
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(DictationMode.Command, vm.uiState.value.mode)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        collector.join()
    }

    @Test
    fun selectedModeSurvivesWhileTranscribing() = runTest {
        val parkedWhisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = parkedWhisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.selectMode(DictationMode.Command)
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)
        assertEquals(DictationMode.Command, vm.uiState.value.mode)
    }

    @Test
    fun stopShowsProcessingUntilTranscriptionCompletesAndBlocksDuplicateTap() = runTest {
        val transcript = CompletableDeferred<Result<String>>()
        val mic = FakeMicCapture(amplitudes = listOf(0.6f, 0.6f, 0.6f))
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                return transcript.await()
            }
        }
        val vm = newVm(
            mic = mic,
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch {
            received += vm.transcriptions.first()
        }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)
        assertEquals(0f, vm.uiState.value.amplitude, 0.0001f)

        val startsBefore = mic.startCount
        val stopsBefore = mic.stopCount
        vm.onMicTap()
        runCurrent()
        assertEquals(startsBefore, mic.startCount)
        assertEquals(stopsBefore, mic.stopCount)
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        transcript.complete(Result.success("git status"))
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0f, vm.uiState.value.amplitude, 0.0001f)
        assertNull(vm.uiState.value.error)
        assertEquals(listOf("git status"), received)
        collector.join()
    }

    @Test
    fun transcriptionDoesNotMutateUiState() = runTest {
        // The inline ViewModel has no editable draft (that is the
        // composer's job). Successful transcription should leave UiState
        // unchanged apart from the FSM rewinding to Idle.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("ls -la") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val collector = launch { vm.transcriptions.take(1).toList() }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(RecordingState.Idle, state.recording)
        assertNull(state.error)
        collector.join()
    }

    @Test
    fun whitespaceOnlyTranscriptionIsDroppedBeforeEmit() = runTest {
        // Empty / whitespace-only payloads must not be written to the
        // terminal — they'd be a footgun on the shell prompt (sending a
        // bare newline would advance the prompt with no command). The
        // ViewModel drops them silently.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("   \n  ") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch {
            // Best-effort collect: if the ViewModel correctly drops the
            // whitespace, the take(1) below would hang. We instead spin
            // up the collector and assert post-stop that nothing has
            // arrived. Cancelled at the end of the test via runTest.
            vm.transcriptions.collect { received += it }
        }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertTrue(
            "expected no transcription emissions for whitespace, got $received",
            received.isEmpty(),
        )
        collector.cancel()
    }

    @Test
    fun transcriptionIsTrimmedBeforeEmit() = runTest {
        // Whisper output often has leading / trailing whitespace; the
        // shell sees that verbatim, which can be surprising ("  ls"
        // looks like a quoted argument in some shells). Trim before
        // emit so the user gets the command they expect.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("  git status  ") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch {
            received += vm.transcriptions.first()
        }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(listOf("git status"), received)
        collector.join()
    }

    // -- Configured silence auto-stop ---------------------------------------

    @Test
    fun silenceWindowAutoStopsRecording() = runTest {
        // Mirrors the composer's silence-watchdog test 1:1. The ViewModel
        // owns its own constant, but it must equal the composer's
        // long-dictation fallback.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("auto-stopped") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        val received = mutableListOf<String>()
        val collector = launch {
            received += vm.transcriptions.first()
        }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(3L * InlineDictationViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()

        advanceTimeBy(InlineDictationViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
        assertEquals(listOf("auto-stopped"), received)
        collector.join()
    }

    @Test
    fun silenceTimerResetsOnLoudAmplitude() = runTest {
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

        // ~55 polls (= 2.75s) burns through the leading loud chunk, the
        // 50-quiet stretch, and the resetting loud sample — but stops
        // short of the configured window, so the watchdog has not fired yet.
        advanceTimeBy(55L * InlineDictationViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(InlineDictationViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun defaultLongDictationWindowKeepsRecordingThroughTenSecondPause() = runTest {
        // Issue #397: inline dictation uses the same conservative default as
        // the prompt composer. A transient 10s pause must not stop recording
        // before the user resumes speaking.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(800) { 0f })
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("too early") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            vm.onMicTap()
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)

            advanceTimeBy(3L * InlineDictationViewModel.SAMPLE_INTERVAL_MS)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(
                "default 30s silence window must survive a 10s pause",
                RecordingState.Recording,
                vm.uiState.value.recording,
            )
            assertEquals(0, mic.stopCount)
        } finally {
            vm.onMicTap()
            advanceUntilIdle()
        }
    }

    // -- Whisper failure surfaced as error ----------------------------------

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
        // No save() on the storage; the factory will return null on the
        // gate check and we should never enter Recording.
        val storage = FakeVault()
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
        val vm = newVm()
        assertTrue(vm.hasApiKey())
    }

    @Test
    fun hasApiKeyFalseWhenVaultEmpty() {
        val storage = FakeVault()
        val vm = newVm(storage = storage)
        assertEquals(false, vm.hasApiKey())
    }

    // -- Permission denied --------------------------------------------------

    @Test
    fun surfacePermissionDeniedSetsErrorAndIdle() {
        val vm = newVm()
        vm.surfacePermissionDenied()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNotNull(vm.uiState.value.error)
        assertTrue(
            vm.uiState.value.error!!.contains("permission", ignoreCase = true),
        )
    }

    @Test
    fun clearErrorRemovesBanner() {
        val vm = newVm()
        vm.surfacePermissionDenied()
        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    // -- FSM no-op while transcribing ---------------------------------------

    // -- Issue #125: VoiceSettingsSnapshot integration --------------------

    @Test
    fun voiceSettingsLanguageHintFlowsIntoWhisperTranscribe() = runTest {
        // The inline path threads the user-configured language through
        // to Whisper, same as the composer.
        val captured = AtomicReference<String?>(null)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                captured.set(language)
                return Result.success("git status")
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            voiceSettings = FakeVoiceSettings(language = "fr"),
        )

        val received = mutableListOf<String>()
        val collector = launch { received += vm.transcriptions.first() }
        runCurrent()
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals("fr", captured.get())
        collector.join()
    }

    @Test
    fun voiceSettingsCustomSilenceWindowAutoStopsAtConfiguredThreshold() = runTest {
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        val customWindowMs = 1_500L
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("short") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            voiceSettings = FakeVoiceSettings(window = customWindowMs),
        )

        val collector = launch { vm.transcriptions.first() }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(3L * InlineDictationViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(customWindowMs + 500L)
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
        collector.join()
    }

    @Test
    fun micTapWhileTranscribingIsIgnored() = runTest {
        // Park the transcription on a never-resolving Whisper call so we
        // stay in `Transcribing` for the duration of the assertion. Then
        // tap again and assert no extra `start()` happened.
        val mic = FakeMicCapture()
        val parkedWhisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                // Suspend forever — we will cancel via runTest's scope.
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = newVm(
            mic = mic,
            whisper = parkedWhisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap() // -> Recording
        runCurrent()
        vm.onMicTap() // -> Transcribing (parked)
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        val startsBefore = mic.startCount
        val stopsBefore = mic.stopCount
        vm.onMicTap() // no-op — we're in Transcribing
        runCurrent()
        assertEquals(startsBefore, mic.startCount)
        assertEquals(stopsBefore, mic.stopCount)
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)
    }
}
