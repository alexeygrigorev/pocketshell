package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #180 connected E2E: drives the composer through the
 * "Whisper failure → audio persisted → user taps retry" journey on an
 * emulator. Renders [SheetContent] directly so the test stays in
 * androidTest scope (the ModalBottomSheet wrapper needs a Compose window
 * decor view and complicates the harness without buying coverage that
 * the inner SheetContent does not already exercise).
 *
 * The fake Whisper client is scripted so the first call fails (the bug
 * the user reported) and the second succeeds (the retry the user wants).
 * A scriptable in-memory [PromptComposerViewModel.PendingTranscriptionQueue]
 * stands in for the real Room + filesystem store; the store is exercised
 * end-to-end by [com.pocketshell.app.voice.PendingTranscriptionStoreTest]
 * and does not need a duplicate emulator round-trip.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerPendingTranscriptionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
        override fun stop(): ByteArray {
            stopCount++
            // Sentinel WAV header bytes — non-empty so the store accepts
            // the audio.
            return ByteArray(44) { 0 }
        }
        override fun currentAmplitude(): Float = 0.5f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private class TestConnectivity(initial: Boolean = true) :
        PromptComposerViewModel.ConnectivityProbe {
        var online: Boolean = initial
        override fun refresh(): Boolean = online
    }

    class InMemoryQueue : PromptComposerViewModel.PendingTranscriptionQueue {
        private val flow = MutableStateFlow<List<PendingTranscriptionItem>>(emptyList())
        private val audio = mutableMapOf<String, ByteArray>()
        override val items = flow

        var enqueueCount = 0
            private set
        var nextId = "queued-1"
        var succeededIds: MutableList<String> = mutableListOf()
        var failureIds: MutableList<String> = mutableListOf()
        var discardedIds: MutableList<String> = mutableListOf()
        var savedIds: MutableList<String> = mutableListOf()

        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): PendingTranscriptionItem? {
            enqueueCount++
            val id = nextId
            this.audio[id] = audio
            val item = PendingTranscriptionItem(
                id = id,
                recordingTimestampMs = System.currentTimeMillis(),
                destinationContext = destinationContext,
                retryCount = 0,
                lastErrorMessage = initialError,
                audioByteSize = audio.size.toLong(),
            )
            flow.value = listOf(item) + flow.value
            return item
        }

        override suspend fun snapshot(): List<PendingTranscriptionItem> = flow.value
        override suspend fun loadAudio(id: String): ByteArray? = audio[id]
        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            audio.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }
        override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? {
            failureIds += id
            val existing = flow.value.firstOrNull { it.id == id } ?: return null
            val updated = existing.copy(
                retryCount = existing.retryCount + 1,
                lastErrorMessage = errorMessage,
            )
            flow.value = flow.value.map { if (it.id == id) updated else it }
            return updated
        }
        override suspend fun discard(id: String) {
            discardedIds += id
            audio.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }
        override suspend fun saveAsAudioFile(id: String): String? {
            savedIds += id
            audio.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
            return "/data/files/voice-exports/$id.wav"
        }
        override suspend fun reconcile() = Unit
    }

    @Test
    fun whisperFailureSurfacesPendingBannerAndRetrySendsAgain() {
        val mic = TestMicCapture()
        val whisperCalls = AtomicInteger(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> {
                val n = whisperCalls.incrementAndGet()
                return if (n == 1) {
                    Result.failure(WhisperException.Transport("simulated drop"))
                } else {
                    Result.success("retried transcript")
                }
            }
        }
        val queue = InMemoryQueue()
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
            pendingTranscriptionStore = queue,
            connectivity = TestConnectivity(initial = true),
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                val pending by vm.pendingItems.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { _ -> },
                    onCancelRecording = vm::cancelRecording,
                    pendingItems = pending,
                    pendingListExpanded = true,
                    onTogglePendingList = {},
                    onRetryPending = vm::retryPending,
                    onDiscardPending = vm::discardPending,
                    onSavePendingAsAudio = vm::savePendingAsAudioFile,
                    onAcknowledgeSavedAudio = vm::clearSavedAudioConfirmation,
                )
            }
        }

        // Drive the recording journey: start, then stop. The stop path
        // persists the audio buffer + calls Whisper, which fails, leaving
        // a queue entry.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.pendingItems.value.isNotEmpty() &&
                vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle
        }

        // Audio persisted before Whisper, then Whisper failed and bumped
        // the retry count.
        assertEquals(1, queue.enqueueCount)
        assertEquals(1, queue.failureIds.size)
        // Banner is rendered (we forced expanded = true so the per-item
        // row is visible too).
        compose.onNodeWithTag(COMPOSER_PENDING_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("1 pending transcription").assertIsDisplayed()

        // Tap retry — the second Whisper call succeeds, draft is
        // appended, queue is cleared.
        val id = queue.snapshotSync()[0].id
        compose.onNodeWithTag(composerPendingRetryTestTag(id)).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.pendingItems.value.isEmpty()
        }

        assertEquals(2, whisperCalls.get())
        assertTrue(queue.succeededIds.contains(id))
        assertEquals("retried transcript", vm.uiState.value.draft)
    }

    @Test
    fun offlineRecordingGoesStraightToQueueWithoutWhisperCall() {
        val mic = TestMicCapture()
        val whisperCalls = AtomicInteger(0)
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> {
                whisperCalls.incrementAndGet()
                return Result.success("should-not-be-called")
            }
        }
        val queue = InMemoryQueue()
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
            pendingTranscriptionStore = queue,
            connectivity = TestConnectivity(initial = false),
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                val pending by vm.pendingItems.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { _ -> },
                    onCancelRecording = vm::cancelRecording,
                    pendingItems = pending,
                )
            }
        }

        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Idle &&
                vm.pendingItems.value.isNotEmpty()
        }

        assertEquals("offline path must not call Whisper", 0, whisperCalls.get())
        assertEquals(1, queue.enqueueCount)
        // The error banner reads "waiting for network".
        compose.onNodeWithText(PendingTranscriptionItem.NETWORK_WAITING_MESSAGE)
            .assertIsDisplayed()
    }

    @Test
    fun retryCapShowsSaveAndDiscardInsteadOfRetry() {
        val mic = TestMicCapture()
        val queue = InMemoryQueue()
        queue.nextId = "capped"
        // Pre-seed a row already at the cap.
        kotlinx.coroutines.runBlocking {
            queue.enqueueAudio(ByteArray(8), "composer")
            queue.markFailure("capped", "1")
            queue.markFailure("capped", "2")
            queue.markFailure("capped", "3")
        }

        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = TestVault(),
            voiceSettings = TestVoiceSettings(),
            pendingTranscriptionStore = queue,
            connectivity = TestConnectivity(initial = true),
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val state by vm.uiState.collectAsState()
                val pending by vm.pendingItems.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { },
                    onSend = { _ -> },
                    pendingItems = pending,
                    pendingListExpanded = true,
                    onSavePendingAsAudio = vm::savePendingAsAudioFile,
                    onDiscardPending = vm::discardPending,
                )
            }
        }

        compose.waitForIdle()
        // The Save / Discard buttons are visible, the Retry tag is not
        // rendered for the capped item.
        compose.onNodeWithTag(composerPendingSaveTestTag("capped")).assertIsDisplayed()
        compose.onNodeWithTag(composerPendingDiscardTestTag("capped")).assertIsDisplayed()
        compose.onNodeWithTag(composerPendingRetryTestTag("capped")).assertDoesNotExist()

        // Tap save — store records the request.
        compose.onNodeWithTag(composerPendingSaveTestTag("capped")).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { queue.savedIds.contains("capped") }
        assertTrue(queue.savedIds.contains("capped"))
    }
}

/**
 * Synchronous snapshot helper for the in-memory queue. The
 * `snapshot()` method on the queue is `suspend`, but the assertion
 * sites here run on the test thread and just need the current row
 * list. Defined as an extension to keep the test class focused on
 * the journey.
 */
private fun PromptComposerPendingTranscriptionTest.InMemoryQueue.snapshotSync():
    List<PendingTranscriptionItem> = kotlinx.coroutines.runBlocking { snapshot() }
