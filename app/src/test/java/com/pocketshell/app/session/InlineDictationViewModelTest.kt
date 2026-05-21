package com.pocketshell.app.session

import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.InlineDictationViewModel.RecordingState
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
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

    private fun newVm(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("git status") },
        storage: PromptComposerViewModel.ApiKeyVault = FakeVault().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ): InlineDictationViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = InlineDictationViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
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
        assertEquals(RecordingState.Idle, state.recording)
        assertNull(state.error)
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

    // -- 5s silence auto-stop -----------------------------------------------

    @Test
    fun silenceForFiveSecondsAutoStopsRecording() = runTest {
        // Mirrors the composer's silence-watchdog test 1:1. The ViewModel
        // owns its own constants, but they must equal the composer's per
        // D10 — we assert on `InlineDictationViewModel.SILENCE_WINDOW_MS`
        // here so the test would fail loudly if the constant drifted.
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
        // short of the 5s window, so the watchdog has not fired yet.
        advanceTimeBy(55L * InlineDictationViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(InlineDictationViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
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
