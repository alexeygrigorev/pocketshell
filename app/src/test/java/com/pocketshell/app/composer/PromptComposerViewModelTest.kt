package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.awaitCancellation
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
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val factory = WhisperClientFactory { whisper }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            speechRecognitionProvider = speechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            savedStateHandle = savedStateHandle,
        )
        if (samplerDispatcher != null) vm.samplerDispatcher = samplerDispatcher
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
    fun initialStateIsIdleWithEmptyDraft() {
        val vm = newVm()
        val state = vm.uiState.value
        assertEquals(RecordingState.Idle, state.recording)
        assertEquals("", state.draft)
        assertNull(state.error)
    }

    @Test
    fun diagnosticsRecordAttachmentSeedAndRemove() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()

            vm.seedAttachment("/home/alexey/private/report.txt")
            vm.removeAttachment("/home/alexey/private/report.txt")

            assertEquals(
                listOf("attachment_seeded_from_share", "attachment_remove"),
                diagnostics.events.map { it.name },
            )
            assertEquals(1, diagnostics.events[0].fields["attachmentCount"])
            assertEquals(1, diagnostics.events[1].fields["beforeCount"])
            assertEquals(0, diagnostics.events[1].fields["afterCount"])
            assertFalse(diagnostics.events.any { event ->
                event.fields.values.any { it == "/home/alexey/private/report.txt" }
            })
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun seedDraftPromptReplacesEmptyDraft() {
        val vm = newVm()
        vm.seedDraftPrompt("Apply the PocketShell review at ~/inbox/pocketshell/reviews/README.md-20260614-025147.yaml")
        assertEquals(
            "Apply the PocketShell review at ~/inbox/pocketshell/reviews/README.md-20260614-025147.yaml",
            vm.uiState.value.draft,
        )
    }

    @Test
    fun seedDraftPromptAppendsToExistingDraftOnNewLine() {
        val vm = newVm()
        vm.onDraftChange("look at this")
        vm.seedDraftPrompt("Apply the PocketShell review at /tmp/r.yaml")
        assertEquals(
            "look at this\nApply the PocketShell review at /tmp/r.yaml",
            vm.uiState.value.draft,
        )
    }

    @Test
    fun seedDraftPromptIgnoresBlank() {
        val vm = newVm()
        vm.onDraftChange("keep me")
        vm.seedDraftPrompt("   ")
        assertEquals("keep me", vm.uiState.value.draft)
    }

    @Test
    fun seedDraftPromptRecordsDiagnostic() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.seedDraftPrompt("Apply the PocketShell review at /tmp/r.yaml")
            assertEquals(
                listOf("review_prompt_seeded_into_composer"),
                diagnostics.events.map { it.name },
            )
        } finally {
            diagnostics.close()
        }
    }

    // -- #770: tap a rendered engine command -> composer pre-filled ----------

    @Test
    fun prefillEngineCommandSetsTheDraftToTheCommand() {
        val vm = newVm()
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandReplacesAnExistingLeadingSlashToken() {
        val vm = newVm()
        vm.onDraftChange("/comp")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandPreservesTrailingTextAfterTheSlashToken() {
        val vm = newVm()
        vm.onDraftChange("/old keep this part")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear keep this part", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandPrependsWhenDraftHasNoLeadingSlash() {
        val vm = newVm()
        vm.onDraftChange("already typed")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clearalready typed", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandIgnoresBlank() {
        val vm = newVm()
        vm.onDraftChange("keep me")
        vm.prefillEngineCommand("   ")
        assertEquals("keep me", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandRecordsDiagnostic() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.prefillEngineCommand("/clear")
            assertEquals(
                listOf("engine_command_tapped_into_composer"),
                diagnostics.events.map { it.name },
            )
        } finally {
            diagnostics.close()
        }
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

    @Test
    fun attachFilesStagesChipsAndKeepsDraftClean() = runTest {
        // Issue #544: staging adds structured chips (file name only) to the
        // attachments list — the draft text is NOT mutated while composing.
        val vm = newVm()
        vm.onDraftChange("Review these")

        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()

        // The draft stays exactly what the user typed — no "Attached files:"
        // bullets folded in.
        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    displayName = "20260601-120000-01-report.txt",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                    displayName = "20260601-120000-02-data.csv",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertNull(vm.uiState.value.error)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
    }

    @Test
    fun attachFilesDeDuplicatesRepeatedRemotePaths() = runTest {
        // Issue #544: re-attaching the same remote path must not double a chip.
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"

        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()
        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun removeAttachmentDropsOnlyThatChipAndKeepsDraft() = runTest {
        // Issue #544: the chip's × removes just that attachment; the draft
        // text the user typed is untouched, and removing all chips leaves a
        // clean prompt.
        val vm = newVm()
        vm.onDraftChange("Look at these")
        val report = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"
        val data = "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv"

        vm.attachFiles(count = 2) { Result.success(listOf(report, data)) }
        advanceUntilIdle()

        vm.removeAttachment(report)

        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = data,
                    displayName = "20260601-120000-02-data.csv",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertEquals("Look at these", vm.uiState.value.draft)

        // Removing the last chip leaves the prompt exactly as typed.
        vm.removeAttachment(data)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertEquals("Look at these", vm.uiState.value.draft)
    }

    @Test
    fun seedAttachmentPreloadsChipAndKeepsDraftClean() = runTest {
        // Issue #560: the share-into-session flow stages the shared file via
        // the #544 mechanic, then seeds the resulting remote path here so the
        // composer opens with the chip already present and the draft clean.
        val vm = newVm()
        vm.onDraftChange("")
        val path = "~/.pocketshell/attachments/host-7-scratch/20260606-120000-01-shot.png"

        vm.seedAttachment(path)

        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = path,
                    displayName = "20260606-120000-01-shot.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun stagedAttachmentsAndDraftPersistAcrossComposerCloseAndSessionSwitch() = runTest {
        // Issue #673: attachments are no longer shown in the session/terminal
        // bottom area — they live only inside the Prompt Composer sheet. The
        // maintainer's desired workflow ("insert prompt → close composer →
        // switch session → send elsewhere") relies on the staged-attachment
        // STATE surviving the composer being dismissed and the session being
        // switched. The composer ViewModel is retained across both, so there is
        // no clear-on-close path: closing the sheet and switching sessions
        // must leave the draft + attachments intact so re-opening shows them.
        val vm = newVm()
        val first = "~/.pocketshell/attachments/host-1/20260610-120000-01-shot.png"
        val second = "~/.pocketshell/attachments/host-1/20260610-120000-02-data.csv"
        vm.onDraftChange("deploy the staging build")
        vm.seedAttachment(first)
        vm.seedAttachment(second)

        // Snapshot the staged state as it is when the user closes the sheet.
        val afterCompose = vm.uiState.value

        // Dismissing the composer sheet and switching sessions does NOT touch
        // the ViewModel state — no clear-on-close, no clear-on-switch hook. The
        // only thing that clears attachments is an explicit send. Re-reading the
        // state (as a re-opened sheet would) shows the same draft + chips.
        val afterReopen = vm.uiState.value
        assertEquals("deploy the staging build", afterReopen.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = first,
                    displayName = "20260610-120000-01-shot.png",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = second,
                    displayName = "20260610-120000-02-data.csv",
                ),
            ),
            afterReopen.attachments,
        )
        assertEquals(afterCompose.draft, afterReopen.draft)
        assertEquals(afterCompose.attachments, afterReopen.attachments)
    }

    @Test
    fun seedAttachmentDeDuplicatesAndIgnoresBlank() = runTest {
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-7-scratch/shot.png"

        vm.seedAttachment(path)
        vm.seedAttachment(path)
        vm.seedAttachment("   ")

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun removeAttachmentForUnknownPathIsNoOp() = runTest {
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"
        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()

        vm.removeAttachment("~/.pocketshell/attachments/host-1/does-not-exist.txt")

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun attachFilesFailureKeepsDraftAndShowsError() = runTest {
        val vm = newVm()
        vm.onDraftChange("Do not lose this draft")

        vm.attachFiles(count = 1) {
            Result.failure(IllegalStateException("Permission denied"))
        }
        advanceUntilIdle()

        assertEquals("Do not lose this draft", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attachment upload failed"))
        assertTrue(error.contains("Permission denied"))
        assertTrue(error.contains("draft was kept"))
    }

    @Test
    fun attachFilesPartialFailureKeepsSurvivorsAndShowsError() = runTest {
        // Issue #570: when one of several images fails to upload, the ones
        // that DID upload must still attach as tiles and the composer must
        // re-enable (Idle) with a per-batch error — never discard everything
        // or wedge on the failed file.
        val vm = newVm()
        vm.onDraftChange("Review these shots")
        val first = "~/.pocketshell/attachments/host-1/20260606-120000-01-first.png"
        val third = "~/.pocketshell/attachments/host-1/20260606-120000-03-third.png"

        vm.attachFiles(count = 3) {
            Result.failure(
                PartialAttachmentUploadException(
                    uploadedPaths = listOf(first, third),
                    failedCount = 1,
                    message = "Attached 2 of 3 files; 1 failed (upload timed out).",
                ),
            )
        }
        advanceUntilIdle()

        // Survivors are attached; the failed file is absent.
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = first,
                    displayName = "20260606-120000-01-first.png",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = third,
                    displayName = "20260606-120000-03-third.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        // Draft untouched, composer re-enabled, per-batch error visible.
        assertEquals("Review these shots", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attached 2 of 3"))
        assertTrue(error.contains("1 failed"))
    }

    @Test
    fun attachFilesInFlightDoesNotBlockDraftEdits() = runTest {
        // Issue #570: staging/uploading attachments must not globally block
        // text composition. The upload can stay busy, but the draft path
        // remains editable and mirrored into saved state.
        val vm = newVm()
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()

        vm.attachFiles(count = 3) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()

        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Uploading(3),
            vm.uiState.value.attachmentUpload,
        )

        vm.onDraftChange("typing while images upload")

        assertEquals("typing while images upload", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Uploading(3),
            vm.uiState.value.attachmentUpload,
        )

        uploadResult.complete(Result.success(emptyList()))
        advanceUntilIdle()
    }

    @Test
    fun attachFilesTimeoutClearsBusyStateAndKeepsDraft() = runTest {
        val vm = newVm()
        val uploadStarted = CompletableDeferred<Unit>()
        vm.onDraftChange("keep this")

        vm.attachFiles(count = 3) {
            uploadStarted.complete(Unit)
            awaitCancellation()
        }
        runCurrent()
        uploadStarted.await()

        advanceTimeBy(PromptComposerViewModel.ATTACHMENT_UPLOAD_TIMEOUT_MS)
        runCurrent()

        assertEquals("keep this", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        assertTrue(vm.uiState.value.attachments.isEmpty())
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attachment upload failed"))
        assertTrue(error.contains("timed out"))
        assertTrue(error.contains("draft was kept"))
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
        // Issue #745: the queued voice send dispatches with the draft RETAINED
        // and in-flight; it clears only when the host confirms delivery.
        assertEquals("Please summarize this", vm.uiState.value.draft)
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
        // Issue #745: draft retained in-flight until delivery is confirmed.
        assertEquals("Please summarize this", vm.uiState.value.draft)
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

    // -- Issue #585: mic swipe-up lock ---------------------------------------

    @Test
    fun lockRecordingOnlyAppliesDuringActiveRecording() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))

        vm.lockRecording()
        assertFalse(vm.uiState.value.recordingLocked)

        vm.onMicTap()
        runCurrent()
        vm.lockRecording()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)
        assertTrue(vm.uiState.value.recordingLocked)

        vm.cancelRecording()
        advanceUntilIdle()
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertFalse(vm.uiState.value.recordingLocked)
    }

    @Test
    fun stoppingLockedRecordingClearsLockBeforeTranscribing() = runTest {
        val release = CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                release.await()
                return Result.success("locked words")
            }
        }
        val vm = newVm(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.lockRecording()
        assertTrue(vm.uiState.value.recordingLocked)

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)
        assertFalse(vm.uiState.value.recordingLocked)

        release.complete(Unit)
        advanceUntilIdle()
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

    /**
     * Issue #688: a [WhisperClient] that holds every transcribe call open
     * until [releaseAll] is invoked. Lets the test make two retry triggers
     * provably concurrent (both in-flight at the same time) before either
     * resolves — the only way to reproduce the duplicate-insert race
     * deterministically. [callCount] records how many round-trips actually
     * fired, so the test can assert the in-flight guard deduped them.
     */
    private class GatedWhisperClient(
        private val result: Result<String>,
    ) : WhisperClient {
        private val gate = CompletableDeferred<Unit>()

        @Volatile
        var callCount: Int = 0
            private set

        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
            callCount++
            gate.await()
            return result
        }

        fun releaseAll() {
            if (!gate.isCompleted) gate.complete(Unit)
        }
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
        // Issue #882: track for teardown so a test that leaves recording active
        // doesn't leak the #880 ticker loop into `runTest`'s final
        // `advanceUntilIdle` (which would hang the suite). See
        // [tearDownViewModels]. Mirrors [newVm].
        createdViewModels += vm
        // Issue #891: disable the overall-send watchdog by default (see [newVm]).
        vm.setSendWatchdogTimeoutForTest(null)
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
    fun networkTimeoutLeavesTranscribingBeforePendingFailureWriteCompletes() = runTest {
        val queue = FakePendingQueue()
        queue.markFailureStarted = CompletableDeferred()
        queue.markFailureRelease = CompletableDeferred()
        val whisperRelease = CompletableDeferred<Unit>()
        val attachmentPath = "~/.pocketshell/attachments/host-1/keep-this.png"
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperRelease.await()
                return Result.failure(WhisperException.Transport("timeout"))
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )

        vm.onDraftChange("keep this draft")
        vm.seedAttachment(attachmentPath)
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        whisperRelease.complete(Unit)
        runCurrent()
        queue.markFailureStarted!!.await()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("Network error: timeout", vm.uiState.value.error)
        assertEquals("keep this draft", vm.uiState.value.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = attachmentPath,
                    displayName = "keep-this.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertTrue(queue.failureIds.isEmpty())

        queue.markFailureRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("pending-1" to "Network error: timeout"),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
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
    fun marginalNonSilentGuardRejectionIsQueuedForRetryInsteadOfLost() = runTest {
        // Regression for #587: a real, long capture can land below the
        // conservative speech RMS floor. It must still skip Whisper on the
        // first pass (#452), but the audio should be preserved for retry/export
        // instead of being discarded under an unrecoverable no-speech banner.
        val marginalAudio = SpeechAudioGuard.speechWavForTesting(durationMs = 1_200, amplitude = 0.004f)
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(marginalAudio))
        assertTrue(SpeechAudioGuard.isRecoverableNoSpeechRejection(marginalAudio))

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("should not be called")
            }
        }
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = marginalAudio),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(1, queue.enqueueCount)
        assertTrue(queue.storedAudio("pending-1")!!.contentEquals(marginalAudio))
        assertEquals(
            PromptComposerViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
            queue.snapshot().single().lastErrorMessage,
        )
        assertEquals(
            PromptComposerViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun pureSilenceStillSkipsWhisperAndIsNotQueued() = runTest {
        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("시청해주셔서 감사합니다!")
            }
        }
        val (vm, queue) = newVmWithQueue(
            mic = SilentCaptureMic(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(0, queue.enqueueCount)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun emptyWhisperSuccessKeepsRealRecordingQueuedForRetry() = runTest {
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("   \n") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("typed prompt")

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(emptyList<String>(), queue.succeededIds)
        assertEquals(
            listOf("pending-1" to PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertEquals("typed prompt", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun hallucinationWhisperSuccessKeepsSpeechLikeRecordingQueuedForRetry() = runTest {
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("시청해주셔서 감사합니다!") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("typed prompt")

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(emptyList<String>(), queue.succeededIds)
        assertEquals(
            listOf("pending-1" to PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertEquals("typed prompt", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
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
    fun retryPendingEmptyWhisperResponseKeepsAudioQueued() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "retryable-empty"
        queue.enqueueAudio(SpeechAudioGuard.speechWavForTesting(), "composer")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success(" \n ") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable-empty")
        advanceUntilIdle()

        assertEquals(
            listOf("retryable-empty" to PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE),
            queue.failureIds,
        )
        assertFalse(queue.succeededIds.contains("retryable-empty"))
        assertNotNull(queue.storedAudio("retryable-empty"))
        assertEquals("", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
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

    // -- Issue #688: retry idempotency + clear-pending state ----------------

    /**
     * Issue #688 regression: a flaky-network retry must insert the
     * transcript EXACTLY ONCE no matter how many retry triggers overlap.
     *
     * Reproduces the maintainer's dogfood report: a recording fails
     * (Network error: timeout) and lands in the pending queue marked
     * "waiting for network". An auto-retry (foreground-resume) and a manual
     * Retry tap then race the SAME row. Before the fix both round-trips ran
     * to completion and BOTH appended the transcript → the draft was
     * duplicated. After the fix the second trigger is a no-op while the
     * first is in flight, so the transcript is inserted once.
     *
     * The [GatedWhisperClient] holds every transcribe call open until the
     * test releases it, so the two triggers are provably concurrent.
     */
    @Test
    fun overlappingAutoRetryAndManualRetryInsertTranscriptExactlyOnce() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "flaky-1"
        // Queued as "waiting for network" so onForegroundResume auto-retries it.
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        val whisper = GatedWhisperClient(result = Result.success("hello world"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        // Trigger 1: foreground-resume auto-retry for the waiting row.
        vm.onForegroundResume()
        runCurrent()
        // The first round-trip is now open (gated). Trigger 2: the user taps
        // Retry on the very same row while the auto-retry is still in flight.
        vm.retryPending("flaky-1")
        runCurrent()

        // Let every gated transcribe call resolve.
        whisper.releaseAll()
        advanceUntilIdle()

        // Exactly one insertion — the draft is NOT "hello world hello world".
        assertEquals("hello world", vm.uiState.value.draft)
        // The round-trip ran at most once for this row (the duplicate
        // trigger was deduped, not just deduped at insert time).
        assertEquals(1, whisper.callCount)
        assertTrue(queue.succeededIds.contains("flaky-1"))
        // The pending queue is fully cleared — no stale row remains.
        assertTrue(queue.snapshot().isEmpty())
    }

    /**
     * Issue #688: a late success for a row that another retry already
     * inserted (or the user discarded) must be DROPPED, not appended a
     * second time. Simulates the "timeout that actually succeeded
     * server-side" the report calls out: the row is gone by the time the
     * straggler round-trip returns, so its transcript must not land.
     */
    @Test
    fun lateSuccessForAlreadyResolvedRowIsDropped() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "straggler"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        queue.markFailure("straggler", "Network error: timeout")

        val whisper = GatedWhisperClient(result = Result.success("late transcript"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.retryPending("straggler")
        runCurrent()
        // While the retry round-trip is open, the user discards the row
        // (or a sibling resolved it). The store no longer has it.
        queue.markSucceeded("straggler")
        runCurrent()

        // Now the straggler round-trip returns success.
        whisper.releaseAll()
        advanceUntilIdle()

        // The transcript must NOT be inserted — the row was already gone.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(queue.snapshot().isEmpty())
    }

    /**
     * Issue #688: while a retry round-trip is in flight, the row must
     * advertise a visible "retrying" state so the user gets immediate
     * feedback rather than a dead button. Once it resolves the flag clears.
     */
    @Test
    fun retryPublishesRetryingFeedbackWhileInFlight() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "feedback-1"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        queue.markFailure("feedback-1", "Network error: timeout")

        val whisper = GatedWhisperClient(result = Result.success("done"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.retryPending("feedback-1")
        runCurrent()
        // Mid-flight: the row is marked retrying.
        assertTrue(vm.uiState.value.retryingIds.contains("feedback-1"))

        whisper.releaseAll()
        advanceUntilIdle()
        // Resolved: the retrying flag is cleared.
        assertFalse(vm.uiState.value.retryingIds.contains("feedback-1"))
    }

    /**
     * Issue #688: a manual Retry tap while an auto-retry for the same row
     * is already in flight must NOT start a second round-trip. The duplicate
     * tap is a no-op (deduped by the in-flight guard).
     */
    @Test
    fun manualRetryWhileAutoRetryInFlightDoesNotStack() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "no-stack"
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        val whisper = GatedWhisperClient(result = Result.success("once"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.onForegroundResume()
        runCurrent()
        repeat(5) {
            vm.retryPending("no-stack")
            runCurrent()
        }
        whisper.releaseAll()
        advanceUntilIdle()

        assertEquals("only one transcribe call may fire for a row", 1, whisper.callCount)
        assertEquals("once", vm.uiState.value.draft)
    }

    /**
     * Issue #688: once the retry cap is hit, nothing auto-inserts. The
     * foreground-resume sweep must not re-fire a capped row, and a manual
     * Retry is gated at the cap.
     */
    @Test
    fun cappedRowNeverAutoInsertsOnForegroundResume() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "capped-row"
        // Cap via three failures while flagged as a network-waiting row so
        // a naive auto-retry sweep would still try it.
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = com.pocketshell.app.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
        queue.markFailure("capped-row", "Network error: timeout")
        queue.markFailure("capped-row", "Network error: timeout")
        queue.markFailure("capped-row", "Network error: timeout")

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("should never insert")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.onForegroundResume()
        advanceUntilIdle()
        vm.retryPending("capped-row")
        advanceUntilIdle()

        assertEquals("a capped row must not call Whisper", 0, whisperCalls)
        assertEquals("", vm.uiState.value.draft)
        // Row is still present in the explicit failed state, audio preserved.
        assertTrue(queue.snapshot().any { it.id == "capped-row" && it.atRetryCap })
        assertNotNull(queue.storedAudio("capped-row"))
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
    fun requestSendInIdleDispatchesDraftAndKeepsItInFlightUntilDelivered() = runTest {
        // Issue #745: tapping Send dispatches the draft AND flips the composer
        // into the in-flight state — the typed text is NOT cleared
        // optimistically. The draft only clears once the host confirms
        // delivery via [markSendDelivered]. This is the no-flicker contract:
        // the field never empties before the bytes are actually sent.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        vm.onDraftChange("hello shell")

        vm.requestSend(withEnter = false)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("hello shell", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        // In-flight: the draft is RETAINED and `sendInFlight` is true so the
        // Send button shows "Sending…".
        assertEquals("hello shell", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)

        // Host confirms delivery: now the draft clears and in-flight ends.
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun requestSendInIdleCarriesTapTimeSendTargetSnapshot() = runTest {
        // Issue #900: the route metadata is captured when Send is tapped and
        // carried with the one-shot request. Existing callers use the default
        // empty RawBytes target; route-aware callers pass a concrete snapshot.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "session-A",
            paneId = "%1",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )
        vm.onDraftChange("hello target")

        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("hello target", sent[0].text)
        assertEquals(target, sent[0].sendTarget)
    }

    @Test
    fun requestSendWhileRecordingPreservesOriginalSendTargetSnapshot() = runTest {
        // Issue #900: a Send tap during Recording first stops the recorder and
        // transcribes. The eventual request must keep the target captured from
        // the original tap.
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = fakeWhisperClient { Result.success("recording target") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)
        val originalTarget = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "session-recording",
            paneId = "%4",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )

        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Recording, vm.uiState.value.recording)

        vm.requestSend(withEnter = true, sendTarget = originalTarget)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("recording target", sent[0].text)
        assertEquals(originalTarget, sent[0].sendTarget)
    }

    @Test
    fun requestSendWhileTranscribingPreservesOriginalSendTargetSnapshot() = runTest {
        // Issue #900: a Send tap during Transcribing is deferred until the
        // transcript lands. The emitted request must still target the pane that
        // was current at tap time, not a later focus snapshot.
        val release = CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                release.await()
                return Result.success("deferred target")
            }
        }
        val vm = newVm(
            mic = FakeMicCapture(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val sent = collectSendRequests(vm)
        val originalTarget = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "session-original",
            paneId = "%2",
            route = OutboundRoute.AgentConversation,
            agentKind = "claude",
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        runCurrent()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        vm.requestSend(withEnter = false, sendTarget = originalTarget)
        runCurrent()
        assertEquals(0, sent.size)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("deferred target", sent[0].text)
        assertEquals(originalTarget, sent[0].sendTarget)
    }

    @Test
    fun requestSendWhileAttachmentUploadInFlightPreservesOriginalSendTargetSnapshot() = runTest {
        // Issue #900: the upload-wait deferral must emit with the target from
        // the original Send tap after the staged attachment arrives.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        val uploadRelease = CompletableDeferred<Result<List<String>>>()
        val originalTarget = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "session-upload",
            paneId = "%3",
            route = OutboundRoute.RawBytes,
            agentKind = null,
        )
        vm.onDraftChange("send after upload")
        vm.attachFiles(count = 1) {
            uploadRelease.await()
        }
        runCurrent()

        vm.requestSend(withEnter = true, sendTarget = originalTarget)
        runCurrent()
        assertEquals(0, sent.size)

        uploadRelease.complete(Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png")))
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals(originalTarget, sent[0].sendTarget)
        assertEquals(1, sent[0].attachments.size)
    }

    @Test
    fun requestSendWhileInFlightDoesNotQueueASecondRequest() = runTest {
        // Issue #745: a second Send tap while the first is still resolving is a
        // no-op (the button is already disabled in the UI, but the VM also
        // guards so a stray double-tap can't dispatch twice).
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        vm.onDraftChange("hello shell")

        vm.requestSend(withEnter = false)
        advanceUntilIdle()
        vm.requestSend(withEnter = false)
        advanceUntilIdle()

        assertEquals(1, sent.size)
    }

    @Test
    fun requestSendAppendsAttachmentSuffixToSentPromptAndClearsChips() = runTest {
        // Issue #544: the "Attached files:" suffix is composed ONLY at SEND
        // time and appended at the END of the outgoing prompt — it never
        // appears in the live draft. After send the chips are cleared.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("Review these")
        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()
        // Pre-send invariant: the draft is still clean.
        assertEquals("Review these", vm.uiState.value.draft)

        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // The send buffers in the `sendRequests` channel; a collector
        // subscribing now still receives it (#254 buffering semantics).
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        assertEquals(
            """
            Review these

            Attached files:
            - ~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt
            - ~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv
            """.trimIndent(),
            sent[0].text,
        )
        assertEquals(true, sent[0].withEnter)
        // Issue #745: in-flight — draft + chips are RETAINED until the host
        // confirms delivery, then cleared by [markSendDelivered].
        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.attachments.size)
        assertTrue(vm.uiState.value.sendInFlight)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun requestSendWithOnlyAttachmentsAndNoDraftStillSends() = runTest {
        // Issue #544: a pure-attachment send (no typed text) still dispatches
        // because the composed prompt carries the "Attached files:" suffix.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/x-report.txt"))
        }
        advanceUntilIdle()

        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        assertEquals(
            """
            Attached files:
            - ~/.pocketshell/attachments/host-1/x-report.txt
            """.trimIndent(),
            sent[0].text,
        )
        // Issue #745: in-flight — the chip is retained until delivery is
        // confirmed, then cleared.
        assertEquals(1, vm.uiState.value.attachments.size)
        vm.markSendDelivered()
        assertTrue(vm.uiState.value.attachments.isEmpty())
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
    fun requestSendDuringAttachmentUploadWaitsForUploadThenSendsWithAttachment() = runTest {
        // Issue #570 (intent) + Issue #872 (reopen, HARD-CUT D22): tapping Send
        // while an upload is in flight must NOT disable the typed prompt — and it
        // must NOT silently drop the attachment by firing a text-only send (the
        // old #570 behaviour, which was the maintainer's on-device "attachment
        // lost" symptom). The send instead parks IN-FLIGHT, awaits the upload, and
        // then dispatches WITH the freshly-staged attachment. The typed text is
        // retained throughout (no flicker), and the "Sending…" state shows
        // immediately so the user gets feedback the moment they tap Send.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()

        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("send this now")

        vm.requestSend(withEnter = true)
        runCurrent()

        // In-flight immediately, typed draft retained, but NO request emitted yet
        // (we are waiting for the upload — never a text-only send).
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals("send this now", vm.uiState.value.draft)
        assertTrue(sent.isEmpty())

        // The upload now lands its attachment.
        uploadResult.complete(
            Result.success(listOf("~/.pocketshell/attachments/host-1/late.png")),
        )
        advanceUntilIdle()

        // Exactly one send went out, carrying BOTH the text and the attachment.
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].withEnter)
        assertEquals(1, sent[0].attachments.size)
        assertTrue(sent[0].text.startsWith("send this now"))
        assertTrue(sent[0].text.contains("late.png"))
        assertEquals("send this now", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)
    }

    // -- Issue #891: overall-send watchdog (stuck "Sending…" escape) ---------

    @Test
    fun stuckSendWithAttachmentTimesOutToRetryableFailedStateAndKeepsContent() = runTest {
        // Issue #891 (G10/D33 reproduce-first): the maintainer's EXACT on-device
        // symptom — a prompt with a PNG attachment stuck on "Sending…" FOREVER,
        // recoverable only by restarting the app. We reproduce a WEDGED send: the
        // attachment uploads fine, the user taps Send, the host `onSend` (the
        // tmux write + #869 ack-gate) never resolves — i.e. neither
        // markSendDelivered nor restoreFailedSend is ever called by the sheet
        // collector (a wedged channel, or the emission landing with no live
        // collector). Before the fix, `sendInFlight` stays true forever (RED:
        // infinite "Sending…", no retry, content NOT preserved as a failed
        // state). With the overall-send watchdog it MUST flip to a retryable
        // "Send failed" state with the draft + attachment preserved (GREEN).
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        // Enable the overall-send watchdog with the REAL production ceiling so
        // this test proves the actual on-device bound (virtual time is free).
        vm.setSendWatchdogTimeoutForTest(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS)
        val sent = collectSendRequests(vm)

        // Stage the attachment (upload completes), type the note. The upload's own
        // delay is tiny here; step just far enough to settle it WITHOUT reaching
        // the watchdog ceiling.
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/screenshot.png"))
        }
        runCurrent()
        vm.onDraftChange("look at this")
        assertEquals(1, vm.uiState.value.attachments.size)

        // Tap Send. The request is emitted (the sheet would route it into the
        // host onSend) and the composer goes in-flight = "Sending…".
        vm.requestSend(withEnter = true)
        runCurrent()
        assertEquals(1, sent.size)
        assertTrue("expected the composer to be Sending…", vm.uiState.value.sendInFlight)
        // Crucially: the host NEVER calls markSendDelivered / restoreFailedSend
        // (the wedged-send scenario). Time passes past the per-leg sheet bound…
        advanceTimeBy(PromptComposerViewModel.SEND_TIMEOUT_MS + 1_000L)
        runCurrent()
        // …yet the composer is STILL stuck: nothing is driving the resolution
        // callbacks, so the per-leg bounds alone do NOT own the in-flight state.
        // This is exactly the on-device hang the watchdog must escape.
        assertTrue(
            "the per-leg bounds do not own the in-flight state; still stuck pre-watchdog",
            vm.uiState.value.sendInFlight,
        )

        // The overall-send watchdog is the escape: cross its ceiling.
        advanceTimeBy(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS)
        runCurrent()

        // The in-flight state is GONE — no infinite "Sending…", no app restart.
        assertFalse(
            "send watchdog must clear the stuck in-flight state",
            vm.uiState.value.sendInFlight,
        )
        // A retryable "Send failed" banner is shown.
        assertEquals(
            PromptComposerViewModel.SEND_TIMEOUT_MESSAGE,
            vm.uiState.value.error,
        )
        // The staged text + attachment SURVIVE so Retry re-sends them (#872).
        assertEquals("look at this", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/screenshot.png",
            vm.uiState.value.attachments[0].remotePath,
        )

        // Retry actually re-dispatches the original text + attachment.
        sent.clear()
        vm.requestSend(withEnter = true)
        runCurrent()
        assertEquals(1, sent.size)
        assertTrue(sent[0].text.startsWith("look at this"))
        assertTrue(sent[0].text.contains("screenshot.png"))
        assertEquals(1, sent[0].attachments.size)
    }

    @Test
    fun stuckSendDuringUploadAwaitEscapesViaWatchdog() = runTest {
        // Issue #891 class coverage: the OTHER wedge site — tapping Send while an
        // attachment upload is STILL in flight ([dispatchSendAfterUpload]). If the
        // upload-await never resolves within the watchdog ceiling (a wedged
        // SSH/SFTP transfer — the #886 channel-stall class), the composer is stuck
        // on "Sending…". The overall-send watchdog must rescue this path too,
        // preserving the typed draft so Retry works.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        // Watchdog ceiling set BELOW the upload timeout for this test so it is
        // unambiguously the watchdog (not the upload bound) that rescues.
        val watchdogMs = 5_000L
        vm.setSendWatchdogTimeoutForTest(watchdogMs)
        collectSendRequests(vm)
        val uploadStarted = CompletableDeferred<Unit>()
        // A wedged upload: the stage never resolves within the watchdog window.
        val uploadResult = CompletableDeferred<Result<List<String>>>()

        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("send while uploading")
        vm.requestSend(withEnter = true)
        runCurrent()
        // In-flight ("Sending…") while awaiting the (wedged) upload.
        assertTrue(vm.uiState.value.sendInFlight)

        // The watchdog ceiling elapses with no resolution — it is the escape.
        advanceTimeBy(watchdogMs + 1_000L)
        runCurrent()

        assertFalse(
            "watchdog must clear the stuck upload-await in-flight state",
            vm.uiState.value.sendInFlight,
        )
        assertEquals(
            PromptComposerViewModel.SEND_TIMEOUT_MESSAGE,
            vm.uiState.value.error,
        )
        assertEquals("send while uploading", vm.uiState.value.draft)

        // Clean up the still-suspended (cancellable) upload + await so `runTest`'s
        // uncompleted-coroutine guard does not flag the deliberately-wedged stage.
        vm.clearForTest()
    }

    @Test
    fun deliveredSendBeforeTimeoutDoesNotFireSpuriousWatchdogFailure() = runTest {
        // Issue #891 guard: a NORMAL send that the host confirms BEFORE the
        // watchdog ceiling must NOT later be stamped with a spurious "Send
        // failed" — the watchdog is disarmed on markSendDelivered.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.setSendWatchdogTimeoutForTest(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS)
        val sent = collectSendRequests(vm)
        vm.onDraftChange("hello there")

        vm.requestSend(withEnter = false)
        runCurrent()
        assertEquals(1, sent.size)
        assertTrue(vm.uiState.value.sendInFlight)

        // Host confirms delivery promptly (well under the ceiling).
        vm.markSendDelivered()
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals("", vm.uiState.value.draft)

        // Let the watchdog ceiling elapse — it must NOT resurrect a failure.
        advanceTimeBy(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS + 5_000L)
        runCurrent()
        assertFalse(vm.uiState.value.sendInFlight)
        assertNull(vm.uiState.value.error)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun failedSendBeforeTimeoutDisarmsWatchdogSoItDoesNotReStampAfterRetry() = runTest {
        // Issue #891 guard: a send that fails (restoreFailedSend) before the
        // ceiling disarms the watchdog, so a later in-progress retry/edit is not
        // clobbered by the first send's stale watchdog firing.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.setSendWatchdogTimeoutForTest(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS)
        collectSendRequests(vm)
        vm.onDraftChange("first attempt")
        vm.requestSend(withEnter = false)
        runCurrent()
        assertTrue(vm.uiState.value.sendInFlight)

        // Host reports failure quickly (the #872 path).
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(
                text = "first attempt",
                withEnter = false,
                cleanDraft = "first attempt",
            ),
        )
        assertFalse(vm.uiState.value.sendInFlight)
        val bannerAfterFailure = vm.uiState.value.error
        assertNotNull(bannerAfterFailure)

        // Past the first send's would-be ceiling: the disarmed watchdog must NOT
        // overwrite the current banner with the timeout message.
        advanceTimeBy(PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS + 5_000L)
        runCurrent()
        assertEquals(bannerAfterFailure, vm.uiState.value.error)
        assertFalse(vm.uiState.value.sendInFlight)
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
        // Issue #745: in-flight — the clean draft + chips are RETAINED (no
        // optimistic empty); `sendInFlight` is true while awaiting the host.
        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.attachments.size)
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
        // And the live tile is on screen while in flight (so Retry has it).
        assertEquals(1, vm.uiState.value.attachments.size)
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
    fun attachThenTypeKeepsBothVisibleInState() = runTest {
        // Issue #694 (second report): "when I attach something and then I want
        // to type, I get strange composer behavior where I don't see
        // anything." The state contract: attaching a file adds a chip WITHOUT
        // mutating the draft, and typing afterwards updates the draft WITHOUT
        // clearing the chips. Both must remain observable so the UI can render
        // the typed text AND the attachment tile at the same time.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()

        // After attach: one chip, draft still empty (attach never touches text).
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals("", vm.uiState.value.draft)

        // Now type. The draft updates and the chip survives.
        vm.onDraftChange("look at this")
        assertEquals("look at this", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/shot.png",
            vm.uiState.value.attachments.single().remotePath,
        )

        // Send composes both: typed text + the attachment suffix.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        assertTrue(sent[0].text.startsWith("look at this"))
        assertTrue(sent[0].text.contains("shot.png"))
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

            // Issue #745: each send dispatches exactly one request and parks
            // the composer in-flight with the draft retained. The success path
            // (host confirmed) then clears it via [markSendDelivered], leaving
            // a clean slate so the NEXT iteration's send is allowed (the
            // in-flight guard would otherwise drop a back-to-back send).
            assertEquals(1, sent.size)
            assertEquals("message $i", sent[0].text)
            assertEquals("message $i", vm.uiState.value.draft)
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
        // Issue #745: no optimistic clear — the draft stays visible while the
        // send is in flight (no flicker), so the user keeps seeing their text.
        assertEquals("ship it", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)

        // Host reports failure → restore. The draft stays, in-flight ends, and
        // an actionable banner appears; the sheet stays open on this state.
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
        // FSM lands back at Idle. Issue #745: the dispatched send parks the
        // composer in-flight with the combined draft retained until delivery
        // is confirmed; it clears only via [markSendDelivered].
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("Tell me from dictation", vm.uiState.value.draft)
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
        // Issue #745: in-flight until delivery confirmed.
        assertEquals("queued result", vm.uiState.value.draft)
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
    fun formatElapsedRendersZeroPaddedMinutesAndSeconds() {
        assertEquals("00:00", formatElapsed(0L))
        assertEquals("00:09", formatElapsed(9_000L))
        assertEquals("00:17", formatElapsed(17_400L)) // sub-second truncates
        assertEquals("01:05", formatElapsed(65_000L))
        assertEquals("12:34", formatElapsed(754_000L))
        // Defensive: negative durations clamp to zero rather than render "-1".
        assertEquals("00:00", formatElapsed(-500L))
    }

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
        // Issue #745: in-flight until delivery confirmed.
        assertEquals("deploy the app", vm.uiState.value.draft)
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
        // Issue #745: in-flight until delivery confirmed.
        assertEquals("deploy the app", vm.uiState.value.draft)
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
        // Issue #745: in-flight until delivery confirmed.
        assertEquals("send from transcribing", vm.uiState.value.draft)
        vm.markSendDelivered()
        assertEquals("", vm.uiState.value.draft)
    }

    // -- Issue #453: waveform phase offset pure-function tests ----------

    @Test
    fun waveformPhaseOffsetRangeIsBounded() {
        // The offset must stay within [-0.18, +0.18] for any bar index
        // and phase value so it only adds subtle ripple on top of the
        // amplitude-driven envelope.
        for (index in 0..29) {
            for (phase in listOf(0f, 7.5f, 15f, 22.5f, 29f, 30f, 60f)) {
                val offset = waveformPhaseOffset(index, phase)
                assertTrue(
                    "offset $offset out of range at index=$index phase=$phase",
                    offset >= -0.2f && offset <= 0.2f,
                )
            }
        }
    }

    @Test
    fun waveformPhaseOffsetProducesWavePattern() {
        // At phase=0, the offset should vary across bars (not all the
        // same value) — a flat response means the wave is broken.
        val offsets = (0..29).map { waveformPhaseOffset(it, 0f) }
        val distinct = offsets.toSet()
        assertTrue("All offsets identical — no wave pattern", distinct.size > 5)
    }

    @Test
    fun waveformPhaseOffsetIsPeriodic() {
        // Adding WAVEFORM_BARS (30) to the phase should produce the same
        // offset for every bar because the sine wraps via modulo.
        for (index in 0..29) {
            val a = waveformPhaseOffset(index, 0f)
            val b = waveformPhaseOffset(index, 30f)
            assertEquals(a, b, 0.0001f)
        }
    }

    @Test
    fun barEnvelopeHeightDpCentreIsTallest() {
        // The centre bar (index 14 or 15) must be taller than the edge
        // bars (index 0 or 29).
        val centre = barEnvelopeHeightDp(14)
        val edge = barEnvelopeHeightDp(0)
        assertTrue("Centre bar ($centre) should be taller than edge ($edge)", centre > edge)
    }
}
