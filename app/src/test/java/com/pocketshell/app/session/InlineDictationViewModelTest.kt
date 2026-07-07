package com.pocketshell.app.session

import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.voice.InMemoryUndeliveredTranscriptStore
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.session.InlineDictationViewModel.DictationMode
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.InlineDictationViewModel.RecordingState
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private class FakePendingQueue : PromptComposerViewModel.PendingTranscriptionQueue {
        private val rows = linkedMapOf<String, PendingTranscriptionItem>()
        private val audios = mutableMapOf<String, ByteArray>()
        private val flow = MutableStateFlow<List<PendingTranscriptionItem>>(emptyList())
        var nextId = "pending-1"
        var enqueueCount = 0
        val failureIds = mutableListOf<Pair<String, String>>()
        val succeededIds = mutableListOf<String>()

        override val items = flow

        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): PendingTranscriptionItem {
            enqueueCount++
            val id = nextId
            val item = PendingTranscriptionItem(
                id = id,
                recordingTimestampMs = 1L,
                destinationContext = destinationContext,
                retryCount = 0,
                lastErrorMessage = initialError,
                audioByteSize = audio.size.toLong(),
            )
            rows[id] = item
            audios[id] = audio
            emit()
            return item
        }

        override suspend fun snapshot(): List<PendingTranscriptionItem> = rows.values.toList()
        override suspend fun loadAudio(id: String): ByteArray? = audios[id]

        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            rows.remove(id)
            audios.remove(id)
            emit()
        }

        override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? {
            failureIds += id to errorMessage
            val current = rows[id] ?: return null
            val updated = current.copy(
                retryCount = current.retryCount + 1,
                lastErrorMessage = errorMessage,
            )
            rows[id] = updated
            emit()
            return updated
        }

        override suspend fun discard(id: String) {
            rows.remove(id)
            audios.remove(id)
            emit()
        }

        override suspend fun saveAsAudioFile(id: String): String? = null
        override suspend fun reconcile() = Unit
        fun storedAudio(id: String): ByteArray? = audios[id]

        private fun emit() {
            flow.value = rows.values.toList().asReversed()
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
        var available: Boolean = true,
    ) : PromptComposerViewModel.SpeechRecognitionProvider {
        var startCount = 0
        var stopCount = 0
        var cancelCount = 0
        var language: String? = null
        var listener: PromptComposerViewModel.SpeechRecognitionListener? = null

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
        whisper: WhisperClient? = fakeWhisperClient { Result.success("git status") },
        storage: PromptComposerViewModel.ApiKeyVault = FakeVault().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
        speechRecognitionProvider: PromptComposerViewModel.SpeechRecognitionProvider =
            FakeSpeechRecognitionProvider(available = false),
        pendingQueue: PromptComposerViewModel.PendingTranscriptionQueue =
            com.pocketshell.app.composer.DisabledPendingTranscriptionQueue,
        undeliveredStore: com.pocketshell.app.voice.UndeliveredTranscriptStore =
            com.pocketshell.app.voice.InMemoryUndeliveredTranscriptStore(),
    ): InlineDictationViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = InlineDictationViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            speechRecognitionProvider = speechRecognitionProvider,
            pendingTranscriptionStore = pendingQueue,
            undeliveredTranscriptStore = undeliveredStore,
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
    fun diagnosticsRecordInlineRecordingSuccess() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val mic = FakeMicCapture(amplitudes = listOf(0.5f, 0.5f, 0.5f))
            val vm = newVm(
                mic = mic,
                whisper = fakeWhisperClient { Result.success("git status") },
                samplerDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.onMicTap()
            runCurrent()
            vm.onMicTap()
            advanceUntilIdle()

            assertTrue(diagnostics.events.any {
                it.name == "inline_recording_start_result" &&
                    it.fields["status"] == "success" &&
                    it.fields["mode"] == DictationMode.Prompt.name
            })
            assertTrue(diagnostics.events.any {
                it.name == "inline_recording_stop" &&
                    it.fields["status"] == "transcribing" &&
                    (it.fields["audioBytes"] as Int) > 0
            })
            assertTrue(diagnostics.events.any {
                it.name == "inline_transcription_result" &&
                    it.fields["status"] == "success" &&
                    it.fields["transcriptBytes"] == "git status".toByteArray(Charsets.UTF_8).size
            })
            assertFalse(diagnostics.events.any { event ->
                event.fields.values.any { it == "git status" }
            })
        } finally {
            diagnostics.close()
        }
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
                return SpeechAudioGuard.speechWavForTesting()
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

    // -- Issue #452: silence / hallucination suppression --------------------

    @Test
    fun silentRecordingIsNotTranscribedAndShowsNoSpeechHint() = runTest {
        var transcribeCalls = 0
        val silentMic = object : PromptComposerViewModel.MicCapture {
            private var running = false
            override fun start() { running = true }
            override fun stop(): ByteArray {
                running = false
                return SpeechAudioGuard.silentWavForTesting()
            }
            override fun currentAmplitude(): Float = if (running) 0.5f else 0f
        }
        val vm = newVm(
            mic = silentMic,
            whisper = fakeWhisperClient {
                transcribeCalls++
                Result.success("시청해주셔서 감사합니다!")
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Whisper never ran; nothing was written to the terminal.
        assertEquals(0, transcribeCalls)
        assertTrue("no transcription should be emitted, got $received", received.isEmpty())
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(
            InlineDictationViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
        collector.cancel()
    }

    @Test
    fun recoverableNoSpeechRejectionIsSavedForRetry() = runTest {
        val marginalAudio = SpeechAudioGuard.speechWavForTesting(durationMs = 1_200, amplitude = 0.004f)
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(marginalAudio))
        assertTrue(SpeechAudioGuard.isRecoverableNoSpeechRejection(marginalAudio))

        var transcribeCalls = 0
        val queue = FakePendingQueue()
        val vm = newVm(
            mic = FakeMicCapture(audio = marginalAudio),
            whisper = fakeWhisperClient {
                transcribeCalls++
                Result.success("should not run")
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingQueue = queue,
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(0, transcribeCalls)
        assertEquals(1, queue.enqueueCount)
        assertTrue(queue.storedAudio("pending-1")!!.contentEquals(marginalAudio))
        val row = queue.snapshot().single()
        assertEquals(PendingTranscriptionEntity.DESTINATION_INLINE_DICTATION, row.destinationContext)
        assertEquals(InlineDictationViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE, row.lastErrorMessage)
        assertEquals(InlineDictationViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE, vm.uiState.value.error)
    }

    @Test
    fun hallucinationPhraseFromWhisperIsSuppressed() = runTest {
        // Energy guard passes (real-speech WAV) but Whisper still returns a
        // stock phrase — the backstop must drop it before terminal write.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("시청해주셔서 감사합니다!") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertTrue("hallucinated phrase must not be emitted, got $received", received.isEmpty())
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(
            InlineDictationViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
        collector.cancel()
    }

    @Test
    fun hallucinationPhraseFromWhisperKeepsInlineAudioQueued() = runTest {
        val audio = SpeechAudioGuard.speechWavForTesting()
        val queue = FakePendingQueue()
        val vm = newVm(
            mic = FakeMicCapture(audio = audio),
            whisper = fakeWhisperClient { Result.success("시청해주셔서 감사합니다!") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingQueue = queue,
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertEquals(1, queue.enqueueCount)
        assertTrue(queue.storedAudio("pending-1")!!.contentEquals(audio))
        assertEquals(
            listOf("pending-1" to InlineDictationViewModel.NO_SPEECH_DETECTED_MESSAGE),
            queue.failureIds,
        )
        assertTrue(queue.succeededIds.isEmpty())
        assertEquals(InlineDictationViewModel.NO_SPEECH_DETECTED_MESSAGE, vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun emptyWhisperResponseKeepsInlineAudioQueued() = runTest {
        val audio = SpeechAudioGuard.speechWavForTesting()
        val queue = FakePendingQueue()
        val vm = newVm(
            mic = FakeMicCapture(audio = audio),
            whisper = fakeWhisperClient { Result.success(" \n ") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingQueue = queue,
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertEquals(1, queue.enqueueCount)
        assertTrue(queue.storedAudio("pending-1")!!.contentEquals(audio))
        assertEquals(
            listOf("pending-1" to InlineDictationViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE),
            queue.failureIds,
        )
        assertTrue(queue.succeededIds.isEmpty())
        assertEquals(InlineDictationViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE, vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun successfulInlineTranscriptionDeletesPersistedAudio() = runTest {
        val audio = SpeechAudioGuard.speechWavForTesting()
        val queue = FakePendingQueue()
        val vm = newVm(
            mic = FakeMicCapture(audio = audio),
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            pendingQueue = queue,
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(listOf("git status"), received)
        assertEquals(1, queue.enqueueCount)
        assertEquals(listOf("pending-1"), queue.succeededIds)
        assertNull(queue.storedAudio("pending-1"))
        assertTrue(queue.failureIds.isEmpty())
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
    fun androidSpeechProviderStartsWithoutOpenAiKey() = runTest {
        val mic = FakeMicCapture()
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(
            provider = VoiceTranscriptionProvider.AndroidSpeech,
            language = "de",
        )
        var whisperCalls = 0
        val vm = newVm(
            mic = mic,
            whisper = fakeWhisperClient {
                whisperCalls++
                Result.success("should not run")
            },
            storage = FakeVault(),
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(vm.needsOpenAiKeyForMicTap())
        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals(1, speech.startCount)
        assertEquals("de", speech.language)
        assertEquals(0, mic.startCount)
        assertEquals(0, whisperCalls)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun androidSpeechFinalTranscriptEmitsToTerminalStream() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val received = mutableListOf<String>()
        val collector = launch { received += vm.transcriptions.first() }

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        runCurrent()

        assertEquals(1, speech.stopCount)
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        speech.listener!!.onFinal(" git status ")
        advanceUntilIdle()

        assertEquals(listOf("git status"), received)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNull(vm.uiState.value.error)
        assertTrue(speech.cancelCount > 0)
        collector.join()
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
        val received = mutableListOf<String>()
        val collector = launch { received += vm.transcriptions.first() }

        vm.onMicTap()
        runCurrent()
        speech.listener!!.onPartial(" git status ")
        speech.listener!!.onFinal("   ")
        advanceUntilIdle()

        assertEquals(listOf("git status"), received)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNull(vm.uiState.value.error)
        collector.join()
    }

    @Test
    fun androidSpeechNoMatchAfterPartialEmitsLastPartialTranscript() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val received = mutableListOf<String>()
        val collector = launch { received += vm.transcriptions.first() }

        vm.onMicTap()
        runCurrent()
        speech.listener!!.onPartial("pwd")
        speech.listener!!.onError(InlineDictationViewModel.NO_SPEECH_DETECTED_MESSAGE)
        advanceUntilIdle()

        assertEquals(listOf("pwd"), received)
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNull(vm.uiState.value.error)
        collector.join()
    }

    @Test
    fun androidSpeechUnavailableShowsErrorWithoutStartingMic() = runTest {
        val speech = FakeSpeechRecognitionProvider(available = false)
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val mic = FakeMicCapture()
        val vm = newVm(
            mic = mic,
            storage = FakeVault(),
            whisper = null,
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(0, speech.startCount)
        assertEquals(0, mic.startCount)
        assertEquals(PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE, vm.uiState.value.error)
    }

    @Test
    fun androidSpeechEmptyFinalShowsNoSpeechAndEmitsNothing() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(provider = VoiceTranscriptionProvider.AndroidSpeech)
        val vm = newVm(
            voiceSettings = voice,
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.take(1).toList(received) }

        vm.onMicTap()
        runCurrent()
        speech.listener!!.onFinal("   ")
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals(PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE, vm.uiState.value.error)
        collector.cancel()
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

    // -- onCleared teardown bound (#957 / #928 D1) --------------------------

    /**
     * A [PromptComposerViewModel.MicCapture] whose `stop()` blocks on a latch,
     * standing in for the unbounded `captureThread.join()` inside the real
     * `PcmCapturePump.stop` when the platform capture thread is wedged. The
     * blocked worker honours [Thread.interrupt] so the bounded-stop path can
     * unwind it the same way it would unwind the real blocked join.
     */
    private class WedgedMicCapture(
        private val releaseStop: CountDownLatch,
    ) : PromptComposerViewModel.MicCapture {
        val stopEntered = CountDownLatch(1)
        val stopInterrupted = AtomicBoolean(false)
        private var running = false

        override fun start() {
            running = true
        }

        override fun stop(): ByteArray {
            stopEntered.countDown()
            try {
                // Block until released OR interrupted — mirrors a wedged
                // captureThread.join() that only unblocks on interrupt.
                if (!releaseStop.await(30, TimeUnit.SECONDS)) {
                    // Latch was never released and we were not interrupted —
                    // treat as a hung stop that exhausted our patience.
                    stopInterrupted.set(true)
                }
            } catch (e: InterruptedException) {
                stopInterrupted.set(true)
                Thread.currentThread().interrupt()
            }
            running = false
            return ByteArray(0)
        }

        override fun currentAmplitude(): Float = if (running) 0.5f else 0f
    }

    /**
     * Reproduce-first (#957 / #928 D1): with a wedged `stop()` that never
     * returns, `onCleared` must STILL return within its budget — it must not
     * block the Main thread on the unbounded capture-thread join.
     *
     * Red on base: the old `runCatching { audioRecorder.stop() }` blocks until
     * the latch is released (here: never within the assertion window), so
     * `clearForTest()` does not return and the elapsed time blows past the
     * budget. Green with the fix: the bounded worker is abandoned after the
     * budget and `clearForTest()` returns promptly.
     */
    @Test
    fun onClearedReturnsWithinBudgetWhenStopIsWedged() = runTest {
        val releaseStop = CountDownLatch(1) // deliberately never released
        val mic = WedgedMicCapture(releaseStop)
        val vm = newVm(mic = mic, samplerDispatcher = StandardTestDispatcher(testScheduler))
        // Shrink the budget so the wedged path resolves fast under test.
        vm.oncClearedStopBudgetMs = 200L

        vm.onMicTap() // -> Recording (Whisper provider, activeProvider set)
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        val budget = vm.oncClearedStopBudgetMs
        val startNs = System.nanoTime()
        vm.clearForTest() // drives onCleared() teardown
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // The stop worker was actually entered (we exercised the real path),
        // and clearForTest returned within a small multiple of the budget
        // rather than blocking on the wedged stop.
        assertTrue(
            "stop() worker should have been started",
            mic.stopEntered.await(2, TimeUnit.SECONDS),
        )
        assertTrue(
            "onCleared blocked $elapsedMs ms; expected to return within the " +
                "$budget ms bound (plus scheduling slack)",
            elapsedMs < budget + 1_000,
        )
    }

    /**
     * Normal path: when `stop()` completes quickly, teardown still invokes it
     * exactly once and returns without engaging the timeout/abandon path.
     */
    @Test
    fun onClearedStillStopsRecorderOnNormalPath() = runTest {
        val mic = FakeMicCapture()
        val vm = newVm(mic = mic, samplerDispatcher = StandardTestDispatcher(testScheduler))

        vm.onMicTap() // -> Recording
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        val stopsBefore = mic.stopCount

        val startNs = System.nanoTime()
        vm.clearForTest()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // stop() was driven exactly once during teardown and it completed
        // well within the budget (no abandon path).
        assertEquals(stopsBefore + 1, mic.stopCount)
        assertTrue(
            "normal-path teardown took $elapsedMs ms; should be near-instant",
            elapsedMs < vm.oncClearedStopBudgetMs,
        )
    }

    /**
     * When the bounded stop times out, a diagnostic is recorded so a wedged
     * recorder stays visible, and teardown does not throw.
     */
    @Test
    fun onClearedRecordsTimeoutDiagnosticWhenStopIsWedged() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val releaseStop = CountDownLatch(1)
            val mic = WedgedMicCapture(releaseStop)
            val vm = newVm(mic = mic, samplerDispatcher = StandardTestDispatcher(testScheduler))
            vm.oncClearedStopBudgetMs = 150L

            vm.onMicTap()
            runCurrent()
            assertEquals(RecordingState.Recording, vm.uiState.value.recording)

            vm.clearForTest()

            assertTrue(
                "expected an inline_recording_cancel_stop_timeout diagnostic",
                diagnostics.events.any { it.name == "inline_recording_cancel_stop_timeout" },
            )
            // The cancel diagnostic is still recorded — teardown completed.
            assertTrue(
                diagnostics.events.any { it.name == "inline_recording_cancel" },
            )
        } finally {
            // Nothing to release; the abandoned worker honours interrupt.
        }
    }

    // -- Issue #1226: durable delivery across a torn-down / re-keying collector

    @Test
    fun transcriptEmittedWhileNoCollectorSubscribedSurvivesTheReKey() = runTest {
        // Reproduces the silent data-loss class (issue #1226). The user
        // dictates a command and taps stop; during the 1-3s Whisper round-trip
        // the session briefly drops/reconnects, so the screen's transcript
        // collector is torn down (focusedPaneId flips to null / re-keys) and no
        // collector is subscribed at emit time. On the old `replay = 0`
        // MutableSharedFlow the emit lands in the collector gap and is silently
        // dropped — the FSM is already back at Idle with error == null, so the
        // user believes they sent a command that never arrived.
        //
        // RED on base: with the SharedFlow, a late subscriber never sees the
        // dropped value -> `received` stays empty -> the assert fails.
        // GREEN with the fix: the Channel buffers the transcript and hands it
        // to the collector when it re-subscribes on re-key.
        val audio = SpeechAudioGuard.speechWavForTesting()
        val vm = newVm(
            mic = FakeMicCapture(audio = audio),
            whisper = fakeWhisperClient { Result.success("deploy prod") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        // No collector subscribed — the screen's collector is torn down during
        // the reconnect gap while the Whisper round-trip resolves.
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Exactly the "user thinks it sent" state: Idle, no error surfaced.
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertNull(vm.uiState.value.error)

        // The pane re-keys to a live id and the screen re-subscribes.
        val received = mutableListOf<String>()
        val collector = launch { received += vm.transcriptions.first() }
        advanceUntilIdle()

        assertEquals(
            "transcript emitted during the collector gap must survive the re-key, not be dropped",
            listOf("deploy prod"),
            received,
        )
        collector.cancel()
    }

    @Test
    fun transcriptDeliveredExactlyOnceWhileCollectorStaysAlive() = runTest {
        // Subscriber-alive path — the pane survives, the transcript is
        // delivered once and never duplicated (AC: no duplicate delivery when
        // the pane survives).
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val received = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { received += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(listOf("git status"), received)

        // Let the collector keep running: a durable channel must not
        // re-deliver an already-consumed value.
        advanceUntilIdle()
        assertEquals(listOf("git status"), received)
        collector.cancel()
    }

    @Test
    fun reKeyAfterSuccessfulDeliveryDoesNotRedeliverTranscript() = runTest {
        // Guards AC3 against the Channel change: a collector that re-subscribes
        // AFTER a value was already delivered must NOT see it again (a
        // `replay = 1` fix would have re-fired the command into the terminal).
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("git status") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val firstReceived = mutableListOf<String>()
        val first = launch { vm.transcriptions.collect { firstReceived += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(listOf("git status"), firstReceived)
        first.cancel() // pane re-keys AFTER successful delivery

        val secondReceived = mutableListOf<String>()
        val second = launch { vm.transcriptions.collect { secondReceived += it } }
        advanceUntilIdle()

        assertTrue(
            "re-key after delivery must not replay the transcript, got $secondReceived",
            secondReceived.isEmpty(),
        )
        second.cancel()
    }

    @Test
    fun screenCollectorPatternDeliversTranscriptAfterPaneReKeyDuringReconnect() = runTest {
        // Faithful model of TmuxSessionScreen's inline-dictation collector and
        // the #1226 fix: while focusedPaneId is null (session dropped mid
        // Whisper round-trip) the LaunchedEffect must NOT drain the delivery
        // channel; when the pane re-keys to a live id the effect re-runs and
        // delivers the buffered transcript into the pane the user is now
        // looking at.
        //
        // RED on base: the old code drained the SharedFlow even with a null
        // pane and `return@collect`-discarded the value (and the SharedFlow
        // dropped it anyway with no subscriber) -> `delivered` stays empty.
        // GREEN with the fix: pane-null skips the collect entirely, the Channel
        // buffers, and the re-keyed collector delivers.
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("make deploy") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val delivered = mutableListOf<Pair<String, String>>() // paneId to text
        var focusedPaneId: String? = null
        var collectorJob: Job? = null

        // Mirrors LaunchedEffect(inlineDictationViewModel, dictationMode,
        // focusedPaneId): re-bind the collector on every focusedPaneId change,
        // and skip collecting entirely when there is no pane (the #1226 guard).
        fun rebindCollector() {
            collectorJob?.cancel()
            val paneId = focusedPaneId ?: return
            collectorJob = launch {
                vm.transcriptions.collect { text -> delivered += paneId to text }
            }
        }

        rebindCollector() // pane null: effect returns early, nothing draining
        runCurrent()

        // Dictation completes while focusedPaneId is null (the reconnect gap).
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertTrue(
            "no delivery may happen while there is no pane to receive it, got $delivered",
            delivered.isEmpty(),
        )

        // Reconnect completes; the pane re-keys to a live id -> effect re-runs.
        focusedPaneId = "%7"
        rebindCollector()
        advanceUntilIdle()

        assertEquals(listOf("%7" to "make deploy"), delivered)
        collectorJob?.cancel()
    }

    // -- Issue #1272: permanent-dead-pane persistence + bounded channel --------

    @Test
    fun permanentDeadPaneTranscriptIsPersistedAndSurfacedOnClear() = runTest {
        // The residual #1226 tail: a transcript resolves while there is NO pane,
        // and the session never returns (the user navigated away). No collector
        // ever subscribes, so the buffered transcript would be delivered to no
        // one and silently lost. On ViewModel teardown (permanent pane death)
        // the delivery channel is cancelled, which persists the still-buffered
        // transcript to the durable store and surfaces it as a retryable item.
        //
        // RED on base: `onCleared` did not cancel the channel and there was no
        // store, so the buffered transcript vanished with the ViewModel ->
        // `store.snapshot()` empty, `undeliveredTranscripts.value` empty.
        // GREEN with the fix: the transcript is persisted + surfaced.
        val store = InMemoryUndeliveredTranscriptStore()
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("git push origin main") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            undeliveredStore = store,
        )

        // Dictate to completion with NO collector ever subscribing (pane gone).
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Nothing persisted yet — the transcript is buffered, still deliverable
        // if the pane were to return within this ViewModel's lifetime.
        assertTrue(
            "transcript must stay buffered (not persisted) while the ViewModel is alive",
            store.snapshot().isEmpty(),
        )

        // The session is permanently gone: the ViewModel is cleared.
        vm.clearForTest()
        advanceUntilIdle()

        val persisted = store.snapshot()
        assertEquals(
            "the undeliverable transcript must be persisted on permanent pane death",
            1,
            persisted.size,
        )
        assertEquals("git push origin main", persisted.first().text)
        assertEquals(
            "the persisted transcript is surfaced via undeliveredTranscripts",
            listOf("git push origin main"),
            vm.undeliveredTranscripts.value.map { it.text },
        )
    }

    @Test
    fun deliveredTranscriptIsNotPersistedOnClear() = runTest {
        // Guard against a false positive: a transcript that WAS delivered to a
        // live collector must not be re-persisted on teardown (receiveAsFlow
        // removed it from the buffer, so channel-cancel has nothing to drain).
        val store = InMemoryUndeliveredTranscriptStore()
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("ls -la") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            undeliveredStore = store,
        )

        val delivered = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { delivered += it } }
        runCurrent()

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(listOf("ls -la"), delivered)

        collector.cancel()
        vm.clearForTest()
        advanceUntilIdle()

        assertTrue(
            "a delivered transcript must NOT be persisted as undelivered, got ${store.snapshot()}",
            store.snapshot().isEmpty(),
        )
    }

    @Test
    fun deliveryChannelIsBoundedAndOverflowPersistsInsteadOfGrowingUnbounded() = runTest {
        // Second #1272 gap: the old `Channel.UNLIMITED` grew without bound under
        // rapid repeated dictation into a permanently-dead pane. The channel is
        // now bounded to DELIVERY_CHANNEL_CAPACITY with DROP_OLDEST, and a
        // dropped-oldest element is routed to the store rather than lost.
        //
        // RED on base: an UNLIMITED channel buffers every send, so before any
        // teardown the store is EMPTY no matter how many transcripts pile up.
        // GREEN with the fix: sending capacity + N transcripts with no collector
        // overflows the oldest N straight into the store.
        val store = InMemoryUndeliveredTranscriptStore()
        val overflow = 3
        val total = InlineDictationViewModel.DELIVERY_CHANNEL_CAPACITY + overflow
        var index = 0
        val vm = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("cmd-${index++}") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            undeliveredStore = store,
        )

        // Fire `total` full dictation cycles with NO collector — the channel
        // fills to capacity and then drops-oldest to the store.
        repeat(total) {
            vm.onMicTap()
            runCurrent()
            vm.onMicTap()
            advanceUntilIdle()
        }

        val persisted = store.snapshot()
        assertEquals(
            "exactly the overflow (oldest) transcripts should be persisted; the " +
                "channel must be bounded, not unbounded",
            overflow,
            persisted.size,
        )
        // DROP_OLDEST persists the FIRST-sent transcripts (cmd-0..cmd-2).
        assertEquals(
            setOf("cmd-0", "cmd-1", "cmd-2"),
            persisted.map { it.text }.toSet(),
        )
    }

    @Test
    fun retryUndeliveredReDeliversToLivePaneAndClearsQueue() = runTest {
        // Recovery loop across ViewModel instances (the real journey): session A
        // dies with an undelivered transcript persisted; a new session screen B
        // reads the durable store, the user taps Retry, and the transcript is
        // re-delivered into B's live pane and removed from the queue.
        val store = InMemoryUndeliveredTranscriptStore()

        // Session A: dictate with no pane, then permanent death -> persisted.
        val vmA = newVm(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("deploy now") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            undeliveredStore = store,
        )
        vmA.onMicTap()
        runCurrent()
        vmA.onMicTap()
        advanceUntilIdle()
        vmA.clearForTest()
        advanceUntilIdle()
        assertEquals(1, store.snapshot().size)
        val id = store.snapshot().first().id

        // Session B: shares the same durable store, has a live collector.
        val vmB = newVm(
            mic = FakeMicCapture(),
            undeliveredStore = store,
        )
        val delivered = mutableListOf<String>()
        val collector = launch { vmB.transcriptions.collect { delivered += it } }
        runCurrent()

        assertEquals(
            "B surfaces the persisted item before retry",
            listOf("deploy now"),
            vmB.undeliveredTranscripts.value.map { it.text },
        )

        vmB.retryUndelivered(id)
        advanceUntilIdle()

        assertEquals(
            "retry re-delivers the transcript into B's live pane",
            listOf("deploy now"),
            delivered,
        )
        assertTrue(
            "the retried item is removed from the queue",
            store.snapshot().isEmpty(),
        )
        collector.cancel()
    }

    @Test
    fun dismissUndeliveredDiscardsWithoutRedelivery() = runTest {
        val store = InMemoryUndeliveredTranscriptStore()
        store.persist("obsolete command")
        val vm = newVm(mic = FakeMicCapture(), undeliveredStore = store)

        val delivered = mutableListOf<String>()
        val collector = launch { vm.transcriptions.collect { delivered += it } }
        runCurrent()

        val id = store.snapshot().first().id
        vm.dismissUndelivered(id)
        advanceUntilIdle()

        assertTrue("dismiss removes the item", store.snapshot().isEmpty())
        assertTrue("dismiss must not re-deliver", delivered.isEmpty())
        collector.cancel()
    }
}
