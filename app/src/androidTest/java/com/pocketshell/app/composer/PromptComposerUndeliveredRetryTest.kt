package com.pocketshell.app.composer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.UNDELIVERED_TRANSCRIPT_BANNER_TAG
import com.pocketshell.app.session.UNDELIVERED_TRANSCRIPT_DISMISS_TAG
import com.pocketshell.app.session.UNDELIVERED_TRANSCRIPT_RETRY_TAG
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.app.voice.InMemoryUndeliveredTranscriptStore
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #1272: the durable "couldn't deliver — retry" surface, wired into the
 * LIVE prompt composer chrome.
 *
 * The data-loss slice (#1341) landed a durable [InMemoryUndeliveredTranscriptStore] /
 * SharedPreferences store + a bounded delivery channel + the VM API
 * ([InlineDictationViewModel.undeliveredTranscripts] / `retryUndelivered` /
 * `dismissUndelivered`), and a JVM `InlineDictationViewModelTest` proved the
 * permanent-dead-pane persist + cross-instance re-delivery LOGIC red→green. But
 * that logic had NO user-visible surface: the retry banner was rendered only
 * inside `KeyBarWithMic`, which has no production call site. The #1341 reviewer
 * blocked closure on exactly this gap.
 *
 * This is the missing UI proof. It exercises the REAL production composer body
 * ([SheetContent]) AND the full [PromptComposerSheet] (no `*StandIn` / `*Proxy`,
 * per #657/F2) bound to a real shared [InlineDictationViewModel], and asserts the
 * FULL loop end-to-end:
 *
 *  1. A transcript becomes undeliverable via the REAL permanent-dead-pane path —
 *     dictated with NO collector ever subscribing, then the session ViewModel is
 *     cleared (navigated-away / permanent pane death). The delivery channel
 *     drains the still-buffered transcript into the durable store.
 *  2. A fresh session ViewModel B shares that durable store; the composer surfaces
 *     the persisted transcript as a VISIBLE "Couldn't deliver — retry" row —
 *     asserted with viewport CONTAINMENT (`assertNodeFullyWithinRoot`, not a bare
 *     `assertIsDisplayed`), the F1/F3 "actually on-screen, not clipped" property.
 *  3. Tapping Retry re-injects the transcript into B's live delivery channel; a
 *     live collector receives it (re-delivered to a live pane) and the row clears.
 *  4. Tapping Dismiss on a still-dead surface (no live pane) discards the item
 *     without re-delivery — the navigated-away / give-up path.
 *  5. The FULL production [PromptComposerSheet] (its `ModalBottomSheet` window +
 *     the `hiltViewModel`-sourced binding, exercised via the `inlineDictationViewModel`
 *     override seam) surfaces the same row, proving the production wiring is
 *     reachable — the exact half the #1341 reviewer blocked on.
 *
 * RED on base (before the composer wiring): [SheetContent] / [PromptComposerSheet]
 * rendered no undelivered banner at all — [UNDELIVERED_TRANSCRIPT_BANNER_TAG] does
 * not exist in the tree, so `assertDoesNotExist()` would pass but the containment /
 * displayed assertions FAIL and the item is unreachable by the user. GREEN with
 * the wiring: the row is visible, contained, and retryable.
 *
 * The assertions are pure Compose UI (no soft-IME dependency), so there is NO
 * `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing assertions —
 * they HARD-fail on every device, CI included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerUndeliveredRetryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // --- Test doubles: the composer's own ViewModel (not under test here) -----

    private class ComposerMic : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class ComposerVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class ComposerVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private fun newComposerVm(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = ComposerMic(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = ComposerVault(),
        voiceSettings = ComposerVoiceSettings(),
    )

    // --- Test doubles: the shared inline-dictation ViewModel (under test) -----

    /**
     * Emits a real speech-energy WAV on stop so [SpeechAudioGuard.hasSpeechEnergy]
     * passes and the FSM reaches the transcribe + delivery-channel send step.
     */
    private class InlineMic : PromptComposerViewModel.MicCapture {
        private var running = false
        override fun start() { running = true }
        override fun stop(): ByteArray {
            running = false
            return SpeechAudioGuard.speechWavForTesting()
        }
        override fun currentAmplitude(): Float = if (running) 0.5f else 0f
    }

    private class InlineVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class InlineVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = InlineDictationViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        // transcriptionProvider() defaults to OpenAiWhisper -> the Whisper FSM path.
    }

    private fun newInlineVm(
        store: InMemoryUndeliveredTranscriptStore,
        transcript: () -> Result<String> = { Result.success("") },
    ): InlineDictationViewModel = InlineDictationViewModel(
        audioRecorder = InlineMic(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    transcript()
            }
        },
        apiKeyStorage = InlineVault(),
        voiceSettings = InlineVoiceSettings(),
        undeliveredTranscriptStore = store,
    )

    /**
     * Drives a real InlineDictationViewModel through the REAL permanent-dead-pane
     * path: dictate to completion with NO collector ever subscribing, then clear
     * the ViewModel (the session is permanently gone). The still-buffered
     * transcript drains into [store]. Returns the persisted item id.
     */
    private fun persistViaPermanentDeadPane(
        store: InMemoryUndeliveredTranscriptStore,
        text: String,
    ): String {
        val deadVm = newInlineVm(store) { Result.success(text) }
        // Start recording (Idle -> Recording), then stop (Recording -> Transcribing
        // -> Idle) so Whisper resolves and the transcript is sent into the delivery
        // channel. No collector is ever subscribed, so it stays buffered.
        compose.runOnUiThread { deadVm.onMicTap() }
        compose.waitUntil(TIMEOUT_MS) {
            deadVm.uiState.value.recording == InlineDictationViewModel.RecordingState.Recording
        }
        compose.runOnUiThread { deadVm.onMicTap() }
        compose.waitUntil(TIMEOUT_MS) {
            deadVm.uiState.value.recording == InlineDictationViewModel.RecordingState.Idle
        }
        // Nothing persisted while the ViewModel is alive — the transcript is still
        // deliverable if a pane were to return within this VM's lifetime.
        assertTrue(
            "transcript must stay buffered (not persisted) while the session VM is alive",
            store.snapshot().isEmpty(),
        )
        // Permanent pane death: the session screen is gone for good.
        compose.runOnUiThread { deadVm.clearForTest() }
        compose.waitUntil(TIMEOUT_MS) { store.snapshot().isNotEmpty() }
        return store.snapshot().first().id
    }

    /** Composes the REAL production composer BODY bound to [vm] (single window root
     *  -> viewport-containment assertions are valid). */
    private fun setSheetContent(vm: InlineDictationViewModel) {
        compose.setContent {
            PocketShellTheme {
                val items by vm.undeliveredTranscripts.collectAsState()
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    undeliveredTranscripts = items,
                    onRetryUndelivered = vm::retryUndelivered,
                    onDismissUndelivered = vm::dismissUndelivered,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Composes the FULL production [PromptComposerSheet] (its `ModalBottomSheet`
     *  window + the real `inlineDictationViewModel` binding). */
    private fun setFullComposerSheet(vm: InlineDictationViewModel) {
        val composerVm = newComposerVm()
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "alex@pocketshell:~$ claude\n> ready",
                        color = PocketShellColors.Text,
                    )
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _ -> true },
                        viewModel = composerVm,
                        // Issue #1272: production resolves this shared VM via
                        // hiltViewModel(); the test injects it directly so the real
                        // composer wiring renders the durable retry surface without
                        // a Hilt graph.
                        inlineDictationViewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun liveCollector(vm: InlineDictationViewModel, sink: CopyOnWriteArrayList<String>): Job {
        var job: Job? = null
        val scope = CoroutineScope(Dispatchers.Main)
        compose.runOnUiThread {
            job = scope.launch { vm.transcriptions.collect { sink.add(it) } }
        }
        compose.waitForIdle()
        return job!!
    }

    @Test
    fun permanentDeadPaneTranscriptIsVisibleContainedInComposerAndRetryReDelivers() {
        val store = InMemoryUndeliveredTranscriptStore()
        val text = "git push origin main"

        // (1) REAL permanent-dead-pane persist: session A dies with the transcript
        // still buffered — the navigated-away / subscriber-torn-down path.
        val id = persistViaPermanentDeadPane(store, text)

        // (2) Session B shares the durable store and has a live delivery collector.
        val vmB = newInlineVm(store)
        val delivered = CopyOnWriteArrayList<String>()
        val collector = liveCollector(vmB, delivered)
        setSheetContent(vmB)

        // The composer must SURFACE the persisted transcript as a VISIBLE retry row.
        // Containment (assertNodeFullyWithinRoot) is the load-bearing assertion —
        // 'displayed' passes even off-screen; this proves the user can actually see
        // it. On base (no composer wiring) the banner tag does not exist -> fails.
        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(UNDELIVERED_TRANSCRIPT_BANNER_TAG)
        compose.onNodeWithText("Couldn't deliver — retry").assertIsDisplayed()
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot("$UNDELIVERED_TRANSCRIPT_RETRY_TAG-$id")

        captureFullDevice("issue-1272-undelivered-retry-row-visible.png")

        // (3) Retry re-delivers into B's live pane and clears the row.
        compose.onNodeWithTag("$UNDELIVERED_TRANSCRIPT_RETRY_TAG-$id").performClick()
        compose.waitUntil(TIMEOUT_MS) { delivered.isNotEmpty() }
        assertEquals(
            "Retry must re-deliver the persisted transcript into the live delivery channel",
            listOf(text),
            delivered.toList(),
        )
        compose.waitUntil(TIMEOUT_MS) { store.snapshot().isEmpty() }
        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertDoesNotExist()

        collector.cancel()
    }

    @Test
    fun undeliveredRetryRowDismissDiscardsWithoutReDelivery() {
        val store = InMemoryUndeliveredTranscriptStore()
        val text = "deploy staging"

        // Permanent-dead-pane persist; the surface then stays dead (no live pane):
        // the navigated-away path where the user gives up and dismisses the command.
        val id = persistViaPermanentDeadPane(store, text)

        val vmB = newInlineVm(store)
        val delivered = CopyOnWriteArrayList<String>()
        val collector = liveCollector(vmB, delivered)
        setSheetContent(vmB)

        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot("$UNDELIVERED_TRANSCRIPT_DISMISS_TAG-$id")

        compose.onNodeWithTag("$UNDELIVERED_TRANSCRIPT_DISMISS_TAG-$id").performClick()
        compose.waitUntil(TIMEOUT_MS) { store.snapshot().isEmpty() }
        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertDoesNotExist()
        assertTrue("Dismiss must NOT re-deliver the transcript", delivered.isEmpty())

        collector.cancel()
    }

    @Test
    fun fullComposerSheetSurfacesUndeliveredRetryRowFromSharedViewModelAndReDelivers() {
        // Proves the PRODUCTION wiring path the #1341 reviewer blocked on: the full
        // PromptComposerSheet (ModalBottomSheet window) resolves the shared
        // InlineDictationViewModel binding (rememberComposerUndeliveredBinding ->
        // SheetContent) and surfaces the retry row so the user can actually see +
        // tap it. Multiple window roots (the sheet's dialog) make onRoot()-based
        // containment ambiguous here, so this asserts displayed + re-delivery; the
        // containment guarantee is owned by the single-root SheetContent test above.
        val store = InMemoryUndeliveredTranscriptStore()
        val text = "make deploy"
        persistViaPermanentDeadPane(store, text)

        val vmB = newInlineVm(store)
        val delivered = CopyOnWriteArrayList<String>()
        val collector = liveCollector(vmB, delivered)
        val id = store.snapshot().first().id
        setFullComposerSheet(vmB)

        compose.onNodeWithText("Couldn't deliver — retry", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()

        captureFullDevice("issue-1272-live-composer-sheet-retry-row.png")

        compose.onNodeWithTag("$UNDELIVERED_TRANSCRIPT_RETRY_TAG-$id", useUnmergedTree = true).performClick()
        compose.waitUntil(TIMEOUT_MS) { delivered.isNotEmpty() }
        assertEquals(
            "Retry from the live composer sheet must re-deliver the transcript",
            listOf(text),
            delivered.toList(),
        )
        compose.waitUntil(TIMEOUT_MS) { store.snapshot().isEmpty() }
        compose.onNodeWithText("Couldn't deliver — retry", useUnmergedTree = true).assertDoesNotExist()

        collector.cancel()
    }

    /**
     * Issue #1272 (round-2 finding 2): capture the retry-row surface through the
     * ComposeTestRule's OWN capture path ([captureToImage]) — NOT
     * `instrumentation.uiAutomation.takeScreenshot()`.
     *
     * `takeScreenshot()` obtains a full-device `UiAutomation`; the no-arg
     * `getUiAutomation()` connects with different flags than the instance the
     * instrumentation / ComposeTestRule already registered, which throws
     * `UiAutomation already registered` and crashed the run (round-1 was 3/3
     * FAILED on run 1: `UiAutomation already registered` / process crash / `No
     * compose hierarchies found`, 3/3 PASSED on run 2). That self-inflicted
     * collision is also the likely culprit wedging the shared AVD for sibling
     * agents. [captureToImage] reuses the already-owned automation, so it can
     * never re-register — and it captures exactly the banner under test, which is
     * the authoritative evidence anyway.
     */
    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-1272-undelivered")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-1272 screenshot directory: ${dir.absolutePath}"
        }
        val file = File(dir, name)
        compose.waitForIdle()
        val bitmap = compose
            .onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG, useUnmergedTree = true)
            .captureToImage()
            .asAndroidBitmap()
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-1272 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE1272_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
