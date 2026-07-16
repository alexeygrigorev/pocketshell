package com.pocketshell.app.composer

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1616 PR-1 D33/G10 proof for the maintainer's exact on-device data-loss
 * journey: send prompt A, type prompt B while A is still `Sending…`, then let A
 * finalize. This mounts the real production [PromptComposerSheet] (including its
 * real [PromptComposerSendDispatcher] and `ModalBottomSheet` window), performs
 * actual Compose text input and Send gestures on-device, and treats the host's
 * delivery acknowledgement as a deterministic latch.
 *
 * `visible` is the only host stand-in: it is the same boolean role as
 * `TmuxSessionScreen.showMicSheet`, while the lifecycle logic that decides
 * whether to invoke it is the production dispatcher under test. The terminal
 * pane behind the sheet is irrelevant to draft persistence and dismissal.
 *
 * RED on the pre-PR-1 base: [PromptComposerViewModel.markSendDelivered] clears
 * prompt B from the live state + durable draft store, and the dispatcher invokes
 * `onDismiss`, removing the real sheet. GREEN with PR-1: prompt B remains visible
 * and durable, the delivered queue row is pruned, and the sheet stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerDraftLossOnFinalizeE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var viewModel: PromptComposerViewModel? = null

    @After
    fun tearDown() {
        viewModel?.clearForTest()
        viewModel = null
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
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

    private fun newViewModel(
        drafts: ComposerDraftStore,
        queue: OutboundQueueStore,
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(
                    audio: ByteArray,
                    language: String?,
                ): Result<String> = Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        composerDraftStore = drafts,
        outboundQueueStore = queue,
    ).also { viewModel = it }

    @Test
    fun finalizingPreviousSendKeepsNewDraftVisibleDurableAndSheetOpen() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"

        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = {
                                sendEntered.complete(Unit)
                                releaseDelivery.await()
                                true
                            },
                            composerTargetKey = targetKey,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                            },
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { vm.composerTarget == targetKey }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("first prompt")
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft == "first prompt" && drafts.load(targetKey) == "first prompt"
        }

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            sendEntered.isCompleted && vm.uiState.value.sendInFlight
        }
        // The #971 handoff is real: prompt A moved into exactly one queue row,
        // leaving the editor empty and ready for prompt B.
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, queue.itemsFor(targetKey).size)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performTextInput("I can still report")
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft == "I can still report" &&
                drafts.load(targetKey) == "I can still report"
        }
        WalkthroughScreenshotArtifacts.capture("issue-1616-01-new-draft-during-send")

        // Previous prompt A finalizes in the background.
        releaseDelivery.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { !vm.uiState.value.sendInFlight }

        assertTrue("the real composer sheet must remain open", visible.value)
        assertEquals("I can still report", vm.uiState.value.draft)
        assertEquals("I can still report", drafts.load(targetKey))
        assertTrue("delivered prompt A must be pruned from the queue", queue.itemsFor(targetKey).isEmpty())
        assertNull(queue.claimNext(targetKey))
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("I can still report", substring = true)
        WalkthroughScreenshotArtifacts.capture("issue-1616-02-finalize-keeps-new-draft-open")
    }

    @Test
    fun queuedSendingStateShowsPromptAOnceWhileDraftBAndRealImeRemainUsable() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"

        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = {
                            sendEntered.complete(Unit)
                            releaseDelivery.await()
                            true
                        },
                        composerTargetKey = targetKey,
                        sendTargetSnapshotProvider = {
                            PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                        },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == targetKey }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick().performTextInput("prompt A")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { sendEntered.isCompleted && vm.uiState.value.sendInFlight }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick().performTextInput("draft B")
        compose.waitUntil(5_000) { vm.uiState.value.draft == "draft B" }

        compose.onNodeWithText("Sending", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" · “prompt A”", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertTextContains("draft B", substring = true)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val statusBounds = compose.onNodeWithTag(COMPOSER_STATUS_VIEWPORT_TAG, true)
            .getUnclippedBoundsInRoot()
        val bannerBounds = compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG, true)
            .getUnclippedBoundsInRoot()
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true)
            .getUnclippedBoundsInRoot()
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
        val density = compose.activity.resources.displayMetrics.density
        val keyboardTopDp =
            (compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom) / density
        assertTrue(
            "queue banner must be contained by the #1619 status viewport: banner=$bannerBounds status=$statusBounds",
            bannerBounds.top >= statusBounds.top - 1.dp && bannerBounds.bottom <= statusBounds.bottom + 1.dp,
        )
        assertTrue("Send must remain above the real IME: send=$sendBounds imeTop=$keyboardTopDp", sendBounds.bottom.value <= keyboardTopDp + 1f)
        assertEquals(1, queue.itemsFor(targetKey).size)
        assertEquals("prompt A", queue.itemsFor(targetKey).single().cleanText)
        WalkthroughScreenshotArtifacts.capture(
            "issue-1620-green-queued-sending-draft-keyboard-up",
        )

        releaseDelivery.complete(Unit)
        compose.waitUntil(5_000) { !vm.uiState.value.sendInFlight }
    }
}
