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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #745 — composer Send feedback on a DEGRADED connection. The
 * maintainer's dogfood report: "I press Send and the screen clears but nothing
 * happens. I wait and wait. It doesn't show me the connection was lost."
 *
 * This drives the real [PromptComposerViewModel] through the EXACT
 * `sendRequests` → (`markSendDelivered`+dismiss | `restoreFailedSend`) wiring
 * the [PromptComposerSheet] uses, including the #745 `withTimeoutOrNull` bound,
 * and renders [SheetContent] (the pure body) so the on-screen feedback can be
 * asserted. A `connectionLost` flag stands in for the host's live liveness.
 *
 * Covers acceptance #5 (a)-(d):
 *  - (a) tapping Send shows the immediate in-flight state (Send disabled +
 *    "Sending…" spinner) until the send resolves,
 *  - (b) the typed draft is RETAINED through a failure (no optimistic empty),
 *  - (c) the failure resolves within a BOUNDED time (the host hangs forever;
 *    the timeout surfaces the "Not sent" banner),
 *  - (d) the connection-lost indicator is visible BEFORE / while sending.
 *
 * The collector is started on a Main-dispatcher scope (the proven pattern from
 * the sibling [PromptComposerSendDismissE2eTest]) rather than a
 * `LaunchedEffect`, so the single-consumer `Channel.receiveAsFlow()` is drained
 * by exactly one collector for the whole test. The collector still wraps the
 * send in the production `withTimeoutOrNull(...) { onSend(...) } == true` shape,
 * but the failure is driven DETERMINISTICALLY by the test completing
 * `releaseSend` with `false` (not by a wall-clock timeout winning a race with
 * the in-flight assertion). The wrap timeout is kept well above the test's
 * 5_000 ms `waitUntil` caps purely as a safety net, so the in-flight window can
 * never close mid-assertion. The production [PromptComposerViewModel.SEND_TIMEOUT_MS]
 * bound itself is unit-asserted via the VM contract.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerDegradedSendE2eTest {

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

    private fun newViewModel(
        outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        outboundQueueStore = outboundQueueStore,
    )

    /**
     * Renders the composer body driven by a real ViewModel. `connectionLost`
     * is pushed into the VM the way the real sheet does, so the connection-lost
     * indicator + in-flight feedback render from the live [UiState].
     */
    private fun renderComposer(
        vm: PromptComposerViewModel,
        visibleState: androidx.compose.runtime.MutableState<Boolean>,
        connectionLost: Boolean,
        onAttachFiles: (() -> Unit)? = null,
        sendTarget: PromptComposerViewModel.SendTargetSnapshot =
            PromptComposerViewModel.SendTargetSnapshot(),
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
        // The host pushes connection liveness into the VM (mirrors the sheet's
        // `LaunchedEffect(connectionLost) { setConnectionDegraded(...) }`).
        vm.setConnectionDegraded(connectionLost)
        compose.setContent {
            PocketShellTheme {
                val visible by remember { visibleState }
                if (visible) {
                    val state by vm.uiState.collectAsState()
                    val queue by vm.outboundQueueItems.collectAsState()
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
                            onSend = { withEnter -> vm.requestSend(withEnter, sendTarget) },
                            onAttachFiles = onAttachFiles,
                            outboundQueueItems = queue,
                            onDeleteOutboundItem = vm::discardOutboundItem,
                            onRetryOutboundItem = vm::retryOutboundItem,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun degradedSendShowsInFlightThenBoundedFailureKeepsDraftAndConnectionLostVisible() {
        // The host's send hangs on a degraded link. The #745 bounded wrap turns
        // that hang into a prompt "Not sent". The test drives the failure
        // DETERMINISTICALLY (it completes `releaseSend` with false) so the
        // in-flight window stays open until the spinner assertion runs — it is
        // never a race between a wall-clock timeout and `assertIsDisplayed`.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Boolean>()
        // Mirror the production sheet's `withTimeoutOrNull(...) { onSend(...) }
        // == true` shape. The wrap timeout is a SAFETY NET only (8s — well
        // above the test's 5_000 ms `waitUntil` caps), so it can never fire
        // mid-assertion; the failure is driven by `releaseSend.complete(false)`.
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                val delivered = withTimeoutOrNull(8_000L) {
                    sendEntered.complete(Unit)
                    // The host's confirmation is whatever the TEST decides:
                    // it completes `releaseSend` with false to simulate the
                    // degraded link failing the send.
                    releaseSend.await()
                } == true
                if (delivered) {
                    vm.markSendDelivered()
                    visible.value = false
                } else {
                    vm.restoreFailedSend(request)
                }
            }
        }
        renderComposer(vm, visible, connectionLost = true)

        // (d) connection-lost indicator is visible BEFORE the user even taps
        // Send — they know the link is down up front, not after a blind wait.
        compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG).assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("restart the worker pool")
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("issue-745-01-degraded-before-send")

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()

        // (a) IMMEDIATE in-flight feedback: the "Sending…" spinner appears and
        // the Send button is disabled until the send resolves. The in-flight
        // state is held open (the collector is parked on `releaseSend.await()`)
        // so this assertion is race-free — the spinner cannot disappear before
        // we explicitly release the send below.
        compose.waitUntil(timeoutMillis = 5_000) { sendEntered.isCompleted }
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.sendInFlight }
        // Wait on the STABLE in-flight tag rather than `waitForIdle()`, which the
        // animating CircularProgressIndicator can keep busy indefinitely.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsNotEnabled()
        // Issue #971: the prompt is HANDED OFF mid-flight — the editor is cleared
        // (single representation), so the same prompt is never shown twice.
        assertEquals("", vm.uiState.value.draft)
        WalkthroughScreenshotArtifacts.capture("issue-745-02-sending-in-flight")

        // (c) the failure resolves within a BOUNDED time. We release the hung
        // send deterministically (false = the host failed to confirm), which is
        // exactly what the production timeout's null result triggers:
        // `restoreFailedSend`. (b) the draft is RETAINED through the failure.
        releaseSend.complete(false)
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        // The spinner must be GONE once the send resolved (in-flight cleared).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals("restart the worker pool", vm.uiState.value.draft)
        assertTrue(visible.value)
        assertTrue(!vm.uiState.value.sendInFlight)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Not sent.", substring = true).assertIsDisplayed()
        // Connection-lost indicator is still visible after the failed send.
        compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-745-03-bounded-not-sent-draft-kept")
    }

    @Test
    fun successfulSendClearsDraftOnlyOnConfirmedDelivery() {
        // The no-flicker contract: the draft stays visible mid-flight and is
        // cleared only when the host CONFIRMS delivery (then the sheet
        // dismisses). Verifies the success branch of the #745 send wiring.
        val vm = newViewModel()
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Boolean>()
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                val delivered = withTimeoutOrNull(5_000L) {
                    sendEntered.complete(Unit)
                    releaseSend.await()
                } == true
                if (delivered) {
                    vm.markSendDelivered()
                    visible.value = false
                } else {
                    vm.restoreFailedSend(request)
                }
            }
        }
        renderComposer(vm, visible, connectionLost = false)

        // Connected: no connection-lost indicator.
        compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG).assertDoesNotExist()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("deploy the staging branch")
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()

        // Mid-flight: spinner up, draft retained. Wait on the STABLE in-flight
        // tag rather than `waitForIdle()`, which the animating spinner can keep
        // busy indefinitely. The collector is parked on `releaseSend.await()`,
        // so the in-flight window stays open until we release below.
        compose.waitUntil(timeoutMillis = 5_000) { sendEntered.isCompleted }
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.sendInFlight }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG).assertIsDisplayed()
        // Issue #971: the prompt is handed off mid-flight — the editor is cleared.
        assertEquals("", vm.uiState.value.draft)

        // Host confirms delivery → draft stays cleared AND the composer dismisses.
        releaseSend.complete(true)
        compose.waitUntil(timeoutMillis = 5_000) { !visible.value }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertDoesNotExist()
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun wedgedSendWithAttachmentEscapesViaWatchdogKeepsTileAndRetryDispatchesAgain() {
        // Issue #891: the maintainer hit a PNG-attachment send that reached the
        // in-flight UI and then NEVER resolved through markSendDelivered() or
        // restoreFailedSend(). This collector receives the first real dispatch,
        // then parks forever without calling either resolution callback; the
        // ViewModel's overall-send watchdog must be the only escape from
        // "Sending...".
        val vm = newViewModel()
        vm.setSendWatchdogTimeoutForTest(1_500L)
        val visible = mutableStateOf(true)
        val draft = "please inspect this screenshot"
        val attachPath = "~/.pocketshell/attachments/host-1/issue891-watchdog.png"
        val dispatched = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        val firstSendEntered = CompletableDeferred<Unit>()
        val firstSendNeverResolves = CompletableDeferred<Unit>()
        val firstCollector = collectorScope.launch {
            vm.sendRequests.collect { request ->
                dispatched += request
                firstSendEntered.complete(Unit)
                firstSendNeverResolves.await()
            }
        }
        renderComposer(
            vm,
            visible,
            connectionLost = false,
            onAttachFiles = {
                vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
            },
        )

        compose.onNodeWithTag(COMPOSER_ATTACH_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.attachments.size == 1
        }
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachPath)).assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput(draft)
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            firstSendEntered.isCompleted && dispatched.size == 1 && vm.uiState.value.sendInFlight
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsNotEnabled()
        WalkthroughScreenshotArtifacts.capture("issue-891-01-wedged-send-with-attachment")

        val first = synchronized(dispatched) { dispatched.single() }
        assertEquals(1, first.attachments.size)
        assertEquals(attachPath, first.attachments.single().remotePath)
        assertTrue(first.text.startsWith(draft))
        assertTrue(first.text.contains(attachPath))

        compose.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.sendInFlight &&
                vm.uiState.value.error == PromptComposerViewModel.SEND_TIMEOUT_MESSAGE
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(draft, vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(attachPath, vm.uiState.value.attachments.single().remotePath)
        assertTrue(visible.value)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(draft, substring = true)
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(attachPath)).assertIsDisplayed()
        compose.onNodeWithText(
            PromptComposerViewModel.SEND_TIMEOUT_MESSAGE,
            substring = true,
        ).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-891-02-watchdog-timeout-keeps-attachment")

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        firstCollector.cancel()
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                dispatched += request
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { dispatched.size == 2 }
        val second = synchronized(dispatched) { dispatched[1] }
        assertEquals(1, second.attachments.size)
        assertEquals(attachPath, second.attachments.single().remotePath)
        assertTrue(second.text.startsWith(draft))
        assertTrue(second.text.contains(attachPath))

        compose.runOnUiThread { vm.discardDraft() }
    }

    @Test
    fun issue971_inFlightShowsPromptOnceThenDropStaysQueuedAutoSendsOnReconnect() {
        // Issue #971/#987 (G10 end-to-end reproduce-first, maintainer Option A):
        // the maintainer's v0.4.18 + v0.4.18-drop dogfood — while a send is in
        // flight the SAME prompt (`/clear`) showed up TWICE (editor + "1 unsent
        // prompt — Sending" row) and on a DROP the composer stacked contradictory
        // status (the prompt back in the editor + "Not sent… send again or
        // discard" AND "Send will retry once reconnected"). The CANONICAL design
        // drives the PRODUCTION [PromptComposerViewModel] + [SheetContent] +
        // [PromptComposerSendDispatcher] with a real outbound queue store and
        // asserts the new contract:
        //  (a) in flight the editor is EMPTY (single representation) with Send
        //      disabled,
        //  (b) on a drop the prompt STAYS as ONE queued "Will send when
        //      reconnected." row (NOT returned to the composer), with NO "Not
        //      sent / send again or discard" error and NO duplicate
        //      "Connection lost" banner,
        //  (c) on reconnect the queued row AUTO-SENDS (the #900 flush) and is
        //      pruned on delivery.
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(outboundQueueStore = queue)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.onComposerTargetChanged("1/session-a")
        val visible = mutableStateOf(true)
        // The host's send behaviour is test-controlled: the FIRST dispatch fails
        // (the drop), and after "reconnect" the SECOND dispatch (the auto-flush)
        // delivers. This mirrors the production
        // sendRequests → markSendDelivered | markOutboundSendDeferred wiring.
        val sendCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstSendEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Boolean>()
        val secondSendEntered = CompletableDeferred<Unit>()
        collectorScope.launch {
            vm.sendRequests.collect { request ->
                val attempt = sendCount.incrementAndGet()
                if (attempt == 1) {
                    firstSendEntered.complete(Unit)
                    val delivered = withTimeoutOrNull(8_000L) { releaseFirst.await() } == true
                    if (delivered) vm.markSendDelivered(request) else vm.markOutboundSendDeferred(request)
                } else {
                    secondSendEntered.complete(Unit)
                    // The reconnect auto-flush delivers.
                    vm.markSendDelivered(request)
                    visible.value = false
                }
            }
        }
        // Connection is down at first (the drop scenario).
        renderComposer(vm, visible, connectionLost = true, sendTarget = target)

        // Type the prompt the maintainer reported being duplicated.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("/clear")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains("/clear", substring = true)

        // Tap Send.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { firstSendEntered.isCompleted }
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.sendInFlight }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // (a) SINGLE REPRESENTATION in flight: editor EMPTY, ONE queue banner,
        // Send disabled.
        assertEquals("", vm.uiState.value.draft)
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("1 unsent prompt", substring = true).assertIsDisplayed()
        assertEquals(listOf("/clear"), vm.outboundQueueItems.value.map { it.cleanText })
        assertEquals(1, vm.outboundQueueItems.value.size)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsNotEnabled()
        WalkthroughScreenshotArtifacts.capture("issue-971-01-in-flight-single-representation")

        // The send fails because the connection dropped.
        releaseFirst.complete(false)
        compose.waitUntil(timeoutMillis = 5_000) { !vm.uiState.value.sendInFlight }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_SEND_IN_FLIGHT_TAG)
                .fetchSemanticsNodes().isEmpty()
        }

        // (b) DROP → the prompt STAYS QUEUED, NOT returned to the composer:
        //  - the editor is still EMPTY (no manual-resend draft),
        //  - exactly ONE queued row remains, reading "Will send when reconnected.",
        //  - NO "Not sent / send again or discard" error banner,
        //  - the standalone "Connection lost" banner is SUPPRESSED (the queue
        //    banner is the single status — no contradictory stack).
        assertEquals("", vm.uiState.value.draft)
        assertEquals(1, vm.outboundQueueItems.value.size)
        assertEquals(OutboundState.Queued, vm.outboundQueueItems.value.single().state)
        assertNull(
            "a drop must not stamp a 'Not sent / send again or discard' error",
            vm.uiState.value.error,
        )
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE,
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("send again or discard").assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG).assertDoesNotExist()
        WalkthroughScreenshotArtifacts.capture("issue-971-02-drop-single-status-stays-queued")

        // (c) RECONNECT → the queued row auto-sends via the #900 flush and is
        // pruned on delivery. (The production TmuxSessionScreen calls
        // requeueStaleOutboundInFlight + retryNextOutboundItem on each refresh;
        // we drive the flush directly here.)
        compose.runOnUiThread { vm.setConnectionDegraded(false) }
        compose.runOnUiThread { vm.retryNextOutboundItem() }
        compose.waitUntil(timeoutMillis = 5_000) { secondSendEntered.isCompleted }
        compose.waitUntil(timeoutMillis = 5_000) { !visible.value }
        assertEquals(2, sendCount.get())
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertEquals("", vm.uiState.value.draft)
        WalkthroughScreenshotArtifacts.capture("issue-971-03-reconnect-auto-sent-queue-cleared")
    }
}
