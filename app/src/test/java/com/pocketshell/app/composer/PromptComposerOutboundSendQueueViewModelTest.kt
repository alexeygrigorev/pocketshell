package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import org.junit.After
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
 * Focused coverage for outbound send queue dispatch, retry, watchdog, and
 * sidecar attachment upload behavior in [PromptComposerViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerOutboundSendQueueViewModelTest {

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

    private fun newSidecarStore(
        ioDispatcher: TestDispatcher,
        context: Context = ApplicationProvider.getApplicationContext(),
    ): OutboundAttachmentSidecarStore {
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also { store ->
            store.idGenerator = { "sidecar-${++nextId}" }
            store.clock = { nextId.toLong() }
            // Issue #1465: pin the store's `withContext(Dispatchers.IO)` file work
            // onto the caller's `testScheduler` (the same one `samplerDispatcher` /
            // `outboundQueueDispatcher` use). Without this the fire-and-forget
            // `launchSidecarRemoval` viewModelScope.launch{} hops real
            // `Dispatchers.IO` and resumes cross-thread onto `MainDispatcherRule`'s
            // `UnconfinedTestDispatcher`, whose `dispatch` throws
            // `UnsupportedOperationException` — the #1461-class coroutines-test race
            // that flaked `:app:testReleaseUnitTest` in the release gate.
            store.ioDispatcher = ioDispatcher
        }
    }

    private fun localAttachmentFile(name: String, content: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    /**
     * The sidecar store does real `Dispatchers.IO` file work (see
     * [OutboundAttachmentSidecarStore]) and the dispatch resumes back on the
     * test Main dispatcher only AFTER that IO completes — so driving the virtual
     * clock alone never observes the settled outcome. Each tick must also yield
     * to the real IO threads so the store work can make progress before the next
     * scheduler advance.
     *
     * Issue #1102: the previous bound was a fixed tick count (`maxTicks`), which
     * flaked the required `Unit tests` check under CI CPU contention. There is no
     * production seam to pin the store's `Dispatchers.IO` work onto the test
     * scheduler (Shape A is unavailable without touching production), so this is
     * the de-flake convention's Shape B — a wall-clock-bounded pump
     * (docs/testing.md "the one de-flake convention", #1048). When the real IO
     * thread is starved, a fixed number of single yields can return before the
     * store work has drained; this loops to a generous real-time deadline and
     * gives the real IO threads actual wall-clock scheduling time each tick, so
     * the predicate is observed once the work has genuinely settled rather than
     * after an arbitrary tick budget. Callers HARD-FAIL on the boolean result
     * ([settleUntil] / [waitForSidecarsCleared] / [waitForSendCount]); the
     * pump's exit condition is the load-bearing assertion, never the loop body.
     *
     * Issue #1048: this pump is deliberately NOT migrated onto the shared
     * `drainMainLooperUntil` settle-pump. It intentionally `advanceTimeBy`s /
     * `advanceUntilIdle`s the kotlinx VIRTUAL clock AND yields to the real
     * `Dispatchers.IO` each tick — that virtual-clock advance is incompatible with
     * the shared pump's "never touch a clock" invariant and is genuinely required
     * here to flush the sidecar store's timed work, so it stays separate (the
     * #1048 brief's "do NOT force genuinely-different pumps into one" rule).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.advanceSchedulerUntil(
        predicate: suspend () -> Boolean,
        // Generous, but kept safely under `runTest`'s 60s default global
        // wall-clock timeout so the pump's own HARD-FAIL fires first with a
        // clear message rather than runTest aborting the whole test.
        timeoutMs: Long = 40_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            advanceUntilIdle()
            if (predicate()) return true
            runCurrent()
            if (predicate()) return true
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return true
            yieldToRealDispatchers()
            if (predicate()) return true
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                return predicate()
            }
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.settleUntil(
        predicate: () -> Boolean,
    ) {
        assertTrue(
            "settleUntil timed out before the predicate held — the sidecar-backed " +
                "dispatch did not drain within the wall-clock bound (issue #1102)",
            advanceSchedulerUntil(predicate = { predicate() }),
        )
    }

    private suspend fun yieldToRealDispatchers() {
        withContext(Dispatchers.IO) {
            // A real (non-virtual) pause so a CI-starved IO thread actually gets
            // wall-clock scheduling time to drain the store's file work before
            // the next scheduler advance. A bare `yield()` can return before the
            // store's IO coroutine is even scheduled under load, which is the
            // #1102 flake; this is bounded by the caller's wall-clock deadline.
            Thread.sleep(1L)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.waitForSidecarsCleared(
        store: OutboundAttachmentSidecarStore,
        outboundItemId: String,
    ) {
        assertTrue(
            advanceSchedulerUntil(predicate = { store.refsFor(outboundItemId).isEmpty() }),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.waitForSendCount(
        sent: List<PromptComposerViewModel.SendRequest>,
        count: Int,
    ) {
        assertTrue(
            advanceSchedulerUntil(predicate = { sent.size >= count }),
        )
    }

    @Test
    fun requestSendInIdleDispatchesDraftAndKeepsItInFlightUntilDelivered() = runTest {
        // Issue #971 (reverses #745): tapping Send dispatches the draft AND hands
        // it off to the outbound queue — the editor is CLEARED on enqueue so the
        // prompt is represented EXACTLY ONCE (the "Sending…" row, when a queue
        // store is wired), never as BOTH editor text AND a queued row.
        // `sendInFlight` stays true (Send disabled / "Sending…") until the host
        // confirms delivery via [markSendDelivered].
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        vm.onDraftChange("hello shell")

        vm.requestSend(withEnter = false)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("hello shell", sent[0].text)
        assertEquals(false, sent[0].withEnter)
        // In-flight: the editor is CLEARED (the prompt was handed off) and
        // `sendInFlight` is true so the Send button shows "Sending…" + is disabled.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendInFlight)

        // Host confirms delivery: in-flight ends, editor still empty.
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
    fun requestSendEnqueuesOutboundItemAndCarriesQueueId() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            paneId = "%7",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("ship it")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        val request = sent.single()
        val queueId = request.outboundQueueItemId
        assertNotNull(queueId)
        val item = requireNotNull(queue.item(queueId!!))
        assertEquals("1/session-a", item.sessionKey)
        assertEquals("ship it", item.cleanText)
        assertEquals("%7", item.paneId)
        assertEquals(OutboundRoute.AgentPayload, item.route)
        assertEquals("codex", item.agentKind)
        assertEquals(OutboundState.InFlight, item.state)
        assertEquals(listOf(queueId), vm.outboundQueueItems.value.map { it.id })

        vm.discardOutboundItem(queueId)
        assertNotNull(queue.item(queueId))
        assertEquals(listOf(queueId), vm.outboundQueueItems.value.map { it.id })
    }

    @Test
    fun requestSendInIdleSchedulesOutboundEnqueueOffCallerThread() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            paneId = "%7",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("ship it later")
        vm.requestSend(withEnter = true, sendTarget = target)

        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals("", vm.uiState.value.draft)
        assertTrue(sent.isEmpty())
        assertTrue(queue.itemsFor("1/session-a").isEmpty())

        advanceUntilIdle()

        val request = sent.single()
        val queueId = requireNotNull(request.outboundQueueItemId)
        assertEquals("ship it later", request.cleanDraft)
        assertEquals(OutboundState.InFlight, queue.item(queueId)!!.state)
        assertEquals(listOf(queueId), vm.outboundQueueItems.value.map { it.id })
    }

    // -- Issue #971/#987: single-representation + auto-retry on drop -------
    //
    // The maintainer's v0.4.18 dogfood + the #987 refinement: while a send is in
    // flight the same prompt was shown TWICE — still in the editable composer
    // field AND as a "1 unsent prompt — Sending — <text>" queue row — and on a
    // DROP the composer stacked contradictory status ("Not sent… send again or
    // discard" vs "Send will retry once reconnected"). The CANONICAL design
    // (#987 Option A): on enqueue the editor CLEARS (the queue row is the ONE
    // representation in flight), Send is disabled in flight; on a drop the prompt
    // STAYS QUEUED and auto-sends on reconnect (NOT returned to the composer for
    // manual resend), shown as the SINGLE "Will send when reconnected." status.

    @Test
    fun issue971_sendInFlightClearsComposerSoPromptShowsExactlyOnce() = runTest {
        // RED on base: emitSendRequest kept the draft visible (#745) AND
        // enqueued a "Sending…" row, so the prompt appeared in BOTH the editor
        // and the queue row at once. GREEN after #971: the editor is cleared on
        // enqueue, leaving the single queue row as the in-flight representation,
        // and Send is disabled while in flight.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("/clear")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        // The send is in flight…
        assertTrue("send should be in flight after Send tap", vm.uiState.value.sendInFlight)
        // …and exactly ONE representation of the prompt exists: the queue row.
        // The composer editor is empty (handed off), NOT still holding "/clear".
        assertEquals(
            "composer editor must be cleared on enqueue — the queue row is the single representation",
            "",
            vm.uiState.value.draft,
        )
        // The single queue row carries the prompt.
        assertEquals(listOf("/clear"), vm.outboundQueueItems.value.map { it.cleanText })
        assertEquals(1, vm.outboundQueueItems.value.size)

        // Delivery clears the in-flight gate and prunes the row — still empty.
        vm.markSendDelivered(sent.single())
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun issue971_droppedSendStaysQueuedForAutoRetryNotReturnedToComposer() = runTest {
        // RED on base (this worktree's prior #971 fix): markOutboundSendDeferred
        // did not exist and the drop path called restoreFailedSend, which restored
        // the draft to the composer AND removed the queue row + stamped a "Not
        // sent. …send again or discard" error — exactly the #987 contradiction.
        // GREEN after #987 Option A: a dropped send STAYS QUEUED (single "Will
        // send when reconnected." row), the composer stays EMPTY (single
        // representation), and NO "Not sent / send again or discard" error is set.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("/clear")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        val queueId = requireNotNull(request.outboundQueueItemId)

        // In flight: editor cleared, single row.
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, vm.outboundQueueItems.value.size)

        // The send fails because the connection dropped (the dispatcher's
        // false/timeout branch routes here).
        vm.markOutboundSendDeferred(request)

        // (a) The composer stays CLEARED — the prompt is NOT returned to the
        // editor for a manual resend.
        assertEquals("", vm.uiState.value.draft)
        // (b) The prompt is ONE queued "will retry" row, NOT a removed/failed row.
        val row = requireNotNull(queue.item(queueId))
        assertEquals(OutboundState.Queued, row.state)
        assertNull("a deferred (will-retry) row carries no error label", row.lastError)
        assertEquals(listOf("/clear"), vm.outboundQueueItems.value.map { it.cleanText })
        assertEquals(1, vm.outboundQueueItems.value.size)
        // (c) NO contradictory "Not sent / send again or discard" error banner.
        assertNull(
            "a drop must show the single 'will retry' status, not a 'Not sent' error",
            vm.uiState.value.error,
        )
        // The consolidated status copy reads "Will send when reconnected."
        assertEquals(
            PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE,
            outboundQueueStateLabel(row),
        )
        // In-flight gate cleared so the reconnect flush can re-claim the row.
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun issue1526_deferralEmissionTriggeredFlushSeesClearedInFlightGateAndReclaims() = runTest {
        // Issue #1526 S1 (RED on the pre-fix ordering): markOutboundSendDeferred
        // refreshed the queue snapshot BEFORE clearing `sendInFlight`. The
        // screen's auto-flush (TmuxOutboundQueueAutoFlushEffect) is triggered BY
        // that snapshot emission and self-gates on `sendInFlight` — an
        // immediately-resumed collector (the on-device shape) observed the
        // requeued row while the gate was still up, skipped it, and — the list
        // never changing again — the deferred prompt sat "Will send when
        // reconnected." forever inside a silently-healed connection window (the
        // maintainer's delayed-delivery symptom). GREEN: the gate clears first,
        // so the very emission that announces the requeued row can re-claim it.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("exactly once across the flap")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val first = sent.single()

        // The PRODUCTION flush shape: the real controller retrying at most one
        // row per queue-snapshot emission, on an IMMEDIATELY-resumed collector
        // (UnconfinedTestDispatcher) — the interleave the composed effect
        // produces on-device.
        val controller = com.pocketshell.app.tmux.OutboundQueueAutoFlushController()
        val flushJob = launch(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        ) {
            vm.outboundQueueItems.collect {
                controller.onQueueSnapshotChanged(sessionLive = true) { excluding ->
                    vm.retryNextOutboundItem(excludingIds = excluding)
                }
            }
        }

        // The connection dropped mid-send with an ambiguous outcome — the
        // dispatcher defers the row (Option A).
        vm.markOutboundSendDeferred(first)
        advanceUntilIdle()

        // THE regression assertion: the deferral's own snapshot emission must be
        // able to re-claim the row (gate cleared first). On the old ordering the
        // flush saw sendInFlight=true, skipped, and no re-dispatch ever came.
        waitForSendCount(sent, 2)
        assertEquals(
            "the flush must re-claim the SAME deferred row",
            first.outboundQueueItemId,
            sent[1].outboundQueueItemId,
        )
        flushJob.cancel()
    }

    @Test
    fun issue971_queuedDropAutoSendsOnReconnectFlushExactlyOnce() = runTest {
        // (d) On reconnect the queued message auto-sends (the #900 flush wired in
        // TmuxSessionScreen via retryNextOutboundItem). After a drop the row stays
        // Queued; the flush claims it exactly once and delivery prunes it — no
        // duplicate, no manual resend needed.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("/clear")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val first = sent.single()

        // Connection drops → the send is deferred (stays queued, composer empty).
        vm.markOutboundSendDeferred(first)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, queue.itemsFor("1/session-a").size)
        assertEquals(OutboundState.Queued, queue.itemsFor("1/session-a").single().state)
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(1, sent.size)

        // Reconnect: the screen's flush claims the next queued row and re-dispatches.
        val flushedId = vm.retryNextOutboundItem()
        advanceUntilIdle()
        waitForSendCount(sent, 2)
        assertEquals(
            "the flush must re-claim the SAME queued row, not mint a new one",
            first.outboundQueueItemId,
            flushedId,
        )
        // Exactly ONE deliverable row for the one logical prompt; the auto-send
        // delivers it and the row is pruned — no duplicate.
        assertEquals(1, queue.itemsFor("1/session-a").size)
        assertEquals(2, sent.size)
        vm.markSendDelivered(sent.last())
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun issue971_sidecarRemovalFailureNeverLeaksUncaughtCoroutineAcrossTests() = runTest {
        // Issue #971 cross-test leak regression. The #971 fix added fire-and-forget
        // `viewModelScope.launch { store.removeOutboundItem(id) }` cleanups on the
        // delivered / failed / discarded outbound paths. Those hopped to real
        // `Dispatchers.IO` (inside removeOutboundItem) with NO try/catch, so a
        // `persistAll` write failure threw an UNCAUGHT exception that escaped
        // viewModelScope. Under `runTest` that exception is captured and
        // re-surfaced as `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`
        // on the NEXT coroutine-using unit test — exactly the leak that reddened
        // CI's full `:app:testDebugUnitTest` (green alone, red in the suite).
        //
        // The real IO `persistAll` failure is not deterministically reproducible
        // under `runTest`, so we inject the THROW synthetically on the cleanup
        // seam (the #780 synthetic-state model). The cleanup runs on the test
        // scheduler here, so without the [runCatching] guard the throw escapes
        // viewModelScope and FAILS this very `runTest` at body-end (RED). With the
        // guard it is swallowed and `runTest` completes cleanly (GREEN) — proving
        // a cleanup failure can never poison a sibling test or the on-device
        // pipeline.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler)),
        )
        var removalAttempts = 0
        vm.sidecarRemovalForTest = { _ ->
            removalAttempts++
            throw IllegalStateException("synthetic persistAll failure on sidecar cleanup")
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        // Drive ALL THREE cleanup callers so the whole class is covered:
        //  1) delivered (markOutboundSendDelivered)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("delivered prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.markSendDelivered(sent.single())
        advanceUntilIdle()

        //  2) failed (markOutboundSendFailed via restoreFailedSend)
        vm.onDraftChange("failing prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.restoreFailedSend(sent.last())
        advanceUntilIdle()

        //  3) discarded (discardOutboundItem)
        vm.onDraftChange("discard prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val toDiscard = vm.outboundQueueItems.value.firstOrNull()
            ?: queue.itemsFor("1/session-a").first()
        vm.markOutboundSendDeferred(sent.last())
        advanceUntilIdle()
        vm.discardOutboundItem(toDiscard.id)
        advanceUntilIdle()

        // The cleanup seam was actually exercised (G3: not a vacuous pass) and the
        // injected throws were contained — `runTest` reaching this assertion at all
        // means NO uncaught coroutine escaped (the leak is gone). If the guard were
        // missing, this body would never complete cleanly.
        assertTrue(
            "the sidecar cleanup seam must have been driven so the throw path is real",
            removalAttempts >= 1,
        )
    }

    @Test
    fun requestSendWithLocalAttachmentStagesSidecarUploadsBeforeClaimAndDispatchesUploadedRefs() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val uploadedSidecarBytes = mutableListOf<String>()
        val uploadFinished = CompletableDeferred<Unit>()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadedSidecarBytes += refs.map { File(it.localPath).readText() }
            uploadFinished.complete(Unit)
            Result.success(refs.map { "~/.pocketshell/attachments/reuploaded/${it.displayName}" })
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        val local = localAttachmentFile("sidecar-report.txt", "local bytes")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("review")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(Uri.fromFile(local), "text/plain")),
        ) {
            Result.success(listOf("~/.pocketshell/attachments/old/20260601-120000-01-sidecar-report.txt"))
        }
        advanceUntilIdle()

        vm.requestSend(withEnter = true, sendTarget = target)
        uploadFinished.await()
        waitForSendCount(sent, 1)

        val request = sent.single()
        val queueId = requireNotNull(request.outboundQueueItemId)
        assertEquals(listOf("local bytes"), uploadedSidecarBytes)
        assertEquals(
            appendAttachmentPaths("review", listOf("~/.pocketshell/attachments/reuploaded/sidecar-report.txt")),
            request.text,
        )
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "~/.pocketshell/attachments/reuploaded/sidecar-report.txt",
                    displayName = "sidecar-report.txt",
                    mimeType = "text/plain",
                ),
            ),
            request.attachments,
        )
        val inFlight = requireNotNull(queue.item(queueId))
        assertEquals(OutboundState.InFlight, inFlight.state)
        assertEquals(
            listOf(DurableAttachmentRef("~/.pocketshell/attachments/reuploaded/sidecar-report.txt", "sidecar-report.txt", "text/plain")),
            inFlight.attachments,
        )
        assertEquals(listOf(queueId), sidecars.refsFor(queueId).map { it.outboundItemId })
    }

    @Test
    fun issue987_attachmentUploadDropKeepsQueuedRowAndComposerClear() = runTest {
        // Reproduces the #987 attachment gap: after Send, a normal dropped SSH
        // connection during sidecar upload used to stamp an upload error and
        // leave a Failed row. Canonical behaviour is the same as text: the row
        // stays queued, the composer stays clear, and reconnect flush retries it.
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        var uploadAttempts = 0
        vm.setOutboundAttachmentSidecarUploader {
            uploadAttempts++
            Result.failure(IllegalStateException("No live SSH session"))
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        val local = localAttachmentFile("drop-upload.txt", "queued bytes")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("send with attachment")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(Uri.fromFile(local), "text/plain")),
        ) {
            Result.success(listOf("~/.pocketshell/attachments/old/drop-upload.txt"))
        }
        settleUntil { vm.uiState.value.attachments.isNotEmpty() }

        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { uploadAttempts == 1 && !vm.uiState.value.sendInFlight }

        assertTrue("upload drop must not emit a text-only send", sent.isEmpty())
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertNull("drop status is represented by the queued row only", vm.uiState.value.error)
        val row = queue.itemsFor("1/session-a").single()
        assertEquals(OutboundState.Queued, row.state)
        assertNull(row.lastError)
        assertEquals(
            PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE,
            outboundQueueStateLabel(row),
        )
        assertEquals(listOf(row.id), vm.outboundQueueItems.value.map { it.id })
    }

    @Test
    fun issue987_sidecarFlushWithoutLiveUploaderStaysQueuedForReconnect() = runTest {
        // Reproduces the no-live-session reconnect path for an already queued
        // attachment row. The flush must not hard-fail the row or surface a
        // separate composer banner; it waits in the queue for the next reconnect.
        // Issue #1102: with NO live uploader, the flush takes the
        // `uploader == null` branch of `uploadSidecarsForOutboundItem`, which
        // calls `requeueForRetry(id)` + `clearStrandedSendInFlight()` and returns
        // WITHOUT ever calling `markUploading`. The previous predicate keyed off
        // `markUploading` (never invoked here), so it was unsatisfiable and only
        // "passed" because the old `settleUntil` silently swallowed the timeout.
        // Key the settle off the action this path actually performs — the requeue
        // — so the wait genuinely blocks until the flush has completed.
        var requeueCount = 0
        val queue = object : InMemoryOutboundQueueStore() {
            override fun requeueForRetry(id: String): OutboundItem? {
                requeueCount++
                return super.requeueForRetry(id)
            }
        }
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val item = OutboundItem(
            id = "queued-no-live-uploader",
            sessionKey = "1/session-a",
            cleanText = "send queued attachment",
            attachments = listOf(DurableAttachmentRef("stale-local", "drop-upload.txt", "text/plain")),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(item)
        sidecars.stage(
            outboundItemId = item.id,
            uris = listOf(Uri.fromFile(localAttachmentFile("drop-upload.txt", "queued bytes"))),
        )
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertEquals(item.id, vm.retryNextOutboundItem())
        settleUntil { requeueCount == 1 && !vm.uiState.value.sendInFlight }

        assertTrue(sent.isEmpty())
        assertNull(vm.uiState.value.error)
        val row = queue.item(item.id)!!
        assertEquals(OutboundState.Queued, row.state)
        assertNull(row.lastError)
        assertEquals(
            PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE,
            outboundQueueStateLabel(row),
        )
        assertEquals(listOf(item.id), vm.outboundQueueItems.value.map { it.id })
    }

    @Test
    fun retryNextOutboundItemUploadsPersistedSidecarsBeforeClaimingQueuedRow() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val item = OutboundItem(
            id = "queued-local",
            sessionKey = "1/session-a",
            cleanText = "send queued",
            attachments = listOf(
                DurableAttachmentRef(
                    "~/.pocketshell/attachments/old/20260601-120000-01-queued.txt",
                    "20260601-120000-01-queued.txt",
                    "text/plain",
                ),
            ),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(item)
        sidecars.stage(
            outboundItemId = item.id,
            uris = listOf(Uri.fromFile(localAttachmentFile("queued.txt", "queued bytes"))),
        )
        val statesAtUpload = mutableListOf<OutboundState>()
        val uploadFinished = CompletableDeferred<Unit>()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            statesAtUpload += requireNotNull(queue.item(item.id)).state
            uploadFinished.complete(Unit)
            Result.success(refs.map { "~/.pocketshell/attachments/flushed/${it.displayName}" })
        }
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val retried = vm.retryNextOutboundItem()
        uploadFinished.await()
        waitForSendCount(sent, 1)

        assertEquals(item.id, retried)
        assertEquals(listOf(OutboundState.Uploading), statesAtUpload)
        val request = sent.single()
        assertEquals(item.id, request.outboundQueueItemId)
        assertEquals(
            appendAttachmentPaths("send queued", listOf("~/.pocketshell/attachments/flushed/queued.txt")),
            request.text,
        )
        val active = requireNotNull(queue.item(item.id))
        assertEquals(OutboundState.InFlight, active.state)
        assertEquals(1, active.attemptCount)
        assertEquals(
            listOf(DurableAttachmentRef("~/.pocketshell/attachments/flushed/queued.txt", "queued.txt", "text/plain")),
            active.attachments,
        )
    }

    @Test
    fun retryNextOutboundItemReplacesOnlyIndexedSidecarAttachmentWhenRemoteNameCollides() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val remoteRef = DurableAttachmentRef(
            remotePath = "~/.pocketshell/attachments/current/conflict.txt",
            displayName = "conflict.txt",
            mimeType = "text/plain",
        )
        val item = OutboundItem(
            id = "queued-mixed-local",
            sessionKey = "1/session-a",
            cleanText = "send mixed",
            attachments = listOf(
                remoteRef,
                DurableAttachmentRef(
                    "~/.pocketshell/attachments/old/20260601-120000-02-conflict.txt",
                    "20260601-120000-02-conflict.txt",
                    "text/plain",
                ),
            ),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(item)
        sidecars.stage(
            outboundItemId = item.id,
            uris = listOf(Uri.fromFile(localAttachmentFile("conflict.txt", "queued bytes"))),
            attachmentIndices = listOf(1),
        )
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            assertEquals(listOf(1), refs.map { it.attachmentIndex })
            Result.success(listOf("~/.pocketshell/attachments/flushed/conflict.txt"))
        }
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertEquals(item.id, vm.retryNextOutboundItem())
        waitForSendCount(sent, 1)

        assertEquals(
            appendAttachmentPaths(
                "send mixed",
                listOf(
                    "~/.pocketshell/attachments/current/conflict.txt",
                    "~/.pocketshell/attachments/flushed/conflict.txt",
                ),
            ),
            sent.single().text,
        )
        assertEquals(
            listOf(
                remoteRef,
                DurableAttachmentRef("~/.pocketshell/attachments/flushed/conflict.txt", "conflict.txt", "text/plain"),
            ),
            queue.item(item.id)!!.attachments,
        )
    }

    @Test
    fun concurrentSidecarRetriesOnlyOneUploadAndSendOwnsTheQueuedRow() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val item = OutboundItem(
            id = "race-local",
            sessionKey = "1/session-a",
            cleanText = "send once",
            attachments = listOf(DurableAttachmentRef("stale-local", "race.txt", "text/plain")),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(item)
        sidecars.stage(
            outboundItemId = item.id,
            uris = listOf(Uri.fromFile(localAttachmentFile("race.txt", "race bytes"))),
        )
        var uploadCount = 0
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadCount++
            assertEquals(OutboundState.Uploading, queue.item(item.id)!!.state)
            vm.retryOutboundItem(item.id)
            Result.success(refs.map { "~/.pocketshell/attachments/race/${it.displayName}" })
        }
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertEquals(item.id, vm.retryNextOutboundItem())
        waitForSendCount(sent, 1)
        assertEquals(1, uploadCount)

        val request = sent.single()
        assertEquals(item.id, request.outboundQueueItemId)
        assertEquals(1, queue.item(item.id)!!.attemptCount)
        assertEquals(OutboundState.InFlight, queue.item(item.id)!!.state)
    }

    @Test
    fun sidecarUploadBlocksSecondQueuedSidecarRowUntilFirstSendResolves() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = OutboundItem(
            id = "sidecar-first",
            sessionKey = "1/session-a",
            cleanText = "first",
            attachments = listOf(DurableAttachmentRef("stale-first", "first.txt", "text/plain")),
            createdAtMs = 1L,
        )
        val second = OutboundItem(
            id = "sidecar-second",
            sessionKey = "1/session-a",
            cleanText = "second",
            attachments = listOf(DurableAttachmentRef("stale-second", "second.txt", "text/plain")),
            createdAtMs = 2L,
        )
        queue.enqueueExisting(first)
        queue.enqueueExisting(second)
        sidecars.stage(first.id, listOf(Uri.fromFile(localAttachmentFile("first.txt", "first bytes"))))
        sidecars.stage(second.id, listOf(Uri.fromFile(localAttachmentFile("second.txt", "second bytes"))))
        val uploadIds = mutableListOf<String>()
        var reentrantRetry: String? = "not-called"
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadIds += refs.single().outboundItemId
            if (refs.single().outboundItemId == first.id) {
                reentrantRetry = vm.retryNextOutboundItem()
            }
            Result.success(refs.map { "~/.pocketshell/attachments/serial/${it.displayName}" })
        }
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertEquals(first.id, vm.retryNextOutboundItem())
        waitForSendCount(sent, 1)

        assertNull(reentrantRetry)
        assertEquals(listOf(first.id), uploadIds)
        assertEquals(first.id, sent.single().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(first.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(second.id)!!.state)

        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        waitForSidecarsCleared(sidecars, first.id)
        assertEquals(second.id, vm.retryNextOutboundItem())
        waitForSendCount(sent, 2)

        assertEquals(listOf(first.id, second.id), uploadIds)
        assertEquals(second.id, sent.last().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(second.id)!!.state)
    }

    @Test
    fun deliveredAndDeletedOutboundItemsCleanUpSidecars() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        val delivered = OutboundItem(
            id = "delivered-local",
            sessionKey = "1/session-a",
            cleanText = "done",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
        )
        val deleted = OutboundItem(
            id = "deleted-local",
            sessionKey = "1/session-a",
            cleanText = "delete",
            state = OutboundState.Queued,
            createdAtMs = 2L,
        )
        queue.enqueueExisting(delivered)
        queue.enqueueExisting(deleted)
        sidecars.stage(delivered.id, listOf(Uri.fromFile(localAttachmentFile("delivered.txt", "done"))))
        sidecars.stage(deleted.id, listOf(Uri.fromFile(localAttachmentFile("deleted.txt", "delete"))))
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.onComposerTargetChanged("1/session-a")

        vm.markSendDelivered(
            PromptComposerViewModel.SendRequest(
                text = "done",
                withEnter = true,
                sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a"),
                outboundQueueItemId = delivered.id,
            ),
        )
        advanceUntilIdle()

        waitForSidecarsCleared(sidecars, delivered.id)

        vm.discardOutboundItem(deleted.id)
        advanceUntilIdle()

        waitForSidecarsCleared(sidecars, deleted.id)
    }

    @Test
    fun deliveredSendMarksQueuedOutboundItemDeliveredAndRefreshesRows() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("send once")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        val request = sent.single()
        assertEquals(1, vm.outboundQueueItems.value.size)

        vm.markSendDelivered(request)

        assertNull(queue.item(request.outboundQueueItemId!!))
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun failedSendRemovesOutboundRowAndReturnsPromptToComposer() = runTest {
        // Issue #971: a failed delivery restores the prompt to the composer as
        // the SINGLE representation, so the queue row is REMOVED (not left as a
        // "Failed" row). Keeping both was the maintainer's reported duplicate —
        // the prompt back in the editor PLUS a "1 unsent prompt — Failed" row.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("retry me")
        vm.requestSend(withEnter = false, sendTarget = target)
        advanceUntilIdle()
        // Handed off: editor cleared, single in-flight row.
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, vm.outboundQueueItems.value.size)

        val request = sent.single()
        vm.restoreFailedSend(request, message = "host send failed")

        // The row is GONE — the prompt is the single representation in the editor.
        assertNull(queue.item(request.outboundQueueItemId!!))
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertEquals("retry me", vm.uiState.value.draft)
        assertEquals("host send failed", vm.uiState.value.error)
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun retryOutboundItemReemitsExistingFailedItemWithSameIdPayloadAndTarget() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val item = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "ship with context",
            attachments = listOf(DurableAttachmentRef("/tmp/build.log", "build.log", "text/plain")),
            withEnter = true,
            paneId = "%7",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
            createdAtMs = 1L,
        )
        queue.claim(item.id)
        queue.markFailed(item.id, lastError = "connection lost")
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        vm.retryOutboundItem(item.id)
        advanceUntilIdle()

        val request = sent.single()
        assertEquals(item.id, request.outboundQueueItemId)
        assertEquals("ship with context", request.cleanDraft)
        assertEquals(
            appendAttachmentPaths("ship with context", listOf("/tmp/build.log")),
            request.text,
        )
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "/tmp/build.log",
                    displayName = "build.log",
                    mimeType = "text/plain",
                ),
            ),
            request.attachments,
        )
        assertTrue(request.withEnter)
        assertEquals("1/session-a", request.sendTarget.sessionKey)
        assertEquals("%7", request.sendTarget.paneId)
        assertEquals(OutboundRoute.AgentPayload, request.sendTarget.route)
        assertEquals("codex", request.sendTarget.agentKind)
        val inFlight = requireNotNull(queue.item(item.id))
        assertEquals(OutboundState.InFlight, inFlight.state)
        assertEquals(2, inFlight.attemptCount)
        assertEquals(listOf(item.id), vm.outboundQueueItems.value.map { it.id })

        // Issue #971: a failed retry restores the prompt to the composer as the
        // SINGLE representation, so the queue row is REMOVED (not left "Failed").
        vm.restoreFailedSend(request, message = "still down")
        assertNull(queue.item(item.id))
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertEquals("ship with context", vm.uiState.value.draft)

        // Re-Send from the restored composer mints exactly one fresh deliverable
        // row, and delivery prunes it.
        vm.requestSend(
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(
                sessionKey = "1/session-a",
                paneId = "%7",
                route = OutboundRoute.AgentPayload,
                agentKind = "codex",
            ),
        )
        advanceUntilIdle()
        waitForSendCount(sent, 2)
        assertEquals(1, queue.itemsFor("1/session-a").size)
        val secondRequest = sent.last()
        vm.markSendDelivered(secondRequest)

        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun retryOutboundItemDoesNotDoubleRetryWhileInFlight() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val item = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "retry once",
            withEnter = false,
        )
        queue.claim(item.id)
        queue.markFailed(item.id, lastError = "lost")
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        vm.retryOutboundItem(item.id)
        vm.retryOutboundItem(item.id)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals(item.id, sent.single().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(item.id)!!.state)
        assertEquals(2, queue.item(item.id)!!.attemptCount)
        assertEquals(listOf(item.id), vm.outboundQueueItems.value.map { it.id })
    }

    @Test
    fun retryNextOutboundItemSchedulesQueueClaimOffCallerThread() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val item = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "saved prompt",
            createdAtMs = 1L,
            paneId = "%1",
        )
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val retried = vm.retryNextOutboundItem()

        assertEquals(item.id, retried)
        assertTrue(sent.isEmpty())
        assertEquals(OutboundState.Queued, queue.item(item.id)!!.state)

        advanceUntilIdle()

        assertEquals(item.id, sent.single().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(item.id)!!.state)
    }

    @Test
    fun retryNextOutboundItemClaimsOldestCurrentTargetAndHonorsLiveWindowExclusions() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val first = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "first retry",
            createdAtMs = 1L,
            paneId = "%1",
        )
        val second = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "second retry",
            createdAtMs = 2L,
            paneId = "%2",
        )
        val otherSession = queue.enqueue(
            sessionKey = "1/session-b",
            cleanText = "other session",
            createdAtMs = 0L,
        )
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val retriedFirst = vm.retryNextOutboundItem()
        advanceUntilIdle()

        assertEquals(first.id, retriedFirst)
        assertEquals(first.id, sent.single().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(first.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(second.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(otherSession.id)!!.state)

        vm.restoreFailedSend(sent.single(), message = "still disconnected")

        val retriedSecond = vm.retryNextOutboundItem(excludingIds = setOf(first.id))
        advanceUntilIdle()
        runCurrent()

        assertEquals(second.id, retriedSecond)
        assertEquals(2, sent.size)
        assertEquals(second.id, sent.last().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(second.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(otherSession.id)!!.state)
    }

    @Test
    fun resendAllQueuedRearmsFailedAndQueuedRowsToQueuedInFifoOrderWithoutMintingDuplicates() = runTest {
        // Issue #1308: the batch "Resend all" — with several unsent prompts queued
        // after a drop, one action re-arms EVERY resendable (Queued/Failed) row to
        // Queued in original FIFO order, reusing requeueForRetry, WITHOUT minting a
        // second deliverable row and WITHOUT disturbing a row still on the wire
        // (InFlight/Uploading), which would be the AC2 double-send.
        val queue = InMemoryOutboundQueueStore()
        val failed1 = queue.enqueue(sessionKey = "1/a", cleanText = "oldest failed", createdAtMs = 1L)
            .let { queue.markFailed(it.id, lastError = "connection lost", lastAttemptAtMs = 10L)!! }
        val queued2 = queue.enqueue(sessionKey = "1/a", cleanText = "middle queued", createdAtMs = 2L)
        val failed3 = queue.enqueue(sessionKey = "1/a", cleanText = "newest failed", createdAtMs = 3L)
            .let { queue.markFailed(it.id, lastError = "connection lost", lastAttemptAtMs = 10L)!! }
        val inFlight = queue.enqueue(sessionKey = "1/a", cleanText = "on the wire", createdAtMs = 4L)
            .let { queue.markInFlight(it.id)!! }
        val uploading = queue.enqueue(sessionKey = "1/a", cleanText = "uploading now", createdAtMs = 5L)
            .let { queue.markUploading(it.id)!! }
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/a")
        val rowCountBefore = queue.itemsFor("1/a").size

        val rearmed = vm.resendAllQueued()

        // FIFO: oldest-composed-first, only the resendable (Queued/Failed) rows.
        assertEquals(listOf(failed1.id, queued2.id, failed3.id), rearmed)
        // Every resendable row is now clean Queued (error cleared) — no sibling minted.
        assertEquals(OutboundState.Queued, queue.item(failed1.id)!!.state)
        assertNull(queue.item(failed1.id)!!.lastError)
        assertEquals(OutboundState.Queued, queue.item(queued2.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(failed3.id)!!.state)
        // AC2: rows still on the wire are untouched (re-arming them is the double-send).
        assertEquals(OutboundState.InFlight, queue.item(inFlight.id)!!.state)
        assertEquals(OutboundState.Uploading, queue.item(uploading.id)!!.state)
        // No duplicate rows minted — same five ids, same order.
        assertEquals(rowCountBefore, queue.itemsFor("1/a").size)
        assertEquals(
            listOf(failed1.id, queued2.id, failed3.id, inFlight.id, uploading.id),
            vm.outboundQueueItems.value.map { it.id },
        )
        // AC4: a re-armed row stays individually retryable (still exact-claimable).
        assertEquals(OutboundState.InFlight, queue.claim(failed1.id)!!.state)
    }

    @Test
    fun resendAllQueuedThenAutoDrainDeliversFifoAndCountDecrementsToZero() = runTest {
        // Issue #1308 (AC1 + AC4): after Resend all re-arms the backlog to Queued,
        // the SAME auto-send drain (retryNextOutboundItem, one-at-a-time) delivers
        // them oldest-first and the visible unsent count decrements to zero.
        val queue = InMemoryOutboundQueueStore()
        val first = queue.enqueue(sessionKey = "1/a", cleanText = "first", createdAtMs = 1L)
            .let { queue.markFailed(it.id, lastError = "lost", lastAttemptAtMs = 10L)!! }
        val second = queue.enqueue(sessionKey = "1/a", cleanText = "second", createdAtMs = 2L)
            .let { queue.markFailed(it.id, lastError = "lost", lastAttemptAtMs = 10L)!! }
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/a")

        assertEquals(listOf(first.id, second.id), vm.resendAllQueued())

        // Drain #1 claims the OLDEST re-armed row (FIFO), delivery prunes it.
        // `runCurrent()` (not advanceUntilIdle) runs the collector WITHOUT advancing
        // virtual time to the send watchdog's timeout, so delivery is deterministic.
        assertEquals(first.id, vm.retryNextOutboundItem())
        runCurrent()
        assertEquals(first.id, sent.single().outboundQueueItemId)
        vm.markSendDelivered(sent.single())
        assertNull(queue.item(first.id))
        assertEquals(listOf(second.id), vm.outboundQueueItems.value.map { it.id })

        // Drain #2 delivers the last row — the unsent count reaches zero.
        assertEquals(second.id, vm.retryNextOutboundItem())
        runCurrent()
        assertEquals(second.id, sent.last().outboundQueueItemId)
        vm.markSendDelivered(sent.last())
        assertNull(queue.item(second.id))
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun resendAllQueuedWithNoResendableRowsIsANoOp() = runTest {
        // Guard: a single in-flight row (nothing resendable) yields no re-arm and
        // no snapshot churn — the button gate (>= 2 resendable) has a VM twin here.
        val queue = InMemoryOutboundQueueStore()
        val inFlight = queue.enqueue(sessionKey = "1/a", cleanText = "on the wire", createdAtMs = 1L)
            .let { queue.markInFlight(it.id)!! }
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/a")

        assertTrue(vm.resendAllQueued().isEmpty())
        assertEquals(OutboundState.InFlight, queue.item(inFlight.id)!!.state)
    }

    @Test
    fun requeueStaleOutboundInFlightUsesWatchdogCutoffBeforeRetryNext() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val now = 500_000L
        val stale = OutboundItem(
            id = "stale-send",
            sessionKey = "1/session-a",
            cleanText = "recover me",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
            lastAttemptAtMs = now - PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS - 1L,
            attemptCount = 1,
            paneId = "%7",
            route = OutboundRoute.RawBytes,
        )
        val fresh = OutboundItem(
            id = "fresh-send",
            sessionKey = "1/session-a",
            cleanText = "leave active",
            state = OutboundState.InFlight,
            createdAtMs = 2L,
            lastAttemptAtMs = now - PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS + 1L,
            attemptCount = 1,
        )
        queue.enqueueExisting(stale)
        queue.enqueueExisting(fresh)
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            clock = { now },
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val recovered = vm.requeueStaleOutboundInFlight()
        val retried = vm.retryNextOutboundItem()
        advanceUntilIdle()

        assertEquals(listOf(stale.id), recovered.map { it.id })
        assertEquals(stale.id, retried)
        assertEquals(stale.id, sent.single().outboundQueueItemId)
        assertEquals(OutboundState.InFlight, queue.item(stale.id)!!.state)
        assertEquals(2, queue.item(stale.id)!!.attemptCount)
        assertEquals(OutboundState.InFlight, queue.item(fresh.id)!!.state)
        assertEquals(listOf(stale.id, fresh.id), vm.outboundQueueItems.value.map { it.id })
    }

    @Test
    fun foregroundResumeRequeuesStaleUploadingOutboundRows() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val now = 500_000L
        val staleUpload = OutboundItem(
            id = "stale-upload",
            sessionKey = "1/session-a",
            cleanText = "recover upload",
            state = OutboundState.Uploading,
            createdAtMs = 1L,
            lastAttemptAtMs = now - PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS - 1L,
            attachments = listOf(DurableAttachmentRef("/tmp/local.png", "local.png", "image/png")),
        )
        val freshUpload = staleUpload.copy(
            id = "fresh-upload",
            cleanText = "leave upload",
            createdAtMs = 2L,
            lastAttemptAtMs = now - PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS + 1L,
        )
        queue.enqueueExisting(staleUpload)
        queue.enqueueExisting(freshUpload)
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            clock = { now },
        )
        vm.onComposerTargetChanged("1/session-a")

        vm.onForegroundResume()
        runCurrent()

        assertEquals(OutboundState.Queued, queue.item(staleUpload.id)!!.state)
        assertEquals(OutboundState.Uploading, queue.item(freshUpload.id)!!.state)
        assertEquals(listOf(staleUpload.id, freshUpload.id), vm.outboundQueueItems.value.map { it.id })
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

    // --- Issue #961: drop+reconnect double-send (coalesce-on-enqueue) -------

    @Test
    fun reSendAfterFailedSendCoalescesToOneRowAndDeliversExactlyOnce() = runTest {
        // Issue #961 + #971 — THE REPORTED CASE (drop+reconnect double-send).
        // Connected → Send (row A, InFlight) → drop fails the send
        // (restoreFailedSend) → user re-Sends the restored draft → deliver →
        // reconnect auto-flush.
        //
        // Under #971 the failed send REMOVES row A and restores the prompt to the
        // composer as the single representation. The re-Send mints exactly ONE
        // fresh row; delivering it prunes it and the reconnect auto-flush finds
        // nothing left — so the prompt is delivered EXACTLY ONCE (no leftover
        // Failed row for the storm to re-deliver), the same end guarantee #961
        // gave via coalesce.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("deploy now")

        // 1) First Send → row A goes in-flight, editor cleared (handoff).
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val firstRequest = sent.single()
        assertEquals(1, vm.outboundQueueItems.value.size)
        assertEquals("", vm.uiState.value.draft)

        // 2) The link drops mid-send → the dispatcher restores the failed send.
        //    Row A is REMOVED (#971) and the draft is restored to "deploy now".
        vm.restoreFailedSend(firstRequest, message = "Not sent. Reconnect, then send again or discard.")
        assertEquals("deploy now", vm.uiState.value.draft)
        assertNull(queue.item(firstRequest.outboundQueueItemId!!))
        assertTrue(vm.outboundQueueItems.value.isEmpty())

        // 3) The user re-Sends the restored draft (the banner says "send again").
        //    The re-Send emits a SendRequest for the (single fresh) row.
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        waitForSendCount(sent, 2)

        // STILL exactly ONE row — no duplicate deliverable row.
        assertEquals(
            "re-Send of the restored draft must leave exactly ONE queued row",
            1,
            queue.itemsFor("1/session-a").size,
        )

        // 4) Deliver the re-Send, then drive reconnect auto-flush passes. Count
        //    every distinct queue row that ever reaches delivery.
        val deliveredIds = mutableSetOf<String>()
        sent.last().outboundQueueItemId?.let { deliveredIds += it }
        vm.markSendDelivered(sent.last())
        advanceUntilIdle()

        repeat(3) {
            val drained = vm.retryNextOutboundItem()
            advanceUntilIdle()
            if (drained != null) {
                sent.last().outboundQueueItemId?.let { deliveredIds += it }
                vm.markSendDelivered(sent.last())
                advanceUntilIdle()
            }
        }

        assertEquals(
            "the prompt must be delivered EXACTLY ONCE, not twice — deliveries: $deliveredIds",
            1,
            deliveredIds.size,
        )
        assertTrue("queue fully drained", queue.itemsFor("1/session-a").isEmpty())
    }

    @Test
    fun reSendOfADifferentPromptAfterFailureMakesOneFreshRow() = runTest {
        // Issue #961 + #971 — class coverage. Under #971 a failed send REMOVES
        // its row and restores the prompt to the composer, so there is no
        // leftover row to coalesce against. The user clears it and types a
        // DIFFERENT prompt; sending it must mint exactly ONE fresh deliverable
        // row carrying the new prompt (the old one is gone, not duplicated).
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.onComposerTargetChanged("1/session-a")

        vm.onDraftChange("deploy now")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.restoreFailedSend(sent.single(), message = "down")
        // The failed row is removed; the prompt is back in the composer.
        assertTrue(queue.itemsFor("1/session-a").isEmpty())
        assertEquals("deploy now", vm.uiState.value.draft)

        // A different prompt this time.
        vm.onDraftChange("rollback")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        waitForSendCount(sent, 2)

        val rows = queue.itemsFor("1/session-a")
        assertEquals("a fresh send makes exactly one row", 1, rows.size)
        assertEquals("rollback", rows.single().cleanText)
    }

    @Test
    fun reconnectStormReSendDeliversExactlyOnce() = runTest {
        // Issue #961 — class coverage: reconnect-storm. After a failed send and
        // a re-Send (coalesced), MULTIPLE reconnect auto-flush passes must still
        // deliver the prompt exactly once (the per-row send-once + the dedup
        // together). Repeated retryNextOutboundItem calls after delivery return
        // null — they can never re-deliver a pruned row.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("deploy now")

        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.restoreFailedSend(sent.single(), message = "down")
        // #971: the failed row is removed; the re-Send mints a single fresh row.
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        waitForSendCount(sent, 2)
        assertEquals(1, queue.itemsFor("1/session-a").size)

        // The re-Send emitted its own request — deliver it.
        val deliveries = mutableSetOf<String>()
        sent.last().outboundQueueItemId?.let { deliveries += it }
        vm.markSendDelivered(sent.last())
        advanceUntilIdle()

        // Storm: fire several flush passes. None may re-deliver the pruned row.
        repeat(5) {
            val claimed = vm.retryNextOutboundItem()
            advanceUntilIdle()
            if (claimed != null) {
                sent.last().outboundQueueItemId?.let { deliveries += it }
                vm.markSendDelivered(sent.last())
                advanceUntilIdle()
            }
        }

        assertEquals("reconnect-storm must still deliver exactly once", 1, deliveries.size)
        assertTrue(queue.itemsFor("1/session-a").isEmpty())
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
        // Issue #971: in-flight — the prompt + chips were HANDED OFF to the queue
        // row, so the editor is cleared (the row is the single representation).
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
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
        // Issue #971: in-flight — the chip was handed off to the queue row, so
        // the editor's tiles are cleared (the row is the single representation).
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)
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
        // Issue #971: once the real dispatch fires (after the upload), the prompt
        // is handed off to the queue row — the editor is cleared.
        assertEquals("", vm.uiState.value.draft)
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

}
