package com.pocketshell.app.composer

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #832 (CHANGES-REQUESTED follow-up) — end-to-end, on-device proof that the
 * **production** durable per-session draft store actually restores the visible
 * composer text on a real session switch.
 *
 * ## Why this test exists (the reviewer's blocking finding)
 *
 * [PromptComposerViewModelTest] covers the store contract at the JVM layer, and
 * [PromptComposerDiscardE2eTest] drives the real composer UI — but BOTH use the
 * in-memory double ([InMemoryComposerDraftStore]) / the no-op
 * [DisabledComposerDraftStore], never the production
 * [SharedPrefsComposerDraftStore] that Hilt actually binds in `VoiceModule`.
 * The issue body itself documents that the *previous* draft chain shipped inert
 * end-to-end because the wiring was never exercised on-device. This test closes
 * that gap: it constructs the ViewModel with the EXACT production store Hilt
 * provides (`SharedPrefsComposerDraftStore(applicationContext)` —
 * `provideComposerDraftStore` is a pass-through `store -> store`, so this is the
 * same object the production graph injects), drives the real composer body
 * through a session switch, and asserts the **visible composer text** — not just
 * VM state — round-trips through real `SharedPreferences`.
 *
 * Each `@Test` wipes the `composer_drafts` prefs file in [Before] so the run is
 * deterministic and isolated from a prior run on the same emulator.
 *
 * The `sendRequests` collector is started on a Main-dispatcher scope (the proven
 * pattern from [PromptComposerDiscardE2eTest]) so the single-consumer
 * `Channel.receiveAsFlow()` is drained by exactly one collector for the test.
 *
 * ## AC2 attachment-refs note (#872 design)
 *
 * The maintainer's reported case is a FAILED / dropped SEND. On that path
 * [PromptComposerViewModel.restoreFailedSend] restores the CLEAN draft text PLUS
 * the actual attachment TILE, and persists BOTH durably per-session (the clean
 * text under the session key, the attachment ref under the `@att/` slot). It no
 * longer folds the raw path into the draft string (#694), which polluted the
 * editable text and double-appended on Retry — the #872 hard-cut.
 * [failedSendDraftWithAttachmentRefRestoredOnSwitchBack] proves the attachment
 * TILE survives switch-away-and-back via the production store, and
 * [sendWhileAttachmentUploadInFlightCarriesTheAttachmentNotTextOnly] proves the
 * v0.4.14 on-device drop (Send fired while the upload was still in flight,
 * silently dropping the attachment) is fixed: the send now awaits the upload and
 * carries the attachment.
 */
@RunWith(AndroidJUnit4::class)
class ComposerDraftDurabilityE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var productionStore: SharedPrefsComposerDraftStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Start from a clean prefs file so a previous run on this emulator cannot
        // leak a stored draft into this assertion.
        context.applicationContext
            .getSharedPreferences("composer_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        // The EXACT object Hilt's provideComposerDraftStore binds (it is a
        // pass-through over this same SharedPreferences-backed implementation).
        productionStore = SharedPrefsComposerDraftStore(context)
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
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

    /**
     * Production ViewModel wired to the REAL [SharedPrefsComposerDraftStore]
     * (the same store Hilt binds), not the in-memory double. The mic / Whisper
     * / vault collaborators are test doubles because they are irrelevant to the
     * draft-durability path under test (no audio is recorded in this journey).
     */
    private fun newViewModel(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        composerDraftStore = productionStore,
    )

    private fun renderComposer(
        vm: PromptComposerViewModel,
        onAttachFiles: (() -> Unit)? = null,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val dark = PocketShellColors.Background.toArgb()
            activity.window.decorView.setBackgroundColor(dark)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = dark
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        compose.setContent {
            PocketShellTheme {
                val visible by remember { mutableStateOf(true) }
                if (visible) {
                    val state by vm.uiState.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Surface)
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) {
                        SheetContent(
                            state = state,
                            onClose = {},
                            onDraftChange = vm::onDraftChange,
                            onMicTap = {},
                            onSend = { withEnter -> vm.requestSend(withEnter) },
                            onDiscard = vm::discardDraft,
                            onAttachFiles = onAttachFiles,
                        )
                    }
                }
            }
        }
    }

    private fun awaitComposerComposed() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Issue #832 AC1/AC4 — the maintainer's exact dogfood scenario, end-to-end
     * over the production [SharedPrefsComposerDraftStore]: a failed send keeps the
     * draft in session A, the user switches to session B (field MUST be empty —
     * no bleed, no resurrection), then returns to A and the **visible composer
     * text** is the restored draft, loaded back out of real SharedPreferences.
     *
     * RED on the pre-#832 base: the old `onComposerTargetChanged` discarded the
     * outgoing draft on the switch, so the field was still empty on return —
     * this assertion fails. GREEN with the fix: the per-session store reloads it.
     */
    @Test
    fun failedSendDraftRestoredAsVisibleTextOnSwitchBack() {
        val vm = newViewModel()
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(vm)
        awaitComposerComposed()

        // Type a draft in session A and trigger a (failing) send → the composer
        // keeps the draft and stamps the "Not sent" banner.
        val draftA = "draft kept in session A"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(draftA)
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        // The kept draft is visible AND persisted in the REAL store under A.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(draftA, substring = true)
        assertEquals(draftA, productionStore.load("1/sessionA"))
        WalkthroughScreenshotArtifacts.capture("issue-832-01-sessionA-not-sent-kept")

        // Switch the composer to session B → the field MUST be empty (no bleed).
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionB") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft.isEmpty() && vm.uiState.value.error == null
        }
        compose.waitForIdle()
        compose.onAllNodesWithText(draftA, substring = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
        WalkthroughScreenshotArtifacts.capture("issue-832-02-sessionB-empty-no-bleed")

        // Switch BACK to session A → the kept draft is restored as visible text,
        // round-tripped out of real SharedPreferences.
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionA") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft == draftA
        }
        compose.waitForIdle()
        // The LOAD-BEARING assertion: the user sees their draft restored.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(draftA, substring = true)
        assertEquals(draftA, vm.uiState.value.draft)
        WalkthroughScreenshotArtifacts.capture("issue-832-03-sessionA-restored")
    }

    /**
     * Issue #832 AC2 / #872 — after a failed attachment-send the staged
     * attachment is discoverably restored on switch-away-and-back. Updated for the
     * #872 design (HARD-CUT D22): `restoreFailedSend` restores the CLEAN draft text
     * plus the actual attachment TILE (it no longer folds the raw path into the
     * draft string, which polluted the editable text and double-appended on
     * Retry). The durable per-session store persists BOTH — the clean text under
     * the session key and the attachment ref under the `@att/` slot — so a switch
     * A→B→A reloads the clean draft AND re-shows the attachment tile, restored out
     * of real `SharedPreferences`.
     */
    @Test
    fun failedSendDraftWithAttachmentRefRestoredOnSwitchBack() {
        val vm = newViewModel()
        val attachPath = "~/.pocketshell/attachments/host-1/20260620-shot.png"
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(
            vm,
            onAttachFiles = {
                vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
            },
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_ATTACH_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // Attach a file + type text, then trigger a failing send. restoreFailedSend
        // restores the clean draft + the attachment TILE (not folded into text).
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.attachments.isNotEmpty() }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("review this")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        // The restored draft is the CLEAN text (no raw path), and the attachment is
        // a staged tile. BOTH are persisted durably under session A.
        assertEquals("review this", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(attachPath, vm.uiState.value.attachments.single().remotePath)
        assertEquals("review this", productionStore.load("1/sessionA"))
        assertEquals(
            attachPath,
            productionStore.loadAttachments("1/sessionA").single().remotePath,
        )

        // Switch away (empty — no text, no tiles) and back: the clean draft AND the
        // attachment tile are restored out of real SharedPreferences.
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionB") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft.isEmpty() && vm.uiState.value.attachments.isEmpty()
        }
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionA") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft == "review this" &&
                vm.uiState.value.attachments.size == 1
        }
        compose.waitForIdle()
        // The clean draft text is visible again AND the attachment tile is back.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains("review this", substring = true)
        assertEquals(attachPath, vm.uiState.value.attachments.single().remotePath)
        WalkthroughScreenshotArtifacts.capture("issue-832-04-attachment-tile-restored")
    }

    /**
     * Issue #872 PART B reopen (v0.4.14) — the ACTUAL on-device drop, end-to-end
     * over the production composer UI. The maintainer attaches a screenshot, types
     * a note, taps Send; a reconnect/transport flap slows the attachment SFTP
     * upload so it is STILL in flight when Send fires. On the old code Send
     * CANCELLED the upload and fired a TEXT-ONLY request — the text "went through"
     * but the attachment was silently dropped. This drives the real production
     * `SheetContent` + `requestSend` path with a gated (still-in-flight) upload and
     * proves the delivered send CARRIES the attachment, never a text-only drop.
     *
     * RED on the v0.4.14 base: the dispatched request had ZERO attachments and no
     * attachment path in its text. GREEN with the fix: the send awaits the upload
     * and dispatches WITH the attachment.
     */
    @Test
    fun sendWhileAttachmentUploadInFlightCarriesTheAttachmentNotTextOnly() {
        val vm = newViewModel()
        val attachPath = "~/.pocketshell/attachments/host-1/20260622-slow-shot.png"
        // The host's "onSend" confirms delivery (text goes through), exactly like
        // the maintainer's report where the text was delivered.
        val dispatched = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                dispatched += request
                vm.markSendDelivered()
            }
        }
        // The upload is GATED so it is provably still in flight when Send fires.
        val uploadGate = kotlinx.coroutines.CompletableDeferred<Result<List<String>>>()
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(
            vm,
            onAttachFiles = {
                vm.attachFiles(count = 1) { uploadGate.await() }
            },
        )
        awaitComposerComposed()

        // Tap Attach (upload starts but BLOCKS), then type a note.
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.attachmentUpload is
                PromptComposerViewModel.AttachmentUploadState.Uploading
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("look at this screenshot")
        compose.waitForIdle()

        // Tap Send WHILE the upload is still in flight (the maintainer's race).
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.outboundHandoffInProgress }
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("issue-872-01-send-while-uploading")
        // NO request has gone out yet — never a text-only send that drops the file.
        assertEquals(0, dispatched.size)

        // The upload now completes (post-reconnect the SFTP transfer lands).
        compose.runOnUiThread { uploadGate.complete(Result.success(listOf(attachPath))) }
        compose.waitUntil(timeoutMillis = 5_000) { dispatched.size == 1 }
        compose.waitForIdle()

        // The delivered send CARRIES the attachment — both the structured tile and
        // the attachment path folded into the composed prompt. Base dropped it.
        val request = dispatched.single()
        assertEquals(1, request.attachments.size)
        assertEquals(attachPath, request.attachments.single().remotePath)
        assert(request.text.contains(attachPath)) {
            "expected the composed prompt to carry the attachment path, was: ${request.text}"
        }
        assert(request.text.startsWith("look at this screenshot")) {
            "expected the typed note to lead the prompt, was: ${request.text}"
        }
        WalkthroughScreenshotArtifacts.capture("issue-872-02-send-carried-attachment")
    }
}
