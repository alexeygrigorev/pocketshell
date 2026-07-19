package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionTestControl
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1681 — the DETERMINISTIC reproduction of the mobile-network
 * self-inflicted lease-close storm (#1680 Track B / H1), keyed on the
 * SELF-INFLICTED signal (G6), on the real connected + Docker + toxiproxy path.
 *
 * ## The maintainer's report (2026-07-18, D33)
 *
 * *"on WiFi it's okayish but when I'm on mobile network it starts breaking —
 * even though everything else works fine."* The storm mechanism (#1680 Track B):
 * on mobile latency a bounded remote exec exceeds the **3.5 s** budget → a
 * bounded-exec site `close()`s the SHARED per-host tmux `-CC` lease → the reader
 * EOFs → the manager mis-reads its OWN self-inflicted EOF as a genuine remote
 * death → reconnect → reattach → reconcile re-classifies → timeout → close →
 * repeat. WiFi RTT stays under 3.5 s so it mostly doesn't trip; mobile RTT
 * spikes over it. #1641 (merged) routes those bounded execs through
 * [com.pocketshell.app.ssh.BoundedSessionExec], which ABANDONS the slow exec and
 * leaves the shared transport untouched — turning the storm into quiet.
 *
 * ## Why this is deterministic (and NOT a server-side sleep — the #1680 thesis)
 *
 * The overrun is caused by NETWORK RTT against the REAL production 3.5 s
 * constant — no widened band, no test-only timing in the assertion path (G6).
 * The toxiproxy `network-fault-proxy` (host 2228 → `agents:22`) applies a
 * symmetric latency toxic so a single SSH round-trip costs ~2 × one-way:
 *
 *  - **Mobile pin** ([ToxiproxyControl.addMobileProfile], RTT ≈ 1.8 s): a
 *    bounded exec is a multi-round-trip channel-open + exec + read (~5 s), so it
 *    overruns the 3.5 s budget, while the `-CC` liveness probe (5 s, a single
 *    round-trip refresh-client), keepalive (30 s), tmux command (10 s), and the
 *    8 s TransportDispatcher per-op ceiling all stay green — ONLY the storm
 *    threshold crosses. (See [ToxiproxyControl.addMobileProfile] for why the RTT
 *    is ~1.8 s, not the recipe's ~4.0 s: at 4.0 s a raw exec trips the 8 s
 *    transport ceiling, confounding the classify's 3.5 s attribution.)
 *  - **WiFi pin** (RTT ≈ 150 ms, [wifiBaselineNoStormNoReconnect]): the classify
 *    completes far under the bound — zero overruns, zero reconnects.
 *
 * These two pinned extremes bracket the monotonic RTT-vs-3.5 s variable, which
 * is strictly stronger than N random samples (the #1633 both-extremes method).
 *
 * ## The self-inflicted signal is CONSTRUCTIVE (the honest key — G6)
 *
 * Pre-#1641 the close was SILENT (no log, no cause-trail), so the RED side
 * cannot key on a breadcrumb. Instead the harness ONLY ever applies a *latency*
 * toxic — it never disables the proxy, never blackholes, never severs the link —
 * so the link is *delayed but never cut*, AND an UN-PROXIED [SshSentinel]
 * exec-pings the fixture SSH port (2222, no toxiproxy) throughout, hard-proving
 * the host + sshd + network stayed perfectly healthy. Under that invariant **any
 * observed lease death is SELF-INFLICTED by construction**, not a real remote
 * drop — that is the "everything else works fine" control the maintainer
 * described, made mechanical.
 *
 * ## Deterministic trigger (confirmed-shell classify over the SHARED `-CC` lease)
 *
 * Only ONE #1680 bounded-exec site borrows the SHARED `-CC` lease the live
 * reader rides: `agent_kind_classify` (`resolveForeignKindGuess` runs
 * [com.pocketshell.app.agents.AgentKindRemoteSource] over the same `sessionRef`).
 * Its overrun is the storm-bearing one — pre-#1641 its `close()` kills the reader.
 * (The RED run PROVED `session_cards_rpc` uses a SEPARATE lease: with the shim
 * restored, its overrun did NOT storm. So keying the fidelity/attribution
 * assertions on ANY `bounded_exec_timeout` would let A2 pass vacuously on RED —
 * they key strictly on `agent_kind_classify`.)
 *
 * The seeded session is pinned `@ps_agent_kind=shell` (confirmed-shell —
 * [setSessionShellKind]), the exact pane class #1641 named as the storm's
 * uncredited entry trigger: it re-runs the classify over the shared lease
 * whenever the pane's `(cwd, command, tty)` input changes
 * ([com.pocketshell.app.tmux.TmuxSessionViewModel] `startAgentDetectionForPane`
 * / `refreshForeignGuessForConfirmedShellPane`). After the mobile profile is
 * applied, [forceReclassifyOverDegradedLink] changes the ACTIVE pane's cwd and
 * forces an active-window reconcile, so the next classify runs over the degraded
 * `-CC` lease and overruns.
 *
 * ## Red → green (validating both the repro and #1641 — G1/D33)
 *
 * The RED is driven by a DETERMINISTIC #780-model synthetic seam (issue #1693),
 * NOT the old flaky "restore the v0.4.38 `close()` shim" revert. That shim-revert
 * stormed only ~1/3 of runs because the modern `RealSshSession.close()` is
 * idempotent + async + `isCloseInitiated`-tagging + lease-refcount-aware
 * (#1135/#1139/#1222/#1567), so it no longer reliably reaches the `-CC` reader —
 * a flaky RED is not a clean D33 gate. The seam
 * ([com.pocketshell.app.ssh.BoundedSessionExec.onTimeoutSyntheticActionsForTest],
 * armed ONLY for `agent_kind_classify` via [SYNTHETIC_SELF_CLOSE_ARG]) makes the
 * classify's REAL 3.5 s overrun force-kill the shared transport RAW and
 * SYNCHRONOUSLY ([SshSessionTestControl.forceTransportDeath]) — an anonymous
 * peer-style drop that EOFs the reader every RED run.
 *
 * - **RED** (run with `-Pandroid.testInstrumentationRunnerArguments.$SYNTHETIC_
 *   SELF_CLOSE_ARG=true`): the mobile-profile classify overruns 3.5 s → the armed
 *   seam force-kills the shared `-CC` lease → `-CC` reader EOF →
 *   `passive_disconnect classification=real_tmux_control_channel_closed`.
 *   [assertConnectedStaysNoSelfInflictedDrop] FAILS with that exact signature
 *   while the sentinel is still alive — DETERMINISTICALLY (20/20).
 * - **GREEN** (current main, seam DISARMED — the committed default): the bounded
 *   exec is abandoned, a `cause_trail stage=bounded_exec_timeout
 *   outcome=abandoned_transport_preserved transportAlive=true transportClosed=false`
 *   breadcrumb is recorded, status never leaves Connected, and a fresh marker
 *   still round-trips (20/20 no storm).
 *
 * The seam mechanism's determinism + strict `agent_kind_classify` keying is
 * pinned at per-push speed by
 * [com.pocketshell.app.ssh.Issue1693SyntheticSelfInflictedCloseSeamTest].
 *
 * ## Gate wiring
 *
 * This is a [NetworkFaultProofBase] toxiproxy proof — it self-skips on per-PR CI
 * ([assumeNetworkFaultProofsEnabled] → `tests.yml` leaves `network-fault-proxy`
 * / toxiproxy down), so wiring it into `scripts/ci-journey-suite.sh` would only
 * ALL-SKIP (the G3 trap). It runs in the phase-2 network-fault cohort of
 * `scripts/nightly-extensive-suite.sh` with the proxy family up, alongside its
 * `NatIdleMappingSurvivalE2eTest` / `PacketLossNetworkFaultE2eTest` siblings.
 *
 * There is NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on any load-bearing
 * assertion (D31/D32 F3): the two `assumeNetworkFaultProofsEnabled()` gates only
 * un-gate the opt-in Docker fixture, never the storm assertion itself.
 *
 * @see com.pocketshell.app.ssh.BoundedSessionExec
 * @see com.pocketshell.app.agents.AgentKindRemoteSource
 * @see SlowClassifyKeepsSharedLeaseJourneyDockerTest
 */
// CI_JOURNEY_SUITE_JUSTIFIED: NetworkFaultProofBase toxiproxy proof; gated by
// assumeNetworkFaultProofsEnabled() (self-skips on CI since tests.yml does not
// start network-fault-proxy:2228). This is a heavy phase-2 fault-injection
// reproduction — too slow for the per-push journey suite. Its durable gate is
// the nightly phase-2 NETWORK_FAULT_CLASSES cohort
// (scripts/nightly-extensive-suite.sh) alongside its ReconnectStormLivelockE2eTest
// / NatIdleMappingSurvivalE2eTest / PacketLossNetworkFaultE2eTest siblings —
// wiring it into ci-journey-suite.sh would only produce a vacuous CI skip.
@RunWith(AndroidJUnit4::class)
class MobileLatencyStormSelfInflictedCloseE2eTest : NetworkFaultProofBase() {

    private var diagnostics: RecordingDiagnosticSink? = null
    private val sentinelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sentinel: SshSentinel? = null

    /** Every distinct connection status this run projected, sampled continuously. */
    private val observedStatuses = mutableSetOf<String>()

    @Before
    fun installDiagnostics() {
        clearLastSessionPrefs()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDownStorm() {
        // Issue #1693 — never leak the process-global synthetic-close seam onto a
        // sibling test on the shared AVD (the KeepAliveTestOverride teardown rule).
        BoundedSessionExec.onTimeoutSyntheticActionsForTest = emptyMap()
        runCatching { sentinel?.stop() }
        runCatching { sentinelScope.cancel() }
        runCatching { toxiproxy().reset() }
        runCatching { diagnostics?.close() }
        diagnostics = null
        clearLastSessionPrefs()
    }

    /**
     * Issue #1693 — arm the DETERMINISTIC synthetic self-inflicted-close seam when
     * the RED reproduction is requested via [SYNTHETIC_SELF_CLOSE_ARG]. Keyed
     * STRICTLY on `agent_kind_classify` (the ONE bounded-exec site that rides the
     * shared `-CC` lease); `session_cards_rpc` and every other site are untouched,
     * so the RED cannot storm through a separate lease (the #1681 finding). When
     * the arg is absent (the committed GREEN default) this is a no-op and the real
     * #1641 abandon path runs. Returns whether the seam was armed.
     */
    private fun armSyntheticSelfInflictedCloseIfRequested(): Boolean {
        val requested = InstrumentationRegistry.getArguments()
            .getString(SYNTHETIC_SELF_CLOSE_ARG)
            ?.toBooleanStrictOrNull() == true
        if (requested) {
            BoundedSessionExec.onTimeoutSyntheticActionsForTest = mapOf(
                CLASSIFY_CALLER_SITE to { session: SshSession ->
                    SshSessionTestControl.forceTransportDeath(session)
                },
            )
        }
        return requested
    }

    /**
     * THE #1681 PROOF (RED-detector / GREEN load-bearer). Under the pinned MOBILE
     * RTT profile, a confirmed-shell classify runs over the delayed-but-alive
     * `-CC` lease and overruns its 3.5 s bound. On the pre-#1641 build this
     * SELF-INFLICTS a lease close (storm); on current main it is abandoned and
     * the session stays Connected.
     */
    @Test
    fun mobileLatencyStormIsSelfInflicted() {
        runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "ms${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1681-mobile-$marker"
        val hostName = "Issue1681 Mobile $marker"

        // (1) Seed a healthy proxy + a foreign shell session, and pin it
        //     confirmed-shell (@ps_agent_kind=shell) so the classify re-fires on
        //     pane-input change — the exact #1641 storm-entry trigger.
        prepareProxyAndRemoteSession(key, sessionName, readyText = "ISSUE1681-READY-$marker")
        setSessionShellKind(key, sessionName)
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        // (2) THE SENTINEL — the un-proxied "everything else works fine" control.
        sentinel = openUnProxiedSentinel(key, sentinelScope)

        // (3) A pre-latency latency-probe connection through the PROXY (2228),
        //     handshaked while the link is still healthy, so the fidelity check
        //     never fights a slow handshake (#1681 attribution note).
        val latencyProbe = openProxyLatencyProbe(key)
        try {
            // (4) Attach on the HEALTHY link → Connected. The first classify fires
            //     fast here and caches; the degraded-link re-fire is forced below.
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToSession(hostRowTag, hostName, sessionName)
            waitForVisibleTerminalText("initial attach") { "ISSUE1681-READY-$marker" in it }
            waitForConnected("initial attach")

            diagnostics!!.clear()
            observedStatuses.clear()

            // (4b) Issue #1693 — arm the DETERMINISTIC synthetic self-inflicted
            //      close (RED only; committed GREEN default leaves it disarmed).
            //      Keyed strictly on `agent_kind_classify`; fires on the classify's
            //      REAL mobile-RTT overrun below.
            val redArmed = armSyntheticSelfInflictedCloseIfRequested()

            // (5) DEGRADE the link to the mobile profile (RTT ≈ 1.8 s).
            toxiproxy().addMobileProfile()

            // (6) A1 — FIXTURE FIDELITY (hard, no assumeTrue). Prove the mobile
            //     profile makes the EXACT bounded-exec command overrun the 3.5 s
            //     bound while the host is still ALIVE. A too-mild profile FAILS
            //     here rather than passing vacuously later (G3/G10).
            assertMobileProfileOverrunsClassify(latencyProbe)

            // (7) Drive the confirmed-shell classify to re-run over the degraded
            //     `-CC` lease (cwd change + active-window reconcile) several times
            //     while continuously sampling status + diagnostics, THEN observe a
            //     long storm-settle window. The classify's close() is ASYNC
            //     (#1139/#1144), so on RED the `-CC` EOF / passive_disconnect can
            //     land seconds after the overrun — the settle window is generous so
            //     that self-inflicted storm reliably lands in-window (a short tail
            //     let a late-firing classify's async close storm AFTER the window,
            //     a false pass). On GREEN nothing ever storms, so the extra wait is
            //     simply quiet.
            val start = SystemClock.elapsedRealtime()
            repeat(RECLASSIFY_KICKS) { kick ->
                forceReclassifyOverDegradedLink(key, sessionName, kick)
                observeWindow(PER_KICK_OBSERVE_MS)
            }
            observeWindow(STORM_SETTLE_MS)
            val elapsed = SystemClock.elapsedRealtime() - start

            // (8) A2 — THE SELF-INFLICTED DETECTOR (the LOAD-BEARING assertion; RED
            //     FAILS here, GREEN passes): no passive_disconnect / reconnect_fail,
            //     status never left Connected, and the sentinel proves it was NOT
            //     the link. Asserted FIRST because on the pre-#1641 RED build the
            //     classify's close is SILENT (no breadcrumb — the exact silent-
            //     close #1641 credited), so the RED signal is the lease DEATH here,
            //     not a breadcrumb. The classify-overran fidelity is proven
            //     build-independently at (6) by the latency probe.
            assertConnectedStaysNoSelfInflictedDrop(elapsed)

            // (9) A1b — GREEN NON-VACUITY: on GREEN the classify overran the 3.5 s
            //     bound over the SHARED lease and was ABANDONED (not silently
            //     closed) — the `agent_kind_classify` breadcrumb the silent close
            //     never wrote. (Only reached on GREEN, since RED fails at A2.)
            val breadcrumbs = classifyTimeoutBreadcrumbs()
            assertTrue(
                "A1b GREEN NON-VACUITY: no `agent_kind_classify` bounded_exec_timeout breadcrumb — " +
                    "the shared-lease classify never overran/abandoned on GREEN, so the storm window " +
                    "was vacuous. The trigger did not re-fire the confirmed-shell classify over the " +
                    "degraded lease. cause_trails=" +
                    "${diagnostics!!.eventsNamed(ReconnectCauseTrail.NAME).map { it.fields }}",
                breadcrumbs.isNotEmpty(),
            )

            // (10) A3 — GREEN ATTRIBUTION: the abandonment is attributable to the
            //      classify with the transport PRESERVED (asserted exactly as
            //      SlowClassifyKeepsSharedLeaseJourneyDockerTest does).
            val crumb = breadcrumbs.first()
            assertEquals(
                "A3: the abandoned bounded exec must record transportClosed=false — the shared lease " +
                    "was preserved, not torn down. crumb=${crumb.fields}",
                false,
                crumb.fields["transportClosed"],
            )
            assertEquals(
                "A3: the abandoned bounded exec must record the transport was still ALIVE when we " +
                    "walked away. crumb=${crumb.fields}",
                true,
                crumb.fields["transportAlive"],
            )

            // (11) A4 — GREEN LEASE LIVENESS: the shared `-CC` lease SURVIVED the
            //      storm and now processes cleanly on the restored link. Restore
            //      the healthy link, re-confirm Connected (the lease a
            //      self-inflicted close would have killed is still up), drive ONE
            //      more shared-lease refresh (a sibling-session `%sessions-changed`)
            //      and confirm it completes with NO new self-inflicted close and
            //      NO overrun — proving the lease is alive and usable, keyed on the
            //      lease itself (NOT the terminal-view render, whose storm-window
            //      render-heal state is a #1495-class concern orthogonal to the
            //      self-inflicted CLOSE this issue targets).
            toxiproxy().clearToxics()
            waitForConnected("post-storm healthy link")
            val healthyBaselineBreadcrumbs = classifyTimeoutBreadcrumbs().size
            forceReclassifyOverDegradedLink(key, sessionName, RECLASSIFY_KICKS)
            observeWindow(HEALTHY_SETTLE_MS)
            assertTrue(
                "A4 LEASE LIVENESS: the shared `-CC` lease left Connected after the link was " +
                    "restored (status=${currentConnectionStatus()}) — the lease did not survive the " +
                    "storm as a usable connection.",
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "A4 LEASE LIVENESS: a shared-lease refresh over the RESTORED healthy link still " +
                    "overran the 3.5 s bound (new bounded_exec_timeout breadcrumbs) — the lease is " +
                    "not processing cleanly. baseline=$healthyBaselineBreadcrumbs now=" +
                    "${classifyTimeoutBreadcrumbs().size}",
                healthyBaselineBreadcrumbs,
                classifyTimeoutBreadcrumbs().size,
            )
            assertTrue(
                "A4 LEASE LIVENESS: a passive_disconnect fired on the restored healthy link — the " +
                    "lease is not usable. events=${diagnostics!!.eventsNamed("passive_disconnect").map { it.fields }}",
                diagnostics!!.eventsNamed("passive_disconnect").isEmpty(),
            )

            writeSummary(
                testName = "MobileLatencyStormSelfInflictedClose-mobile",
                lines = listOf(
                    "session=$sessionName",
                    "marker=$marker",
                    "profile=mobile RTT≈${ToxiproxyControl.MOBILE_RTT_MS}ms " +
                        "(one_way=${ToxiproxyControl.MOBILE_ONE_WAY_LATENCY_MS}ms)",
                    "synthetic_self_inflicted_close_armed=$redArmed",
                    "reclassify_kicks=$RECLASSIFY_KICKS",
                    "bounded_exec_timeout_breadcrumbs=${breadcrumbs.size}",
                    "sentinel_pings=${sentinel?.pingCount} attempts=${sentinel?.attemptCount} " +
                        "alive=${sentinel?.isAlive}",
                    "observed_statuses=${observedStatuses.joinToString("|")}",
                    "passive_disconnects=${diagnostics!!.eventsNamed("passive_disconnect").size}",
                    "reconnect_fails=${diagnostics!!.eventsNamed("reconnect_fail").size}",
                    "expectation=Connected stays, no self-inflicted drop, breadcrumb attributable, " +
                        "marker round-trips, sentinel alive",
                ),
            )
        } finally {
            runCatching { runBlocking { latencyProbe.close() } }
        }
        }
    }

    /**
     * A5 — THE WiFi BASELINE (the #1633 under-threshold extreme). Same journey at
     * WiFi RTT (≈ 150 ms): the classify completes far under the 3.5 s bound, so
     * there is ZERO overrun AND ZERO reconnect — proving the mobile-arm assertion
     * constrains the THRESHOLD, not "reconnects are banned universally".
     */
    @Test
    fun wifiBaselineNoStormNoReconnect() {
        runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "wf${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1681-wifi-$marker"
        val hostName = "Issue1681 WiFi $marker"

        prepareProxyAndRemoteSession(key, sessionName, readyText = "ISSUE1681-WIFI-READY-$marker")
        setSessionShellKind(key, sessionName)
        val hostRowTag = seedNetworkFaultHost(key, hostName)
        sentinel = openUnProxiedSentinel(key, sentinelScope)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachToSession(hostRowTag, hostName, sessionName)
        waitForVisibleTerminalText("initial attach") { "ISSUE1681-WIFI-READY-$marker" in it }
        waitForConnected("initial attach")

        diagnostics!!.clear()
        observedStatuses.clear()

        // Degrade only to the WiFi profile (RTT ≈ 150 ms — under the 3.5 s bound).
        toxiproxy().addSymmetricLatency(ToxiproxyControl.WIFI_ONE_WAY_LATENCY_MS)

        val start = SystemClock.elapsedRealtime()
        repeat(RECLASSIFY_KICKS) { kick ->
            forceReclassifyOverDegradedLink(key, sessionName, kick)
            observeWindow(PER_KICK_OBSERVE_MS)
        }
        observeWindow(STORM_SETTLE_MS)
        val elapsed = SystemClock.elapsedRealtime() - start

        // A5.1: ZERO bounded_exec_timeout breadcrumbs — no bounded exec overran at WiFi RTT.
        val breadcrumbs = classifyTimeoutBreadcrumbs()
        assertTrue(
            "A5: a bounded_exec_timeout breadcrumb fired at WiFi RTT " +
                "(${ToxiproxyControl.WIFI_ONE_WAY_LATENCY_MS}ms one-way) — no bounded exec must " +
                "overrun the 3.5 s bound on WiFi. breadcrumbs=${breadcrumbs.map { it.fields }}",
            breadcrumbs.isEmpty(),
        )
        // A5.2: ZERO reconnects, status never left Connected (same detector as A2).
        assertConnectedStaysNoSelfInflictedDrop(elapsed)

        writeSummary(
            testName = "MobileLatencyStormSelfInflictedClose-wifi",
            lines = listOf(
                "session=$sessionName",
                "profile=wifi RTT≈${ToxiproxyControl.WIFI_ONE_WAY_LATENCY_MS * 2}ms",
                "bounded_exec_timeout_breadcrumbs=${breadcrumbs.size}",
                "sentinel_alive=${sentinel?.isAlive}",
                "observed_statuses=${observedStatuses.joinToString("|")}",
                "expectation=zero overrun, zero reconnect (under-threshold extreme)",
            ),
        )
        }
    }

    // -- assertions --------------------------------------------------------------------

    /**
     * A1 — HARD fixture fidelity. Run the EXACT `pocketshell agents kind` command
     * shape the app runs, over the pre-established PROXY connection while the
     * mobile latency toxic is active, and time it: it MUST take strictly longer
     * than the app's 3.5 s bound. No `assumeTrue` — a too-mild profile FAILS here
     * (the #847/G10 happy-fixture lesson), never passes vacuously.
     *
     * The exec is UN-bounded here (unlike the app's `BoundedSessionExec`), so at
     * this RTT it either (a) returns after > 3.5 s with a valid `{"results":...}`
     * envelope (host alive, just slow — the ideal proof), or (b) is killed by the
     * 8 s TransportDispatcher per-op ceiling — which ALSO proves it overran the
     * 3.5 s bound. Both branches are valid overrun proof; host aliveness is
     * independently hard-proven by the un-proxied [SshSentinel].
     */
    private suspend fun assertMobileProfileOverrunsClassify(probe: SshSession) {
        val requestJson = """{"panes":[{"pane_id":"%0","pane_pid":1}]}"""
        val command = "printf %s ${shellQuote(requestJson)} | { " +
            PocketshellCommand.wrap("agents kind") + " ; }"
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { probe.exec(command) }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val thrown = result.exceptionOrNull()
        assertTrue(
            "A1 FIXTURE FIDELITY: `pocketshell agents kind` finished in ${elapsedMs}ms over the " +
                "mobile profile, which is NOT slower than the app's ${CLASSIFY_BOUND_MS}ms bound. A " +
                "sub-bound classify can never overrun, so it cannot reproduce the storm-entry edge " +
                "(the #847/G10 happy-fixture lesson). Check network-fault-proxy is up and the mobile " +
                "toxic is applied. thrown=${thrown?.javaClass?.simpleName}",
            elapsedMs > CLASSIFY_BOUND_MS,
        )
        // When the un-bounded exec DID return (did not hit the 8 s ceiling), it
        // must be a valid, ALIVE envelope — slow, not failing.
        val ok = result.getOrNull()
        if (ok != null) {
            assertTrue(
                "A1: the slow classify returned but not a valid `{\"results\":...}` envelope; " +
                    "exit=${ok.exitCode} stdout='${ok.stdout.take(200)}' stderr='${ok.stderr.take(200)}'",
                ok.exitCode == 0 && ok.stdout.contains("\"results\""),
            )
        }
    }

    /**
     * A2 — THE SELF-INFLICTED DETECTOR. The link was only DELAYED (never cut) and
     * the sentinel proves the host stayed healthy, so any lease death here is
     * self-inflicted. RED fails here (the shim's close → `passive_disconnect
     * classification=real_tmux_control_channel_closed`); GREEN is the load-bearing
     * pass (status stayed Connected across the whole storm window).
     */
    private fun assertConnectedStaysNoSelfInflictedDrop(elapsedMs: Long) {
        // The sentinel HARD-PROVES the host + network stayed healthy — this makes
        // any observed drop self-inflicted by construction.
        val s = sentinel
        assertTrue(
            "the un-proxied sentinel must have exec-pinged the healthy host throughout; " +
                "attempts=${s?.attemptCount} pings=${s?.pingCount} failures=${s?.failureLog}",
            s != null && s.attemptCount > 0 && s.isAlive,
        )

        val passive = diagnostics!!.eventsNamed("passive_disconnect")
        assertEquals(
            "A2 SELF-INFLICTED STORM: ${passive.size} passive_disconnect event(s) fired while a " +
                "merely-SLOW classify ran over the shared lease and the un-proxied sentinel stayed " +
                "alive (host healthy). On the pre-#1641 build these carry " +
                "classification=real_tmux_control_channel_closed — the self-inflicted close mis-read " +
                "as a real drop. events=${passive.map { it.fields }}",
            0,
            passive.size,
        )
        val reconnectFails = diagnostics!!.eventsNamed("reconnect_fail")
        assertEquals(
            "A2 SELF-INFLICTED STORM: ${reconnectFails.size} reconnect_fail event(s) fired on a " +
                "proven-up (merely-delayed) link. events=${reconnectFails.map { it.fields }}",
            0,
            reconnectFails.size,
        )
        val nonConnected = observedStatuses.filter { it != "Connected" }
        assertTrue(
            "A2 SELF-INFLICTED STORM: the connection left Connected (observed=$observedStatuses) " +
                "while a merely-SLOW classify ran over a delayed-but-alive link (elapsed=${elapsedMs}ms). " +
                "That is the mobile-network storm — the classify's self-close flapping the status.",
            nonConnected.isEmpty(),
        )
        assertTrue(
            "the session must be Connected at the end of the storm window; " +
                "status=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    /**
     * The `agent_kind_classify` `bounded_exec_timeout` breadcrumbs — the ONE
     * bounded-exec site that rides the SHARED `-CC` lease (`resolveForeignKindGuess`
     * over `sessionRef`), so its overrun is the STORM-bearing one the RED v0.4.38
     * shim self-closes into a `-CC` reader EOF. Keyed strictly on this callerSite
     * (NOT any `bounded_exec_timeout`): the RED run proved a `session_cards_rpc`
     * overrun uses a SEPARATE lease and does NOT storm, so accepting it would let
     * A2 pass vacuously on RED (G3/G6). The confirmed-shell state
     * ([setSessionShellKind] → `@ps_agent_kind=shell`) is the class this covers —
     * the exact pane class #1641 named as the storm's uncredited entry trigger.
     */
    private fun classifyTimeoutBreadcrumbs(): List<RecordedDiagnosticEvent> =
        diagnostics!!.eventsNamed(ReconnectCauseTrail.NAME).filter {
            it.fields["callerSite"] == CLASSIFY_CALLER_SITE &&
                it.fields["stage"] == BOUNDED_EXEC_TIMEOUT_STAGE
        }

    // -- triggers / helpers ------------------------------------------------------------

    /**
     * Re-fire the foreign `AgentKindRemoteSource.classify` — the ONE bounded-exec
     * site that borrows the SHARED `-CC` lease (`resolveForeignKindGuess` runs it
     * over the same `sessionRef` the live `-CC` reader rides), so its overrun is
     * the storm-bearing one: pre-#1641 the classify's `close()` kills the reader.
     * (A `session_cards_rpc` overrun uses a SEPARATE lease — its close does NOT
     * kill the `-CC` reader, proven by the RED run — so it is NOT a valid storm
     * fidelity signal and A1/A3 key strictly on `agent_kind_classify`.)
     *
     * Two moves, both over the UN-PROXIED port so the setup itself is not delayed:
     *  1. Change the ACTIVE pane's cwd to a DISTINCT directory (`mkdir -p … ; cd …`)
     *     so the pane's `(cwd, command, tty)` input triple genuinely changes — the
     *     trigger [refreshForeignGuessForConfirmedShellPane] busts the one-shot
     *     confirmed-shell guess cache on.
     *  2. A background split + `kill-pane -a` (`-d`, keeping the ACTIVE pane %0)
     *     emits a `%layout-change` on the ATTACHED window, driving a full
     *     [reconcilePanes] → `applyParsedPanes` re-read of %0's cwd →
     *     `startAgentDetectionForPane` → the confirmed-shell classify over the
     *     now-delayed shared `-CC` lease. (`kill-pane -a` keeps %0, so the app
     *     stays on the attached pane — verified via the `-active-pane` reveal
     *     log staying on %0.)
     */
    private suspend fun forceReclassifyOverDegradedLink(key: String, sessionName: String, kick: Int) {
        val s = shellQuote(sessionName)
        // (1) distinct cwd in the active pane
        execRemoteDirect(
            key,
            "tmux send-keys -t $s " +
                shellQuote("mkdir -p /tmp/k1681-$kick; cd /tmp/k1681-$kick") + " Enter",
        )
        SystemClock.sleep(RECONCILE_SETTLE_MS)
        // (2) active-window structural churn -> full pane reconcile that re-reads
        //     %0's cwd, keeping the app on %0 (`-d` + `kill-pane -a`).
        execRemoteDirect(
            key,
            "tmux split-window -d -t $s 2>/dev/null; sleep 0.4; " +
                "tmux kill-pane -a -t $s 2>/dev/null || true",
        )
    }

    /** Record `@ps_agent_kind=shell` on the seeded session so the app treats it confirmed-shell. */
    private suspend fun setSessionShellKind(key: String, sessionName: String) {
        val result = execRemoteDirect(
            key,
            "tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell; echo kind_set",
        )
        assertTrue(
            "expected to set @ps_agent_kind=shell; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0 && result.stdout.contains("kind_set"),
        )
    }

    /** A direct, UN-PROXIED exec on the fixture SSH port (2222) — never delayed. */
    private suspend fun execRemoteDirect(key: String, command: String): ExecResult =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command) } }.getOrThrow()

    /** A PROXY (2228) connection handshaked BEFORE the latency toxic, for the fidelity probe. */
    private suspend fun openProxyLatencyProbe(key: String): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = NETWORK_FAULT_SSH_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    private fun observeWindow(durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            sampleStatus()
            SystemClock.sleep(POLL_MS)
        }
        sampleStatus()
    }

    private fun sampleStatus() {
        val status = currentConnectionStatus()
        observedStatuses += status::class.simpleName ?: status.toString()
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

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val POLL_MS: Long = 250L

        /** The app's bounded-exec ceiling — `AgentKindRemoteSource.EXEC_READ_TIMEOUT_MS`. */
        const val CLASSIFY_BOUND_MS: Long = 3_500L

        /** Cause-trail identifiers stamped by #1641 ([com.pocketshell.app.ssh.BoundedSessionExec]). */
        const val BOUNDED_EXEC_TIMEOUT_STAGE: String = "bounded_exec_timeout"

        /**
         * The SHARED-`-CC`-lease bounded-exec site — the storm-bearing one
         * ([com.pocketshell.app.agents.AgentKindRemoteSource]). The RED run proved
         * `session_cards_rpc` uses a separate lease and does NOT storm, so the
         * fidelity/attribution assertions key strictly on this callerSite.
         */
        const val CLASSIFY_CALLER_SITE: String = "agent_kind_classify"

        /**
         * Issue #1693 — instrumentation arg that arms the DETERMINISTIC synthetic
         * self-inflicted-close seam for the RED reproduction. Absent (the committed
         * GREEN default) → the real #1641 abandon path runs and nothing storms. Set
         * `true` → the classify's real mobile-RTT overrun force-kills the shared
         * `-CC` lease every run. Enable with
         * `-Pandroid.testInstrumentationRunnerArguments.pocketshellSyntheticSelfInflictedClose=true`.
         */
        const val SYNTHETIC_SELF_CLOSE_ARG: String = "pocketshellSyntheticSelfInflictedClose"

        /** How many degraded-link classify kicks to fire (RED self-sustains after the first). */
        const val RECLASSIFY_KICKS: Int = 4

        /** Settle after a cwd change before the structural churn that forces the reconcile. */
        const val RECONCILE_SETTLE_MS: Long = 1_000L

        /** A4 settle window after restoring the healthy link (one clean shared-lease refresh). */
        val HEALTHY_SETTLE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 18_000L else 12_000L

        val PER_KICK_OBSERVE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 16_000L else 12_000L

        /**
         * Long post-kick storm-settle window. On RED the classify's ASYNC close
         * (#1139/#1144) can land the `-CC` EOF / passive_disconnect several seconds
         * after the last overrun, so this is generous enough that the self-inflicted
         * storm reliably lands in-window (avoiding the false pass a short tail gave).
         */
        val STORM_SETTLE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 35_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
    }
}
