package com.pocketshell.app.composer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * Issue #1743 keeps that production modal mounted for the queued-Sending case,
 * dispatches deterministic keyboard insets to every active app window root,
 * and reads the exact inset from the owning modal Compose tree. Draft B is long
 * enough to make the production available-above-keyboard cap bind; the same-root
 * editor viewport must measurably contract from its keyboard-down geometry. The
 * test edits draft B again only after that keyboard-up state is proven, so an
 * activity-decor inset, a short intrinsic-height sheet, or pre-inset text
 * injection cannot make the usability proof pass.
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

    /**
     * Issue #695 recurrence: the production screen-scoped dispatcher must close
     * the empty real sheet at local queue acceptance, not after the host's slow
     * suspend delivery callback.
     */
    @Test
    fun acceptedPromptDismissesBeforeTenSecondHostDeliveryCompletes() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val sendCompleted = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"

        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = {
                            sendEntered.complete(Unit)
                            delay(10_000)
                            sendCompleted.complete(Unit)
                            true
                        },
                        onDelivered = { visible.value = false },
                    )
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = targetKey,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                            },
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == targetKey }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick()
            .performTextInput("send without waiting")
        val tappedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()

        compose.waitUntil(2_000) { !visible.value }
        val dismissedAfterMs = SystemClock.elapsedRealtime() - tappedAt
        assertTrue(
            "local acceptance must dismiss promptly, took ${dismissedAfterMs}ms",
            dismissedAfterMs < 2_000,
        )
        compose.waitUntil(2_000) { sendEntered.isCompleted }
        assertTrue("host delivery must have started", sendEntered.isCompleted)
        assertFalse("dismissal must not await the injected 10s host callback", sendCompleted.isCompleted)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, queue.itemsFor(targetKey).size)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertDoesNotExist()
    }

    /**
     * The local acceptance event and the dismissal reduction are distinct main
     * loop turns. New typing in that window owns the sheet and must survive
     * exactly, even while the accepted row continues delivering.
     */
    @Test
    fun newDraftBeforeAcceptanceDismissReductionKeepsSheetOpenExactly() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val reductionEntered = CompletableDeferred<Unit>()
        val releaseReduction = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"
        vm.beforeHandoffAutoCloseReductionForTest = {
            reductionEntered.complete(Unit)
            releaseReduction.await()
        }

        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = {
                            releaseDelivery.await()
                            true
                        },
                        onDelivered = { visible.value = false },
                    )
                    if (visible.value) {
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = targetKey,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                            },
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.composerTarget == targetKey }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick()
            .performTextInput("accepted prompt")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) {
            reductionEntered.isCompleted &&
                vm.uiState.value.draft.isEmpty() &&
                queue.itemsFor(targetKey).size == 1
        }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .performClick()
            .performTextInput("brand new draft")
        compose.waitUntil(5_000) {
            vm.uiState.value.draft == "brand new draft" &&
                drafts.load(targetKey) == "brand new draft"
        }
        releaseReduction.complete(Unit)
        compose.waitForIdle()

        assertTrue("new input must retain ownership of the real sheet", visible.value)
        assertEquals("brand new draft", vm.uiState.value.draft)
        assertEquals("brand new draft", drafts.load(targetKey))
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true)
            .assertIsDisplayed()
            .assertTextContains("brand new draft", substring = true)
        releaseDelivery.complete(Unit)
        compose.waitUntil(5_000) { !vm.uiState.value.sendInFlight }
        assertTrue("post-acceptance completion must not close over the new draft", visible.value)
        assertEquals("brand new draft", vm.uiState.value.draft)
    }

    @Test
    fun finalizingPreviousSendKeepsNewDraftVisibleDurableAndSheetOpen() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val reductionEntered = CompletableDeferred<Unit>()
        val releaseReduction = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"
        vm.beforeHandoffAutoCloseReductionForTest = {
            reductionEntered.complete(Unit)
            releaseReduction.await()
        }

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
            sendEntered.isCompleted &&
                reductionEntered.isCompleted &&
                vm.uiState.value.sendInFlight
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
        releaseReduction.complete(Unit)
        compose.waitForIdle()
        assertTrue("new typing must defeat the pending acceptance dismissal", visible.value)
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
    fun queuedSendingStateShowsPromptAOnceWhileDraftBAndSyntheticImeRemainUsable() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val targetKey = "1/session-a"
        val sheetStateRef = AtomicReference<SheetState?>()

        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    val sheetState =
                        rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    SideEffect { sheetStateRef.set(sheetState) }
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
                        modifier = Modifier.observeProductionSheetIme(),
                        sheetState = sheetState,
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            val sheetState = sheetStateRef.get()
            vm.composerTarget == targetKey &&
                sheetState != null &&
                sheetState.currentValue != SheetValue.Hidden &&
                sheetState.currentValue == sheetState.targetValue
        }
        val sheetState = checkNotNull(sheetStateRef.get())
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick().performTextInput("prompt A")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { sendEntered.isCompleted && vm.uiState.value.sendInFlight }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick().performTextInput(LONG_DRAFT_B)
        compose.waitUntil(5_000) {
            vm.uiState.value.draft == LONG_DRAFT_B &&
                drafts.load(targetKey) == LONG_DRAFT_B
        }

        compose.onNodeWithText("Sending", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" · “prompt A”", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertTextContains(LONG_DRAFT_B, substring = false)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertPromptAExactlyOnce(queue, targetKey)

        hideRealImeAndAssertHidden(
            "Synthetic #1743 proof requires the physical IME to be absent before dispatch.",
        )
        assertActivityDecorHasNoRealImeInset()
        val keyboardDown = applySyntheticKeyboardDownAndReadGeometry()
        val syntheticIme = applySyntheticImeAndAssertObservedInProductionSheet(sheetState)

        // Exercise the real editor again only after the mounted modal has
        // Compose-observed the exact keyboard-up inset. Text injection before
        // this point cannot prove the keyboard-up editor remains usable.
        val updatedDraft = "$LONG_DRAFT_B$POST_IME_DRAFT_SUFFIX"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performTextInput(POST_IME_DRAFT_SUFFIX)
        compose.waitUntil(5_000) {
            vm.uiState.value.draft == updatedDraft &&
                drafts.load(targetKey) == updatedDraft
        }
        assertNoPhysicalImeWindow(
            "Physical IME appeared after the post-inset #1743 editor mutation.",
        )
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertTextContains(updatedDraft, substring = false)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        assertPromptAExactlyOnce(queue, targetKey)

        val sheetNode = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val modalRoot = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === sheetNode.root }
        val statusNode = assertNodeFullyWithinOwningModalRoot(
            COMPOSER_STATUS_VIEWPORT_TAG,
            sheetNode,
            modalRoot,
        )
        val bannerNode = assertNodeFullyWithinOwningModalRoot(
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            sheetNode,
            modalRoot,
        )
        val draftNode = assertNodeFullyWithinOwningModalRoot(
            COMPOSER_DRAFT_TAG,
            sheetNode,
            modalRoot,
        )
        val sendNode = assertNodeFullyWithinOwningModalRoot(
            COMPOSER_SEND_ENTER_TAG,
            sheetNode,
            modalRoot,
        )
        val keyboardTopPx = modalRoot.boundsInRoot.bottom - syntheticIme.observedImeBottomPx
        val keyboardUpDraftHeight = draftNode.boundsInRoot.height
        val requiredDraftCompression =
            syntheticIme.observedImeBottomPx * MINIMUM_DRAFT_COMPRESSION_FRACTION

        assertNoPhysicalImeWindow(
            "Physical IME appeared before the final #1743 geometry and screenshot proof.",
        )
        // This cap-specific oracle intentionally precedes the broader keyboard-
        // boundary checks below. With the cap neutralized, both describe the same
        // bad geometry, but the overflowing editor's failure must identify the
        // missing available-above-keyboard cap directly.
        assertTrue(
            "The exact nonzero modal IME inset must make the production " +
                "available-above-keyboard cap bind: this deliberately overflowing " +
                "real editor must surrender measurable viewport height while sticky " +
                "Send remains reachable. keyboardDownDraftHeight=" +
                "${keyboardDown.draftHeightPx} keyboardUpDraftHeight=$keyboardUpDraftHeight " +
                "requiredCompression=$requiredDraftCompression observedImeBottomPx=" +
                "${syntheticIme.observedImeBottomPx} draft=${draftNode.boundsInRoot} " +
                "send=${sendNode.boundsInRoot}",
            keyboardDown.draftHeightPx - keyboardUpDraftHeight >= requiredDraftCompression,
        )
        assertTrue(
            "Queue banner must be contained by the #1619 status viewport in the same " +
                "modal coordinate root. banner=${bannerNode.boundsInRoot} " +
                "status=${statusNode.boundsInRoot}",
            bannerNode.boundsInRoot.top >= statusNode.boundsInRoot.top - ROOT_SLOP_PX &&
                bannerNode.boundsInRoot.bottom <=
                statusNode.boundsInRoot.bottom + ROOT_SLOP_PX,
        )
        assertTrue(
            "Queue banner, editor, and Send action must all stay above the synthetic " +
                "keyboard boundary in their owning modal root. keyboardTopPx=$keyboardTopPx " +
                "banner=${bannerNode.boundsInRoot} draft=${draftNode.boundsInRoot} " +
                "send=${sendNode.boundsInRoot}",
            listOf(bannerNode, draftNode, sendNode).all {
                it.boundsInRoot.bottom <= keyboardTopPx + ROOT_SLOP_PX
            },
        )
        assertTrue(
            "Draft editor must end above the Send action. draft=${draftNode.boundsInRoot} " +
                "send=${sendNode.boundsInRoot}",
            draftNode.boundsInRoot.bottom <= sendNode.boundsInRoot.top + ROOT_SLOP_PX,
        )
        assertTrue(
            "The mounted production modal must materially lift Send when the exact " +
                "synthetic IME reaches its owning root. keyboardDownSendBottom=" +
                "${keyboardDown.sendBottomPx} keyboardUpSendBottom=" +
                "${sendNode.boundsInRoot.bottom} " +
                "observedImeBottomPx=${syntheticIme.observedImeBottomPx}",
            keyboardDown.sendBottomPx - sendNode.boundsInRoot.bottom >=
                syntheticIme.observedImeBottomPx * MINIMUM_LIFT_FRACTION,
        )
        println(
            "ISSUE1743_MODAL_IME root=${modalRoot.boundsInRoot} " +
                "observedImeBottomPx=${syntheticIme.observedImeBottomPx} " +
                "keyboardTopPx=$keyboardTopPx keyboardDown=$keyboardDown " +
                "keyboardUpDraftHeight=$keyboardUpDraftHeight " +
                "banner=${bannerNode.boundsInRoot} draft=${draftNode.boundsInRoot} " +
                "send=${sendNode.boundsInRoot}",
        )
        assertNoPhysicalImeWindow(
            "Physical IME appeared immediately before the #1743 screenshot capture.",
        )
        WalkthroughScreenshotArtifacts.capture(
            "issue-1620-green-queued-sending-draft-keyboard-up",
        )

        releaseDelivery.complete(Unit)
        compose.waitUntil(5_000) { !vm.uiState.value.sendInFlight }
        assertEquals(updatedDraft, vm.uiState.value.draft)
        assertEquals(updatedDraft, drafts.load(targetKey))
    }

    private fun assertPromptAExactlyOnce(
        queue: OutboundQueueStore,
        targetKey: String,
    ) {
        val queueItems = queue.itemsFor(targetKey)
        assertEquals("exactly one queue row must own prompt A", 1, queueItems.size)
        assertEquals("prompt A", queueItems.single().cleanText)
        assertEquals(
            "Sending status must have exactly one visible owner",
            1,
            compose.onAllNodesWithText(
                "Sending",
                substring = false,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            "prompt A must be rendered exactly once in the sending banner",
            1,
            compose.onAllNodesWithText(
                " · “prompt A”",
                substring = false,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
    }

    private data class SyntheticImeGeometry(
        val observedImeBottomPx: Int,
    )

    private data class KeyboardDownGeometry(
        val draftHeightPx: Float,
        val sendBottomPx: Float,
    )

    private fun applySyntheticKeyboardDownAndReadGeometry(): KeyboardDownGeometry {
        repeat(2) {
            applySyntheticInsets(imeBottomPx = 0, requireModalRoot = true)
            compose.waitForIdle()
        }
        val observedIme = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(PRODUCTION_SHEET_IME_BOTTOM_PX)
            ?: -1
        assertEquals(
            "Keyboard-down baseline must be Compose-observed as exact zero in the " +
                "mounted production modal.",
            0,
            observedIme,
        )
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return KeyboardDownGeometry(
            draftHeightPx = draftBounds.height,
            sendBottomPx = sendBounds.bottom,
        )
    }

    private fun applySyntheticImeAndAssertObservedInProductionSheet(
        sheetState: SheetState,
    ): SyntheticImeGeometry {
        val expectedIme = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()
        repeat(2) {
            applySyntheticInsets(
                imeBottomPx = expectedIme,
                requireModalRoot = true,
            )
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) {
            sheetState.currentValue == sheetState.targetValue
        }
        val sheetNode = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val observedIme = sheetNode.config.getOrNull(PRODUCTION_SHEET_IME_BOTTOM_PX)
            ?: 0
        assertTrue(
            "Synthetic ime() inset must be Compose-observed exactly in the mounted " +
                "production ModalBottomSheet root; keyboard-down, stale, or activity-only " +
                "dispatch is vacuous. observedImeBottomPx=$observedIme " +
                "expectedImeBottomPx=$expectedIme",
            expectedIme > 0 && observedIme == expectedIme,
        )
        val modalRoot = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === sheetNode.root }
        assertTrue(
            "Mounted production modal Compose root must have nonzero geometry. " +
                "bounds=${modalRoot.boundsInRoot}",
            modalRoot.boundsInRoot.height > 0f,
        )
        return SyntheticImeGeometry(observedImeBottomPx = observedIme)
    }

    private fun assertNodeFullyWithinOwningModalRoot(
        tag: String,
        sheetNode: androidx.compose.ui.semantics.SemanticsNode,
        modalRoot: androidx.compose.ui.semantics.SemanticsNode,
    ): androidx.compose.ui.semantics.SemanticsNode {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "Node '$tag' must belong to the mounted PromptComposerSheet modal root.",
            node.root === sheetNode.root && modalRoot.root === sheetNode.root,
        )
        val bounds = node.boundsInRoot
        val rootBounds = modalRoot.boundsInRoot
        assertTrue(
            "Node '$tag' must remain fully contained in the mounted production modal root. " +
                "node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX,
        )
        return node
    }

    private fun Modifier.observeProductionSheetIme(): Modifier = composed {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        testTag(PRODUCTION_SHEET_TAG).semantics {
            this[PRODUCTION_SHEET_IME_BOTTOM_PX] = imeBottomPx
        }
    }

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        requireModalRoot: Boolean,
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Issue #1743 synthetic modal-window proof requires API 29+ WindowInspector; " +
                "deviceApi=${Build.VERSION.SDK_INT}"
        }
        assertNoPhysicalImeWindow(
            "Synthetic #1743 proof cannot dispatch while a physical IME is visible.",
        )
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
            .build()
        compose.activityRule.scenario.onActivity { activity ->
            val roots = activeAppWindowRoots(activity)
            val activityDecor = activity.window.decorView
            val modalRoots = roots.filterNot { it === activityDecor }
            check(roots.any { it === activityDecor }) {
                "Active app roots must include the activity decor."
            }
            if (requireModalRoot && modalRoots.isEmpty()) {
                error("Mounted PromptComposerSheet modal root disappeared before inset dispatch.")
            }
            println(
                "ISSUE1743_SYNTHETIC_WINDOW_ROOTS count=${roots.size} " +
                    "modalCount=${modalRoots.size} " +
                    "roots=${roots.map { "${it.javaClass.name}:${it.width}x${it.height}" }}",
            )
            roots.forEach { root -> ViewCompat.dispatchApplyWindowInsets(root, insets) }
        }
    }

    private fun activeAppWindowRoots(activity: ComponentActivity) =
        WindowInspector.getGlobalWindowViews()
            .asSequence()
            .map { it.rootView }
            .distinctBy { System.identityHashCode(it) }
            .filter { root ->
                root.isAttachedToWindow &&
                    root.context.applicationContext.packageName ==
                    activity.applicationContext.packageName
            }
            .toList()

    private fun assertActivityDecorHasNoRealImeInset() {
        compose.activityRule.scenario.onActivity { activity ->
            val activityIme = ViewCompat.getRootWindowInsets(activity.window.decorView)
            check(
                activityIme?.isVisible(WindowInsetsCompat.Type.ime()) != true &&
                    (activityIme?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) == 0,
            ) {
                "Synthetic #1743 proof cannot start while the activity owns a real " +
                    "IME inset: insets=$activityIme"
            }
        }
    }

    private fun hideRealImeAndAssertHidden(message: String) {
        hideRealImeBestEffort()
        assertTrue(
            "$message Physical input-method window remained visible: " +
                visiblePhysicalImeWindows(),
            waitForPhysicalImeWindow(expected = false),
        )
        assertTrue(
            "$message An app root still reports a visible positive real-IME inset.",
            waitForAnyAppRootImeVisible(expected = false),
        )
        assertFalse(
            "$message Activity decor did not report IME visibility=false.",
            waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = false,
                timeoutMs = REAL_IME_TIMEOUT_MS,
            ),
        )
        assertNoPhysicalImeWindow(message)
    }

    private fun hideRealImeBestEffort() {
        runCatching {
            compose.activityRule.scenario.onActivity { activity ->
                val inputMethodManager =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                activeAppWindowRoots(activity).forEach { root ->
                    ViewCompat.getWindowInsetsController(root)
                        ?.hide(WindowInsetsCompat.Type.ime())
                    root.windowToken?.let { token ->
                        inputMethodManager.hideSoftInputFromWindow(token, 0)
                    }
                }
                ViewCompat.getWindowInsetsController(activity.window.decorView)
                    ?.hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun assertNoPhysicalImeWindow(message: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline =
            SystemClock.elapsedRealtime() + PHYSICAL_IME_ABSENCE_STABILITY_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val windows = visiblePhysicalImeWindows()
            assertTrue("$message visibleImeWindows=$windows", windows.isEmpty())
            SystemClock.sleep(50)
        }
    }

    private fun waitForPhysicalImeWindow(
        expected: Boolean,
        timeoutMs: Long = REAL_IME_TIMEOUT_MS,
    ): Boolean = waitForWallClock(timeoutMs) {
        visiblePhysicalImeWindows().isNotEmpty() == expected
    }

    private fun waitForAnyAppRootImeVisible(
        expected: Boolean,
        timeoutMs: Long = REAL_IME_TIMEOUT_MS,
    ): Boolean = waitForWallClock(timeoutMs) {
        readAnyAppRootImeVisible() == expected
    }

    private fun waitForWallClock(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            SystemClock.sleep(50)
        }
        return predicate()
    }

    private fun readAnyAppRootImeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity ->
            visible = activeAppWindowRoots(activity).any { root ->
                val insets = ViewCompat.getRootWindowInsets(root)
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            }
        }
        return visible
    }

    /**
     * Accessibility exposes the physical IME independently of synthetic
     * WindowInsetsCompat dispatch, preventing a green modal-boundary oracle
     * while LatinIME visibly covers the composer controls.
     */
    private fun visiblePhysicalImeWindows(): List<String> =
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .let { automation ->
                val serviceInfo = automation.serviceInfo
                if (
                    serviceInfo.flags and
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0
                ) {
                    serviceInfo.flags =
                        serviceInfo.flags or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    automation.serviceInfo = serviceInfo
                }
                automation.windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .map { window ->
                        val rootPackage = runCatching {
                            window.root?.packageName?.toString()
                        }.getOrNull()
                        "package=$rootPackage active=${window.isActive} " +
                            "focused=${window.isFocused} bounds=" +
                            "${android.graphics.Rect().also(window::getBoundsInScreen)}"
                    }
            }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val SYNTHETIC_IME_HEIGHT_DP = 295f
        const val MINIMUM_LIFT_FRACTION = 0.5f
        const val MINIMUM_DRAFT_COMPRESSION_FRACTION = 0.08f
        const val ROOT_SLOP_PX = 2f
        const val REAL_IME_TIMEOUT_MS = 30_000L
        const val PHYSICAL_IME_ABSENCE_STABILITY_MS = 500L
        const val PRODUCTION_SHEET_TAG = "issue1743-production-composer-sheet"
        const val POST_IME_DRAFT_SUFFIX = " remains editable after keyboard lift"
        val LONG_DRAFT_B = (1..14).joinToString(separator = " ") {
            "draft B segment $it stays durable while prompt A is sending;"
        }
        val PRODUCTION_SHEET_IME_BOTTOM_PX =
            SemanticsPropertyKey<Int>("Issue1743ProductionSheetImeBottomPx")
    }
}
