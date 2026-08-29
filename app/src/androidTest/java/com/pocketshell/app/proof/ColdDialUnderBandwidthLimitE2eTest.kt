package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.projects.FOLDER_LIST_CONTENT_TAG
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
    fun coldDialUnderBufferbloatCompletesWithinBudget() { runBlocking {
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
        // every connect riding the degraded link. openSessionFromList throws if the
        // picker wedges (session row never lists) or the terminal never attaches
        // (dial aborted) — so a green attach IS the within-budget proof.
        //
        // Issue #2409 (round 2): this used to call attachToSession(), whose
        // enumeration wait has no tolerance for the app's OWN retryable
        // folder-list panel. Reproduced on this box: at this severity (350 ±200 ms
        // one-way, RTT 0.7–1.1 s) the FolderListGateway bounded exec — a hard
        // 3.5 s TOTAL budget for ~2–3 round-trips — sits ON its cliff, so the
        // enumeration intermittently lands on "Couldn't refresh the project tree —
        // tap to retry" and this DIAL proof died in setup with
        // `folder-list:content=0`, never reaching the thing it asserts. That is a
        // second, distinct #2409 recurrence mechanism for this same class. The
        // enumeration is not what #1064 proves, so heal it through the app's own
        // Retry affordance and RECORD the taps (see [settleFolderListToleratingRetry]).
        val dialStart = SystemClock.elapsedRealtime()
        waitForHostRow(hostRowTag)
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        val folderListRetryTaps = settleFolderListToleratingRetry()
        recordTiming("cold_dial_folder_list_retry_taps", folderListRetryTaps.toLong())
        openSessionFromList(hostName, sessionName)
        val coldDialMs = SystemClock.elapsedRealtime() - dialStart
        recordTiming("cold_dial_attach_ms", coldDialMs)

        // LOAD-BEARING (the #1064 point): a slow-but-progressing cold dial completes
        // within the 30/35s dial budget without a premature abort — the attach landed
        // (attachToSession above throws otherwise) and no Disconnected/Error band ever
        // shows on the way up.
        assertNoDisconnectBand("cold-dial-under-bufferbloat")

        // Sanity: the degraded link actually engaged (a clean sub-second dial
        // would mean the toxics silently did not apply — a vacuous pass). The
        // floor is well below any plausible bufferbloat dial but above a clean one.
        assertTrue(
            "expected the bufferbloat cold dial to take a meaningful time " +
                "(toxics engaged); got ${coldDialMs}ms",
            coldDialMs >= COLD_DIAL_MIN_EXPECTED_MS,
        )

        // Session usable after the cold dial — DISTINCT slow-link mechanism (issue #1676).
        //
        // The prior "session usable" check typed a command through the terminal and
        // waited for its printf output to render. That check was BOTH:
        //  (a) VACUOUS — the visible-text predicate matched the ECHOED COMMAND TEXT
        //      (`printf 'COLD-LIVE-…'` literally contains "COLD-LIVE-…"), so it never
        //      actually verified the printf OUTPUT round-tripped; and
        //  (b) UNBOUNDED under the bufferbloat — even the command-echo redraw over the
        //      downstream bandwidth cap drained past the 180s visibility budget
        //      (~217s+ measured on the nightly), which is a slow-LINK fixture artifact,
        //      not an app defect. That is the distinct mechanism that red this cohort
        //      test on the nightly.
        //
        // Per the maintainer-approved #1676 plan (option 3): the load-bearing gating
        // proof is the dial-completes-within-budget + no-disconnect-band + toxics-engaged
        // assertions above (the actual #1064 point). "Session usable" is now proven
        // ROBUSTLY and non-vacuously server-side — the cold dial produced a real, live
        // tmux session with exactly the app's one client — over a DIRECT SSH connection
        // (port 2222, NOT through the bufferbloat proxy), so it is bounded and does not
        // depend on draining the shell redraw through the capped link.
        waitForClientCountAtMost(key, sessionName, max = 1, label = "cold-dial live session")

        writeSummary(
            testName = "ColdDialUnderBandwidthLimitE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=apply jitter-latency + bandwidth cap BEFORE the first connect; cold-dial attach",
                "toxics=latency ${COLD_DIAL_LATENCY_MS}ms +/-${COLD_DIAL_JITTER_MS}ms both dirs, " +
                    "bandwidth ${COLD_DIAL_BANDWIDTH_KBPS}KB/s downstream",
                "cold_dial_attach_ms=$coldDialMs",
                "cold_dial_folder_list_retry_taps=$folderListRetryTaps",
                "budget=SshConnection.DEFAULT_TIMEOUT_MS=30000ms, " +
                    "SshLeaseManager.DEFAULT_CONNECT_TIMEOUT_MILLIS=35000ms",
                "expectation=slow-but-progressing cold dial completes within budget, no Disconnected band, usable",
            ),
        )
        Unit
    } }

    /**
     * (1b) Issue #2409 — the SEED-LADDER-SATURATED cold attach.
     *
     * [coldDialUnderBufferbloatCompletesWithinBudget] above reproduced the
     * defect on the nightly emulator but PASSED on a fast dev box, because at
     * its severity the pre-reveal seed ladder happened to land its `capture-pane`
     * on the 3rd of 4 attempts (measured locally: panes-ready in 10.07 s against
     * the then-12 s ceiling — 84 % of budget, i.e. green by 1.9 s of luck). A
     * proof whose verdict depends on which host runs it is not a proof; per
     * D32-G10 the fixture that creates the non-happy state has to be part of it.
     *
     * So this case PINS the state instead of hoping for it. The dial and folder
     * enumeration ride the WiFi-baseline severity ([SETUP_ONE_WAY_LATENCY_MS],
     * RTT ≈ 150 ms — that constant's KDoc explains why it is NOT the cold-dial
     * severity), and the link is retuned IN PLACE to [ATTACH_SEED_LATENCY_MS] at
     * the last possible moment — after the picker has settled, immediately before
     * the session row is tapped. At that severity an exec-lane round-trip costs
     * ≈4 s, which is deterministically:
     *
     *  - ABOVE the seed `capture-pane` ceiling (`SEED_CAPTURE_TIMEOUT_MS`, 2.5 s),
     *    so ALL of the `SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS` seed attempts time out
     *    and the pre-reveal seed spends its whole ≈10.4 s worst case; and
     *  - BELOW the reconcile ceiling (`RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS`, 6 s),
     *    so `list-panes` still SUCCEEDS and the link is genuinely
     *    slow-but-progressing, never wedged.
     *
     * That makes one `reconcilePanes()` cost ≈14 s of honest, budgeted work. On
     * the superseded flat 12 s `ATTACH_PANES_READY_TIMEOUT_MS` the app cancelled
     * it mid-flight and went `Attaching -> Unreachable` with
     * "Tap Reconnect to retry" — the exact nightly failure. With the ceiling
     * derived from the stages it wraps, the same attach completes.
     *
     * The elapsed-time assertion below is the anti-vacuous pin: if the fixture
     * ever stops making the attach outlast the superseded ceiling, this case
     * fails as a fixture regression rather than passing for the wrong reason.
     */
    @Test
    fun coldAttachWhoseSeedLadderIsSaturatedStillCompletesInsteadOfSurrendering() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "cq${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue2409-seed-$marker"
        val hostName = "Issue2409 Seed $marker"

        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE2409-SEED-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        // SETUP severity — see [SETUP_ONE_WAY_LATENCY_MS]. A toxic IS installed
        // (so the attach-phase retune below is an in-place update on the live
        // link, never a clean window), but at the WiFi baseline, which is the
        // documented ZERO-bounded-exec-overrun extreme.
        val proxy = toxiproxy()
        proxy.addSymmetricLatency(oneWayMs = SETUP_ONE_WAY_LATENCY_MS)
        recordTiming("setup_one_way_latency_ms", SETUP_ONE_WAY_LATENCY_MS.toLong())

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        waitForHostRow(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        // Settle the enumeration BEFORE the attach severity is raised, healing a
        // retryable folder-list failure through the app's own Retry affordance
        // (see [settleFolderListToleratingRetry]). Recorded, never silent.
        val folderListRetryTaps = settleFolderListToleratingRetry()
        recordTiming("folder_list_retry_taps", folderListRetryTaps.toLong())

        val attachStart = SystemClock.elapsedRealtime()
        var attachSeverityAppliedAtMs = 0L
        openSessionFromList(hostName, sessionName) {
            // The fixture: retune the LIVE toxic in place (no clean window) so the
            // attach — and only the attach — rides the seed-saturating severity.
            proxy.updateJitterLatency(latencyMs = ATTACH_SEED_LATENCY_MS, jitterMs = 0)
            attachSeverityAppliedAtMs = SystemClock.elapsedRealtime()
            recordTiming("attach_seed_latency_ms", ATTACH_SEED_LATENCY_MS.toLong())
        }
        val attachMs = SystemClock.elapsedRealtime() - attachSeverityAppliedAtMs
        recordTiming("saturated_seed_attach_ms", attachMs)
        recordTiming("saturated_seed_total_ms", SystemClock.elapsedRealtime() - attachStart)

        // LOAD-BEARING: the app must not surrender a healthy attach. openSessionFromList
        // throws with the VM's real connection status if the terminal never attached
        // (#2409's harness fix), and a settled Failed band is the rendered form of the
        // same give-up.
        assertNoDisconnectBand("cold-attach-with-saturated-seed-ladder")

        // Anti-vacuous: the attach genuinely had to outlast the superseded ceiling.
        // Without this the case could go green on a box fast enough that the seed
        // ladder never saturated — the exact way the sibling above passed locally
        // while failing nine nightly runs.
        assertTrue(
            "expected the saturated seed ladder to make the attach outlast the superseded " +
                "${SUPERSEDED_ATTACH_CEILING_MS}ms attach ceiling (that is what makes this a " +
                "regression pin); attach took ${attachMs}ms at ${ATTACH_SEED_LATENCY_MS}ms " +
                "one-way latency",
            attachMs >= SUPERSEDED_ATTACH_CEILING_MS,
        )

        waitForClientCountAtMost(key, sessionName, max = 1, label = "saturated-seed live session")

        writeSummary(
            testName = "ColdDialUnderBandwidthLimitE2eTest#saturatedSeedLadder",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=dial + enumeration at the WiFi baseline " +
                    "(${SETUP_ONE_WAY_LATENCY_MS}ms one-way), then retune the " +
                    "LIVE toxic to ${ATTACH_SEED_LATENCY_MS}ms one-way immediately before the " +
                    "session-row tap so every seed capture-pane exceeds its 2.5s ceiling while " +
                    "list-panes stays under its 6s ceiling",
                "setup_one_way_latency_ms=$SETUP_ONE_WAY_LATENCY_MS",
                "folder_list_retry_taps=$folderListRetryTaps",
                "saturated_seed_attach_ms=$attachMs",
                "expectation=the attach completes; the app must NOT go Attaching -> Unreachable " +
                    "on a link that is merely slow (#2409)",
            ),
        )
        Unit
    } }

    /**
     * (2) Genuinely STALLED cold dial: the link is up (TCP connects through the
     * proxy) but bytes never flow (a half-open blackhole), so the handshake makes
     * NO progress. The dial must fail **cleanly** within the budget to the
     * retryable picker error panel — it must NOT wedge the picker on an
     * indefinite spinner. The retry affordance proves the failure is recoverable.
     */
    @Test
    fun stalledColdDialFailsCleanlyToRetryablePickerWithoutWedge() { runBlocking {
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
    } }

    /**
     * Issue #2409 (round 2) — settle the host-detail folder list to CONTENT,
     * healing a RETRYABLE enumeration failure through the app's own Retry
     * affordance, and report how many taps that took.
     *
     * The enumeration is explicitly NOT what this case proves (the attach is),
     * and `FolderListGateway`'s bounded exec is a hard 3.5 s TOTAL budget
     * (`BoundedSessionExec.execBounded`, #1641) with NO auto-retry behind it:
     * once it abandons, the picker parks on [FOLDER_LIST_ERROR_TAG] until a
     * human (or this helper) taps Retry. On a contended emulator the cold-start
     * JIT/first-compose of `FolderListScreen` can starve that read's IO worker
     * long enough to blow the budget on its own — which reddens the ATTACH proof
     * in its SETUP, producing an artifact indistinguishable from a fixture
     * outage. Healing it here keeps the failure signal of this test attributable
     * to the attach.
     *
     * This tolerates only the app's OWN advertised recovery path, bounded to
     * [FOLDER_LIST_MAX_RETRY_TAPS] taps, and the count is recorded as
     * `folder_list_retry_taps` in the timings + summary — so a fixture that
     * starts needing retries is VISIBLE in the artifact rather than silently
     * masked. The retryability of that panel is itself owned (and asserted) by
     * [stalledColdDialFailsCleanlyToRetryablePickerWithoutWedge] below.
     */
    private fun settleFolderListToleratingRetry(): Int {
        var retryTaps = 0
        val deadline = SystemClock.elapsedRealtime() + FOLDER_LIST_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasTag(FOLDER_LIST_CONTENT_TAG)) return retryTaps
            if (hasTag(FOLDER_LIST_ERROR_TAG) && retryTaps < FOLDER_LIST_MAX_RETRY_TAPS) {
                compose.onNodeWithTag(FOLDER_LIST_RETRY_TAG, useUnmergedTree = true).performClick()
                retryTaps++
            }
            SystemClock.sleep(FOLDER_LIST_SETTLE_POLL_MS)
        }
        // Deliberately NOT an assertion: openSessionFromList's own diagnostic
        // wait runs next and produces the richer failure report (tag/text probes).
        return retryTaps
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
        // Issue #1676: kept at the original 120 KB/s (the intended cold-dial severity)
        // — the ~217s post-attach shell-redraw drain that this cap produces is no longer
        // in the test path (the vacuous+unbounded echo check was replaced by a bounded
        // server-side liveness check), so no toxic relaxation is needed.
        const val COLD_DIAL_BANDWIDTH_KBPS: Int = 120

        // The bufferbloat dial must take meaningfully longer than a clean dial;
        // a sub-second attach would mean the toxics never applied (vacuous pass).
        const val COLD_DIAL_MIN_EXPECTED_MS: Long = 1_000L

        // Beyond the 35s lease dial budget so the stalled connect has fully timed
        // out and surfaced its clean failure, but well under the 300s per-test
        // ci-journey watchdog.
        const val STALL_FAIL_TIMEOUT_MS: Long = 60_000L

        /**
         * Issue #2409 — one-way latency applied to the ATTACH phase only (see
         * [coldAttachWhoseSeedLadderIsSaturatedStillCompletesInsteadOfSurrendering]).
         *
         * An exec-lane round-trip is roughly `3 x RTT + fixed` = `6 x latency +
         * ~0.4s`. Measured at the cold-dial 350ms severity a seed capture costs
         * ≈2.5s — right ON the seed ceiling, which is exactly why the outcome
         * flipped with the host. 600ms puts it at ≈4s: unambiguously ABOVE the
         * 2.5s seed ceiling (every seed attempt times out) and unambiguously
         * BELOW the 6s reconcile ceiling (`list-panes` still succeeds, so the
         * link is slow, not wedged). Jitter is 0 on purpose — this fixture must
         * be deterministic, not realistic; the sibling case above owns realism.
         */
        const val ATTACH_SEED_LATENCY_MS: Int = 600

        /**
         * Issue #2409 (round 2) — the SETUP-phase one-way latency for
         * [coldAttachWhoseSeedLadderIsSaturatedStillCompletesInsteadOfSurrendering],
         * i.e. why its dial + enumeration run at the WiFi baseline rather than at
         * the cold-dial severity.
         *
         * Round 1 applied `addJitterLatency(350ms ±200ms)` before the activity
         * launch, mirroring the sibling. That made the case ~57 % red on the
         * emulator — and every failure was in SETUP, in the folder/session
         * enumeration, before the fault under test was applied:
         *
         * ```
         * W/PsFolderProbe: folder-list SSH exec read made no progress within 3500ms; ABANDONING
         * AssertionError: Timed out ... waiting for host-detail folder list ... folder-list:content=0
         * ```
         *
         * `FolderListGateway.execBounded` is a 3.5 s TOTAL budget for a
         * channel-open + exec + read, i.e. ~2–3 round-trips plus fixed cost —
         * `ToxiproxyControl.addMobileProfile`'s KDoc pins exactly this
         * arithmetic. At 350 ±200 ms one-way the RTT is 0.7–1.1 s, so that exec
         * costs ~2.5–3.5 s: sitting ON the cliff, with the cold-start
         * JIT/first-compose of `FolderListScreen` (13 MB of compiler allocation
         * in the failing logcat) enough to tip it over. The setup severity was
         * therefore an undeclared second fault, racing a budget this case does
         * not test.
         *
         * [ToxiproxyControl.WIFI_ONE_WAY_LATENCY_MS] is the documented
         * under-threshold extreme of that same variable (RTT ≈ 150 ms, "a
         * classify at this RTT never overruns the 3.5 s bound"): the enumeration
         * exec costs a few hundred ms against a 3.5 s budget, ~10× of margin. A
         * toxic is still INSTALLED, which matters — the attach-phase
         * `updateJitterLatency` retunes it IN PLACE on the established link, so
         * there is still no clean window for the round-trip under test to slip
         * through (that was round 1's reason for the in-place update, and it is
         * preserved).
         *
         * Dropping the setup toxic to zero would have been the other option; a
         * live-but-benign toxic keeps the in-place-retune property and is
         * strictly closer to the original intent.
         */
        const val SETUP_ONE_WAY_LATENCY_MS: Int = ToxiproxyControl.WIFI_ONE_WAY_LATENCY_MS

        /**
         * Issue #2409 (round 2) — bound for [settleFolderListToleratingRetry].
         * Comfortably covers two 3.5 s bounded-exec attempts plus the app's own
         * evict-and-retry, and stays well under the 300 s per-test ci-journey
         * watchdog. Matches the picker budget `openSessionFromList` uses next.
         */
        const val FOLDER_LIST_SETTLE_TIMEOUT_MS: Long = 60_000L

        /** Poll cadence for [settleFolderListToleratingRetry]. */
        const val FOLDER_LIST_SETTLE_POLL_MS: Long = 250L

        /**
         * At most two taps on the app's own Retry affordance. Enough to absorb a
         * cold-start stall; too few to hide a genuinely broken enumeration (which
         * would exhaust them and fall through to `openSessionFromList`'s
         * diagnostic failure).
         */
        const val FOLDER_LIST_MAX_RETRY_TAPS: Int = 2

        /**
         * Issue #2409 — the flat `ATTACH_PANES_READY_TIMEOUT_MS` this issue
         * replaced with a value derived from the stages it wraps. Used only as
         * the anti-vacuous floor for the saturated-seed case.
         */
        const val SUPERSEDED_ATTACH_CEILING_MS: Long = 12_000L
    }
}
