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
                    if (visible.value) {
                        PromptComposerSheet(
                        onDismiss = {
                            dismissCount.incrementAndGet()
                            visible.value = false
                        },
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
                            true
                        },
                        composerTargetKey = target,
                        sendTargetSnapshotProvider = {
                            PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                        },
                        viewModel = vm,
                    )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == target }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("prompt A")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) { firstEntered.isCompleted && vm.uiState.value.sendInFlight }

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
        assertEquals(0, dismissCount.get())
        assertTrue(visible.value)
        WalkthroughScreenshotArtifacts.capture("issue-1621-green-second-prompt-queued-during-first")

        releaseFirst.complete(Unit)
        compose.waitUntil(5_000) { secondEntered.isCompleted }
        assertEquals(1, queue.itemsFor(target).size)
        assertEquals("prompt B", queue.itemsFor(target).single().cleanText)
        assertEquals(OutboundState.InFlight, queue.itemsFor(target).single().state)
        assertTrue(vm.uiState.value.sendInFlight)
        assertEquals(listOf("prompt A", "prompt B"), callbackOrder.toList())
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1), callbackOrder.groupingBy { it }.eachCount())
        assertEquals(0, dismissCount.get())
        assertTrue(visible.value)
        WalkthroughScreenshotArtifacts.capture("issue-1621-green-fifo-second-delivering")
        releaseSecond.complete(Unit)
        compose.waitUntil(5_000) {
            !vm.uiState.value.sendInFlight &&
                queue.itemsFor(target).isEmpty() &&
                dismissCount.get() == 1 &&
                !visible.value
        }
        assertEquals(listOf("prompt A", "prompt B"), callbackOrder.toList())
        assertEquals(mapOf("prompt A" to 1, "prompt B" to 1), callbackOrder.groupingBy { it }.eachCount())
        assertEquals(1, dismissCount.get())
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
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = {
                                dismissCount.incrementAndGet()
                                visible.value = false
                            },
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
                                true
                            },
                            composerTargetKey = target,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                            },
                            viewModel = vm,
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
        compose.waitUntil(5_000) { vm.uiState.value.draft.isEmpty() }
        assertTrue(queue.itemsFor(target).isEmpty())
        assertEquals(0, dismissCount.get())
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertExists()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick().performTextInput("prompt B")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) { newerEntered.isCompleted }
        releaseNewer.complete(Unit)
        compose.waitUntil(5_000) { dismissCount.get() == 1 && !visible.value }
        assertEquals(1, dismissCount.get())
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
