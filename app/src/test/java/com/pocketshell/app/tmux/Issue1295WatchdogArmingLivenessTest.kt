package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordedDiagnosticEvent
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1295 — the STEADY heal watchdog is the ONLY post-reveal recovery net for Claude's
 * incremental-repaint style, but it can be UNARMED / orphaned: a within-grace foreground
 * reattach does a one-shot reseed and (pre-#1295) armed NO watchdog, a superseded runtime
 * exits its loop, and a disconnect-recovery still settling on a transient band could leave
 * the recovered runtime watchdog-less. An idle Claude pane on such a runtime diverges
 * mid-session and stays BLACK forever.
 *
 * ## Deliverable 1 (blocking, diagnostics prerequisite) — POSITIVE liveness heartbeat
 *
 * Every `black_frame_observed` class is emitted only from INSIDE a watchdog tick / the reveal
 * gate, so when the watchdog is UNARMED (the #1295 bug) the export contains ZERO events —
 * indistinguishable from recording-off / eviction / backgrounded. #1295 first lands a POSITIVE
 * `watchdog_liveness` heartbeat that an armed, foregrounded, visible-pane watchdog emits each
 * tick; its ABSENCE alongside foreground+live evidence is the positive signature of the bug.
 * [livenessHeartbeatEmittedOnArmedForegroundVisibleTickAndAbsentWhenBackgrounded].
 *
 * ## Deliverable 2 — arm exactly one live watchdog per active attached runtime
 *
 * Red → green class coverage (G2), all three unarmed paths:
 *  - [withinGraceReattachRearmsSteadyWatchdogAndHealsIdlePane] — the LOAD-BEARING repro,
 *    reproducible TODAY via the within-grace reseed path: the connect-time watchdog is gone
 *    (simulating a superseded/exited/ceiling'd loop), the within-grace foreground reattach
 *    must RE-ARM a watchdog and it must HEAL the idle pane that later diverges. RED on base
 *    (reattach arms nothing → the diverged idle pane stays black); GREEN with the fix.
 *  - [withinGraceReattachDoesNotStackASecondWatchdog] — no double-arming (criterion 4): the
 *    reattach re-arm cancels the prior loop (arm-dedup), so A→B→A can never stack two loops;
 *    also proves the successor is NOT left watchdog-less.
 *  - [disconnectRecoveryTransientBandHandsOffSteadyWatchdog] — a disconnect-recovery blank
 *    watchdog that hits a TRANSIENT reconnecting band mid-window must hand off the lifetime
 *    stale-render net instead of bare-exiting into a permanent black. RED on base; GREEN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1295WatchdogArmingLivenessTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Deliverable 1 — POSITIVE liveness heartbeat, and its ABSENCE while backgrounded.
    // -------------------------------------------------------------------------

    @Test
    fun livenessHeartbeatEmittedOnArmedForegroundVisibleTickAndAbsentWhenBackgrounded() =
        runVmTest { sink ->
            val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
            val vm = connectVm(client)
            val pane = vm.panes.value.single { it.paneId == "%1" }
            vm.resizeRemotePty(80, 40)
            advanceUntilIdle()

            vm.setStaleRenderWatchdogMaxTicksForTest(1000)
            vm.setPaneLastOutputAtMsForTest("%1", 0L)
            vm.setProcessForegroundForClearedForTest(true)
            vm.setScreenInteractiveForTest(true)
            val guard = requireNotNull(vm.currentRuntimeGuardForTest())
            vm.armActivePaneStaleRenderWatchdogForTest(guard)
            runCurrent()

            // Three hot (4s) ticks while ARMED + foregrounded + visible => three heartbeats.
            advanceTimeBy(13 * 1000L)
            runCurrent()

            val foregroundBeats = sink.eventsNamed(WATCHDOG_LIVENESS_EVENT)
            assertTrue(
                "Issue #1295: an ARMED, foregrounded, visible-pane watchdog must emit a POSITIVE " +
                    "watchdog_liveness heartbeat each tick (found ${foregroundBeats.size})",
                foregroundBeats.size >= 3,
            )
            // The heartbeat carries the pane + runtime identity + timestamp an export needs to
            // convict the unarmed state.
            val beat = foregroundBeats.first()
            assertEquals("%1", beat.fields["paneId"])
            assertEquals("work", beat.fields["session"])
            assertEquals(guard.generation, beat.fields["generation"])
            assertNotNull("the heartbeat carries a runtime client identity", beat.fields["clientHash"])
            assertNotNull("the heartbeat carries a monotonic timestamp", beat.fields["atMs"])
            assertEquals(true, beat.fields["foreground"])
            assertEquals(true, beat.fields["screenOn"])

            // ABSENCE SIGNATURE: while BACKGROUNDED the tick skips its capture — so NO new
            // heartbeat is emitted. This is exactly the shape the export uses: a live-attached
            // runtime with foreground evidence but no heartbeat == the watchdog is not running.
            vm.setProcessForegroundForClearedForTest(false)
            val beatsBeforeBackground = sink.eventsNamed(WATCHDOG_LIVENESS_EVENT).size
            advanceTimeBy(13 * 1000L)
            runCurrent()
            assertEquals(
                "Issue #1295: a BACKGROUNDED watchdog tick emits NO heartbeat — its absence is " +
                    "the positive signature the export correlates against foreground+live evidence",
                beatsBeforeBackground,
                sink.eventsNamed(WATCHDOG_LIVENESS_EVENT).size,
            )
            // Guard the pane var is genuinely on screen for the whole window (not torn down).
            assertFalse(pane.terminalState.visibleScreenIsBlank())
        }

    // -------------------------------------------------------------------------
    // Deliverable 2 (LOAD-BEARING, red→green) — within-grace foreground reattach must RE-ARM
    // the steady watchdog when the connect-time one is gone, and that watchdog must HEAL the
    // idle pane that diverges mid-session AFTER the reattach.
    // -------------------------------------------------------------------------

    @Test
    fun withinGraceReattachRearmsSteadyWatchdogAndHealsIdlePane() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        // Sticky matching capture so the reattach reseed + healthy ticks are non-urgent no-ops
        // until the divergence below; the recovery frame is queued explicitly at heal time.
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        vm.markActiveLeaseWarmForTest()

        // The connect-time steady watchdog is NOT running — the exact #1295 bug condition (a
        // superseded runtime exited its loop / a prior disconnect-recovery left it dead / the
        // lifetime tick-ceiling elapsed over a long session). `connectVm` disabled auto-arm, so
        // nothing armed one at connect; assert the precondition explicitly.
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no steady watchdog is running before the within-grace reattach",
                it == null || it.isCancelled,
            )
        }

        // Re-enable production auto-arm so the reattach path is the SOLE arming opportunity
        // under test (the connect-time watchdog is gone).
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        // The real within-grace background→foreground reattach (the reseed-only fast path over
        // the warm client). Drive it, then complete the reseed coroutine WITHOUT advancing
        // virtual time (so the watchdog parks at its first 4s delay rather than draining).
        vm.onAppForegrounded(resumedWithinGrace = true)
        repeat(5) { runCurrent() }

        // LOAD-BEARING (a): the within-grace reattach must have RE-ARMED the steady watchdog.
        // RED on base — the reseed-only path arms nothing, so this stays null.
        val armed = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1295: the within-grace foreground reattach MUST re-arm the steady stale-" +
                "render watchdog (the sole post-reveal net for an idle Claude pane). job=$armed",
            armed != null && armed.isActive,
        )

        // The idle pane diverges mid-session: an alt-screen overpaint wipes the viewport to a
        // lone spinner line while tmux still holds the authoritative frame (Claude idle only
        // repaints the spinner, so it never self-heals). tmux's grid now carries the rich frame.
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "IDLE-SPINNER-ONLY\r\n").toByteArray(Charsets.US_ASCII),
        )
        assertFalse(
            "precondition: the diverged pane does NOT yet carry the recovered frame",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = idleClaudeRecoveredFrame(), isError = false),
        )

        // LOAD-BEARING (b): the re-armed watchdog captures tmux's grid, sees the divergence, and
        // repaints the diverged idle pane within one tick. RED on base — no watchdog is armed, so
        // the pane stays stranded on the diverged frame forever.
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertTrue(
            "Issue #1295: the re-armed watchdog must repaint the diverged idle pane from tmux's " +
                "grid — never leave it stranded on a live transport",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
    }

    // -------------------------------------------------------------------------
    // Deliverable 2 (refinement — beyond-grace PINNED resume, red→green) — the pinned
    // beyond-grace foreground resume ([reseedActivePaneOnLivePinnedForeground], the #1181
    // path) is a ONE-SHOT reseed over a still-live port-forward-pinned client with NOTHING
    // pending, and (pre-#1295) armed NO steady watchdog. On a long-lived pinned "always-on"
    // connection the connect-time watchdog can be gone (lifetime tick-ceiling elapsed over a
    // long session), so this resume must RE-ARM the single lifetime net, and that watchdog
    // must HEAL an idle pane that later diverges. RED on base (the pinned reseed arms nothing
    // → the diverged idle pane stays black forever); GREEN with the `:3934` re-arm.
    //
    // Distinct from [withinGraceReattachRearmsSteadyWatchdogAndHealsIdlePane]: that drives the
    // WITHIN-grace reseed path (`resumedWithinGrace=true` → `:4084`); this drives the
    // BEYOND-grace PINNED path (`resumedWithinGrace=false`, nothing pending → `:3934`), which
    // no other test reaches — every prior new test short-circuits at the within-grace gate.
    // -------------------------------------------------------------------------

    @Test
    fun pinnedBeyondGraceResumeRearmsSteadyWatchdogAndHealsIdlePane() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        // Sticky matching capture so the pinned reseed + healthy ticks are non-urgent no-ops
        // until the divergence below; the recovery frame is queued explicitly at heal time.
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Precondition: this is the pinned beyond-grace state — the `-CC` client is still
        // LIVE and NOTHING is pending to replay (the pin suppressed the grace teardown, so
        // nothing was ever stashed). onAppForegrounded(false) therefore lands on
        // reseedActivePaneOnLivePinnedForeground() (the sole beyond-grace path that, pre-fix,
        // drives a reseed but armed no watchdog).
        assertFalse(
            "precondition: the live pinned resume has NO pendingReattach",
            vm.hasPendingReattachForTest(),
        )

        // The connect-time steady watchdog is NOT running — the exact #1295 bug condition (a
        // long-lived pinned session whose connect-time watchdog hit its lifetime tick-ceiling).
        // connectVm disabled auto-arm, so nothing armed one at connect; assert the precondition.
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no steady watchdog is running before the pinned beyond-grace resume",
                it == null || it.isCancelled,
            )
        }

        // Re-enable production auto-arm so the pinned-resume path is the SOLE arming
        // opportunity under test (the connect-time watchdog is gone).
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        // The real notification-tap foreground return BEYOND grace onto the live pinned client
        // with nothing pending → reseedActivePaneOnLivePinnedForeground(). Complete the reseed
        // coroutine WITHOUT advancing virtual time (so the re-armed watchdog parks at its first
        // 4s delay rather than draining).
        vm.onAppForegrounded(resumedWithinGrace = false)
        repeat(8) { runCurrent() }

        // LOAD-BEARING (a): the pinned beyond-grace resume must have RE-ARMED the steady
        // watchdog (`:3934`). RED on base — the pinned reseed-only path arms nothing, so this
        // stays null and the idle pane below can never heal.
        val armed = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1295: the pinned beyond-grace foreground resume MUST re-arm the steady " +
                "stale-render watchdog for the still-live pinned runtime (the sole post-reveal " +
                "net for an idle Claude pane). job=$armed",
            armed != null && armed.isActive,
        )

        // The idle pane diverges mid-session: an alt-screen overpaint wipes the viewport to a
        // lone spinner line while tmux still holds the authoritative frame (Claude idle only
        // repaints the spinner, so it never self-heals).
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "IDLE-SPINNER-ONLY\r\n").toByteArray(Charsets.US_ASCII),
        )
        assertFalse(
            "precondition: the diverged pane does NOT yet carry the recovered frame",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = idleClaudeRecoveredFrame(), isError = false),
        )

        // LOAD-BEARING (b): the re-armed watchdog captures tmux's grid, sees the divergence,
        // and repaints the diverged idle pane within one tick. RED on base — no watchdog is
        // armed, so the pane stays stranded on the diverged frame forever.
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertTrue(
            "Issue #1295: the re-armed pinned-resume watchdog must repaint the diverged idle " +
                "pane from tmux's grid — never leave it stranded on a live transport",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
    }

    // -------------------------------------------------------------------------
    // Deliverable 2 (criterion 4 + "successor not orphaned") — the reattach RE-ARM cancels the
    // prior loop (arm-dedup), so A→B→A can never stack two watchdogs for one runtime, and the
    // successor is never left watchdog-less.
    // -------------------------------------------------------------------------

    @Test
    fun withinGraceReattachDoesNotStackASecondWatchdog() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        vm.markActiveLeaseWarmForTest()
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        // Simulate the connect-time watchdog W1 running (the bypass seam arms regardless of the
        // auto-arm flag). Then a within-grace reattach must re-arm — but as a SINGLE loop.
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        val first = requireNotNull(vm.staleRenderWatchdogJobForTest())
        assertTrue("W1 is active before the reattach", first.isActive)

        // Production auto-arm on, then the real within-grace reattach.
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.onAppForegrounded(resumedWithinGrace = true)
        repeat(5) { runCurrent() }

        val second = requireNotNull(vm.staleRenderWatchdogJobForTest())
        assertTrue(
            "Issue #1295: the within-grace reattach must CANCEL the prior watchdog loop (arm-" +
                "dedup) so A→B→A can never stack two concurrent loops for one runtime",
            first.isCancelled,
        )
        assertTrue("the successor runtime has a single ACTIVE watchdog (never orphaned)", second.isActive)
        assertFalse("the reattach produced a FRESH loop, not a re-use of W1", first === second)
    }

    // -------------------------------------------------------------------------
    // Deliverable 2 (criterion b + criterion 4, red→green) — a GENUINE A→B→A multi-session
    // switch (supersede): each `connect()` to a distinct session is a real runtime supersede
    // (a NEW connectGeneration AND a NEW `-CC` client, verified below), NOT a same-runtime
    // reattach. The successor runtime must arm exactly one steady watchdog (never left
    // watchdog-less), the prior runtime's loop must be cancelled (arm-dedup — no stacking),
    // and switching BACK to A must re-arm A so its idle pane heals. RED on base if the
    // supersede arming (`:6545`/`:7041`) or the #1166 arm-dedup (`:11624`) is removed.
    // -------------------------------------------------------------------------

    @Test
    fun genuineSessionSwitchAToBToASupersedeArmsExactlyOneWatchdogPerRuntimeAndHealsA() =
        runVmTest { _ ->
            // A fresh durable `-CC` client on EVERY connect (so clientRef genuinely changes on
            // each switch — a real supersede, not a warm-runtime reuse). Records every client
            // so the test can assert distinct runtime identities.
            val handedOutClients = mutableListOf<FakeTmuxClient>()
            val vm = connectSwitchVm { requested ->
                durableSessionClient(requested).also { handedOutClients.add(it) }
            }

            // ---- Connect A (the first live runtime) ------------------------------------
            switchTo(vm, "session-a")
            val guardA = requireNotNull(vm.currentRuntimeGuardForTest())
            val watchdogA = requireNotNull(
                vm.staleRenderWatchdogJobForTest(),
            ) {
                "connect A's reveal must arm the steady watchdog for runtime A"
            }
            assertTrue("runtime A has one ACTIVE armed watchdog", watchdogA.isActive)
            assertTrue("guard A is the current runtime", vm.isCurrentRuntimeForTest(guardA))

            // ---- Switch A→B (a genuine supersede: new generation + new client) ---------
            switchTo(vm, "session-b")
            val guardB = requireNotNull(vm.currentRuntimeGuardForTest())
            val watchdogB = requireNotNull(
                vm.staleRenderWatchdogJobForTest(),
            ) {
                "Issue #1295 (criterion b): the SUPERSEDING runtime B must NOT be left " +
                    "watchdog-less — its reveal must arm the steady watchdog"
            }

            // Prove this is an ACTUAL supersede (not the same-runtime reattach proxy the prior
            // test covers): B carries a DIFFERENT generation AND a DIFFERENT `-CC` client, and A
            // is no longer the current runtime.
            assertTrue(
                "the switch bumped connectGeneration (genuine supersede, not a reattach): " +
                    "A=${guardA.generation} B=${guardB.generation}",
                guardB.generation != guardA.generation,
            )
            assertFalse(
                "the switch swapped the `-CC` client (genuine supersede): A and B share a client",
                guardA.client === guardB.client,
            )
            assertFalse(
                "runtime A is superseded — no longer the current runtime after the switch",
                vm.isCurrentRuntimeForTest(guardA),
            )

            // (i) EXACTLY ONE armed watchdog for the successor, and A's loop is gone (arm-dedup:
            // the successor arm CANCELLED A's loop — no two stacked loops for the two runtimes).
            assertTrue("successor runtime B has one ACTIVE armed watchdog", watchdogB.isActive)
            assertFalse("the switch produced a FRESH loop for B, not A's", watchdogA === watchdogB)
            assertTrue(
                "Issue #1295 (criterion 4): runtime A's watchdog loop must be CANCELLED by the " +
                    "supersede arm-dedup — the switch may never leave A's loop stacked alongside B's",
                watchdogA.isCancelled,
            )

            // ---- Switch B→A (back to A: the warm cached-runtime activation, re-armed) ----
            switchTo(vm, "session-a")
            val guardA2 = requireNotNull(vm.currentRuntimeGuardForTest())
            val watchdogA2 = requireNotNull(
                vm.staleRenderWatchdogJobForTest(),
            ) {
                "Issue #1295: switching BACK to A must re-arm A's steady watchdog"
            }
            assertTrue(
                "the switch back to A bumped generation again (fresh runtime): " +
                    "B=${guardB.generation} A2=${guardA2.generation}",
                guardA2.generation != guardB.generation,
            )
            assertTrue("(ii) runtime A is re-armed with one ACTIVE watchdog", watchdogA2.isActive)
            assertTrue(
                "Issue #1295 (criterion 4): runtime B's watchdog loop must be CANCELLED when we " +
                    "switch back to A — never stacked with A's re-armed loop",
                watchdogB.isCancelled,
            )
            assertFalse("the re-arm produced a FRESH loop, not a re-use of B's", watchdogB === watchdogA2)

            // (iii) Across the whole A→B→A journey exactly ONE watchdog loop is ever active: the
            // single `staleRenderWatchdogJob` field always holds the current runtime's loop, and
            // every prior runtime's loop is cancelled. No runtime is left with a second loop.
            val allLoops = listOf(watchdogA, watchdogB, watchdogA2)
            assertEquals(
                "exactly ONE watchdog loop is active after the A→B→A switch — no stacking",
                1,
                allLoops.count { it.isActive },
            )

            // Settle the warm-switch's cached-runtime remote refresh (a 1ms-delayed reconcile)
            // BEFORE staging the divergence, so its reconcile can't consume the recovered
            // capture we queue below. This tiny advance keeps A2's watchdog parked (≪ 4s tick).
            advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS + 10)
            runCurrent()
            assertTrue(
                "A2's watchdog stays armed after the warm-switch refresh settles",
                vm.staleRenderWatchdogJobForTest()?.isActive == true,
            )

            // (ii, heal) the re-armed runtime A heals an idle pane that diverges after the switch
            // back — proving the re-armed watchdog is a LIVE net, not merely a launched job.
            vm.setPaneLastOutputAtMsForTest("%1", 0L)
            vm.setProcessForegroundForClearedForTest(true)
            vm.setScreenInteractiveForTest(true)
            val pane = vm.panes.value.single { it.paneId == "%1" }
            pane.terminalState.appendRemoteOutput(
                (CLEAR_ONLY + "IDLE-SPINNER-ONLY\r\n").toByteArray(Charsets.US_ASCII),
            )
            assertFalse(
                "precondition: A's diverged pane does NOT yet carry the recovered frame",
                renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
            )
            // Swap in the recovered frame as the sticky capture on the client the re-armed A2
            // watchdog captures against. The warm switch BACK to session-a reactivated its CACHED
            // runtime, so A2's client is the cached session-a `-CC` client (guardA2.client), NOT a
            // fresh factory client.
            (guardA2.client as FakeTmuxClient).defaultCaptureResponse =
                CommandResponse(number = 91L, output = idleClaudeRecoveredFrame(), isError = false)
            advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
            runCurrent()
            assertTrue(
                "Issue #1295: the re-armed runtime-A watchdog must heal A's diverged idle pane " +
                    "from tmux's grid after the switch back",
                renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
            )
        }

    // -------------------------------------------------------------------------
    // Deliverable 2 (criterion c, red→green) — disconnect-recovery: a blank watchdog that hits
    // a TRANSIENT reconnecting band mid-window (a recovery still settling on a live client)
    // must hand off the lifetime stale-render net instead of bare-exiting into a permanent
    // black. RED on base (bare exit → no watchdog); GREEN with the hand-off.
    // -------------------------------------------------------------------------

    @Test
    fun disconnectRecoveryTransientBandHandsOffSteadyWatchdog() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)

        // The blank watchdog is armed the way the silent-reattach / reconnect nets arm it
        // (surfaceErrorOnExhaustion=false). Enable production stale auto-arm so the hand-off can
        // fire; the connect-time watchdog is already gone (connectVm disabled auto-arm).
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no steady watchdog is armed before the blank-watchdog hand-off",
                it == null || it.isCancelled,
            )
        }

        // The recovery is still SETTLING: a transient reconnecting band over a still-live client
        // + current runtime (the disconnect-recovery gap). The blank watchdog's first tick sees
        // not-Connected.
        vm.forceInlineReconnectingBandForTest()
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 100)
        runCurrent()

        // RED on base: the blank watchdog bare-exited on not-Connected → the recovered runtime
        // is left with NO post-reveal net. GREEN with the fix: it handed off the lifetime net.
        val staleJob = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1295: a blank watchdog that hits a TRANSIENT reconnecting band on a still-" +
                "live client must hand off the lifetime stale-render watchdog (not bare-exit into " +
                "a permanent black). job=$staleJob",
            staleJob != null && staleJob.isActive,
        )
    }

    // ------------------------------------------------------------------ Harness

    private fun idleClaudeRecoveredFrame(): List<String> = buildList {
        add("IDLE-CLAUDE-RECOVERED")
        repeat(27) { add("recovered context row $it with real tmux content") }
    }

    private fun renderedTranscriptFor(pane: TmuxPaneState): String {
        val state = pane.terminalState
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    private fun runVmTest(body: suspend TestScope.(RecordingSink) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        val sink = installRecordingDiagnosticSink()
        try {
            body(RecordingSink(sink))
        } finally {
            sink.close()
            for (vm in createdVms) {
                runCatching { vm.setProcessForegroundForClearedForTest(false) }
                runCatching { vm.clearForTest() }
            }
            advanceUntilIdle()
            createdVms.clear()
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private class RecordingSink(
        private val delegate: com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink,
    ) {
        fun eventsNamed(name: String): List<RecordedDiagnosticEvent> = delegate.eventsNamed(name)
    }

    private fun TestScope.newVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
        // Keep connect()'s reveal from auto-arming its own watchdogs so the arming path under
        // test is the SOLE opportunity; individual tests re-enable auto-arm as needed.
        it.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        createdVms.add(it)
    }

    private fun TestScope.connectVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
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
        advanceUntilIdle()
        return vm
    }

    /**
     * A VM whose STALE-render watchdog auto-arm is LEFT ENABLED (so the connect/switch reveal
     * arms the steady watchdog through the REAL production path, `:6545`/`:7041`) — the blank
     * watchdog is silenced to keep capture traffic to the one reveal capture. Does NOT connect;
     * [switchTo] drives each genuine `connect()` switch. [clientFactory] is invoked per attach
     * with the requested session name so each switch gets a FRESH `-CC` client (a real
     * supersede, not a warm reuse).
     */
    private fun TestScope.connectSwitchVm(
        clientFactory: (String) -> FakeTmuxClient,
    ): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = registry,
            runtimeCache = TmuxSessionRuntimeCache(),
            sshLeaseManager = leaseManager,
            sessionLifecycleSignals = null,
        ).also {
            it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
            it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
            it.setStaleRenderWatchdogMaxTicksForTest(1000)
            createdVms.add(it)
        }
        runCurrent()
        vm.setTmuxClientFactoryForTest { _, requested, _ -> clientFactory(requested) }
        return vm
    }

    /**
     * Drive a genuine session switch: the real `connect()` to [session] (a distinct session on
     * the same host), then drain the reveal WITHOUT advancing virtual time past the steady
     * watchdog's first 4s tick — so each switch's re-armed watchdog PARKS at its first delay
     * (inspectable) instead of draining its 10k-tick loop under `advanceUntilIdle`.
     */
    private fun TestScope.switchTo(vm: TmuxSessionViewModel, session: String) {
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = session,
        )
        repeat(40) { runCurrent() }
    }

    /**
     * A durable per-session `-CC` client fixture: a repeating stream of `list-panes` + capture
     * rows (pane `%1`) so the attach/switch reconcile loop can iterate without running dry, plus
     * a sticky matching capture so steady watchdog ticks are non-urgent no-ops until a test
     * queues a diverging frame.
     */
    private fun durableSessionClient(sessionName: String): FakeTmuxClient =
        FakeTmuxClient().apply {
            // Captures are served from the STICKY [defaultCaptureResponse] (not a FIFO queue) so
            // every seed/reconcile/watchdog capture reads the same non-blank frame — and so the
            // heal test can swap in the recovered frame via `defaultCaptureResponse` without
            // fighting a deep pre-queued FIFO. Only `list-panes` + cursor replies are queued
            // (durably) for the attach/switch reconcile loop.
            defaultCaptureResponse =
                CommandResponse(number = 80L, output = listOf("$sessionName ready"), isError = false)
            repeat(16) {
                responses.addLast(
                    CommandResponse(
                        number = 1L,
                        output = listOf("%1\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                        isError = false,
                    ),
                )
                cursorQueryResponses.addLast(
                    CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
                )
            }
        }

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
        title: String = sessionName,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$title\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

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

    private companion object {
        // ESC[2J ESC[H — a full clear + home, the overpaint that blacks a viewport.
        const val CLEAR_ONLY: String = "[2J[H"
    }
}
