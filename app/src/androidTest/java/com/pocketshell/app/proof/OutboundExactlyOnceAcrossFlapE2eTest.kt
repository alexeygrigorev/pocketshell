package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_CLOSE_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.composerOutboundQueueRetryTestTag
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.isComposerQueueRetryable
import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.PromptComposerOutboundDrainTestSeams
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticPrivacy
import com.pocketshell.app.tmux.AgentSubmitCaptureSeams
import com.pocketshell.app.tmux.OutboundDeliverySeams
import com.pocketshell.app.tmux.PasteChunkSeams
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.TMUX_UNIFIED_TERMINAL_PAGER_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.durableTmuxSessionKey
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.testaccess.AuthoritativeSshLeaseConnector
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_UNSENT_BADGE_TAG
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.agents.AgentKind
import com.termux.view.TerminalView
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboundExactlyOnceAcrossFlapE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(testName)
        .around(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null
    private var issue1739CaptureCleanupGate: CompletableDeferred<Unit>? = null
    private var issue1602QueueJourney: Issue1602RecoveredQueueJourney? = null
    /** Issue #1819: the last sidecar SSH read error, so a blank frame can name its cause. */
    private var lastSidecarFailure: String? = null
    private val artifacts = OutboundAcceptanceArtifacts(DEVICE_DIR_NAME) { testName.methodName }
    private val queueViewport = OutboundQueueViewportCapture(compose, artifacts, ::visibleTerminalText)

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        // Issue #2124: this class asserts the LEGACY delivery-inference
        // lifecycle (bounded paste-ack, turnover proof, the unconfirmed row
        // waiting for late authority). The shipped default is now the
        // acknowledged host-CLI path, so the legacy authority is selected here
        // EXPLICITLY rather than the class being silently re-pointed at a
        // different state machine — or, worse, its assertions rewritten.
        pinOutboundDeliveryAuthority(
            com.pocketshell.app.settings.OutboundDeliveryAuthority.TerminalInference,
        )
        val key = OutboundExactlyOnceFixture.readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        OutboundExactlyOnceFixture.seedFakeAgentSession(key, testName.methodName)
        hostRowTag = OutboundExactlyOnceFixture.seedDockerHost(key)
    }

    @Before
    fun setUp() {
        // Issue #2124: zero before every test so tearDown's assertion is about
        // THIS test's sends — the behavioural half of the authority pin's
        // liveness proof (see [assertNoAcknowledgedSendsWereRecorded]).
        com.pocketshell.app.tmux.HostAckSendProbe.reset()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        authoritativeLeaseConnector().resetOutageForTest()
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
        AgentSubmitCaptureSeams.reset()
        PromptComposerOutboundDrainTestSeams.beforeEmit = null
    }

    @After
    fun tearDown() {
        // Issue #2124: EVERY test in this class asserts the legacy inference
        // lifecycle, so not one of its sends may have ridden the acknowledged
        // lane. Asserted here (not per-test) so a new test in this class cannot
        // forget it, and BEFORE the pin is cleared.
        assertNoAcknowledgedSendsWereRecorded(testName.methodName)
        clearOutboundDeliveryAuthorityPin()
        runCatching { authoritativeLeaseConnector().resetOutageForTest() }
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
        AgentSubmitCaptureSeams.reset()
        PromptComposerOutboundDrainTestSeams.beforeEmit = null
        issue1739CaptureCleanupGate?.complete(Unit)
        issue1739CaptureCleanupGate = null
        issue1602QueueJourney?.close()
        issue1602QueueJourney = null
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { OutboundExactlyOnceFixture.cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun composerPromptSentDuringFlapIsDeliveredExactlyOnce() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("initial attach")
        val viewModel = currentViewModel()
        viewModel.setAgentSubmitEnterDelayForTest(0)
        val clientBeforeFlap = viewModel.currentClientIdentityForTest()

        waitForDetectionBound(viewModel)
        openConversationTab(viewModel)

        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val payload = "exactly once across the flap $nonce"
        val payloadStripped = payload.filterNot { it.isWhitespace() }

        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = true

        val sendTappedAtMs = SystemClock.elapsedRealtime()
        openComposerAndSend(payload)

        waitForDeferral()

        val clientAfterInjection = assertFlapInjected(clientBeforeFlap)

        val submittedPredicate: (String) -> Boolean = {
            it.filterNot { ch -> ch.isWhitespace() }
                .contains(FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped)
        }
        waitForConnected("post-flap silent heal")
        val silentHealSubmitted = pollSidecarCaptureWhileDrivingIssue1739Main(
            SILENT_HEAL_SUBMIT_WINDOW_MS,
            submittedPredicate,
        ) != null
        if (!silentHealSubmitted) {
            recordTiming("user_retype_resend_used", 1L)
            openComposerAndSend(payload)
        }

        waitForSidecarCaptureWhileDrivingIssue1739Main(
            "prompt submitted after flap",
            SUBMIT_AFTER_FLAP_TIMEOUT_MS,
            submittedPredicate,
        )
        recordTiming("submitted_after_send_tap_ms", SystemClock.elapsedRealtime() - sendTappedAtMs)
        captureArtifacts("composer-submitted")

        waitForConnected("post-flap reconnect")
        val clientAfterFlap = currentViewModel().currentClientIdentityForTest()
        assertTrue(
            "transport did not rotate: $clientBeforeFlap/$clientAfterInjection/$clientAfterFlap",
            clientAfterFlap != null && clientAfterFlap != clientBeforeFlap,
        )

        val capture = waitForStableSidecarCapture()
        writeText("composer-final-capture.txt", capture)
        val captureStripped = capture.filterNot { it.isWhitespace() }
        assertFalse(
            "payload duplicated: $capture",
            captureStripped.contains(payloadStripped + payloadStripped),
        )
        assertEquals(
            "payload occurrence: $capture",
            1,
            countOccurrences(captureStripped, payloadStripped),
        )
        assertEquals(
            "submitted occurrence: $capture",
            1,
            countOccurrences(captureStripped, FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped),
        )
        assertInputBoxEmpty("after the verified resend", capture)

        val verifies = diagnostics!!.eventsNamed("outbound_verify_before_resend")
        assertTrue(
            "missing verify-before-resend: $verifies",
            verifies.any { it.fields["outcome"] == "AlreadyLanded" },
        )
        writeTimings()
    } }

    @Test
    fun manualRetryOfUnconfirmedSubmitWaitsForLateAuthority() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("manual retry initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("manual retry initial attach")
        val tmuxVm = currentViewModel()
        tmuxVm.setAgentSubmitEnterDelayForTest(0)
        waitForDetectionBound(tmuxVm)
        openConversationTab(tmuxVm)
        val composerVm = currentPromptComposerViewModel()
        val target = waitForDurableComposerTarget(tmuxVm, composerVm)
        val store = composerVm.outboundQueueStore
        store.clearSession(target)
        val payload = "manual retry late authority ${SystemClock.elapsedRealtime().toString().takeLast(6)}"
        // #2056: prove the induced ambiguity is real before relying on it.
        assertPaneSubmitOracleIsUndecidable("manual retry", payload)

        openComposerAndSend(payload)
        waitForIssue1739Boundary(CONNECTED_TIMEOUT_MS, "manual send parks", {
            outboundWaitDetails(store, target, composerVm)
        }) {
            store.itemsFor(target).singleOrNull()?.let { row ->
                row.wireSubmitAttempted && row.wireAttemptGeneration > 0 && row.isComposerQueueRetryable()
            } == true && !composerVm.uiState.value.sendInFlight
        }
        val parked = store.itemsFor(target).single()
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        if (!hasNode(COMPOSER_DRAFT_TAG)) {
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        }
        waitForComposerReady(expectQueue = true)
        val retryTag = composerOutboundQueueRetryTestTag(parked.id)
        compose.waitUntil(UI_TIMEOUT_MS) { hasNode(retryTag) }
        val verifiesBefore = diagnostics!!.eventsNamed("outbound_verify_before_resend").size
        compose.onNodeWithTag(retryTag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        waitForIssue1739Boundary(CONNECTED_TIMEOUT_MS, "manual retry verifies", {
            outboundWaitDetails(store, target, composerVm)
        }) {
            diagnostics!!.eventsNamed("outbound_verify_before_resend").size > verifiesBefore &&
                store.item(parked.id)?.isComposerQueueRetryable() == true &&
                !composerVm.uiState.value.sendInFlight
        }
        val retried = requireNotNull(store.item(parked.id))
        assertEquals(parked.sendKey, retried.sendKey)
        assertEquals(parked.wireAttemptGeneration, retried.wireAttemptGeneration)
        assertEquals(parked.paneId, retried.paneId)
        assertEquals(parked.tmuxSessionId, retried.tmuxSessionId)
        assertEquals(parked.tmuxSessionCreated, retried.tmuxSessionCreated)
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        assertTrue(diagnostics!!.eventsNamed("outbound_verify_before_resend").any {
            it.fields["durableRowId"] == parked.id && it.fields["outcome"] == "Unknown" &&
                it.fields["reason"] == "submit_attempt_unconfirmed"
        })

        publishDelayedTranscript()
        compose.waitUntil(CONNECTED_TIMEOUT_MS) { store.item(parked.id) == null }
        compose.waitUntil(UI_TIMEOUT_MS) { !hasNode(retryTag) }
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        Unit
    } }

    @Test
    fun lateAckSurvivesCloseAndRecreateWithoutDuplicate() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("late-ack initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("late-ack initial attach")
        val tmuxVm = currentViewModel()
        tmuxVm.setAgentSubmitEnterDelayForTest(0)
        waitForDetectionBound(tmuxVm)
        openConversationTab(tmuxVm)
        val composerVm = currentPromptComposerViewModel()
        val target = waitForDurableComposerTarget(tmuxVm, composerVm)
        val store = composerVm.outboundQueueStore
        store.clearSession(target)
        val payload = "late authoritative ack ${SystemClock.elapsedRealtime().toString().takeLast(6)}"
        // #2056: prove the induced ambiguity is real before relying on it.
        assertPaneSubmitOracleIsUndecidable("late-ack", payload)

        openComposerAndSend(payload)
        waitForIssue1739Boundary(CONNECTED_TIMEOUT_MS, "late send parks", {
            outboundWaitDetails(store, target, composerVm)
        }) {
            store.itemsFor(target).singleOrNull()?.let { row ->
                row.wireSubmitAttempted && row.wireAttemptGeneration > 0 && row.state != OutboundState.InFlight
            } == true
        }
        val submitted = store.itemsFor(target).single()
        requireNotNull(store.markFailed(submitted.id, "authoritative acknowledgement pending"))
        composerVm.refreshOutboundQueueItemsFor(target)
        val parked = requireNotNull(store.item(submitted.id))
        assertEquals(OutboundState.Failed, parked.state)
        assertEquals(submitted.sendKey, parked.sendKey)
        assertEquals(submitted.wireAttemptGeneration, parked.wireAttemptGeneration)
        assertTrue(parked.wireSubmitAttempted)
        assertTrue(parked.sendKey.isNotBlank())
        assertTrue(parked.tmuxSessionId != null && parked.tmuxSessionCreated != null)
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        // #2048: durable acceptance closes before delayed authority arrives.
        compose.waitUntil(UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        assertFalse("accepted composer must close while the exact row stays parked", hasNode(COMPOSER_DRAFT_TAG))

        // #1819: reopen only to inspect the parked row before close/recreate.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(expectQueue = true)
        val retryTag = composerOutboundQueueRetryTestTag(parked.id)
        compose.waitUntil(UI_TIMEOUT_MS) { hasNode(retryTag) }
        captureViewportArtifacts("issue2037-before-late-authoritative-ack")

        compose.onNodeWithTag(COMPOSER_CLOSE_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        compose.activityRule.scenario.recreate()
        waitForConnected("issue2037 activity recreate")
        val rebuiltComposer = currentPromptComposerViewModel()
        val rebuiltStore = rebuiltComposer.outboundQueueStore
        val rebuilt = requireNotNull(rebuiltStore.item(parked.id))
        assertEquals(parked.sendKey, rebuilt.sendKey)
        assertEquals(parked.wireAttemptGeneration, rebuilt.wireAttemptGeneration)
        assertEquals(parked.paneId, rebuilt.paneId)
        assertEquals(parked.tmuxSessionId, rebuilt.tmuxSessionId)
        assertEquals(parked.tmuxSessionCreated, rebuilt.tmuxSessionCreated)
        assertTrue(rebuilt.isComposerQueueRetryable())
        assertEquals(parked.attemptCount, rebuilt.attemptCount)
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        // #2048: late authority prunes state without a second close callback.
        compose.waitUntil(UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        compose.waitUntil(UI_TIMEOUT_MS) {
            !rebuiltComposer.uiState.value.sendInFlight && rebuiltStore.item(parked.id)?.isComposerQueueRetryable() == true
        }
        val claimsBefore = diagnostics!!.eventsNamed("row_state").count {
            it.fields["itemId"] == parked.id && it.fields["reason"] == "claimed"
        }

        publishDelayedTranscript()

        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) { rebuiltStore.itemsFor(target).isEmpty() }
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        assertEquals(listOf(payload), readFakeAgentSubmitLedger().map { it.second })
        assertEquals(claimsBefore, diagnostics!!.eventsNamed("row_state").count {
            it.fields["itemId"] == parked.id && it.fields["reason"] == "claimed"
        })
        assertTrue(
            diagnostics!!.eventsNamed("agent_submit_transcript_late_ack")
                .any { it.fields["pane"] == parked.paneId && it.fields["agent"] == AgentKind.ClaudeCode.name },
        )
        captureViewportArtifacts("issue2037-after-late-authoritative-ack")
        Unit
    } }

    @Test
    fun busyAgentOnStableWritableWireQueuesFifoWithoutBurningAttemptBudget() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("busy initial") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("busy initial")
        val tmuxVm = currentViewModel()
        tmuxVm.setAgentSubmitEnterDelayForTest(0)
        waitForDetectionBound(tmuxVm)
        openConversationTab(tmuxVm)
        val composer = currentPromptComposerViewModel()
        val target = waitForDurableComposerTarget(tmuxVm, composer)
        val store = composer.outboundQueueStore
        store.clearSession(target)
        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val payloads = listOf("busy first $nonce", "busy second $nonce")
        // #2056: prove the induced ambiguity is real before relying on it.
        assertPaneSubmitOracleIsUndecidable("busy", payloads[0])

        openComposerAndSend(payloads[0])
        waitForIssue1739Boundary(CONNECTED_TIMEOUT_MS, "busy first parks", {
            outboundWaitDetails(store, target, composer)
        }) {
            store.itemsFor(target).singleOrNull()?.state == OutboundState.Queued
        }
        assertTrue(tmuxVm.isSendTransportWritable())
        openComposerAndSend(payloads[1])
        waitForIssue1739Boundary(CONNECTED_TIMEOUT_MS, "busy second parks", {
            outboundWaitDetails(store, target, composer)
        }) {
            val rows = store.itemsFor(target)
            // #2056 CONTRACT FLIP (see the ledger assertion below): BOTH rows now reach
            // the wire. The head no longer holds the tail, so `all { wireSubmitAttempted }`
            // replaces `first().wireSubmitAttempted` — strictly more than before.
            rows.size == 2 && rows.all { it.wireSubmitAttempted } &&
                rows.all { it.state == OutboundState.Queued } && !composer.uiState.value.sendInFlight
        }
        val queuedSignature = store.itemsFor(target).map { it.id to it.attemptCount }
        val heartbeatBefore = requireNotNull(
            Regex("FAKE-AGENT HEARTBEAT: (\\d+)").find(sidecarCapturePane())?.groupValues?.get(1)?.toLongOrNull(),
        )

        val holdUntil = SystemClock.elapsedRealtime() + BUSY_BUDGET_HOLD_MS
        var settledChecks = 0
        while (SystemClock.elapsedRealtime() < holdUntil) {
            assertTrue("real -CC wire stayed writable", tmuxVm.isSendTransportWritable())
            assertTrue("busy output must not become transport failure", store.itemsFor(target).none { it.state == OutboundState.Failed })
            val rows = store.itemsFor(target)
            if (!composer.uiState.value.sendInFlight && rows.all { it.state == OutboundState.Queued }) {
                assertEquals("settled row ids/order and attempt generations stay exact", queuedSignature, rows.map { it.id to it.attemptCount })
                settledChecks += 1
            }
            pumpComposeMainFor(250)
        }
        val heartbeatAfter = requireNotNull(
            Regex("FAKE-AGENT HEARTBEAT: (\\d+)").find(sidecarCapturePane())?.groupValues?.get(1)?.toLongOrNull(),
        )
        assertTrue("agent output advanced throughout the healthy hold", heartbeatAfter > heartbeatBefore)
        assertTrue("the hold observed multiple completed retry/refund cycles", settledChecks >= 2)
        // Issue #2056 CONTRACT FLIP, stated explicitly (was `listOf(payloads[0])`).
        //
        // The previous expectation encoded head-of-line BLOCKING: while the head's
        // outcome was unproven, the tail was not allowed to reach the agent. That is
        // exactly the behaviour #2056 was filed against — "once the first message gets
        // clogged, every following message gets clogged too, even though those
        // following messages are in fact being delivered." An unresolvable head must
        // not hold the tail (issue #2056 acceptance criterion 5).
        //
        // The flip is sound, not a relaxation: a row only reaches this ambiguous state
        // AFTER its paste was ack-proven on the pane and tmux accepted its Enter, so
        // the head's bytes are already on the pane. Dispatching the tail therefore
        // cannot reorder anything — which is why the expectation below is still an
        // EXACT list in FIFO order, and still pins exactly-once (each payload appears
        // once, neither is re-Entered). It is strictly stronger than the old one: it
        // now also proves the non-poisoning drain on the REAL wire, during the hold,
        // while both durable rows are still parked awaiting the late authority.
        assertEquals(
            "an unresolvable head must not hold the tail, and neither payload may be " +
                "Entered twice (#2056 / #1944)",
            payloads,
            readFakeAgentSubmitLedger().map { it.second },
        )
        assertEquals(
            "both rows are still durably parked awaiting the late authority while their " +
                "payloads have already reached the agent (#2056)",
            2,
            store.itemsFor(target).count { it.wireSubmitAttempted && it.isComposerQueueRetryable() },
        )
        captureViewportArtifacts("issue2042-busy-stable-wire-queued")

        val transcript = "/home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL"
        // Issue #2056 round 2 — the relay must stop after ONE publish, and its pid must
        // be recorded so a crashed run cannot leave it behind.
        //
        // It used to break at `n >= 2`, because on the old head-of-line-BLOCKING
        // contract the two payloads reached the staging file in two separate waves
        // (the tail was only sent after the head resolved). Under the #2056 contract
        // BOTH payloads are already staged before the relay starts, so one copy
        // publishes both and `n` never reaches 2 — leaving the relay polling this
        // shared fixture for a further 60 s, straight into the NEXT test in the class,
        // where it published that test's staged submit and pruned the row that test
        // was waiting to see parked (observed: manualRetry... green in isolation, red
        // as `rows=[]` when it ran after this one).
        val relay = execRemoteSetupUntilReady(
            SshKey.Pem(fixtureKey),
            "nohup sh -c " + shellQuote(
                "i=0; while [ \$i -lt 300 ]; do " +
                    "if [ -s $LATE_ACK_STAGING_JSONL ]; then cat $LATE_ACK_STAGING_JSONL >> $transcript; " +
                    ": > $LATE_ACK_STAGING_JSONL; break; fi; " +
                    "i=\$((i+1)); sleep .2; done",
            ) + " >/tmp/issue2042-relay.log 2>&1 & echo \$! > $TRANSCRIPT_RELAY_PID_PATH",
            "issue2042 start transcript relay",
        )
        assertEquals(0, relay.exitCode)
        compose.waitUntil(CONNECTED_TIMEOUT_MS) { store.itemsFor(target).isEmpty() }
        assertTrue(tmuxVm.isSendTransportWritable())
        assertEquals("FIFO drains once when authority catches up", payloads, readFakeAgentSubmitLedger().map { it.second })
        captureViewportArtifacts("issue2042-busy-stable-wire-drained")
        Unit
    } }

    @Test
    fun fallbackQueueRowsSurviveSessionSwitchAndDrainOnlyIntoSameGeneration() { runBlocking<Unit> {
        OutboundExactlyOnceFixture.enableIssue1944FramedFakeAgent(fixtureKey)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("initial attach")

        val tmuxVm = currentViewModel()
        // Cold Live/frame delivery may precede immutable tree identity binding.
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            tmuxVm.currentTargetSessionKeyForTest()?.startsWith("tmux:") == true
        }
        val originalDurableA = requireNotNull(tmuxVm.currentTargetSessionKeyForTest())
        assertTrue("A must open with its durable tree identity", originalDurableA.startsWith("tmux:"))
        val hostId = originalDurableA.removePrefix("tmux:").substringBefore(':').toLong()
        val renamedA = "$SESSION_NAME-renamed"
        renameCurrentSessionThroughUi(renamedA)
        waitForConnected("rename navigation")
        val kindResult = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = "tmux set-option -t ${shellQuote(renamedA)} @ps_agent_kind claude",
            description = "issue1944 bind renamed fake-agent kind",
        )
        assertEquals("fake-agent kind tag must update", 0, kindResult.exitCode)
        val durableA = requireNotNull(currentViewModel().currentTargetSessionKeyForTest())
        assertEquals(
            "rename navigation must carry the same immutable tmux generation",
            originalDurableA,
            durableA,
        )
        val bIdentity = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = "tmux display-message -p -t ${shellQuote("=$SESSION_B:")} " +
                shellQuote("#{session_id}|#{session_created}"),
            description = "issue1944 session-B immutable identity",
        )
        val bFields = bIdentity.stdout.trim().split('|')
        assertEquals("session-B identity must contain id+created", 2, bFields.size)
        val durableB = requireNotNull(
            durableTmuxSessionKey(hostId, bFields[0], bFields[1].toLongOrNull()),
        )
        switchSessionFromMoreMenu(SESSION_B, durableB)
        switchSessionFromMoreMenu(renamedA, durableA)
        waitForVisibleTerminal("renamed A after recorded-kind refresh") { it.contains(FAKE_AGENT_READY) }
        val vm = currentViewModel()
        vm.setPassiveDisconnectRecoveryForTest(
            graceMs = OUTAGE_GRACE_MS,
            silentReattachTimeoutMs = OUTAGE_DIAL_MS,
        )
        vm.setAutoReconnectDelaysForTest(OUTAGE_RETRY_DELAYS_MS)
        waitForDetectionBound(vm)
        openConversationTab(vm)
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            vm.currentRecordedAgentRouteEvidence.value?.durableSessionKey == durableA
        }
        val routeEvidenceBeforeOutage = requireNotNull(vm.currentRecordedAgentRouteEvidence.value)
        assertEquals(durableA, routeEvidenceBeforeOutage.durableSessionKey)
        val clientBeforeOutage = vm.currentClientIdentityForTest()
        val generationBeforeOutage = vm.currentConnectGenerationForTest()
        val fallbackA = "$hostId/$renamedA"
        val store = currentPromptComposerViewModel().outboundQueueStore
        store.clearSession(fallbackA)
        store.clearSession(durableA)

        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val firstPayload = "issue1944-first-$nonce"
        val secondPayload = "issue1944-second-$nonce"
        val replacementDraft = "issue1602 replacement draft $nonce  + αβγ"
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
            .performClick()
        waitForComposerReady(expectQueue = false)
        val leaseConnector = authoritativeLeaseConnector()
        val outageA = leaseConnector.beginSustainedOutageForLastLeaseForTest()
        assertEquals(DEFAULT_HOST, outageA.leaseKey.host)
        assertEquals(DEFAULT_PORT, outageA.leaseKey.port)
        assertEquals(DEFAULT_USER, outageA.leaseKey.user)
        val outageAStartedAt = SystemClock.elapsedRealtime()
        assertTrue("the clean outage must cut the live client", vm.triggerCleanPassiveDropForTest())
        awaitAuthoritativeLeaseOutage(vm, outageA)
        assertEquals(routeEvidenceBeforeOutage, vm.currentRecordedAgentRouteEvidence.value)
        val fallbackEnqueueStartedAt = SystemClock.elapsedRealtime()
        openComposerAndSend(firstPayload)
        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "first real composer send settled as queued while unavailable",
            timeoutDetails = {
                "rows=${store.itemsFor(fallbackA).map { Triple(it.id, it.cleanText, it.state) }} " +
                    "sendInFlight=${currentPromptComposerViewModel().uiState.value.sendInFlight} " +
                    "queueEvents=${diagnostics!!.events.filter { event ->
                        event.name == "row_state" || event.name == "composer_send" ||
                            event.name == "composer_send_deferred_to_queue" ||
                            event.name == "drain_attempt"
                    }.map { it.name to it.fields }} " +
                    "events=${boundedEventTail(diagnostics!!.events)}"
            },
        ) {
            val rows = store.itemsFor(fallbackA)
            rows.size == 1 && rows.single().cleanText == firstPayload &&
                rows.single().state == OutboundState.Queued &&
                !currentPromptComposerViewModel().uiState.value.sendInFlight
        }
        openComposerAndSend(secondPayload)
        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "two real composer sends committed",
            timeoutDetails = {
                    "durable=${store.itemsFor(durableA).map { Triple(it.id, it.cleanText, it.state) }} " +
                    "fallback=${store.itemsFor(fallbackA).map { Triple(it.id, it.cleanText, it.state) }} " +
                    "draft=${draftText()} queueEvents=${diagnostics!!.events.filter { event ->
                        event.name == "row_state" || event.name == "composer_send" ||
                            event.name == "composer_send_deferred_to_queue"
                    }.map { it.name to it.fields }} " +
                    "events=${boundedEventTail(diagnostics!!.events)}"
            },
        ) {
            val durableRows = store.itemsFor(durableA)
            val fallbackRows = store.itemsFor(fallbackA)
            durableRows.size + fallbackRows.size >= 2 &&
                (durableRows + fallbackRows).all { it.state == OutboundState.Queued } &&
                !currentPromptComposerViewModel().uiState.value.sendInFlight
        }
        val queued = store.itemsFor(fallbackA)
        assertEquals(listOf(firstPayload, secondPayload), queued.map { it.cleanText })
        assertTrue("outage rows stay Queued rather than being parked Failed", queued.all { it.state == OutboundState.Queued })
        assertTrue("durable identity must stay empty before live generation settles", store.itemsFor(durableA).isEmpty())
        assertTrue("fallback rows preserve exact tmux generation evidence", queued.all {
            it.tmuxSessionId == routeEvidenceBeforeOutage.durableSessionKey
                .removePrefix("tmux:$hostId:").substringBeforeLast(':') &&
                it.tmuxSessionCreated != null &&
                it.paneId == routeEvidenceBeforeOutage.paneId
        })
        recordTiming("fallback_two_rows_committed_ms", SystemClock.elapsedRealtime() - fallbackEnqueueStartedAt)
        compose.onNode(
            hasContentDescription("2 unsent") and
                androidx.compose.ui.test.hasTestTag(SESSION_COMPOSER_UNSENT_BADGE_TAG),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(expectQueue = true)
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true)
            .performClick()
        queueViewport.capture("issue1944-queued-before-switch", queued)
        assertTrue(
            "outage must remain controller-non-Connected through both sends",
            currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "outage wire oracle must remain false through both sends",
            currentViewModel().isSendTransportWritable(),
        )
        val queuedIds = queued.map { it.id }
        val serverWhileDown = runBlocking { sidecarCapturePane(renamedA) }
        assertFalse(serverWhileDown.contains(firstPayload))
        assertFalse(serverWhileDown.contains(secondPayload))

        pressSystemBack()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(250)
        assertEquals(queuedIds, store.itemsFor(fallbackA).map { it.id })
        assertTrue(currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertFalse(currentViewModel().isSendTransportWritable())
        val createdCapture = runBlocking { sidecarCapturePane(renamedA) }
        assertFalse(createdCapture.contains(firstPayload))
        assertFalse(createdCapture.contains(secondPayload))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(expectQueue = true)
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true).performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(250)
        assertEquals(queuedIds, store.itemsFor(fallbackA).map { it.id })
        assertTrue("open-sheet CREATED must not submit", readFakeAgentSubmitLedger().isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val finalForegroundResumeAt = SystemClock.elapsedRealtime()
        pressSystemBack()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }

        assertAuthoritativeLeaseOutageHeld(vm, outageA, outageAStartedAt, "A")
        awaitSustainedOutageTerminalization(
            vm,
            outageA,
            finalForegroundResumeAt + OUTAGE_GRACE_MS + OUTAGE_MARGIN_MS,
        )
        vm.closeCurrentConnectionAndJoinForTest()
        val bBlockedBaseline = outageA.blockedAttemptCount
        val bOpenStartedAt = SystemClock.elapsedRealtime()
        clickTmuxBack()
        openSessionFromFolder(SESSION_B, waitForConnection = false)
        awaitBlockedNavigationSettled(vm, outageA, bBlockedBaseline, "B")
        assertEquals(SESSION_B, currentViewModel().latestRestoreIntentSnapshot()?.sessionName)
        leaseConnector.endSustainedOutageForTest(outageA)
        var bReconnectAccepted = false
        compose.activityRule.scenario.onActivity { bReconnectAccepted = vm.reconnect() }
        assertTrue(bReconnectAccepted)
        val freshBClient = waitForFreshClient(clientBeforeOutage)
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            vm.currentTargetSessionKeyForTest() == durableB
        }
        waitForVisibleTerminal("open B") { it.contains(SESSION_B_MARKER) }
        assertTrue("session B must never own A rows", store.itemsFor("$hostId/$SESSION_B").isEmpty())
        assertTrue(
            "session B durable key must never own A rows",
            store.itemsFor(requireNotNull(currentViewModel().currentTargetSessionKeyForTest())).isEmpty(),
        )
        compose.onNodeWithTag(SESSION_COMPOSER_UNSENT_BADGE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { hasNode(COMPOSER_DRAFT_TAG) }
        assertTrue(
            "B composer must not render A's first row",
            compose.onAllNodesWithText(firstPayload, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "B composer must not render A's second row",
            compose.onAllNodesWithText(secondPayload, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        captureViewportArtifacts("issue1944-b-isolated")
        recordTiming("b_isolated_ui_ms", SystemClock.elapsedRealtime() - bOpenStartedAt)
        pressSystemBack()

        val bVm = currentViewModel()
        val clientAtB = bVm.currentClientIdentityForTest()
        val generationAtB = bVm.currentConnectGenerationForTest()
        assertTrue("B must be reached through a fresh client", clientAtB != null && clientAtB != clientBeforeOutage)
        assertEquals("fresh-client wait must settle on B's active client", freshBClient, clientAtB)
        assertTrue("the reconnect generation must advance", generationAtB > generationBeforeOutage)
        openSessionSwitcher(renamedA)
        val outageB = leaseConnector.beginSustainedOutageForLastLeaseForTest()
        assertEquals(outageA.leaseKey, outageB.leaseKey)
        val outageBStartedAt = SystemClock.elapsedRealtime()
        assertTrue("B transport cut must hold A offline for UI proof", bVm.triggerCleanPassiveDropForTest())
        awaitAuthoritativeLeaseOutage(bVm, outageB)
        awaitSustainedOutageTerminalization(
            bVm,
            outageB,
            outageBStartedAt + OUTAGE_GRACE_MS + OUTAGE_MARGIN_MS,
        )
        val aReturnStartedAt = SystemClock.elapsedRealtime()
        val aBlockedBaseline = outageB.blockedAttemptCount
        compose.onNodeWithTag(TMUX_SESSION_PAGER_TAG, useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            currentViewModel().latestRestoreIntentSnapshot()?.sessionName == renamedA
        }
        val offlineAIntent = requireNotNull(currentViewModel().latestRestoreIntentSnapshot())
        assertEquals("cached row must target renamed A while offline", renamedA, offlineAIntent.sessionName)
        assertEquals(durableA.removePrefix("tmux:$hostId:").substringBeforeLast(':'), offlineAIntent.tmuxSessionId)
        assertEquals(durableA.substringAfterLast(':').toLong(), offlineAIntent.sessionCreated)
        awaitBlockedNavigationSettled(bVm, outageB, aBlockedBaseline, "returned A")
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            store.itemsFor(fallbackA).map { it.id } == queuedIds
        }
        assertTrue("durable owner must stay empty before A wire truth settles", store.itemsFor(durableA).isEmpty())
        compose.onNode(
            hasContentDescription("2 unsent") and
                androidx.compose.ui.test.hasTestTag(SESSION_COMPOSER_UNSENT_BADGE_TAG),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(expectQueue = true)
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true).performClick()
        queueViewport.capture("issue1944-a-returned-queued", store.itemsFor(fallbackA))
        // Reopened #1602/#2034 field sequence: while A is still offline after
        // switch-away/back, begin a new replacement prompt but do not Send it.
        // The later fallback→durable identity refinement and old queue callbacks
        // must not blank or submit these bytes.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performTextInput(replacementDraft)
        waitForIssue1739Boundary(UI_TIMEOUT_MS, "replacement draft visible before heal", {
            "draft=${draftText()} expected=$replacementDraft"
        }) {
            draftText() == replacementDraft
        }
        assertEquals(replacementDraft, currentPromptComposerViewModel().uiState.value.draft)
        waitForIssue1739Boundary(UI_TIMEOUT_MS, "replacement draft persisted before heal", {
            "fallback=${currentPromptComposerViewModel().composerDraftStore.load(fallbackA)}"
        }) {
            currentPromptComposerViewModel().composerDraftStore.load(fallbackA) == replacementDraft
        }
        val returnedOfflineRows = store.itemsFor(fallbackA)
        assertEquals(
            "offline retry may advance only attempt time; payload, identity, route, and wire fields must survive",
            queued,
            returnedOfflineRows.mapIndexed { index, row ->
                row.copy(lastAttemptAtMs = queued[index].lastAttemptAtMs)
            },
        )
        assertEquals(listOf(0, 0), returnedOfflineRows.map { it.attemptCount })

        val offlineComposer = currentPromptComposerViewModel()
        val issue1602Journey = Issue1602RecoveredQueueJourney(
            compose = compose,
            composer = offlineComposer,
            waitForComposerReady = { waitForComposerReady(it) },
            draftText = ::draftText,
            readSubmitLedger = { runBlocking { readFakeAgentSubmitLedger() } },
            claimCount = { rowId -> diagnostics!!.eventsNamed("row_state").count {
                it.fields["itemId"] == rowId && it.fields["reason"] == "claimed"
            } },
            captureViewport = ::captureViewportArtifacts,
            captureQueue = queueViewport::capture,
            uiTimeoutMs = UI_TIMEOUT_MS,
            connectedTimeoutMs = CONNECTED_TIMEOUT_MS,
        ).also { issue1602QueueJourney = it }
        issue1602Journey.parkHeadAndProveOffline(
            fallbackA,
            queuedIds,
            firstPayload,
            secondPayload,
            replacementDraft,
        )
        recordTiming("a_rows_visible_before_heal_ms", SystemClock.elapsedRealtime() - aReturnStartedAt)
        pressSystemBack()

        val healStartedAt = SystemClock.elapsedRealtime()
        val activeAVm = currentViewModel()
        assertTrue("the Activity must still route connectivity to its active host VM", activeAVm === bVm)
        assertAuthoritativeLeaseOutageHeld(activeAVm, outageB, outageBStartedAt, "B")

        issue1602Journey.blockReconnectDrain()

        leaseConnector.endSustainedOutageForTest(outageB)
        SystemClock.sleep(250)
        assertEquals("restoring connector authority alone must not deliver", queuedIds, store.itemsFor(fallbackA).map { it.id })
        assertTrue("restoring connector authority alone must not reach the server", readFakeAgentSubmitLedger().isEmpty())
        var aReconnectAccepted = false
        compose.activityRule.scenario.onActivity { aReconnectAccepted = activeAVm.reconnect() }
        assertTrue(aReconnectAccepted)
        waitForConnected("heal returned A")
        waitForVisibleTerminal("return to A") { it.contains(FAKE_AGENT_READY) }
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            store.itemsFor(fallbackA).isEmpty() && store.itemsFor(durableA).map { it.id } == queuedIds
        }
        assertTrue("promoted rows must leave no orphan fallback owner", store.itemsFor(fallbackA).isEmpty())
        val promotion = diagnostics!!.eventsNamed("identity_promotion").single { event ->
            event.fields["oldFingerprint"] == DiagnosticPrivacy.stableFingerprint(fallbackA) &&
                event.fields["newFingerprint"] == DiagnosticPrivacy.stableFingerprint(durableA)
        }
        assertEquals(2, promotion.fields["rowCount"])
        assertEquals(queuedIds, promotion.fields["rowIds"])
        assertEquals(true, promotion.fields["preservedExceptOwner"])
        assertEquals(promotion.fields["expectedRowFingerprints"], promotion.fields["rowFingerprints"])

        issue1602Journey.provePromotionThenRetry(
            durableA,
            fallbackA,
            queuedIds,
            firstPayload,
            secondPayload,
            replacementDraft,
        )

        assertTrue("both stable rows must be delivered+pruned", store.itemsFor(durableA).isEmpty())
        pressSystemBack()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(expectQueue = false)
        waitForIssue1739Boundary(UI_TIMEOUT_MS, "replacement draft survives identity promotion", {
            "visible=${draftText()} durable=${currentPromptComposerViewModel().composerDraftStore.load(durableA)} " +
                "fallback=${currentPromptComposerViewModel().composerDraftStore.load(fallbackA)}"
        }) {
            draftText() == replacementDraft &&
                currentPromptComposerViewModel().composerDraftStore.load(durableA) == replacementDraft &&
                currentPromptComposerViewModel().composerDraftStore.load(fallbackA) == null
        }
        assertEquals(
            "identity promotion must persist the exact unsent replacement bytes",
            replacementDraft,
            currentPromptComposerViewModel().composerDraftStore.load(durableA),
        )
        assertEquals(
            "fallback draft slot must be retired only after the durable copy exists",
            null,
            currentPromptComposerViewModel().composerDraftStore.load(fallbackA),
        )
        captureViewportArtifacts("issue1602-replacement-draft-preserved-after-heal")
        pressSystemBack()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(COMPOSER_DRAFT_TAG) }
        val clientAfterHeal = currentViewModel().currentClientIdentityForTest()
        val generationAfterHeal = currentViewModel().currentConnectGenerationForTest()
        assertTrue("A heal must use a fresh client", clientAfterHeal != null && clientAfterHeal != clientAtB)
        assertTrue("A heal must advance generation", generationAfterHeal > generationAtB)
        recordTiming("fallback_promoted_and_drained_ms", SystemClock.elapsedRealtime() - healStartedAt)
        val routedRows = diagnostics!!.eventsNamed("composer_tmux_send_route")
            .filter { it.fields["rowId"] in queuedIds }
        assertEquals(
            "both stable durable row ids must reach the tmux dispatcher",
            queuedIds.toSet(),
            routedRows.map { it.fields["rowId"] }.toSet(),
        )
        assertTrue(
            "both recovered composer rows must use an agent route, never RawBytes; events=$routedRows",
            routedRows.isNotEmpty() && routedRows.all { it.fields["route"] != "RawBytes" },
        )
        val turnovers = diagnostics!!.eventsNamed("agent_submit_turnover")
            .filter { it.fields["result"] == "transcript_ack_observed" }
        assertEquals(
            "each durable row must earn exact-runtime post-Enter turnover before prune",
            2,
            turnovers.size,
        )
        assertTrue(
            "turnover evidence must remain bound to one client generation",
            turnovers.all {
                it.fields["clientHash"] == it.fields["currentClientHash"] &&
                    it.fields["generation"] == it.fields["currentGeneration"]
            },
        )
        assertEquals(
            "both rows must drain only into the one healed A generation",
            setOf(generationAfterHeal),
            turnovers.map { it.fields["generation"] }.toSet(),
        )
        assertTrue(
            "recorded-agent rows must not fall back to a screen-only ready oracle",
            diagnostics!!.eventsNamed("agent_submit_turnover")
                .none { it.fields["result"] == "ready_surface_observed" },
        )
        val transcriptAcks = diagnostics!!.eventsNamed("agent_submit_transcript_ack")
        assertEquals("each row must bind to a new confirmed transcript event", 2, transcriptAcks.size)
        assertEquals(1, transcriptAcks.map { it.fields["sourceHash"] }.toSet().size)
        assertTrue(
            "transcript events must stay on the exact pane/Claude source binding; events=$transcriptAcks",
            transcriptAcks.all {
                it.fields["pane"] == "%0" && it.fields["agent"] == AgentKind.ClaudeCode.name
            },
        )
        val secondSubmitted = waitForStableSidecarCapture(renamedA)
        val submitLedger = readFakeAgentSubmitLedger()
        writeText(
            "issue1944-submit-ledger.txt",
            submitLedger.joinToString(separator = "\n", postfix = "\n") { (sequence, payload) ->
                "$sequence|$payload"
            },
        )
        assertEquals("server ledger must contain two submit Enters", listOf(1, 2), submitLedger.map { it.first })
        assertEquals(
            "the parked head must not block the younger row, and real Retry must submit the head once",
            listOf(secondPayload, firstPayload),
            submitLedger.map { it.second },
        )
        writeText(
            "issue1944-final-diagnostics.txt",
            buildString {
                appendLine("queuedIds=$queuedIds")
                appendLine("remainingRows=${store.itemsFor(durableA)}")
                appendLine("capture=$secondSubmitted")
                diagnostics!!.events.filter { event ->
                    event.name == "row_state" ||
                        event.name == "agent_submit_ack" ||
                        event.name == "agent_submit_turnover" ||
                        event.name == "agent_submit_transcript_ack" ||
                        event.name == "composer_tmux_send_route" ||
                        event.name == "outbound_verify_before_resend" ||
                        event.name == "dispatch_rejected" ||
                        event.name == "drain_attempt"
                }.forEach { appendLine("${it.name} ${it.fields}") }
            },
        )
        val secondSubmittedStripped = secondSubmitted.filterNot { it.isWhitespace() }
        val firstStripped = firstPayload.filterNot { it.isWhitespace() }
        assertEquals(
            "final visible frame must retain the real-Retry submission and clean input; capture=$secondSubmitted",
            1,
            countOccurrences(
                secondSubmittedStripped,
                FAKE_AGENT_SUBMITTED_STRIPPED + firstStripped,
            ),
        )
        val deliveredIds = diagnostics!!.eventsNamed("row_state")
            .filter { it.fields["toState"] == "Sent" && it.fields["itemId"] in queuedIds }
            .map { it.fields["itemId"] }
        assertEquals(
            "younger auto-drain then explicit head Retry must each deliver exactly once",
            listOf(queuedIds[1], queuedIds[0]),
            deliveredIds,
        )
        captureViewportArtifacts("issue1944-returned-and-drained")
        writeText("issue1944-queue.txt", "queuedIds=$queuedIds\ndeliveredIds=$deliveredIds\n")
        writeTimings()
        Unit
    } }

    /**
     * Issue #1739 harness guard: the synthetic parked cleanup must model the
     * real SSH path's threading. `RealSshSession.exec` performs physical channel
     * close writes on the dedicated `TransportDispatcher` thread (and owns
     * detached teardown on Dispatchers.IO), never by blocking Android Main.
     *
     * This narrow launch-owned proof parks cleanup after one REAL capture,
     * proves the cancellation continuation is off Main, posts a Main heartbeat,
     * and observes the bounded ack diagnostic while the cleanup gate is STILL
     * closed. It prevents the full reconnect journey from manufacturing an ANR
     * in its test seam and mistaking that harness artifact for product behavior.
     */
    @Test
    fun parkedAckCleanupIsOffMainWhileHeartbeatAndBoundedDiagnosticContinue() {
        runBlocking<Unit> {
            attachSeededTmuxSession(hostRowTag)
            waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
            waitForConnected("initial attach")
            val viewModel = currentViewModel()
            viewModel.setAgentSubmitEnterDelayForTest(0)
            waitForDetectionBound(viewModel)
            openConversationTab(viewModel)

            val cleanupGate = CompletableDeferred<Unit>()
            issue1739CaptureCleanupGate = cleanupGate
            val cleanupParked = AtomicBoolean(false)
            val cleanupRanOnMain = AtomicReference<Boolean>()
            val store = currentPromptComposerViewModel().outboundQueueStore
            val sessionKey = requireNotNull(viewModel.currentTargetSessionKeyForTest()) {
                "the connected VM must expose its exact durable queue session key"
            }
            val expectedClient = requireNotNull(viewModel.currentClientIdentityForTest()) {
                "the connected VM must expose its current client identity"
            }
            val captureProbe = armIssue1739CaptureProbe(
                viewModel = viewModel,
                store = store,
                sessionKey = sessionKey,
                expectedClient = expectedClient,
            ) {
                cleanupRanOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                cleanupParked.set(true)
                withContext(NonCancellable) { cleanupGate.await() }
            }

            val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
            openComposerAndSend("issue1739 heartbeat $nonce\nissue1739 parked cleanup $nonce")
            waitForIssue1739CapturePrecondition(
                label = "real ack capture",
                probe = captureProbe,
            )
            waitForIssue1739Boundary(ACK_BOUNDED_RESULT_TIMEOUT_MS, "off-Main cleanup park") {
                cleanupParked.get()
            }
            assertEquals(
                "the synthetic NonCancellable cleanup must resume off Android Main, " +
                    "matching RealSshSession/TransportDispatcher threading",
                false,
                cleanupRanOnMain.get(),
            )

            val mainHeartbeat = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post { mainHeartbeat.set(true) }
            waitForIssue1739Boundary(
                MAIN_HEARTBEAT_TIMEOUT_MS,
                "Main heartbeat during parked cleanup",
            ) {
                mainHeartbeat.get()
            }
            waitForIssue1739Boundary(ACK_BOUNDED_RESULT_TIMEOUT_MS, "bounded ack diagnostic") {
                diagnostics!!.eventsNamed("agent_submit_ack").any {
                    it.fields["result"] == "capture_timeout" ||
                        it.fields["result"] == "ack_timeout"
                }
            }
            assertFalse(
                "the ack deadline and Main heartbeat must win while cleanup remains parked",
                cleanupGate.isCompleted,
            )

            cleanupGate.complete(Unit)
            AgentSubmitCaptureSeams.reset()
        }
    }

    /**
     * Issue #1739: launch-owned reproduction of the preserved post-reconnect
     * multiline AgentPayload wedge. A REAL capture first proves the bracketed
     * paste reached the fake-Claude pane as its collapsed chip; only its
     * cancellation cleanup is then parked in NonCancellable state. The 800ms
     * caller boundary must fail/requeue without blind Enter, and the SAME
     * durable token must verify the landed chip and complete Enter-only.
     */
    @Test
    fun postReconnectAckCleanupIsBoundedAndSameTokenRetrySubmitsExactlyOnce() {
        runBlocking<Unit> {
            attachSeededTmuxSession(hostRowTag)
            waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
            waitForConnected("initial attach")
            val viewModel = currentViewModel()
            viewModel.setAgentSubmitEnterDelayForTest(0)
            waitForDetectionBound(viewModel)
            openConversationTab(viewModel)

            // First reproduce the prerequisite faithfully: a cut of the live
            // worker/runtime, followed by a REAL fresh-client reconnect.
            val clientBeforeCut = viewModel.currentClientIdentityForTest()
            assertTrue(
                "precondition: the live worker cut must arm",
                viewModel.triggerCleanPassiveDropForTest(),
            )
            val clientAfterCut = waitForFreshClient(clientBeforeCut)
            recordTiming("issue1739_reconnected_client", clientAfterCut.toLong())

            val cleanupGate = CompletableDeferred<Unit>()
            issue1739CaptureCleanupGate = cleanupGate
            val store = currentPromptComposerViewModel().outboundQueueStore
            val sessionKey = requireNotNull(viewModel.currentTargetSessionKeyForTest()) {
                "the connected VM must expose its exact durable queue session key"
            }
            val captureProbe = armIssue1739CaptureProbe(
                viewModel = viewModel,
                store = store,
                sessionKey = sessionKey,
                expectedClient = clientAfterCut,
            ) {
                withContext(NonCancellable) { cleanupGate.await() }
            }

            val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
            val payload =
                "issue1739 first line $nonce\nissue1739 exact once marker $nonce"
            val payloadStripped = payload.filterNot { it.isWhitespace() }

            val tappedAtMs = SystemClock.elapsedRealtime()
            openComposerAndSend(payload)
            waitForIssue1739CapturePrecondition(
                label = "real ack capture after paste",
                probe = captureProbe,
            )

            // Authoritative intermediate evidence, while the first ack capture
            // is still parked: one durable InFlight row, wire attempted, real
            // collapsed paste visible, and NO submission/Enter.
            val routedSessionKey = captureProbe.routedSessionKey.get()
            val routedRowId = captureProbe.routedRowId.get()
            val inFlightRows = store.itemsFor(routedSessionKey)
            assertEquals(
                "the real composer must own exactly one durable row during the wedged ack; " +
                    "rows=$inFlightRows",
                1,
                inFlightRows.size,
            )
            val row = inFlightRows.single()
            assertEquals("the probe must follow the exact routed durable row", routedRowId, row.id)
            assertEquals(
                "the row must still be InFlight while the real capture cleanup is parked",
                OutboundState.InFlight,
                row.state,
            )
            assertTrue("the real bracketed paste commit must be durable", row.wireAttempted)
            val firstCapture = captureProbe.firstRealCapture.get()
            assertTrue(
                "real pane evidence must contain Claude's collapsed multiline-paste chip; " +
                    "capture=$firstCapture",
                firstCapture.contains("[Pasted text #"),
            )
            assertFalse(
                "Enter must not be sent while paste acknowledgement is unproven; " +
                    "capture=$firstCapture",
                firstCapture.contains("FAKE-AGENT SUBMITTED:"),
            )
            writeText("issue1739-first-real-capture.txt", firstCapture)
            val paneId = requireNotNull(row.paneId) {
                "the durable AgentPayload row must retain its pane identity"
            }
            openTerminalTab(viewModel, paneId)
            val pendingViewportText = waitForVisibleTerminal(
                "issue1739 pasted but not submitted viewport",
            ) {
                it.contains("[Pasted text #") && !it.contains("FAKE-AGENT SUBMITTED:")
            }
            captureLiveTerminalViewport(
                "issue1739-paste-pending-viewport",
                pendingViewportText,
                viewModel,
                paneId,
            )

            // The detached boundary must win at ~800ms even though cleanup
            // remains NonCancellable. The normal composer dispatcher requeues
            // the SAME row, whose auto-flush verifies the visible chip.
            waitForIssue1739Boundary(
                ACK_BOUNDED_RESULT_TIMEOUT_MS,
                "bounded capture/ack failure",
            ) {
                diagnostics!!.eventsNamed("agent_submit_ack")
                    .any {
                        it.fields["result"] == "capture_timeout" ||
                            it.fields["result"] == "ack_timeout"
                    }
            }
            recordTiming(
                "issue1739_capture_timeout_after_tap_ms",
                SystemClock.elapsedRealtime() - tappedAtMs,
            )
            // The outer ack deadline and its single inner capture both use the
            // same documented 800ms bound. Either may win the scheduler race:
            // inner-first records `capture_timeout`; outer-first records
            // `ack_timeout`. Both carry the immutable runtime identity and both
            // are the same bounded/no-Enter outcome.
            val boundedAck = diagnostics!!.eventsNamed("agent_submit_ack")
                .firstOrNull {
                    it.fields["result"] == "capture_timeout" ||
                        it.fields["result"] == "ack_timeout"
                }
            assertTrue(
                "timeout diagnostics must bind to the fresh current client; " +
                    "clientAfterCut=$clientAfterCut event=$boundedAck",
                boundedAck != null &&
                    boundedAck.fields["clientHash"] == clientAfterCut &&
                    boundedAck.fields["currentClientHash"] == clientAfterCut &&
                    boundedAck.fields["generation"] ==
                    boundedAck.fields["currentGeneration"],
            )
            cleanupGate.complete(Unit)
            AgentSubmitCaptureSeams.reset()

            val submitted = waitForIssue1739RetrySubmission(
                "same-token Enter-only retry submission",
                SUBMIT_AFTER_FLAP_TIMEOUT_MS,
                verified = {
                    diagnostics!!.eventsNamed("outbound_verify_before_resend").any {
                        it.fields["sendToken"] == row.id &&
                            it.fields["outcome"] == "AlreadyLanded"
                    }
                },
            ) {
                val stripped = it.filterNot { ch -> ch.isWhitespace() }
                stripped.contains(FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped)
            }
            recordTiming(
                "issue1739_submitted_after_tap_ms",
                SystemClock.elapsedRealtime() - tappedAtMs,
            )
            val settled = waitForStableSidecarCapture()
            writeText("issue1739-final-raw-transcript.txt", settled)
            val settledStripped = settled.filterNot { it.isWhitespace() }
            assertEquals(
                "the multiline payload must occur exactly once in the real submitted " +
                    "transcript; capture=$settled",
                1,
                countOccurrences(settledStripped, payloadStripped),
            )
            assertEquals(
                "Enter must submit exactly once; capture=$settled",
                1,
                countOccurrences(
                    settledStripped,
                    FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped,
                ),
            )
            assertInputBoxEmpty("after issue1739 same-token retry", submitted)
            waitForIssue1739Boundary(
                SUBMIT_AFTER_FLAP_TIMEOUT_MS,
                "authoritative transcript ack and durable row prune",
                timeoutDetails = { "row=${store.item(row.id)} events=${boundedEventTail(diagnostics!!.events)}" },
            ) {
                store.item(row.id) == null
            }
            assertTrue(
                "the SAME durable row/token must be Sent and pruned; id=${row.id} " +
                    "remaining=${store.itemsFor(routedSessionKey)}",
                store.item(row.id) == null && store.itemsFor(routedSessionKey).isEmpty(),
            )
            writeText(
                "issue1739-row-prune.txt",
                "session=$routedSessionKey\nrowId=${row.id}\nitem=null\nsessionItems=0\n",
            )
            val verifies = diagnostics!!.eventsNamed("outbound_verify_before_resend")
            assertTrue(
                "the retry must verify the SAME durable token as AlreadyLanded; " +
                    "row=${row.id} events=$verifies",
                verifies.any {
                    it.fields["sendToken"] == row.id &&
                        it.fields["outcome"] == "AlreadyLanded"
                },
            )
            assertEquals(
                "only one collapsed-paste commit may reach the live pane; capture=$settled",
                1,
                Regex("""\[Pasted text #\d+ \+\d+ lines?]""")
                    .findAll(firstCapture)
                    .count(),
            )
            assertEquals(
                "the fresh reconnect client must remain live after retry",
                clientAfterCut,
                currentViewModel().currentClientIdentityForTest(),
            )
            val finalViewportText = waitForVisibleTerminal(
                "issue1739 final one-submission viewport",
            ) {
                val stripped = it.filterNot { ch -> ch.isWhitespace() }
                stripped.contains(FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped)
            }
            openTerminalTab(viewModel, paneId)
            captureLiveTerminalViewport(
                "issue1739-final-submitted-viewport",
                finalViewportText,
                viewModel,
                paneId,
            )
            writeText(
                "issue1739-diagnostics.txt",
                diagnostics!!.events.joinToString("\n") {
                    "${it.category}/${it.name} ${it.fields}"
                },
            )
            captureArtifacts("issue1739-live-input")
            writeTimings()
        }
    }

    /**
     * Issue #1636 — the PAYLOAD-INTEGRITY limb, against the REAL tmux server.
     *
     * The sibling test above proves the prompt is delivered ONCE. This one proves
     * the bytes it delivers are the RIGHT ones, which is a different failure and
     * one no occurrence-count assertion can see. The cut is the #1526 S6 spec's
     * cut point (b) — a teardown at a paste CHUNK BOUNDARY, which no fixture
     * reproduced before (the seam above models cut point (c), AFTER the whole
     * paste has landed).
     *
     * Journey: a LONG single-line prompt (> one paste chunk, so the paste is
     * multi-chunk — the shape the #1610 storm's ~5 s teardown lands inside) is
     * sent from the REAL composer with [PasteChunkSeams] armed to genuinely drop
     * the transport partway through the paste. The app reconnects for real and
     * the deferred row is re-sent through the SAME verify-before-resend chain.
     *
     * RED on base: chunks 1..k are already in the fake-agent's input box; the
     * resend's probe keys on the payload's tail (which never landed), reports
     * `NotLanded`, and re-pastes the FULL payload on top — the fixture submits
     * `<partial-prefix><payload>`. GREEN: the fill never touches the pane and the
     * single `paste-buffer` commit delivers the payload whole, so the submitted
     * content EQUALS the prompt.
     *
     * The assertion is CONTENT EQUALITY of the submitted text read back off the
     * server (`capture-pane` via a sidecar SSH session), not needle presence —
     * the needle is present in the corrupted text too (G6).
     */
    @Test
    fun multiChunkPromptCutAtAPasteChunkBoundaryIsSubmittedByteExact() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("initial attach")
        val viewModel = currentViewModel()
        viewModel.setAgentSubmitEnterDelayForTest(0)
        val clientBeforeFlap = viewModel.currentClientIdentityForTest()

        waitForDetectionBound(viewModel)
        openConversationTab(viewModel)

        // A LONG SINGLE-LINE prompt: > one chunk (so the paste is multi-chunk and
        // has interior boundaries to cut at) and no line break (the fake-agent
        // submits on any LF, so a multi-line prompt could not be one submission
        // there — the multi-line limb of the class is covered byte-exactly by the
        // JVM `OutboundPastePayloadIntegrityTest`).
        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val payload = "byteexact$nonce-" + "abcdefghij".repeat(180) + "-tail$nonce"
        val payloadStripped = payload.filterNot { it.isWhitespace() }

        // Arm the chunk-boundary cut: partway through the paste the transport is
        // REALLY dropped (a clean passive drop, the same teardown the storm makes)
        // and the send fails — exactly the state that used to strand a partial
        // prefix in the agent's input box.
        PasteChunkSeams.onCut = { viewModel.triggerCleanPassiveDropForTest() }
        PasteChunkSeams.failAtFillChunkIndex = 1

        val sendTappedAtMs = SystemClock.elapsedRealtime()
        openComposerAndSend(payload)
        waitForDeferral()
        assertEquals(
            "the seam must have fired (the cut is the whole point of this journey)",
            -1,
            PasteChunkSeams.failAtFillChunkIndex,
        )

        // The link heals for real; the resend rides the auto-flush, or (when it
        // races the dying transport) the maintainer's own recovery of re-typing.
        val submittedPredicate: (String) -> Boolean = {
            it.filterNot { ch -> ch.isWhitespace() }.contains(FAKE_AGENT_SUBMITTED_STRIPPED)
        }
        waitForConnected("post-cut silent heal")
        if (
            pollSidecarCaptureWhileDrivingIssue1739Main(
                SILENT_HEAL_SUBMIT_WINDOW_MS,
                submittedPredicate,
            ) == null
        ) {
            recordTiming("user_retype_resend_used", 1L)
            openComposerAndSend(payload)
        }
        waitForSidecarCaptureWhileDrivingIssue1739Main(
            "prompt submitted after the chunk-boundary cut",
            SUBMIT_AFTER_FLAP_TIMEOUT_MS,
            submittedPredicate,
        )
        recordTiming("byte_exact_submitted_after_send_tap_ms", SystemClock.elapsedRealtime() - sendTappedAtMs)
        captureArtifacts("payload-integrity-submitted")

        // The cut was REAL: the send rode a reconnect (fresh client identity).
        waitForConnected("post-cut reconnect")
        val clientAfterFlap = currentViewModel().currentClientIdentityForTest()
        assertTrue(
            "the seam must have dropped the transport (fresh tmux client after the " +
                "cut); before=$clientBeforeFlap after=$clientAfterFlap",
            clientAfterFlap != null && clientAfterFlap != clientBeforeFlap,
        )

        // ===== THE payload-integrity assertion (server-side, BYTES). =====
        val capture = waitForStableSidecarCapture()
        writeText("payload-integrity-final-capture.txt", capture)
        val submitted = submittedTextStripped(capture)
        assertEquals(
            "the agent must receive the prompt BYTE-EXACT across a paste chunk-boundary " +
                "teardown + verified resend. On base the resend re-pastes onto the partial " +
                "prefix the cut stranded, so the submitted text is " +
                "'<partial-prefix><payload>' — submitted exactly once, silently corrupt. " +
                "capture:\n$capture",
            payloadStripped,
            submitted,
        )
        assertInputBoxEmpty("after the byte-exact verified resend", capture)
        writeTimings()
    } }

    /**
     * Keystroke lane: input typed through the REAL TerminalView session (pane
     * input queue + pump) whose first send loses its result must NOT be doubled
     * by the retry — the probe sees it landed and suppresses attempt 2.
     */
    @Test
    fun keystrokesWithLostSendResultAreDeliveredExactlyOnce() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("initial attach")

        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val typed = "exactly-once-keys-$nonce"

        // Arm the lane-B seam: the NEXT pane-input batch send reports failure
        // AFTER its bytes landed (result lost) — the ambiguous B2 cut whose
        // blind retry doubled keystrokes.
        OutboundDeliverySeams.failInputSendResultLostOnce = true
        val typedAtMs = SystemClock.elapsedRealtime()
        writeThroughTerminalSession(typed)

        // GREEN: the keystrokes must arrive (timely) ...
        val capture = waitForSidecarCaptureWhileDrivingIssue1739Main(
            "typed keystrokes visible",
            KEYSTROKE_TIMEOUT_MS,
        ) {
            it.filterNot { ch -> ch.isWhitespace() }.contains(typed)
        }
        recordTiming("keystrokes_visible_after_type_ms", SystemClock.elapsedRealtime() - typedAtMs)

        // ... and after the retry window settles, EXACTLY ONCE (base: the blind
        // attempt 2 re-sends the batch and the input box reads '<typed><typed>').
        // Issue #1819: PUMP this settle rather than sleeping it. The window must
        // cover the production 150ms retry delay + probe round-trip; a bare wall
        // sleep leaves the Compose scheduler frozen, so the very retry this
        // assertion exists to catch could not fire and the proof passed
        // vacuously.
        pumpComposeMainFor(KEYSTROKE_SETTLE_MS)
        val settled = waitForStableSidecarCapture()
        writeText("keystroke-final-capture.txt", settled)
        val settledStripped = settled.filterNot { it.isWhitespace() }
        assertFalse(
            "REGRESSION (#1526 base signature, lane B): keystrokes must NOT be " +
                "doubled by the blind retry; capture:\n$settled",
            settledStripped.contains(typed + typed),
        )
        assertEquals(
            "typed keystrokes must occur EXACTLY ONCE in the pane; capture:\n$settled",
            1,
            countOccurrences(settledStripped, typed),
        )
        captureArtifacts("keystroke-exactly-once")
        writeTimings()
    } }

    // ---------------------------------------------------------------- drive helpers

    /**
     * Wait for real #975 detection. Keep this wall sleep: unlike retry/timeout
     * waits, its subject is remote I/O. Advancing the virtual Main clock can
     * expire the detection budgets before a slow AVD's SSH round trip returns.
     */
    private fun waitForDetectionBound(vm: TmuxSessionViewModel) {
        val deadline = SystemClock.elapsedRealtime() + DETECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.agentConversations.value.values.any { it.detection != null }) return
            SystemClock.sleep(150)
        }
        assertTrue(
            "precondition: live source detection must bind (recorded Claude identity + " +
                "fresh seeded Claude JSONL) so " +
                "the composer routes down the agent delivery chain; " +
                "conversations=${vm.agentConversations.value.mapValues { it.value.detection?.agent }}",
            vm.agentConversations.value.values.any { it.detection != null },
        )
    }

    private fun openConversationTab(vm: TmuxSessionViewModel) {
        val paneId = requireNotNull(
            vm.agentConversations.value.entries.firstOrNull { it.value.detection != null }?.key,
        ) { "a detection-bound conversation pane must exist before opening the tab" }
        val deadline = SystemClock.elapsedRealtime() + DETECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.agentConversations.value[paneId]?.selectedTab ==
                com.pocketshell.app.session.SessionTab.Conversation &&
                hasNode(TMUX_CONVERSATION_PANE_TAG)
            ) {
                return
            }
            // Exercise the tap, then its same production entry point so an
            // overloaded AVD cannot wedge this unrelated precondition.
            if (hasNode(CONVERSATION_SEGMENT_TAG)) {
                runCatching {
                    compose.onNodeWithTag(CONVERSATION_SEGMENT_TAG, useUnmergedTree = true)
                        .performClick()
                }
            }
            compose.activityRule.scenario.onActivity {
                vm.selectSessionTab(paneId, com.pocketshell.app.session.SessionTab.Conversation)
            }
            compose.waitForIdle()
            // Safe to pump: waitForIdle already advances this clock, and the
            // exit condition is tab selection rather than remote completion.
            pumpComposeMainFor(TAB_POLL_STEP_MS)
        }
        assertTrue(
            "the Conversation tab must open (segment tapped / selectSessionTab); " +
                "selectedTab=${vm.agentConversations.value[paneId]?.selectedTab} " +
                "paneShown=${hasNode(TMUX_CONVERSATION_PANE_TAG)}",
            false,
        )
    }

    private fun hasNode(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }.getOrDefault(false)

    private suspend fun publishDelayedTranscript() {
        val publish = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = "cat ${shellQuote(LATE_ACK_STAGING_JSONL)} >> " +
                shellQuote("/home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL") +
                " && : > ${shellQuote(LATE_ACK_STAGING_JSONL)}",
            description = "issue2037 publish delayed transcript",
        )
        assertEquals(0, publish.exitCode)
    }

    private fun openComposerAndSend(payload: String) {
        if (!hasNode(COMPOSER_DRAFT_TAG)) {
            pumpComposeMainFor(750)
            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                hasNode(SESSION_COMPOSER_LAUNCHER_TAG)
            }
            compose.waitForIdle()
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { hasNode(COMPOSER_DRAFT_TAG) }
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true).performTextInput(payload)

        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "composer draft committed before Send",
            timeoutDetails = { "draftText=${draftText()} expectedLength=${payload.length}" },
        ) {
            draftText().contains(payload)
        }

        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "composer Send enabled before tap",
            timeoutDetails = {
                "Send stayed disabled (outboundHandoffInProgress / no composition); " +
                    "draftText=${draftText()}"
            },
        ) {
            sendButtonEnabled()
        }

        val sendsBefore = diagnostics!!.eventsNamed("composer_send").size
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()

        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "composer Send dispatched a real send",
            timeoutDetails = {
                "the Send tap did not dispatch: no new 'composer_send' diagnostic; " +
                    "sendsBefore=$sendsBefore draftText=${draftText()} " +
                    "events=${boundedEventTail(diagnostics!!.events)}"
            },
        ) {
            diagnostics!!.eventsNamed("composer_send").size > sendsBefore
        }
    }

    private fun sendButtonEnabled(): Boolean = runCatching {
        val node = compose.onAllNodesWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return false
        !node.config.contains(SemanticsProperties.Disabled)
    }.getOrDefault(false)

    private fun draftText(): String = runCatching {
        compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
    }.getOrDefault("")

    /**
     * Issue #1819 — assert the injected flap AT THE POINT OF INJECTION.
     *
     * The `failSendResultLostBeforeSubmitEnter` seam does NOT guarantee a flap.
     * `TmuxSessionViewModel.consumeSendResultLostSeamForTest()` consumes the
     * flag, calls `triggerCleanPassiveDropForTest()` — which returns `false` and
     * does nothing when the live `PassiveTransportDropEffects.classify(client)`
     * says `Ignore` — and throws either way. It also cannot control WHICH
     * recovery arm the drop selects: `PauseUntilForeground` /
     * `SkipInAppNavigation` install no replacement client at all. So the row
     * defers, the resend completes on the SAME client, and the journey's
     * "the flap was REAL" precondition failed ~180s later with a message that
     * looked like a delivery bug. Captured on CI run 30220248414 attempt-1: two
     * `tmux-passive-disconnect-silent-reattach` events for a class that injects
     * three drops — this test's flap never happened.
     *
     * Assert the two things the injection must actually produce, bounded, right
     * here: the seam FIRED with `dropped=true`, and a FRESH client identity
     * replaced the pre-flap one. No assertion is weakened — this constrains the
     * flap strictly more than the end-of-journey check alone, and names the true
     * cause when the injection no-ops.
     */
    private fun assertFlapInjected(clientBeforeFlap: Int?): Int? {
        waitForIssue1739Boundary(
            timeoutMs = CONNECTED_TIMEOUT_MS,
            label = "flap seam fired",
            timeoutDetails = {
                "the injected send never reached the drop seam; " +
                    "events=${boundedEventTail(diagnostics!!.events)}"
            },
        ) {
            diagnostics!!.eventsNamed("outbound_result_lost_seam").isNotEmpty()
        }
        val seam = diagnostics!!.eventsNamed("outbound_result_lost_seam")
        assertTrue(
            "the injected drop seam must have ACTUALLY dropped the transport " +
                "(triggerCleanPassiveDropForTest returned false — the live " +
                "passive-drop classification refused it, so no flap happened and " +
                "this journey would have proved exactly-once across NO flap); " +
                "seam=${seam.map { it.fields }}",
            seam.any { it.fields["dropped"] == true },
        )
        var fresh: Int? = null
        waitForIssue1739Boundary(
            timeoutMs = CONNECTED_TIMEOUT_MS,
            label = "fresh tmux client after the injected flap",
            timeoutDetails = {
                "the drop fired but no replacement client was installed — the " +
                    "recovery arm skipped reattach (PauseUntilForeground / " +
                    "SkipInAppNavigation), so the resend rides the SAME client " +
                    "and there is no flap to prove delivery across; " +
                    "before=$clientBeforeFlap current=$fresh " +
                    "seam=${seam.map { it.fields }} " +
                    "events=${boundedEventTail(diagnostics!!.events)}"
            },
        ) {
            fresh = currentViewModel().currentClientIdentityForTest()
            fresh != null && fresh != clientBeforeFlap
        }
        recordTiming("flap_injected_fresh_client", (fresh ?: 0).toLong())
        return fresh
    }

    private fun writeThroughTerminalSession(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var written = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val session = view.currentSession ?: return@onActivity
            // The REAL keyboard input path: TerminalView key events call
            // TerminalSession.write, which feeds the bridge's terminal-to-process
            // queue -> the per-pane input queue -> the pump under test.
            session.write(bytes, 0, bytes.size)
            written = true
        }
        assertTrue("expected to write through the live TerminalView session", written)
    }

    private fun deferredRowVisible(): Boolean = runCatching {
        compose.onAllNodesWithText(
            "Will send when reconnected.",
            substring = true,
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun waitForDeferral() {
        waitForIssue1739Boundary(
            timeoutMs = DEFERRAL_TIMEOUT_MS,
            label = "ambiguous send deferral",
            predicate = {
                diagnostics!!.eventsNamed("composer_send_deferred_to_queue").isNotEmpty() ||
                    deferredRowVisible()
            },
            timeoutDetails = {
                val events = diagnostics!!.events
                "expected diagnostic 'composer_send_deferred_to_queue' or the " +
                    "'Will send when reconnected.' row; " +
                    "events=${boundedEventTail(events)}"
            },
        )
    }

    // ---------------------------------------------------------------- sidecar capture

    private suspend fun sidecarCapturePane(sessionName: String = SESSION_NAME): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux capture-pane -p -t ${shellQuote(sessionName)}")
            }
        }
        // Issue #1819: remember WHY a read came back empty. A failed sidecar
        // dial is indistinguishable from an empty pane at the call sites, and
        // that ambiguity is what let a transient SSH failure be reported as a
        // delivery violation (see [waitForStableSidecarCapture]).
        result.exceptionOrNull()?.let { lastSidecarFailure = it.toString() }
        return result.getOrNull()?.stdout.orEmpty()
    }

    /**
     * Issue #1819 — the class-wide sweep of the #1739/#1773/#1798 pump.
     *
     * The unpumped `pollSidecarCapture` / `waitForSidecarCapture` wall-only
     * polls that used to live here are DELETED (D22 hard-cut, no shim). Three
     * previous determinism rounds each pumped only the waits that round happened
     * to trip over — #1773 the initial-capture/deferral boundaries, #1798 the
     * `multiChunk` sidecar waits — leaving the composer and keystroke limbs of
     * the SAME class polling wall time while launch-owned Compose Main stayed
     * frozen, so the production auto-flush/ack/Enter continuation this journey is
     * waiting FOR could not run. Every sidecar wait in this class now goes
     * through [pollSidecarCaptureWhileDrivingIssue1739Main]; the hard wall
     * deadline remains the load-bearing bound.
     *
     * Poll the sidecar capture until two consecutive reads agree (settled),
     * pacing Compose Main between reads so a production continuation cannot be
     * starved by the settle loop itself.
     *
     * Issue #1819 — NEVER return a blank frame to a delivery assertion. This
     * used to `return previous` after 20 tries even when every read had come
     * back empty, and every caller feeds the result straight into an
     * exactly-once count. Observed locally (determinism run 9/10): a transient
     * sidecar SSH dial failure returned "", and
     * `postReconnectAckCleanupIsBoundedAndSameTokenRetrySubmitsExactlyOnce`
     * failed with *"the multiline payload must occur exactly once in the real
     * submitted transcript; capture= expected:<1> but was:<0>"* — a read failure
     * wearing the exact message that would convince a reader this class has a
     * duplicate-delivery race. The fake-agent pane always holds at least its
     * READY banner and input box, so a BLANK capture is by definition a failed
     * read and never a real pane state; hard-fail as the harness failure it is,
     * naming the SSH error. This strengthens the proof — a genuinely wrong pane
     * still fails the count assertion, but a broken sidecar can no longer
     * masquerade as one.
     */
    private fun waitForStableSidecarCapture(sessionName: String = SESSION_NAME): String {
        lastSidecarFailure = null
        var previous = runBlocking { sidecarCapturePane(sessionName) }
        repeat(20) {
            // Issue #1819 audit: pumped, and correctly so. This wait's subject is
            // "production has STOPPED changing the pane" — the inverse of waiting
            // for a remote round trip to land. Freezing virtual Main would make
            // in-flight production work invisible and settle falsely early, which
            // is the vacuous-pass direction (G6). It also matches the pre-existing
            // [pollSidecarCaptureWhileDrivingIssue1739Main] (#1798), which already
            // pumps across the identical sidecar reads.
            pumpComposeMainFor(SIDECAR_SETTLE_STEP_MS)
            val next = runBlocking { sidecarCapturePane(sessionName) }
            if (next == previous && next.isNotBlank()) return next
            previous = next
        }
        assertTrue(
            "the sidecar capture never returned a non-blank pane frame — this is a " +
                "sidecar SSH READ failure, not a delivery result. The fake-agent pane " +
                "always contains its READY banner and input box, so a blank frame " +
                "cannot be a real pane state; do NOT read a delivery count off it. " +
                "lastSidecarFailure=$lastSidecarFailure",
            previous.isNotBlank(),
        )
        return previous
    }

    /**
     * Advance launch-owned Compose Main in the same bounded 20ms steps as the
     * #1739 helpers for [millis] of wall time.
     *
     * The replacement for a bare `SystemClock.sleep(n)`: a raw sleep advances
     * wall time while the Compose test scheduler stays frozen, so any production
     * `delay`/`withTimeoutOrNull` this journey is waiting on never fires. Used
     * wherever the test must let REAL production work happen rather than merely
     * let wall time pass.
     *
     * Issue #1819 — this is NOT a blanket replacement, and a future sweep must
     * not convert every `SystemClock.sleep` in this class. The rule, by SUBJECT
     * of the wait:
     *
     *  - **Pump** when the test waits for a step that can only happen once
     *    virtual Main time moves: a production `delay` retry timer must fire, an
     *    ack/cleanup `withTimeoutOrNull` must EXPIRE, a recomposition must land,
     *    or a retry LADDER with Main backoff must make progress. Freezing there
     *    wedges the very thing under test and yields a vacuous pass (G6).
     *  - **Sleep** when the test waits for a one-shot WALL-CLOCK remote round
     *    trip to COMPLETE and production guards it with a Main-scoped
     *    `withTimeoutOrNull` budget sized for a real device. On a starved
     *    swiftshader AVD the wall latency exceeds that budget, so advancing the
     *    clock ~1:1 with wall time CANCELS what the test is waiting for. The
     *    frozen clock is the thing making the wait work.
     *
     * The two sites on the sleep side are [waitForDetectionBound] and
     * [currentViewModel]; both carry the reasoning at the site.
     */
    private fun pumpComposeMainFor(millis: Long) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(ISSUE1739_MAIN_CLOCK_STEP_MS)
            SystemClock.sleep(ISSUE1739_MAIN_CLOCK_STEP_MS)
        }
    }

    private fun assertInputBoxEmpty(label: String, capture: String) {
        val inputLine = capture.lines()
            .lastOrNull { it.trimStart().startsWith(">") }
            ?.trim()
            .orEmpty()
        assertEquals(
            "$label: the agent input box must be EMPTY (the prompt left the " +
                "input — submitted once, not re-pasted); capture:\n$capture",
            ">",
            inputLine,
        )
    }

    // ---------------------------------------------------------------- attach helpers

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        // Issue #1819: WAIT for the navigation the tap starts. This used to
        // assert the session screen exists on the very next statement, with no
        // wait — the same "act, then immediately assert a dependent step"
        // shape as the composer Send tap. Observed locally (determinism run
        // 9/10): `Failed: assertExists. Expected exactly '1' node ... (TestTag
        // = 'tmux:session')` — the tap had landed but the screen had not been
        // composed yet, so the attach precondition failed before the journey
        // began. Bounded by the existing session-screen timeout; still hard-fails.
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { hasNode(TMUX_SESSION_SCREEN_TAG) }
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession?.emulator != null
            }
            attached
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            }
            if (vm?.panes?.value?.isNotEmpty() == true) break
            // Issue #1819: deliberately a bare sleep, NOT [pumpComposeMainFor].
            // Same exception as [waitForDetectionBound] — the subject is a
            // one-shot wall-clock remote round trip (`list-panes` arriving)
            // guarded by a Main-scoped `withTimeoutOrNull`, so freezing the
            // virtual clock is what lets it finish on a starved AVD.
            SystemClock.sleep(100)
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentPromptComposerViewModel(): PromptComposerViewModel {
        var vm: PromptComposerViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[PromptComposerViewModel::class.java]
        }
        return requireNotNull(vm) { "PromptComposerViewModel not available" }
    }

    private fun clickTmuxBack() {
        val tag = listOf(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, TMUX_FULL_CHROME_BACK_BUTTON_TAG)
            .firstOrNull { hasNode(it) }
            ?: TMUX_FULL_CHROME_BACK_BUTTON_TAG
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { !hasNode(TMUX_SESSION_SCREEN_TAG) }
    }

    private fun renameCurrentSessionThroughUi(newName: String) {
        val moreTag = listOf(TMUX_COMPACT_CHROME_MORE_BUTTON_TAG, TMUX_FULL_CHROME_MORE_BUTTON_TAG)
            .firstOrNull { hasNode(it) }
            ?: TMUX_FULL_CHROME_MORE_BUTTON_TAG
        compose.onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rename session", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextClearance()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextInput(newName)
        compose.onNodeWithTag(TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithText(newName, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun openSessionFromFolder(sessionName: String, waitForConnection: Boolean = true) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { hasNode(TMUX_SESSION_SCREEN_TAG) }
        if (waitForConnection) waitForConnected("open $sessionName")
    }

    /** Return through the real in-session picker, which reads live tmux names. */
    private fun switchSessionFromMoreMenu(
        sessionName: String,
        expectedDurableKey: String,
        waitForConnection: Boolean = true,
    ) {
        openSessionSwitcher(sessionName)
        selectSessionFromOpenSwitcher(expectedDurableKey)
        if (waitForConnection) waitForConnected("switch to $sessionName")
    }

    private fun openSessionSwitcher(sessionName: String) {
        val moreTag = listOf(
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
        ).firstOrNull { hasNode(it) } ?: TMUX_FULL_CHROME_MORE_BUTTON_TAG
        compose.onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
        compose.onNodeWithText("Switch session", useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectSessionFromOpenSwitcher(expectedDurableKey: String) {
        // Session-switcher pages are current-first. Swipe to the adjacent live
        // tmux page; tapping the offscreen Text semantics is not a real pager
        // selection and does not settle the page/navigation effect.
        compose.onNodeWithTag(TMUX_SESSION_PAGER_TAG, useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            currentViewModel().currentTargetSessionKeyForTest() == expectedDurableKey
        }
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun waitForComposerReady(expectQueue: Boolean) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            hasNode(COMPOSER_DRAFT_TAG) &&
                (!expectQueue || hasNode(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG))
        }
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun waitForDurableComposerTarget(
        tmuxVm: TmuxSessionViewModel,
        composerVm: PromptComposerViewModel,
    ): String {
        var target: String? = null
        waitForIssue1739Boundary(HOST_ROW_TIMEOUT_MS, "durable composer target", {
            "tmux=${tmuxVm.currentTargetSessionKeyForTest()} " +
                "composer=${composerVm.composerTarget} status=${currentConnectionStatus()} " +
                "events=${boundedEventTail(diagnostics!!.events)}"
        }) {
            target = composerVm.composerTarget
            target?.startsWith("tmux:") == true
        }
        return requireNotNull(target)
    }

    private fun waitForFreshClient(previousClient: Int?): Int {
        var current: Int? = null
        waitUntilWall(CONNECTED_TIMEOUT_MS, "fresh client after worker cut") {
            current = currentViewModel().currentClientIdentityForTest()
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected &&
                current != null &&
                current != previousClient
        }
        return requireNotNull(current)
    }

    private fun waitUntilWall(
        timeoutMs: Long,
        label: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            compose.mainClock.advanceTimeBy(ISSUE1739_MAIN_CLOCK_STEP_MS)
            SystemClock.sleep(ISSUE1739_MAIN_CLOCK_STEP_MS)
        }
        assertTrue("$label did not resolve within ${timeoutMs}ms", predicate())
    }

    /**
     * Issue #1739 connected-test pump.
     *
     * [createAndroidComposeRule] installs a [kotlinx.coroutines.test.TestDispatcher]
     * for app Main/viewModelScope. A raw [SystemClock.sleep] loop advances wall
     * time but leaves that virtual scheduler frozen, so the production 800ms
     * `withTimeoutOrNull` cannot fire and the synthetic cleanup is never
     * cancelled. Advance only this journey's Compose Main clock in small steps
     * while retaining the hard wall-clock deadline. The wall bound is the
     * load-bearing anti-hang assertion; scheduler advancement only lets the
     * launch-owned harness model time actually passing on a normal device.
     */
    private fun waitForIssue1739Boundary(
        timeoutMs: Long,
        label: String,
        timeoutDetails: () -> String = { "" },
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            compose.mainClock.advanceTimeBy(ISSUE1739_MAIN_CLOCK_STEP_MS)
            SystemClock.sleep(ISSUE1739_MAIN_CLOCK_STEP_MS)
        }
        assertTrue(
            "$label did not resolve within ${timeoutMs}ms while advancing " +
                "Compose Main by ${ISSUE1739_MAIN_CLOCK_STEP_MS}ms steps; " +
                timeoutDetails(),
            predicate(),
        )
    }

    private suspend fun armIssue1739CaptureProbe(
        viewModel: TmuxSessionViewModel,
        store: OutboundQueueStore,
        sessionKey: String,
        expectedClient: Int,
        onCancelled: suspend () -> Unit,
    ): Issue1739CaptureProbe = Issue1739CaptureProbeHarness(
        diagnostics = requireNotNull(diagnostics),
        capturePane = { sidecarCapturePane() },
        countCollapsedPasteChips = ::countCollapsedPasteChips,
        boundedEventTail = ::boundedEventTail,
        sessionName = SESSION_NAME,
        fakeAgentReady = FAKE_AGENT_READY,
    ).arm(viewModel, store, sessionKey, expectedClient, onCancelled)

    private fun boundedEventTail(
        events: List<RecordedDiagnosticEvent>,
        fromIndex: Int = 0,
    ): String {
        val start = fromIndex.coerceIn(0, events.size)
        val total = events.size - start
        val tail = events.subList(start, events.size)
            .takeLast(DIAGNOSTIC_EVENT_TAIL_COUNT)
            .joinToString {
                val fields = it.fields.entries.take(DIAGNOSTIC_FIELD_COUNT)
                    .joinToString { field ->
                        "${field.key.take(DIAGNOSTIC_FIELD_KEY_LIMIT)}=" +
                            boundedFieldValue(field.value)
                    }
                "${it.name.take(DIAGNOSTIC_EVENT_NAME_LIMIT)}{$fields}"
            }
        return "total=$total omitted=${(total - DIAGNOSTIC_EVENT_TAIL_COUNT).coerceAtLeast(0)} " +
            "tail=[$tail]"
    }

    private fun outboundWaitDetails(
        store: OutboundQueueStore,
        target: String,
        vm: PromptComposerViewModel,
    ): String = "rows=${store.itemsFor(target)} send=${vm.uiState.value.sendInFlight} " +
        "handoff=${vm.uiState.value.outboundHandoffInProgress} " +
        "consumer=${vm.outboundSendConsumers.activeGenerationForDispatch()} " +
        "owner=${vm.outboundDrainOwnership.activeRowId()} " +
        // #2056: the composer's CURRENT target (a changed key makes `rows=[]` mean
        // "looked in the wrong place", not "pruned") plus a delivery-scoped event tail,
        // because the raw tail is per-frame render-heal churn.
        "composerTargetNow=${vm.composerTarget} expectedTarget=$target " +
        "delivery=${boundedEventTail(
            diagnostics!!.events.filter {
                it.name in Issue2056InducedSubmitAmbiguity.DELIVERY_EVENT_NAMES
            },
        )} " +
        "events=${boundedEventTail(diagnostics!!.events)}"

    private fun boundedFieldValue(value: Any?): String = when (value) {
        null -> "null"
        is CharSequence -> value.take(DIAGNOSTIC_FIELD_VALUE_LIMIT).toString()
        is Number, is Boolean, is Enum<*> ->
            value.toString().take(DIAGNOSTIC_FIELD_VALUE_LIMIT)
        else -> "<${value.javaClass.simpleName.take(DIAGNOSTIC_FIELD_VALUE_LIMIT)}>"
    }

    private fun boundedCaptureEntries(entries: List<String>): String =
        entries.takeLast(DIAGNOSTIC_CAPTURE_LIMIT_COUNT)
            .joinToString(prefix = "[", postfix = "]") { it.take(DIAGNOSTIC_CAPTURE_LIMIT) }

    private fun waitForIssue1739CapturePrecondition(
        label: String,
        probe: Issue1739CaptureProbe,
    ) {
        waitForIssue1739Boundary(
            timeoutMs = ACK_CAPTURE_OBSERVED_TIMEOUT_MS,
            label = label,
            timeoutDetails = probe.timeoutDetails,
        ) {
            probe.observed.get()
        }
        writeText(
            "issue1739-capture-precondition-${testName.methodName}.txt",
            "matched=${probe.matched.get()}\n" +
                "readyOnly=${boundedCaptureEntries(probe.readyOnly)}\n" +
                "rejected=${boundedCaptureEntries(probe.rejected)}\n" +
                "capture=${probe.firstRealCapture.get().take(DIAGNOSTIC_CAPTURE_LIMIT)}\n",
        )
    }

    /**
     * Issue #1739 post-failure redispatch pump.
     *
     * The production auto-flush correctly suppresses an immediate retry, then
     * wakes after `OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS` on app Main. Under
     * [createAndroidComposeRule] that delay is virtual, just like the ack
     * deadline above. Poll the REAL server-side pane and SAME-token diagnostic
     * while advancing the Compose Main clock in bounded 20ms steps. This keeps
     * the real queue/backoff/auto-flush path load-bearing—no direct send call,
     * no production delay override—and retains an independent hard wall bound.
     */
    private fun waitForIssue1739RetrySubmission(
        label: String,
        timeoutMs: Long,
        verified: () -> Boolean,
        submitted: (String) -> Boolean,
    ): String {
        var last = ""
        val matched = pollSidecarCaptureWhileDrivingIssue1739Main(timeoutMs) { capture ->
            last = capture
            verified() && submitted(capture)
        }
        assertTrue(
            "$label did not resolve within ${timeoutMs}ms while driving the real " +
                "auto-flush; verified=${verified()} capture:\n$last",
            matched != null,
        )
        return matched ?: last
    }

    private fun pollSidecarCaptureWhileDrivingIssue1739Main(
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val capture = runBlocking { sidecarCapturePane() }
            if (predicate(capture)) return capture

            var tick = 0
            while (
                tick < ISSUE1739_MAIN_CLOCK_STEPS_PER_SIDECAR_POLL &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                compose.mainClock.advanceTimeBy(ISSUE1739_MAIN_CLOCK_STEP_MS)
                SystemClock.sleep(ISSUE1739_MAIN_CLOCK_STEP_MS)
                tick += 1
            }
        }
        return null
    }

    private fun waitForSidecarCaptureWhileDrivingIssue1739Main(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val matched = pollSidecarCaptureWhileDrivingIssue1739Main(timeoutMs, predicate)
        assertTrue(
            "$label: sidecar capture never satisfied predicate within ${timeoutMs}ms " +
                "while advancing Compose Main by ${ISSUE1739_MAIN_CLOCK_STEP_MS}ms steps; " +
                "diagnostics=${diagnostics?.events?.map { "${it.name}${it.fields}" }}",
            matched != null,
        )
        return matched.orEmpty()
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun awaitAuthoritativeLeaseOutage(
        viewModel: TmuxSessionViewModel,
        outage: AuthoritativeSshLeaseConnector.LeaseOutageForTest,
    ) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            outage.blockedAttemptCount > 0 &&
                currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected &&
                !viewModel.isSendTransportWritable()
        }
        assertTrue(outage.blockedAttemptCount > 0)
    }

    private fun assertAuthoritativeLeaseOutageHeld(
        viewModel: TmuxSessionViewModel,
        outage: AuthoritativeSshLeaseConnector.LeaseOutageForTest,
        startedAtMs: Long,
        label: String,
    ) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            SystemClock.elapsedRealtime() - startedAtMs >= OUTAGE_OFFLINE_MS
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        assertTrue(outage.blockedAttemptCount > 0)
        assertFalse("$label wire remains offline", viewModel.isSendTransportWritable())
        recordTiming("${label.lowercase()}_authoritative_offline_ms", elapsedMs)
        recordTiming("${label.lowercase()}_authoritative_blocked_dials", outage.blockedAttemptCount.toLong())
    }

    private fun authoritativeLeaseConnector(): AuthoritativeSshLeaseConnector {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(context, TestAccessEntryPoint::class.java)
            .authoritativeSshLeaseConnector()
    }

    private fun awaitBlockedNavigationSettled(
        viewModel: TmuxSessionViewModel,
        outage: AuthoritativeSshLeaseConnector.LeaseOutageForTest,
        blockedBaseline: Int,
        label: String,
    ) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            outage.blockedAttemptCount > blockedBaseline && !viewModel.connectJobActiveForTest()
        }
        assertTrue(
            "$label navigation must exhaust its blocked authoritative connect before restore",
            outage.blockedAttemptCount > blockedBaseline && !viewModel.connectJobActiveForTest(),
        )
    }

    private fun awaitSustainedOutageTerminalization(
        viewModel: TmuxSessionViewModel,
        outage: AuthoritativeSshLeaseConnector.LeaseOutageForTest,
        notBeforeMs: Long,
    ) {
        var lastBlockedCount = -1
        var stableSinceMs = 0L
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            val now = SystemClock.elapsedRealtime()
            val blockedCount = outage.blockedAttemptCount
            if (blockedCount != lastBlockedCount) {
                lastBlockedCount = blockedCount
                stableSinceMs = now
            }
            now >= notBeforeMs && now - stableSinceMs >= OUTAGE_QUIET_MS &&
                !viewModel.withinGraceRecoveryActiveForTest() &&
                viewModel.connectionControllerStateForTest() is ConnectionState.Unreachable &&
                !viewModel.connectJobActiveForTest()
        }
        compose.activityRule.scenario.onActivity { }
        assertTrue(
            !viewModel.withinGraceRecoveryActiveForTest() &&
            viewModel.connectionControllerStateForTest() is ConnectionState.Unreachable &&
                !viewModel.connectJobActiveForTest(),
        )
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    // ------------------------------------------------- seeding (see OutboundExactlyOnceFixture)

    /** Issue #2056 HARD precondition — see [Issue2056InducedSubmitAmbiguity]. */
    private fun assertPaneSubmitOracleIsUndecidable(label: String, payload: String) {
        // Require the fixture's own banner in the frame first: a blank sidecar read
        // would answer Unknown for the wrong reason. Deliberately NOT a settled-frame
        // wait — the heartbeat fixture repaints continuously and the oracle's verdict
        // does not depend on settling.
        var capture = ""
        waitForIssue1739Boundary(
            timeoutMs = CONNECTED_TIMEOUT_MS,
            label = "$label pane frame for the submit-oracle precondition",
            timeoutDetails = { "lastCapture=${capture.take(DIAGNOSTIC_CAPTURE_LIMIT)} failure=$lastSidecarFailure" },
        ) {
            capture = runBlocking { sidecarCapturePane() }
            capture.contains(FAKE_AGENT_READY)
        }
        Issue2056InducedSubmitAmbiguity
            .assertPaneSubmitOracleIsUndecidable(label, payload, capture)
    }

    // ---------------------------------------------------------------- artifacts

    private fun captureArtifacts(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        writeText("$name-visible-terminal.txt", visibleTerminalText())
    }

    /** Same-run reviewer evidence for #1944's composer and recovered terminal checkpoints. */
    private fun captureViewportArtifacts(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot for $name"
        }
        check(bitmap.width == 1080 && bitmap.height == 2400) {
            "$name must be the reviewer-required 1080x2400 viewport; got ${bitmap.width}x${bitmap.height}"
        }
        artifacts.writeViewport(name, bitmap)
        bitmap.recycle()
        writeText("$name-visible-terminal.txt", visibleTerminalText())
    }

    /**
     * Authoritative #1739 evidence: capture the active app window while the
     * Terminal tab is visibly backed by a live [TerminalView]. The PNG is taken
     * during the test, before activity teardown, and copied by the connected-test
     * additional-output collector together with its exact terminal text.
     */
    private fun captureLiveTerminalViewport(
        name: String,
        visibleText: String,
        viewModel: TmuxSessionViewModel,
        paneId: String,
    ): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(viewModel.agentConversations.value[paneId]?.selectedTab == SessionTab.Terminal) {
            "$name requires Terminal to be the authoritative selected tab"
        }
        check(hasNode(TMUX_TERMINAL_TAB_TAG) && !hasNode(TMUX_CONVERSATION_PANE_TAG)) {
            "$name requires the real Terminal tab UI with no Conversation surface visible"
        }
        instrumentation.waitForIdleSync()
        var terminalIsLiveAndShown = false
        compose.activityRule.scenario.onActivity { activity ->
            val terminal = activity.window.decorView.findTerminalView()
            terminalIsLiveAndShown =
                terminal != null &&
                terminal.isShown &&
                terminal.width > 0 &&
                terminal.height > 0 &&
                terminal.currentSession != null
        }
        check(terminalIsLiveAndShown) {
            "$name requires a visible live TerminalView before viewport capture"
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot for $name"
        }
        check(bitmap.width == 1080 && bitmap.height == 2400) {
            "$name must be the reviewer-required 1080x2400 viewport; " +
                "got ${bitmap.width}x${bitmap.height}"
        }
        val file = artifacts.writeViewport(name, bitmap, event = "ISSUE1739_LIVE_VIEWPORT")
        bitmap.recycle()
        writeText("$name-visible-terminal.txt", visibleText)
        return file
    }

    private fun openTerminalTab(viewModel: TmuxSessionViewModel, paneId: String) {
        compose.activityRule.scenario.onActivity {
            viewModel.selectSessionTab(paneId, SessionTab.Terminal)
        }
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            viewModel.agentConversations.value[paneId]?.selectedTab == SessionTab.Terminal
        }
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            viewModel.agentConversations.value[paneId]?.selectedTab == SessionTab.Terminal &&
                hasNode(TMUX_TERMINAL_TAB_TAG) &&
                !hasNode(TMUX_CONVERSATION_PANE_TAG)
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun writeText(name: String, text: String): File = artifacts.writeText(name, text)

    // Per-method file: full-class runs must not overwrite timing evidence.
    private fun writeTimings(): File = artifacts.writeTimings()

    private fun recordTiming(name: String, value: Long) = artifacts.recordTiming(name, value)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun readFakeAgentSubmitLedger(): List<Pair<Int, String>> {
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = "cat ${shellQuote(FAKE_AGENT_LEDGER_PATH)} 2>/dev/null || true",
            description = "issue1944 append-only fake-agent submit ledger",
        )
        assertEquals("fake-agent ledger read must succeed", 0, result.exitCode)
        return result.stdout.lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val fields = line.split('|', limit = 2)
                check(fields.size == 2) { "invalid fake-agent ledger row: $line" }
                requireNotNull(fields[0].toIntOrNull()) to
                    String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8)
            }
            .toList()
    }

    private companion object {
        // Fixture identity lives in OutboundExactlyOnceFixture (#2056 file-size split).
        const val DEVICE_DIR_NAME: String = "issue1526-exactly-once"
        const val SESSION_NAME: String = OutboundExactlyOnceFixture.SESSION_NAME
        const val SESSION_B: String = OutboundExactlyOnceFixture.SESSION_B
        const val SESSION_B_MARKER: String = OutboundExactlyOnceFixture.SESSION_B_MARKER
        const val FAKE_AGENT_LEDGER_PATH: String = OutboundExactlyOnceFixture.FAKE_AGENT_LEDGER_PATH
        const val LATE_ACK_STAGING_JSONL: String = OutboundExactlyOnceFixture.LATE_ACK_STAGING_JSONL
        const val TRANSCRIPT_RELAY_PID_PATH: String =
            OutboundExactlyOnceFixture.TRANSCRIPT_RELAY_PID_PATH
        const val SEEDED_JSONL: String = OutboundExactlyOnceFixture.SEEDED_JSONL
        const val FAKE_AGENT_READY: String = OutboundExactlyOnceFixture.FAKE_AGENT_READY
        const val BUSY_BUDGET_HOLD_MS: Long = 25_000L
        const val CONVERSATION_SEGMENT_TAG: String = TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + "1"

        val DETECTION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 45_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val UI_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 10_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 45_000L
        /**
         * Issue #1819: SCALED for CI (3x), matching every other environment
         * bound in this class (detection 45->90, host row 20->60, UI 10->30,
         * connected 45->90, deferral 30->60, submit-after-flap 90->180). This
         * one was overlooked and stayed a flat 5s — a wait for a REAL SSH
         * capture-pane round trip on a starved swiftshader emulator, which is
         * signature D of #1819. The LOCAL bound is unchanged at 5s, so a local
         * run remains the stricter check; only the CI environment is scaled.
         *
         * Deliberately NOT scaled below: [ACK_BOUNDED_RESULT_TIMEOUT_MS] and
         * [MAIN_HEARTBEAT_TIMEOUT_MS] bound PRODUCTION deadlines (the 800ms ack
         * bound and Main responsiveness). Those are the load-bearing #1739
         * assertions — scaling them would weaken what they constrain (G6).
         */
        val ACK_CAPTURE_OBSERVED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 5_000L
        const val ACK_BOUNDED_RESULT_TIMEOUT_MS: Long = 5_000L
        const val MAIN_HEARTBEAT_TIMEOUT_MS: Long = 2_000L
        const val ISSUE1739_MAIN_CLOCK_STEP_MS: Long = 20L
        const val OUTAGE_OFFLINE_MS: Long = 250L
        const val OUTAGE_GRACE_MS: Long = 4_000L
        const val OUTAGE_DIAL_MS: Long = 750L
        const val OUTAGE_MARGIN_MS: Long = 2_000L
        const val OUTAGE_QUIET_MS: Long = 500L
        val OUTAGE_RETRY_DELAYS_MS: List<Long> = listOf(0L, 250L, 500L)

        /**
         * Issue #1819 pumped-step sizes replacing bare `SystemClock.sleep`s.
         *
         * There is deliberately NO detection-poll step here: [waitForDetectionBound]
         * keeps its bare `SystemClock.sleep`, because its subject is a wall-clock
         * SSH round trip guarded by Main-scoped budgets rather than a Main
         * continuation. See that function's KDoc for the measured A/B.
         */
        const val SIDECAR_SETTLE_STEP_MS: Long = 300L
        const val TAB_POLL_STEP_MS: Long = 300L
        const val ISSUE1739_MAIN_CLOCK_STEPS_PER_SIDECAR_POLL: Int = 10
        const val DIAGNOSTIC_CAPTURE_LIMIT: Int = 1_024
        const val DIAGNOSTIC_CAPTURE_LIMIT_COUNT: Int = 3
        const val DIAGNOSTIC_EVENT_TAIL_COUNT: Int = 10
        const val DIAGNOSTIC_EVENT_NAME_LIMIT: Int = 64
        const val DIAGNOSTIC_FIELD_COUNT: Int = 10
        const val DIAGNOSTIC_FIELD_KEY_LIMIT: Int = 64
        const val DIAGNOSTIC_FIELD_VALUE_LIMIT: Int = 160
        val DEFERRAL_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L

        /**
         * How long the deferred row gets to auto-send off the SILENT within-grace
         * heal before the test steps in with the maintainer's own recovery
         * (re-typing the prompt + Send). The auto-flush resend fires the moment
         * the row defers; when it races the still-dying transport it re-defers
         * and the in-window exclusion holds it (audit A6 — slice S4 territory),
         * so this window stays short.
         */
        val SILENT_HEAL_SUBMIT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 20_000L else 10_000L

        /**
         * The "timely" bound: the deferred prompt must submit within this after
         * the Send tap — the flap + reconnect + auto-flush + verified resend
         * all inside it (audit A3's "delivered LONG AFTER" is the symptom).
         */
        val SUBMIT_AFTER_FLAP_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 180_000L else 90_000L
        val KEYSTROKE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L

        /** Covers the lane-B retry window (150ms delay + probe round-trip). */
        const val KEYSTROKE_SETTLE_MS: Long = 1_500L
    }
}
