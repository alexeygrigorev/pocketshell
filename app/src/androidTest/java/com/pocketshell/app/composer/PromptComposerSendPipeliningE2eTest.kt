package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-sheet proof for issue #1621's send-while-sending FIFO journey. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSendPipeliningE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var viewModel: PromptComposerViewModel? = null

    @After
    fun tearDown() {
        viewModel?.clearForTest()
        viewModel = null
    }

    @Test
    fun secondPromptQueuesDuringFirstAndThenDeliversFifo() {
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(queue)
        val target = "1/session-a"
        val callbackOrder = Collections.synchronizedList(mutableListOf<String>())
        val dismissCount = AtomicInteger(0)
        val visible = mutableStateOf(true)
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()

        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = { request ->
                            val payload = request.cleanDraft
                            callbackOrder.add(payload)
                            when (payload) {
                                "prompt A" -> {
                                    firstEntered.complete(Unit)
                                    releaseFirst.await()
                                }
                                "prompt B" -> {
                                    secondEntered.complete(Unit)
                                    releaseSecond.await()
                                }
                                else -> error("unexpected callback payload: $payload")
                            }
                            ComposerSendResult.Delivered
                        },
                        onDelivered = {
                            dismissCount.incrementAndGet()
                            visible.value = false
                        },
                    )
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = target,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                            },
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == target }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("prompt A")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) {
            vm.uiState.value.draft.isEmpty() &&
                !vm.uiState.value.outboundHandoffInProgress &&
                queue.itemsFor(target).size == 1
        }
        // Keep local acceptance, close, and host delivery as separate
        // milestones. A composite timeout hid #2048's regression: the durable
        // row was accepted and its blocked host callback started, but the empty
        // composer alone stayed open until delivery.
        compose.waitUntil(5_000) { dismissCount.get() == 1 && !visible.value }
        compose.waitUntil(5_000) { firstEntered.isCompleted && vm.uiState.value.sendInFlight }

        // Local acceptance closes the empty composer promptly. Reopening it
        // while A is still delivering preserves #1621 pipelining: B can still
        // enqueue behind A and closes promptly on its own acceptance.
        compose.runOnUiThread { visible.value = true }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("prompt B")
        compose.waitUntil(5_000) { vm.uiState.value.draft == "prompt B" }
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            !vm.uiState.value.outboundHandoffInProgress && queue.itemsFor(target).size == 2
        }

        assertEquals(listOf("prompt A", "prompt B"), queue.itemsFor(target).map { it.cleanText })
        assertEquals(listOf(OutboundState.InFlight, OutboundState.Queued), queue.itemsFor(target).map { it.state })
        assertEquals(listOf("prompt A"), callbackOrder.toList())
        assertEquals(2, dismissCount.get())
        assertTrue(!visible.value)
        WalkthroughScreenshotArtifacts.capture("issue-1621-green-second-prompt-queued-during-first")

        releaseFirst.complete(Unit)
        compose.waitUntil(5_000) { secondEntered.isCompleted }
        assertEquals(1, queue.itemsFor(target).size)
        assertEquals("prompt B", queue.itemsFor(target).single().cleanText)
        assertEquals(OutboundState.InFlight, queue.itemsFor(target).single().state)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(listOf("prompt A", "prompt B"), callbackOrder.toList())
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1), callbackOrder.groupingBy { it }.eachCount())
        assertEquals(2, dismissCount.get())
        assertTrue(!visible.value)
        WalkthroughScreenshotArtifacts.capture("issue-1621-green-fifo-second-delivering")
        releaseSecond.complete(Unit)
        compose.waitUntil(5_000) {
            !vm.uiState.value.sendInFlight &&
                queue.itemsFor(target).isEmpty()
        }
        assertEquals(listOf("prompt A", "prompt B"), callbackOrder.toList())
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1), callbackOrder.groupingBy { it }.eachCount())
        assertEquals(2, dismissCount.get())
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(queue.itemsFor(target).isEmpty())
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertDoesNotExist()
        WalkthroughScreenshotArtifacts.capture("issue-1621-green-fifo-complete-queue-empty")
    }

    @Test
    fun olderAutoFlushCompletionCannotDismissNewlyOpenedComposerBeforeTyping() {
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(queue)
        val target = "1/session-a"
        vm.onComposerTargetChanged(target)
        queue.enqueue(target, "older queued prompt", emptyList(), true)
        vm.refreshOutboundQueueItemsFor(target)
        assertTrue(vm.retryNextOutboundItem() != null)

        val visible = mutableStateOf(true)
        val dismissCount = AtomicInteger(0)
        val olderEntered = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val newerEntered = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = { request ->
                            when (request.cleanDraft) {
                                "older queued prompt" -> {
                                    olderEntered.complete(Unit)
                                    releaseOlder.await()
                                }
                                "prompt B" -> {
                                    newerEntered.complete(Unit)
                                    releaseNewer.await()
                                }
                                else -> error("unexpected ${request.cleanDraft}")
                            }
                            ComposerSendResult.Delivered
                        },
                        onDelivered = {
                            dismissCount.incrementAndGet()
                            visible.value = false
                        },
                    )
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = target,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                            },
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { olderEntered.isCompleted }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()

        releaseOlder.complete(Unit)
        compose.waitUntil(5_000) { !vm.uiState.value.sendInFlight }
        compose.waitForIdle()
        assertEquals("an older auto-flush completion cannot close this open epoch", 0, dismissCount.get())
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("older queued prompt")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) {
            vm.uiState.value.draft.isEmpty() &&
                dismissCount.get() == 1 &&
                !visible.value
        }
        assertTrue(queue.itemsFor(target).isEmpty())
        assertEquals(1, dismissCount.get())

        compose.runOnUiThread { visible.value = true }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("prompt B")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) { newerEntered.isCompleted }
        releaseNewer.complete(Unit)
        compose.waitUntil(5_000) { dismissCount.get() == 2 && !visible.value }
        assertEquals(2, dismissCount.get())
    }

    /**
     * The no-store fallback has no local-acceptance event, so its delivery is
     * allowed to request close. Remounting the sheet while that callback is
     * blocked must therefore be protected by [onComposerOpened] itself, even
     * before the user types. This is the mutation-valid remount oracle: remove
     * the mount epoch and the released delivery closes the new sheet.
     */
    @Test
    fun noRowCompletionCannotDismissRemountedEmptyComposerOrResubmitPayload() {
        val vm = newViewModel(DisabledOutboundQueueStore)
        val target = "1/session-a"
        val visible = mutableStateOf(true)
        val deliveryEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val closeCallbacks = AtomicInteger(0)
        val hostSubmissions = Collections.synchronizedList(mutableListOf<String>())

        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = { request ->
                            hostSubmissions.add(request.cleanDraft)
                            deliveryEntered.complete(Unit)
                            releaseDelivery.await()
                            ComposerSendResult.Delivered
                        },
                        onDelivered = {
                            closeCallbacks.incrementAndGet()
                            visible.value = false
                        },
                    )
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = target,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                            },
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == target }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("older no-row prompt")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) {
            deliveryEntered.isCompleted &&
                vm.uiState.value.sendInFlight &&
                vm.uiState.value.draft.isEmpty()
        }
        assertEquals(listOf("older no-row prompt"), hostSubmissions.toList())

        compose.runOnUiThread { visible.value = false }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertDoesNotExist()
        compose.runOnUiThread { visible.value = true }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()

        releaseDelivery.complete(Unit)
        compose.waitUntil(5_000) { !vm.uiState.value.sendInFlight }
        compose.waitForIdle()

        assertTrue("the remounted empty composer owns the new epoch", visible.value)
        assertEquals("older completion must not close the remount", 0, closeCallbacks.get())
        assertEquals(
            "the older payload must reach the host exactly once",
            listOf("older no-row prompt"),
            hostSubmissions.toList(),
        )
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()
    }

    private fun newViewModel(queue: OutboundQueueStore): PromptComposerViewModel =
        PromptComposerViewModel(
            audioRecorder = object : PromptComposerViewModel.MicCapture {
                override fun start() = Unit
                override fun stop(): ByteArray = ByteArray(0)
                override fun currentAmplitude(): Float = 0f
            },
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                        Result.success("")
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
            },
            composerDraftStore = InMemoryComposerDraftStore(),
            outboundQueueStore = queue,
        ).also { viewModel = it }
}
