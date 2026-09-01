package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #1153 — the DISCRIMINATING on-device journey for the maintainer's "I attach something and
 * I send it, my screen went dark / black … it partly redrew itself but it was still too black …
 * there was no reconnect" (v0.4.21 dogfood, 2026-07-01).
 *
 * ## The maintainer's report + root cause
 *
 * A composer Send WITH AN ATTACHMENT is ALWAYS multi-line (it appends an "Attached files:" block),
 * so it takes the bracketed-paste + submit branch and the alt-screen agent clear+cursor-address-
 * redraws its WHOLE viewport. The overpaint blanks the local grid and the agent only PARTIALLY
 * repaints, leaving a large black BAND above a surviving input box + a few conversation lines +
 * status — MORE than the ≤3 live lines the pre-#1153 partial-black heuristic capped at. The
 * transport never dropped (no reconnect). The #941 post-send heal was supposed to catch this but
 * gated on the LOCAL-ONLY `blank || partialBlank` heuristic and SKIPPED a >3-line half-black
 * frame, and its single fixed 350 ms one-shot lost the race against the bigger attachment redraw.
 *
 * ## What this journey PROVES (D33 / G10 — real path, guaranteed-live transport)
 *
 * Seed a tmux session running a FULL-screen frame, attach, then:
 *  1. Drive the REAL production send path with a WITH-ATTACHMENT (multi-line) payload against an
 *     acknowledgement-capable agent pane, and prove the observed ack belongs to this exact live
 *     pane/client/session/generation.
 *  2. Before the Send reconcile's bounded settle ticks, inject the >3-line HALF-BLACK overpaint
 *     (clear + a handful of live lines, NO frame marker) straight into the SAME live emulator the
 *     app renders — the REMOTE tmux grid keeps the full frame, so the transport is GUARANTEED LIVE.
 *  3. Assert the SEND heal REPAINTS the visible render to MATCH tmux's authoritative `capture-pane`
 *     (the marker re-renders across the upper rows AND the render is no longer a lost-frame vs the
 *     capture — [TerminalSurfaceState.visibleRenderLostFrameVsCapture] goes TRUE→FALSE across the
 *     send), the transport stays `Connected`, and NO reconnect surface appears.
 *
 * ## Why the acceptance is "matches tmux's capture", NOT an absolute row-fraction
 *
 * The heal's real contract is "the visible render is repainted to the SAME content tmux holds"
 * ([TerminalSurfaceState.visibleRenderLostFrameVsCapture] false), NOT "the live rows exceed some
 * fraction of the viewport". A correctly-restored agent frame can legitimately be a small fraction
 * of a tall (e.g. 56-row) emulator viewport — the unified local suspect pre-gate
 * [TerminalSurfaceState.renderLooksSuspect] (the single 0.75 live-fraction cost-gate every launcher
 * shares, epic #1353 R1) reads
 * such a frame "sparse", which is HARMLESS: the authoritative `capture-pane` diff is the load-bearing
 * guard and refuses to re-heal a frame that already matches tmux (proven at the JVM level by
 * `shortPromptDoesNotThrashHeal`). So the final acceptance compares visible-vs-capture, not a
 * viewport-relative row-fraction that a legitimate restored frame fails on a tall CI viewport.
 *
 * The steady-state stale-render watchdog is PAUSED for the run
 * ([TmuxSessionViewModel.pauseActivePaneStaleRenderWatchdogForTest] disables future auto-arms and
 * cancels/joins the attach-owned loop), so ONLY the immediate SEND heal can green the pane —
 * proving the fix is on the send path, not a slow background watchdog tick. The half-black is
 * injected SYNTHETICALLY (the CI swiftshader AVD can't run a real agent redraw) and the
 * load-bearing assertions HARD-fail otherwise — no
 * `Assume.assumeFalse(isRunningOnCi())` self-skip (the #780/#1138 model).
 *
 * Runs on the per-PR emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class SendWithAttachmentStaysVisibleE2eTest {
    private lateinit var trustedHostKeySha256: String

    // Launch-owned MainActivity rule (#788/#848): the Compose test clock drives the SAME
    // foreground MainActivity the Termux TerminalView interop child is placed into.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null

    private suspend fun seedBeforeLaunch() {
        // Issue #2124: the acknowledgement this class asserts
        // (`assertExactlyCurrentAckObserved` — exactly one observed
        // `agent_submit_ack`, never timeout/fallback) is the LEGACY
        // terminal-inference oracle, emitted only by `AgentSubmitAck`. The
        // shipped default is now the acknowledged host-CLI path, which never
        // consults that stack, so select the legacy authority by name rather
        // than letting a default flip silently re-point the class at a
        // different state machine. The #1153 send-heal behaviour this class
        // exists to prove is unchanged by the selection.
        //
        // Issue #2189 decision: REPLACE. The send-heal is preserved verbatim
        // on HostAck (`onDelivered` → `ReconcileReason.Send`). Keep this class
        // as the inference-ack proof until #2125; the shipped-default sibling
        // is [Issue2189HostAckSendHealJourneyE2eTest]. The `agent_submit_ack`
        // field-binding oracle drops with the stack.
        pinOutboundDeliveryAuthority(
            com.pocketshell.app.settings.OutboundDeliveryAuthority.TerminalInference,
        )
        BackgroundGraceTestOverride.setForTest(null)
        val key = readFixtureKey()
        fixtureKey = key
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(key))
        seedFullFrameSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @Before
    fun setUp() {
        // Issue #2124: zero before every test so the tearDown assertion below is
        // about THIS test's sends.
        com.pocketshell.app.tmux.HostAckSendProbe.reset()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        // Issue #2124: this class asserts the LEGACY inference acknowledgement,
        // so not one of its sends may have ridden the acknowledged host-CLI
        // lane. This is the behavioural half of the authority pin's liveness
        // proof: without the pin the legacy `agent_submit_ack` never fires at
        // all (`events=[]`), which is exactly how this class reddened on
        // run 31953547265.
        assertNoAcknowledgedSendsWereRecorded("SendWithAttachmentStaysVisibleE2eTest")
        diagnostics?.close()
        diagnostics = null
        clearOutboundDeliveryAuthorityPin()
        BackgroundGraceTestOverride.setForTest(null)
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun sendWithAttachmentHealsHalfBlackActivePane() { runBlocking {
        attachSeededTmuxSession(hostRowTag)

        // Atomically disable future auto-arms AND cancel/join the watchdog that attach already
        // started. Once this returns, ONLY the immediate send heal can green a half-black pane.
        pauseAndProveStaleRenderWatchdogQuiescent()

        // Baseline: the full frame is on screen and the session is Connected.
        waitForVisibleTerminal("initial full frame") { frameRowCount(it) >= MIN_RESTORED_FRAME_ROWS }
        waitForConnected("initial attach")
        capturePaintedRows("issue1153-00-attached")

        // tmux's AUTHORITATIVE grid (via `capture-pane -p`) — the full frame. The half-black is
        // injected LOCAL-only, and the opt-in fake agent updates only its lower input rows, so the
        // REMOTE upper frame remains intact throughout and is the ground truth the send heal must
        // repaint back to.
        val authoritativeCapture = captureRemoteTmuxPane()
        assertTrue(
            "sanity: tmux's authoritative capture must hold the full frame marker; capture:\n$authoritativeCapture",
            authoritativeCapture.contains(FRAME_MARKER),
        )

        // Keep the activity RESUMED across the send + heal window so a loaded CI emulator's
        // spurious onStop cannot park/clear the runtime mid-send (the runtime the heal runs on
        // must stay alive through drive+assert).
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }

        // ===== Drive the REAL production send path with a WITH-ATTACHMENT (multi-line) payload. =====
        // Issue #1769: #1739 made missing acknowledgement authoritative. Snapshot the exact live
        // pane/runtime BEFORE the send, clear diagnostics, and require the one resulting ack to
        // bind to that same pane, target session, client, and generation. There is no durable queue
        // row in this direct VM call; the TmuxPaneState is the authoritative current row.
        val ackAuthority = snapshotAckAuthority()
        diagnostics!!.clear()

        // ===== Inject the >3-line HALF-BLACK overpaint on the LIVE, retained emulator. =====
        // Clear + a handful of live lines (NO frame marker), leaving a large black band. The
        // REMOTE tmux grid keeps the full frame, so the transport stays GUARANTEED LIVE.
        val esc = ""
        val halfBlack = buildString {
            append("$esc[2J$esc[H")
            // Cursor-address each live line onto a row SPACED [HALF_BLACK_ROW_STRIDE] apart, so a
            // blank row separates them and `getSelectedText(joinBackLines=true)` cannot fold
            // adjacent short lines into one logical line (contiguous short lines pack/join on the
            // wide phone viewport and read as fewer live lines). The interspersed blank rows also
            // ARE the black band this reproduces.
            for (line in 0 until HALF_BLACK_LIVE_LINES) {
                val row = 1 + line * HALF_BLACK_ROW_STRIDE
                append("$esc[$row;1H$HALF_BLACK_MARKER live line $line after send overpaint")
            }
        }
        val sendAndInject = driveAcknowledgedSendThenInject(
            authority = ackAuthority,
            halfBlack = halfBlack,
            authoritativeCapture = authoritativeCapture,
        )
        val ackEvent = sendAndInject.ack
        val injected = sendAndInject.halfBlack
        // Written before the heal oracle so the disabled-heal mutation still proves ack stayed
        // green while the load-bearing final render assertion went red.
        writeAckAuthority(ackAuthority, ackEvent)

        // ----- Symptom precondition: the injected pane is the >3-line HALF-BLACK send state. -----
        val afterInject = injected.visibleText
        assertFalse(
            "the injected half-black must have WIPED the full frame (marker gone from the visible " +
                "grid); visible:\n$afterInject",
            afterInject.contains(FRAME_MARKER),
        )
        assertTrue(
            "the injected pane must keep MORE than 3 live lines (the >3-line half-black band the " +
                "pre-#1153 heal skipped), found ${frameRowCount(afterInject)}",
            frameRowCount(afterInject) > 3,
        )
        assertFalse(
            "precondition (#1153 RED gap): the pane is NOT the ≤3-line partial-black the pre-#1153 " +
                "send heal covered",
            injected.partiallyBlank,
        )
        // The cheap send-heal pre-check flags the half-black as worth paying for the authoritative
        // `capture-pane` diff (a >3-line half-black is at/under the 0.5 live-fraction cost-gate).
        assertTrue(
            "precondition: the send-heal pre-check must flag the half-black render as sparse enough " +
                "to pay for the authoritative capture diff",
            injected.looksSparse,
        )
        // LOAD-BEARING precondition (RED): the injected half-black render is a genuine LOST FRAME
        // vs tmux's authoritative capture — tmux holds MATERIALLY MORE than the render shows. This
        // is the exact predicate the production send heal keys off, so it goes TRUE here (lost) and
        // must go FALSE after the heal (matches tmux).
        assertTrue(
            "precondition: the half-black render must be a LOST FRAME vs tmux's authoritative " +
                "capture (tmux holds materially more than the render shows)",
            injected.lostFrameVsCapture,
        )

        // ----- DISCRIMINATOR: the transport is GUARANTEED LIVE (a render bug, not a drop). -----
        assertTrue(
            "the transport must stay Connected with a half-black render, observed=${injected.connectionStatus}",
            injected.connectionStatus is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("the tmux client must NOT be disconnected", injected.clientDisconnected)
        assertNoVisibleReconnect("half-black (no reconnect surface)")

        // ----- GREEN: the SEND heal must restore the FULL frame (the upper black band repainted). -----
        val visibleAfter = waitForVisibleTerminal(
            "send-heal full-frame restore",
            timeoutMillis = RESTORE_TIMEOUT_MS,
        ) { it.contains(FRAME_MARKER) && frameRowCount(it) >= MIN_RESTORED_FRAME_ROWS }
        assertTrue(
            "REGRESSION (#1153): the with-attachment send heal must restore the FULL frame (the " +
                "marker + the upper rows that were black). On base the LOCAL-ONLY blank/partial-black " +
                "gate skipped the >3-line half-black and the pane stayed too black. Found rows=" +
                "${frameRowCount(visibleAfter)}.\n$visibleAfter",
            visibleAfter.contains(FRAME_MARKER) && frameRowCount(visibleAfter) >= MIN_RESTORED_FRAME_ROWS,
        )
        // LOAD-BEARING GREEN: the heal's REAL contract — the visible render is repainted to MATCH
        // tmux's authoritative capture, so it is no longer a LOST FRAME (the frame is NOT left
        // partial-black relative to what tmux actually holds). This is the same production predicate
        // the send heal keys off, now FALSE. NOTE: it is deliberately NOT an absolute row-fraction
        // ("looks sparse"): a correctly-restored agent frame can legitimately be a small fraction of
        // a tall CI viewport, which the cheap 0.5 cost-gate would still flag "sparse" — harmless,
        // because the capture diff (this assertion) is the real acceptance.
        assertFalse(
            "REGRESSION (#1153): after the send heal the render must MATCH tmux's authoritative " +
                "capture (no longer a lost-frame / left partial-black relative to what tmux holds). " +
                "On base the LOCAL-ONLY blank/partial-black gate skipped the >3-line half-black and " +
                "the render stayed too black while tmux held the full frame.\n$visibleAfter",
            activePaneLostFrameVsCapture(authoritativeCapture),
        )
        capturePaintedRows("issue1153-02-healed")

        // ----- DISCRIMINATOR: still Connected, no reconnect across the send + heal. -----
        assertNoVisibleReconnect("post-heal (no reconnect surface)")
        assertTrue(
            "session must stay Connected after the send heal (render fix, no reconnect), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        writeSummary(ackAuthority, ackEvent)
    } }

    // ---------------------------------------------------------------- Drive the send

    /**
     * Drive the REAL production send path with a WITH-ATTACHMENT (multi-line) payload. The opt-in
     * fake-agent fixture emits the established collapsed-paste chip, so the #1739 authoritative
     * ack gate must observe the exact current runtime before this returns success and schedules the
     * post-send overpaint heal this journey exercises.
     */
    private suspend fun driveAcknowledgedSendThenInject(
        authority: AckAuthoritySnapshot,
        halfBlack: String,
        authoritativeCapture: String,
    ): AcknowledgedSendAndInjection {
        var sendVm: TmuxSessionViewModel? = null
        var sendPaneId: String? = null
        var sendView: TerminalView? = null
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            val paneId = vm.panes.value.firstOrNull()?.paneId ?: return@onActivity
            check(paneId == authority.paneId) {
                "active pane changed before send: expected=${authority.paneId} actual=$paneId"
            }
            vm.setAgentSubmitEnterDelayForTest(0)
            vm.setAgentSubmitAckTimeoutForTest(ACK_TIMEOUT_MS)
            sendVm = vm
            sendPaneId = paneId
            sendView = checkNotNull(activity.window.decorView.findTerminalView()) {
                "expected the live TerminalView before send"
            }
        }
        val vm = checkNotNull(sendVm) { "expected a live ViewModel before send" }
        val paneId = checkNotNull(sendPaneId) { "expected an active pane before send" }
        val view = checkNotNull(sendView) { "expected a retained TerminalView before send" }

        // The production acknowledgement capture is owned by bridgeScope (Android Main). Invoke
        // the suspending send from the instrumentation coroutine so Main remains free to complete
        // that capture; runBlocking inside onActivity would deterministically starve the exact ack.
        val result = vm.sendAgentPayloadToPaneResult(
            paneId,
            WITH_ATTACHMENT_PAYLOAD,
            AgentKind.Codex,
        )
        assertTrue(
            "the with-attachment send itself must succeed through an observed acknowledgement; " +
                "failure=${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val postSendCapture = captureRemoteTmuxPane()
        assertTrue(
            "the opt-in fake agent must retain its one initial full frame after send; a later " +
                "fixture-owned full redraw could mask the production Send heal.\n$postSendCapture",
            postSendCapture.contains(INITIAL_FULL_RENDER_SENTINEL),
        )

        var outcome: AcknowledgedSendAndInjection? = null
        compose.activityRule.scenario.onActivity { activity ->
            val currentVm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            check(currentVm === vm) {
                "activity/ViewModel changed between exact acknowledgement and injection"
            }
            val ack = assertExactlyCurrentAckObserved(authority, vm)
            // Back on Android Main immediately after the send returns: the Send reconcile has a
            // delayed first tick on this dispatcher, so the synthetic overpaint lands only AFTER
            // success + exact-current ack and BEFORE that tick. No fixed sleep participates.
            val injected = injectHalfBlackAndSnapshot(
                activity = activity,
                vm = vm,
                view = view,
                frame = halfBlack,
                authoritativeCapture = authoritativeCapture,
            )
            outcome = AcknowledgedSendAndInjection(ack = ack, halfBlack = injected)
        }
        val captured = checkNotNull(outcome) {
            "expected to send, acknowledge, and inject on the live activity"
        }
        writeBitmap("issue1153-01-half-black-viewport", captured.halfBlack.bitmap)
        captured.halfBlack.bitmap.recycle()
        writeText("issue1153-01-half-black-visible-terminal.txt", captured.halfBlack.visibleText)
        return captured
    }

    private data class AcknowledgedSendAndInjection(
        val ack: RecordedDiagnosticEvent,
        val halfBlack: HalfBlackSnapshot,
    )

    private data class AckAuthoritySnapshot(
        val paneRow: com.pocketshell.app.tmux.TmuxPaneState,
        val paneId: String,
        val paneSessionId: String,
        val targetSessionName: String,
        val clientHash: Int,
        val generation: Long,
    )

    private fun snapshotAckAuthority(): AckAuthoritySnapshot {
        var snapshot: AckAuthoritySnapshot? = null
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            snapshot = snapshotAckAuthority(vm)
        }
        return checkNotNull(snapshot).also {
            assertEquals(
                "fixture sanity: the current target must be the seeded render-heal session",
                SESSION_NAME,
                it.targetSessionName,
            )
        }
    }

    private fun snapshotAckAuthority(vm: TmuxSessionViewModel): AckAuthoritySnapshot {
        val pane = checkNotNull(vm.panes.value.firstOrNull()) {
            "expected the acknowledged send to have a current pane row"
        }
        val runtime = checkNotNull(vm.currentRuntimeGuardForTest()) {
            "expected the acknowledged send to have a current live runtime"
        }
        return AckAuthoritySnapshot(
            paneRow = pane,
            paneId = pane.paneId,
            paneSessionId = pane.sessionId,
            targetSessionName = runtime.target.sessionName,
            clientHash = System.identityHashCode(runtime.client),
            generation = runtime.generation,
        )
    }

    private fun assertExactlyCurrentAckObserved(
        before: AckAuthoritySnapshot,
        vm: TmuxSessionViewModel,
    ): RecordedDiagnosticEvent {
        val acks = diagnostics!!.eventsNamed("agent_submit_ack")
        assertEquals(
            "the with-attachment send must emit exactly one authoritative agent_submit_ack; " +
                "events=$acks",
            1,
            acks.size,
        )
        val ack = acks.single()
        assertEquals(
            "the one ack must be observed, never timeout/fallback",
            "ack_observed",
            ack.fields["result"],
        )
        assertEquals("the ack must belong to the snapshotted pane row", before.paneId, ack.fields["pane"])
        assertEquals(
            "the ack must belong to the snapshotted target session",
            before.targetSessionName,
            ack.fields["session"],
        )
        assertEquals("the ack must use the snapshotted client", before.clientHash, ack.fields["clientHash"])
        assertEquals(
            "the ack current client must still be the snapshotted client",
            before.clientHash,
            ack.fields["currentClientHash"],
        )
        assertEquals("the ack must use the snapshotted generation", before.generation, ack.fields["generation"])
        assertEquals(
            "the ack current generation must still be the snapshotted generation",
            before.generation,
            ack.fields["currentGeneration"],
        )

        val after = snapshotAckAuthority(vm)
        assertSame(
            "the acknowledged send must leave the exact active pane row authoritative",
            before.paneRow,
            after.paneRow,
        )
        assertEquals("the acknowledged send must retain the active pane id", before.paneId, after.paneId)
        assertEquals(
            "the acknowledged send must retain the active pane's tmux session id",
            before.paneSessionId,
            after.paneSessionId,
        )
        assertEquals(
            "the acknowledged send must retain the target session",
            before.targetSessionName,
            after.targetSessionName,
        )
        assertEquals("the acknowledged send must retain the live client", before.clientHash, after.clientHash)
        assertEquals(
            "the acknowledged send must retain the connect generation",
            before.generation,
            after.generation,
        )
        return ack
    }

    private suspend fun pauseAndProveStaleRenderWatchdogQuiescent() {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        val currentVm = checkNotNull(vm) { "expected a live ViewModel before pausing watchdog" }
        currentVm.pauseActivePaneStaleRenderWatchdogForTest()
        val currentJob = currentVm.staleRenderWatchdogJobForTest()
        assertFalse(
            "the attach-owned stale-render watchdog must be inactive before the send mutation window",
            currentJob?.isActive == true,
        )
        assertTrue(
            "the attach-owned stale-render watchdog must be fully joined before the send mutation window",
            currentJob == null || currentJob.isCompleted,
        )
        writeText(
            "issue1769-watchdog-quiescence.txt",
            buildString {
                appendLine("issue=1769")
                appendLine("seam=pauseActivePaneStaleRenderWatchdogForTest")
                appendLine("future_auto_arm=false")
                appendLine("current_job_present=${currentJob != null}")
                appendLine("current_job_active=${currentJob?.isActive ?: false}")
                appendLine("current_job_completed=${currentJob?.isCompleted ?: true}")
                appendLine("current_job_cancelled=${currentJob?.isCancelled ?: false}")
                appendLine("automatic_watchdog_quiescent=true")
                appendLine("sole_heal_owner=ReconcileReason.Send")
            },
        )
    }

    /**
     * The heal's REAL contract, keyed off the SAME production predicate the send heal uses
     * ([TerminalSurfaceState.visibleRenderLostFrameVsCapture]): is the active pane's visible render a
     * LOST FRAME relative to tmux's authoritative `capture-pane` — tmux holds materially MORE than
     * the render shows? TRUE = the render is left partial-black vs what tmux actually has; FALSE =
     * the render matches tmux's grid (nothing lost). Viewport-independent — the acceptance the
     * geometrically-fixed 0.5 row-fraction "looks sparse" check cannot express.
     */
    private fun activePaneLostFrameVsCapture(capture: String): Boolean {
        var hit = false
        compose.activityRule.scenario.onActivity { activity ->
            hit = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes.value.firstOrNull()
                ?.terminalState
                ?.visibleRenderLostFrameVsCapture(capture) ?: false
        }
        return hit
    }

    // ---------------------------------------------------------------- Emulator feed

    /**
     * Append the synthetic overpaint and snapshot every load-bearing precondition before releasing
     * Android Main. The Send reconcile also runs on Main and begins after a 350 ms settle delay;
     * taking one atomic snapshot prevents a contended emulator from letting that legitimate heal
     * run between six separate test-thread reads and erasing the RED precondition evidence.
     */
    private fun injectHalfBlackAndSnapshot(
        activity: MainActivity,
        vm: TmuxSessionViewModel,
        view: TerminalView,
        frame: String,
        authoritativeCapture: String,
    ): HalfBlackSnapshot {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        val pane = checkNotNull(vm.panes.value.firstOrNull()) {
            "expected a current pane for the half-black injection"
        }
        check(view.isAttachedToWindow && view.rootView === activity.window.decorView.rootView) {
            "expected the pre-send TerminalView to remain attached through exact acknowledgement"
        }
        val emulator = checkNotNull(view.mEmulator) {
            "expected the retained live emulator for the half-black injection"
        }
        val beforeAppend = emulator.screen.visibleScreenText
        emulator.append(bytes, bytes.size)
        view.invalidate()
        val afterAppend = emulator.screen.visibleScreenText
        check(afterAppend.contains(HALF_BLACK_MARKER)) {
            "half-black bytes did not land in the retained emulator: " +
                "rows=${emulator.mRows} columns=${emulator.mColumns} " +
                "before=${beforeAppend.take(200)} after=${afterAppend.take(200)}"
        }

        check(view.width > 0 && view.height > 0) {
            "expected non-zero TerminalView bounds for the half-black artifact"
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
        return HalfBlackSnapshot(
            visibleText = afterAppend,
            partiallyBlank = pane.terminalState.visibleScreenIsPartiallyBlank(),
            looksSparse = pane.terminalState.renderLooksSuspect(),
            lostFrameVsCapture =
                pane.terminalState.visibleRenderLostFrameVsCapture(authoritativeCapture),
            connectionStatus = vm.connectionStatus.value,
            clientDisconnected = vm.clientDisconnectedForTest(),
            bitmap = bitmap,
        )
    }

    private data class HalfBlackSnapshot(
        val visibleText: String,
        val partiallyBlank: Boolean,
        val looksSparse: Boolean,
        val lostFrameVsCapture: Boolean,
        val connectionStatus: TmuxSessionViewModel.ConnectionStatus,
        val clientDisconnected: Boolean,
        val bitmap: Bitmap,
    )

    // ---------------------------------------------------------------- Render capture

    private fun capturePaintedRows(name: String): Int {
        val bitmap = renderViewportBitmap(name)
        writeBitmap("$name-viewport", bitmap)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        val rows = paintedRowCount(bitmap)
        bitmap.recycle()
        return rows
    }

    private fun renderViewportBitmap(label: String): Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView(),
                label,
            )
        }
        return checkNotNull(bitmap) {
            "activity was not available to capture viewport '$label' (#2135)"
        }
    }

    private fun paintedRowCount(bitmap: Bitmap): Int {
        var painted = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            var rowPainted = false
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(p) > 40 ||
                    android.graphics.Color.green(p) > 40 ||
                    android.graphics.Color.blue(p) > 40
                ) {
                    rowPainted = true
                    break
                }
                x += 4
            }
            if (rowPainted) painted++
            y += 4
        }
        return painted
    }

    private fun frameRowCount(text: String): Int =
        text.split('\n').count { it.isNotBlank() }

    // ---------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
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

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus.value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()?.currentSession?.emulator?.screen?.visibleScreenText.orEmpty()
        }
        return text
    }

    private fun assertNoVisibleReconnect(label: String) {
        org.junit.Assert.assertEquals(
            "expected no disconnect band for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        org.junit.Assert.assertEquals(
            "expected no Tap Reconnect button for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Reconnecting", "Disconnected", "Tap Reconnect").forEach { text ->
            org.junit.Assert.assertEquals(
                "expected no visible '$text' text for $label", 0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    // ---------------------------------------------------------------- Fixture

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

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
                name = "issue1153-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1153 Send-With-Attachment Half Black",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    trustedHostKeySha256 = trustedHostKeySha256,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * Seed the real acknowledgement-capable fake-agent in its opt-in #1769 incremental mode.
     * Its initial render paints ~20 marker rows (dense enough that tmux's authoritative
     * `capture-pane` holds MATERIALLY MORE than the injected half-black band), while later
     * paste/submit renders touch only the lower input rows and can never self-heal the upper band.
     */
    private suspend fun seedFullFrameSession(key: String) {
        val payload =
            "POCKETSHELL_FAKE_AGENT_RENDER_MODE=$FAKE_AGENT_RENDER_MODE " +
                "exec /usr/local/bin/pocketshell-fake-agent"
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote(payload),
            )
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded full-frame session: ${exec?.stdout?.trim()}")
    }

    /**
     * Fetch tmux's AUTHORITATIVE visible-pane content via `capture-pane -p` (plain text, NO `-e`
     * escapes — so its non-whitespace glyph count is comparable to the render's, the way
     * [TerminalSurfaceState.visibleRenderLostFrameVsCapture] measures both sides). This is the
     * ground-truth frame the send heal must repaint the local render back to.
     */
    private suspend fun captureRemoteTmuxPane(): String {
        val script = "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}"
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected `capture-pane` to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        return exec?.stdout.orEmpty()
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true") }
            }
        }
    }

    // ---------------------------------------------------------------- Artifacts

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1153_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1153_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeAckAuthority(
        authority: AckAuthoritySnapshot,
        ack: RecordedDiagnosticEvent,
    ): File =
        writeText(
            "issue1769-ack-authority.txt",
            buildString {
                appendLine("issue=1769")
                appendLine("pane=${authority.paneId}")
                appendLine("pane_session_id=${authority.paneSessionId}")
                appendLine("target_session=${authority.targetSessionName}")
                appendLine("client_hash=${authority.clientHash}")
                appendLine("generation=${authority.generation}")
                appendLine("ack_name=${ack.name}")
                appendLine("ack_fields=${ack.fields}")
                appendLine("ack_exact_current_runtime=true")
            },
        )

    private fun writeSummary(
        authority: AckAuthoritySnapshot,
        ack: RecordedDiagnosticEvent,
    ): File =
        writeText(
            "issue1153-summary.txt",
            buildString {
                appendLine("test=SendWithAttachmentStaysVisibleE2eTest")
                appendLine("issue=1153")
                appendLine("fixture_correction_issue=1769")
                appendLine(
                    "fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT), " +
                        "ack-capable incremental fake-agent",
                )
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("frame_marker=$FRAME_MARKER")
                appendLine("ack_pane=${authority.paneId}")
                appendLine("ack_pane_session_id=${authority.paneSessionId}")
                appendLine("ack_target_session=${authority.targetSessionName}")
                appendLine("ack_client_hash=${authority.clientHash}")
                appendLine("ack_generation=${authority.generation}")
                appendLine("ack_result=${ack.fields["result"]}")
                appendLine("ack_exact_current_runtime=true")
                appendLine(
                    "watchdog=paused+joined before mutation " +
                        "(only the SEND heal can green the pane)",
                )
                appendLine(
                    "scenario=attach a full-screen agent frame, drive a REAL acknowledged " +
                        "with-attachment (multi-line) send, inject a >3-line HALF-BLACK overpaint " +
                        "on the LIVE emulator before the Send settle ticks, assert " +
                        "the SEND heal restores the full frame with the transport still Connected",
                )
                appendLine(
                    "expectation=transport ALIVE (Connected, no reconnect surface) AND render HEALS " +
                        "on the SEND path (full frame restored from tmux's authoritative capture)",
                )
            },
        )

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact directory ${dir.absolutePath}" }
        return File(dir, name)
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue1153SendHalfBlack"
        const val DEVICE_DIR_NAME: String = "issue1153-send-with-attachment-half-black"
        const val SESSION_NAME: String = "issue1153-send-halfblack"
        const val FRAME_MARKER: String = "ISSUE1153-FRAME"
        const val INITIAL_FULL_RENDER_SENTINEL: String = "ISSUE1153-FRAME row 1 render 1"
        const val HALF_BLACK_MARKER: String = "ISSUE1153-HALFBLACK"
        const val FAKE_AGENT_RENDER_MODE: String = "issue1153-incremental"

        // NOTE: DEFAULT_HOST / DEFAULT_PORT / DEFAULT_USER are the pool-aware package-level vals
        // from AndroidSshTestFixtures.kt (backed by the `agentsPort` instrumentation arg), NOT
        // hardcoded here — so this journey runs correctly under both single-lane (2222) and
        // `--pool` (self-allocated fixture port) modes.

        // >3 live lines (so NOT the ≤3-line partial-black) yet a small fraction of any real phone
        // viewport (≥ ~24 rows) — the >3-line half-black band the pre-#1153 heal missed.
        const val HALF_BLACK_LIVE_LINES: Int = 6

        // Rows between successive live lines (a blank row between each) so visible-screen text
        // cannot fold adjacent short lines into one logical line, and the gaps form the band.
        const val HALF_BLACK_ROW_STRIDE: Int = 2

        const val MIN_RESTORED_FRAME_ROWS: Int = 10

        // A with-attachment composer payload: the draft plus the "Attached files:" block the
        // composer appends, which makes every with-attachment send multi-line (bracketed paste).
        const val WITH_ATTACHMENT_PAYLOAD: String =
            "please review this\n\nAttached files:\n- /home/testuser/report.txt"

        val ACK_TIMEOUT_MS: Long = 800L
        val RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 20_000L else 12_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
