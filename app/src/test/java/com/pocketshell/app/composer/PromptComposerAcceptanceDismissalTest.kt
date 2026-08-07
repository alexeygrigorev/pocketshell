package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * Issue #695/#2048: accepted empty composers dismiss promptly, while interaction
 * epochs keep a reopened or edited composer safe from older completion.
 *
 * The connected sibling mounts the real sheet and injects the reported 10s
 * callback. This deterministic matrix covers the state boundary itself:
 * accepted/empty, accepted then newly edited, failure before local acceptance,
 * and failure after acceptance with/without a newer draft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAcceptanceDismissalTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDown() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    @Test
    fun durableAcceptanceClosesEmptyComposerBeforeDeliveryResult() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("slow host prompt")

        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, queue.itemsFor(target.sessionKey).size)
        assertEquals(1, accepted.size)
        assertEquals(1, sent.size)
        assertTrue(vm.uiState.value.sendInFlight)
        assertTrue(vm.consumeHandoffAcceptanceForAutoClose(accepted.single()))
        assertTrue(vm.markSendDelivered(sent.single()))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun newDraftBeforeAcceptanceReductionRejectsDismissalAndSurvivesDelivery() = runTest {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue, drafts)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("submitted")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        assertEquals(1, accepted.size)

        vm.onDraftChange("new draft")
        assertFalse(vm.consumeHandoffAcceptanceForAutoClose(accepted.single()))
        vm.markSendDelivered(sent.single())

        assertEquals("new draft", vm.uiState.value.draft)
        assertEquals("new draft", drafts.load(target.sessionKey))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun alreadyDeliveredIdenticalDraftClosesAsAuthoritativeNoRowAcceptance() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("send again")
        vm.requestSend(true, target)
        advanceUntilIdle()
        assertEquals(1, accepted.size)
        assertTrue(accepted.single().outboundQueueItemId != null)
        vm.onDraftChange("send again")
        assertFalse(vm.markSendDelivered(sent.single()))
        assertEquals(sent.single(), vm.backgroundDeliveredRequest)
        assertFalse(vm.uiState.value.outboundHandoffInProgress)
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals("send again", vm.uiState.value.draft)

        vm.requestSend(true, target)
        advanceUntilIdle()
        runCurrent()

        assertEquals(2, accepted.size)
        assertEquals(1, sent.size)
        assertTrue(vm.consumeHandoffAcceptanceForAutoClose(accepted.last()))
        assertEquals("", vm.uiState.value.draft)
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
        assertNull(accepted.last().outboundQueueItemId)
    }

    @Test
    fun mutationAfterAlreadyDeliveredDedupStillBlocksClose() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("send again")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.onDraftChange("send again")
        vm.markSendDelivered(sent.single())
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.onDraftChange("new work")

        assertFalse(vm.consumeHandoffAcceptanceForAutoClose(accepted.last()))
        assertEquals("new work", vm.uiState.value.draft)
    }

    @Test
    fun durableAcceptanceAndLateExactAckProduceExactlyOneCloseCallback() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("late ack")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        var closeCallbacks = 0
        if (vm.consumeHandoffAcceptanceForAutoClose(accepted.single())) closeCallbacks++
        val request = sent.single()
        queue.markWireSubmitAttempted(target.sessionKey, requireNotNull(request.outboundQueueItemId))
        val row = requireNotNull(queue.item(request.outboundQueueItemId))
        queue.markFailed(row.id, "bounded acknowledgement pending", 1L)

        assertTrue(vm.acknowledgeLateOutboundDeliveries(listOf(row)))
        assertEquals(
            "one durable row closes exactly once at acceptance, never again at late ack",
            1,
            closeCallbacks,
        )
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun lateAckPrunesWithoutTouchingHeldAttachmentPickerInteraction() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("sent before picker")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        val id = requireNotNull(request.outboundQueueItemId)
        queue.markWireSubmitAttempted(target.sessionKey, id)
        queue.markFailed(id, "bounded acknowledgement pending", 1L)
        val row = requireNotNull(queue.item(id))

        vm.onAttachmentPickIntent()

        assertTrue(vm.acknowledgeLateOutboundDeliveries(listOf(row)))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun lateAckPrunesWithoutTouchingHeldMicPermissionInteraction() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("sent before permission")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        val id = requireNotNull(request.outboundQueueItemId)
        queue.markWireSubmitAttempted(target.sessionKey, id)
        queue.markFailed(id, "bounded acknowledgement pending", 1L)
        val row = requireNotNull(queue.item(id))

        vm.onMicStartIntent()

        assertTrue(vm.acknowledgeLateOutboundDeliveries(listOf(row)))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun deferredAmbiguousAttemptIsPrunedByExactLateAckWithoutASecondClose() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("ambiguous then late")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        queue.markWireSubmitAttempted(target.sessionKey, requireNotNull(request.outboundQueueItemId))

        vm.markOutboundSendDeferred(request)
        val deferred = requireNotNull(queue.item(request.outboundQueueItemId))
        assertEquals(OutboundState.Queued, deferred.state)

        assertTrue("the exact late ack prunes the accepted durable row", vm.acknowledgeLateOutboundDeliveries(listOf(deferred)))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
    }

    @Test
    fun delayedAckFromSessionACannotCloseUntouchedSessionBComposer() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val targetA = target()
        vm.onComposerTargetChanged(targetA.sessionKey)
        vm.onDraftChange("sent from A")
        vm.requestSend(true, targetA)
        advanceUntilIdle()
        val requestA = sent.single()

        vm.onComposerTargetChanged("1/session-b")

        assertFalse("A's delayed ack must not dismiss B's empty composer", vm.markSendDelivered(requestA))
        assertEquals("1/session-b", vm.composerTarget)
    }

    @Test
    fun unchangedEmptyComposerStillClosesOnSameTargetAck() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("close after delivery")
        vm.requestSend(true, target)
        advanceUntilIdle()

        assertTrue(vm.markSendDelivered(sent.single()))
    }

    @Test
    fun sameTargetReconnectWithoutNewContentStillClosesOnAck() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("survive reconnect")
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.onComposerTargetChanged(target.sessionKey)

        assertTrue(vm.markSendDelivered(sent.single()))
    }

    @Test
    fun transientFallbackToDurableTargetRebindDoesNotDefeatCloseWithoutNewContent() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("close after identity settles")
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.onComposerTargetChanged("fallback:${target.sessionKey}")
        vm.onComposerTargetChanged(target.sessionKey)

        assertTrue(vm.markSendDelivered(sent.single()))
    }

    @Test
    fun attachmentAddedAfterSendBlocksDelayedAckClose() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("sent first")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.attachFiles(1) { Result.success(listOf("/remote/new-after-send.txt")) }
        advanceUntilIdle()

        assertFalse(vm.markSendDelivered(sent.single()))
        assertEquals(listOf("new-after-send.txt"), vm.uiState.value.attachments.map { it.displayName })
    }

    @Test
    fun beginningDictationAfterSendBlocksDelayedAckClose() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("sent first")
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.onMicTap()
        runCurrent()
        vm.cancelRecording()
        advanceUntilIdle()

        assertFalse(vm.markSendDelivered(sent.single()))
        assertEquals(PromptComposerViewModel.RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun failedAttachmentIntentStillBlocksDelayedAckAfterReturningEmpty() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("sent first")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.attachFiles(1) { Result.failure(IllegalStateException("picker upload failed")) }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertFalse(vm.markSendDelivered(sent.single()))
    }

    @Test
    fun authoritativeTurnoverAmbiguityOnWritableWireDoesNotBurnAttemptBudget() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        vm.setTransportWritableProbe { true }
        val target = target()
        queue.enqueueExisting(
            OutboundItem(
                id = "busy-row",
                sessionKey = target.sessionKey,
                cleanText = "busy agent",
                createdAtMs = 1L,
            ),
        )
        backgroundScope.launch {
            collectPromptComposerSendRequests(
                viewModel = vm,
                onSend = { ComposerSendResult.AuthoritativeAckPending },
            )
        }
        runCurrent()
        vm.onComposerTargetChanged(target.sessionKey)
        requireNotNull(vm.retryNextOutboundItem())
        advanceUntilIdle()

        val deferred = queue.itemsFor(target.sessionKey).single()
        assertEquals("turnover ambiguity is not a transport-failure budget attempt", 0, deferred.attemptCount)
        assertEquals(OutboundState.Queued, deferred.state)
    }

    @Test
    fun pendingAckRefundsBudgetButManualRetryAdvancesDurableWireGeneration() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val target = target()
        val row = queue.enqueue(sessionKey = target.sessionKey, cleanText = "generation", createdAtMs = 1L)
        var sendCalls = 0
        backgroundScope.launch {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                sendCalls++
                queue.markWireSubmitAttempted(
                    request.sendTarget.sessionKey,
                    requireNotNull(request.outboundQueueItemId),
                )
                ComposerSendResult.AuthoritativeAckPending
            })
        }
        runCurrent()
        vm.onComposerTargetChanged(target.sessionKey)
        requireNotNull(vm.retryNextOutboundItem())
        advanceUntilIdle()
        val generationOne = requireNotNull(queue.item(row.id))
        assertEquals(0, generationOne.attemptCount)
        assertEquals(1, generationOne.wireAttemptGeneration)

        vm.retryOutboundItem(row.id)
        advanceUntilIdle()
        var settleTurns = 0
        while (sendCalls < 2 || vm.uiState.value.sendInFlight) {
            check(settleTurns++ < 10) { "manual Retry never settled after AuthoritativeAckPending" }
            runCurrent()
            advanceTimeBy(1)
        }
        assertEquals(2, sendCalls)
        assertFalse(vm.uiState.value.sendInFlight)
        val generationTwo = requireNotNull(queue.item(row.id))
        assertEquals(OutboundState.Queued, generationTwo.state)
        assertEquals(0, generationTwo.attemptCount)
        assertEquals(2, generationTwo.wireAttemptGeneration)
        assertFalse(
            queue.acknowledgeLateDelivered(
                generationOne.id,
                generationOne.sendKey,
                generationOne.wireAttemptGeneration,
            ),
        )
        assertTrue(
            queue.acknowledgeLateDelivered(
                generationTwo.id,
                generationTwo.sendKey,
                generationTwo.wireAttemptGeneration,
            ),
        )
    }

    @Test
    fun genuineFailureOnWritableWireStillParksAfterBoundedAttempts() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        vm.setTransportWritableProbe { true }
        val target = target()
        queue.enqueue(sessionKey = target.sessionKey, cleanText = "poison", createdAtMs = 1L)
        val tail = queue.enqueue(sessionKey = target.sessionKey, cleanText = "healthy tail", createdAtMs = 2L)
        val attempted = mutableListOf<String>()
        val consumer = backgroundScope.launch {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                attempted += request.cleanDraft
                if (request.cleanDraft == "poison") ComposerSendResult.Failed else ComposerSendResult.Delivered
            })
        }
        runCurrent()
        vm.onComposerTargetChanged(target.sessionKey)

        requireNotNull(vm.retryNextOutboundItem())
        advanceUntilIdle()
        assertEquals(listOf("poison"), attempted)
        assertEquals(1, requireNotNull(queue.itemsFor(target.sessionKey).first { it.id != tail.id }).attemptCount)
        val poisonId = queue.itemsFor(target.sessionKey).first { it.id != tail.id }.id
        repeat(OUTBOUND_MAX_AUTO_ATTEMPTS - 1) {
            requireNotNull(queue.claim(poisonId))
            requireNotNull(queue.requeueForRetry(poisonId))
        }
        consumer.cancelAndJoin()
        val replacement = newVm(StandardTestDispatcher(testScheduler), queue)
        replacement.setTransportWritableProbe { true }
        replacement.onComposerTargetChanged(target.sessionKey)
        backgroundScope.launch {
            collectPromptComposerSendRequests(replacement, onSend = { request ->
                attempted += request.cleanDraft
                ComposerSendResult.Delivered
            })
        }
        runCurrent()
        advanceUntilIdle()
        assertNull(replacement.retryNextOutboundItem())
        assertNull("the poison head must not starve its healthy tail", queue.item(tail.id))

        val parked = queue.itemsFor(target.sessionKey).single()
        assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, parked.attemptCount)
        assertEquals(OutboundState.Failed, parked.state)
        assertEquals(listOf("poison", "healthy tail"), attempted)
    }

    @Test
    fun lateExactAckPrunesDeliveredRowButNeverClosesOverNewDraft() = runTest {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue, drafts)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("submitted")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        queue.markWireSubmitAttempted(target.sessionKey, requireNotNull(request.outboundQueueItemId))
        val row = requireNotNull(queue.item(request.outboundQueueItemId))
        queue.markFailed(row.id, "bounded acknowledgement pending", 1L)
        vm.onDraftChange("new draft")

        assertTrue(vm.acknowledgeLateOutboundDeliveries(listOf(row)))
        assertTrue(queue.itemsFor(target.sessionKey).isEmpty())
        assertEquals("new draft", vm.uiState.value.draft)
        assertEquals("new draft", drafts.load(target.sessionKey))
    }

    @Test
    fun manualRetryGenerationBetweenLateResolveAndPruneDoesNotConsumeAuthority() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("race with Retry")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val request = sent.single()
        val id = requireNotNull(request.outboundQueueItemId)
        queue.markWireSubmitAttempted(target.sessionKey, id)
        queue.markFailed(id, "late turnover")
        val resolvedAttemptOne = requireNotNull(queue.item(id))
        var consumed = 0

        assertFalse(
            vm.acknowledgeLateOutboundDeliveries(
                listOf(resolvedAttemptOne),
                beforePrune = {
                    requireNotNull(queue.requeueForRetry(id))
                    requireNotNull(queue.claim(id))
                    queue.markWireSubmitAttempted(target.sessionKey, id)
                },
                onAcknowledged = { consumed++ },
            ),
        )

        val retryGeneration = requireNotNull(queue.item(id))
        assertEquals(resolvedAttemptOne.id, retryGeneration.id)
        assertEquals(resolvedAttemptOne.sendKey, retryGeneration.sendKey)
        assertEquals(2, retryGeneration.attemptCount)
        assertEquals(
            resolvedAttemptOne.wireAttemptGeneration + 1,
            retryGeneration.wireAttemptGeneration,
        )
        assertEquals("a rejected stale prune cannot consume authority", 0, consumed)
    }

    @Test
    fun confirmedBacklogBatchIsPrunedBeforeFifoRestartsOnce() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        fun attempted(id: String, createdAt: Long): OutboundItem {
            val queued = queue.enqueueExisting(
                OutboundItem(
                    id = id,
                    sessionKey = target.sessionKey,
                    cleanText = id,
                    paneId = target.paneId,
                    sendKey = "key-$id",
                    createdAtMs = createdAt,
                ),
            )
            val claimed = requireNotNull(queue.claim(queued.id))
            queue.markWireSubmitAttempted(target.sessionKey, queued.id)
            queue.markFailed(queued.id, "turnover timed out")
            return requireNotNull(queue.item(claimed.id))
        }
        val first = attempted("confirmed-1", 1L)
        val second = attempted("confirmed-2", 2L)
        queue.enqueueExisting(
            OutboundItem(
                id = "unconfirmed-3",
                sessionKey = target.sessionKey,
                cleanText = "third",
                paneId = target.paneId,
                sendKey = "key-3",
                createdAtMs = 3L,
            ),
        )

        assertTrue(vm.acknowledgeLateOutboundDeliveries(listOf(first, second)))
        advanceUntilIdle()

        assertNull(queue.item(first.id))
        assertNull(queue.item(second.id))
        assertEquals(
            "FIFO must restart only after every already-confirmed row is gone",
            listOf("unconfirmed-3"),
            sent.mapNotNull { it.outboundQueueItemId },
        )
        assertEquals(1, queue.item("unconfirmed-3")!!.attemptCount)
    }

    @Test
    fun failureBeforeLocalAcceptanceEmitsNoDismissalAndRestoresSubmittedDraft() = runTest {
        val vm = newVm(
            dispatcher = StandardTestDispatcher(testScheduler),
            queue = DisabledOutboundQueueStore,
        )
        val accepted = collectAcceptances(vm)
        vm._sendRequests.close()
        vm.onDraftChange("not accepted")

        vm.requestSend(withEnter = true)
        runCurrent()

        assertTrue(accepted.isEmpty())
        assertEquals("not accepted", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sendInFlight)
        assertTrue(vm.uiState.value.error.orEmpty().contains("Not sent"))
    }

    @Test
    fun failureAfterAcceptanceKeepsRowQueuedAndDoesNotRestoreOverEmptyEditor() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue)
        val accepted = collectAcceptances(vm)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("accepted then offline")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        vm.markOutboundSendDeferred(sent.single(), resetAttemptBudget = true)

        assertEquals(1, accepted.size)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(OutboundState.Queued, queue.itemsFor(target.sessionKey).single().state)
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun failureAfterAcceptanceNeverOverwritesNewDraft() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val drafts = InMemoryComposerDraftStore()
        val vm = newVm(StandardTestDispatcher(testScheduler), queue, drafts)
        val sent = collectSends(vm)
        val target = target()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("accepted prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.onDraftChange("new work")

        vm.markOutboundSendDeferred(sent.single(), resetAttemptBudget = true)

        assertEquals("new work", vm.uiState.value.draft)
        assertEquals("new work", drafts.load(target.sessionKey))
        assertEquals(OutboundState.Queued, queue.itemsFor(target.sessionKey).single().state)
    }

    @Test
    fun deliveryBarrierReleasesAfterDecisionAndWhenScreenOwnerDisposes() = runTest {
        val coordinator = ComposerHandoffAcceptanceCoordinator()
        val decided = ComposerHandoffAcceptance("1/session-a", 1L, "decided")
        val disposed = ComposerHandoffAcceptance("1/session-a", 2L, "disposed")
        var decidedDeliveryReleased = false
        var disposedDeliveryReleased = false
        coordinator.publish(decided)
        coordinator.publish(disposed)
        backgroundScope.launch {
            coordinator.awaitReduction(decided.outboundQueueItemId)
            decidedDeliveryReleased = true
        }
        backgroundScope.launch {
            coordinator.awaitReduction(disposed.outboundQueueItemId)
            disposedDeliveryReleased = true
        }
        runCurrent()

        assertFalse(decidedDeliveryReleased)
        assertFalse(disposedDeliveryReleased)
        coordinator.completeReduction(decided.outboundQueueItemId)
        runCurrent()
        assertTrue(decidedDeliveryReleased)
        assertFalse(disposedDeliveryReleased)

        coordinator.completeAllReductions()
        runCurrent()
        assertTrue(disposedDeliveryReleased)
    }

    private fun kotlinx.coroutines.test.TestScope.collectAcceptances(
        vm: PromptComposerViewModel,
    ): MutableList<ComposerHandoffAcceptance> {
        val accepted = mutableListOf<ComposerHandoffAcceptance>()
        backgroundScope.launch { vm.handoffAcceptances.collect { accepted += it } }
        runCurrent()
        return accepted
    }

    private fun kotlinx.coroutines.test.TestScope.collectSends(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        return sent
    }

    private fun target() =
        PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

    private fun newVm(
        dispatcher: TestDispatcher,
        queue: OutboundQueueStore,
        drafts: ComposerDraftStore = InMemoryComposerDraftStore(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = object : PromptComposerViewModel.MicCapture {
                override fun start() = Unit
                override fun stop(): ByteArray = ByteArray(0)
                override fun currentAmplitude(): Float = 0f
            },
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(
                        audio: ByteArray,
                        language: String?,
                    ): Result<String> = Result.success("")
                }
            },
            apiKeyStorage = object : PromptComposerViewModel.ApiKeyVault {
                override fun save(key: CharArray) = Unit
                override fun load(): CharArray = "sk-test".toCharArray()
                override fun clear() = Unit
            },
            voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
                override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
                override fun whisperLanguageHint(): String? = null
                override fun transcriptionProvider(): VoiceTranscriptionProvider =
                    VoiceTranscriptionProvider.OpenAiWhisper
            },
            composerDraftStore = drafts,
            outboundQueueStore = queue,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        createdViewModels += vm
        return vm
    }
}
