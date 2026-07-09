package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAndroidSpeechViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    private class FakeVault(initial: CharArray? = null) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
    }

    private fun newStorage(): ApiKeyVault = FakeVault()

    private class FakeMicCapture : PromptComposerViewModel.MicCapture {
        var startCount = 0
        override fun start() {
            startCount++
        }
        override fun stop(): ByteArray = byteArrayOf(1)
        override fun currentAmplitude(): Float = 0f
    }

    private fun fakeWhisperClient(result: () -> Result<String>): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> = result()
    }

    private class FakeVoiceSettings(
        private val language: String? = null,
        private val provider: VoiceTranscriptionProvider = VoiceTranscriptionProvider.AndroidSpeech,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = language
        override fun transcriptionProvider(): VoiceTranscriptionProvider = provider
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
            FakeSpeechRecognitionProvider(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            speechRecognitionProvider = speechRecognitionProvider,
            savedStateHandle = savedStateHandle,
        )
        if (samplerDispatcher != null) {
            vm.samplerDispatcher = samplerDispatcher
            vm.outboundQueueDispatcher = samplerDispatcher
        }
        vm.clock = clock
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    @Test
    fun androidSpeechProviderStartsWithoutOpenAiKey() = runTest {
        val mic = FakeMicCapture()
        var whisperCalls = 0
        val speech = FakeSpeechRecognitionProvider()
        val voice = FakeVoiceSettings(language = "en")
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

        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun androidSpeechPartialUpdatesReplacePreviousHypothesis() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
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

    @Test
    fun androidSpeechRecordingTimerAdvancesFromStart() = runTest {
        var now = 5_000_000L
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
            storage = newStorage(),
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertEquals("00:00", formatElapsed(vm.uiState.value.recordingElapsedMs))

        now += 13_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:13", formatElapsed(vm.uiState.value.recordingElapsedMs))

        now += 7_000L
        advanceTimeBy(PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals("00:20", formatElapsed(vm.uiState.value.recordingElapsedMs))

        speech.listener!!.onFinal("hello world")
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun androidSpeechTickerStopsWhenRecordingEnds() = runTest {
        var now = 1_000L
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
            storage = newStorage(),
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

        speech.listener!!.onFinal("git status")
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)

        val frozen = vm.uiState.value.recordingElapsedMs
        now += 60_000L
        advanceTimeBy(10L * PromptComposerViewModel.SAMPLE_INTERVAL_MS)
        runCurrent()
        assertEquals(frozen, vm.uiState.value.recordingElapsedMs)
    }

    @Test(timeout = 30_000L)
    fun recordingTickerTerminatesOnVmClearSoSuiteCannotHang() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
            storage = newStorage(),
            speechRecognitionProvider = speech,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.clearForTest()
        advanceUntilIdle()
    }

    @Test
    fun androidSpeechEmptyFinalUsesLastPartialTranscript() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
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
        val vm = newVm(
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
        val vm = newVm(
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
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
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
        val vm = newVm(
            storage = newStorage(),
            whisper = null,
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
        val vm = newVm(
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
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
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
    fun androidSpeechLivePartialKeepsFullGrowingTextForWidthAwareDisplay() = runTest {
        val speech = FakeSpeechRecognitionProvider()
        val vm = newVm(
            storage = newStorage(),
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

        assertEquals(longPartial, vm.uiState.value.liveTranscript)
        assertTrue(
            "raw live transcript must contain the latest words",
            vm.uiState.value.liveTranscript!!.endsWith("what is still left to do"),
        )

        speech.listener!!.onFinal(longPartial)
        advanceUntilIdle()
        assertNull(vm.uiState.value.liveTranscript)
    }
}
