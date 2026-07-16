package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #1621: enqueue a later composition while an earlier row is delivering. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerSendPipeliningTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var viewModel: PromptComposerViewModel? = null

    @After
    fun tearDown() {
        viewModel?.clearForTest()
        viewModel = null
    }

    @Test
    fun threePromptsCommitAndDeliverExactlyOnceInFifoThenQueueIsEmpty() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val commits = java.util.Collections.synchronizedList(mutableListOf<String>())
        vm.outboundHandoffCommitForTest = { commits += it.cleanText }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)

        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertTrue(vm.uiState.value.sendInFlight)
        assertFalse("A handoff must be complete", vm.outboundHandoffInProgress)

        vm.onDraftChange("prompt B")
        assertEquals("prompt B", vm.uiState.value.draft)
        assertFalse(
            "different clean text must have a different logical send key",
            queue.itemsFor(target.sessionKey).single().sendKey ==
                computeSendKey("prompt B", emptyList(), true, target),
        )
        vm.requestSend(withEnter = true, sendTarget = target)
        assertTrue("B must claim the enqueue handoff even while A delivers", vm.outboundHandoffInProgress)
        advanceUntilIdle()

        assertEquals("handoff failure: ${vm.uiState.value.error}", null, vm.uiState.value.error)
        assertEquals(listOf("prompt A", "prompt B"), commits)
        assertEquals(listOf("prompt A", "prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })
        assertEquals("", vm.uiState.value.draft)

        vm.markSendDelivered(sent[0])
        advanceUntilIdle()
        runCurrent()
        assertTrue("B must own wire delivery after FIFO claim", vm.uiState.value.sendInFlight)
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)
        assertEquals(listOf("prompt A", "prompt B"), sent.map { it.cleanDraft })

        vm.onDraftChange("prompt C")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        assertEquals(listOf("prompt B", "prompt C"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })

        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A", "prompt B", "prompt C"), sent.map { it.cleanDraft })
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)

        vm.markSendDelivered(sent[2])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1, "prompt C" to 1), sent.groupingBy { it.cleanDraft }.eachCount())
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(emptyList<PromptComposerViewModel.StagedAttachment>(), vm.uiState.value.attachments)
    }

    @Test
    fun unchangedDoubleTapDuringHandoffCommitsOneRow() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("same prompt")

        vm.requestSend(withEnter = true, sendTarget = target)
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals(1, queue.itemsFor(target.sessionKey).size)
        assertEquals("same prompt", queue.itemsFor(target.sessionKey).single().cleanText)
        assertEquals(listOf("same prompt"), sent.map { it.cleanDraft })
        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
        assertEquals(1, sent.size)
    }

    @Test
    fun unchangedRetryWhileOriginalOwnsWireDoesNotMintSecondRow() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("same prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val original = sent.single()

        vm.onDraftChange("same prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals(1, queue.itemsFor(target.sessionKey).size)
        assertEquals(original.outboundQueueItemId, queue.itemsFor(target.sessionKey).single().id)
        assertEquals(listOf("same prompt"), sent.map { it.cleanDraft })
        assertFalse(vm.uiState.value.outboundHandoffInProgress)

        vm.markSendDelivered(original)
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun unchangedRetryWhileOriginalOwnsWireAcknowledgesAndConsumesExactComposition() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("same prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val original = sent.single()

        vm.onDraftChange("same prompt")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        assertEquals("the accepted coalesced tap must consume its exact composition", "", vm.uiState.value.draft)
        assertEquals(1, queue.itemsFor(target.sessionKey).size)
        assertEquals(listOf("same prompt"), sent.map { it.cleanDraft })

        vm.onDraftChange("prompt C")
        vm.markSendDelivered(original)
        advanceUntilIdle()
        assertEquals("prompt C", vm.uiState.value.draft)
    }

    /**
     * Issue #1621: settle the dispatch/collector lanes WITHOUT draining virtual
     * time to infinity. The rest of this class disables the delivery watchdog
     * ([newVm] calls `setSendWatchdogTimeoutForTest(null)`) precisely because
     * `advanceUntilIdle()` would otherwise fire the ~140s watchdog and defer the
     * "in-flight" row out from under the scenario. The banner-Retry proofs below
     * need a REAL armed watchdog (its teardown is half of the bug), so they step
     * virtual time in small bounded increments that never reach the timeout.
     */
    private fun TestScope.settleWithoutFiringWatchdog() {
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()
    }

    /**
     * Issue #1621 (round-three review finding): the QUEUE BANNER's Retry, which
     * PR-4 newly makes reachable in the `[A InFlight, B Queued]` state.
     *
     * Production does not override `onRetryOutboundItem`, so the banner's Retry
     * lands on [PromptComposerViewModel.retryOutboundItem] — the entry point the
     * composer PILL never uses (the pill guards on `!sendInFlight`). Before the
     * fix, `retryOutboundItem` strand-cleared on bare `sendInFlight`, which
     * disarmed HEALTHY A's delivery watchdog, deferred A back to `Queued`, and let
     * B overtake A on the wire — FIFO broken and A's attempt possibly still live
     * server-side (the #1526 duplicate-delivery class).
     *
     * This is the load-bearing negative (G6): the assertion that must be GREEN is
     * "A still owns delivery", not a structural proxy.
     */
    @Test
    fun bannerRetryWhileHealthyAOwnsWireNeverStrandsAOrLetsBOvertake() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        // A REAL armed delivery watchdog (not the harness default of "disabled"), so
        // the watchdog-identity half of the ownership assertion is exercised for real.
        // Virtual time is stepped in 50ms increments below, never reaching this.
        vm.setSendWatchdogTimeoutForTest(600_000L)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)

        // A: sent, healthy, owns the wire.
        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleWithoutFiringWatchdog()
        val aRequest = sent.single()
        // B: typed + Sent while A is still delivering — PR-4's normal healthy state.
        vm.onDraftChange("prompt B")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleWithoutFiringWatchdog()
        val rows = queue.itemsFor(target.sessionKey)
        assertEquals(
            "precondition: the maintainer's [A InFlight, B Queued] banner state",
            listOf(OutboundState.InFlight, OutboundState.Queued),
            rows.map { it.state },
        )
        val bId = rows.last().id
        val aWatchdog = vm.deliveryWatchdogJobForTest()
        assertTrue("precondition: A's delivery watchdog is armed", aWatchdog?.isActive == true)

        // The user taps the collapsed banner's cyan Retry, which targets the oldest
        // retryable row — B — while A is healthy and mid-flight.
        assertEquals(
            "precondition: the banner's collapsed Retry targets B",
            bId,
            retryableOutboundQueueItem(vm.outboundQueueItems.value)?.id,
        )
        vm.retryOutboundItem(bId)
        settleWithoutFiringWatchdog()

        // A keeps delivery ownership: request identity, gate, row state, watchdog.
        assertTrue("A must keep the delivery gate", vm.uiState.value.sendInFlight)
        assertEquals(
            "A must keep in-flight request identity — a tap meant for the queued " +
                "tail must not re-own delivery",
            aRequest.outboundQueueItemId,
            vm.inFlightSendRequest?.outboundQueueItemId,
        )
        assertTrue(
            "A's delivery watchdog must NOT be torn down by a tap on B",
            aWatchdog?.isActive == true,
        )
        assertEquals(
            "A's delivery watchdog identity must be unchanged",
            aWatchdog,
            vm.deliveryWatchdogJobForTest(),
        )
        // B does not overtake A on the wire.
        assertEquals(
            "B must NOT be emitted while A owns the wire — FIFO",
            listOf("prompt A"),
            sent.map { it.cleanDraft },
        )
        assertEquals(
            "A must stay InFlight (not deferred back to Queued) and B stay Queued",
            listOf(OutboundState.InFlight, OutboundState.Queued),
            queue.itemsFor(target.sessionKey).map { it.state },
        )
        assertEquals("no second row may be minted", 2, queue.itemsFor(target.sessionKey).size)

        // A completes → B drains next, in order, exactly once each.
        vm.markSendDelivered(aRequest)
        settleWithoutFiringWatchdog()
        assertEquals(listOf("prompt A", "prompt B"), sent.map { it.cleanDraft })
        vm.markSendDelivered(sent[1])
        settleWithoutFiringWatchdog()
        assertEquals(
            mapOf("prompt A" to 1, "prompt B" to 1),
            sent.groupingBy { it.cleanDraft }.eachCount(),
        )
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    /**
     * Issue #1621 class coverage (G2), the reviewer's `Failed`-tail variant: the
     * #1602 PARK case must still be retryable from the banner while A is healthy.
     * A parked (`Failed`, auto-retry-exhausted) tail is SKIPPED by the auto-flush
     * drain, so a Retry that merely declined to act would leave B permanently
     * un-sendable. The tap must re-arm B (Queued, budget reset, error cleared) —
     * WITHOUT touching A — and B must then drain FIFO once A completes.
     */
    @Test
    fun bannerRetryOnParkedFailedTailReArmsItWithoutDisturbingHealthyA() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        vm.setSendWatchdogTimeoutForTest(600_000L)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)

        // B is a PARKED tail: Failed, surfaced, auto-retry budget exhausted.
        val b = queue.enqueue(sessionKey = target.sessionKey, cleanText = "parked B", createdAtMs = 2)
        repeat(OUTBOUND_MAX_AUTO_ATTEMPTS) { queue.claim(b.id); queue.requeueForRetry(b.id) }
        queue.markFailed(b.id, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
        // A is composed after B but owns the wire (a healthy live delivery).
        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleWithoutFiringWatchdog()
        val aRequest = sent.single()
        assertEquals("precondition: A is the row on the wire", "prompt A", aRequest.cleanDraft)
        assertTrue("precondition: A owns the wire", vm.uiState.value.sendInFlight)
        assertEquals(
            "precondition: B is parked Failed behind the healthy A",
            OutboundState.Failed,
            queue.item(b.id)!!.state,
        )
        val aWatchdog = vm.deliveryWatchdogJobForTest()

        vm.retryOutboundItem(b.id)
        settleWithoutFiringWatchdog()

        // A untouched.
        assertTrue("A must keep the delivery gate", vm.uiState.value.sendInFlight)
        assertEquals(
            aRequest.outboundQueueItemId,
            vm.inFlightSendRequest?.outboundQueueItemId,
        )
        assertEquals("A's watchdog identity must survive", aWatchdog, vm.deliveryWatchdogJobForTest())
        assertEquals(
            "B must not be emitted ahead of A",
            listOf("prompt A"),
            sent.map { it.cleanDraft },
        )
        // B re-armed and drainable again (the #1602 recovery preserved).
        val reArmed = queue.item(b.id)!!
        assertEquals("the parked tail must be re-armed to Queued", OutboundState.Queued, reArmed.state)
        assertEquals("its surfaced error must be cleared", null, reArmed.lastError)
        assertTrue(
            "its bounded auto-retry budget must be re-granted so the drain does not re-park it",
            reArmed.attemptCount < OUTBOUND_MAX_AUTO_ATTEMPTS,
        )

        vm.markSendDelivered(aRequest)
        settleWithoutFiringWatchdog()
        assertEquals(
            "the re-armed tail drains once A completes",
            listOf("prompt A", "parked B"),
            sent.map { it.cleanDraft },
        )
        assertEquals(OutboundState.InFlight, queue.item(b.id)!!.state)
    }

    @Test
    fun timedOutSlowBHandoffNeverCancelsLaterCAttachmentOwner() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also {
            it.outboundQueueDispatcher = dispatcher
            it.setHandoffWatchdogTimeoutForTest(5_000L)
        }
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt B")
        val bCommitReached = CompletableDeferred<Unit>()
        val releaseBCommit = CompletableDeferred<Unit>()
        vm.outboundHandoffCommitForTest = {
            bCommitReached.complete(Unit)
            releaseBCommit.await()
        }
        vm.requestSend(withEnter = true, sendTarget = target)
        runCurrent()
        assertTrue(bCommitReached.isCompleted)

        val cStage = CompletableDeferred<Result<List<String>>>()
        vm.onDraftChange("prompt C")
        vm.attachFiles(count = 1) { cStage.await() }
        runCurrent()
        val cAttachmentOwner = vm.attachmentJob
        assertTrue(cAttachmentOwner?.isActive == true)

        testScheduler.advanceTimeBy(5_001L)
        runCurrent()
        assertTrue("B timeout must not cancel C's newer staging owner", cAttachmentOwner?.isActive == true)
        assertTrue("the global attachment owner must still be C", vm.attachmentJob === cAttachmentOwner)

        cStage.complete(Result.success(listOf("/tmp/c.txt")))
        advanceUntilIdle()
        assertEquals("prompt C", vm.uiState.value.draft)
        assertEquals(listOf("/tmp/c.txt"), vm.uiState.value.attachments.map { it.remotePath })
        assertFalse(vm.isAttachmentJobActiveForTest())
    }

    @Test
    fun bRemoteAttachmentStagingTimeoutNeverReplacesOrCancelsAOwners() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also {
            it.outboundQueueDispatcher = dispatcher
            it.setSendWatchdogTimeoutForTest(60_000L)
            it.setHandoffWatchdogTimeoutForTest(5_000L)
        }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        runCurrent()

        val aWatchdog = vm.deliveryWatchdogJobForTest()
        val aRequest = vm.inFlightSendRequest
        val aRow = queue.itemsFor(target.sessionKey).single()
        assertTrue(aWatchdog?.isActive == true)
        assertTrue(vm.uiState.value.sendInFlight)

        val remoteUpload = CompletableDeferred<Result<List<String>>>()
        vm.onDraftChange("prompt B")
        vm.attachFiles(count = 1) { remoteUpload.await() }
        runCurrent()
        assertTrue(vm.isAttachmentJobActiveForTest())
        vm.requestSend(withEnter = true, sendTarget = target)
        runCurrent()

        assertTrue(vm.handoffWatchdogJobForTest()?.isActive == true)
        assertTrue("A delivery watchdog identity must not change", vm.deliveryWatchdogJobForTest() === aWatchdog)
        assertTrue("A request identity must not change", vm.inFlightSendRequest === aRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(aRow.id, queue.itemsFor(target.sessionKey).single().id)
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })

        advanceTimeBy(5_001L)
        runCurrent()

        assertFalse(vm.outboundHandoffInProgress)
        assertFalse(vm.isAttachmentJobActiveForTest())
        assertEquals(PromptComposerViewModel.SEND_TIMEOUT_MESSAGE, vm.uiState.value.error)
        assertEquals("prompt B", vm.uiState.value.draft)
        assertTrue("B timeout must preserve A watchdog identity", vm.deliveryWatchdogJobForTest() === aWatchdog)
        assertTrue(aWatchdog?.isActive == true)
        assertTrue(vm.inFlightSendRequest === aRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)

        vm.markSendDelivered(aRequest)
        runCurrent()
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun attachmentOnlyAndTextRemoteRowsQueueBehindAAndDeliverFifo() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/only.pdf"))
        }
        runCurrent()
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.onDraftChange("review remote")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/report.txt"))
        }
        runCurrent()
        vm.requestSend(true, target)
        advanceUntilIdle()

        val rows = queue.itemsFor(target.sessionKey)
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued, OutboundState.Queued), rows.map { it.state })
        assertEquals("", rows[1].cleanText)
        assertEquals(listOf("only.pdf"), rows[1].attachments.map { it.displayName })
        assertEquals("review remote", rows[2].cleanText)
        assertEquals(listOf("report.txt"), rows[2].attachments.map { it.displayName })
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })

        vm.markSendDelivered(sent[0])
        advanceUntilIdle()
        runCurrent()
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        runCurrent()
        vm.markSendDelivered(sent[2])
        advanceUntilIdle()

        assertEquals(listOf("prompt A", "", "review remote"), sent.map { it.cleanDraft })
        assertEquals(listOf(0, 1, 1), sent.map { it.attachments.size })
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun localSidecarBCommitsQueuedButCannotUploadOrEmitUntilACompletes() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(queue, sidecars = sidecars).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val uploads = mutableListOf<String>()
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploads += refs.single().outboundItemId
            Result.success(refs.map { "~/.pocketshell/attachments/uploaded/${it.displayName}" })
        }
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()

        val picked = pickedFile("local.txt", "local bytes")
        vm.onDraftChange("prompt B local")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(picked, "text/plain")),
        ) { Result.failure(IllegalStateException("retain local pick")) }
        advanceUntilIdle()
        vm.requestSend(true, target)
        advanceUntilIdle()

        val rows = queue.itemsFor(target.sessionKey)
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), rows.map { it.state })
        assertTrue(sidecars.refsFor(rows[1].id).isNotEmpty())
        assertEquals(emptyList<String>(), uploads)
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertFalse(vm.outboundSidecarDispatchInFlight)

        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(rows[1].id), uploads)
        assertEquals(listOf("prompt A", "prompt B local"), sent.map { it.cleanDraft })
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun bQueuesWhileASidecarIsUploadingWithoutParallelUploadOrWire() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sidecars = newSidecarStore(dispatcher)
        val targetKey = "1/a"
        val a = OutboundItem(
            id = "uploading-a",
            sessionKey = targetKey,
            cleanText = "prompt A local",
            attachments = listOf(DurableAttachmentRef("stale", "a.txt", "text/plain")),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(a)
        sidecars.stage(a.id, listOf(pickedFile("a.txt", "A bytes")))
        val vm = newVm(queue, sidecars = sidecars).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val uploads = mutableListOf<String>()
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploads += refs.single().outboundItemId
            uploadStarted.complete(Unit)
            releaseUpload.await()
            Result.success(refs.map { "~/.pocketshell/attachments/uploaded/${it.displayName}" })
        }
        vm.onComposerTargetChanged(targetKey)
        assertEquals(a.id, vm.retryNextOutboundItem())
        runCurrent()
        assertTrue(uploadStarted.isCompleted)
        assertTrue(vm.outboundSidecarDispatchInFlight)
        assertEquals(OutboundState.Uploading, queue.item(a.id)?.state)

        val aSidecarOwner = vm.outboundSidecarDispatchInFlight
        vm.onDraftChange("prompt B")
        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey))
        runCurrent()

        assertTrue(aSidecarOwner && vm.outboundSidecarDispatchInFlight)
        assertEquals(listOf(a.id), uploads)
        assertEquals(emptyList<PromptComposerViewModel.SendRequest>(), sent)
        assertEquals(listOf(OutboundState.Uploading, OutboundState.Queued), queue.itemsFor(targetKey).map { it.state })
        assertEquals(listOf("prompt A local", "prompt B"), queue.itemsFor(targetKey).map { it.cleanText })

        releaseUpload.complete(Unit)
        runCurrent()
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A local"), sent.map { it.cleanDraft })
        assertEquals(OutboundState.InFlight, queue.item(a.id)?.state)
        assertEquals(OutboundState.Queued, queue.itemsFor(targetKey)[1].state)

        vm.markSendDelivered(sent[0])
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A local", "prompt B"), sent.map { it.cleanDraft })
        assertEquals(listOf(a.id), uploads)
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(targetKey))
    }

    @Test
    fun bEnqueueFailureReleasesOnlyHandoffAndPreservesDraftAndAOwners() = runTest {
        val queue = object : InMemoryOutboundQueueStore() {
            override fun enqueue(
                sessionKey: String,
                cleanText: String,
                attachments: List<DurableAttachmentRef>,
                withEnter: Boolean,
                createdAtMs: Long,
                paneId: String,
                route: OutboundRoute,
                agentKind: String?,
                sendKey: String,
            ): OutboundItem {
                if (cleanText == "prompt B") error("synthetic enqueue rejection")
                return super.enqueue(
                    sessionKey,
                    cleanText,
                    attachments,
                    withEnter,
                    createdAtMs,
                    paneId,
                    route,
                    agentKind,
                    sendKey,
                )
            }
        }
        val drafts = InMemoryComposerDraftStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue, drafts).also {
            it.outboundQueueDispatcher = dispatcher
            it.setSendWatchdogTimeoutForTest(60_000L)
            it.setHandoffWatchdogTimeoutForTest(10_000L)
        }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        runCurrent()
        val aWatchdog = vm.deliveryWatchdogJobForTest()
        val aRequest = vm.inFlightSendRequest

        vm.onDraftChange("prompt B")
        vm.requestSend(true, target)
        runCurrent()

        assertFalse(vm.outboundHandoffInProgress)
        assertEquals(null, vm.handoffWatchdogJobForTest())
        assertEquals("prompt B", vm.uiState.value.draft)
        assertEquals("prompt B", drafts.load(target.sessionKey))
        assertTrue(vm.uiState.value.error?.startsWith("Send failed") == true)
        assertTrue(vm.deliveryWatchdogJobForTest() === aWatchdog)
        assertTrue(aWatchdog?.isActive == true)
        assertTrue(vm.inFlightSendRequest === aRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertEquals(listOf("prompt A"), queue.itemsFor(target.sessionKey).map { it.cleanText })

        vm.markSendDelivered(aRequest)
        runCurrent()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
        assertEquals("prompt B", vm.uiState.value.draft)
    }

    @Test
    fun bSidecarStageFailureRetainsCompositionAndNeverMutatesAOwners() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val drafts = InMemoryComposerDraftStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(queue, drafts, sidecars).also {
            it.outboundQueueDispatcher = dispatcher
            it.setSendWatchdogTimeoutForTest(60_000L)
            it.setHandoffWatchdogTimeoutForTest(10_000L)
        }
        vm.setOutboundAttachmentSidecarUploader { refs ->
            Result.success(refs.map { "~/.pocketshell/attachments/${it.displayName}" })
        }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        runCurrent()
        val aWatchdog = vm.deliveryWatchdogJobForTest()
        val aRequest = vm.inFlightSendRequest

        val picked = pickedFile("vanishes.txt", "bytes before handoff")
        vm.onDraftChange("prompt B local")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(picked, "text/plain")),
        ) { Result.failure(IllegalStateException("retain locally")) }
        runCurrent()
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(1, drafts.loadAttachments(target.sessionKey).size)
        assertTrue(File(requireNotNull(picked.path)).delete())

        vm.requestSend(true, target)
        runCurrent()

        assertFalse(vm.outboundHandoffInProgress)
        assertEquals(null, vm.handoffWatchdogJobForTest())
        assertEquals("prompt B local", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals("prompt B local", drafts.load(target.sessionKey))
        assertEquals(1, drafts.loadAttachments(target.sessionKey).size)
        assertTrue(vm.uiState.value.error?.startsWith("Send failed") == true)
        assertTrue(vm.deliveryWatchdogJobForTest() === aWatchdog)
        assertTrue(aWatchdog?.isActive == true)
        assertTrue(vm.inFlightSendRequest === aRequest)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertEquals(listOf("prompt A"), queue.itemsFor(target.sessionKey).map { it.cleanText })
    }

    @Test
    fun emptyDraftDoesNothingAndDisabledStoreRemainsSingleActive() = runTest {
        val vm = newVm(DisabledOutboundQueueStore)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = ""))
        runCurrent()
        assertEquals(emptyList<PromptComposerViewModel.SendRequest>(), sent)

        vm.onDraftChange("legacy A")
        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = ""))
        runCurrent()
        vm.onDraftChange("legacy B")
        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = ""))
        runCurrent()
        assertEquals(listOf("legacy A"), sent.map { it.cleanDraft })
        assertEquals("legacy B", vm.uiState.value.draft)

        vm.markSendDelivered(sent.single())
        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = ""))
        runCurrent()
        assertEquals(listOf("legacy A", "legacy B"), sent.map { it.cleanDraft })
    }

    @Test
    fun recordingCompletionWhileAActiveQueuesTranscriptB() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val releaseTranscript = CompletableDeferred<Unit>()
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                releaseTranscript.await()
                return Result.success("voice prompt B")
            }
        }
        val mic = object : PromptComposerViewModel.MicCapture {
            override fun start() = Unit
            override fun stop(): ByteArray = SpeechAudioGuard.speechWavForTesting()
            override fun currentAmplitude(): Float = 0f
        }
        val vm = newVm(queue, mic = mic, whisper = whisper).also {
            it.outboundQueueDispatcher = dispatcher
            it.samplerDispatcher = dispatcher
        }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()

        vm.onMicTap()
        runCurrent()
        vm.requestSend(true, target)
        runCurrent()
        releaseTranscript.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertEquals(listOf("prompt A", "voice prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })
        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A", "voice prompt B"), sent.map { it.cleanDraft })
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun crossTargetBStaysQueuedUntilAResolvesThenCurrentTargetDrains() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val targetA = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        val targetB = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/b")
        vm.onComposerTargetChanged(targetA.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, targetA)
        advanceUntilIdle()
        vm.onComposerTargetChanged(targetB.sessionKey)
        vm.onDraftChange("prompt B")
        vm.requestSend(true, targetB)
        advanceUntilIdle()

        assertEquals(OutboundState.InFlight, queue.itemsFor(targetA.sessionKey).single().state)
        assertEquals(OutboundState.Queued, queue.itemsFor(targetB.sessionKey).single().state)
        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A", "prompt B"), sent.map { it.cleanDraft })
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(targetA.sessionKey))
        assertEquals(OutboundState.InFlight, queue.itemsFor(targetB.sessionKey).single().state)
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(targetB.sessionKey))
    }

    @Test
    fun deferAndReconnectReuseStableIdsAndPreserveAThenBFifo() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.onDraftChange("prompt B")
        vm.requestSend(true, target)
        advanceUntilIdle()
        val originalIds = queue.itemsFor(target.sessionKey).map { it.id }

        vm.markOutboundSendDeferred(sent[0])
        assertEquals(originalIds, queue.itemsFor(target.sessionKey).map { it.id })
        assertEquals(listOf(OutboundState.Queued, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })
        assertFalse(vm.uiState.value.sendInFlight)

        assertEquals(originalIds[0], vm.retryNextOutboundItem())
        runCurrent()
        assertEquals(listOf("prompt A", "prompt A"), sent.map { it.cleanDraft })
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A", "prompt A", "prompt B"), sent.map { it.cleanDraft })
        assertEquals(originalIds[1], sent[2].outboundQueueItemId)
        vm.markSendDelivered(sent[2])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun unavailableChannelRequeuesOnlyBWhenACompletes() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.onDraftChange("prompt B")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm._sendRequests.close()

        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        runCurrent()

        assertEquals(listOf("prompt A"), sent.map { it.cleanDraft })
        assertEquals(listOf("prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(OutboundState.Queued, queue.itemsFor(target.sessionKey).single().state)
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(null, vm.inFlightSendRequest)
    }

    @Test
    fun delayedCommitNeverClearsLaterComposition() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val drafts = InMemoryComposerDraftStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue, drafts).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        val bCommitted = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        vm.outboundHandoffCommitForTest = { item ->
            if (item.cleanText == "prompt B") {
                bCommitted.complete(Unit)
                releaseB.await()
            }
        }
        vm.onComposerTargetChanged(target.sessionKey)

        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.onDraftChange("prompt B")
        vm.requestSend(withEnter = true, sendTarget = target)
        runCurrent()
        assertTrue(bCommitted.isCompleted)

        vm.onDraftChange("prompt C")
        releaseB.complete(Unit)
        advanceUntilIdle()

        assertEquals("prompt C", vm.uiState.value.draft)
        assertEquals("prompt C", drafts.load(target.sessionKey))
        assertEquals(listOf("prompt A", "prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })

        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        assertEquals(listOf("prompt A", "prompt B", "prompt C"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        vm.markSendDelivered(sent[0])
        advanceUntilIdle()
        runCurrent()
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        runCurrent()
        vm.markSendDelivered(sent[2])
        advanceUntilIdle()
        assertEquals(listOf("prompt A", "prompt B", "prompt C"), sent.map { it.cleanDraft })
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
    }

    @Test
    fun aCompletesBeforeBCommitThenBCommitKicksIdleDrainExactlyOnce() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        val bCommitted = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(true, target)
        advanceUntilIdle()
        vm.outboundHandoffCommitForTest = { item ->
            if (item.cleanText == "prompt B") {
                bCommitted.complete(Unit)
                releaseB.await()
            }
        }
        vm.onDraftChange("prompt B")
        vm.requestSend(true, target)
        runCurrent()
        assertTrue(bCommitted.isCompleted)
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })

        vm.markSendDelivered(sent.single())
        runCurrent()
        assertFalse(vm.uiState.value.sendInFlight)
        assertEquals(listOf("prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(OutboundState.Queued, queue.itemsFor(target.sessionKey).single().state)

        releaseB.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf("prompt A", "prompt B"), sent.map { it.cleanDraft })
        assertEquals(OutboundState.InFlight, queue.itemsFor(target.sessionKey).single().state)
        vm.markSendDelivered(sent[1])
        advanceUntilIdle()
        assertEquals(emptyList<OutboundItem>(), queue.itemsFor(target.sessionKey))
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1), sent.groupingBy { it.cleanDraft }.eachCount())
    }

    @Test
    fun failureOfActivePromptDoesNotStompLaterQueuedPrompt() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue).also { it.outboundQueueDispatcher = dispatcher }
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange("prompt A")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        vm.onDraftChange("prompt B")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()

        vm.restoreFailedSend(sent.single())

        assertEquals("", vm.uiState.value.draft)
        assertEquals(listOf("prompt A", "prompt B"), queue.itemsFor(target.sessionKey).map { it.cleanText })
        assertEquals(listOf(OutboundState.Failed, OutboundState.Queued), queue.itemsFor(target.sessionKey).map { it.state })
    }

    @Test
    fun delayedCommitOnOldTargetNeverClearsNewTargetComposition() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val drafts = InMemoryComposerDraftStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = newVm(queue, drafts).also { it.outboundQueueDispatcher = dispatcher }
        val targetA = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a")
        val targetB = "1/b"
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        vm.outboundHandoffCommitForTest = { item ->
            if (item.cleanText == "old-target prompt") {
                commitReached.complete(Unit)
                releaseCommit.await()
            }
        }
        vm.onComposerTargetChanged(targetA.sessionKey)
        vm.onDraftChange("old-target prompt")
        vm.requestSend(withEnter = true, sendTarget = targetA)
        runCurrent()
        assertTrue(commitReached.isCompleted)

        vm.onComposerTargetChanged(targetB)
        vm.onDraftChange("new-target prompt")
        releaseCommit.complete(Unit)
        advanceUntilIdle()

        assertEquals(targetB, vm.composerTarget)
        assertEquals("new-target prompt", vm.uiState.value.draft)
        assertEquals("new-target prompt", drafts.load(targetB))
        assertEquals("", drafts.load(targetA.sessionKey).orEmpty())
        assertEquals("old-target prompt", queue.itemsFor(targetA.sessionKey).single().cleanText)
    }

    private fun newVm(
        queue: OutboundQueueStore,
        drafts: ComposerDraftStore = InMemoryComposerDraftStore(),
        sidecars: OutboundAttachmentSidecarStore? = null,
        mic: PromptComposerViewModel.MicCapture = object : PromptComposerViewModel.MicCapture {
            override fun start() = Unit
            override fun stop(): ByteArray = ByteArray(0)
            override fun currentAmplitude(): Float = 0f
        },
        whisper: WhisperClient = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                Result.success("")
        },
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = object : ApiKeyVault {
                override fun save(key: CharArray) = Unit
                override fun load(): CharArray = "sk-test".toCharArray()
                override fun clear() = Unit
            },
            voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
                override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
                override fun whisperLanguageHint(): String? = null
            },
            composerDraftStore = drafts,
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
            savedStateHandle = SavedStateHandle(),
        )
        vm.setSendWatchdogTimeoutForTest(null)
        viewModel = vm
        return vm
    }

    private fun newSidecarStore(dispatcher: TestDispatcher): OutboundAttachmentSidecarStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also {
            it.idGenerator = { "pipeline-sidecar-${++nextId}" }
            it.clock = { nextId.toLong() }
            it.ioDispatcher = dispatcher
        }
    }

    private fun pickedFile(name: String, content: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Uri.fromFile(File(File(context.cacheDir, "pipeline-picks"), name).apply {
            parentFile?.mkdirs()
            writeText(content)
        })
    }
}
