package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #695 recurrence: dismissal belongs to local outbound acceptance, not
 * the later host-delivery result.
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
    fun durableAcceptanceMakesEmptyComposerDismissibleBeforeDeliveryResult() = runTest {
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
