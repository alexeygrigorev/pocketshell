package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * PR-1 of the #1616 composer audit — the DRAFT-LOSS-ON-FINALIZE data-loss bug
 * (Fable's top-priority item).
 *
 * The maintainer's exact scenario (Screenshot 3): a previous send is in flight
 * (`Sending…`, a queued row); the user types a NEW draft. When the in-flight
 * send FINALIZES (delivered, deferred, or permanently failed — including a
 * BACKGROUND reconnect auto-flush of a queued row), the composer used to:
 *   (a) WIPE the new draft from every store (in-memory, [SavedStateHandle], the
 *       durable [ComposerDraftStore]) — because [markSendDelivered] /
 *       [restoreFailedSend] cleared/overwrote the field, and
 *   (b) CLOSE the sheet over the user (the dispatcher's `onDelivered` fired
 *       unconditionally).
 *
 * Since #971/#1540 the composer field is emptied AT Send time
 * ([clearComposerForHandoff]), so at finalize time the field holds ONLY the
 * user's NEW post-handoff typing — nothing a finalize path clears can ever be
 * the delivered prompt. The fix: a finalize is a BACKGROUND event and must be
 * INVISIBLE to the editor. The sheet may only close as a direct result of a
 * USER action ([PromptComposerViewModel.isQuiescentForAutoClose] gates the
 * dispatcher).
 *
 * These are the reproduce-first (D33/G10) RED→GREEN + class-coverage (G2) tests:
 * {delivered, deferred/requeued, permanently-failed} × {same-target,
 * cross-target} × {text draft, staged attachment}, plus the background
 * queue-flush finalize case and the [restoreFailedSend] overwrite case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerDraftLossOnFinalizeTest {

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
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class FakeMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = byteArrayOf(1)
        override fun currentAmplitude(): Float = 0f
    }

    private class FakeVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.OpenAiWhisper
    }

    private fun fakeWhisperClient(): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
            Result.success("hello world")
    }

    private fun newVm(
        samplerDispatcher: TestDispatcher,
        composerDraftStore: ComposerDraftStore = InMemoryComposerDraftStore(),
        outboundQueueStore: OutboundQueueStore = InMemoryOutboundQueueStore(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { fakeWhisperClient() },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = outboundQueueStore,
            savedStateHandle = savedStateHandle,
        )
        vm.samplerDispatcher = samplerDispatcher
        vm.outboundQueueDispatcher = samplerDispatcher
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    private fun kotlinx.coroutines.test.TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val collected = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch { vm.sendRequests.collect { collected += it } }
        runCurrent()
        return collected
    }

    private fun target(sessionKey: String) =
        PromptComposerViewModel.SendTargetSnapshot(sessionKey = sessionKey)

    // ----------------------------------------------------------------------
    // markSendDelivered — the reported (Screenshot 3) BACKGROUND-flush case.
    // ----------------------------------------------------------------------

    @Test
    fun deliveredSameTargetKeepsNewTextDraftTypedDuringFlight() = runTest {
        // RED on base: markSendDelivered called onDraftChange("") — wiping the
        // draft from the field, SavedStateHandle, AND the durable store — then
        // the sheet closed over the user. GREEN: the finalize is invisible.
        val store = InMemoryComposerDraftStore()
        val savedState = SavedStateHandle()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            savedStateHandle = savedState,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("first prompt")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        // Handed off: the field is empty, the queue row carries the prompt.
        assertEquals("", vm.uiState.value.draft)

        // The user types a NEW draft while the previous send is in flight.
        vm.onDraftChange("I can still report")

        // The background send finalizes (delivered).
        vm.markSendDelivered(sent.single())

        // The new draft survives in EVERY store, and the sheet must NOT close.
        assertEquals("I can still report", vm.uiState.value.draft)
        assertEquals("I can still report", savedState.get<String>(PromptComposerViewModel.KEY_DRAFT))
        assertEquals("I can still report", store.load("1/session-a"))
        assertFalse(vm.isQuiescentForAutoClose())
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun deliveredSameTargetKeepsNewAttachmentStagedDuringFlight() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("first prompt")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.draft)

        // The user stages a NEW attachment during flight.
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/new-shot.png"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        vm.markSendDelivered(sent.single())

        // The new attachment survives; the sheet stays open.
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/new-shot.png",
            vm.uiState.value.attachments.single().remotePath,
        )
        assertFalse(vm.isQuiescentForAutoClose())
    }

    @Test
    fun deliveredCrossTargetDoesNotClearOtherSessionPersistedDraft() = runTest {
        // A queued send to session-a finalizes in the BACKGROUND while the
        // composer is showing session-b (the user switched). On base
        // markSendDelivered ran clearComposerDraft(deliveryTarget) — wiping
        // session-a's persisted draft that the user still wants.
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        // Session A has a persisted draft the user wants kept.
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("draft in A")
        // The user switches the composer to session B and types there.
        vm.onComposerTargetChanged("1/session-b")
        vm.onDraftChange("draft in B")
        assertEquals("draft in A", store.load("1/session-a"))

        // A background send targeting session A finalizes.
        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "old prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        val request = PromptComposerViewModel.SendRequest(
            text = "old prompt A",
            withEnter = true,
            cleanDraft = "old prompt A",
            sendTarget = target("1/session-a"),
            outboundQueueItemId = row.id,
        )
        vm.markSendDelivered(request)

        // Session A's persisted draft is UNTOUCHED, and session B's live draft
        // (the shown composer) is untouched too.
        assertEquals("draft in A", store.load("1/session-a"))
        assertEquals("draft in B", vm.uiState.value.draft)
        assertEquals("draft in B", store.load("1/session-b"))
    }

    @Test
    fun deliveredCrossTargetDoesNotClearOtherSessionPersistedAttachment() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/session-b")
        vm.onDraftChange("draft in B")
        val attachment = DurableAttachmentRef(
            remotePath = "~/.pocketshell/attachments/host-1/new-a.png",
            displayName = "new-a.png",
        )
        store.saveAttachments("1/session-a", listOf(attachment))

        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "old prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        vm.markSendDelivered(
            PromptComposerViewModel.SendRequest(
                text = "old prompt A",
                withEnter = true,
                cleanDraft = "old prompt A",
                sendTarget = target("1/session-a"),
                outboundQueueItemId = row.id,
            ),
        )

        assertEquals(listOf(attachment), store.loadAttachments("1/session-a"))
        assertEquals("draft in B", vm.uiState.value.draft)
    }

    @Test
    fun deliveredWithQuiescentComposerAllowsAutoClose() = runTest {
        // The normal Send-and-empty flow: after handoff the composer is
        // quiescent (draft empty, no attachments, Idle), so the sheet is STILL
        // allowed to auto-close on delivery. The guard only KEEPS it open when
        // the user has started a new draft.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("ship it")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.draft)

        vm.markSendDelivered(sent.single())

        assertTrue(vm.isQuiescentForAutoClose())
        assertEquals("", vm.uiState.value.draft)
    }

    // ----------------------------------------------------------------------
    // markOutboundSendDeferred — the reconnect requeue finalize.
    // ----------------------------------------------------------------------

    @Test
    fun deferredKeepsNewTextDraftTypedDuringFlight() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("first prompt")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.draft)

        vm.onDraftChange("typing while it retries")

        // The send is deferred back to the queue (a drop → auto-retry).
        vm.markOutboundSendDeferred(sent.single())

        // The new draft is untouched; the row stays queued; the sheet stays open.
        assertEquals("typing while it retries", vm.uiState.value.draft)
        assertEquals("typing while it retries", store.load("1/session-a"))
        assertEquals(1, vm.outboundQueueItems.value.size)
        assertEquals(OutboundState.Queued, vm.outboundQueueItems.value.single().state)
        assertFalse(vm.isQuiescentForAutoClose())
    }

    @Test
    fun deferredKeepsNewAttachmentStagedDuringFlight() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("first prompt")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/new-deferred.png"))
        }
        advanceUntilIdle()

        vm.markOutboundSendDeferred(sent.single())

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/new-deferred.png",
            vm.uiState.value.attachments.single().remotePath,
        )
        assertEquals(OutboundState.Queued, vm.outboundQueueItems.value.single().state)
        assertFalse(vm.isQuiescentForAutoClose())
    }

    @Test
    fun deferredCrossTargetKeepsOtherSessionPersistedTextDraft() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/session-b")
        vm.onDraftChange("draft B")
        store.save("1/session-a", "draft A")
        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "old prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        queue.claim(row.id)

        vm.markOutboundSendDeferred(
            PromptComposerViewModel.SendRequest(
                text = "old prompt A",
                withEnter = true,
                cleanDraft = "old prompt A",
                sendTarget = target("1/session-a"),
                outboundQueueItemId = row.id,
            ),
        )

        assertEquals("draft A", store.load("1/session-a"))
        assertEquals("draft B", vm.uiState.value.draft)
        assertEquals(OutboundState.Queued, queue.item(row.id)?.state)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun deferredCrossTargetKeepsOtherSessionPersistedAttachment() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/session-b")
        val attachment = DurableAttachmentRef(
            remotePath = "~/.pocketshell/attachments/host-1/deferred-a.png",
            displayName = "deferred-a.png",
        )
        store.saveAttachments("1/session-a", listOf(attachment))
        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "old prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        queue.claim(row.id)

        vm.markOutboundSendDeferred(
            PromptComposerViewModel.SendRequest(
                text = "old prompt A",
                withEnter = true,
                cleanDraft = "old prompt A",
                sendTarget = target("1/session-a"),
                outboundQueueItemId = row.id,
            ),
        )

        assertEquals(listOf(attachment), store.loadAttachments("1/session-a"))
        assertEquals(OutboundState.Queued, queue.item(row.id)?.state)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    // ----------------------------------------------------------------------
    // restoreFailedSend — the permanent-failure overwrite case.
    // ----------------------------------------------------------------------

    @Test
    fun restoreFailedSameTargetDoesNotStompNewDraftTypedDuringFlight() = runTest {
        // RED on base: restoreFailedSend set draft = restoredDraft
        // UNCONDITIONALLY, overwriting the user's in-progress typing.
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("retry me")
        vm.requestSend(withEnter = false, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.draft)
        val request = sent.single()

        // The user types a NEW draft during flight.
        vm.onDraftChange("my new thoughts")

        // The send permanently fails.
        vm.restoreFailedSend(request, message = "host send failed")

        // The user's new draft is UNTOUCHED (NOT overwritten with "retry me"),
        // and the failed prompt is preserved as a retryable Failed queue row.
        assertEquals("my new thoughts", vm.uiState.value.draft)
        assertEquals("my new thoughts", store.load("1/session-a"))
        assertEquals(1, vm.outboundQueueItems.value.size)
        assertEquals(OutboundState.Failed, vm.outboundQueueItems.value.single().state)
        assertEquals("retry me", vm.outboundQueueItems.value.single().cleanText)
        assertFalse(vm.isQuiescentForAutoClose())
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun restoreFailedSameTargetStillRestoresWhenNoCompetingDraft() = runTest {
        // Regression guard: with NO new typing, the failed prompt is still
        // restored into the (empty) editor and the row is removed — the
        // pre-existing #971 single-representation behavior is preserved.
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("retry me")
        vm.requestSend(withEnter = false, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        val request = sent.single()

        vm.restoreFailedSend(request, message = "host send failed")

        assertEquals("retry me", vm.uiState.value.draft)
        assertEquals("host send failed", vm.uiState.value.error)
        assertNull(queue.item(request.outboundQueueItemId!!))
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    @Test
    fun restoreFailedSameTargetKeepsNewAttachmentStagedDuringFlight() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("retry me")
        vm.requestSend(withEnter = false, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        val request = sent.single()

        // The user stages a NEW attachment during flight (no text).
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/new.png"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        vm.restoreFailedSend(request, message = "host send failed")

        // The user's new attachment is untouched; failed prompt kept as a row.
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/new.png",
            vm.uiState.value.attachments.single().remotePath,
        )
        assertEquals(1, vm.outboundQueueItems.value.size)
        assertEquals(OutboundState.Failed, vm.outboundQueueItems.value.single().state)
        assertFalse(vm.isQuiescentForAutoClose())
    }

    @Test
    fun restoreFailedSameTargetKeepsIdenticalNewDraftAsSeparateAttempt() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("run it again")
        vm.requestSend(withEnter = true, sendTarget = target("1/session-a"))
        advanceUntilIdle()
        val request = sent.single()

        // This is new post-handoff typing even though its bytes are identical.
        vm.onDraftChange("run it again")
        vm.restoreFailedSend(request, message = "host send failed")

        assertEquals("run it again", vm.uiState.value.draft)
        val failed = queue.item(request.outboundQueueItemId!!)
        assertNotNull(failed)
        assertEquals(OutboundState.Failed, failed?.state)
        assertEquals("run it again", failed?.cleanText)
    }

    @Test
    fun restoreFailedCrossTargetDoesNotStompOtherSessionPersistedDraft() = runTest {
        // The failed send targets session-a, but the composer is showing
        // session-b. On base restoreFailedSend ran saveComposerDraft(session-a,
        // failedPrompt) — overwriting session-a's real persisted draft.
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        // Session A already holds a real user draft.
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("real draft A")
        vm.onComposerTargetChanged("1/session-b")
        vm.onDraftChange("draft B")

        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "failed prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        queue.claim(row.id)
        val request = PromptComposerViewModel.SendRequest(
            text = "failed prompt A",
            withEnter = true,
            cleanDraft = "failed prompt A",
            sendTarget = target("1/session-a"),
            outboundQueueItemId = row.id,
        )
        vm.restoreFailedSend(request, message = "down")

        // Session A's real draft is preserved; the failed prompt survives as a
        // Failed row (NOT written over session A's draft).
        assertEquals("real draft A", store.load("1/session-a"))
        assertEquals("draft B", vm.uiState.value.draft)
        // The ViewModel deliberately exposes queue rows only for the currently
        // displayed session (B), so A's failed row must NOT bleed into B's UI.
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        val failed = queue.item(row.id)
        assertNotNull(failed)
        assertEquals(OutboundState.Failed, failed?.state)
        assertEquals("failed prompt A", failed?.cleanText)
    }

    @Test
    fun restoreFailedCrossTargetDoesNotStompOtherSessionPersistedAttachment() = runTest {
        val store = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
            outboundQueueStore = queue,
        )
        vm.onComposerTargetChanged("1/session-b")
        vm.onDraftChange("draft B")
        val attachment = DurableAttachmentRef(
            remotePath = "~/.pocketshell/attachments/host-1/real-a.png",
            displayName = "real-a.png",
        )
        store.saveAttachments("1/session-a", listOf(attachment))

        val row = queue.enqueue(
            sessionKey = "1/session-a",
            cleanText = "failed prompt A",
            attachments = emptyList(),
            withEnter = true,
            paneId = "%1",
            route = OutboundRoute.RawBytes,
            agentKind = null,
            createdAtMs = 1L,
        )
        queue.claim(row.id)
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(
                text = "failed prompt A",
                withEnter = true,
                cleanDraft = "failed prompt A",
                sendTarget = target("1/session-a"),
                outboundQueueItemId = row.id,
            ),
            message = "down",
        )

        assertEquals(listOf(attachment), store.loadAttachments("1/session-a"))
        assertEquals("draft B", vm.uiState.value.draft)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertEquals(OutboundState.Failed, queue.item(row.id)?.state)
    }

    // ----------------------------------------------------------------------
    // isQuiescentForAutoClose — the predictability-contract truth table.
    // ----------------------------------------------------------------------

    @Test
    fun quiescentGuardTruthTable() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/session-a")

        // Empty draft, no attachments, Idle → quiescent (safe to auto-close).
        assertTrue(vm.isQuiescentForAutoClose())

        // Non-empty draft → NOT quiescent.
        vm.onDraftChange("typing")
        assertFalse(vm.isQuiescentForAutoClose())
        vm.onDraftChange("")
        assertTrue(vm.isQuiescentForAutoClose())

        // A staged attachment → NOT quiescent.
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/a.png"))
        }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isNotEmpty())
        assertFalse(vm.isQuiescentForAutoClose())
    }

    @Test
    fun quiescentGuardIsFalseWhileRecording() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/session-a")
        assertTrue(vm.isQuiescentForAutoClose())

        // Enter the Recording state — the user is actively dictating a new
        // prompt; a background finalize must NOT close the sheet over them.
        vm.onMicTap()
        runCurrent()
        assertEquals(PromptComposerViewModel.RecordingState.Recording, vm.uiState.value.recording)
        assertFalse(vm.isQuiescentForAutoClose())
    }
}
