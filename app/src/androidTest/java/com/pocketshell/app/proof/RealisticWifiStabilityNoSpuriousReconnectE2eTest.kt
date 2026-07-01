package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KeepAliveTestOverride
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #970 — the realistic-wifi STABILITY regression gate (the durable proof
 * for #964, D31/D32/D33). The "reconnects on good wifi" symptom kept slipping
 * through because every existing harness either does a HARD cut (`addBlackhole`)
 * or a 2s synthetic handle-flip, or it BYPASSES the app's `ConnectionController`
 * / `LivenessProbe` / keepalive (the toxiproxy latency proof,
 * [NetworkLatencyModelE2eTest], opens a DIRECT tmux connection + self-skips on
 * CI). NONE reproduces a STABLE-but-jittery wifi link on the REAL app path.
 *
 * This class fills that gap with a FULL-APP-PATH proof: it drives the real
 * `MainActivity` → host-card → session attach → `tmux -CC` → `LivenessProbe` +
 * `TransportKeepAlive`, holds a STABLE-but-momentarily-slow link, and asserts the
 * app does NOT spuriously reconnect — **ZERO `liveness_probe_silent_drop` /
 * `reconnect_start` cause events, ZERO `Reconnecting`/`Disconnected` transitions,
 * ZERO extra connect attempts, and the viewport stays painted.**
 *
 * --------------------------------------------------------------------------
 * THE #964 BUG IT REPRODUCES (red on base, green once #964 lands):
 *
 * v0.4.17 runs TWO foreground liveness mechanisms on MISMATCHED budgets:
 *   - the app-level `LivenessProbe` (#927) declares the tmux `-CC` channel dead
 *     at ~48s (`failureThreshold × (interval + perProbeTimeout)`), and
 *   - the always-on transport keepalive (#945, `TransportKeepAlive`) is designed
 *     to RIDE THROUGH ~90s before giving up.
 * On a live-but-slow/congested link the probe declares dead and FORCE-REDIALS at
 * ~48s — BEFORE the keepalive's ~90s ride-through can prove the link is still
 * alive — so the keepalive's whole "absorb the blip" purpose is undercut and the
 * user sees a spurious reconnect on a fine link. The #964 fix coordinates them:
 * the probe DEFERS to the keepalive's liveness signal while the transport is
 * provably alive, so a slow-but-live `-CC` blip is ridden through instead of
 * redialed.
 *
 * --------------------------------------------------------------------------
 * TWO METHODS (parity with the silent-drop pair):
 *
 *  1. [stableSlowControlChannelOnLiveTransportNeverRedials] — the DETERMINISTIC,
 *     per-push CI variant. Runs on the plain deterministic `agents:2222` fixture
 *     (NO toxiproxy, NO new Docker service) and does NOT self-skip on CI, so it is
 *     wired into `scripts/ci-journey-suite.sh` (G9). It reproduces the #964
 *     budget mismatch DETERMINISTICALLY using ONLY base-available seams (so it
 *     compiles + goes RED on rc/0.4.18 WITHOUT #964 and GREEN once #964 lands):
 *       - the `-CC` probe is made to report DEAD for a BOUNDED window via the
 *         existing `forceLivenessProbeDeadForTest` seam — modelling a momentarily
 *         SLOW/congested control channel (NOT a permanent wedge), while
 *       - the underlying `agents:2222` SSH transport stays GENUINELY LIVE, so the
 *         real transport keepalive keeps proving the link alive (the keepalive's
 *         inbound-activity timestamp is fresh from connect and refreshed by the
 *         shortened-interval keepalive — [KeepAliveTestOverride]).
 *     The probe budget is shortened via [LivenessProbeTestOverride] so detection
 *     lands in seconds, NOT the production ~48s — the analogue of
 *     `BackgroundGraceTestOverride`, NOT a weakening of the assertion (the
 *     N-consecutive-failure criterion and the keepalive-coordination logic both
 *     run unchanged). The dead window is held just past ONE shortened probe budget
 *     (so the threshold is reached) then CLEARED, so the next probe answers — i.e.
 *     a slow link that un-congests, the exact #964 case, NOT the #822 permanently
 *     wedged `-CC` channel that the #964 fix DELIBERATELY still escalates.
 *
 *     On BASE (rc/0.4.18, no #964) the probe reaching its threshold force-redials
 *     immediately (records `liveness_probe_silent_drop`, bumps
 *     [TMUX_CONNECT_ATTEMPTS], raises the `Reconnecting` band) even though the
 *     keepalive is still proving the transport alive → the ZERO-reconnect
 *     assertions FAIL → **RED**. With #964 the probe DEFERS to the still-healthy
 *     keepalive and never redials → **GREEN**.
 *
 *  2. [realisticJitteryWifiOnRealLinkNeverRedials] — the SYNTHETIC-JITTER,
 *     PER-PUSH CI variant (issue #1081). It USED to be an opt-in toxiproxy proof
 *     gated by `assumeNetworkFaultProofsEnabled()`, which SELF-SKIPPED on CI (the
 *     `tests.yml` per-push job does not start the toxiproxy proxy family). That
 *     self-skip made the headline "jittery real Wi-Fi never spuriously redials"
 *     assertion provide ZERO protection on CI while appearing covered — the exact
 *     #657/#780 self-skip anti-pattern (F3: no `assumeFalse(isRunningOnCi())` on a
 *     load-bearing assertion). Per #1081 it is now rebuilt on the #780
 *     SYNTHETIC-INJECTION model: it runs on the plain deterministic `agents:2222`
 *     fixture (NO toxiproxy, NO new Docker service, NO self-skip), so it is a
 *     genuine per-push CI gate that HARD-asserts on CI.
 *
 *     Jitter is dispatched DETERMINISTICALLY: a physically-stable-but-jittery wifi
 *     link is modelled as REPEATED `-CC` probe-failure BURSTS (via the existing
 *     `forceLivenessProbeDeadForTest` seam) interleaved with healthy gaps, WHILE
 *     the transport keepalive is pinned PROVEN-ALIVE (`forceTransportProvenAliveForTest
 *     = true` — the #964 slow-but-live signal, and the pin the #1103 whole-link-death
 *     seam requires so a `-CC`-only wedge on a live transport is NOT misread as a
 *     transport death). Most bursts are SUB-budget (fewer than [failureThreshold]
 *     consecutive misses — the N-consecutive reset guard absorbs them); one burst
 *     spans MORE THAN TWO shortened probe budgets (it reaches the threshold — the
 *     #964/#982 keepalive deferral absorbs it with no time ceiling). Either way the
 *     app must record ZERO reconnect. It asserts the SAME ZERO-reconnect invariant
 *     as the deterministic sibling and NEVER self-skips, so the jittery-link
 *     property is protected on every push. The faithful-real-wire toxiproxy variant
 *     is deliberately NOT carried alongside (D22 hard-cut): wiring the network-fault
 *     fixture into the per-push gate is a non-goal (#1081) because it makes the gate
 *     heavier/slower, and the deterministic decision logic (defer vs declare,
 *     N-consecutive reset) is proven RED→GREEN by the pure-JVM `LivenessProbeTest`.
 *
 * Assertions are USER-VISIBLE (D28(3)/D33): the cause-trail / diagnostics that
 * record a reconnect, the rendered connection-status indicators, the connect
 * attempt counter, and the painted terminal viewport — never internal/shadow
 * state. NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on EITHER method's
 * load-bearing assertions (D31/F3/G4).
 */
@RunWith(AndroidJUnit4::class)
class RealisticWifiStabilityNoSpuriousReconnectE2eTest : NetworkFaultProofBase() {

    private var diagnostics: RecordingDiagnosticSink? = null

    @Before
    fun installDiagnostics() {
        clearLastSessionPrefs()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        // Shorten BOTH liveness budgets deterministically. This is the analogue of
        // BackgroundGraceTestOverride — it does NOT weaken any assertion: the
        // N-consecutive-failure probe criterion and the keepalive-coordination
        // logic both run unchanged on the shortened clock, and the keepalive's
        // inbound-activity timestamp stays fresh so the #964 deferral guard has a
        // live signal to defer to. Production keeps the 7s/5s/4 + 30s/3 defaults.
        LivenessProbeTestOverride.setForTest(
            intervalMs = PROBE_INTERVAL_MS,
            perProbeTimeoutMs = PROBE_TIMEOUT_MS,
            failureThreshold = PROBE_FAILURE_THRESHOLD,
        )
        KeepAliveTestOverride.setForTest(
            intervalMs = KEEPALIVE_INTERVAL_MS,
            countMax = KEEPALIVE_COUNT_MAX,
        )
    }

    @After
    fun clearOverrides() {
        LivenessProbeTestOverride.clear()
        KeepAliveTestOverride.clear()
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
    }

    /**
     * THE DETERMINISTIC, PER-PUSH CI GATE (no toxiproxy, no self-skip).
     *
     * A STABLE link whose tmux `-CC` control channel is momentarily SLOW (the
     * probe fails) WHILE the SSH transport stays provably alive (the keepalive
     * rides through) must NOT spuriously reconnect. RED on rc/0.4.18 (the probe
     * redials at its budget regardless of the keepalive — #964); GREEN once #964
     * coordinates the probe to defer to the keepalive.
     */
    @Test
    fun stableSlowControlChannelOnLiveTransportNeverRedials() { runBlocking<Unit> {
        val key = readFixtureKey()
        val marker = "rw${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue970-stable-$marker"
        val hostName = "Issue970 StableWifi $marker"

        // DETERMINISTIC fixture: the plain agents:2222 link, GENUINELY LIVE — NO
        // toxiproxy. We seed the session + host directly on DEFAULT_PORT.
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedExtraSession(key, sessionName, "ISSUE970-STABLE-READY-$marker")
        val hostRowTag = seedNetworkFaultHost(key, hostName, port = DEFAULT_PORT)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("stable_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Establish a live baseline, then send NOTHING (the user is reading /
        // recording a voice note — the #822/#964 idle scenario). The attach itself
        // is the ONE expected connect attempt.
        sendCommandThroughTerminalInput("printf 'LIVE-$marker\\n'", "pre-stall-live")
        waitForVisibleTerminalText("pre-stall-live") { "LIVE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")
        waitForConnected("initial attach")
        captureViewport("issue970-01-attached")
        diagnostics!!.clear()
        val attemptsAfterAttach = TMUX_CONNECT_ATTEMPTS.get()

        // ---- THE #982/#984 SLOW-BUT-LIVE WINDOW (spans >2 probe budgets) ----
        // Arm the slow `-CC` channel: the probe's refresh-client ping reports DEAD
        // (a momentarily congested control channel), while the agents:2222 SSH
        // TRANSPORT stays genuinely live, so the always-on keepalive keeps proving
        // the link alive the WHOLE time (ride-through window 60s ≫ the 12s hold).
        // Hold it across MORE THAN TWO shortened probe budgets — the gap the deleted
        // maxKeepAliveDeferrals=1 time ceiling could not cross without escalating
        // (#982/#984). The new contract DEFERS UNCONDITIONALLY while the keepalive
        // proves alive → ZERO reconnect. Then CLEAR it so the next probe answers —
        // a slow link that un-congests, NOT a permanently wedged channel (#822,
        // which the new contract still escalates via the keepalive death signal or
        // the 180s absolute backstop).
        val stallStart = SystemClock.elapsedRealtime()
        // Issue #1103 fix: EXPLICITLY pin the keepalive PROVEN-ALIVE while the `-CC`
        // dead-seam is armed. #1103 changed isTransportKeepAliveProvenAliveRecently()
        // to report the transport DEAD whenever `forceLivenessProbeDeadForTest` is
        // armed with NO explicit pin (a WHOLE-link death, for the #822 silent-drop
        // proof) — which would make this slow-but-LIVE scenario declare a drop and
        // redial (a false RED). The #1103 comment itself prescribes this: "the #964
        // slow-but-live phase deliberately sets it true WHILE the `-CC` dead-seam is
        // armed." An EXPLICIT pin always wins, so the probe defers as this test
        // intends. Cleared below so the recovered channel reads the real keepalive.
        currentViewModel().forceTransportProvenAliveForTest = true
        currentViewModel().forceLivenessProbeDeadForTest = true
        recordTiming("stable_slow_cc_armed_at_ms", stallStart - attachStart)

        // Watch for ANY spurious reconnect across the whole slow window: the probe
        // must DEFER to the keepalive, never redial. (On base it redials the
        // instant the threshold is reached, ~PROBE_RAW_BUDGET_MS in.)
        watchNoSpuriousReconnect("during-slow-cc", SLOW_CC_HOLD_MS)

        currentViewModel().forceLivenessProbeDeadForTest = false
        currentViewModel().forceTransportProvenAliveForTest = null
        recordTiming("stable_slow_cc_cleared_at_ms", SystemClock.elapsedRealtime() - attachStart)

        // Let a couple of real probes run after the channel recovers — they must
        // answer (the transport was never down), the deferral run resets, and STILL
        // no reconnect fires.
        watchNoSpuriousReconnect("after-slow-cc-recovered", POST_RECOVER_WATCH_MS)

        // ---- LOAD-BEARING ASSERTIONS (ZERO reconnect, viewport painted) ----
        // Diagnostics (the redial cause events) + the connect-attempt counter are
        // the always-available proofs of ZERO reconnect; assert them first.
        assertNoReconnectCauseEvents("stable slow-cc on live transport")
        assertNoExtraConnectAttempts(
            attemptsAfterAttach,
            expectedDelta = 0,
            label = "no redial across the slow-but-live -CC window",
        )
        // Settle on a present, Connected hierarchy, then assert the rendered
        // connection-status surface never shows a Reconnecting transition.
        waitForConnected("after slow-cc window")
        waitForVisibleTerminalText("viewport stays painted") { "LIVE-$marker" in it }
        assertNoReconnectingTransition("stable slow-cc on live transport")
        captureViewport("issue970-02-after-slow-cc")

        // The SAME session is still live + input-accepting (no switch dance needed).
        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "post-stable")
        waitForVisibleTerminalText("post-stable round-trip") { "AFTER-$marker" in it }

        writeSummary(
            testName = "RealisticWifiStabilityNoSpuriousReconnect-deterministic",
            lines = listOf(
                "session=$sessionName",
                "fixture=agents:$DEFAULT_PORT (deterministic, NO toxiproxy)",
                "scenario=slow -CC channel (probe DEAD) on a genuinely-live transport (keepalive ALIVE)",
                "probe_budget_ms=${PROBE_RAW_BUDGET_MS} (interval $PROBE_INTERVAL_MS x threshold " +
                    "$PROBE_FAILURE_THRESHOLD of $PROBE_TIMEOUT_MS)",
                "keepalive_override=${KEEPALIVE_INTERVAL_MS}ms x $KEEPALIVE_COUNT_MAX",
                "slow_cc_hold_ms=$SLOW_CC_HOLD_MS",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsAfterAttach}",
                "liveness_probe_silent_drop=${diagnostics!!.eventsNamed("liveness_probe_silent_drop").size}",
                "reconnect_start=${diagnostics!!.eventsNamed("reconnect_start").size}",
                "expectation=ZERO reconnect on a slow-but-live link (#964)",
                "expected_base=RED (probe redials at its budget regardless of keepalive)",
            ),
        )
    } }

    /**
     * THE SYNTHETIC-JITTER, PER-PUSH CI VARIANT (issue #1081 — no toxiproxy, no
     * self-skip, the #780 model).
     *
     * A physically-stable-but-jittery wifi link (repeated `-CC` probe-failure
     * bursts on a genuinely-live, keepalive-proven transport) must NOT spuriously
     * reconnect. Modelled synthetically on the deterministic `agents:2222` fixture
     * so it HARD-asserts on every push instead of self-skipping. RED on rc/0.4.18
     * (WITHOUT #964 the over-budget burst force-redials the FINE link at its budget
     * regardless of the keepalive); GREEN once #964 coordinates the probe to defer
     * to the still-healthy keepalive AND the N-consecutive reset guard absorbs the
     * sub-budget bursts. The deterministic decision logic (defer vs declare,
     * N-consecutive reset) is proven RED→GREEN by the pure-JVM `LivenessProbeTest`.
     */
    @Test
    fun realisticJitteryWifiOnRealLinkNeverRedials() { runBlocking<Unit> {
        val key = readFixtureKey()
        val marker = "jw${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue970-jitter-$marker"
        val hostName = "Issue970 JitterWifi $marker"

        // DETERMINISTIC fixture: the plain agents:2222 link, GENUINELY LIVE — NO
        // toxiproxy. We seed the session + host directly on DEFAULT_PORT, exactly
        // like the deterministic sibling above.
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedExtraSession(key, sessionName, "ISSUE970-JITTER-READY-$marker")
        val hostRowTag = seedNetworkFaultHost(key, hostName, port = DEFAULT_PORT)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("jitter_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        sendCommandThroughTerminalInput("printf 'LIVE-$marker\\n'", "pre-jitter-live")
        waitForVisibleTerminalText("pre-jitter-live") { "LIVE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")
        waitForConnected("initial attach")
        diagnostics!!.clear()
        val attemptsAfterAttach = TMUX_CONNECT_ATTEMPTS.get()

        // Pin the transport keepalive PROVEN ALIVE for the WHOLE jittery hold: the
        // wifi RTT wobbles but the link is never actually down, so the always-on
        // keepalive keeps seeing inbound transport activity. This is the #964
        // slow-but-live signal the probe defers to — and the EXPLICIT pin the
        // #1103 whole-link-death seam requires (arming only the `-CC` dead-seam
        // reports the transport DEAD, which would model a WHOLE-link drop, not the
        // jittery-but-live link under test). An EXPLICIT pin always wins in
        // isTransportKeepAliveProvenAliveRecently().
        currentViewModel().forceTransportProvenAliveForTest = true
        try {
            // SYNTHETIC JITTER (the #780 model — dispatch the fault deterministically,
            // no toxiproxy, no self-skip): model a physically-stable-but-jittery wifi
            // link as REPEATED `-CC` probe-failure BURSTS interleaved with healthy
            // gaps. Each burst arms the probe DEAD (a momentarily congested control
            // channel), then CLEARS it (the RTT recovers). Most bursts are SUB-budget
            // (fewer than PROBE_FAILURE_THRESHOLD consecutive misses — the
            // N-consecutive reset guard absorbs them, resetting on the next healthy
            // probe); the MIDDLE burst spans MORE THAN TWO shortened probe budgets, so
            // it reaches the threshold WHILE the keepalive proves the transport alive
            // (the #964/#982 unconditional deferral absorbs it). Either way ZERO redial
            // across every burst AND every gap.
            for (round in 0 until JITTER_ROUNDS) {
                val crossesBudget = round == JITTER_ROUNDS / 2
                val deadBurstMs = if (crossesBudget) OVER_BUDGET_BURST_MS else SUB_BUDGET_BURST_MS
                // Arm the momentarily-congested `-CC` channel.
                currentViewModel().forceLivenessProbeDeadForTest = true
                recordTiming("jitter_round_${round}_dead_burst_ms", deadBurstMs)
                // The probe must NEVER redial during the burst: a sub-budget burst
                // never reaches the counter; the over-budget burst is deferred to the
                // proven-alive keepalive. (On base the over-budget burst redials the
                // instant the shortened threshold is reached.)
                watchNoSpuriousReconnect("jitter_round_${round}_dead", deadBurstMs)
                // The RTT recovers: real probes answer, the failure run resets.
                currentViewModel().forceLivenessProbeDeadForTest = false
                watchNoSpuriousReconnect("jitter_round_${round}_live", JITTER_LIVE_GAP_MS)
            }
        } finally {
            currentViewModel().forceLivenessProbeDeadForTest = false
            currentViewModel().forceTransportProvenAliveForTest = null
            recordTiming("jitter_total_hold_ms", SystemClock.elapsedRealtime() - attachStart)
        }

        // ---- LOAD-BEARING ASSERTIONS (ZERO reconnect, viewport painted) ----
        assertNoReconnectCauseEvents("synthetic jittery wifi")
        assertNoExtraConnectAttempts(
            attemptsAfterAttach,
            expectedDelta = 0,
            label = "no redial across the jittery-but-live hold",
        )
        waitForConnected("after jittery hold")
        waitForVisibleTerminalText("viewport stays painted") { "LIVE-$marker" in it }
        assertNoReconnectingTransition("synthetic jittery wifi")
        captureViewport("issue970-03-after-jitter")

        // The SAME session is still live + input-accepting.
        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "post-jitter")
        waitForVisibleTerminalText("post-jitter round-trip") { "AFTER-$marker" in it }

        writeSummary(
            testName = "RealisticWifiStabilityNoSpuriousReconnect-jitter",
            lines = listOf(
                "session=$sessionName",
                "fixture=agents:$DEFAULT_PORT (deterministic, NO toxiproxy)",
                "scenario=repeated -CC probe-failure bursts (synthetic jitter) on a " +
                    "keepalive-proven-live transport",
                "probe_budget_ms=${PROBE_RAW_BUDGET_MS} (interval $PROBE_INTERVAL_MS x threshold " +
                    "$PROBE_FAILURE_THRESHOLD)",
                "jitter_rounds=$JITTER_ROUNDS",
                "sub_budget_burst_ms=$SUB_BUDGET_BURST_MS (under the $PROBE_RAW_BUDGET_MS budget)",
                "over_budget_burst_ms=$OVER_BUDGET_BURST_MS (spans >2 budgets — keepalive defer)",
                "live_gap_ms=$JITTER_LIVE_GAP_MS",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsAfterAttach}",
                "liveness_probe_silent_drop=${diagnostics!!.eventsNamed("liveness_probe_silent_drop").size}",
                "reconnect_start=${diagnostics!!.eventsNamed("reconnect_start").size}",
                "expectation=ZERO reconnect on a stable jittery link (#964/#982, #1081)",
                "expected_base=RED (over-budget burst redials at its budget regardless of keepalive)",
            ),
        )
    } }

    // -- ZERO-reconnect assertions (the load-bearing ones) ------------------------

    /**
     * ZERO `reconnect` cause events: the probe-declared silent drop AND the
     * reconnect-start are the loud signals the redial path records. A stable
     * client riding through a slow-but-live blip records NONE of them.
     */
    private fun assertNoReconnectCauseEvents(label: String) {
        val forbidden = diagnostics!!.events.filter { event ->
            event.name in FORBIDDEN_RECONNECT_EVENTS
        }
        assertTrue(
            "expected ZERO reconnect cause events for $label, found=$forbidden " +
                "(all=${diagnostics!!.events.map { it.name }})",
            forbidden.isEmpty(),
        )
    }

    /**
     * ZERO `Reconnecting` transitions + ZERO connection-lost surfaces: no
     * Connecting overlay, no Reconnecting/Disconnected status pill, no disconnect
     * band, no Reconnect button, no pull-to-reconnect, no "Attaching…" overlay, and
     * the projected status never left Connected.
     */
    private fun assertNoReconnectingTransition(label: String) {
        assertNoConnectionLostSurface(label)
        assertTrue(
            "expected the projected status to stay Connected for $label, " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun assertNoConnectionLostSurface(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label", 0,
            tagCount(TMUX_CONNECTING_PROGRESS_TAG),
        )
        assertEquals(
            "expected no Reconnecting/Disconnected status pill for $label", 0,
            tagCount(TMUX_CONNECTION_STATUS_PILL_TAG),
        )
        assertEquals(
            "expected no disconnect band for $label", 0,
            tagCount(TMUX_SESSION_ERROR_TAG),
        )
        assertEquals(
            "expected no Tap Reconnect button for $label", 0,
            tagCount(TMUX_SESSION_RECONNECT_TAG),
        )
        assertEquals(
            "expected no pull-to-reconnect affordance for $label", 0,
            tagCount(TMUX_PULL_TO_RECONNECT_TAG),
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label", 0,
            tagCount(TMUX_SWITCHING_LOADING_TAG),
        )
        listOf("Reconnecting", "Disconnected", "Connecting", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label", 0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    /**
     * Continuously assert NO spurious reconnect surfaces across [durationMs] — both
     * the rendered connection-lost indicators AND the diagnostics that record a
     * redial. Sampled tightly so a transient ~1s flap (the maintainer's exact
     * symptom) cannot slip between samples.
     */
    private fun watchNoSpuriousReconnect(label: String, durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // LOAD-BEARING (always queryable, never a compose query): the diagnostics
            // that record a redial, and the VM-projected status that DRIVES every
            // connection-lost indicator. A spurious reconnect ALWAYS records a
            // forbidden cause event AND/OR flips the projected status off Connected,
            // so these two catch it even on a sample where the compose hierarchy is
            // momentarily not queryable.
            assertNoReconnectCauseEvents(label)
            assertProjectedStatusConnected(label)
            // CONFIRMATORY (compose UI): also assert no rendered connection-lost
            // surface, but only when the hierarchy is present — the empty
            // ComposeTestRule can transiently report "No compose hierarchies" during
            // a heavy frame, which is NOT a reconnect (and the two load-bearing
            // checks above already covered this sample).
            if (composeHierarchyPresent()) {
                assertNoConnectionLostSurface(label)
            }
            SystemClock.sleep(100)
        }
    }

    private fun assertProjectedStatusConnected(label: String) {
        val status = currentConnectionStatus()
        assertTrue(
            "expected the projected status to stay Connected for $label (a spurious " +
                "reconnect flips it off Connected), observed=$status",
            status is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun composeHierarchyPresent(): Boolean =
        runCatching {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
            true
        }.getOrDefault(false)

    private fun tagCount(tag: String): Int =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    // -- VM + viewport helpers ----------------------------------------------------

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        launchedActivity?.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForConnected(label: String, timeoutMs: Long = CONNECTED_TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let {
            val file = artifactFile("$name-viewport.png")
            java.io.FileOutputStream(file).use { out ->
                check(it.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE970_VIEWPORT ${file.absolutePath}")
            it.recycle()
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
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

    private companion object {
        // Shortened probe budget for a deterministic, fast per-push run. With the
        // `forceLivenessProbeDeadForTest` seam armed the probe ping short-circuits
        // to DEAD WITHOUT touching the wire, so a failed probe costs only the loop
        // INTERVAL (not interval + per-probe timeout). The effective time to reach
        // the failure threshold is therefore `threshold × interval`:
        //   interval 1.5s × threshold 3 = ~4.5s to one threshold hit.
        // The per-probe timeout (1.5s) only governs a REAL (non-short-circuited)
        // probe, which is what the OPT-IN toxiproxy variant exercises; here it is
        // immaterial to the cadence. The N-consecutive-failure false-positive guard
        // is exercised exhaustively by the pure virtual-clock LivenessProbeTest;
        // threshold 3 keeps a single AVD scheduling hiccup from tripping it.
        const val PROBE_INTERVAL_MS: Long = 1_500L
        const val PROBE_TIMEOUT_MS: Long = 1_500L
        const val PROBE_FAILURE_THRESHOLD: Int = 3
        // Effective time to ONE threshold hit while the dead-seam is armed (the
        // ping short-circuits, so each failure costs one interval): 3 × 1.5s = 4.5s.
        const val PROBE_RAW_BUDGET_MS: Long =
            PROBE_FAILURE_THRESHOLD * PROBE_INTERVAL_MS // 4.5s

        // Shorten the keepalive INTERVAL so a real keepalive reply lands every
        // couple of seconds (keeping the transport's inbound-activity timestamp
        // fresh), but keep countMax HIGH so the derived ride-through WINDOW
        // (intervalMs × countMax = 2s × 30 = 60s, the window the #982/#984 deferral
        // guard `isTransportProvenAliveWithinKeepAliveWindow` checks) stays
        // COMFORTABLY LONGER than the slow-CC hold (12s). This guarantees the
        // keepalive is continuously "proving the transport alive" for the whole
        // slow window even if a couple of replies are momentarily delayed — so the
        // #982/#984 deferral guard always has a fresh live signal to defer to. The
        // window MUST exceed SLOW_CC_HOLD_MS or it would expire mid-hold and the
        // probe would stop deferring — a false RED.
        const val KEEPALIVE_INTERVAL_MS: Long = 2_000L
        const val KEEPALIVE_COUNT_MAX: Int = 30

        // #982/#984: hold the slow `-CC` window so it spans MORE THAN TWO shortened
        // probe budgets — the structural gap the deleted maxKeepAliveDeferrals=1
        // time ceiling could not cross without escalating at ~96s (audit #984 §5,
        // the #970 gate's old SLOW_CC_HOLD_MS=6.5s sat inside ONE budget and so
        // never reached the escalation branch). With the keepalive PROVEN ALIVE the
        // whole time (ride-through window 60s ≫ 12s), the new contract DEFERS
        // UNCONDITIONALLY across every budget — ZERO reconnect.
        //   - PROBE_RAW_BUDGET_MS = 3 × 1.5s = 4.5s, so 12s spans ~2.7 budgets.
        // RED at the old time-ceiling (it escalates after ~2 budgets ≈ 9s, inside
        // the 12s hold → a reconnect fires); GREEN with the time-ceiling removed.
        const val SLOW_CC_HOLD_MS: Long = 12_000L
        const val POST_RECOVER_WATCH_MS: Long = 4_000L

        // --- Synthetic-jitter (issue #1081) variant knobs — NO toxiproxy. ---
        // Number of jitter rounds (each = one dead burst + one healthy gap). Spans
        // several probe budgets in total so both guards are exercised repeatedly.
        const val JITTER_ROUNDS: Int = 5
        // A SUB-budget dead burst: shorter than one full failure-threshold budget
        // (PROBE_RAW_BUDGET_MS = 4.5s), so it produces FEWER than
        // PROBE_FAILURE_THRESHOLD consecutive misses — the N-consecutive reset guard
        // absorbs it (the counter resets the moment the healthy gap's probe answers).
        const val SUB_BUDGET_BURST_MS: Long = 2_500L
        // The MIDDLE round's OVER-budget dead burst: spans MORE THAN TWO shortened
        // probe budgets (so it reaches the failure threshold), exercising the
        // #964/#982 unconditional keepalive deferral (no time ceiling) — mirrors the
        // deterministic sibling's SLOW_CC_HOLD_MS. On base (no #964) this burst
        // force-redials at its budget → the ZERO-reconnect assertion goes RED.
        const val OVER_BUDGET_BURST_MS: Long = 12_000L
        // The healthy gap between bursts — a couple of real probes answer, resetting
        // the failure run, and STILL no reconnect fires.
        const val JITTER_LIVE_GAP_MS: Long = 3_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L

        val FORBIDDEN_RECONNECT_EVENTS: Set<String> = setOf(
            "liveness_probe_silent_drop",
            "reconnect_start",
            "reconnect_tapped",
            "network_reconnect_start",
            "foreground_runtime_probe_failed",
        )
    }
}
