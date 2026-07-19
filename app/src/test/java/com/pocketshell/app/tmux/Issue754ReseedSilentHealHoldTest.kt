package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #754 (REOPENED, D31/D33/G10) — the within-grace foreground RESEED path must ride
 * through a link-blip drop WITHOUT painting the "Attaching…" overlay.
 *
 * ## The reported defect (nightly-red 2026-07, on-device)
 *
 * On a within-grace foreground return over a still-warm `-CC` lease the driver-owned
 * RESEED-only path ([TmuxSessionViewModel.launchForegroundReattachReseed]) runs a
 * `capture-pane` reseed. Under a blackhole blip the link is still cut, so a foregrounded
 * probe / `capture-pane` surfaces a `TransportDropped` — foregrounded, so NOT suppressed —
 * which walks the controller `Live → Reattaching`. Because the reseed path (unlike its
 * sibling [TmuxSessionViewModel.launchForegroundHealWithinGrace]) NEVER armed
 * `revealController.setSilentHealInFlight(true)`, the [com.pocketshell.core.connection.RevealStateMachine]
 * projects that Reattaching as [RevealState.Seeding] → the screen paints the
 * `TMUX_SWITCHING_LOADING_TAG` "Attaching…" overlay over the live frame
 * (`expected:<0> but was:<1>` in the release-gating fault suite).
 *
 * ## The fix these tests pin
 *
 * The reseed path now arms the silent-heal reveal/status hold for the bounded grace window
 * (mirroring the heal path). While armed, a Reattaching projection HOLDS the current live
 * frame (no overlay) and the status stays the calm `Connected`. The hold is released after
 * the grace window so a genuine BEYOND-grace drop still surfaces its reconnect band.
 *
 * ## Deterministic red→green (the #780 synthetic-injection model, per-PR gated JVM proof)
 *
 * The waived nightly toxiproxy proof ([com.pocketshell.app.proof.WithinGraceResumeRideThroughE2eTest],
 * `assumeFalse(isRunningOnCi())`) gave the regression zero per-PR coverage. This is the
 * deterministic gate: a live VM foregrounds within grace (the RESEED path), then a
 * confirmed drop is injected DURING the reseed window. On base the reveal flips to
 * [RevealState.Seeding] (overlay) → RED; with the fix the reveal HOLDS Live → GREEN.
 *
 * Negative (non-masking): after the bounded grace window elapses the hold is cleared, so a
 * later drop STILL surfaces [RevealState.Seeding] — arming silent-heal must never suppress a
 * real beyond-grace drop into silence.
 *
 * ## Determinism (PR #1674 flake fix — the #1633 class)
 *
 * The original harness rolled its own `StandardTestDispatcher` Main + a private
 * real-`Dispatchers.IO` `factoryScope` + a hand-rolled 5s wall-clock pump, and left the
 * VM's teardown / agent-kind-exec / seed-IO / reconcile / port-detection / session-card
 * dispatchers on their PRODUCTION real-`Dispatchers.IO` defaults. Under the full
 * `:app:testReleaseUnitTest` module run those real threads race the virtual scheduler, so
 * the reconnect ladder's progress (which hops through the real teardown scope) reached the
 * assertion at a nondeterministic point — the exact release-variant-only flake #1674 hit.
 *
 * This suite now extends [TmuxSessionViewModelTestBase] (like the sibling
 * [Issue1538ForegroundHealWithinGraceRideThroughTest], which exercises the SAME
 * within-grace foreground-heal path deterministically): every real-IO dispatcher is pinned
 * to the ONE shared virtual-clock scheduler and drained in `@After`, and the wall-clock
 * pump is replaced by the audited `awaitCondition` (`runCurrent`-only, never advances the
 * clock — so the bounded grace-window hold stays armed). The confirmed-dead ladder is
 * pinned to `[0, 60_000]` so it deterministically PARKS on `Reconnecting` (the honest
 * ongoing reconnect the hold rides through), instead of the old single `[0]` rung whose
 * exhaustion-vs-parked outcome depended on the real-IO teardown hop landing first.
 * The load-bearing assertions are UNCHANGED — still RED on base (Seeding overlay).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue754ReseedSilentHealHoldTest : TmuxSessionViewModelTestBase() {

    @Before
    fun disableLivenessAutoStart() {
        // The connect()-driven live seed reaches Connected, which would auto-start the
        // periodic liveness probe loop; disable it so the `awaitCondition` runCurrent-pump
        // that settles connect never chases a never-terminating re-arm (the #1517 class).
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
    }

    @After
    fun clearLivenessOverride() {
        LivenessProbeTestOverride.clear()
    }

    /**
     * Connect a live single-pane session that ends up LIVE with a seeded (non-blank) pane
     * and a WARM lease — exactly the within-grace foreground precondition
     * ([TmuxSessionViewModel.canReseedWithinGraceForeground] true).
     */
    private fun TestScope.connectLiveVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
        // Isolate the reseed/drop interaction under test: the connect()-auto-armed blank +
        // stale-render watchdogs would otherwise add their own capture-pane traffic and
        // re-arm forever under the virtual clock.
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        // Drive the connect to a seeded LIVE reveal. `advanceUntilIdle()` is safe HERE (the
        // grace hold + auto-reconnect ladder are not armed until the later foreground) and the
        // three periodic re-arm loops that would make it spin — the liveness probe (disabled in
        // @Before) and the blank + stale-render watchdogs (disabled above) — are all off. Every
        // real-IO dispatcher is pinned to the shared virtual scheduler by the base's newVm, so
        // this drains deterministically instead of racing a real thread (the #1674 flake).
        advanceUntilIdle()
        // The warm lease is the within-grace reseed precondition; pin it deterministically.
        vm.markActiveLeaseWarmForTest()
        return vm
    }

    private val richFrame: List<String> = buildList {
        add("╭──────────────────────────────────────────────╮")
        repeat(10) { i -> add("│  context line $i — real rendered content here  │") }
        add("╰──────────────────────────────────────────────╯")
    }

    private fun FakeTmuxClient.withRichInitialFrame(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = richFrame, isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun linkBlipDrop(): TmuxDisconnectEvent =
        TmuxDisconnectEvent(
            reason = TmuxDisconnectReason.ReaderEof,
            source = "link_blackhole",
            intent = "unknown",
        )

    // -----------------------------------------------------------------------
    // (1) LOAD-BEARING red→green: a drop DURING the within-grace reseed window must be
    //     ridden through silently — NO RevealState.Seeding (the "Attaching…" overlay), and
    //     the status stays the calm Connected. RED on base (Seeding + Reconnecting).
    // -----------------------------------------------------------------------

    @Test
    fun dropDuringWithinGraceReseedHoldsLiveRevealNoAttachingOverlay() = runTest(scheduler) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        // Precondition: the connect settled on a LIVE reveal (the frame the user is looking at).
        assertTrue(
            "precondition: connect reaches RevealState.Live before the foreground reseed; got " +
                "${vm.revealState.value}",
            vm.revealState.value is RevealState.Live,
        )
        // Large grace so the bounded hold stays armed for the whole (clock-not-advanced) window.
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 300_000L)
        vm.setProcessForegroundForClearedForTest(true)

        val diagnostics = installRecordingDiagnosticSink()
        try {
            // The within-grace foreground return → the driver-owned RESEED-only path.
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            // Non-vacuous: the reseed path actually ran (its cause-trail), not the heal path.
            assertTrue(
                "the within-grace foreground must take the driver-owned reseed_only path; trail=" +
                    "${diagnostics.eventsNamed("cause_trail")}",
                diagnostics.eventsNamed("cause_trail").any {
                    it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
                },
            )

            // Inject a CONFIRMED drop DURING the reseed window while foregrounded — the still-cut
            // link's probe/capture-pane surfacing a TransportDropped (foregrounded ⇒ NOT suppressed).
            client.markDisconnectedForTest(linkBlipDrop())
            awaitCondition {
                diagnostics.eventsNamed("passive_disconnect").isNotEmpty()
            }
            // Let the controller Reattaching projection reach the reveal collector.
            runCurrent()

            // LOAD-BEARING: the drop drove the controller Live→Reattaching, but the armed
            // silent-heal hold keeps the reveal on the LIVE frame — NO RevealState.Seeding, so
            // SwitchingLoadingPlaceholder ([TMUX_SWITCHING_LOADING_TAG]) never paints "Attaching…".
            // On base (no arm) the Reattaching projects Seeding → the #754 overlay → RED.
            assertFalse(
                "a drop during the within-grace reseed must be RIDDEN THROUGH — the reveal must " +
                    "NOT drop to RevealState.Seeding (the \"Attaching…\" overlay, #754); got " +
                    "${vm.revealState.value}",
                vm.revealState.value is RevealState.Seeding,
            )
            assertTrue(
                "the within-grace ride-through keeps the reveal on the held LIVE frame; got " +
                    "${vm.revealState.value}",
                vm.revealState.value is RevealState.Live,
            )
            // The status hold keeps the calm Connected (no Reconnecting bar) during the ride-through.
            assertTrue(
                "the within-grace ride-through keeps the calm Connected status (no Reconnecting " +
                    "band); got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            diagnostics.close()
        }
    }

    // -----------------------------------------------------------------------
    // (2) NON-MASKING (bounded hold): after the grace window elapses the hold is released, so a
    //     later drop STILL surfaces RevealState.Seeding — the arm must never suppress a real
    //     beyond-grace drop into silence.
    // -----------------------------------------------------------------------

    @Test
    fun dropAfterGraceWindowElapsesStillSurfacesSeedingOverlay() = runTest(scheduler) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        assertTrue(vm.revealState.value is RevealState.Live)
        // Small grace so the bounded hold clears when we advance past it.
        val graceMs = 1_000L
        vm.setPassiveDisconnectRecoveryForTest(graceMs = graceMs)
        vm.setProcessForegroundForClearedForTest(true)

        val diagnostics = installRecordingDiagnosticSink()
        try {
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            assertTrue(
                "precondition: the reseed_only path ran; trail=${diagnostics.eventsNamed("cause_trail")}",
                diagnostics.eventsNamed("cause_trail").any {
                    it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
                },
            )
            // Advance PAST the grace window so the bounded silent-heal hold is released.
            advanceTimeBy(graceMs + 500L)
            runCurrent()

            // A drop now (after the window) must surface normally — the hold is gone.
            client.markDisconnectedForTest(linkBlipDrop())
            awaitCondition {
                diagnostics.eventsNamed("passive_disconnect").isNotEmpty()
            }
            runCurrent()

            assertTrue(
                "after the grace window the hold must be released, so a real drop surfaces " +
                    "RevealState.Seeding (the reconnect/loading surface) — the arm must NOT mask a " +
                    "beyond-grace drop; got ${vm.revealState.value}",
                vm.revealState.value is RevealState.Seeding,
            )
            assertFalse(
                "a genuine post-window drop must NOT be held on the calm Connected status; got " +
                    "${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            diagnostics.close()
        }
    }

    // -----------------------------------------------------------------------
    // (3) CONFIRMED-DEAD / dispatch class (D31/G2): the reviewer's reopened gap. On a CLEAN
    //     CUT the `-CC` socket is already dead by foreground time, so
    //     `canReseedWithinGraceForeground()` DECLINES the reseed and the within-grace foreground
    //     takes the HEAL / dispatch path — NOT the reseed_only path. The single-shot heal
    //     fast-fails a dead/unreachable host and hands off to the loud auto-reconnect ladder,
    //     whose `Reconnecting` projection paints RevealState.Seeding ("Attaching…") — WITHIN
    //     grace — because the reveal hold was released the instant the heal job completed.
    //     LOAD-BEARING: the within-grace confirmed-dead foreground must RIDE THROUGH — NO
    //     RevealState.Seeding overlay while the honest reconnect runs underneath, for the whole
    //     bounded grace window. RED on base (Seeding), GREEN with the whole-window hold.
    // -----------------------------------------------------------------------

    @Test
    fun confirmedDeadWithinGraceForegroundHoldsLiveRevealNoAttachingOverlay() = runTest(scheduler) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        assertTrue(
            "precondition: connect reaches RevealState.Live before the confirmed-dead foreground; " +
                "got ${vm.revealState.value}",
            vm.revealState.value is RevealState.Live,
        )
        // Large grace so the bounded whole-window hold stays armed for the clock-not-advanced window.
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 300_000L)
        // A first immediate rung (fails) then a LONG-delayed second rung so the ladder deterministically
        // PARKS in `Reconnecting` (the honest ongoing reconnect over the dead host — the E2E
        // `proxy.disable()` state) rather than exhausting to Unreachable. A single `[0]` rung would
        // exhaust the ladder in one virtual-clock drain; the old harness only survived because the
        // real-`Dispatchers.IO` teardown hop froze the ladder mid-rung — the exact #1674 flake source.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 60_000L))
        // The clean-cut host is unreachable during the window: the heal's silent recovery AND the
        // ladder's re-dial fail, so the ladder STAYS Reconnecting — a deterministic, non-flaky
        // reproduction of the confirmed-dead honest reconnect.
        vm.forceUnrecoverableHostForTest = true

        val diagnostics = installRecordingDiagnosticSink()
        try {
            // --- The `-CC` socket dropped WHILE BACKGROUNDED (clean cut) ---
            // Backgrounded drops are DEFERRED to the single grace owner (no reveal change while
            // backgrounded), so the live frame is held into the foreground — the exact confirmed-dead
            // precondition. `client.disconnected` is now true, so the reseed gate correctly DECLINES.
            vm.setProcessForegroundForClearedForTest(false)
            client.markDisconnectedForTest(cleanCutDrop())
            // A backgrounded `-CC` drop records the canonical `passive_disconnect` breadcrumb but
            // the driver SUPPRESSES the controller submit (single grace owner) — so the controller
            // stays Live and the reveal holds the live frame (no Seeding while backgrounded).
            awaitCondition {
                diagnostics.eventsNamed("passive_disconnect").isNotEmpty()
            }
            runCurrent()
            assertTrue(
                "precondition: a backgrounded clean cut is DEFERRED — the live frame is held (no " +
                    "Seeding while backgrounded); got ${vm.revealState.value}",
                vm.revealState.value is RevealState.Live,
            )

            // --- Foreground return WITHIN grace over the now-dead `-CC` lease ---
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            // Non-vacuous: the reseed gate DECLINED (dead lease), so the within-grace foreground
            // took the HEAL / dispatch path, NOT the reseed_only path — the exact reopened class.
            awaitCondition {
                diagnostics.eventsNamed("cause_trail").any {
                    it.fields["stage"] == "foreground_reattach" &&
                        it.fields["outcome"] == "silent_heal_within_grace"
                }
            }
            assertTrue(
                "the confirmed-dead foreground must NOT take the reseed_only path (the dead lease " +
                    "declines it); trail=${diagnostics.eventsNamed("cause_trail")}",
                diagnostics.eventsNamed("cause_trail").none {
                    it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
                },
            )
            // Drive the heal to its FAILURE + the loud auto-reconnect ladder engaging — the exact
            // window the reopened overlay painted. On base the hold is already released here.
            awaitCondition {
                diagnostics.eventsNamed("auto_reconnect_decision").any {
                    it.fields["decision"] == "scheduled"
                }
            }
            runCurrent()

            // LOAD-BEARING: the confirmed-dead within-grace foreground must RIDE THROUGH — the
            // honest reconnect runs underneath but the reveal HOLDS the live frame: NO
            // RevealState.Seeding, so the "Attaching…" (TMUX_SWITCHING_LOADING_TAG) overlay never
            // paints. On base (the whole-window hold missing / released on the heal job) the
            // ladder's `Reconnecting` projects Seeding → the #754 overlay → RED.
            assertFalse(
                "a confirmed-dead within-grace foreground must be RIDDEN THROUGH — the reveal must " +
                    "NOT drop to RevealState.Seeding (the \"Attaching…\" overlay, #754 reopened); got " +
                    "${vm.revealState.value}",
                vm.revealState.value is RevealState.Seeding,
            )
            assertTrue(
                "the confirmed-dead ride-through keeps the reveal on the held LIVE frame; got " +
                    "${vm.revealState.value}",
                vm.revealState.value is RevealState.Live,
            )
            // The status hold keeps the calm Connected (no Reconnecting bar) across the window.
            assertTrue(
                "the confirmed-dead within-grace ride-through keeps the calm Connected status (no " +
                    "Reconnecting band); got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            diagnostics.close()
        }
    }

    // -----------------------------------------------------------------------
    // (4) NON-MASKING for the confirmed-dead class (bounded hold): after the grace window elapses
    //     a still-unreachable confirmed-dead host SURFACES RevealState.Seeding (the honest
    //     reconnect band) — the whole-window hold must never suppress a real BEYOND-grace outage
    //     into silence.
    // -----------------------------------------------------------------------

    @Test
    fun confirmedDeadAfterGraceWindowElapsesStillSurfacesSeedingOverlay() = runTest(scheduler) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        assertTrue(vm.revealState.value is RevealState.Live)
        val graceMs = 1_000L
        vm.setPassiveDisconnectRecoveryForTest(graceMs = graceMs)
        // A first immediate rung (fails) then a LONG-delayed second rung so the ladder stays in
        // `Reconnecting` (not exhausted to Unreachable) across the assertion window.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 60_000L))
        vm.forceUnrecoverableHostForTest = true

        val diagnostics = installRecordingDiagnosticSink()
        try {
            vm.setProcessForegroundForClearedForTest(false)
            client.markDisconnectedForTest(cleanCutDrop())
            awaitCondition {
                diagnostics.eventsNamed("passive_disconnect").isNotEmpty()
            }
            runCurrent()

            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            awaitCondition {
                diagnostics.eventsNamed("cause_trail").any {
                    it.fields["stage"] == "foreground_reattach" &&
                        it.fields["outcome"] == "silent_heal_within_grace"
                }
            }
            awaitCondition {
                diagnostics.eventsNamed("auto_reconnect_decision").any {
                    it.fields["decision"] == "scheduled"
                }
            }
            // WITHIN grace: the ongoing (failing) reconnect is HELD on the live frame (proves the
            // hold is genuinely masking the ladder — non-vacuous negative).
            runCurrent()
            assertFalse(
                "precondition: WITHIN grace the confirmed-dead reconnect must be HELD silent (no " +
                    "Seeding) — else the beyond-grace surfacing below proves nothing; got " +
                    "${vm.revealState.value}",
                vm.revealState.value is RevealState.Seeding,
            )
            // Advance PAST the grace window so the bounded whole-window hold is released.
            advanceTimeBy(graceMs + 500L)
            runCurrent()

            assertTrue(
                "after the grace window the whole-window hold must be released, so a still-unreachable " +
                    "confirmed-dead host SURFACES RevealState.Seeding (the honest reconnect surface) — " +
                    "the hold must NOT mask a beyond-grace outage; got ${vm.revealState.value}",
                vm.revealState.value is RevealState.Seeding,
            )
            assertFalse(
                "a genuine beyond-grace confirmed-dead outage must NOT be held on the calm Connected " +
                    "status; got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            diagnostics.close()
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun cleanCutDrop(): TmuxDisconnectEvent =
        TmuxDisconnectEvent(
            reason = TmuxDisconnectReason.ReaderEof,
            source = "clean_cut_confirmed_dead",
            intent = "unknown",
        )

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class AlwaysConnectedSession(
        val id: String,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }
}
