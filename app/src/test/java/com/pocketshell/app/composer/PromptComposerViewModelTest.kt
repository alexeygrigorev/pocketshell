package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
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
 *  - Idle -> Recording -> (configured silence auto-stop) -> Transcribing -> Idle
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
     * Issue #882: every VM built by [newVm] is tracked here and torn down in
     * [tearDownViewModels] so no recording ticker / sampler loop outlives its
     * test. The #880 recording-elapsed ticker is an infinite
     * `while { delay() }` loop that only breaks when recording leaves
     * [RecordingState.Recording]. A test that starts recording (e.g.
     * [androidSpeechProviderStartsWithoutOpenAiKey]) and ends its `runTest`
     * body without stopping would leave that loop alive; `runTest`'s final
     * `advanceUntilIdle` then advances virtual time through the infinite
     * `delay` forever and the whole JVM unit suite hangs (35-min CI timeout).
     * Clearing the VM cancels its `viewModelScope`, terminating any lingering
     * loop regardless of recording state.
     */
    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

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
        private val audio: ByteArray = SpeechAudioGuard.speechWavForTesting(),
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
            return audio
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
     * tests. The default mirrors the production long-dictation window
     * and no language hint; issue-specific tests override values through
     * the factory call site below.
     */
    private class FakeVoiceSettings(
        private var window: Long = PromptComposerViewModel.SILENCE_WINDOW_MS,
        private var language: String? = null,
        private var provider: VoiceTranscriptionProvider = VoiceTranscriptionProvider.OpenAiWhisper,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = window
        override fun whisperLanguageHint(): String? = language
        override fun transcriptionProvider(): VoiceTranscriptionProvider = provider

        fun setWindow(ms: Long) { window = ms }
        fun setLanguage(code: String?) { language = code }
        fun setProvider(value: VoiceTranscriptionProvider) { provider = value }
    }

    private class FakeSpeechRecognitionProvider(
        private val available: Boolean = true,
    ) : PromptComposerViewModel.SpeechRecognitionProvider {
        var listener: PromptComposerViewModel.SpeechRecognitionListener? = null
        var startCount = 0
        var stopCount = 0
        var cancelCount = 0
        var language: String? = null

        override fun isAvailable(): Boolean = available

        override fun start(
            language: String?,
            listener: PromptComposerViewModel.SpeechRecognitionListener,
        ): PromptComposerViewModel.SpeechRecognitionSession? {
            if (!available) return null
            startCount++
            this.language = language
            this.listener = listener
            return object : PromptComposerViewModel.SpeechRecognitionSession {
                override fun stopListening() {
                    stopCount++
                }

                override fun cancel() {
                    cancelCount++
                }
            }
        }
    }

    private fun newVm(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("hello world") },
        storage: ApiKeyVault = newStorage().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
        speechRecognitionProvider: PromptComposerViewModel.SpeechRecognitionProvider =
            UnavailableSpeechRecognitionProvider,
        composerDraftStore: ComposerDraftStore = DisabledComposerDraftStore,
        pendingTranscriptionStore: PromptComposerViewModel.PendingTranscriptionQueue =
            DisabledPendingTranscriptionQueue,
        connectivity: PromptComposerViewModel.ConnectivityProbe = AlwaysOnlineConnectivityProbe,
        outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            speechRecognitionProvider = speechRecognitionProvider,
            pendingTranscriptionStore = pendingTranscriptionStore,
            connectivity = connectivity,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = savedStateHandle,
        )
        if (samplerDispatcher != null) {
            vm.samplerDispatcher = samplerDispatcher
            vm.outboundQueueDispatcher = samplerDispatcher
        }
        vm.clock = clock
        // Issue #882: track for teardown so a test that leaves recording active
        // doesn't leak the #880 ticker loop into `runTest`'s final
        // `advanceUntilIdle` (which would hang the suite). See
        // [tearDownViewModels].
        createdViewModels += vm
        // Issue #891: the overall-send watchdog launches a real 110s `delay` on
        // viewModelScope the instant a send goes in-flight. `runTest`'s terminal
        // `advanceUntilIdle` would drain that delay and fire the watchdog after
        // every send, spuriously flipping the in-flight assertions of tests that
        // are NOT about the watchdog. Disable it here by default; the dedicated
        // watchdog tests re-enable it explicitly via
        // [PromptComposerViewModel.setSendWatchdogTimeoutForTest] so the
        // load-bearing behaviour is still proven (never vacuously skipped).
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
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
        // Tap again to settle the recording job — `runTest` waits for
        // every coroutine on the test scheduler to terminate, so a
        // dangling silence-watchdog loop would hang the test forever.
        vm.onMicTap()
        advanceUntilIdle()
    }

    @Test
    fun diagnosticsRecordWhisperRecordingSuccess() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f))
            val vm = newVm(
                mic = mic,
                whisper = fakeWhisperClient { Result.success("hello world") },
                samplerDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.onMicTap()
            runCurrent()
            vm.onMicTap()
            advanceUntilIdle()

            assertTrue(diagnostics.events.any {
                it.name == "composer_recording_start_result" &&
                    it.fields["provider"] == "OpenAiWhisper" &&
                    it.fields["status"] == "success"
            })
            assertTrue(diagnostics.events.any {
                it.name == "composer_recording_stop" &&
                    it.fields["provider"] == "OpenAiWhisper" &&
                    it.fields["status"] == "transcribing" &&
                    (it.fields["audioBytes"] as Int) > 0
            })
            assertTrue(diagnostics.events.any {
                it.name == "composer_transcription_result" &&
                    it.fields["status"] == "success" &&
                    it.fields["transcriptBytes"] == "hello world".toByteArray(Charsets.UTF_8).size
            })
            assertFalse(diagnostics.events.any { event ->
                event.fields.values.any { it == "hello world" }
            })
        } finally {
            diagnostics.close()
        }
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

    // -- Issue #452: silence / hallucination suppression --------------------

    /**
     * Mic that captures a silent WAV — passes the energy guard's WAV-shape
     * check but has zero RMS, so [SpeechAudioGuard.hasSpeechEnergy] returns
     * false and the FSM must skip Whisper.
     */
    private class SilentCaptureMic : PromptComposerViewModel.MicCapture {
        var stopCount = 0
        private var running = false
        override fun start() { running = true }
        override fun stop(): ByteArray {
            stopCount++
            running = false
            return SpeechAudioGuard.silentWavForTesting()
        }
        override fun currentAmplitude(): Float = if (running) 0.5f else 0f
    }

    @Test
    fun silentRecordingIsNotTranscribedAndShowsNoSpeechHint() = runTest {
        var transcribeCalls = 0
        val vm = newVm(
            mic = SilentCaptureMic(),
            whisper = fakeWhisperClient {
                transcribeCalls++
                Result.success("시청해주셔서 감사합니다!")
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("keep this ")
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Whisper was never called — the silent audio was rejected up front.
        assertEquals(0, transcribeCalls)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        // No hallucinated text leaked into the draft; the typed text stays.
        assertEquals("keep this ", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun hallucinationPhraseFromWhisperIsSuppressed() = runTest {
        // Audio clears the energy bar (FakeMicCapture returns a real-speech
        // WAV) but Whisper still returns a stock silence-hallucination
        // phrase — the backstop must drop it.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("시청해주셔서 감사합니다!") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("keep this ")
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        // The hallucinated phrase is not appended; the prior draft survives.
        assertEquals("keep this ", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun realSpeechIsStillTranscribedNormally() = runTest {
        // Regression guard: the silence/hallucination changes must not
        // affect a normal dictation.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("git status", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
    }

    // -- Issue #1350: Send clears the draft for EVERY prompt shape --------
    //
    // Class-coverage guard for the maintainer's dogfood annoyance ("the next
    // dictation/typing appends to stale text"). `clearComposerForHandoff`
    // (issue #971 single-representation hand-off) empties the editable draft the
    // instant a Send is dispatched, and it is length/shape-INDEPENDENT. These
    // pin that invariant across the common text shapes — short, long/wrapping,
    // and multi-line — so a future change can't
    // reintroduce a stale draft for one shape (e.g. a wrapped long prompt).

    @Test
    fun sendClearsDraftForShortPrompt() = runTest {
        assertSendClearsDraft("deploy the staging build now")
    }

    @Test
    fun sendClearsDraftForLongWrappingPrompt() = runTest {
        assertSendClearsDraft(
            "please carefully refactor the authentication middleware module so that " +
                "every single inbound request is fully validated against the brand new " +
                "rotating session token format and structured audit logging schema before " +
                "it is ever allowed to reach the request handler layer or any downstream " +
                "service in the pipeline today",
        )
    }

    @Test
    fun sendClearsDraftForMultiLinePrompt() = runTest {
        assertSendClearsDraft(
            "first line of the plan\nsecond line continues it\nthird and final line",
        )
    }

    private suspend fun TestScope.assertSendClearsDraft(prompt: String) {
        val vm = newVm()
        val sends = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job = launch { vm.sendRequests.collect { sends += it } }
        vm.onDraftChange(prompt)
        assertEquals(prompt, vm.uiState.value.draft)

        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // The editor is emptied the instant the send is handed off to the queue
        // (issue #971 single-representation), regardless of prompt length/shape —
        // so the next dictation/typing starts from a clean field.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
        // The exact prompt still travelled out in the SendRequest (nothing lost).
        assertEquals(1, sends.size)
        assertEquals(prompt, sends.single().text)
        job.cancelAndJoin()
    }

    // -- Configured silence auto-stop ---------------------------------------

    @Test
    fun silenceWindowAutoStopsRecording() = runTest {
        // Amplitudes: first three loud, then nothing.
        val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f) + List(500) { 0f })
        // Bind the ViewModel's clock to the virtual scheduler so the
        // silence window advances in the same coordinate system the test
        // does via `advanceTimeBy`.
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

        // Advance past the configured silence window — auto-stop should fire.
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
        // stop short of the configured window so the silence watchdog has
        // not yet fired by this point.
        advanceTimeBy(55L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Now advance past the (post-reset) silence window. The
        // watchdog should fire on the trailing quiet stretch.
        advanceTimeBy(PromptComposerViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun defaultLongDictationWindowKeepsRecordingThroughTenSecondPause() = runTest {
        // Issue #397: the default must be much less sensitive than the old
        // 5s window. Ten seconds of transient silence after speech is a
        // natural long-dictation pause and must not auto-stop by default.
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

            advanceTimeBy(3L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
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
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun softSpeechBelowCaptureBarDoesNotTriggerPrematureAutoStop() = runTest {
        // Regression for #587 (units/threshold mismatch). A user dictating
        // softly / far from the mic produces frames whose PEAK amplitude
        // (`currentAmplitude()` is peak per `PcmCapturePump.peakAmplitude`)
        // sits BELOW the capture-confirmation bar
        // `SILENCE_AMPLITUDE_THRESHOLD = 0.04f` but well ABOVE the silence
        // floor — e.g. ~0.02 peak (RMS ~0.014, comfortably over the audio
        // guard's `MIN_RMS_AMPLITUDE = 0.006f`). Before the fix the watchdog
        // reset its silence clock ONLY on `>= 0.04f`, so this real speech
        // was treated as silence and auto-stopped mid-utterance — the
        // captured WAV then failed `hasSpeechEnergy` and the user saw a
        // false "No speech detected". The watchdog must keep recording while
        // soft-but-real signal (>= SILENCE_RESET_AMPLITUDE_THRESHOLD) is
        // present.
        val softPeak = 0.02f
        assertTrue(
            "test fixture must be below the capture bar",
            softPeak < PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD,
        )
        assertTrue(
            "test fixture must be above the silence-reset floor",
            softPeak >= PromptComposerViewModel.SILENCE_RESET_AMPLITUDE_THRESHOLD,
        )
        // A continuous stream of soft speech for far longer than the silence
        // window. If the watchdog only resets on >= 0.04, it auto-stops; with
        // the reconciled reset floor it keeps recording.
        val mic = FakeMicCapture(amplitudes = List(2_000) { softPeak })
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("soft speech") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        try {
            vm.onMicTap()
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)

            // Advance well past the silence window. Soft-but-continuous
            // speech must NOT auto-stop.
            advanceTimeBy(PromptComposerViewModel.SILENCE_WINDOW_MS + 5_000L)
            runCurrent()

            assertEquals(
                "soft real speech below the 0.04 capture bar but above the " +
                    "silence-reset floor must keep recording, not auto-stop",
                RecordingState.Recording,
                vm.uiState.value.recording,
            )
            assertEquals(0, mic.stopCount)
        } finally {
            vm.cancelRecording()
            advanceUntilIdle()
        }
    }

    @Test
    fun pureSilenceStillAutoStopsAfterWindow() = runTest {
        // Counter-bound for the fix above (#452 must not regress). True
        // near-silence (peak below the silence-reset floor — room hum / mic
        // self-noise) must STILL auto-stop after the window. If the reset
        // floor were dropped too far this would hang recording forever.
        val nearSilencePeak = 0.003f
        assertTrue(
            "near-silence fixture must be below the silence-reset floor",
            nearSilencePeak < PromptComposerViewModel.SILENCE_RESET_AMPLITUDE_THRESHOLD,
        )
        val mic = FakeMicCapture(
            amplitudes = List(2_000) { nearSilencePeak },
            // Audio the guard rejects as silence so we land on the no-speech
            // path, not a transcription.
            audio = SpeechAudioGuard.silentWavForTesting(),
        )
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient { Result.success("should not run") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        advanceTimeBy(PromptComposerViewModel.SILENCE_WINDOW_MS + 1_000L)
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(1, mic.stopCount)
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
    fun androidSpeechProviderStartsWithoutOpenAiKey() = runTest {
        val mic = FakeMicCapture()
        var whisperCalls = 0
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(
            provider = VoiceTranscriptionProvider.AndroidSpeech,
            language = "en",
        )
        val vm = newVm(
            mic = mic,
            storage = newStorage(),
            whisper = fakeWhisperClient {
                whisperCalls++
                Result.success("should not be used")
            },
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(vm.needsOpenAiKeyForMicTap())
        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals(1, speech.startCount)
        assertEquals("en", speech.language)
        assertEquals(0, mic.startCount)
        assertEquals(0, whisperCalls)
        assertNull(vm.uiState.value.error)

        // Issue #882: this assertion-only test left recording active. The
        // #880 ticker (`viewModelScope.launch(samplerDispatcher) { while {
        // delay() } }`) runs on the `runTest` scheduler, so `runTest`'s final
        // `advanceUntilIdle` would advance virtual time through the infinite
        // `delay` forever and hang the whole JVM unit suite (the 35-min CI
        // timeout). Drive recording back to Idle so the ticker breaks before
        // the body ends. (The `@After` teardown is the broader net for any
        // ticker that ran on the rule's Main dispatcher instead.)
        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun androidSpeechPartialUpdatesReplacePreviousHypothesis() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("Run")
        vm.onMicTap()
        runCurrent()

        speech.listener!!.onPartial("git")
        assertEquals("Run git", vm.uiState.value.draft)
        assertEquals("git", vm.uiState.value.liveTranscript)

        speech.listener!!.onPartial("git status")
        assertEquals("Run git status", vm.uiState.value.draft)
        assertEquals("git status", vm.uiState.value.liveTranscript)

        speech.listener!!.onFinal("git status --short")
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("Run git status --short", vm.uiState.value.draft)
        assertNull(vm.uiState.value.liveTranscript)
    }

    // -- Issue #880: Android-recognizer recording timer must advance --------

    @Test
    fun androidSpeechRecordingTimerAdvancesFromStart() = runTest {
        // #880 reproduce-first: with the Android SpeechRecognizer provider the
        // composer recording timer was frozen at 00:00 — the Whisper PCM
        // sampler loop that ticks [recordingElapsedMs] never ran for this path.
        // Drive a virtual wall clock and assert the elapsed timer advances. On
        // the unfixed code this stays at "00:00" (no ticking job for the
        // Android path); with the fix the wall-clock tick advances it.
        var now = 5_000_000L
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            storage = newStorage(),
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals("00:00", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Advance the wall clock by 13s and let the ticker poll run.
        now += 13_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:13", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Advance another 7s -> 20s total.
        now += 7_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:20", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Settle: final lands the FSM in Idle and cancels the ticker.
        speech.listener!!.onFinal("hello world")
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun androidSpeechTickerStopsWhenRecordingEnds() = runTest {
        // The ticking job must terminate once recording ends so it does not
        // keep updating state (or leak the coroutine) after Idle.
        var now = 1_000L
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            storage = newStorage(),
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )

        vm.onMicTap()
        runCurrent()
        now += 4_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:04", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Stop (the user's "Insert"/"Send" path) -> Transcribing, then final.
        speech.listener!!.onFinal("git status")
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)

        // After Idle the clock keeps moving but the timer must NOT advance —
        // the ticker is cancelled.
        val frozen = vm.uiState.value.recordingElapsedMs
        now += 60_000L
        advanceTimeBy(10L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(frozen, vm.uiState.value.recordingElapsedMs)
    }

    /**
     * Issue #882 regression: the #880 recording-elapsed ticker is an infinite
     * `while (isActive) { if (!Recording) break; …; delay() }` loop. If a test
     * starts recording on the `runTest` scheduler and leaves it active, the
     * end-of-body `advanceUntilIdle` advances virtual time through that `delay`
     * forever — hanging the JVM unit suite until the 35-min CI timeout. This
     * test reproduces that exact condition (recording left active) and proves
     * the lifecycle teardown ([clearForTest] / [onCleared] cancelling the
     * ticker) makes `advanceUntilIdle` terminate. The hard `timeout` turns any
     * regression into a fast deterministic failure instead of a suite hang.
     */
    @Test(timeout = 30_000L)
    fun recordingTickerTerminatesOnVmClearSoSuiteCannotHang() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            storage = newStorage(),
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        // Start recording and let the ticker loop arm its `delay`.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // Tear the VM down WITHOUT first stopping recording — the real
        // lifecycle clear. Before the #882 fix this left the ticker looping;
        // after it, `onCleared` cancels the ticker job.
        vm.clearForTest()

        // The load-bearing assertion: with the ticker cancelled, the scheduler
        // idles and `advanceUntilIdle` returns. On the unfixed code this line
        // never returns (infinite virtual-time loop) and the `@Test(timeout)`
        // fires — proving the regression is caught.
        advanceUntilIdle()
    }

    @Test
    fun androidSpeechEmptyFinalUsesLastPartialTranscript() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onDraftChange("Run")
        vm.onMicTap()
        runCurrent()

        speech.listener!!.onPartial("git status")
        speech.listener!!.onFinal("   ")
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("Run git status", vm.uiState.value.draft)
        assertNull(vm.uiState.value.liveTranscript)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun androidSpeechNoMatchAfterPartialDispatchesQueuedSend() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sends = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job = launch { vm.sendRequests.collect { sends += it } }

        vm.onDraftChange("Please")
        vm.onMicTap()
        runCurrent()
        speech.listener!!.onPartial("summarize this")
        vm.requestSend(withEnter = true)
        runCurrent()

        speech.listener!!.onError(PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE)
        advanceUntilIdle()

        assertEquals(listOf(PromptComposerViewModel.SendRequest("Please summarize this", true)), sends)
        // Issue #971: the queued voice send hands the prompt off to the queue
        // row, so the editor is CLEARED in flight (single representation);
        // `sendInFlight` clears when the host confirms delivery.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        job.cancelAndJoin()
    }

    @Test
    fun androidSpeechEmptyFinalWithoutPartialShowsAndroidSpecificRetryCopy() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        speech.listener!!.onFinal("   ")
        advanceUntilIdle()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE, vm.uiState.value.error)
        assertNull(vm.uiState.value.liveTranscript)
    }

    @Test
    fun androidSpeechCancelRestoresPreDictationDraftAndIgnoresLateCallbacks() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )

        vm.onDraftChange("typed before dictation")
        vm.onMicTap()
        runCurrent()

        speech.listener!!.onPartial("temporary hypothesis")
        assertEquals("typed before dictation temporary hypothesis", vm.uiState.value.draft)

        vm.cancelRecording()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("typed before dictation", vm.uiState.value.draft)
        assertEquals("typed before dictation", savedStateHandle[PromptComposerViewModel.KEY_DRAFT])
        assertNull(vm.uiState.value.liveTranscript)
        assertEquals(0, speech.stopCount)
        assertTrue(speech.cancelCount > 0)

        speech.listener!!.onPartial("late partial")
        speech.listener!!.onFinal("late final")
        advanceUntilIdle()

        assertEquals("typed before dictation", vm.uiState.value.draft)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun androidSpeechUnavailableShowsErrorGracefully() = runTest {
        val speech = FakeSpeechRecognitionProvider(available = false)
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            storage = newStorage(),
            whisper = null,
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0, speech.startCount)
        assertEquals(
            PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun androidSpeechSendWhileRecordingStopsThenDispatchesFinalTranscript() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sends = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job = launch { vm.sendRequests.collect { sends += it } }

        vm.onDraftChange("Please")
        vm.onMicTap()
        runCurrent()
        vm.requestSend(withEnter = true)
        runCurrent()
        assertEquals(1, speech.stopCount)
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        speech.listener!!.onFinal("summarize this")
        advanceUntilIdle()

        assertEquals(listOf(PromptComposerViewModel.SendRequest("Please summarize this", true)), sends)
        // Issue #971: handed off to the queue row — editor cleared in flight.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        job.cancelAndJoin()
    }

    @Test
    fun androidSpeechCancelAfterStopIgnoresLateCallbacksAndQueuedSend() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )
        val sends = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job = launch { vm.sendRequests.collect { sends += it } }

        vm.onDraftChange("typed before dictation")
        vm.onMicTap()
        runCurrent()
        speech.listener!!.onPartial("live words")
        assertEquals("typed before dictation live words", vm.uiState.value.draft)

        vm.requestSend(withEnter = true)
        runCurrent()
        assertEquals(1, speech.stopCount)
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        vm.cancelRecording()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("typed before dictation", vm.uiState.value.draft)
        assertEquals("typed before dictation", savedStateHandle[PromptComposerViewModel.KEY_DRAFT])
        assertNull(vm.uiState.value.liveTranscript)
        assertTrue(speech.cancelCount > 0)

        speech.listener!!.onPartial("late partial")
        speech.listener!!.onFinal("late final")
        advanceUntilIdle()

        assertEquals("typed before dictation", vm.uiState.value.draft)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertTrue(sends.isEmpty())
        job.cancelAndJoin()
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

    // -- Issue #1245: recording works with NO lock concept -------------------

    @Test
    fun recordingRunsWithoutAnyLockConceptUntilStopped() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))

        // A plain mic tap starts recording — there is no press-and-hold or
        // swipe-to-lock step; recording simply runs.
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        // The recording keeps running (Discard / Insert / Send are the only ways
        // to end it), and cancel returns to Idle cleanly.
        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
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
        val attachmentPath = "~/.pocketshell/attachments/host-1/keep-this.png"
        vm.seedAttachment(attachmentPath)
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
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = attachmentPath,
                    displayName = "keep-this.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
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
        // The recording discard action is separate from Transcribing's cancel
        // affordance. Even if a stale recording-discard tap reaches the
        // ViewModel during Transcribing, the FSM must not jump back to Idle
        // while the Whisper response is in flight — that would race the
        // success path's draft append and drop the transcript.
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
        var markFailureStarted: CompletableDeferred<Unit>? = null
        var markFailureRelease: CompletableDeferred<Unit>? = null

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

        fun storedAudio(id: String): ByteArray? = initialAudioMap[id]

        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun markFailure(
            id: String,
            errorMessage: String,
        ): com.pocketshell.app.voice.PendingTranscriptionItem? {
            markFailureStarted?.complete(Unit)
            markFailureRelease?.await()
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
        // Configure a 1.5s window — well below the long-dictation default
        // — and verify the recording auto-stops just past the new bound.
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
        // fire even though the default fallback has not elapsed.
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
    fun restoreFailedSendPutsPayloadBackInDraftWithActionableError() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val request = PromptComposerViewModel.SendRequest(
            text = "dictated prompt while offline",
            withEnter = true,
        )

        vm.restoreFailedSend(request)

        assertEquals("dictated prompt while offline", vm.uiState.value.draft)
        assertEquals(
            "Not sent. Reconnect, then send again or discard the draft.",
            vm.uiState.value.error,
        )
        assertEquals(PromptComposerViewModel.RecordingState.Idle, vm.uiState.value.recording)
    }

    // -- Issue #746: discard draft + session scoping ------------------------

    @Test
    fun discardDraftClearsTextAttachmentsBannerAndSavedState() = runTest {
        // Issue #746: the "Not sent. …or discard the draft." banner promised a
        // Discard action that did not exist. discardDraft() must wipe the draft
        // text, every staged attachment, the error/status banner, and the
        // persisted SavedStateHandle keys so the next open is a clean slate.
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )
        vm.onDraftChange("ничего не происходит")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()
        // The draft + attachment chip are staged before the failed send.
        assertTrue(vm.uiState.value.draft.isNotEmpty())
        assertTrue(vm.uiState.value.attachments.isNotEmpty())
        // Simulate the degraded-send "Not sent" banner. The "Not sent" state is a
        // draft (+ since #872, the restored attachment tiles) + banner the user
        // must be able to discard. Here we pass a bare SendRequest (clean draft
        // defaults to text, no tiles) so the assertions below focus on discard.
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "ничего не происходит", withEnter = true),
        )
        assertTrue(vm.uiState.value.draft.isNotEmpty())
        assertNotNull(vm.uiState.value.error)

        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertNull(vm.uiState.value.error)
        // SavedStateHandle is wiped so a process-death recreate stays clean.
        assertEquals("", savedStateHandle.get<String>(PromptComposerViewModel.KEY_DRAFT).orEmpty())
        assertNull(savedStateHandle.get<String>(PromptComposerViewModel.KEY_DRAFT_OWNER))
    }

    @Test
    fun discardDraftClearsStagedAttachmentChipsBeforeSend() = runTest {
        // Issue #746: discard must also clear staged attachment chips that are
        // still present (i.e. before a send folded them into the draft text).
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("with a chip")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isNotEmpty())

        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test
    fun draftFromOneSessionDoesNotBleedIntoAnother() = runTest {
        // Issue #746: a "Not sent" draft authored in session A must NOT appear
        // when the composer is opened/focused in session B. Switching the
        // target session discards the stale draft + banner.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )
        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)

        // Switch to session B: the stale draft + banner are gone.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun draftSurvivesReturningToTheOwningSession() = runTest {
        // Issue #746: scoping discards the draft only in OTHER sessions. While
        // the composer stays on the session that authored the draft, the draft
        // (and its retry banner) must be preserved so the user can resend.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )
        // Re-reporting the same target (e.g. a recomposition) is a no-op.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun processRestoredDraftIsAdoptedByFirstTargetNotDropped() = runTest {
        // Issue #746: a draft restored from SavedStateHandle after process
        // death has no owner stamp yet. The FIRST target the host reports must
        // ADOPT it (the user is back where they typed it) rather than discard a
        // legitimately-recovered prompt. A subsequent switch to a different
        // session then discards it.
        val savedStateHandle = SavedStateHandle()
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT] = "recovered draft"
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )
        assertEquals("recovered draft", vm.uiState.value.draft)

        // First target after restore adopts the orphan draft.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("recovered draft", vm.uiState.value.draft)

        // A later switch to a different session discards it.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun emptyDraftSwitchJustReassignsTargetWithoutClearingFutureDrafts() = runTest {
        // Issue #746: switching sessions with NO draft is a no-op clear, and a
        // draft typed afterwards is owned by the now-current session so it
        // persists on a same-session recomposition.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.onComposerTargetChanged("1/sessionB")
        vm.onDraftChange("typed in B")
        // Same-session re-report keeps the draft.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("typed in B", vm.uiState.value.draft)
        // Switching away from B discards B's draft.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("", vm.uiState.value.draft)
    }

    // -- Issue #832: durable per-session draft store ------------------------

    @Test
    fun failedSendDraftSurvivesSwitchAwayAndBack() = runTest {
        // Issue #832 reproduction (the maintainer's exact dogfood scenario): an
        // attachment/send fails mid-session (silent drop), the composer keeps
        // the draft ("Your draft was kept"), the user switches to another
        // session, then returns — and the draft MUST still be there. Before the
        // durable per-session store this draft was discarded on the FIRST
        // switch and was unrecoverable through the UI.
        //
        // RED without the fix: with the per-session store wired, the switch
        // back used to find an empty draft (the old onComposerTargetChanged
        // discarded it). GREEN: the store reloads session A's draft.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")

        // The send into session A fails; the composer keeps the draft.
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )
        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)

        // The user switches to session B: A's draft must NOT bleed into B.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)

        // The user returns to session A: the kept draft is restored.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("draft for A", vm.uiState.value.draft)
    }

    @Test
    fun multipleSessionsEachKeepTheirOwnDurableDraft() = runTest {
        // Issue #832 class-coverage: distinct drafts in three sessions all
        // survive arbitrary switching; each session only ever shows its own.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )

        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("alpha draft")
        vm.onComposerTargetChanged("1/b")
        assertEquals("", vm.uiState.value.draft)
        vm.onDraftChange("bravo draft")
        vm.onComposerTargetChanged("2/c")
        assertEquals("", vm.uiState.value.draft)
        vm.onDraftChange("charlie draft")

        // Cycle back through every session: each reloads its own draft.
        vm.onComposerTargetChanged("1/a")
        assertEquals("alpha draft", vm.uiState.value.draft)
        vm.onComposerTargetChanged("2/c")
        assertEquals("charlie draft", vm.uiState.value.draft)
        vm.onComposerTargetChanged("1/b")
        assertEquals("bravo draft", vm.uiState.value.draft)
    }

    @Test
    fun editingDraftAfterSwitchBackUpdatesTheRightSessionsStore() = runTest {
        // Issue #832: an edit after returning to a session is persisted under
        // THAT session, not the one we switched away from.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("a v1")
        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("a v2")

        assertEquals("a v2", store.load("1/a"))
        assertNull(store.load("1/b"))
    }

    @Test
    fun sharedPrefsDraftWritesAreScheduledOffCallerThreadButVisibleToSessionSwitches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPrefsComposerDraftStore(ApplicationProvider.getApplicationContext())
        val target = "test/off-main-${System.nanoTime()}"
        val vm = newVm(
            samplerDispatcher = dispatcher,
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged(target)

        vm.onDraftChange("first")
        vm.onDraftChange("second")

        assertEquals("second", vm.uiState.value.draft)
        assertNull(store.load(target))

        vm.onComposerTargetChanged("$target/other")
        vm.onComposerTargetChanged(target)

        assertEquals("second", vm.uiState.value.draft)
        assertNull(store.load(target))

        advanceUntilIdle()

        assertEquals("second", store.load(target))
    }

    @Test
    fun clearDraftWinsOverPendingSharedPrefsDraftSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPrefsComposerDraftStore(ApplicationProvider.getApplicationContext())
        val target = "test/clear-wins-${System.nanoTime()}"
        val vm = newVm(
            samplerDispatcher = dispatcher,
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged(target)

        vm.onDraftChange("stale draft")
        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        advanceUntilIdle()

        assertNull(store.load(target))
    }

    @Test
    fun discardClearsTheDurableSlotSoSwitchBackIsEmpty() = runTest {
        // Issue #832: Discard is the explicit "throw it away" control — it must
        // drop the durable slot too, otherwise a switch back resurrects the
        // draft the user just discarded.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "stale prompt", withEnter = true),
        )
        assertEquals("stale prompt", store.load("1/a"))

        vm.discardDraft()
        assertNull(store.load("1/a"))

        // Switch away and back: the discarded draft does not come back.
        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun deliveredSendClearsTheDurableSlot() = runTest {
        // Issue #832: a confirmed delivery wipes the draft AND its durable
        // slot, so the next time the user lands on that session the composer
        // is a clean slate (not a resurrected just-sent prompt).
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        assertEquals("about to send", store.load("1/a"))

        vm.markSendDelivered()
        assertNull(store.load("1/a"))

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun restoreFailedSendUsesRequestTargetWhenCurrentComposerMoved() = runTest {
        // Issue #900: a send can resolve after the user has switched sessions.
        // The failed draft belongs to the tap-time session carried by the request,
        // not whatever composer target is current when the callback returns.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        val request = PromptComposerViewModel.SendRequest(
            text = "draft for A",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a"),
        )

        vm.onComposerTargetChanged("1/b")
        vm.onDraftChange("draft for B")
        vm.restoreFailedSend(request)

        assertEquals("draft for B", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        assertEquals("draft for A", store.load("1/a"))
        assertEquals("draft for B", store.load("1/b"))

        vm.onComposerTargetChanged("1/a")
        assertEquals("draft for A", vm.uiState.value.draft)
    }

    @Test
    fun composerTargetChangedRefreshesOutboundQueueForCurrentSession() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val a1 = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "first A",
            createdAtMs = 1L,
        )
        val b1 = queue.enqueue(
            sessionKey = "1/b",
            cleanText = "only B",
            createdAtMs = 2L,
        )
        val a2 = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "second A",
            createdAtMs = 3L,
        )
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )

        assertTrue(vm.outboundQueueItems.value.isEmpty())

        vm.onComposerTargetChanged("1/a")
        assertEquals(listOf(a1.id, a2.id), vm.outboundQueueItems.value.map { it.id })

        vm.onComposerTargetChanged("1/b")
        assertEquals(listOf(b1.id), vm.outboundQueueItems.value.map { it.id })

        vm.onComposerTargetChanged(null)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun discardOutboundItemRemovesQueuedAndFailedButKeepsActiveRows() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val inFlight = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "in flight",
            createdAtMs = 1L,
        ).let { queue.claimNext("1/a")!! }
        val queued = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "queued",
            createdAtMs = 2L,
        )
        val failed = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "failed",
            createdAtMs = 3L,
        ).let { queue.markFailed(it.id, lastError = "lost", lastAttemptAtMs = 10L)!! }
        val uploading = queue.enqueue(
            sessionKey = "1/a",
            cleanText = "uploading",
            createdAtMs = 4L,
        ).let { queue.markUploading(it.id)!! }
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/a")
        assertEquals(
            listOf(inFlight.id, queued.id, failed.id, uploading.id),
            vm.outboundQueueItems.value.map { it.id },
        )

        vm.discardOutboundItem(uploading.id)
        vm.discardOutboundItem(inFlight.id)
        assertNotNull(queue.item(uploading.id))
        assertNotNull(queue.item(inFlight.id))

        vm.discardOutboundItem(queued.id)
        assertNull(queue.item(queued.id))
        assertEquals(
            listOf(inFlight.id, failed.id, uploading.id),
            vm.outboundQueueItems.value.map { it.id },
        )

        vm.discardOutboundItem(failed.id)
        assertNull(queue.item(failed.id))
        assertEquals(
            listOf(inFlight.id, uploading.id),
            vm.outboundQueueItems.value.map { it.id },
        )
    }

    @Test
    fun deliveredSendUsesRequestTargetWhenCurrentComposerMoved() = runTest {
        // Issue #900: the success path has the same stale-callback risk as
        // restore. A delayed success for A must clear A's durable draft without
        // clearing B's currently visible draft.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        val request = PromptComposerViewModel.SendRequest(
            text = "about to send",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a"),
        )

        vm.onComposerTargetChanged("1/b")
        vm.onDraftChange("draft for B")
        vm.markSendDelivered(request)

        assertNull(store.load("1/a"))
        assertEquals("draft for B", vm.uiState.value.draft)
        assertEquals("draft for B", store.load("1/b"))

        vm.onComposerTargetChanged("1/a")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun failedAttachmentSendPreservesAttachmentPathsForResend() = runTest {
        // Issue #694: the maintainer's "I can't send attachments anymore"
        // turned out to be a degraded-connection "Not sent" failure. The
        // worry is that the attachments get SILENTLY DROPPED on the failed
        // send so the reconnect-then-resend goes out without them.
        //
        // The composer folds the staged attachment paths into the outgoing
        // prompt's "Attached files:" suffix at send time. When the host
        // reports the send failed, [restoreFailedSend] must put that EXACT
        // payload (text + attachment paths) back in the draft so a resend
        // after reconnect still carries the attachments — not a silent drop.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("Review these")
        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260611-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260611-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()

        // First send: capture the composed prompt the sheet would route to
        // the host's `onSend`.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        val composed = sent[0]
        // The composed prompt carries both files.
        assertTrue(composed.text.contains("20260611-120000-01-report.txt"))
        assertTrue(composed.text.contains("20260611-120000-02-data.csv"))
        // Issue #971: in-flight — the prompt + chips were HANDED OFF, so the
        // editor is cleared (single representation); `sendInFlight` is true while
        // awaiting the host. The clean draft + tiles still travel in the request.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)

        // The host reports the write failed (degraded connection). The sheet
        // routes the exact request back into [restoreFailedSend].
        //
        // Issue #872 — close the #832 AC2 gap: restoreFailedSend now restores the
        // CLEAN draft + the actual attachment TILES (not the paths folded into
        // text). The user sees their prompt and the file tiles again — exactly
        // what they had before tapping Send.
        vm.restoreFailedSend(composed)
        assertFalse(vm.uiState.value.sendInFlight)

        // The restored draft is the clean text — NOT polluted with raw remote
        // paths — and the two attachment tiles are back on screen.
        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.attachments.size)
        assertEquals(
            listOf(
                "~/.pocketshell/attachments/host-1/20260611-120000-01-report.txt",
                "~/.pocketshell/attachments/host-1/20260611-120000-02-data.csv",
            ),
            vm.uiState.value.attachments.map { it.remotePath },
        )
        assertEquals(
            "Not sent. Reconnect, then send again or discard the draft.",
            vm.uiState.value.error,
        )

        // Resend after reconnect: the tiles are still present, so
        // [dispatchSendNow] re-appends both attachment paths from the live tiles
        // at send time — the resend STILL includes both attachments, never a
        // silent drop, and the agent receives the exact same composed prompt.
        val resent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val resendJob: Job = launch { vm.sendRequests.collect { resent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        resendJob.cancelAndJoin()

        assertEquals(1, resent.size)
        assertEquals(composed.text, resent[0].text)
        assertTrue(resent[0].text.contains("20260611-120000-01-report.txt"))
        assertTrue(resent[0].text.contains("20260611-120000-02-data.csv"))
        assertEquals(2, resent[0].attachments.size)
    }

    // -- Issue #872: durable per-session STAGED ATTACHMENT refs ----------------

    @Test
    fun failedSendAttachmentSurvivesSwitchAwayAndBack() = runTest {
        // Issue #872 (the maintainer's exact dogfood scenario, attachment half):
        // attach a file, type a note, tap Send, the send fails (a spurious flap /
        // genuine drop) -> the composer keeps the draft AND the attachment tile.
        // The user switches to another session and returns — and the ATTACHMENT
        // (not just the text) MUST still be there. Before #872 the staged tiles
        // were explicitly dropped (`attachments = emptyList()`) on every switch,
        // so the attachment was unrecoverable through the UI even though the text
        // survived (#832 AC2 gap).
        //
        // RED without the fix: the switch back found ZERO attachment tiles (the
        // old onComposerTargetChanged dropped them). GREEN: the durable store
        // reloads session A's staged attachment.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        vm.onDraftChange("please review")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()

        // Capture the composed SendRequest the sheet would route, then fail it.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(1, sent.size)

        vm.restoreFailedSend(sent[0])
        // The failed send kept the clean draft AND the attachment tile.
        assertEquals("please review", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertNotNull(vm.uiState.value.error)
        // Both are durably persisted under session A.
        assertEquals("please review", store.load("1/sessionA"))
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/shot.png"),
            store.loadAttachments("1/sessionA").map { it.remotePath },
        )

        // Switch to session B: A's attachment must NOT bleed into B.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())

        // Return to session A: the kept draft AND the attachment tile are back.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("please review", vm.uiState.value.draft)
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/shot.png"),
            vm.uiState.value.attachments.map { it.remotePath },
        )

        // And a resend after the round-trip still carries the attachment.
        val resent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val resendJob: Job = launch { vm.sendRequests.collect { resent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        resendJob.cancelAndJoin()
        assertEquals(1, resent.size)
        assertEquals(1, resent[0].attachments.size)
        assertTrue(resent[0].text.contains("shot.png"))
    }

    @Test
    fun deliveredSendClearsTheDurableAttachmentSlot() = runTest {
        // Issue #872: a confirmed delivery must drop the durable attachment refs
        // too (the sibling of clearing the text slot), so a later switch back
        // does not resurrect already-sent tiles.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/sent.png"))
        }
        advanceUntilIdle()
        // Persist the staged attachment via a failed-then-... no: simulate the
        // durable persistence by failing once so the slot is written.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        vm.restoreFailedSend(sent[0])
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/sent.png"),
            store.loadAttachments("1/a").map { it.remotePath },
        )

        // Now the (re)send is delivered: the durable attachment slot is cleared.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        vm.markSendDelivered()
        assertTrue(store.loadAttachments("1/a").isEmpty())

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test
    fun discardClearsTheDurableAttachmentSlot() = runTest {
        // Issue #872: Discard must drop the durable attachment slot too, else a
        // switch back resurrects the attachment the user just discarded.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("draft")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/x.png"))
        }
        advanceUntilIdle()
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        vm.restoreFailedSend(sent[0])
        assertFalse(store.loadAttachments("1/a").isEmpty())

        vm.discardDraft()
        assertTrue(store.loadAttachments("1/a").isEmpty())

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertEquals("", vm.uiState.value.draft)
    }

    // -- Issue #872 PART B reopen (v0.4.14): the ACTUAL on-device drop ----------
    //
    // The maintainer's exact reopen wording (2026-06-22): "attaching a
    // screenshot, tapping Send — something happens with the connection (a
    // reconnect), the text message goes through but the ATTACHMENT is lost."
    //
    // The text goes through => this is the DELIVERED path, NOT restoreFailedSend.
    // The 91510d0a fix only made restoreFailedSend durable — but the real drop is
    // in dispatchSendNow's upload-in-flight branch: when a reconnect/transport
    // flap slows the attachment SFTP upload so it is STILL in flight when Send
    // fires, the old code CANCELLED the upload and sent text-only, silently
    // eating the attachment (no tile carried, no durable ref, no error). That is
    // why the fix didn't hold on device — the durable path never ran for this
    // trigger.

    @Test
    fun sendWhileUploadStillInFlightDoesNotSilentlyDropTheAttachment() = runTest {
        // REPRODUCES THE REOPEN (RED on base): attach a screenshot whose upload is
        // still in flight (a reconnect/flap slowed the SFTP transfer), type a
        // note, tap Send. On base the upload is cancelled and a TEXT-ONLY request
        // goes out with ZERO attachments — the attachment is silently lost. The
        // fix must instead AWAIT the in-flight upload and dispatch the send WITH
        // the attachment once it stages — so the attachment is never dropped.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        // The upload is gated so it is provably still in flight when Send fires
        // (the maintainer's race: reconnect slows the SFTP transfer).
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("look at this screenshot")

        // Tap Send WHILE the upload is still in flight.
        vm.requestSend(withEnter = true)
        runCurrent()

        // No request has gone out yet — the send waits for the upload, instead of
        // firing a text-only request and dropping the attachment.
        assertTrue(
            "Send must not fire a text-only request while the attachment upload " +
                "is still in flight (that is the on-device silent drop).",
            sent.isEmpty(),
        )

        // The upload now finishes (post-reconnect the SFTP transfer completes).
        uploadResult.complete(
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png")),
        )
        advanceUntilIdle()
        job.cancelAndJoin()

        // EXACTLY ONE send went out, and it CARRIES the attachment — text AND the
        // attachment path. Base behaviour dropped it (0 attachments, no path).
        assertEquals(1, sent.size)
        assertEquals(1, sent[0].attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/shot.png",
            sent[0].attachments.single().remotePath,
        )
        assertTrue(
            "The composed prompt must carry the attachment path.",
            sent[0].text.contains("shot.png"),
        )
        assertTrue(sent[0].text.startsWith("look at this screenshot"))
        // Issue #971: once the real dispatch fires (after the upload), the prompt
        // + tile are handed off to the queue row — the editor is cleared (single
        // representation). The attachment still travels in the request, and a
        // failed send restores the tile via [restoreFailedSend].
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)
    }

    @Test
    fun sendWhileUploadInFlightThatThenFailsKeepsAttachmentDurableForRetry() = runTest {
        // CLASS COVERAGE (G2): the SAME race, but the in-flight upload FAILS
        // (genuine drop). The attachment intent must NOT vanish silently — the
        // composer surfaces an error and keeps the typed draft so the user can
        // re-attach + retry, instead of a text-only send sailing through with the
        // attachment gone. On base the upload was cancelled and a text-only send
        // fired (RED: 1 send, 0 attachments, no error).
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("look at this")

        vm.requestSend(withEnter = true)
        runCurrent()
        assertTrue("No text-only send may fire while the upload is pending.", sent.isEmpty())

        // The upload fails (the reconnect tore the SFTP transfer down).
        uploadResult.complete(Result.failure(IllegalStateException("transport dropped")))
        advanceUntilIdle()
        job.cancelAndJoin()

        // NO send sailed through silently without the attachment. The draft is
        // kept and an error is surfaced so the user can re-attach and retry.
        assertTrue(
            "A failed attachment upload must NOT let a text-only send sail " +
                "through silently (the on-device 'attachment lost' symptom).",
            sent.isEmpty(),
        )
        assertEquals("look at this", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun sendWhileTwoAttachmentsUploadingCarriesBothNotZero() = runTest {
        // CLASS COVERAGE (G2): MULTIPLE attachments uploading when Send fires.
        // Base dropped all of them (text-only). The fix awaits and carries both.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 2) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("review both")

        vm.requestSend(withEnter = true)
        runCurrent()
        assertTrue(sent.isEmpty())

        uploadResult.complete(
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/a.png",
                    "~/.pocketshell/attachments/host-1/b.png",
                ),
            ),
        )
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        assertEquals(2, sent[0].attachments.size)
        assertTrue(sent[0].text.contains("a.png"))
        assertTrue(sent[0].text.contains("b.png"))
    }

    @Test
    fun successfulSendClearsDraftSoSheetDismissesEveryTime() = runTest {
        // Issue #695: "when I'm sending a message, first the message
        // disappears from the Composer and then sometimes the Composer stays
        // on the screen." The sheet dismisses on the SUCCESS path only
        // (`if (onSend(...)) onDismiss()`), and a SUCCESSFUL send must emit a
        // SendRequest and leave the draft empty every time — so a host that
        // returns success always dismisses, with no leftover draft that would
        // make the sheet re-render with stale content.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))

        // Repeat to catch any "sometimes stays open" intermittency in the
        // dispatch/clear ordering. A fresh collector per iteration mirrors the
        // sheet's real lifecycle: the `sendRequests` collector is torn down on
        // dismiss and re-created on the next open (#254), so each send is
        // consumed by exactly one collector — the same drain the success path
        // depends on to dismiss.
        repeat(3) { i ->
            vm.onDraftChange("message $i")
            val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
            val job: Job = launch { vm.sendRequests.collect { sent += it } }
            vm.requestSend(withEnter = true)
            advanceUntilIdle()
            job.cancelAndJoin()

            // Issue #971: each send dispatches exactly one request and HANDS the
            // prompt off to the queue row, so the editor is cleared in flight (the
            // message never appears twice). The success path (host confirmed) then
            // ends the in-flight state via [markSendDelivered], leaving a clean
            // slate so the NEXT iteration's send is allowed (the in-flight guard
            // would otherwise drop a back-to-back send).
            assertEquals(1, sent.size)
            assertEquals("message $i", sent[0].text)
            assertEquals("", vm.uiState.value.draft)
            assertTrue(vm.uiState.value.sendInFlight)
            vm.markSendDelivered()
            assertEquals("", vm.uiState.value.draft)
            assertFalse(vm.uiState.value.sendInFlight)
            assertNull(vm.uiState.value.error)
        }
    }

    @Test
    fun failedSendKeepsDraftWithTextForRetryNotDismiss() = runTest {
        // Issue #695 acceptance #3 / #390: a FAILED send must keep the
        // composer open WITH the text (so the user can retry after
        // reconnecting), while a successful send dismisses. The dismiss is
        // conditional on the host's `onSend` returning true; the failure path
        // routes through [restoreFailedSend], which re-populates the draft and
        // surfaces the "Not sent" banner — the state the sheet renders to keep
        // itself open.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("ship it")

        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        // Issue #971: the prompt is handed off to the queue row in flight, so the
        // editor is cleared (single representation) while `sendInFlight` is true.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)

        // Host reports failure → restore. The prompt returns to the (now-single)
        // composer, in-flight ends, and an actionable banner appears; the sheet
        // stays open on this state.
        vm.restoreFailedSend(sent[0])
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals("ship it", vm.uiState.value.draft)
        assertEquals(
            "Not sent. Reconnect, then send again or discard the draft.",
            vm.uiState.value.error,
        )
        assertEquals(PromptComposerViewModel.RecordingState.Idle, vm.uiState.value.recording)
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
        // FSM lands back at Idle. Issue #971: the dispatched send hands the
        // combined prompt off to the queue row, so the editor is CLEARED in
        // flight (single representation); `sendInFlight` stays true until
        // delivery is confirmed via [markSendDelivered].
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
        vm.markSendDelivered()
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
        // Issue #971: handed off to the queue row — editor cleared in flight.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun requestSendDuringRecordingWhisperFailureDropsSendAndSurfacesError() = runTest {
        // Acceptance #211: If Whisper fails, the queued Send is
        // cancelled and the transcript goes into the retry queue with
        // an error message.
        val mic = FakeMicCapture()
        val queue = FakePendingQueue()
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Transport("simulated drop"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingTranscriptionStore = queue,
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
        val queue = FakePendingQueue()
        val vm = newVm(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingTranscriptionStore = queue,
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
        //   3. User taps Discard → FSM=Idle, draft preserved.
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

    // -- Issue #453: elapsed timer formatting -----------------------------

    @Test
    fun recordingElapsedTimerIsDrivenByTheInjectedClock() = runTest {
        // A fixed, manually-advanced clock so the timer is deterministic.
        var now = 1_000_000L
        val vm = newVm(
            mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f, 0.5f)),
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )
        vm.onMicTap()
        runCurrent()
        // First sampler tick stamps elapsed = clock() - start = 0.
        assertEquals("00:00", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Advance the wall clock by 17s and let one more sampler poll run.
        now += 17_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:17", formatElapsed(vm.uiState.value.recordingElapsedMs))

        // Settle the recording job so runTest doesn't hang.
        vm.onMicTap()
        advanceUntilIdle()
    }

    // -- Issue #870 (reopen): the raw growing partial is preserved for the
    //    width-aware two-line display ----------------------------------------
    //
    // The width-INDEPENDENT VM-side char budget (liveTranscriptTail /
    // liveTranscriptDisplay) was removed (D22 hard-cut) because on a real device
    // the 90-char tail did not fit two lines at the panel width and the trailing
    // ellipsis re-clipped the NEWEST words — the exact reopen symptom. The visible
    // tail is now resolved width-aware at render time by the dedicated two-line
    // LiveTranscriptTwoLine composable; its load-bearing proof is the connected
    // test PromptComposerLiveTranscriptTwoLineTest (it needs a real TextMeasurer /
    // panel width, which a JVM unit test cannot provide). At the VM level we only
    // assert the FULL growing partial is preserved (head NOT dropped here), so the
    // render-time tail has the whole text to anchor against.

    @Test
    fun androidSpeechLivePartialKeepsFullGrowingTextForWidthAwareDisplay() = runTest {
        // Drive the real recognizer state: a long partial must land in
        // [UiState.liveTranscript] UNTRUNCATED (no VM char budget) so the
        // render-time two-line area can keep the newest words visible at the
        // actual panel width.
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            storage = newStorage(),
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()

        val longPartial =
            "summarize the conversation so far and then write a short status " +
                "update describing what we changed and what is still left to do"
        speech.listener!!.onPartial(longPartial)
        runCurrent()

        // The full growing text is preserved verbatim — newest words at the tail,
        // head NOT clipped at the VM (trimming is render-time + width-aware).
        assertEquals(longPartial, vm.uiState.value.liveTranscript)
        assertTrue(
            "raw live transcript must contain the latest words",
            vm.uiState.value.liveTranscript!!.endsWith("what is still left to do"),
        )

        // Settle.
        speech.listener!!.onFinal(longPartial)
        advanceUntilIdle()
        assertNull(vm.uiState.value.liveTranscript)
    }

    // -- Issue #508/#580: two explicit stop buttons (Insert / Send) -------

    @Test
    fun toFieldStopLandsTranscriptInDraftWithoutSending() = runTest {
        // The "Insert" button is the historic stop-and-transcribe path
        // ([onMicTap] while Recording): the transcript appends to the editable
        // draft and NOTHING is sent. The user can then attach a screenshot /
        // edit before tapping Send manually.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("deploy the app") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onMicTap() // start recording
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.onMicTap() // "Insert": stop -> transcribe -> draft
        advanceUntilIdle()

        assertEquals(0, sent.size)
        assertEquals("deploy the app", vm.uiState.value.draft)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun sendStopTranscribesAndSendsImmediately() = runTest {
        // The "Send" button routes through [requestSend(true)] while Recording:
        // it queues the send and stops the recorder; once Whisper returns, the
        // queued send fires with the combined transcript and the draft clears.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("deploy the app") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onMicTap() // start recording
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.requestSend(withEnter = true) // "Send": stop -> transcribe -> send
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("deploy the app", sent[0].text)
        assertEquals(true, sent[0].withEnter)
        // Issue #971: handed off to the queue row — editor cleared in flight.
        assertEquals("", vm.uiState.value.draft)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun sendStopCombinesExistingDraftWithTranscript() = runTest {
        // "Send" while a typed draft already exists sends the combined text
        // (existing draft + just-transcribed words), not just the transcript.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("the app") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)
        vm.onDraftChange("deploy")

        vm.onMicTap()
        runCurrent()
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("deploy the app", sent[0].text)
        // Issue #971: handed off to the queue row — editor cleared in flight.
        assertEquals("", vm.uiState.value.draft)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun sendWhileTranscribingFiresOnceWhisperReturns() = runTest {
        // The "Send" button is also offered in Transcribing (the audio is
        // already captured). Tapping it there arms the queued send so the
        // in-flight round-trip dispatches the transcript when it lands.
        //
        // Gate the Whisper response on an explicit latch so the FSM stays
        // parked on Transcribing while we tap Send; without it the suspend
        // function returns immediately and the FSM is already back on Idle.
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                release.await()
                return Result.success("send from transcribing")
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)

        vm.onMicTap() // start recording
        runCurrent()
        vm.onMicTap() // stop -> Transcribing (whisper coroutine parked on latch)
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        // Tap Send while still Transcribing — only the queued flag is armed,
        // nothing dispatched yet.
        vm.requestSend(withEnter = true)
        runCurrent()
        assertEquals(0, sent.size)

        // Whisper round-trip completes: the queued send fires.
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertEquals("send from transcribing", sent[0].text)
        // Issue #971: handed off to the queue row — editor cleared in flight.
        assertEquals("", vm.uiState.value.draft)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
    }

}
