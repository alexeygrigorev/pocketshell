package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.projects.FOLDER_LIST_ERROR_TAG
import com.pocketshell.app.projects.FOLDER_LIST_RETRY_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1064 (R4 / #843 round-2 T10/C4): slow **cold-dial** robustness on a
 * congested cellular link.
 *
 * The cold-dial budget (`SshConnection.DEFAULT_TIMEOUT_MS = 30s` +
 * `SshLeaseManager.DEFAULT_CONNECT_TIMEOUT_MILLIS = 35s`) is generous but, prior
 * to this proof, **untested behind multi-second bufferbloat**. The audit's worry
 * is two-sided:
 *
 *  1. a slow-but-PROGRESSING first connect must complete within the budget —
 *     the dial must NOT give up prematurely on a handshake that is merely slow;
 *  2. a genuinely STALLED first connect (bytes never flow) must fail **cleanly**
 *     to a retryable picker state within the budget — it must NOT wedge the
 *     picker on an indefinite spinner.
 *
 * Both faults are applied BEFORE the app's first connect (the cold dial), so the
 * handshake itself rides the degraded link — unlike the reconnect/mid-session
 * fault proofs which inject the fault on an already-warm transport.
 *
 * Per the issue this is a **test-only confirmation guard**: the existing 30/35s
 * budget already rides through the slow-but-progressing case, so no dial-budget
 * change was required (if a regression shrank the budget below what a multi-
 * second handshake needs, [coldDialUnderBufferbloatCompletesWithinBudget] would
 * go red — a disconnect band or a never-attached terminal).
 *
 * Runs in the nightly toxiproxy phase (`NetworkFaultProofBase`), opt-in via
 * `pocketshellNetworkFaultProofs=true`; it self-skips on the per-PR CI journey
 * suite because tests.yml does not start the `network-fault-proxy` fixture.
 */
// CI_JOURNEY_SUITE_JUSTIFIED: NetworkFaultProofBase toxiproxy proof; gated by
// assumeNetworkFaultProofsEnabled() (self-skips on CI since tests.yml does not
// start network-fault-proxy:2228). Durable gate is the nightly suite's
// NETWORK_FAULT_CLASSES (scripts/nightly-extensive-suite.sh) alongside its
// StaleLeaseSwitchRecoveryE2eTest / NetworkLatencyModelE2eTest siblings — wiring
// it into ci-journey-suite.sh would only produce a vacuous CI skip.
@RunWith(AndroidJUnit4::class)
class ColdDialUnderBandwidthLimitE2eTest : NetworkFaultProofBase() {

    /**
     * (1) Slow-but-PROGRESSING cold dial: a healthy handshake that is merely
     * slow (jitter latency + bandwidth cap applied before the first connect)
     * must complete within the dial budget — attach lands, no Disconnected band
     * ever shows, and the session is usable. If the budget were too small to
     * cover a multi-second handshake, the dial would abort and this goes red.
     */
    @Test
    fun coldDialUnderBufferbloatCompletesWithinBudget() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "cd${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1064-cold-$marker"
        val hostName = "Issue1064 Cold $marker"

        // Seed the remote session + warm the fixture on a CLEAN link, then degrade
        // the link so only the app's first (cold) connect rides the bufferbloat.
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE1064-COLD-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        // Multi-second bufferbloat applied BEFORE the first connect: high jitter
        // latency on both directions (so each handshake round-trip is slow and
        // wobbly) plus a downstream bandwidth cap. Tuned to make the cold dial
        // take several seconds — comfortably under the 30/35s budget, but clearly
        // "slow" so a premature-abort regression surfaces.
        val proxy = toxiproxy()
        proxy.addJitterLatency(latencyMs = COLD_DIAL_LATENCY_MS, jitterMs = COLD_DIAL_JITTER_MS)
        proxy.addBandwidthLimit(rateKbps = COLD_DIAL_BANDWIDTH_KBPS)
        recordTiming("cold_dial_latency_ms", COLD_DIAL_LATENCY_MS.toLong())
        recordTiming("cold_dial_jitter_ms", COLD_DIAL_JITTER_MS.toLong())
        recordTiming("cold_dial_bandwidth_kbps", COLD_DIAL_BANDWIDTH_KBPS.toLong())

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // The cold dial: host-row tap -> folder/session enumeration -> tmux attach,
        // every connect riding the degraded link. attachToSession throws if the
        // picker wedges (session row never lists) or the terminal never attaches
        // (dial aborted) — so a green attach IS the within-budget proof.
        val dialStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        val coldDialMs = SystemClock.elapsedRealtime() - dialStart
        recordTiming("cold_dial_attach_ms", coldDialMs)

        // No premature abort: a slow-but-progressing dial must never surface the
        // Disconnected/Error band on the way up.
        assertNoDisconnectBand("cold-dial-under-bufferbloat")

        // Session is usable: a command echoes back over the slow link.
        sendCommandThroughTerminalInput("printf 'COLD-LIVE-$marker\\n'", "cold-dial-live")
        waitForVisibleTerminalText("cold-dial-live") { "COLD-LIVE-$marker" in it }

        // Sanity: the degraded link actually engaged (a clean sub-second dial
        // would mean the toxics silently did not apply — a vacuous pass). The
        // floor is well below any plausible bufferbloat dial but above a clean one.
        assertTrue(
            "expected the bufferbloat cold dial to take a meaningful time " +
                "(toxics engaged); got ${coldDialMs}ms",
            coldDialMs >= COLD_DIAL_MIN_EXPECTED_MS,
        )

        writeSummary(
            testName = "ColdDialUnderBandwidthLimitE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=apply jitter-latency + bandwidth cap BEFORE the first connect; cold-dial attach",
                "toxics=latency ${COLD_DIAL_LATENCY_MS}ms +/-${COLD_DIAL_JITTER_MS}ms both dirs, " +
                    "bandwidth ${COLD_DIAL_BANDWIDTH_KBPS}KB/s downstream",
                "cold_dial_attach_ms=$coldDialMs",
                "budget=SshConnection.DEFAULT_TIMEOUT_MS=30000ms, " +
                    "SshLeaseManager.DEFAULT_CONNECT_TIMEOUT_MILLIS=35000ms",
                "expectation=slow-but-progressing cold dial completes within budget, no Disconnected band, usable",
            ),
        )
        Unit
    }

    /**
     * (2) Genuinely STALLED cold dial: the link is up (TCP connects through the
     * proxy) but bytes never flow (a half-open blackhole), so the handshake makes
     * NO progress. The dial must fail **cleanly** within the budget to the
     * retryable picker error panel — it must NOT wedge the picker on an
     * indefinite spinner. The retry affordance proves the failure is recoverable.
     */
    @Test
    fun stalledColdDialFailsCleanlyToRetryablePickerWithoutWedge() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "cs${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1064-stall-$marker"
        val hostName = "Issue1064 Stall $marker"

        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE1064-STALL-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        // Half-open blackhole BEFORE the first connect: TCP establishes through
        // the (enabled) proxy, but every byte is dropped, so the SSH handshake
        // never progresses and the cold dial rides to the connect-timeout budget.
        val proxy = toxiproxy()
        proxy.addBlackhole()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Tap the host row; the folder/session enumeration connect stalls.
        waitForHostRow(hostRowTag)
        val stallStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        // The picker must surface the retryable ConnectError panel within the
        // budget rather than hang forever. Wait beyond the 35s dial budget but
        // well under the per-test watchdog.
        compose.waitUntil(timeoutMillis = STALL_FAIL_TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_LIST_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val stallFailMs = SystemClock.elapsedRealtime() - stallStart
        recordTiming("stalled_cold_dial_clean_fail_ms", stallFailMs)

        // Clean, recoverable failure (not a wedge): the error panel + a retry
        // affordance are shown, so the picker is usable again, not stuck.
        compose.onNodeWithTag(FOLDER_LIST_ERROR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "expected a retry affordance on the stalled cold-dial error panel " +
                "(clean, recoverable failure — not a wedge)",
            compose.onAllNodesWithTag(FOLDER_LIST_RETRY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary(
            testName = "ColdDialUnderBandwidthLimitE2eTest#stalled",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=half-open blackhole BEFORE first connect; cold-dial enumeration stalls",
                "stalled_cold_dial_clean_fail_ms=$stallFailMs",
                "expectation=picker surfaces retryable ConnectError within budget, no indefinite wedge",
            ),
        )
        Unit
    }

    private fun waitForHostRow(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private companion object {
        // Per-direction latency + jitter for the slow-but-progressing dial. The
        // effective round-trip base is ~2x latency; with many handshake round
        // trips this makes the cold dial take several seconds — clearly "slow"
        // bufferbloat, but comfortably under the 30/35s dial budget.
        const val COLD_DIAL_LATENCY_MS: Int = 350
        const val COLD_DIAL_JITTER_MS: Int = 200

        // Mild downstream bandwidth cap (KB/s): adds drain time without choking
        // the small handshake / fresh-shell redraw to the point of a real timeout.
        const val COLD_DIAL_BANDWIDTH_KBPS: Int = 120

        // The bufferbloat dial must take meaningfully longer than a clean dial;
        // a sub-second attach would mean the toxics never applied (vacuous pass).
        const val COLD_DIAL_MIN_EXPECTED_MS: Long = 1_000L

        // Beyond the 35s lease dial budget so the stalled connect has fully timed
        // out and surfaced its clean failure, but well under the 300s per-test
        // ci-journey watchdog.
        const val STALL_FAIL_TIMEOUT_MS: Long = 60_000L
    }
}
