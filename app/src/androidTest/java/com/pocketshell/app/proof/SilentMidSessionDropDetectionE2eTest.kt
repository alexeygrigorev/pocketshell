package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Epic #792 Slice 0 — coverage-first executable spec for the #822 dogfood bug.
 *
 * These are the TWO missing journey tests called out by the "Finish-it audit"
 * (2026-06-19) on #792. They pin the maintainer's real on-device #822 symptom —
 * "SSH drops on stable Wi-Fi, header stuck on amber 'Reconnecting', recovers only
 * by switching to another session and back" — as automated journeys.
 *
 * THEY ARE EXPECTED TO BE RED on today's `main` (HEAD 09bc7c37). They are the
 * target behaviour the later connection-manager slices (Slice C reconnect-ladder
 * IO, Slice D `LivenessProbe`) will deliver; they merge GREEN when that fix lands.
 * Per D28(3)/D31 they assert the USER-VISIBLE state (the rendered connection-lost
 * indicator and the recovered session viewport), not internal/shadow state, so a
 * journey only passes when the user-visible behaviour is actually correct.
 *
 * --------------------------------------------------------------------------
 * WHY THEY FAIL TODAY (the structural drop-detection gap — audit §3):
 *
 * On an IDLE, quiet control channel there is NO active liveness probe. A silent
 * Wi-Fi half-open is invisible until one of two lagging/send-triggered oracles
 * trips:
 *   1. sshj keep-alive — `SshConnection.DEFAULT_KEEP_ALIVE_SECONDS` (15s) ×
 *      `DEFAULT_MAX_ALIVE_COUNT` (4) = a ~60s window before `CONNECTION_LOST`
 *      (`SshConnection.kt:39,60,199`); and the first keep-alive request can land up
 *      to one full interval after connect, so worst-case detection is ~75s.
 *   2. `TmuxClient.disconnected` — flips true only when the reader loop sees EOF
 *      or the command-timeout watchdog trips on a DISPATCHED command
 *      (`TmuxClient.kt:587`, `:430-469`). With NO command in flight (the user is
 *      reading or recording a voice note — the exact #822 scenario), nothing probes
 *      the channel, so nothing flips.
 *
 * `RealSshSession.kt:126-127` states the root directly: "A silently-dead transport
 * (sshj's `isConnected` lies until the 60s keepalive trips)." Between the drop and
 * the next send/keepalive there is a DETECTION VOID: the user keeps interacting
 * with a dead session and the connection-lost indicator never appears.
 *
 * Test 1 ([silentIdleDropSurfacesConnectionLostIndicatorWithoutSending]) blackholes
 * the link on an IDLE session and asserts the connection-lost indicator surfaces
 * within [DROP_DETECT_WINDOW_MS] WITHOUT any send. On today's `main` the indicator
 * never appears inside that window (the contrast against [DisconnectBlackholeE2eTest],
 * which only surfaces the band BECAUSE it sends a command into the dead channel),
 * so the test FAILS for exactly the audited reason — the missing mid-session probe.
 *
 * Test 2 ([silentDropAutoRecoversWithoutSessionSwitchDance]) asserts that after such
 * a drop the SAME session recovers WITHOUT the switch-to-another-session-and-back
 * workaround. On today's `main` the inline `scheduleAutoReconnect` wedges (per #822's
 * own analysis: "the OLD path failing"), recoverable only by the switch dance which
 * re-enters `connect()` and evicts the poisoned lease — so this FAILS too.
 *
 * --------------------------------------------------------------------------
 * HARNESS: the #552 toxiproxy / link-cut harness via [NetworkFaultProofBase]
 * (`network-fault-proxy` on host port 2228 → `agents:22`; Toxiproxy control API on
 * 8474). [toxiproxy().addBlackhole] applies the `timeout=0` toxic on both streams —
 * a half-open / no-FIN dead peer that keeps the TCP socket "established" while
 * silently dropping every byte. That is the authentic silent-drop failure mode an
 * EOF-based detector misses and that only an active liveness probe can catch on a
 * quiet channel. This is the SAME harness `KeepAliveDeadPeerDetectionE2eTest` and
 * `DisconnectBlackholeE2eTest` use.
 *
 * NIGHTLY phase: gated through [NetworkFaultProofBase.assumeNetworkFaultProofsEnabled]
 * (`pocketshellNetworkFaultProofs=true`, self-skips on CI) like every
 * `NetworkFaultProofBase` proof, because the per-PR `ci-journey-suite.sh` brings up
 * ONLY `agents:2222` and NOT the toxiproxy proxy family on 2228/8474. This is an
 * ENVIRONMENT gate (the fixtures are absent), NOT a self-skip of the load-bearing
 * assertion: when the fixtures ARE present the assertions HARD-fail. The fix slice
 * (Slice D) that turns these GREEN must also wire the toxiproxy family into the
 * per-PR gate (or move this class onto the deterministic `agents:2222` fixture with
 * a synthetic-drop seam) so D31's per-push-CI requirement is met — see the "PROD
 * HOOK NEEDED" notes below.
 *
 * --------------------------------------------------------------------------
 * PROD HOOK NEEDED (for the fix slices — NOT added in Slice 0):
 *
 *  - **LivenessProbe seam (Slice D / V7a).** A controller-owned active probe that,
 *    while foregrounded + `Live`, pings the active control channel every N seconds
 *    (audit suggests ~10s, below the 60s keep-alive window) and, on probe failure,
 *    submits `ConnectionEvent.TransportDropped`. The controller reducer ALREADY
 *    handles the rest (`Live --TransportDropped--> Reattaching --(fails)-->
 *    Reconnecting(n) --> Unreachable`, `ConnectionController.kt:182-210`); detection
 *    is the only missing piece. To keep this journey deterministic on CI the probe
 *    will likely need a test-injectable interval/clock seam (the analogue of the
 *    existing `BackgroundGraceTestOverride`), so the probe window can be shortened
 *    well below 60s under the swiftshader AVD without weakening the assertion.
 *  - **Single controller reconnect entrypoint (Slice C).** The wedge auto-recovery
 *    (Test 2) needs the reconnect-ladder IO moved into the `TransportEffects`
 *    re-dial so a dropped lease is evicted+re-dialled WITHOUT the switch dance.
 *    `#823`'s pull-to-reconnect / Reconnect button must trigger that ONE entrypoint,
 *    never a third writer on `scheduleAutoReconnect`.
 */
@RunWith(AndroidJUnit4::class)
class SilentMidSessionDropDetectionE2eTest : NetworkFaultProofBase() {

    @org.junit.Before
    fun armLivenessProbeWindow() {
        // EPIC #792 Slice D: shorten the LivenessProbe window so the REAL toxiproxy
        // half-open blackhole is detected within this test's product budget
        // ([DROP_DETECT_WINDOW_MS] = 12s). With the production defaults (10s interval
        // × 2 failures of an 8s timeout ≈ 28s worst case) detection would not land
        // inside the 12s budget. The shortened window (interval 2s, per-probe timeout
        // 3s, threshold 2 → ~8s worst case) keeps the N-consecutive-failure guard
        // intact while fitting the budget — this is the analogue of
        // BackgroundGraceTestOverride, NOT a weakening of the assertion (the real
        // blackholed channel must still genuinely fail every probe).
        LivenessProbeTestOverride.setForTest(
            intervalMs = 2_000L,
            perProbeTimeoutMs = 3_000L,
            failureThreshold = 2,
        )
    }

    @org.junit.After
    fun clearLivenessProbeWindow() {
        LivenessProbeTestOverride.clear()
    }

    /**
     * #822 (the headline): a silent mid-session drop on an IDLE channel must be
     * DETECTED and SHOWN as a connection-lost indicator within a bounded window —
     * with NO command sent (the user is reading or recording a voice note).
     *
     * RED on today's `main`: there is no mid-session liveness probe, so the
     * connection-lost indicator never appears inside [DROP_DETECT_WINDOW_MS].
     */
    @Test
    fun silentIdleDropSurfacesConnectionLostIndicatorWithoutSending() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "sd${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue822-silentdrop-$marker"
        val hostName = "Issue822 SilentDrop $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE822-SILENTDROP-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("silentdrop_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Confirm we are genuinely live BEFORE the drop, via the user-visible
        // connection status (the authority that drives the header pill / indicator).
        // One send here only establishes the live baseline — after this point NOTHING
        // is sent, modelling the user reading / recording a voice note.
        sendCommandThroughTerminalInput("printf 'LIVE-$marker\\n'", "pre-drop-live")
        waitForVisibleTerminalText("pre-drop-live") { "LIVE-$marker" in it }
        assertTrue(
            "expected Connected before the silent drop, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Silently kill the link: keep the socket established, drop every byte both
        // ways (half-open / no-FIN). The user sends NOTHING after this.
        val dropStart = SystemClock.elapsedRealtime()
        toxiproxy().clearToxics()
        toxiproxy().addBlackhole()
        recordTiming("silentdrop_blackhole_at_ms", dropStart - attachStart)

        // THE LOAD-BEARING ASSERTION (V7): without any send, a USER-VISIBLE
        // connection-lost indicator must surface within the bounded window.
        //
        // Window justification: this is the PRODUCT requirement (a few seconds), NOT
        // the current ~60-75s keep-alive worst case. [DROP_DETECT_WINDOW_MS] = 12s is
        // a few seconds of detection + margin for the swiftshader CI emulator sharing
        // cores with a Docker container — deliberately FAR below the ~60s keep-alive
        // window so a PASS proves a proactive probe fired, not the keep-alive lag.
        // On today's `main` no probe exists, so the indicator does not appear here and
        // this FAILS — which is the Slice 0 deliverable.
        val detected = waitForConnectionLostIndicator(
            label = "silent-idle-drop",
            timeoutMillis = DROP_DETECT_WINDOW_MS,
        )
        val detectMs = SystemClock.elapsedRealtime() - dropStart
        recordTiming("silentdrop_indicator_window_ms", DROP_DETECT_WINDOW_MS)
        recordTiming("silentdrop_indicator_detected_ms", if (detected) detectMs else -1L)
        recordConnectionLostSnapshot("silent-idle-drop")

        writeSummary(
            testName = "SilentMidSessionDropDetectionE2eTest-silentIdleDrop",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=idle channel, NO send after baseline, link blackholed",
                "cut=toxiproxy timeout toxic timeout=0 on upstream+downstream (half-open)",
                "detection_window_ms=$DROP_DETECT_WINDOW_MS",
                "indicator_detected=$detected",
                "indicator_detected_ms=${if (detected) detectMs else -1L}",
                "final_connection_status=${currentConnectionStatus()}",
                "expectation=connection-lost indicator within window WITHOUT a send (#822/V7)",
                "expected_today=RED — no mid-session liveness probe exists (audit §3)",
            ),
        )

        assertTrue(
            "Expected a USER-VISIBLE connection-lost indicator within ${DROP_DETECT_WINDOW_MS}ms " +
                "of a SILENT idle drop WITHOUT sending anything, but the session still showed " +
                "Connected/healthy (status=${currentConnectionStatus()}). This is the #822 " +
                "detection void: with no command in flight, no active liveness probe notices the " +
                "dead channel, so the user keeps interacting with a dead session. Expected to " +
                "FAIL until the LivenessProbe (Slice D) lands.",
            detected,
        )
    }

    /**
     * #822: after a silent drop, the SAME session must auto-recover (or recover via
     * the visible reconnect affordance) once the link is restored — WITHOUT the
     * switch-to-another-session-and-back workaround the maintainer is forced into.
     *
     * RED on today's `main`: the inline reconnect ladder wedges on a poisoned warm
     * lease (recoverable only by the switch dance, which re-enters `connect()` and
     * evicts the lease), so the current session never returns to a live, input-
     * accepting state on its own.
     */
    @Test
    fun silentDropAutoRecoversWithoutSessionSwitchDance() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "wg${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue822-wedge-$marker"
        val hostName = "Issue822 Wedge $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE822-WEDGE-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("wedge_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "pre-wedge")
        waitForVisibleTerminalText("pre-wedge") { "BEFORE-$marker" in it }
        assertTrue(
            "expected Connected before the silent drop, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Silent half-open drop, then restore the link AFTER a sustained outage —
        // a clean Wi-Fi blip / NAT rebinding. The user does NOT touch the session
        // and does NOT perform the switch-to-another-session-and-back workaround.
        disableProxyFor("wedge_silent_drop", downMillis = SILENT_OUTAGE_MS)

        // THE LOAD-BEARING ASSERTION: once the link is back, the SAME session must
        // return to a live, input-accepting state on its own (or via the in-place
        // Reconnect affordance) — NO switch dance. We give it [WEDGE_RECOVER_WINDOW_MS]
        // and assert the connection-lost indicator clears AND a fresh send round-trips
        // through the SAME session. On today's `main` the inline ladder wedges, so the
        // indicator never clears within the window and/or the send never round-trips —
        // RED for the audited "OLD path failing" reason.
        val recovered = waitForSessionRecovered(
            label = "wedge-auto-recover",
            timeoutMillis = WEDGE_RECOVER_WINDOW_MS,
        )
        val recoverMs = SystemClock.elapsedRealtime() - attachStart
        recordTiming("wedge_recover_window_ms", WEDGE_RECOVER_WINDOW_MS)
        recordTiming("wedge_recovered_bool", if (recovered) 1L else 0L)
        recordTiming("wedge_recover_elapsed_ms", recoverMs)
        recordConnectionLostSnapshot("wedge-auto-recover")

        var roundTripped = false
        if (recovered) {
            roundTripped = runCatching {
                sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "post-wedge")
                waitForVisibleTerminalText(
                    "post-wedge",
                    timeoutMillis = WEDGE_ROUND_TRIP_WINDOW_MS,
                ) { "AFTER-$marker" in it }
                waitForClientCountAtMost(key, sessionName, max = 1, label = "post-wedge same client")
                true
            }.getOrDefault(false)
        }

        writeSummary(
            testName = "SilentMidSessionDropDetectionE2eTest-wedgeAutoRecover",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=silent drop, link restored, NO switch-session dance, NO manual nudge",
                "cut=toxiproxy disable for >=${SILENT_OUTAGE_MS}ms then enable",
                "recover_window_ms=$WEDGE_RECOVER_WINDOW_MS",
                "recovered_to_live=$recovered",
                "post_recovery_round_trip=$roundTripped",
                "final_connection_status=${currentConnectionStatus()}",
                "expectation=SAME session recovers + input round-trips, no switch dance (#822)",
                "expected_today=RED — inline reconnect ladder wedges (audit §3, 'OLD path failing')",
            ),
        )

        assertTrue(
            "Expected the SAME session to auto-recover to a live, input-accepting state within " +
                "${WEDGE_RECOVER_WINDOW_MS}ms of the link being restored — WITHOUT the " +
                "switch-to-another-session-and-back workaround. Observed recovered=$recovered, " +
                "post-recovery send round-tripped=$roundTripped, status=${currentConnectionStatus()}. " +
                "This is the #822 wedge: the inline reconnect ladder fails on a poisoned warm lease " +
                "and only the switch dance evicts it. Expected to FAIL until the controller-owned " +
                "reconnect ladder (Slice C) lands.",
            recovered && roundTripped,
        )
    }

    // -- user-visible state helpers ------------------------------------------------

    /**
     * Wait for ANY user-visible connection-lost indicator to surface, polling the
     * SAME signals a human sees on screen:
     *  - the header connection pill ([TMUX_CONNECTION_STATUS_PILL_TAG]) showing a
     *    non-Connected status ("Reconnecting" / "Disconnected");
     *  - the mid-session disconnect band ([TMUX_SESSION_ERROR_TAG]) + its
     *    Reconnect button ([TMUX_SESSION_RECONNECT_TAG]);
     *  - the pull-to-reconnect affordance ([TMUX_PULL_TO_RECONNECT_TAG], #823),
     *    mounted only while NOT Connected;
     *  - the projected [TmuxSessionViewModel.ConnectionStatus] leaving Connected,
     *    which is the authority that DRIVES the above indicators.
     *
     * Returns true as soon as the session is no longer presented as healthily
     * Connected; false if it stays Connected for the whole window.
     */
    private fun waitForConnectionLostIndicator(label: String, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionLostIndicatorVisible()) {
                recordTiming("${label}_indicator_first_seen_ms", SystemClock.elapsedRealtime())
                return true
            }
            SystemClock.sleep(200)
        }
        return connectionLostIndicatorVisible()
    }

    private fun connectionLostIndicatorVisible(): Boolean {
        if (hasErrorBand() || hasReconnectButton() || hasPullToReconnect()) return true
        // The status that DRIVES every connection-lost indicator: anything other than
        // Connected/Idle (Idle = no session attached at all) is a user-visible
        // non-healthy state. Reconnecting/Failed/Connecting all render an indicator.
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Connected -> false
            is TmuxSessionViewModel.ConnectionStatus.Idle -> false
            else -> true
        }
    }

    /**
     * Wait for the SAME session to return to a healthily-Connected, input-ready
     * state: no error band, no pull-to-reconnect affordance, no Reconnect button,
     * and the projected status back to Connected.
     */
    private fun waitForSessionRecovered(label: String, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionHealthyConnected()) {
                recordTiming("${label}_recovered_seen_ms", SystemClock.elapsedRealtime())
                return true
            }
            SystemClock.sleep(250)
        }
        return sessionHealthyConnected()
    }

    private fun sessionHealthyConnected(): Boolean {
        if (hasErrorBand() || hasReconnectButton() || hasPullToReconnect()) return false
        return currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
    }

    private fun hasErrorBand(): Boolean =
        compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasReconnectButton(): Boolean =
        compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasPullToReconnect(): Boolean =
        compose.onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun connectionPillTexts(): List<String> =
        compose.onAllNodesWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
            .let { pillCount ->
                listOf("Reconnecting", "Disconnected", "Connecting")
                    .filter { text ->
                        compose.onAllNodesWithText(text, useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    .let { matches -> if (pillCount > 0) matches.ifEmpty { listOf("<pill present>") } else matches }
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

    private fun recordConnectionLostSnapshot(label: String) {
        artifactFile("$label-connection-lost-snapshot.txt").writeText(
            buildString {
                appendLine("label=$label")
                appendLine("connection_status=${currentConnectionStatus()}")
                appendLine("error_band_present=${hasErrorBand()}")
                appendLine("reconnect_button_present=${hasReconnectButton()}")
                appendLine("pull_to_reconnect_present=${hasPullToReconnect()}")
                appendLine("connection_pill_texts=${connectionPillTexts()}")
            },
        )
    }

    private companion object {
        /**
         * Product-level detection budget for a silent idle drop: a few seconds +
         * CI-emulator margin. Deliberately FAR below the ~60s sshj keep-alive window
         * (`SshConnection.DEFAULT_KEEP_ALIVE_SECONDS` 15s × `DEFAULT_MAX_ALIVE_COUNT`
         * 4) so a PASS proves a PROACTIVE liveness probe fired, not the keep-alive lag.
         * 12s = ~a few seconds of detection plus headroom for the swiftshader emulator
         * sharing cores with the Docker `agents` container.
         */
        const val DROP_DETECT_WINDOW_MS: Long = 12_000L

        /** Sustained silent outage before the link is restored (clean Wi-Fi blip / NAT rebind). */
        const val SILENT_OUTAGE_MS: Long = 8_000L

        /**
         * Window for the SAME session to auto-recover to live after the link returns,
         * WITHOUT the switch dance. Generous for the auto-reconnect ladder + reattach +
         * reseed on the loaded CI emulator, while still failing fast if the session
         * wedges (the #822 symptom).
         */
        const val WEDGE_RECOVER_WINDOW_MS: Long = 45_000L

        /** Round-trip window for the post-recovery send to echo back through the same session. */
        const val WEDGE_ROUND_TRIP_WINDOW_MS: Long = 30_000L
    }
}
