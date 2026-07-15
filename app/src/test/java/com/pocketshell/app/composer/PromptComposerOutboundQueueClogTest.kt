package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1602: clogged outbound queue after reconnect. Residual from the #1562
 * message-queue epic (root reconnect-storm fixed in #1567/#1568): after a
 * reconnect the outbound queue can CLOG. Two mechanisms, both reproduced RED on
 * base and proven GREEN with the fix:
 *
 *  - **Head-of-line block.** The auto-flush drain always re-picks the OLDEST
 *    retryable row ([firstComposerQueueRetryable]), with no attempt bound, so a
 *    permanently-failing HEAD is re-selected forever and a healthy TAIL behind
 *    it never gets a delivery attempt. Fix: a head that exhausts its bounded
 *    auto-retries is PARKED (`Failed`, surfaced) and SKIPPED so the tail drains.
 *  - **Silent Retry no-op.** While a wedged head holds `sendInFlight`, a user
 *    tapping Retry hits `dispatchOutboundItem` returning false — nothing moves.
 *    Fix: a manual Retry breaks the strand and produces a real delivery attempt.
 *
 * These are pure JVM ViewModel tests (gate-wired in the `Unit tests` job). The
 * exactly-once (#1529) / coalesce (#961) invariants are asserted preserved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerOutboundQueueClogTest {

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
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    private fun fakeWhisperClient(): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
            Result.success("hello world")
    }

    private fun newVm(
        outboundQueueStore: OutboundQueueStore,
    ): PromptComposerViewModel {
        val dispatcher = StandardTestDispatcher()
        val vm = PromptComposerViewModel(
            audioRecorder = MinimalMicCapture(),
            whisperClientFactory = WhisperClientFactory { fakeWhisperClient() },
            apiKeyStorage = FakeVault("sk-test".toCharArray()),
            voiceSettings = MinimalVoiceSettings(),
            outboundQueueStore = outboundQueueStore,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        // Bind the outbound-queue dispatch onto the test scheduler so the launched
        // claim/emit coroutine drains under advanceUntilIdle.
        vm.outboundQueueDispatcher = dispatcher
        // Disable the ~140s overall-send watchdog by default so its `delay` does not
        // fire during the test's virtual-time advances (the wedge is modelled by an
        // explicit markOutboundSendDeferred, the exact production watchdog/drop path).
        vm.setSendWatchdogTimeoutForTest(null)
        createdViewModels += vm
        return vm
    }

    private fun TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val collected = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch { vm.sendRequests.collect { collected += it } }
        runCurrent()
        return collected
    }

    /**
     * The send-request collector resumes on the Main test dispatcher after a
     * channel emission, so a plain [advanceUntilIdle] alone does not always
     * observe the newest element. Pump virtual time + yield to real dispatchers
     * until the predicate holds; HARD-FAIL on timeout (never a self-skip).
     */
    private suspend fun TestScope.settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            runCurrent()
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return
            withContext(Dispatchers.IO) { }
            if (predicate()) return
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                assertTrue("settleUntil timed out before the predicate held (#1602)", predicate())
                return
            }
        }
    }

    @Test
    fun permanentlyFailingHeadDoesNotBlockHealthyTail() = runTest {
        // RED on base: the drain always re-picks the oldest retryable row, so the
        // permanently-failing HEAD blocks the healthy TAIL forever (no delivery
        // attempt for the tail across any number of reconnect flushes). GREEN: once
        // the head exhausts its bounded auto-retries it is PARKED (Failed, surfaced)
        // and SKIPPED, so the tail drains.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(outboundQueueStore = queue)
        val sent = collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "HEAD (permanently stuck)", createdAtMs = 1)
        val tail = queue.enqueue(sessionKey = session, cleanText = "TAIL (healthy)", createdAtMs = 2)
        vm.refreshOutboundQueueItemsFor(session)

        var tailDispatched = false
        repeat(50) {
            val flushedId = vm.retryNextOutboundItem()
            advanceUntilIdle()
            if (flushedId == tail.id) {
                tailDispatched = true
                return@repeat
            }
            if (flushedId == head.id) {
                // The head's delivery never acks (a wedged send). Resolve it the way
                // the ~140s send watchdog / a real drop does: defer it back to the
                // queue for auto-retry, re-arming it at the front of the queue.
                val request = vm.inFlightSendRequest ?: return@repeat
                vm.markOutboundSendDeferred(request)
                advanceUntilIdle()
            }
        }

        assertTrue(
            "the healthy TAIL must get a delivery attempt even though the HEAD " +
                "permanently fails — a stuck head must not block following sends (#1602)",
            tailDispatched,
        )
        settleUntil { sent.any { it.outboundQueueItemId == tail.id } }
        assertTrue(
            "the healthy tail's SendRequest must have been emitted",
            sent.any { it.outboundQueueItemId == tail.id },
        )
        // The permanently-failing head is PARKED + SURFACED (Failed) — not silently
        // dropped, and not left Queued (which would re-block on the next cycle).
        assertEquals(OutboundState.Failed, requireNotNull(queue.item(head.id)).state)
    }

    @Test
    fun manualRetryReDrivesCloggedQueueInsteadOfSilentNoOp() = runTest {
        // RED on base: while a wedged HEAD send holds the `sendInFlight` gate, the
        // user tapping Retry on any row is a SILENT NO-OP — retryOutboundItem →
        // dispatchOutboundItem returns false and NO delivery attempt is produced.
        // GREEN: the manual Retry breaks the strand and produces a delivery attempt.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(outboundQueueStore = queue)
        val sent = collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "HEAD wedged", createdAtMs = 1)
        val tail = queue.enqueue(sessionKey = session, cleanText = "TAIL to retry", createdAtMs = 2)
        vm.refreshOutboundQueueItemsFor(session)

        // Head goes in-flight and WEDGES (never acked) — it holds the gate.
        val flushedId = vm.retryNextOutboundItem()
        advanceUntilIdle()
        assertEquals(head.id, flushedId)
        assertTrue("the wedged head holds the sendInFlight gate", vm.uiState.value.sendInFlight)
        val sentBefore = sent.size

        // User taps Retry on the tail while the queue is clogged.
        vm.retryOutboundItem(tail.id)
        settleUntil { sent.size > sentBefore && sent.any { it.outboundQueueItemId == tail.id } }

        assertTrue(
            "a manual Retry in the clogged state must produce a delivery attempt, " +
                "not a silent no-op (#1602)",
            sent.size > sentBefore,
        )
        assertTrue(
            "the retried tail row must have produced a SendRequest",
            sent.any { it.outboundQueueItemId == tail.id },
        )
        // The claim (row transitioned to InFlight) is the authoritative "delivery
        // attempt dispatched" signal.
        assertEquals(OutboundState.InFlight, requireNotNull(queue.item(tail.id)).state)
    }

    @Test
    fun healthyTailDrainsInFifoOrderAfterHeadParked() = runTest {
        // Class coverage (G2): multi-entry ordering preserved for the non-stuck
        // tail. A permanently-failing head is parked; the two healthy rows behind
        // it still deliver in FIFO (oldest-first) order.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(outboundQueueStore = queue)
        collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "HEAD stuck", createdAtMs = 1)
        val b = queue.enqueue(sessionKey = session, cleanText = "B", createdAtMs = 2)
        val c = queue.enqueue(sessionKey = session, cleanText = "C", createdAtMs = 3)
        vm.refreshOutboundQueueItemsFor(session)

        val deliveredHealthy = mutableListOf<String>()
        repeat(80) {
            if (deliveredHealthy.size == 2) return@repeat
            val id = vm.retryNextOutboundItem() ?: return@repeat
            advanceUntilIdle()
            val req = vm.inFlightSendRequest ?: return@repeat
            if (id == head.id) {
                vm.markOutboundSendDeferred(req)
                advanceUntilIdle()
            } else {
                deliveredHealthy += req.cleanDraft
                vm.markSendDelivered(req)
                advanceUntilIdle()
            }
        }

        assertEquals(
            "the healthy tail must deliver in FIFO order once the stuck head is parked",
            listOf("B", "C"),
            deliveredHealthy,
        )
        assertEquals(OutboundState.Failed, requireNotNull(queue.item(head.id)).state)
        assertTrue("B and C are delivered (pruned)", queue.item(b.id) == null && queue.item(c.id) == null)
    }

    @Test
    fun parkedHeadSurfacedAndManualRetryReDrivesItWithoutBreakingCoalesce() = runTest {
        // Class coverage (G2): skip-AND-surface + the #961 coalesce / exactly-once
        // invariant on retry. A parked head reads as Failed (surfaced, not dropped);
        // re-enqueuing the SAME logical send coalesces instead of duplicating; a
        // manual Retry on the parked row actually re-drives it.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(outboundQueueStore = queue)
        val sent = collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "stuck head", createdAtMs = 1, sendKey = "sk-head")
        vm.refreshOutboundQueueItemsFor(session)

        repeat(50) {
            if (queue.item(head.id)?.state == OutboundState.Failed) return@repeat
            val id = vm.retryNextOutboundItem() ?: return@repeat
            advanceUntilIdle()
            val req = vm.inFlightSendRequest ?: return@repeat
            if (id == head.id) {
                vm.markOutboundSendDeferred(req)
                advanceUntilIdle()
            }
        }

        val parked = requireNotNull(queue.item(head.id))
        assertEquals(
            "a permanently-failing head is SURFACED as Failed (skip-and-surface)",
            OutboundState.Failed,
            parked.state,
        )
        assertNotNull("the parked row carries a surfaced error label", parked.lastError)

        // #961 coalesce still holds: re-enqueue of the SAME logical send re-arms the
        // existing parked row instead of minting a second deliverable row.
        val reEnqueued = queue.enqueue(sessionKey = session, cleanText = "stuck head", createdAtMs = 9, sendKey = "sk-head")
        assertEquals("re-enqueue of the same logical send must coalesce, not duplicate", head.id, reEnqueued.id)
        assertEquals(1, queue.itemsFor(session).size)

        // A manual Retry on the (re-armed) parked row must actually re-drive it.
        val before = sent.size
        vm.retryOutboundItem(head.id)
        settleUntil { sent.size > before && sent.any { it.outboundQueueItemId == head.id } }
        assertTrue("manual Retry must re-drive a parked row (#1602)", sent.size > before)
        assertTrue(sent.any { it.outboundQueueItemId == head.id })
    }

    private class MinimalMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class MinimalVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): com.pocketshell.app.settings.VoiceTranscriptionProvider =
            com.pocketshell.app.settings.VoiceTranscriptionProvider.OpenAiWhisper
    }
}
