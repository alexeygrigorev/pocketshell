package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Issue #2056 — head-of-line poisoning by an UNRESOLVABLE row.
 *
 * A send whose delivery outcome cannot be proven resolves as
 * [ComposerSendResult.AuthoritativeAckPending]. On base that deferral re-granted
 * the row's whole bounded auto-retry budget (`resetAttemptBudget = true`), so the
 * row's `attemptCount` was zeroed on EVERY cycle, it could never reach
 * [OUTBOUND_MAX_AUTO_ATTEMPTS], [firstComposerAutoFlushable] re-selected it as the
 * FIFO head forever, and no younger row was ever dispatched — the maintainer's
 * "once the first message gets clogged, every following message gets clogged too,
 * even though those following messages are in fact being delivered".
 *
 * Every assertion here compiles and runs against unmodified `origin/main`, so the
 * reproduction is a genuine base-RED, not a compile error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2056UnresolvableHeadDoesNotHoldTailTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    private class FakeVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
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

    private fun newVm(store: OutboundQueueStore): PromptComposerViewModel {
        val dispatcher = StandardTestDispatcher(mainDispatcherRule.dispatcher.scheduler)
        val vm = PromptComposerViewModel(
            audioRecorder = MinimalMicCapture(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                        Result.success("hello")
                }
            },
            apiKeyStorage = FakeVault(),
            voiceSettings = MinimalVoiceSettings(),
            outboundQueueStore = store,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        // The wire is demonstrably writable in this scenario — the payload reached
        // the pane. Only the delivery PROOF is missing.
        vm.setTransportWritableProbe { true }
        createdViewModels += vm
        return vm
    }

    /**
     * THE reported head-of-line symptom, driven through the real
     * [collectPromptComposerSendRequests] consumer so the production
     * result -> deferral mapping is the code under test.
     */
    @Test
    fun unresolvableHeadDoesNotStarveTheDeliverableTail() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "tmux:1/\$7/1700"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "csp", createdAtMs = 1, sendKey = "sk-head")
        val tail = queue.enqueue(
            sessionKey = session,
            cleanText = "follow-up prompt",
            createdAtMs = 2,
            sendKey = "sk-tail",
        )
        vm.refreshOutboundQueueItemsFor(session)

        val dispatched = mutableListOf<String>()
        backgroundScope.launch {
            collectPromptComposerSendRequests(
                viewModel = vm,
                onSend = { request ->
                    dispatched += request.outboundQueueItemId.orEmpty()
                    if (request.outboundQueueItemId == head.id) {
                        // The payload physically reached the pane, but the delivery
                        // proof expired: the exact ambiguous outcome of #2056.
                        ComposerSendResult.AuthoritativeAckPending
                    } else {
                        ComposerSendResult.Delivered
                    }
                },
            )
        }
        runCurrent()

        repeat(30) {
            if (queue.item(tail.id) == null) return@repeat
            vm.retryNextOutboundItem()
            advanceUntilIdle()
        }

        assertTrue(
            "the tail was never even dispatched — an unresolvable head starved the " +
                "whole queue (#2056)",
            dispatched.contains(tail.id),
        )
        assertNull(
            "a deliverable tail row must drain and terminally ack on its OWN evidence " +
                "even while the head stays unresolvable (#2056)",
            queue.item(tail.id),
        )
        assertTrue(
            "an unresolvable row must not be re-dispatched forever by the auto-flush " +
                "drain — every such dispatch can only reproduce the same unknown outcome " +
                "(#2056). Observed head dispatches: ${dispatched.count { it == head.id }}",
            dispatched.count { it == head.id } <= 2,
        )
        assertEquals(
            "the unresolvable head is preserved, never silently dropped",
            "csp",
            requireNotNull(queue.item(head.id)).cleanText,
        )
    }

    /**
     * Class coverage (G2): three deliverable rows behind ONE unresolvable head must
     * all drain, in FIFO order. A single-tail proof would pass even if the fix only
     * unblocked one row per poisoned head.
     */
    @Test
    fun everyDeliverableRowBehindAnUnresolvableHeadDrainsInFifoOrder() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(queue)
        val session = "tmux:1/\$7/1700"
        vm.onComposerTargetChanged(session)

        val head = queue.enqueue(sessionKey = session, cleanText = "csp", createdAtMs = 1, sendKey = "sk-head")
        listOf("first" to 2L, "second" to 3L, "third" to 4L).forEach { (text, at) ->
            queue.enqueue(sessionKey = session, cleanText = text, createdAtMs = at, sendKey = "sk-$text")
        }
        vm.refreshOutboundQueueItemsFor(session)

        val delivered = mutableListOf<String>()
        backgroundScope.launch {
            collectPromptComposerSendRequests(
                viewModel = vm,
                onSend = { request ->
                    if (request.outboundQueueItemId == head.id) {
                        ComposerSendResult.AuthoritativeAckPending
                    } else {
                        delivered += request.cleanDraft
                        ComposerSendResult.Delivered
                    }
                },
            )
        }
        runCurrent()

        repeat(60) {
            if (delivered.size == 3) return@repeat
            vm.retryNextOutboundItem()
            advanceUntilIdle()
        }

        assertEquals(
            "every deliverable row behind the unresolvable head must drain, in FIFO order",
            listOf("first", "second", "third"),
            delivered,
        )
    }
}
