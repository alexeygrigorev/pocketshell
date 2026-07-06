package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1166 (battery/heat) — DURABLE JVM REGRESSION GUARD for the phone-heat fix:
 * gate the 4s stale-render `capture-pane` watchdog on foreground + screen-on and
 * back it off when the pane is stable, WITHOUT reducing heal reliability
 * (#1138/#1153/#874 must still heal within the same bound).
 *
 * ## The drain (#1164 audit)
 *
 * [TmuxSessionViewModel.armActivePaneStaleRenderWatchdog] fired a real `capture-pane`
 * SSH round-trip every [STALE_RENDER_WATCHDOG_TICK_MS] (4s) for the whole life of
 * every open session with NO foreground/background/screen-off gating and NO back-off
 * — a healthy unchanging pane paid ~15 captures/min forever, and rapid switching
 * stacked multiple loops (no arm-dedup).
 *
 * ## Red -> green
 *
 * Each test drives the REAL watchdog loop
 * ([TmuxSessionViewModel.armActivePaneStaleRenderWatchdogForTest]) on the virtual
 * test clock and counts `capture-pane` round-trips ([FakeTmuxClient.sentCommands]):
 *  - [backgroundedPaneDoesNotCapture] / [screenOffPaneDoesNotCapture]: on base
 *    (ungated loop) the watchdog captured every 4s regardless of lifecycle -> RED.
 *    With the fix the gate skips the round-trip entirely -> 0 captures -> GREEN.
 *  - [foregroundScreenOnResumesCapturing]: resuming foreground + screen-on captures
 *    again (immediate heal on return).
 *  - [stableForegroundPaneBacksOff]: a steady non-diverging pane widens 4s -> 8s ->
 *    16s so it stops hammering capture-pane.
 *  - [healthyStreamingPaneBacksOffInsteadOfThrashing]: issue #1219 steady-heat fix —
 *    a HEALTHY, continuously-STREAMING pane BACKS OFF instead of staying pinned at the
 *    hot 4s cadence. RED on base (any %output reset the back-off -> ~15 captures/60s);
 *    GREEN with the fix (~6 captures/60s). The #1164 "runs warm" lever.
 *  - [healthyStreamingOutputDoesNotCutBackedOffWaitShort]: the #1219 wake gate — a
 *    dense healthy pane's %output is IGNORED (no capture cuts the backed-off wait
 *    short), the direct counterpart of the black case below.
 *  - [fragmentsOverBlackStreamingPaneStillHealsWithinHotBound]: the LOAD-BEARING
 *    black-recovery guard — a fragments-over-black (#966/#1138/#1214) redraw on a
 *    STREAMING pane during a backed-off window STILL heals within ≤4s (its render is
 *    locally suspect, so its %output wakes the watchdog). Proves the steady-heat
 *    back-off did NOT blunt the oracle for a genuinely-black streaming pane.
 *  - [backedOffPartialBlackRedrawHealsWithinHotBound]: the #1138 no-black-screen bar —
 *    a partial-black redraw during a backed-off window heals within ≤4s, not up to 16s.
 *  - [divergingTickSnapsCadenceBackToHot]: a detected divergence (heal fired) snaps the
 *    cadence back to 4s so the following ticks stay hot (#1138/#1153 heal preservation).
 *  - [staleRenderWatchdogIntervalCurve]: the pure back-off curve (4/8/16, capped).
 *  - [rapidRearmCancelsPriorWatchdogLoop]: arm-dedup — a second arm cancels the first
 *    loop so rapid A->B->A switching can't stack concurrent 4s loops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StaleRenderWatchdogGatingTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) Back-off curve — the pure interval function. 4s -> 8s -> 16s, capped.
    // -------------------------------------------------------------------------

    @Test
    fun staleRenderWatchdogIntervalCurve() = runVmTest {
        val vm = connectVm(FakeTmuxClient().withSinglePaneRow("work", "%1"))
        assertEquals("0 stable ticks -> hot 4s", 4_000L, vm.staleRenderWatchdogIntervalMs(0))
        assertEquals("1 stable tick -> 8s", 8_000L, vm.staleRenderWatchdogIntervalMs(1))
        assertEquals("2 stable ticks -> 16s (cap)", 16_000L, vm.staleRenderWatchdogIntervalMs(2))
        assertEquals("3 stable ticks -> capped 16s", 16_000L, vm.staleRenderWatchdogIntervalMs(3))
        assertEquals("many stable ticks -> capped 16s", 16_000L, vm.staleRenderWatchdogIntervalMs(50))
    }

    // -------------------------------------------------------------------------
    // (2) FOREGROUND + SCREEN-ON GATE — backgrounded pane pays 0 captures.
    //     RED on base (ungated loop captured every 4s). GREEN: 0 captures.
    // -------------------------------------------------------------------------

    @Test
    fun backgroundedPaneDoesNotCapture() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = armIdleWatchdog(client, paneId = "%1")

        // App BACKGROUNDED (screen may be on, but nothing visible to heal).
        vm.setProcessForegroundForClearedForTest(false)
        vm.setScreenInteractiveForTest(true)

        val before = client.captureCount()
        // 6 ticks' worth of virtual time at the hottest cadence. Use advanceTimeBy
        // (NOT advanceUntilIdle, which would drain the bounded loop to maxTicks).
        advanceTimeBy(6 * STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        assertEquals(
            "REGRESSION (#1166): a BACKGROUNDED pane must pay ZERO stale-render " +
                "capture-pane round-trips (nothing on screen to heal). On base the " +
                "ungated watchdog captured every 4s.",
            before,
            client.captureCount(),
        )
    }

    @Test
    fun screenOffPaneDoesNotCapture() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = armIdleWatchdog(client, paneId = "%1")

        // App FOREGROUNDED but the SCREEN IS OFF -> still nothing visible to heal.
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(false)

        val before = client.captureCount()
        advanceTimeBy(6 * STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        assertEquals(
            "REGRESSION (#1166): a SCREEN-OFF pane must pay ZERO stale-render " +
                "capture-pane round-trips.",
            before,
            client.captureCount(),
        )
    }

    // -------------------------------------------------------------------------
    // (3) RESUME — foreground + screen-on resumes capturing (immediate heal on
    //     return). While gated: 0 captures; on the first ungated tick: captures.
    // -------------------------------------------------------------------------

    @Test
    fun foregroundScreenOnResumesCapturing() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = armIdleWatchdog(client, paneId = "%1")

        // Backgrounded window: 0 captures. advanceTimeBy (NOT advanceUntilIdle) so
        // the loop stays parked and can resume when we foreground it below.
        vm.setProcessForegroundForClearedForTest(false)
        vm.setScreenInteractiveForTest(true)
        val gatedBefore = client.captureCount()
        advanceTimeBy(4 * STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertEquals(
            "no captures while backgrounded",
            gatedBefore,
            client.captureCount(),
        )

        // Return to foreground + screen-on: the watchdog resumes and captures.
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val resumeBefore = client.captureCount()
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        assertTrue(
            "REGRESSION (#1166): on foreground + screen-on the watchdog must resume " +
                "and capture again (heal a pane that changed while away). Captured " +
                "${client.captureCount() - resumeBefore} times after resume.",
            client.captureCount() > resumeBefore,
        )
    }

    // -------------------------------------------------------------------------
    // (4) BACK-OFF WHEN STABLE — a steady non-diverging pane widens its cadence,
    //     so over a fixed window it pays fewer captures than a flat 4s loop would.
    // -------------------------------------------------------------------------

    @Test
    fun stableForegroundPaneBacksOff() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Issue #1294: model a CONFIRMED-healthy idle pane — a DENSE, fully-rendered viewport
        // (not the 1-line "work ready" partial-blank, which now reads SUSPECT and correctly
        // stays hot). Each watchdog tick's empty capture over a confidently-dense render scores
        // HEALTHY (a non-urgent no-op), so the #1219/#1164 battery back-off (4s->8s->16s) holds.
        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertFalse(
            "precondition: the idle pane is confidently DENSE (not suspect), so an empty " +
                "capture scores HEALTHY and the pane backs off",
            pane.terminalState.visibleRenderMayHaveLostFrame(),
        )

        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        val before = client.captureCount()
        // Captures land at t=4s (#1, stableTicks->1), t=12s (#2, ->2), t=28s (#3, cap).
        // advanceTimeBy (NOT advanceUntilIdle, which would run the loop to maxTicks).
        advanceTimeBy(20 * 1000L + 100)
        runCurrent()

        val captures = client.captureCount() - before
        assertEquals(
            "REGRESSION (#1166/#1294): a STABLE (confirmed-healthy) foreground pane must back " +
                "off (4s->8s->16s). Over 20s it should capture 2 times (t=4s, t=12s), NOT the 5 " +
                "a flat 4s cadence would pay. Observed $captures.",
            2,
            captures,
        )
    }

    // -------------------------------------------------------------------------
    // (5) STEADY-HEAT FIX (issue #1219) — a HEALTHY, continuously-STREAMING pane
    //     must BACK OFF (4s -> 8s -> 16s) instead of thrashing capture-pane at the
    //     hot 4s cadence forever. This is the #1164 "runs warm" lever: a live agent
    //     pane emitting %output steadily paid an SSH capture-pane round-trip every 4s
    //     during the heaviest-use foreground window.
    //
    //     RED (base): ANY %output reset the back-off (stableTicks -> 0 via
    //     `hadNewOutput`, and the wake cut every backed-off wait short), so a
    //     streaming pane never left the hot 4s cadence -> ~15 captures over 60s ->
    //     the `captures <= 8` assertion FAILS. GREEN (#1219): a CONFIDENTLY-DENSE
    //     healthy render ignores the %output wakes and the oracle backs off, so the
    //     same 60s window pays ~6 captures.
    // -------------------------------------------------------------------------

    @Test
    fun healthyStreamingPaneBacksOffInsteadOfThrashing() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Paint a DENSE, fully-rendered viewport (32 of 40 rows live) so the pane is
        // CONFIDENTLY HEALTHY — a real continuously-streaming agent pane, NOT blank /
        // partial-black / fragments-over-black.
        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertFalse(
            "precondition: a dense pane must NOT read as a possible lost frame " +
                "(else the wake gate would keep it hot)",
            pane.terminalState.visibleRenderMayHaveLostFrame(),
        )

        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        // The watchdog captures return an EMPTY frame each tick (no queued response),
        // so healActivePaneIfStaleRender no-ops (healed=false) and never repaints —
        // the dense render is preserved as HEALTHY the whole window.
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Continuously stream fresh %output on the ACTIVE pane once a second — faster
        // than the hot 4s tick — driving the REAL output path end-to-end so the
        // output -> wake wire is exercised. On base every one of these reset the
        // back-off / cut the wait short, pinning the hot cadence.
        val before = client.captureCount()
        val windowMs = 60 * 1000L
        var elapsed = 0L
        while (elapsed < windowMs) {
            advanceTimeBy(1000L)
            runCurrent()
            vm.recordPaneOutputActivityForTest("%1")
            runCurrent()
            elapsed += 1000L
        }
        val captures = client.captureCount() - before

        // A hot 4s cadence over 60s pays ~15 captures; backed off (t=4,12,28,44,60)
        // pays ~5-6. Assert the fix meaningfully cut the steady-state rate.
        assertTrue(
            "REGRESSION (#1219 steady-heat): a HEALTHY continuously-streaming pane " +
                "must BACK OFF (4s->8s->16s), NOT thrash capture-pane at the hot 4s " +
                "cadence. Over 60s a hot loop pays ~15 captures; backed off ~6. " +
                "Observed $captures.",
            captures <= 8,
        )
    }

    // -------------------------------------------------------------------------
    // (5b) A FRAGMENTS-OVER-BLACK redraw on a STREAMING pane during a BACKED-OFF
    //      window STILL HEALS within the hot bound (issue #1219 must NOT regress
    //      the #1138/#966/#1214 black-screen recovery). A redraw on device arrives
    //      AS %output, so once the render goes suspect (black) the next %output wakes
    //      the backed-off watchdog -> the divergence is captured + healed within ≤4s,
    //      not up to 16s later. This is the LOAD-BEARING black-recovery guard: it must
    //      pass GREEN with the fix, proving the steady-heat back-off did not blunt the
    //      oracle for a genuinely-black streaming pane.
    // -------------------------------------------------------------------------

    @Test
    fun fragmentsOverBlackStreamingPaneStillHealsWithinHotBound() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Issue #1294: the pane STREAMS a DENSE, confidently-healthy frame first — the "S" in
        // "STREAMING pane". Each backoff-phase tick's empty capture over this dense render
        // scores HEALTHY (a non-urgent no-op), so the pane backs off (a partial-blank pane
        // would now correctly stay hot). The fragments-over-black redraw below then makes it
        // suspect.
        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        // Let the watchdog back off to the widest (16s) interval — captures at t=4s,
        // t=12s (empty capture over a confidently-dense render => HEALTHY no-op).
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        val before = client.captureCount()
        advanceTimeBy(13 * 1000L)
        runCurrent()
        assertEquals("backed off to 2 captures by t=13s", 2, client.captureCount() - before)

        // The agent overpaints its viewport into FRAGMENTS-OVER-BLACK — a CSI 2J
        // clear then a handful of scattered live lines (the #966/#1214 class) — while
        // tmux still holds the authoritative rich frame. On device this redraw arrives
        // AS %output, which must WAKE the backed-off watchdog because the render is now
        // locally suspect ([visibleRenderMayHaveLostFrame]).
        pane.terminalState.appendRemoteOutput(
            fragmentsOverBlackFrame().toByteArray(Charsets.US_ASCII),
        )
        assertTrue(
            "precondition: a fragments-over-black render must read as a possible lost " +
                "frame so the wake gate honors it",
            pane.terminalState.visibleRenderMayHaveLostFrame(),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = richFrameLines(), isError = false),
        )
        val beforeWake = client.captureCount()
        vm.recordPaneOutputActivityForTest("%1")

        // Within the HOT bound (≤4s) the heal must have fired — the capture that
        // detects + repairs the fragments-over-black divergence lands now, NOT at
        // the next backed-off tick ~15s later.
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertTrue(
            "REGRESSION (#1219 must not regress #1138/#966/#1214): a fragments-over-" +
                "black redraw during a BACKED-OFF window on a STREAMING pane must be " +
                "captured + healed within the hot bound (≤${STALE_RENDER_WATCHDOG_TICK_MS}" +
                "ms), NOT up to 16s later. Captured ${client.captureCount() - beforeWake} " +
                "within the hot bound.",
            client.captureCount() > beforeWake,
        )
    }

    // -------------------------------------------------------------------------
    // (5c) A HEALTHY streaming pane's %output does NOT cut a BACKED-OFF wait short
    //      (issue #1219). The #1166 wake is now SCOPED to a suspect render: a dense
    //      healthy pane's redraw is ignored so the full backed-off interval elapses,
    //      the direct counterpart of (5b) proving the wake gate discriminates.
    // -------------------------------------------------------------------------

    @Test
    fun healthyStreamingOutputDoesNotCutBackedOffWaitShort() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Back off to the widest (16s) interval: captures at t=4s, t=12s.
        val before = client.captureCount()
        advanceTimeBy(13 * 1000L)
        runCurrent()
        assertEquals("backed off to 2 captures by t=13s", 2, client.captureCount() - before)

        // A fresh %output lands on the HEALTHY (dense) active pane ~1s into the 16s
        // backed-off window. Because the render is NOT suspect, the wake must be
        // IGNORED and the wait must run out — a sub-hot advance yields NO capture.
        val beforeWake = client.captureCount()
        vm.recordPaneOutputActivityForTest("%1")
        advanceTimeBy(2 * 1000L)
        runCurrent()
        assertEquals(
            "REGRESSION (#1219): a HEALTHY streaming pane's %output must NOT cut the " +
                "backed-off wait short — no capture may land 2s into the 16s window. " +
                "Captured ${client.captureCount() - beforeWake}.",
            0,
            client.captureCount() - beforeWake,
        )

        // The full backed-off interval still elapses and captures (heal net intact):
        // the next capture lands at t=28s, not defeated, just deferred as intended.
        advanceTimeBy(16 * 1000L)
        runCurrent()
        assertTrue(
            "the backed-off tick still fires when the 16s interval elapses",
            client.captureCount() > beforeWake,
        )
    }

    // -------------------------------------------------------------------------
    // (5b) A PARTIAL-BLACK REDRAW during a BACKED-OFF window HEALS within the hot
    //      bound (issue #1166 heal-latency fix, the #1138 no-black-screen bar). A
    //      redraw on device arrives AS %output, so it wakes the backed-off watchdog
    //      -> the divergence is captured + healed within ≤4s, not up to 16s later.
    // -------------------------------------------------------------------------

    @Test
    fun backedOffPartialBlackRedrawHealsWithinHotBound() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        // Connect + resize BEFORE arming so the watchdog loop is not drained by the
        // resize's advanceUntilIdle (auto-arm is disabled in newVm).
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Issue #1294: the backoff phase must be CONFIRMED-healthy so the pane backs off. The
        // render is the connect seed ("work ready"); return a MATCHING capture on every backoff
        // tick so the oracle reads HEALTHY (empty captures would now score UNVERIFIED and keep
        // the pane hot). A sticky default (not a dense frame) is used deliberately: a dense
        // frame would pollute the scrollback and defeat the ≤3-line partial-black detection the
        // redraw below relies on.
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)

        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Back off to the widest (16s) interval: captures at t=4s, t=12s.
        val before = client.captureCount()
        advanceTimeBy(13 * 1000L)
        runCurrent()
        assertEquals("backed off to 2 captures by t=13s", 2, client.captureCount() - before)

        // The agent redraws its viewport partial-black and tmux holds the
        // authoritative rich frame — so the NEXT capture DIVERGES and heals. On
        // device this redraw arrives AS %output, which must WAKE the backed-off
        // watchdog rather than waiting out the remaining ~15s.
        pane.terminalState.appendRemoteOutput(
            ("[2J[H" + "ISSUE1166-ONE-LIVE-LINE\r\n").toByteArray(Charsets.US_ASCII),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = richFrameLines(), isError = false),
        )
        val beforeWake = client.captureCount()
        vm.recordPaneOutputActivityForTest("%1")

        // Within the HOT bound (≤4s) the heal must have fired — the capture that
        // detects + repairs the partial-black divergence lands now, NOT at t=28s.
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()
        assertTrue(
            "REGRESSION (#1166 heal-latency, #1138 no-black-screen): a partial-black " +
                "redraw during a BACKED-OFF window must be captured + healed within the " +
                "hot bound (≤${STALE_RENDER_WATCHDOG_TICK_MS}ms), NOT up to 16s later. " +
                "Captured ${client.captureCount() - beforeWake} within the hot bound.",
            client.captureCount() > beforeWake,
        )
    }

    // -------------------------------------------------------------------------
    // (6) A DETECTED DIVERGENCE (the heal fired) snaps the cadence back to hot —
    //     so a pane that just went partial-black (#1138) is re-checked at 4s,
    //     not at the backed-off interval.
    // -------------------------------------------------------------------------

    @Test
    fun divergingTickSnapsCadenceBackToHot() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        // Connect + resize BEFORE arming so the watchdog loop is not drained by the
        // resize's advanceUntilIdle (auto-arm is disabled in newVm).
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Issue #1294: the backoff phase must be CONFIRMED-healthy so the pane backs off. Return
        // a MATCHING capture ("work ready", the connect-seed render) on every backoff tick so the
        // oracle reads HEALTHY (empty captures would now score UNVERIFIED and keep it hot). A
        // sticky default (not a dense frame) is used so the scrollback stays clean for the
        // ≤3-line partial-black drive below.
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)

        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Let it back off to stableTicks>=2 (interval 16s): captures at t=4s, t=12s.
        val before = client.captureCount()
        advanceTimeBy(13 * 1000L)
        runCurrent()
        assertEquals("backed off to 2 captures by t=13s", 2, client.captureCount() - before)

        // Drive the pane partial-black and queue tmux's authoritative rich frame so
        // the NEXT watchdog capture DIVERGES -> heals -> resets the back-off.
        pane.terminalState.appendRemoteOutput(
            ("[2J[H" + "ISSUE1166-ONE-LIVE-LINE\r\n").toByteArray(Charsets.US_ASCII),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = richFrameLines(), isError = false),
        )
        // The backed-off tick fires at t=28s, heals (healed=true) -> stableTicks=0.
        advanceTimeBy(16 * 1000L + 100)
        runCurrent()
        val afterHeal = client.captureCount()

        // Now hot again: one 4s advance yields another capture.
        advanceTimeBy(STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        assertTrue(
            "REGRESSION (#1166): a tick that DETECTED a divergence (heal fired) must snap " +
                "the cadence back to 4s, so a just-blacked pane is re-checked hot.",
            client.captureCount() > afterHeal,
        )
    }

    // -------------------------------------------------------------------------
    // (7) ARM-DEDUP — a second arm cancels the prior watchdog loop, so rapid
    //     A->B->A switching can never stack concurrent 4s capture loops.
    // -------------------------------------------------------------------------

    @Test
    fun rapidRearmCancelsPriorWatchdogLoop() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())

        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        val first = requireNotNull(vm.staleRenderWatchdogJobForTest())
        assertTrue("first watchdog loop is active", first.isActive)

        // Rapid re-arm (models a fast A->B->A reveal re-arming the watchdog).
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        val second = requireNotNull(vm.staleRenderWatchdogJobForTest())

        assertTrue(
            "REGRESSION (#1166): the prior watchdog loop must be CANCELLED on re-arm so " +
                "rapid switching can't stack multiple concurrent 4s capture loops.",
            first.isCancelled,
        )
        assertTrue("the new watchdog loop is active", second.isActive)
        assertFalse("re-arm produced a fresh loop", first === second)
    }

    // ------------------------------------------------------------------ Harness

    /**
     * Connect a VM, disable the auto-arm (so `connect()` doesn't arm its own loop),
     * and drive the watchdog loop directly via the #1166 test seam with a small tick
     * ceiling. The pane starts with an empty capture queue so a tick that DOES run
     * is a stable no-op (healed=false) unless the test queues a diverging frame.
     */
    private fun TestScope.armIdleWatchdog(
        client: FakeTmuxClient,
        paneId: String,
    ): TmuxSessionViewModel {
        val vm = connectVm(client)
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest(paneId, 0L)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()
        return vm
    }

    private fun richFrameLines(): List<String> = buildList {
        add("ISSUE1166-RECOVERED-VIEWPORT")
        repeat(27) { add("recovered context row $it") }
    }

    /**
     * A DENSE, confidently-healthy viewport (32 of the 80x40 pty's rows live) — a
     * normally-painted streaming agent pane. Its live fraction (0.8) sits ABOVE the
     * [visibleRenderMayHaveLostFrame] ceiling, so the #1219 wake gate reads it HEALTHY
     * and lets the watchdog back off.
     */
    private fun denseHealthyFrame(): String = buildString {
        append(CLEAR_ONLY)
        repeat(32) { append("agent response line $it with real streamed content\r\n") }
    }

    /**
     * A FRAGMENTS-OVER-BLACK viewport (#966/#1214): a CSI 2J clear then a handful of
     * scattered live lines (>3 so it exercises the VISIBLE-only line-fraction leg of
     * [visibleRenderMayHaveLostFrame], the class the ≤3-line partial-black misses) over
     * an otherwise-black 40-row grid. Reads as a possible lost frame so the #1219 wake
     * gate honors its %output and heals it within the hot bound. Modelled on the
     * maintainer's #966 alt-screen pane — no dense scrollback masks the visible sparsity.
     */
    private fun fragmentsOverBlackFrame(): String = buildString {
        append(CLEAR_ONLY)
        append("24m 3 / 8 / 4 / 3 / 31\r\n")
        append("scattered fragment A\r\n")
        append("scattered fragment B\r\n")
        append("scattered fragment C\r\n")
        append("3\r\n")
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
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
        // Keep connect()'s reveal from auto-arming its own stale-render watchdog: this
        // suite drives the watchdog loop explicitly via the #1166 test seam so the
        // capture counts + timings are deterministic (and connect()'s advanceUntilIdle
        // stays cheap). Tests re-enable / re-arm as needed.
        it.setStaleRenderWatchdogAutoArmEnabledForTest(false)
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

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

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
        // ESC[2J ESC[H — a full clear + home, the overpaint that wipes a viewport to
        // black before a fresh (possibly fragments-over-black) paint. The `[` chars
        // are preceded by a literal ESC (0x1B).
        const val CLEAR_ONLY: String = "[2J[H"
    }
}
