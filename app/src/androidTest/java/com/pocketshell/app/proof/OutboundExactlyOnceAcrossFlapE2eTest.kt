package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.OutboundDeliverySeams
import com.pocketshell.app.tmux.PasteChunkSeams
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import kotlinx.coroutines.runBlocking
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
 * input box with a fresh Claude transcript for its cwd (the #975 masked-live
 * fixture shape) so REAL live detection binds; attach through the app, open
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
    private val timings = mutableListOf<String>()

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
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
    }

    @After
    fun tearDown() {
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
        PasteChunkSeams.reset()
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

        // REAL live detection must bind (the #975 transcript-evidence path the
        // seeded fresh Claude JSONL drives): a bound detection + the
        // Conversation tab is what routes the composer send down the
        // agent-payload delivery chain — the maintainer's duplicated-prompt
        // lane. A presumed-only/recorded-only pane routes RawBytes instead.
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
        val silentHealSubmitted = pollSidecarCapture(SILENT_HEAL_SUBMIT_WINDOW_MS, submittedPredicate)
        if (!silentHealSubmitted) {
            recordTiming("user_retype_resend_used", 1L)
            openComposerAndSend(payload)
        }

        // GREEN: the prompt must SUBMIT — exactly once — within a bound (the
        // "timely" half of the acceptance). The authoritative signal is the
        // SERVER-side `capture-pane` (the TerminalView is covered by the
        // Conversation surface here).
        waitForSidecarCapture(
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
                "the flap); before=$clientBeforeFlap after=$clientAfterFlap",
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
        if (!pollSidecarCapture(SILENT_HEAL_SUBMIT_WINDOW_MS, submittedPredicate)) {
            recordTiming("user_retype_resend_used", 1L)
            openComposerAndSend(payload)
        }
        waitForSidecarCapture(
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
     * The text the fake agent SUBMITTED, whitespace-stripped — everything between
     * its `FAKE-AGENT SUBMITTED: ` marker and the `> ` input box that follows it.
     * The input box wraps across rows as it grows, so the capture is compared
     * whitespace-insensitively; the payload carries no whitespace of its own, so
     * this is exact for the property under test.
     */
    private fun submittedTextStripped(capture: String): String {
        val stripped = capture.filterNot { it.isWhitespace() }
        val start = stripped.indexOf(FAKE_AGENT_SUBMITTED_STRIPPED)
        assertTrue(
            "the fixture must have submitted a prompt; capture:\n$capture",
            start >= 0,
        )
        val body = stripped.substring(start + FAKE_AGENT_SUBMITTED_STRIPPED.length)
        val inputBox = body.indexOf('>')
        return if (inputBox >= 0) body.substring(0, inputBox) else body
    }

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
        val capture = waitForSidecarCapture("typed keystrokes visible", KEYSTROKE_TIMEOUT_MS) {
            it.filterNot { ch -> ch.isWhitespace() }.contains(typed)
        }
        recordTiming("keystrokes_visible_after_type_ms", SystemClock.elapsedRealtime() - typedAtMs)

        // ... and after the retry window settles, EXACTLY ONCE (base: the blind
        // attempt 2 re-sends the batch and the input box reads '<typed><typed>').
        SystemClock.sleep(KEYSTROKE_SETTLE_MS)
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

    /** Wait for REAL live detection to bind (the #975 transcript-evidence path). */
    private fun waitForDetectionBound(vm: TmuxSessionViewModel) {
        val deadline = SystemClock.elapsedRealtime() + DETECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.agentConversations.value.values.any { it.detection != null }) return
            SystemClock.sleep(150)
        }
        assertTrue(
            "precondition: live detection must bind (fresh seeded Claude JSONL) so " +
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
            SystemClock.sleep(300)
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

    private fun openComposerAndSend(payload: String) {
        // Open the composer via the launcher unless the sheet is already open
        // (a deferred send leaves it open with the queue row visible).
        if (!hasNode(COMPOSER_DRAFT_TAG)) {
            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                hasNode(SESSION_COMPOSER_LAUNCHER_TAG)
            }
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { hasNode(COMPOSER_DRAFT_TAG) }
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true).performTextInput(payload)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
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
        val deadline = SystemClock.elapsedRealtime() + DEFERRAL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (diagnostics!!.eventsNamed("composer_send_deferred_to_queue").isNotEmpty()) return
            if (deferredRowVisible()) return
            SystemClock.sleep(100)
        }
        assertTrue(
            "expected the ambiguous send to DEFER (diagnostic " +
                "'composer_send_deferred_to_queue' or the 'Will send when " +
                "reconnected.' row) within ${DEFERRAL_TIMEOUT_MS}ms; " +
                "recorded=${diagnostics!!.events.map { it.name }.distinct()}; " +
                "pane capture:\n${runBlocking { sidecarCapturePane() }}",
            false,
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
    private suspend fun sidecarCapturePane(): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    /** Non-asserting sidecar poll: true when [predicate] matched within [timeoutMs]. */
    private fun pollSidecarCapture(timeoutMs: Long, predicate: (String) -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(runBlocking { sidecarCapturePane() })) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun waitForSidecarCapture(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runBlocking { sidecarCapturePane() }
            if (predicate(last)) return last
            SystemClock.sleep(250)
        }
        assertTrue(
            "$label: sidecar capture never satisfied predicate; " +
                "diagnostics=${diagnostics?.events?.map { "${it.name}${it.fields}" }}; " +
                "capture:\n$last",
            predicate(last),
        )
        return last
    }

    /** Poll the sidecar capture until two consecutive reads agree (settled). */
    private fun waitForStableSidecarCapture(): String {
        var previous = runBlocking { sidecarCapturePane() }
        repeat(20) {
            SystemClock.sleep(300)
            val next = runBlocking { sidecarCapturePane() }
            if (next == previous && next.isNotBlank()) return next
            previous = next
        }
        return previous
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

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
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
            SystemClock.sleep(100)
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
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
     * <line>` and clears the box), with a FRESH Claude transcript for the
     * pane's cwd (the #975 transcript-evidence shape) so REAL live detection
     * binds — a bound detection + the Conversation tab is what routes the
     * composer send down the agent-payload delivery chain
     * (`sendToAgentPaneResult` → `sendAgentPayloadToPaneResult`), the lane the
     * maintainer's duplicated prompts ride.
     */
    private suspend fun seedFakeAgentSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
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
                    shellQuote("exec /usr/local/bin/pocketshell-fake-agent"),
            )
            // The #975/#1057 masked-live fixture shape (mirrors the proven
            // ConversationTuiCommandJourneyDockerTest seeding): the session
            // records `shell` while the fresh cwd transcript above is the
            // evidence that binds REAL live Claude detection.
            appendLine("tmux set-option -t ${shellQuote(SESSION_NAME)} @ps_agent_kind shell")
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
                            "rm -f /home/testuser/.claude/projects/-home-testuser/$SEEDED_JSONL " +
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

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1526_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    /**
     * Issue #1621 (round-three review follow-up): suffix the timings artifact with
     * the TEST METHOD name. Both methods in this class used to write the one shared
     * `timings.txt`, so in a normal full-class run the keystroke lane overwrote the
     * composer lane's `user_retype_resend_used` / `submitted_after_send_tap_ms` —
     * the branch-taken evidence was only recoverable by running a method in
     * isolation. Per-method files make it readable from any run.
     */
    private fun writeTimings(): File {
        val file = artifactFile("timings-${testName.methodName}.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1526_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1526_TIMING $line")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1526-exactly-once"
        const val SESSION_NAME: String = "issue1526-exactly-once"
        const val SEEDED_JSONL: String = "issue1526-live-claude.jsonl"
        const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"
        const val FAKE_AGENT_SUBMITTED_STRIPPED: String = "FAKE-AGENTSUBMITTED:"
        const val CONVERSATION_SEGMENT_TAG: String = TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + "1"

        val DETECTION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 45_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val UI_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 10_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 45_000L
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
