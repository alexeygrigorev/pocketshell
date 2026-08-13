package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.app.tmux.OutboundQueueAutoFlushController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** Issue #1944 physical drain ownership, promotion, and screen-consumer turnover. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerDrainOwnershipTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.clearForTest() }
        viewModels.clear()
    }

    private fun newVm(
        dispatcher: TestDispatcher,
        queue: OutboundQueueStore,
        draftStore: ComposerDraftStore = DisabledComposerDraftStore,
    ): PromptComposerViewModel =
        PromptComposerViewModel(
            audioRecorder = object : PromptComposerViewModel.MicCapture {
                override fun start() = Unit
                override fun stop(): ByteArray = byteArrayOf(1)
                override fun currentAmplitude(): Float = 0f
            },
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = object : ApiKeyVault {
                override fun save(key: CharArray) = Unit
                override fun load(): CharArray? = null
                override fun clear() = Unit
            },
            voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
                override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
                override fun whisperLanguageHint(): String? = null
                override fun transcriptionProvider(): VoiceTranscriptionProvider =
                    VoiceTranscriptionProvider.OpenAiWhisper
            },
            composerDraftStore = draftStore,
            outboundQueueStore = queue,
            savedStateHandle = SavedStateHandle(),
        ).also {
            it.samplerDispatcher = dispatcher
            it.outboundQueueDispatcher = dispatcher
            it.setSendWatchdogTimeoutForTest(null)
            viewModels += it
        }

    /**
     * Reopened #1602: a screen replacement installs a new physical-send consumer
     * before the retiring generation's buffered request is reduced. The row still
     * says InFlight and is younger than the wall-clock stale bound, but its stamped
     * consumer generation can no longer perform IO. Retry must use that exact
     * ownership fact instead of waiting ~160 seconds and appearing tappable-silent.
     *
     * RED on current main: [activeSendIsWedged] only checks row age, so the Retry
     * merely re-arms the tail and produces no request on the recovered consumer.
     */
    @Test
    fun issue1602_retryReDrivesOnRecoveredReplacementConsumerWithoutWallClockWait() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(dispatcher, queue)
        vm.setTransportWritableProbe { true }
        val target = "1/session-a"
        vm.onComposerTargetChanged(target)
        val head = queue.enqueue(target, "old owner", createdAtMs = 1L)
        val retry = queue.enqueue(target, "retry on recovered wire", createdAtMs = 2L)
        vm.refreshOutboundQueueItemsFor(target)

        val retiredGeneration = vm.outboundSendConsumers.register()
        assertTrue(vm.dispatchOutboundItem(head.id))
        advanceUntilIdle()
        // The retiring screen already took its request from the one-consumer
        // channel, then disappeared before reducing a terminal result.
        val retiredRequest = vm.sendRequests.first()
        assertEquals(retiredGeneration, retiredRequest.outboundConsumerGeneration)
        assertEquals(OutboundState.InFlight, requireNotNull(queue.item(head.id)).state)

        val physicalAttempts = mutableListOf<PromptComposerViewModel.SendRequest>()
        val physicalAttemptStarted = CompletableDeferred<Unit>()
        val physicalAttemptNeverReturns = CompletableDeferred<ComposerSendResult>()
        val recoveredConsumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                physicalAttempts += request
                physicalAttemptStarted.complete(Unit)
                physicalAttemptNeverReturns.await()
            })
        }
        runCurrent()
        val recoveredGeneration = requireNotNull(vm.outboundSendConsumers.activeGenerationForDispatch())
        assertTrue(recoveredGeneration != retiredGeneration)
        assertTrue(
            "the exact request is owned by a retired consumer on a writable recovered wire",
            vm.activeSendIsWedged(),
        )

        vm.retryOutboundItem(retry.id)
        assertEquals(
            "the tap must become observable before the async drain gets a turn",
            setOf(retry.id),
            vm.uiState.value.outboundRetryingIds,
        )
        runCurrent()
        physicalAttemptStarted.await()

        assertEquals(
            "Retry must invoke recovered screen B's physical host-send callback exactly once",
            1,
            physicalAttempts.count { it.outboundQueueItemId == retry.id },
        )
        val physicalRequest = physicalAttempts.single()
        assertEquals("payload bytes must remain exact", "retry on recovered wire", physicalRequest.cleanDraft)
        assertEquals("the physical write owns exactly one Enter", true, physicalRequest.withEnter)
        assertEquals(recoveredGeneration, physicalRequest.outboundConsumerGeneration)
        assertEquals(OutboundState.Queued, requireNotNull(queue.item(head.id)).state)
        assertEquals(OutboundState.InFlight, requireNotNull(queue.item(retry.id)).state)
        assertTrue(vm.uiState.value.outboundRetryingIds.isEmpty())
        assertEquals(listOf(head.id, retry.id), queue.itemsFor(target).map { it.id })
        recoveredConsumer.cancelAndJoin()
    }

    /** A programmatic/manual Retry is fail-closed even if stale UI invokes it offline. */
    @Test
    fun issue1602_manualRetryOnDeadWireWaitsVisiblyWithoutAnyPhysicalAttempt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(dispatcher, queue)
        vm.setTransportWritableProbe { false }
        val target = "1/session-a"
        vm.onComposerTargetChanged(target)
        val failed = queue.enqueue(target, "must remain byte exact", createdAtMs = 1L)
        requireNotNull(queue.markFailed(failed.id, "connection lost"))
        vm.refreshOutboundQueueItemsFor(target)
        val physicalAttempts = mutableListOf<PromptComposerViewModel.SendRequest>()
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                physicalAttempts += request
                ComposerSendResult.Delivered
            })
        }
        runCurrent()

        vm.retryOutboundItem(failed.id)
        runCurrent()

        assertTrue("dead wire cannot enter the physical callback", physicalAttempts.isEmpty())
        assertFalse(vm.uiState.value.sendInFlight)
        assertTrue(vm.uiState.value.outboundRetryingIds.isEmpty())
        assertEquals(
            "Waiting for connection — Retry when the session is online.",
            vm.uiState.value.error,
        )
        assertEquals("must remain byte exact", requireNotNull(queue.item(failed.id)).cleanText)
        assertEquals(OutboundState.Queued, requireNotNull(queue.item(failed.id)).state)
        assertEquals(listOf(failed.id), queue.itemsFor(target).map { it.id })
        consumer.cancelAndJoin()
    }

    /**
     * Reopened #1602/#2034 stale-callback/ABA guard. Once a row has acquired a
     * second drain token, a late failure from token A must not clear token B's
     * global gate or requeue B's InFlight row. It also must not touch the user's
     * newer live draft or any unrelated row identity.
     *
     * RED on current main: terminal callbacks ignore the failed token release and
     * unconditionally clear [PromptComposerViewModel.inFlightSendRequest].
     */
    @Test
    fun issue1602_staleFailureCallbackCannotClearReplacementAttemptOrNewDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val draftStore = InMemoryComposerDraftStore()
        val vm = newVm(dispatcher, queue, draftStore)
        val target = "1/session-a"
        vm.onComposerTargetChanged(target)
        val sent = collectSendRequests(vm)
        val head = queue.enqueue(target, "same row, new token", createdAtMs = 1L)
        val unrelated = queue.enqueue(target, "unrelated queued row", createdAtMs = 2L)
        vm.refreshOutboundQueueItemsFor(target)

        assertTrue(vm.dispatchOutboundItem(head.id))
        advanceUntilIdle()
        val retiredRequest = sent.single()
        vm.markOutboundSendDeferred(retiredRequest, resetAttemptBudget = true)
        assertTrue(vm.dispatchOutboundItem(head.id))
        advanceUntilIdle()
        val replacementRequest = requireNotNull(vm.inFlightSendRequest)
        assertTrue(retiredRequest.outboundDrainLeaseToken != replacementRequest.outboundDrainLeaseToken)
        vm.onDraftChange("new replacement prompt\nwith exact bytes: αβγ")

        vm.markOutboundSendDeferred(retiredRequest, resetAttemptBudget = true)

        assertEquals(replacementRequest, vm.inFlightSendRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(OutboundState.InFlight, requireNotNull(queue.item(head.id)).state)
        assertEquals(OutboundState.Queued, requireNotNull(queue.item(unrelated.id)).state)
        assertEquals(listOf(head.id, unrelated.id), queue.itemsFor(target).map { it.id })
        assertEquals("new replacement prompt\nwith exact bytes: αβγ", vm.uiState.value.draft)
        assertEquals(vm.uiState.value.draft, draftStore.load(target))
    }

    @Test
    fun issue1602_staleDeliveredCallbackCannotPruneReplacementAttemptOrNewDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val draftStore = InMemoryComposerDraftStore()
        val vm = newVm(dispatcher, queue, draftStore)
        val target = "1/session-a"
        vm.onComposerTargetChanged(target)
        val sent = collectSendRequests(vm)
        val row = queue.enqueue(target, "same row, new token", createdAtMs = 1L)
        val unrelated = queue.enqueue(target, "unrelated queued row", createdAtMs = 2L)
        vm.refreshOutboundQueueItemsFor(target)

        assertTrue(vm.dispatchOutboundItem(row.id))
        advanceUntilIdle()
        val retiredRequest = sent.single()
        vm.markOutboundSendDeferred(retiredRequest, resetAttemptBudget = true)
        assertTrue(vm.dispatchOutboundItem(row.id))
        advanceUntilIdle()
        val replacementRequest = requireNotNull(vm.inFlightSendRequest)
        vm.onDraftChange("new draft survives stale delivered ack")

        assertFalse("retired token cannot claim delivery", vm.markSendDelivered(retiredRequest))

        assertEquals(replacementRequest, vm.inFlightSendRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(OutboundState.InFlight, requireNotNull(queue.item(row.id)).state)
        assertEquals(OutboundState.Queued, requireNotNull(queue.item(unrelated.id)).state)
        assertEquals(listOf(row.id, unrelated.id), queue.itemsFor(target).map { it.id })
        assertEquals("new draft survives stale delivered ack", vm.uiState.value.draft)
        assertEquals(vm.uiState.value.draft, draftStore.load(target))
    }

    /**
     * Reopened #1602/#2034 identity-promotion regression. The screen first binds
     * the fallback host/name key, then exact pane-generation proof promotes it to
     * the durable tmux key. That is a re-key of ONE session, not a user switch:
     * live draft bytes and every unrelated queue id must remain visible.
     *
     * RED on current main: queue rows are promoted, then
     * `onComposerTargetChanged(durable)` loads the empty durable draft slot and
     * visibly erases the still-live fallback draft.
     */
    @Test
    fun issue1602_exactIdentityPromotionPreservesDraftBytesAndAllQueueRowIds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val draftStore = InMemoryComposerDraftStore()
        val vm = newVm(dispatcher, queue, draftStore)
        val fallback = "1/session-a"
        val durable = "tmux:1:\$0:1944"
        vm.onComposerTargetChanged(fallback)
        val draft = "replacement prompt\n  preserves spaces + 🌍"
        vm.onDraftChange(draft)
        val failed = queue.enqueue(
            sessionKey = fallback,
            cleanText = "failed head",
            createdAtMs = 1L,
            paneId = "%0",
            tmuxSessionId = "\$0",
            tmuxSessionCreated = 1944L,
        )
        requireNotNull(queue.markFailed(failed.id, "connection lost"))
        val younger = queue.enqueue(
            sessionKey = fallback,
            cleanText = "younger row",
            createdAtMs = 2L,
            paneId = "%0",
            tmuxSessionId = "\$0",
            tmuxSessionCreated = 1944L,
        )
        vm.refreshOutboundQueueItemsFor(fallback)

        vm.promoteFallbackOutboundIdentity(fallback, durable, setOf("%0"), "\$0", 1944L)
        vm.onComposerTargetChanged(durable)

        assertEquals("visible draft bytes must not change during exact re-key", draft, vm.uiState.value.draft)
        assertEquals("durable draft owner receives the exact bytes", draft, draftStore.load(durable))
        assertEquals(listOf(failed.id, younger.id), vm.outboundQueueItems.value.map { it.id })
        assertEquals(listOf(failed.id, younger.id), queue.itemsFor(durable).map { it.id })
        assertTrue(queue.itemsFor(fallback).isEmpty())
    }

    private fun TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val requests = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch { vm.sendRequests.collect { requests += it } }
        runCurrent()
        return requests
    }

    @Test
    fun issue1944_firstConsumerAcceptsRequestBufferedBeforeRegistration() {
        val registry = OutboundSendConsumerRegistry()

        assertTrue(registry.canDispatch())
        assertNull(registry.activeGenerationForDispatch())

        val firstConsumer = registry.register()

        assertTrue(registry.accepts(firstConsumer, requestGeneration = null))
    }

    @Test
    fun issue1944_replacementConsumerRejectsRequestsOwnedByRetiredGeneration() {
        val registry = OutboundSendConsumerRegistry()
        val retiredConsumer = registry.register()
        assertTrue(registry.accepts(retiredConsumer, retiredConsumer))
        assertTrue(registry.unregister(retiredConsumer))
        assertFalse(registry.canDispatch())

        val replacementConsumer = registry.register()

        assertFalse(registry.accepts(retiredConsumer, retiredConsumer))
        assertFalse(registry.accepts(replacementConsumer, retiredConsumer))
        assertTrue(registry.accepts(replacementConsumer, replacementConsumer))
    }

    @Test
    fun fallbackPhysicalOwnerSurvivesPromotionUntilDeferredThenDurableFifoRetries() = runTest {
        val fallback = "1/session-a"
        val durable = "tmux:1:\$0:1944"
        val queue = InMemoryOutboundQueueStore()
        val first = queue.enqueue(sessionKey = fallback, cleanText = "first dictated prompt", createdAtMs = 1L, paneId = "%1", tmuxSessionId = "\$0", tmuxSessionCreated = 1944L)
        val second = queue.enqueue(sessionKey = fallback, cleanText = "second dictated prompt", createdAtMs = 2L, paneId = "%1", tmuxSessionId = "\$0", tmuxSessionCreated = 1944L)
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged(fallback)

        assertTrue(vm.dispatchOutboundItem(first.id))
        advanceUntilIdle()
        val fallbackRequest = sent.single()
        assertEquals(first.id, fallbackRequest.outboundQueueItemId)
        assertEquals(
            "handoff must retain the physical row owner across identity promotion",
            first.id,
            vm.outboundDrainOwnership.activeRowId(),
        )
        val promoted = vm.promoteFallbackOutboundIdentity(
            fallback, durable, setOf("%1"), "\$0", 1944L,
        )
        assertEquals(listOf(first.id, second.id), promoted.map { it.id })
        assertEquals(OutboundState.InFlight, queue.item(first.id)!!.state)
        assertNull(queue.itemsFor(durable).planComposerAutoFlush(durable).nextId)
        assertFalse(vm.dispatchOutboundItem(second.id))
        vm.onComposerTargetChanged(durable)

        vm.markOutboundSendDeferred(fallbackRequest, resetAttemptBudget = true)
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(0, queue.item(first.id)!!.attemptCount)
        assertNull(vm.outboundDrainOwnership.activeRowId())
        assertEquals(
            listOf(OutboundState.Queued, OutboundState.Queued),
            vm.outboundQueueItems.value.map { it.state },
        )
        assertTrue(queue.itemsFor(fallback).isEmpty())
        assertEquals(first.id, vm.retryNextOutboundItem())
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(first.id, first.id), sent.map { it.outboundQueueItemId })
        assertEquals(OutboundState.InFlight, queue.item(first.id)!!.state)
        assertEquals(OutboundState.Queued, queue.item(second.id)!!.state)
    }

    @Test
    fun exactGenerationFallbackRowCannotDrainBeforeDurableIdentityPromotion() = runTest {
        val fallback = "1/renamed-a"
        val durable = "tmux:1:\$0:1944"
        val queue = InMemoryOutboundQueueStore()
        val row = queue.enqueue(
            sessionKey = fallback,
            cleanText = "dictated while disconnected",
            createdAtMs = 1L,
            paneId = "%0",
            tmuxSessionId = "\$0",
            tmuxSessionCreated = 1944L,
        )
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged(fallback)

        assertNull(
            "a live fallback window must wait for pane-proven durable ownership",
            vm.retryNextOutboundItem(),
        )
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertEquals(OutboundState.Queued, queue.item(row.id)?.state)

        vm.promoteFallbackOutboundIdentity(fallback, durable, setOf("%0"), "\$0", 1944L)
        vm.onComposerTargetChanged(durable)
        assertEquals(row.id, vm.retryNextOutboundItem())
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(row.id), sent.map { it.outboundQueueItemId })
    }

    @Test
    fun preHandoffClaimLossReleasesOwnerWithoutEmittingRequest() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val row = queue.enqueue(sessionKey = "1/session-a", cleanText = "must remain retryable", createdAtMs = 1L)
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertTrue(vm.dispatchOutboundItem(row.id))
        assertEquals(row.id, vm.outboundDrainOwnership.activeRowId())
        assertTrue(queue.remove(row.id))
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertNull(vm.outboundDrainOwnership.activeRowId())
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun issue1944_consumerMountDoesNotBypassClosedTransportDrainWindow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val row = queue.enqueue(sessionKey = "1/session-a", cleanText = "wait for the wire", createdAtMs = 1L)
        val vm = newVm(dispatcher, queue)
        vm.setTransportWritableProbe { false }
        vm.onComposerTargetChanged("1/session-a")
        var physicalSendCount = 0
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { physicalSendCount++; ComposerSendResult.Delivered })
        }
        runCurrent()

        assertEquals(OutboundState.Queued, queue.item(row.id)!!.state)
        assertNull(vm.outboundDrainOwnership.activeRowId())
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(0, physicalSendCount)
        consumer.cancel()
    }

    @Test
    fun issue1944_cancelledScreenConsumerDefersBeforeReplacementRetriesExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(dispatcher, queue)
        vm.setTransportWritableProbe { true }
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        val firstPhysicalSendStarted = CompletableDeferred<Unit>()
        val firstPhysicalSendNeverReturns = CompletableDeferred<ComposerSendResult>()
        val acceptedPayloads = mutableListOf<String>()
        vm.onComposerTargetChanged(target.sessionKey)
        val autoFlush = OutboundQueueAutoFlushController.boundTo(vm)
        val flushJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.outboundQueueItems.collect {
                autoFlush.onQueueSnapshotChanged(sessionLive = true) { excluded ->
                    vm.retryNextOutboundItem(excluded)
                }
            }
        }
        val acceptanceJob = backgroundScope.launch {
            vm.handoffAcceptances.collect(vm::completeHandoffAcceptanceReduction)
        }
        val retiringConsumer = launch {
            collectPromptComposerSendRequests(vm, onSend = {
                firstPhysicalSendStarted.complete(Unit)
                firstPhysicalSendNeverReturns.await()
            })
        }
        runCurrent()
        vm.onDraftChange("keep this across screen turnover")
        vm.requestSend(withEnter = true, sendTarget = target)
        runCurrent()
        firstPhysicalSendStarted.await()
        val rowId = requireNotNull(queue.itemsFor(target.sessionKey).singleOrNull()?.id)

        retiringConsumer.cancel(CancellationException("screen target turned over"))
        retiringConsumer.join()
        runCurrent()
        assertEquals(OutboundState.Queued, requireNotNull(queue.item(rowId)).state)
        assertFalse(vm.uiState.value.sendInFlight)
        assertNull(vm.outboundDrainOwnership.activeRowId())

        val replacement = launch {
            collectPromptComposerSendRequests(vm, onSend = { acceptedPayloads += it.cleanDraft; ComposerSendResult.Delivered })
        }
        advanceUntilIdle()
        assertEquals(listOf("keep this across screen turnover"), acceptedPayloads)
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
        assertFalse(vm.uiState.value.sendInFlight)
        assertNull(vm.outboundDrainOwnership.activeRowId())
        replacement.cancelAndJoin()
        acceptanceJob.cancelAndJoin()
        flushJob.cancelAndJoin()
    }
}
