package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.tmux.OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS
import com.pocketshell.app.tmux.OutboundQueueAutoFlushController
import com.pocketshell.app.tmux.outboundBudgetTestComposer
import com.pocketshell.app.tmux.runOutboundQueueAutoFlush
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1686 (Track C): the composer outbound queue must be INDIFFERENT to a
 * false/flapping `ConnectionStatus` — the WIRE is the oracle.
 *
 * The maintainer's reported symptom: "the composer queue gets clogged because it
 * thinks the connection is not there." Two independent enum-trusting layers caused
 * it, both reproduced here red→green:
 *
 *  1. **The drain gate** was hard-gated on the enum (`sessionLive`), so during a
 *     false-disconnect NOTHING even tried the wire ([drainRunsWhenEnumNotConnectedButTransportWritable]).
 *  2. **The retry budget** burned one unit per attempt regardless of WHY the send
 *     failed, so a flapping/prolonged outage parked the row `Failed` after 6 and it
 *     stranded forever ([transportUnavailableFailuresNeverParkTheRow]).
 *
 * The fix: the drain gate opens on transport-writability, and a failure taxonomy
 * only burns the budget on a LIVE-transport REJECTION — a transport-unavailable
 * failure re-arms without ever parking, and the connected/transport-alive edge
 * un-parks the auto-parked backlog so it self-heals.
 *
 * Pure JVM ViewModel/controller tests (gate-wired in the `Unit tests` job).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerWireOracleClogTest {

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

    private fun runTestDispatcher() = StandardTestDispatcher()

    private fun newVm(outboundQueueStore: OutboundQueueStore): PromptComposerViewModel {
        val dispatcher = runTestDispatcher()
        val vm = PromptComposerViewModel(
            audioRecorder = MinimalMicCapture(),
            whisperClientFactory = WhisperClientFactory { fakeWhisperClient() },
            apiKeyStorage = FakeVault("sk-test".toCharArray()),
            voiceSettings = MinimalVoiceSettings(),
            outboundQueueStore = outboundQueueStore,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        createdViewModels += vm
        return vm
    }

    // -------- Layer 1: the drain gate is decoupled from the enum --------

    @Test
    fun drainRunsWhenEnumNotConnectedButTransportWritable() = runTest {
        // RED on base: the drain lane is gated purely on `sessionLive` (the enum), so
        // with sessionLive=false NOTHING attempts the wire even though the transport
        // is writable — the false-disconnect clog. GREEN: the wire-oracle gate opens
        // on `sessionLive || transportWritable()`, so the drain attempts and drains.
        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer(), clock = { testScheduler.currentTime })
        controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = "1/s") {}
        val dispatched = mutableListOf<String>()
        val retryEligible: (Set<String>) -> String? = { ex -> if ("r1" in ex) null else "r1" }
        val quietQueue = flow<Any?> { emit(Unit); awaitCancellation() }

        val job = launch {
            runOutboundQueueAutoFlush(
                sessionLive = false, // the enum says NOT connected (false-disconnect / #1680 storm)
                outboundQueueItems = quietQueue,
                controller = controller,
                retryNext = { ex -> retryEligible(ex)?.also { dispatched += it } },
                transportWritable = { true }, // ...but the WIRE is writable
            )
        }
        runCurrent()
        assertEquals(
            "the snapshot lane must drain when the transport is writable even though the " +
                "enum says not-Connected (#1686 wire-oracle drain gate)",
            listOf("r1"),
            dispatched,
        )
        // The poll lane now runs UNCONDITIONALLY and re-evaluates the transport gate,
        // so a row deferred inside an unchanged (enum-still-false) window re-dispatches.
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals(listOf("r1", "r1"), dispatched)
        job.cancelAndJoin()
    }

    @Test
    fun drainStaysShutWhenBothEnumAndTransportAreDown() = runTest {
        // Class coverage (G2) + no-new-reconnect-pressure: a GENUINELY dead wire keeps
        // the gate shut (enum false AND transport not writable), so the drain does NOT
        // attempt — no dispatch, no reconnect kick during a real outage.
        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer(), clock = { testScheduler.currentTime })
        controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = "1/s") {}
        val dispatched = mutableListOf<String>()
        val quietQueue = flow<Any?> { emit(Unit); awaitCancellation() }

        val job = launch {
            runOutboundQueueAutoFlush(
                sessionLive = false,
                outboundQueueItems = quietQueue,
                controller = controller,
                retryNext = { _ -> "r1".also { dispatched += it } },
                transportWritable = { false }, // the wire is genuinely dead
            )
        }
        runCurrent()
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals(
            "a genuinely dead wire keeps the drain gate shut — no attempt, no reconnect kick",
            emptyList<String>(),
            dispatched,
        )
        job.cancelAndJoin()
    }

    // -------- Layer 2: the failure taxonomy --------

    /** Mirror of `PromptComposerSendDispatcher`'s #1686 classification of a failed send. */
    private fun PromptComposerViewModel.resolveFailureLikeDispatcher(
        request: PromptComposerViewModel.SendRequest,
    ) = markOutboundSendDeferred(request, resetAttemptBudget = !isSendTransportWritable())

    @Test
    fun transportUnavailableFailuresNeverParkTheRow() = runTest {
        // RED on base: every dispatch during a false-disconnect burns one unit of the
        // 6-attempt budget (markOutboundSendDeferred keeps attemptCount), so after 6
        // the row is PARKED `Failed` forever — the clog that only a manual Retry clears.
        // GREEN: with the wire down (probe=false) the failure is transport-class, so
        // the row re-arms WITHOUT burning the budget and NEVER parks, no matter how many
        // times the flapping outage fails it.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        vm.setTransportWritableProbe { false } // the wire is genuinely unavailable

        val row = queue.enqueue(sessionKey = session, cleanText = "queued during outage", createdAtMs = 1)
        vm.refreshOutboundQueueItemsFor(session)

        // Drive far past the 6-attempt budget.
        repeat(20) {
            val id = vm.retryNextOutboundItem() ?: return@repeat
            advanceUntilIdle()
            if (id == row.id) {
                val req = vm.inFlightSendRequest ?: return@repeat
                vm.resolveFailureLikeDispatcher(req)
                advanceUntilIdle()
            }
        }

        val current = requireNotNull(queue.item(row.id))
        assertEquals(
            "a transport-unavailable failure must NEVER park the row — it re-arms and " +
                "retries forever until the wire returns (#1686)",
            OutboundState.Queued,
            current.state,
        )
        assertTrue(
            "the bounded auto-retry budget must not accrue on transport-unavailable failures",
            current.attemptCount < OUTBOUND_MAX_AUTO_ATTEMPTS,
        )
    }

    @Test
    fun liveTransportRejectionStillParksAfterBudget() = runTest {
        // Negative / G6: the taxonomy must PRESERVE #1602 — a genuinely poison row on a
        // LIVE wire (probe=true ⇒ rejection) still burns the budget and parks after 6,
        // so a stuck head does not block the healthy tail. If the taxonomy over-generalised
        // (never parking anything) this would go red.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        vm.setTransportWritableProbe { true } // the wire is UP — failures are rejections

        val row = queue.enqueue(sessionKey = session, cleanText = "poison row", createdAtMs = 1)
        vm.refreshOutboundQueueItemsFor(session)

        var parked = false
        repeat(20) {
            if (queue.item(row.id)?.state == OutboundState.Failed) { parked = true; return@repeat }
            val id = vm.retryNextOutboundItem() ?: return@repeat
            advanceUntilIdle()
            if (id == row.id) {
                val req = vm.inFlightSendRequest ?: return@repeat
                vm.resolveFailureLikeDispatcher(req)
                advanceUntilIdle()
            }
        }
        if (queue.item(row.id)?.state == OutboundState.Failed) parked = true

        assertTrue(
            "a live-transport rejection must still park a poison row after the bounded budget (#1602 preserved)",
            parked,
        )
    }

    // -------- Layer 3: connected/transport-alive-edge self-heal --------

    @Test
    fun connectedEdgeUnparksAutoFailedRowsAndResetsBudget() = runTest {
        // RED on base: `onConnectionWindowChanged`'s connected-edge callback only requeues
        // stale InFlight rows — it NEVER un-parks a `Failed` row, so an auto-parked backlog
        // stays stranded until a manual per-row Retry. GREEN: the transport-alive edge
        // un-parks every auto-parked row and re-grants its budget so the backlog self-heals.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val parkedA = queue.enqueue(sessionKey = session, cleanText = "parked A", createdAtMs = 1)
        val parkedB = queue.enqueue(sessionKey = session, cleanText = "parked B", createdAtMs = 2)
        // Model the auto-parked backlog after a storm (budget exhausted).
        queue.markInFlight(parkedA.id)
        queue.markFailed(parkedA.id, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
        queue.markInFlight(parkedB.id)
        queue.markFailed(parkedB.id, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
        vm.refreshOutboundQueueItemsFor(session)
        assertEquals(OutboundState.Failed, requireNotNull(queue.item(parkedA.id)).state)

        val unparked = vm.unparkTransportFailedRows()

        assertEquals(
            "the transport-alive edge un-parks the WHOLE auto-parked backlog (class coverage, not one row)",
            setOf(parkedA.id, parkedB.id),
            unparked.toSet(),
        )
        listOf(parkedA.id, parkedB.id).forEach { id ->
            val row = requireNotNull(queue.item(id))
            assertEquals("un-parked back to Queued", OutboundState.Queued, row.state)
            assertEquals("budget re-granted", 0, row.attemptCount)
        }
    }

    @Test
    fun connectedEdgeLeavesGenuineNonTransportParksAlone() = runTest {
        // Class coverage (G2): a genuine non-budget park (e.g. an empty "Nothing to send"
        // row) is NOT auto-unparked — re-arming it would not help. Only the bounded-budget
        // auto-park (which a storm produces) self-heals.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val poison = queue.enqueue(sessionKey = session, cleanText = "poison", createdAtMs = 1)
        queue.markFailed(poison.id, lastError = "Nothing to send")
        vm.refreshOutboundQueueItemsFor(session)

        assertEquals(emptyList<String>(), vm.unparkTransportFailedRows())
        assertEquals(OutboundState.Failed, requireNotNull(queue.item(poison.id)).state)
    }

    @Test
    fun unparkIsANoOpWithNoTargetOrForForeignSessions() = runTest {
        // Missing-data + foreign-session case (G2): no composer target ⇒ empty (no crash);
        // and a parked row for a DIFFERENT session is not touched (target-scoped self-heal).
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        assertEquals("no target ⇒ no-op", emptyList<String>(), vm.unparkTransportFailedRows())

        val other = queue.enqueue(sessionKey = "1/other", cleanText = "foreign", createdAtMs = 1)
        queue.markFailed(other.id, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
        vm.onComposerTargetChanged("1/session-a")
        vm.refreshOutboundQueueItemsFor("1/session-a")

        assertNull(vm.unparkTransportFailedRows().firstOrNull())
        assertEquals(OutboundState.Failed, requireNotNull(queue.item(other.id)).state)
    }
}
