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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issues #695 + #694 — full-composer send / dismiss / failed-send journey on
 * the device, with full-device screenshots of the exact reported states.
 *
 * #695: "first the message disappears from the Composer and then sometimes the
 * Composer stays on the screen." The composer dismisses on the SUCCESS path
 * only (the host's `onSend` returning true), and the failed-send path keeps the
 * composer OPEN with the text restored for retry. This test drives the real
 * [PromptComposerViewModel] through the same `sendRequests` ->
 * (`onDismiss` | `restoreFailedSend`) wiring that [PromptComposerSheet] uses,
 * with a `visible` flag standing in for the host's "is the composer shown"
 * state.
 *
 * #694: attaching a file then typing must show BOTH (chip + typed text), the
 * send must carry the attachment paths, and a degraded-connection "Not sent"
 * must keep the attachment paths in the restored draft for the resend — never
 * a silent drop.
 *
 * The `sendRequests` collector is started explicitly on a Main-dispatcher scope
 * (the proven pattern from [PromptComposerSendNoKeyboardTest]) rather than a
 * `LaunchedEffect` inside the sheet composition, so the single-consumer
 * `Channel.receiveAsFlow()` is drained deterministically by exactly one
 * collector for the whole test.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSendDismissE2eTest {

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
     * Hosts the composer body driven by a real ViewModel. `visibleState`
     * mirrors the host's "is the composer shown" flag — the test's
     * `sendRequests` collector flips it to false on a SUCCESSFUL send
     * (the dismiss) and leaves it true on a FAILED send (stays open).
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
                            onAttachFiles = onAttachFiles,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun successfulSendDismissesComposerEveryTime() {
        // #695: a SUCCESSFUL send clears the draft AND dismisses the composer.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        // Host whose send succeeds → hide the composer (the dismiss).
        collectorScope.launch {
            vm.sendRequests.collect { visible.value = false }
        }
        renderComposer(vm, visible)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("deploy the staging branch")
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("issue-695-01-composed-before-send")

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        // On success the host hides the composer: the draft field is gone.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertDoesNotExist()
        WalkthroughScreenshotArtifacts.capture("issue-695-02-dismissed-after-send")
    }

    @Test
    fun failedSendKeepsComposerOpenWithRestoredText() {
        // #695 acceptance #3 / #390: a FAILED send keeps the composer OPEN with
        // the text restored and the "Not sent" banner so the user can retry.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        // Host whose send fails → restore the draft (the composer stays open).
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        renderComposer(vm, visible)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("restart the worker pool")
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()

        // The composer is STILL shown; the draft came back and the "Not sent"
        // banner is visible. Wait on the ViewModel state (the source of truth
        // the sheet renders from), then assert the rendered banner + draft.
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        assertEquals("restart the worker pool", vm.uiState.value.draft)
        assertTrue(visible.value)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Not sent.", substring = true).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-695-03-not-sent-stays-open")
    }

    @Test
    fun attachThenTypeShowsBothAndFailedSendKeepsAttachmentPaths() {
        // #694: attach a file, then type -> both the chip and the typed text
        // are visible. A degraded-connection "Not sent" keeps the attachment
        // paths in the restored draft so the resend still includes them; a
        // later successful resend dispatches and dismisses.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        val attachPath = "~/.pocketshell/attachments/host-1/20260611-report.png"
        var sendSucceeds = false
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                if (sendSucceeds) visible.value = false else vm.restoreFailedSend(request)
            }
        }
        // The real SAF picker can't be driven from instrumentation, so the
        // attach button stages a known path through the production
        // attachFiles() path (the same call the picker callback makes).
        renderComposer(
            vm,
            visible,
            onAttachFiles = {
                vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
            },
        )

        // Attach: drives the real attachFiles path through SheetContent's
        // attach button. The chip appears; the draft stays empty.
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.attachments.isNotEmpty()
        }
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals("", vm.uiState.value.draft)

        // Type after attaching: the chip survives and the typed text shows.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("what is wrong here")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        assertEquals(1, vm.uiState.value.attachments.size)
        WalkthroughScreenshotArtifacts.capture("issue-694-01-attach-then-type")

        // Send while degraded: it fails. The restored draft keeps the
        // attachment path so it is not silently dropped.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        val restored = vm.uiState.value.draft
        assertTrue(
            "restored draft must keep the attachment path for resend (#694)",
            restored.contains("20260611-report.png"),
        )
        assertTrue(restored.contains("what is wrong here"))
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-694-02-not-sent-keeps-attachment")

        // Reconnect (send succeeds) and resend: the request still carries the
        // attachment path and the composer dismisses.
        sendSucceeds = true
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertDoesNotExist()
        WalkthroughScreenshotArtifacts.capture("issue-694-03-resend-after-reconnect")
    }
}
