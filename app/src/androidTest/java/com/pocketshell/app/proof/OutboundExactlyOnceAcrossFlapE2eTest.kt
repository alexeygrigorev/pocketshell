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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.composerOutboundQueueItemRowTestTag
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE
import com.pocketshell.app.composer.OUTBOUND_MAX_AUTO_ATTEMPTS
import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticPrivacy
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
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
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
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

/**
 * Issue #1526 — Slice S1+S6: the OUTBOUND EXACTLY-ONCE across-a-flap journey
 * (the audit's `OutboundExactlyOnceAcrossFlapE2eTest`), at the DELIVERY level.
 *
 * ## The recurrence class this pins (D31)
 *
 * #961 already "fixed" the twice-delivered prompt at the ENQUEUE layer (dedup
 * to one queued row) — and the maintainer still saw duplicates, because the
 * duplicate is manufactured on the WIRE: a send whose exec result is lost
 * mid-flap has ALREADY run `tmux send-keys` server-side, the row requeues, and
 * the reconnect auto-flush re-pasted the full payload with no check of what
 * landed (audit A1/A2/B2). The existing store-level proofs
 * (`PromptComposerDegradedSendE2eTest` "exactly one queued ROW") pass even
 * while the pane receives the text twice — so this journey asserts the
 * SERVER-SIDE occurrence count via a sidecar `tmux capture-pane`, never a
 * client-store proxy.
 *
 * ## Journey (emulator + the deterministic Docker `agents:2222` fixture)
 *
 * Composer lane: seed a tmux session running the `pocketshell-fake-agent`
 * input box recorded as Claude with a fresh Claude transcript for its cwd so
 * REAL live source detection binds; attach through the app, open
 * the Conversation tab, then send a prompt from the REAL composer (launcher →
 * draft field → Send) with the flap seam armed
 * ([OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter], the #780
 * synthetic-injection model): the paste runs on the REAL server, then the
 * transport is genuinely dropped before the submit Enter — the exact audit cut
 * point (c). The app silently reconnects (a REAL redial to the fixture; the
 * client identity changes) and the deferred row is re-sent — by the #900
 * auto-flush, or (when that resend raced the dying transport and the in-window
 * exclusion holds it — audit A6, slice S4) by the maintainer's own recovery of
 * re-typing the same prompt + Send, which #961 coalesces onto the SAME queued
 * row. Either way the resend rides the SAME agent delivery chain, where S1
 * PROBES (#869 ack needle), finds the payload already in the input box, and
 * submits ONLY Enter.
 *
 * Keystroke lane: type through the REAL TerminalView session (the pane input
 * queue + pump) with the lane-B seam armed
 * ([OutboundDeliverySeams.failInputSendResultLostOnce]): the bytes land, the
 * result is lost, and the pre-S1 blind attempt-2 would double them.
 *
 * ## Load-bearing assertions (RED on base → GREEN with S1)
 *
 *  - server-side occurrence of the payload in `capture-pane` == 1 (base: the
 *    re-paste doubles it — the input box reads `<payload><payload>`), and
 *  - the prompt is SUBMITTED exactly once (one `FAKE-AGENT SUBMITTED:` line,
 *    input box empty after), and
 *  - delivery completes within a bound after the flap (no unbounded delay),
 *  - the flap was REAL: the tmux client identity changed across the send.
 *
 * No `Assume.assumeFalse(isRunningOnCi())` on any load-bearing assertion; uses
 * ONLY the deterministic `agents:2222` fixture `tests.yml` already brings up
 * (no toxiproxy — the flap is seam-injected, the reconnect is real), so it is
 * wired into the per-push `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class OutboundExactlyOnceAcrossFlapE2eTest {

    // Launch-owned MainActivity rule (#788/#848): the Compose test clock drives
    // the SAME foreground MainActivity the TerminalView interop child is placed
    // into.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null
    private var issue1739CaptureCleanupGate: CompletableDeferred<Unit>? = null

    /** Issue #1819: the last sidecar SSH read error, so a blank frame can name its cause. */
    private var lastSidecarFailure: String? = null
    private val artifacts = OutboundAcceptanceArtifacts(DEVICE_DIR_NAME) { testName.methodName }
    private val queueViewport = OutboundQueueViewportCapture(compose, artifacts, ::visibleTerminalText)

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedFakeAgentSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @Before
    fun setUp() {
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        authoritativeLeaseConnector().resetOutageForTest()
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
        AgentSubmitCaptureSeams.reset()
    }

    @After
    fun tearDown() {
        runCatching { authoritativeLeaseConnector().resetOutageForTest() }
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
        AgentSubmitCaptureSeams.reset()
        issue1739CaptureCleanupGate?.complete(Unit)
        issue1739CaptureCleanupGate = null
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    /**
     * Composer lane (+ the reconnect auto-flush): a prompt sent from the real
     * composer during an injected mid-send flap must reach the pane EXACTLY
     * ONCE and submit exactly once — the resend must verify before re-pasting.
     */
    @Test
    fun composerPromptSentDuringFlapIsDeliveredExactlyOnce() { runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        waitForConnected("initial attach")
        val viewModel = currentViewModel()
        viewModel.setAgentSubmitEnterDelayForTest(0)
        val clientBeforeFlap = viewModel.currentClientIdentityForTest()

        // REAL live source detection must bind from the recorded Claude
        // identity plus its seeded fresh JSONL: a bound detection + the
        // Conversation tab is what routes the composer send down the
        // agent-payload delivery chain — the maintainer's duplicated-prompt
        // lane. An unbound pane routes RawBytes instead.
        waitForDetectionBound(viewModel)
        openConversationTab(viewModel)

        val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
        val payload = "exactly once across the flap $nonce"
        val payloadStripped = payload.filterNot { it.isWhitespace() }

        // Arm the flap seam: the NEXT agent-payload send DROPS the transport
        // AFTER the paste ran server-side, before the submit Enter — the exact
        // audit cut point (c) the maintainer's flaky link produces.
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = true

        // Drive the REAL composer: launcher -> draft -> Send.
        val sendTappedAtMs = SystemClock.elapsedRealtime()
        openComposerAndSend(payload)

        // The ambiguous failure must actually defer the row to the durable
        // queue (Option A) — the precondition the resend path exists for. Both
        // signals are accepted: the deferral diagnostic, or the user-visible
        // "Will send when reconnected." queue-row status.
        waitForDeferral()

        // Issue #1819: the INJECTION must be asserted where it is injected.
        // `triggerCleanPassiveDropForTest()` silently returns false when the
        // live classification says Ignore, and the arm it selects may skip
        // recovery entirely — so "the seam was armed" does NOT imply "a flap
        // happened". Proving it here, bounded, is what stops a no-op injection
        // from cascading into a 180s delivery timeout that reads like an
        // outbound-delivery bug (the whole reason this class was suspected of a
        // product race). The end-of-journey fresh-client assertion below is kept
        // as well — this one only makes it fail EARLIER and for the true reason.
        val clientAfterInjection = assertFlapInjected(clientBeforeFlap)

        // The flap heals (the within-grace silent reattach — a real redial to
        // the fixture). Give the #900 auto-flush resend a short window; if the
        // resend raced the dying transport and re-deferred (the in-window
        // exclusion then holds it — audit A6, slice S4), do what the maintainer
        // does: RE-TYPE the same prompt and tap Send. #961 coalesces it onto
        // the SAME queued row, and the send rides the SAME agent delivery
        // chain — where verify-before-resend must find the earlier paste and
        // NOT re-paste it (on base this exact user retry is what produced the
        // duplicated prompt).
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

        // GREEN: the prompt must SUBMIT — exactly once — within a bound (the
        // "timely" half of the acceptance). The authoritative signal is the
        // SERVER-side `capture-pane` (the TerminalView is covered by the
        // Conversation surface here).
        waitForSidecarCaptureWhileDrivingIssue1739Main(
            "prompt submitted after flap",
            SUBMIT_AFTER_FLAP_TIMEOUT_MS,
            submittedPredicate,
        )
        recordTiming("submitted_after_send_tap_ms", SystemClock.elapsedRealtime() - sendTappedAtMs)
        captureArtifacts("composer-submitted")

        // The flap was REAL: the send rode a reconnect (fresh client identity).
        waitForConnected("post-flap reconnect")
        val clientAfterFlap = currentViewModel().currentClientIdentityForTest()
        assertTrue(
            "the seam must have dropped the transport (fresh tmux client after " +
                "the flap); before=$clientBeforeFlap atInjection=$clientAfterInjection " +
                "after=$clientAfterFlap " +
                "seam=${diagnostics!!.eventsNamed("outbound_result_lost_seam")
                    .map { it.fields }} " +
                "events=${boundedEventTail(diagnostics!!.events)}",
            clientAfterFlap != null && clientAfterFlap != clientBeforeFlap,
        )

        // ===== THE delivery-level exactly-once assertions (server-side). =====
        val capture = waitForStableSidecarCapture()
        writeText("composer-final-capture.txt", capture)
        val captureStripped = capture.filterNot { it.isWhitespace() }
        assertFalse(
            "REGRESSION (#1526 base signature): the payload must NOT appear " +
                "doubled back-to-back (the blind re-paste writes " +
                "'<payload><payload>' into the input box); capture:\n$capture",
            captureStripped.contains(payloadStripped + payloadStripped),
        )
        assertEquals(
            "the payload must occur EXACTLY ONCE in the visible pane frame " +
                "(delivery-level exactly-once, not 'one queued row'); capture:\n$capture",
            1,
            countOccurrences(captureStripped, payloadStripped),
        )
        assertEquals(
            "the prompt must be SUBMITTED exactly once; capture:\n$capture",
            1,
            countOccurrences(captureStripped, FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped),
        )
        assertInputBoxEmpty("after the verified resend", capture)

        // Wiring proof: the resend actually took the verify-before-resend gate.
        val verifies = diagnostics!!.eventsNamed("outbound_verify_before_resend")
        assertTrue(
            "the resend must have PROBED before re-sending (verify-before-resend " +
                "wired on the auto-flush path); recorded=$verifies",
            verifies.any { it.fields["outcome"] == "AlreadyLanded" },
        )
        writeTimings()
    } }

    @Test
    fun fallbackQueueRowsSurviveSessionSwitchAndDrainOnlyIntoSameGeneration() { runBlocking<Unit> {
        enableIssue1944FramedFakeAgent()
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
        val returnedOfflineRows = store.itemsFor(fallbackA)
        assertEquals(
            "offline retry may advance only attempt time; payload, identity, route, and wire fields must survive",
            queued,
            returnedOfflineRows.mapIndexed { index, row ->
                row.copy(lastAttemptAtMs = queued[index].lastAttemptAtMs)
            },
        )
        assertEquals(listOf(0, 0), returnedOfflineRows.map { it.attemptCount })
        recordTiming("a_rows_visible_before_heal_ms", SystemClock.elapsedRealtime() - aReturnStartedAt)
        pressSystemBack()

        val healStartedAt = SystemClock.elapsedRealtime()
        val activeAVm = currentViewModel()
        assertTrue("the Activity must still route connectivity to its active host VM", activeAVm === bVm)
        assertAuthoritativeLeaseOutageHeld(activeAVm, outageB, outageBStartedAt, "B")
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
            store.itemsFor(fallbackA).isEmpty() &&
                (store.itemsFor(durableA).map { it.id } == queuedIds || store.itemsFor(durableA).isEmpty())
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
        waitForIssue1739Boundary(
            timeoutMs = CONNECTED_TIMEOUT_MS,
            label = "promoted durable rows drained after heal",
            timeoutDetails = {
                "durable=${store.itemsFor(durableA)} fallback=${store.itemsFor(fallbackA)} " +
                    "sendInFlight=${currentPromptComposerViewModel().uiState.value.sendInFlight} " +
                    "wire=${currentViewModel().isSendTransportWritable()} " +
                    "status=${currentConnectionStatus()} " +
                    "ledger=${runBlocking { readFakeAgentSubmitLedger() }} " +
                    "queueEvents=${diagnostics!!.events.filter { event ->
                        event.name == "row_state" || event.name == "drain_attempt" ||
                            event.name == "dispatch_rejected" ||
                            event.name == "composer_tmux_send_route" ||
                            event.name == "agent_submit_turnover" ||
                            event.name == "identity_promotion"
                    }.map { it.name to it.fields }}"
            },
        ) {
            store.itemsFor(durableA).isEmpty()
        }
        assertTrue("both stable rows must be delivered+pruned", store.itemsFor(durableA).isEmpty())
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
            "server ledger is the authoritative exactly-once FIFO oracle",
            listOf(firstPayload, secondPayload),
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
        val secondStripped = secondPayload.filterNot { it.isWhitespace() }
        assertEquals(
            "final visible frame must retain the latest submitted row and clean input; capture=$secondSubmitted",
            1,
            countOccurrences(
                secondSubmittedStripped,
                FAKE_AGENT_SUBMITTED_STRIPPED + secondStripped,
            ),
        )
        val deliveredIds = diagnostics!!.eventsNamed("row_state")
            .filter { it.fields["toState"] == "Sent" && it.fields["itemId"] in queuedIds }
            .map { it.fields["itemId"] }
        assertEquals("automatic drain must deliver FIFO exactly once", queuedIds, deliveredIds)
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
     * Wait for REAL live detection to bind (the #975 transcript-evidence path).
     *
     * DO NOT convert this [SystemClock.sleep] into [pumpComposeMainFor]. It is a
     * DELIBERATE exception to the #1739/#1773/#1798/#1819 pump sweep, and the
     * exception is load-bearing.
     *
     * The sweep's rationale is that a bare wall sleep freezes the
     * [createAndroidComposeRule]-installed Main [kotlinx.coroutines.test.TestDispatcher]
     * scheduler, so a production continuation the test is waiting FOR — a Main
     * `delay` retry timer, an ack/cleanup `withTimeoutOrNull` that must EXPIRE —
     * can never run. That rationale does not apply here, and inverts.
     *
     * This wait's subject is not a Main continuation: it is a WALL-CLOCK remote
     * round trip. `startAgentDetectionForPane` launches on `bridgeScope`
     * (= `viewModelScope` context, i.e. the virtual-time Main dispatcher) and the
     * pane/detection chain it depends on is wrapped in Main-scoped
     * `withTimeoutOrNull` budgets (`awaitPanesReadyForAttach`'s
     * `attachPanesReadyTimeoutMs`, the conversation load watchdog). Those budgets
     * are sized for a real device, not for a starved swiftshader AVD whose SSH
     * `list-panes` / `/proc/<pid>/fd` round trips run far slower in WALL time.
     * Freezing the virtual clock is exactly what lets those round trips finish:
     * the budgets simply never expire while the probe is in flight. Advancing the
     * clock ~1:1 with wall time re-arms them, and the probe gets CANCELLED rather
     * than completing — leaving `_agentConversations` entirely empty, which is why
     * the failure signature is `conversations={}` (an empty map, not a map with an
     * unbound row) rather than a slow bind.
     *
     * Measured, paired A/B on one lane, full 5-test class per run: `origin/main`
     * base 5/5 green; this diff with ONLY this hunk reverted 3/3 green; this diff
     * with the hunk pumped 4/9 green — every failure this function's own assertion
     * (Fisher one-tailed p ~ 0.020). [currentViewModel]'s `SystemClock.sleep(100)`
     * is left alone for the same reason: its subject is also a remote-IO
     * completion (panes arriving), not a Main continuation.
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
            // Prefer the real UI tap on the Conversation segment; ALSO drive
            // the SAME production entry the tap dispatches (selectSessionTab)
            // so a momentarily-unhittable pill on a loaded AVD cannot wedge the
            // precondition (the tab mechanics are not the property under test).
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
            // Issue #1819 audit: pumping is safe HERE even though opening the
            // Conversation does a remote window read, because this loop already
            // calls `compose.waitForIdle()` on every iteration — which advances
            // the Compose clock — so base was never clock-frozen across this
            // wait and the pump introduces no new clock advancement. (Contrast
            // [waitForDetectionBound], whose base loop was a bare sleep and
            // therefore genuinely frozen.) The exit condition is also the TAB
            // being open, not the transcript having loaded, so no one-shot
            // remote completion is riding on this wait.
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

    /**
     * Drive the REAL composer: launcher -> draft -> Send, and PROVE the send
     * was dispatched before returning.
     *
     * Issue #1819 — the locally reproduced defect. This helper used to
     * `performTextInput(payload)` and immediately `performClick()` Send with no
     * wait and no check. When the sheet had not yet recomposed the draft, Send
     * fired against an empty draft, production correctly did nothing, and NO
     * `composer_send` was ever recorded — so the journey's own scenario never
     * started and every downstream wait (deferral, ack capture, submission) then
     * burned its full budget and failed with a message that reads like an
     * outbound-delivery bug. Captured locally: `events=total=61` with no
     * `composer_send`/`enqueue`, `rowsTotal=0`.
     *
     * The fix removes the race at its CAUSE rather than retrying an assertion:
     * the draft content is confirmed committed before Send is tapped, and the
     * dispatch is then confirmed from the production `composer_send` diagnostic.
     * This is a DRIVER, not the property under test — the property is
     * exactly-once delivery, and a driver that silently fails to drive is the
     * #1778 shape (the injected scenario never happening). Both waits are
     * bounded by the existing [UI_TIMEOUT_MS] and hard-fail.
     */
    private fun openComposerAndSend(payload: String) {
        // Open the composer via the launcher unless the sheet is already open
        // (a deferred send leaves it open with the queue row visible).
        if (!hasNode(COMPOSER_DRAFT_TAG)) {
            // A successful handoff dismisses the modal asynchronously. Let the
            // launcher become the settled foreground hit target before opening
            // the next real composer; its semantics node is structurally present
            // during the closing animation but a tap in that overlap is ignored.
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

        // The draft must actually hold the payload before Send is tapped —
        // otherwise the tap dispatches nothing and the journey silently no-ops.
        waitForIssue1739Boundary(
            timeoutMs = UI_TIMEOUT_MS,
            label = "composer draft committed before Send",
            timeoutDetails = { "draftText=${draftText()} expectedLength=${payload.length}" },
        ) {
            draftText().contains(payload)
        }

        // ...and Send must be ENABLED when it is tapped. `PromptComposerSheet`
        // computes `sendEnabled = !state.outboundHandoffInProgress && ...`, and a
        // performClick on a DISABLED Compose button silently does nothing — so a
        // tap that lands while a previous handoff is still in flight dispatches
        // no send at all. Observed on determinism run det4/6 with the draft fully
        // committed: "the Send tap did not dispatch: no new 'composer_send'
        // diagnostic; sendsBefore=0 draftText=<full payload>". Wait for the
        // enabled state rather than retrying the click — same reason as above,
        // the race is removed at its cause.
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

        // The tap must have DISPATCHED a send. Without this the whole journey
        // can proceed against a send that never happened.
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

    /**
     * Is the composer's Send control currently ENABLED? A disabled Compose node
     * carries [SemanticsProperties.Disabled]; `performClick` on it is a silent
     * no-op, which is how a tap can leave no trace at all.
     */
    private fun sendButtonEnabled(): Boolean = runCatching {
        val node = compose.onAllNodesWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return false
        !node.config.contains(SemanticsProperties.Disabled)
    }.getOrDefault(false)

    /** The live composer draft field's text, or "" when the sheet is not open. */
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

    /**
     * The VISIBLE pane frame via a sidecar SSH `capture-pane -p` — the
     * authoritative delivery-level surface. Deliberately NOT `-S -N`
     * (scrollback): the fake-agent redraws its whole frame (clear + reprint) on
     * reconnect/resize, so scrollback accumulates ECHOES of the single input
     * line / submitted marker across frames; the visible frame always holds
     * exactly READY + the (single) SUBMITTED line + the input box, so payload
     * occurrence counts on it are deterministic.
     */
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

    /**
     * Issue #1819: this is the wait behind [waitForFreshClient], i.e. it waits
     * for a PRODUCTION reconnect to install a replacement client. It used to
     * sleep wall time with the Compose scheduler frozen, so the reconnect it
     * waits for could be starved by the wait itself. Pump instead; the hard wall
     * deadline stays the load-bearing bound.
     *
     * Audited against the [pumpComposeMainFor] rule and deliberately KEPT on the
     * pump side, even though a reconnect is remote IO. A reconnect is not a
     * one-shot round trip: it is a retry LADDER whose backoff/spacing are Main
     * `delay`s (the grace `withTimeoutOrNull` loop and its 250ms retry spacing),
     * so a frozen virtual clock wedges the ladder instead of protecting it — the
     * opposite of [waitForDetectionBound]. And this wait's bound is
     * [CONNECTED_TIMEOUT_MS] (45s local / 90s CI), many times a single attempt's
     * dial/attach budget, so an attempt that the advancing clock does cancel is
     * simply re-tried well inside the bound. Detection has neither property.
     */
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

    /**
     * Poll the real pane while pacing launch-owned Compose Main.
     *
     * The sidecar SSH read is wall-clock IO. Between reads, drive Main in the
     * same bounded 20ms steps as the #1739 acknowledgement and redispatch
     * helpers so a non-immediate capture, ack timeout, Enter, or queue
     * continuation cannot be frozen merely because this journey is observing
     * the real pane. The hard wall deadline remains authoritative.
     */
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

    // ---------------------------------------------------------------- seeding

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1526-exactly-once-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1526 ExactlyOnce",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * Seed a tmux session running the deterministic `pocketshell-fake-agent`
     * input box (echoes typed chars; on Enter prints `FAKE-AGENT SUBMITTED:
     * <line>` and clears the box), recorded as Claude and paired with a FRESH
     * Claude transcript for the pane's cwd so REAL live source detection
     * binds — a bound detection + the Conversation tab is what routes the
     * composer send down the agent-payload delivery chain
     * (`sendToAgentPaneResult` → `sendAgentPayloadToPaneResult`), the lane the
     * maintainer's duplicated prompts ride.
     */
    private suspend fun seedFakeAgentSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true")
            appendLine("rm -f ${shellQuote(FAKE_AGENT_LEDGER_PATH)}")
            appendLine("mkdir -p /home/testuser/.claude/projects/-home-testuser")
            appendLine(
                "cp /home/testuser/.claude/projects/-workspace-pocketshell/" +
                    "pocketshell-claude.jsonl " +
                    "/home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL",
            )
            appendLine("touch /home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    "-c /home/testuser " +
                    shellQuote(
                        "POCKETSHELL_FAKE_AGENT_SUBMIT_LEDGER=$FAKE_AGENT_LEDGER_PATH " +
                            "POCKETSHELL_FAKE_AGENT_TRANSCRIPT=/home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL " +
                            "exec /usr/local/bin/pocketshell-fake-agent",
                    ),
            )
            // Issue #1963: this exactly-once fixture is an intentionally fake
            // Bash input surface, so the healthy host classifier correctly
            // reports `none`. Recording it as `shell` made binding depend on
            // the #975 masked-live fallback, which deliberately accepts JSONL
            // evidence only after an UNREADABLE (`unknown`) classification —
            // never after a readable `none`. Full-shard state exposed that
            // mismatch as `agentConversations={}` after the first method.
            // Record the identity this fixture is explicitly modelling, then
            // keep the real source selector + transcript reader load-bearing.
            // Nothing is injected into the ViewModel and the production
            // `detection != null` precondition below remains unchanged.
            appendLine("tmux set-option -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine(
                "tmux show-options -v -t ${shellQuote(SESSION_NAME)} " +
                    "@ps_agent_kind | grep -qx claude",
            )
            appendLine(
                "test -s /home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} -x 80 -y 40 " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_B)} @ps_agent_kind shell")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1526 fake-agent tmux seed session",
        )
        assertTrue(
            "expected fake-agent seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    /**
     * Give only the #1944 outage proof the framed Claude/Codex-shaped input.
     * Other journeys in this class intentionally retain the legacy `>` fixture
     * because their assertions characterize that readline surface.
     */
    private suspend fun enableIssue1944FramedFakeAgent() {
        val command =
            "tmux respawn-pane -k -t ${shellQuote("=$SESSION_NAME:0.0")} " +
                shellQuote(
                    "POCKETSHELL_FAKE_AGENT_SUBMIT_LEDGER=$FAKE_AGENT_LEDGER_PATH " +
                        "POCKETSHELL_FAKE_AGENT_TRANSCRIPT=/home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL " +
                        "POCKETSHELL_FAKE_AGENT_RENDER_MODE=issue1944-framed-input " +
                        "exec /usr/local/bin/pocketshell-fake-agent",
                )
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = command,
            description = "issue1944 framed fake-agent input surface",
        )
        assertEquals("framed fake-agent respawn must succeed: ${result.stderr}", 0, result.exitCode)
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec(
                        "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true; " +
                            "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true; " +
                            "rm -f /home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL " +
                            "${shellQuote(FAKE_AGENT_LEDGER_PATH)} " +
                            "2>/dev/null || true",
                    )
                }
            }
        }
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

    /**
     * Issue #1621 (round-three review follow-up): suffix the timings artifact with
     * the TEST METHOD name. Both methods in this class used to write the one shared
     * `timings.txt`, so in a normal full-class run the keystroke lane overwrote the
     * composer lane's `user_retype_resend_used` / `submitted_after_send_tap_ms` —
     * the branch-taken evidence was only recoverable by running a method in
     * isolation. Per-method files make it readable from any run.
     */
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
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1526-exactly-once"
        const val SESSION_NAME: String = "issue1526-exactly-once"
        const val SESSION_B: String = "issue1944-switch-b"
        const val SESSION_B_MARKER: String = "ISSUE1944-B-READY"
        const val FAKE_AGENT_LEDGER_PATH: String = "/tmp/pocketshell-fake-agent-submits.log"
        const val SEEDED_JSONL: String = "issue1526-live-claude.jsonl"
        const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"
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
