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

    private fun newVm(dispatcher: TestDispatcher, queue: OutboundQueueStore): PromptComposerViewModel =
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
            outboundQueueStore = queue,
            savedStateHandle = SavedStateHandle(),
        ).also {
            it.samplerDispatcher = dispatcher
            it.outboundQueueDispatcher = dispatcher
            it.setSendWatchdogTimeoutForTest(null)
            viewModels += it
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
