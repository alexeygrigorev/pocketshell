package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Unit tests for [PromptComposerViewModel] behavior outside the focused
 * mic/Whisper recording FSM suite.
 *
 * Robolectric is here only because [AndroidKeystoreApiKeyStorage] (a
 * production constructor argument) reaches into the Android KeyStore for
 * its master key. We construct a real-but-isolated instance per test.
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
     * [RecordingState.Recording]. A test that starts recording and ends its
     * `runTest` body without stopping would leave that loop alive; `runTest`'s final
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
    fun clearErrorRemovesBanner() {
        val vm = newVm()
        vm.surfacePermissionDenied()
        assertNotNull(vm.uiState.value.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    // -- Send state and outbound queue --------------------------------------

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

}
