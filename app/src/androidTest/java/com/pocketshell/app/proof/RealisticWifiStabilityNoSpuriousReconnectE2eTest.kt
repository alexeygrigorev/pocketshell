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
 *  2. [realisticJitteryWifiOnRealLinkNeverRedials] — the OPT-IN realistic-link
 *     variant (gated by [assumeNetworkFaultProofsEnabled], self-skips on CI like
 *     every [NetworkFaultProofBase] proof since `tests.yml` does not bring up the
 *     toxiproxy proxy family). It holds a steady `latency=50ms, jitter=30ms`
 *     ([ToxiproxyControl.addJitterLatency]) + brief sub-budget stalls
 *     ([starveLinkFor]) + a same-IP reassoc on the REAL wire, LONGER than the
 *     slowest detection budget, and asserts the SAME ZERO-reconnect invariant
 *     against the REAL `refresh-client` probe + the REAL keepalive (no synthetic
 *     `forceLivenessProbeDeadForTest`). This is the faithful realistic-link proof;
 *     the deterministic variant above is its CI-runnable, never-self-skipping
 *     sibling.
 *
 * Assertions are USER-VISIBLE (D28(3)/D33): the cause-trail / diagnostics that
 * record a reconnect, the rendered connection-status indicators, the connect
 * attempt counter, and the painted terminal viewport — never internal/shadow
 * state. NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on the deterministic
 * variant's load-bearing assertions (D31/F3/G4).
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
    fun stableSlowControlChannelOnLiveTransportNeverRedials() = runBlocking<Unit> {
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

        // ---- THE #964 SLOW-BUT-LIVE WINDOW ----
        // Arm the slow `-CC` channel: the probe's refresh-client ping reports DEAD
        // (a momentarily congested control channel), while the agents:2222 SSH
        // TRANSPORT stays genuinely live, so the always-on keepalive keeps proving
        // the link alive. Hold it just PAST one shortened probe budget so the probe
        // REACHES its failure threshold (base redials here), then CLEAR it so the
        // next probe answers — modelling a slow link that un-congests (the #964
        // case), NOT a permanently wedged channel (#822, which the #964 fix
        // correctly still escalates after its bounded deferral).
        val stallStart = SystemClock.elapsedRealtime()
        currentViewModel().forceLivenessProbeDeadForTest = true
        recordTiming("stable_slow_cc_armed_at_ms", stallStart - attachStart)

        // Watch for ANY spurious reconnect across the whole slow window: the probe
        // must DEFER to the keepalive, never redial. (On base it redials the
        // instant the threshold is reached, ~PROBE_RAW_BUDGET_MS in.)
        watchNoSpuriousReconnect("during-slow-cc", SLOW_CC_HOLD_MS)

        currentViewModel().forceLivenessProbeDeadForTest = false
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
    }

    /**
     * THE OPT-IN REALISTIC-LINK VARIANT (toxiproxy, self-skips on CI).
     *
     * A steady jittery wifi link (latency 50ms + jitter 30ms) with brief
     * sub-budget stalls and a same-IP reassoc, held LONGER than the slowest
     * detection budget, must NOT spuriously reconnect on the REAL probe + keepalive
     * path (no synthetic seam). RED on base for the #964 reason; GREEN once #964
     * lands. This is the faithful realistic-link proof; the deterministic method
     * above is its CI-runnable sibling.
     */
    @Test
    fun realisticJitteryWifiOnRealLinkNeverRedials() = runBlocking<Unit> {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "jw${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue970-jitter-$marker"
        val hostName = "Issue970 JitterWifi $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE970-JITTER-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

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

        // STABLE-BUT-JITTERY: a steady latency+jitter band the whole hold — a
        // physically stable wifi/mobile link whose RTT wobbles but is never down.
        toxiproxy().addJitterLatency(latencyMs = JITTER_LATENCY_MS, jitterMs = JITTER_BAND_MS)
        try {
            // Brief sub-budget stalls + a same-IP reassoc interleaved across a hold
            // LONGER than the slowest detection budget. starveLinkFor itself asserts
            // no disconnect band surfaces while starved; between stalls the link is
            // its jittery-but-live self. The reassoc is modelled by clearing then
            // re-applying the jitter toxic mid-hold (a same-IP RF reassoc keeps the
            // socket; the latency band re-arms) — NOT a socket drop.
            val rounds = SLOWEST_BUDGET_HOLD_MS / JITTER_ROUND_MS
            for (round in 0 until rounds) {
                // A brief sub-budget stall — shorter than the probe's per-probe
                // timeout, so a STABLE client rides through it.
                starveLinkFor("jitter_round_${round}_substall", downMillis = SUB_BUDGET_STALL_MS)
                if (round == rounds / 2) {
                    // Same-IP reassoc mid-hold: drop + re-arm the latency band.
                    toxiproxy().clearToxics()
                    toxiproxy().addJitterLatency(latencyMs = JITTER_LATENCY_MS, jitterMs = JITTER_BAND_MS)
                    recordTiming("jitter_reassoc_at_ms", SystemClock.elapsedRealtime() - attachStart)
                }
                // Ride the jittery-but-live link between stalls.
                watchNoSpuriousReconnect("jitter_round_$round", JITTER_ROUND_MS - SUB_BUDGET_STALL_MS)
            }
        } finally {
            toxiproxy().clearToxics()
            recordTiming("jitter_total_hold_ms", SystemClock.elapsedRealtime() - attachStart)
        }

        assertNoReconnectCauseEvents("realistic jittery wifi")
        assertNoReconnectingTransition("realistic jittery wifi")
        assertNoExtraConnectAttempts(
            attemptsAfterAttach,
            expectedDelta = 0,
            label = "no redial across the jittery-but-live hold",
        )
        waitForConnected("after jittery hold")

        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "post-jitter")
        waitForVisibleTerminalText("post-jitter round-trip") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-jitter same client")

        writeSummary(
            testName = "RealisticWifiStabilityNoSpuriousReconnect-jitter",
            lines = listOf(
                "session=$sessionName",
                "fixture=network-fault-proxy:$NETWORK_FAULT_SSH_PORT (toxiproxy)",
                "scenario=latency ${JITTER_LATENCY_MS}ms jitter ${JITTER_BAND_MS}ms + sub-budget stalls + reassoc",
                "hold_ms=$SLOWEST_BUDGET_HOLD_MS",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsAfterAttach}",
                "liveness_probe_silent_drop=${diagnostics!!.eventsNamed("liveness_probe_silent_drop").size}",
                "expectation=ZERO reconnect on a stable jittery link (#964)",
                "expected_base=RED (probe redials at ~48s budget before keepalive rides through)",
            ),
        )
    }

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
        // (intervalMs × countMax = 2s × 15 = 30s, the window the #964 deferral
        // guard `isTransportProvenAliveWithinKeepAliveWindow` checks) stays
        // COMFORTABLY LONGER than the slow-CC hold (10s). This guarantees the
        // keepalive is continuously "proving the transport alive" for the whole
        // slow window even if a couple of replies are momentarily delayed — so the
        // #964 deferral guard always has a fresh live signal to defer to. (A short
        // 6s window would expire mid-hold and the probe would stop deferring — a
        // false RED.)
        const val KEEPALIVE_INTERVAL_MS: Long = 2_000L
        const val KEEPALIVE_COUNT_MAX: Int = 15

        // Hold the slow `-CC` window so it brackets EXACTLY ONE shortened probe
        // threshold hit:
        //   - PAST one budget (~4.5s) so the threshold is reached — BASE redials
        //     here (RED), and the #964 fix takes its single bounded DEFERRAL; but
        //   - UNDER two budgets (~9s) so a SECOND threshold hit never lands inside
        //     the dead window — which would make the #964 fix correctly ESCALATE
        //     the #822 wedge path (a genuinely-stuck `-CC` channel), a FALSE red for
        //     THIS slow-but-live scenario.
        // 6.5s sits squarely in the (4.5s, 9s) window with ~2s margin on each side,
        // robust against AVD scheduling jitter. After clearing, the next real probe
        // answers (the channel was never down) and the deferral run resets.
        const val SLOW_CC_HOLD_MS: Long = 6_500L
        const val POST_RECOVER_WATCH_MS: Long = 4_000L

        // Realistic-link (toxiproxy) variant knobs.
        const val JITTER_LATENCY_MS: Int = 50
        const val JITTER_BAND_MS: Int = 30
        // A brief stall SHORTER than the probe's per-probe timeout so a STABLE
        // client rides through it without counting a failure on its own.
        const val SUB_BUDGET_STALL_MS: Long = 800L
        const val JITTER_ROUND_MS: Long = 12_000L
        // Held LONGER than the production probe budget (~48s) so the realistic
        // variant truly exercises the full detection window the deterministic
        // variant shortens. 60s > 48s.
        const val SLOWEST_BUDGET_HOLD_MS: Long = 60_000L

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
