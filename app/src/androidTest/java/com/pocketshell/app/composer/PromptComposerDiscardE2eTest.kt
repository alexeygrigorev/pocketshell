package com.pocketshell.app.composer

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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #746 — the "Not sent" composer state had no Discard control (only Send
 * + the close `×`, which PRESERVES the draft), and the draft was activity-scoped
 * so it bled into other sessions. This on-device journey proves both fixes, and
 * does so on top of #745's send-feedback behaviour (the failed send routes
 * through [PromptComposerViewModel.restoreFailedSend], which folds the staged
 * attachment paths into the restored draft and stamps the "Not sent" banner):
 *
 *  1. A degraded send shows the "Not sent. …or discard the draft." banner with a
 *     real **Discard** button; tapping it clears the text, any attachment, and
 *     the banner — the user no longer has to delete a stale prompt by hand.
 *  2. A draft authored in session A does NOT appear when the composer is opened
 *     in session B: switching the composer's target session discards the stale
 *     draft + banner so it never bleeds across sessions.
 *
 * The `sendRequests` collector is started on a Main-dispatcher scope (the proven
 * pattern from [PromptComposerSendDismissE2eTest]) so the single-consumer
 * `Channel.receiveAsFlow()` is drained deterministically by exactly one
 * collector for the whole test.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerDiscardE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    )

    /**
     * Hosts the composer body driven by a real ViewModel. `visibleState` mirrors
     * the host's "is the composer shown" flag; the discard path keeps it true
     * (the composer stays open, just empty).
     */
    private fun renderComposer(
        vm: PromptComposerViewModel,
        visibleState: androidx.compose.runtime.MutableState<Boolean>,
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
                val visible by remember { visibleState }
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

    @Test
    fun notSentBannerShowsDiscardThatClearsDraftAttachmentsAndBanner() {
        // #746 acceptance #1/#2: the "Not sent" state shows an explicit Discard
        // action that clears text + attachments + dismisses the banner. Under
        // #745 the failed send (restoreFailedSend) folds the attachment path
        // into the restored draft text, so what Discard must clear is the
        // draft (with its folded attachment paths) + the banner.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        val attachPath = "~/.pocketshell/attachments/host-1/20260613-shot.png"
        // Host whose send fails → the composer stays open with the "Not sent"
        // banner restored for retry/discard.
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(
            vm,
            visible,
            onAttachFiles = {
                vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
            },
        )

        // Wait for the composer to actually compose before driving it — on a
        // contended sharded AVD the activity can lag behind the first query
        // ("No compose hierarchies found" otherwise).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_ATTACH_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // Attach a file, then type — both staged.
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.attachments.isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("ничего не происходит")
        compose.waitForIdle()

        // Degraded send → "Not sent" banner with a Discard button. #745 folds
        // the attachment path into the restored draft, so the chip is gone but
        // the draft + banner (and its Discard control) are present.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        compose.onNodeWithText("Not sent.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DISCARD_TAG).assertIsDisplayed().assertIsEnabled()
        WalkthroughScreenshotArtifacts.capture("issue-746-01-not-sent-with-discard")

        // Tap Discard → draft, attachment, and banner all gone.
        compose.onNodeWithTag(COMPOSER_DISCARD_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft.isEmpty() &&
                vm.uiState.value.attachments.isEmpty() &&
                vm.uiState.value.error == null
        }
        compose.waitForIdle()
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        // The banner (and its Discard control) is gone; the composer is a clean
        // slate but still open.
        compose.onAllNodesWithText("Not sent.", substring = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-746-02-after-discard-clean")
    }

    @Test
    fun draftDoesNotBleedIntoAnotherSession() {
        // #746 acceptance #3/#4: a "Not sent" draft authored in session A must
        // NOT appear when the composer is opened in session B. Switching the
        // composer's target session discards the stale draft + banner.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        // Open in session A and create a "Not sent" draft there.
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(vm, visible)

        // Wait for the composer to actually compose before driving it — on a
        // contended sharded AVD the activity can lag behind the first query
        // ("No compose hierarchies found" otherwise).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("draft authored in session A")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        compose.onNodeWithText("draft authored in session A", substring = true)
            .assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-746-03-sessionA-not-sent")

        // Switch the composer to session B (the host re-targets on a switch).
        // The stale draft + banner are gone — they never bleed into B.
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionB") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft.isEmpty() && vm.uiState.value.error == null
        }
        compose.waitForIdle()
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        compose.onAllNodesWithText("draft authored in session A", substring = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
        compose.onAllNodesWithText("Not sent.", substring = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
        WalkthroughScreenshotArtifacts.capture("issue-746-04-sessionB-clean")
    }
}
