package com.pocketshell.app.tmux

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1301 — REPRODUCE-FIRST (D33/G10) JVM proof for the CONTINUOUS RECONCILER's
 * reseed race + eventless convergence, on the REAL bridge + real emulator (the
 * capture → seed-gate → emulator path, not a proxy).
 *
 * ## The capture-clobbers-newer-delta race (criterion 4 — the load-bearing red→green)
 *
 * The reconcile heal ([TmuxSessionViewModel.healActivePaneIfStaleRender]) captures tmux's
 * authoritative grid, then — if the render diverged — repaints it via `appendRemoteOutput`,
 * which prepends a `CSI 2J` clear ([toTerminalViewportBytes]). If a NEWER live `%output`
 * delta lands on the emulator AFTER the (older) capture was issued but BEFORE the snapshot
 * is applied, the `CSI 2J` clear WIPES the newer delta and the pane transiently regresses to
 * the older frame. THE BUG: on base the heal applies the snapshot with the seed gate OPEN,
 * so the racing delta paints the emulator and is then clobbered.
 *
 * THE FIX (#1301, design §5): for a SUSPECT pane (one we are about to reseed) the heal
 * QUIESCES live delivery — it closes the seed gate around the capture+apply so the racing
 * delta is BUFFERED (in arrival order), then `seedThenOpenGate` re-applies the buffered
 * delta ON TOP of the snapshot (newest-wins), and a `finally` reopens the gate on every
 * exit so live output is never swallowed. A HEALTHY pane is NOT quiesced (its gate stays
 * open) so the #1219/#1164 steady-heat back-off is unaffected.
 *
 * RED on base: [reseedDoesNotClobberNewerInFlightDelta] +
 * [reseedDoesNotClobberNewerDeltaForFullyBlankRender] feed a newer live delta while the
 * (parked) capture is in flight and assert the delta survives on the VISIBLE screen. On base
 * the snapshot's `CSI 2J` clobbers it → the marker is absent → RED. With the quiesce fix the
 * buffered delta flushes on top of the snapshot → present → GREEN.
 *
 * ## Class coverage (G2)
 *
 * The race only arises on the APPLY path (a divergence the heal repaints). Two apply-path
 * divergence classes are covered: fragments-over-black (the maintainer's #1208 photograph)
 * and a fully-blank / lost-after-paint render. The surface-black-model-intact and
 * empty-seed / never-seeded classes score HEALTHY / UNVERIFIED and apply NO clobbering
 * snapshot (covered by their own paths — [SurfaceBlackModelIntactDiagnosticTest], the
 * capture-empty branch), and the overflow reseed has its own #1297 pause/resume quiesce
 * ([PaneOutputOverflowRecoveryTest]); [healthyPaneIsNotQuiescedSoLiveOutputIsNotBuffered]
 * proves the fix does not touch the healthy path.
 *
 * ## Eventless mid-session convergence (item 1 — the continuous reconciler)
 *
 * [idlePaneConvergesToTmuxTruthWithoutAnyOutputEvent] injects a mid-session divergence into
 * an IDLE, non-repainting pane with NO `%output` / reveal / switch event, then advances one
 * reconcile interval and asserts the VISIBLE screen converges to tmux's authoritative frame —
 * proving the reconcile is PERIODIC (driven by the watchdog tick), not event-only. It is the
 * load-bearing "converges to authoritative tmux content" assertion (not merely "a capture
 * ran"), and it exercises the quiesce path on a plain idle heal (no delta to buffer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReconcileReseedRaceTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -----------------------------------------------------------------------
    // (1) THE RACE — a newer in-flight delta must survive the reseed apply.
    // -----------------------------------------------------------------------

    @Test
    fun reseedDoesNotClobberNewerInFlightDelta() = runVmTest {
        assertNewerDeltaSurvivesReseed(renderFrame = fragmentsOverBlackFrame())
    }

    @Test
    fun reseedDoesNotClobberNewerDeltaForFullyBlankRender() = runVmTest {
        // Class coverage (G2): a fully-blank / lost-after-paint render (renderedChars == 0)
        // also diverges and applies a snapshot — the same clobber window. The newer delta
        // must survive here too.
        assertNewerDeltaSurvivesReseed(renderFrame = fullyBlankFrame())
    }

    /**
     * Drive ONE real reconcile heal on a SUSPECT pane whose tmux capture carries a rich
     * frame (so the oracle diverges and applies a snapshot). While the capture is PARKED in
     * flight, feed a NEWER live `%output` delta straight into the pane's bridge (the
     * production `feedBytes` live path, which honors the seed gate), then release the capture.
     *
     * On base the delta paints the emulator through the OPEN gate and the snapshot's
     * `CSI 2J` clobbers it → the marker is ABSENT from the visible screen → RED. With the
     * #1301 quiesce the delta is buffered behind the closed gate and re-applied on top of the
     * snapshot → PRESENT → GREEN.
     */
    private fun TestScope.assertNewerDeltaSurvivesReseed(renderFrame: String) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Put the pane into the SUSPECT render state under test (a real emulator paint).
        pane.terminalState.appendRemoteOutput(renderFrame.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the render must look SUSPECT so the reconciler quiesces + heals it",
            pane.terminalState.renderLooksSuspect(),
        )
        assertFalse(
            "precondition: the newer delta marker must NOT already be on the visible screen",
            visibleScreenTextFor(pane).contains(NEWER_DELTA_MARKER),
        )

        // tmux's authoritative grid: a rich frame the reconciler will reseed from. Its cursor
        // is homed to the bottom so the flushed delta lands within the visible viewport,
        // BELOW the snapshot rows, rather than overwriting them. The snapshot does NOT contain
        // the delta marker — the only way the marker reaches the visible screen is the delta.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = richFrameLines(), isError = false),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 91L, output = listOf("0,14"), isError = false),
        )
        // PARK the capture so we can inject the racing delta while it is in flight.
        val captureGate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = captureGate

        val healJob = launch { vm.healActivePaneIfStaleRenderForTest() }
        // Run the heal up to the parked capture. With the fix the seed gate is now CLOSED
        // (the pane is suspect); on base it is still open.
        runCurrent()

        // A NEWER live %output delta arrives while the (older) capture is parked in flight —
        // the exact race window. Feed it via the production live path (bridge.feedBytes),
        // which buffers behind a closed gate and paints the emulator through an open one.
        feedLiveDelta(pane, "$NEWER_DELTA_MARKER\r\n")

        // Release the capture; the heal returns the older snapshot and applies it.
        captureGate.complete(Unit)
        advanceUntilIdle()
        healJob.cancel()

        val visible = visibleScreenTextFor(pane)
        assertTrue(
            "REGRESSION (#1301 capture-clobbers-newer-delta race): a NEWER in-flight " +
                "%output delta that races the reconcile reseed must SURVIVE (newest-wins), " +
                "not be clobbered by the (older) snapshot's CSI 2J clear. On base the delta " +
                "paints through the open seed gate and is wiped; the #1301 quiesce buffers it " +
                "and re-applies it on top of the snapshot. Marker missing from visible screen:\n" +
                visible,
            visible.contains(NEWER_DELTA_MARKER),
        )
        // The reseed still landed the authoritative frame (the fix did not merely drop the apply).
        assertTrue(
            "the authoritative tmux frame must still be on the visible screen after the reseed",
            visible.contains("RECONCILED-VIEWPORT"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) HEALTHY pane is NOT quiesced — no regression to the streaming path.
    // -----------------------------------------------------------------------

    @Test
    fun healthyPaneIsNotQuiescedSoLiveOutputIsNotBuffered() = runVmTest {
        // A dense, confidently-HEALTHY pane: the reconciler must NOT close its seed gate, so
        // a live delta arriving during the (healthy) capture window paints the emulator
        // immediately instead of being buffered — the #1219/#1164 steady-heat path is
        // untouched by the #1301 quiesce (which is gated on a SUSPECT render).
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertFalse(
            "precondition: the pane is confidently HEALTHY (not suspect)",
            pane.terminalState.renderLooksSuspect(),
        )

        // The heal capture confirms the render (matches the dense frame) → HEALTHY, no apply.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = denseHealthyFrame().lines(), isError = false),
        )
        val captureGate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = captureGate

        val healJob = launch { vm.healActivePaneIfStaleRenderForTest() }
        runCurrent()

        // A live delta during the capture window must paint IMMEDIATELY (gate stayed open for
        // a healthy pane), not be held until after the capture.
        feedLiveDelta(pane, "$HEALTHY_DELTA_MARKER\r\n")
        assertTrue(
            "REGRESSION (#1301): a HEALTHY pane must NOT be quiesced — a live delta during " +
                "its heal capture must reach the emulator immediately (gate stays OPEN), " +
                "preserving the #1219/#1164 streaming path.",
            visibleScreenTextFor(pane).contains(HEALTHY_DELTA_MARKER),
        )

        captureGate.complete(Unit)
        advanceUntilIdle()
        healJob.cancel()

        // Still present after the healthy (no-apply) heal — nothing clobbered it.
        assertTrue(
            "the live delta must remain after a HEALTHY heal (no snapshot applied)",
            visibleScreenTextFor(pane).contains(HEALTHY_DELTA_MARKER),
        )
    }

    // -----------------------------------------------------------------------
    // (2b) FAIL-SAFE — a SUSPECT pane whose capture FAILS must still REOPEN the
    //      seed gate, or all future live %output is swallowed FOREVER (a permanent
    //      black pane WORSE than the reseed race). This is the `finally`-reopen the
    //      code comment warns about by name; it is the ONLY thing that opens the
    //      gate on the capture-FAILED / Unverified-while-suspect path (no snapshot
    //      is applied, so `seedThenOpenGate` never runs). Removing the finally-reopen
    //      body must turn these RED (issue #1301 reviewer round-2 gap).
    //
    //      Class coverage (G2) — the capture "fails" in every way the #1294
    //      Claude-burst-starved-capture class can: it returns EMPTY, returns an
    //      ERROR, or THROWS (timeout). All three land in the capture-failed branch,
    //      score UNVERIFIED (the render still looks lost), apply NO snapshot, and
    //      must rely on the `finally` to reopen the gate.
    // -----------------------------------------------------------------------

    @Test
    fun suspectPaneReopensGateWhenCaptureReturnsEmpty() = runVmTest {
        assertSuspectCaptureFailureReopensGate(CaptureFailureMode.EMPTY)
    }

    @Test
    fun suspectPaneReopensGateWhenCaptureReturnsError() = runVmTest {
        assertSuspectCaptureFailureReopensGate(CaptureFailureMode.ERROR)
    }

    @Test
    fun suspectPaneReopensGateWhenCaptureThrows() = runVmTest {
        assertSuspectCaptureFailureReopensGate(CaptureFailureMode.THROW)
    }

    private enum class CaptureFailureMode { EMPTY, ERROR, THROW }

    /**
     * Drive ONE real reconcile heal on a SUSPECT pane whose `capture-pane` FAILS (returns
     * empty / returns an error / throws a timeout — the #1294 starved-capture class). The
     * heal quiesces (closes the seed gate) because the render looks lost, then the capture
     * fails so it scores UNVERIFIED with NO snapshot applied — meaning `seedThenOpenGate`
     * NEVER runs and the ONLY thing that can reopen the gate is the `finally`.
     *
     * While the (parked) capture is in flight, a live `%output` delta arrives — buffered
     * behind the closed gate. After the heal returns, assert BOTH:
     *   (a) the seed gate is REOPENED (`gated == false`), and
     *   (b) the buffered delta reached the visible screen (live output flowed, not swallowed),
     * and — the strongest proof that the swallow is gone forever — a SECOND live delta fed
     * AFTER the heal completes also reaches the screen.
     *
     * RED when the `finally`-reopen body is removed: the gate stays CLOSED, both deltas stay
     * buffered, neither marker reaches the visible screen. GREEN with the finally.
     */
    private fun TestScope.assertSuspectCaptureFailureReopensGate(mode: CaptureFailureMode) {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Put the pane into the SUSPECT render state so the reconciler quiesces (closes the
        // gate) before capturing — the exact precondition of the swallow bug.
        pane.terminalState.appendRemoteOutput(
            fragmentsOverBlackFrame().toByteArray(Charsets.US_ASCII),
        )
        advanceUntilIdle()
        assertTrue(
            "precondition: the render must look SUSPECT so the reconciler quiesces + heals it",
            pane.terminalState.renderLooksSuspect(),
        )
        assertTrue(
            "precondition: the seed gate starts OPEN before the heal",
            isSeedGateOpenFor(pane),
        )

        // Make the parked capture FAIL in the requested way. All three exits reach the
        // capture-failed branch (combined == null OR captureResponse null/error/empty).
        when (mode) {
            CaptureFailureMode.EMPTY ->
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 90L, output = emptyList(), isError = false),
                )
            CaptureFailureMode.ERROR ->
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 90L, output = listOf("capture failed"), isError = true),
                )
            CaptureFailureMode.THROW -> {
                client.throwOnCommandPrefix = "capture-pane"
                client.throwOnCommandRemaining = 1
            }
        }

        // PARK the capture so the racing delta lands while the gate is CLOSED (quiesced).
        val captureGate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = captureGate

        val healJob = launch { vm.healActivePaneIfStaleRenderForTest() }
        // Run the heal up to the parked capture; with the quiesce the gate is now CLOSED.
        runCurrent()
        assertFalse(
            "the reconciler must have CLOSED the seed gate around the suspect capture",
            isSeedGateOpenFor(pane),
        )

        // A live %output delta arrives during the quiesce window — buffered behind the gate.
        feedLiveDelta(pane, "$SWALLOW_DELTA_MARKER\r\n")
        assertFalse(
            "the buffered delta must NOT paint the emulator while the gate is closed",
            visibleScreenTextFor(pane).contains(SWALLOW_DELTA_MARKER),
        )

        // Release the (failing) capture; the heal scores UNVERIFIED, applies NO snapshot, and
        // must reopen the gate in its `finally` — the only reopen on this path.
        captureGate.complete(Unit)
        advanceUntilIdle()
        healJob.cancel()

        // (a) The gate is REOPENED — without the finally it would still be closed.
        assertTrue(
            "REGRESSION (#1301 fail-safe): a SUSPECT pane whose reconcile capture FAILED " +
                "(mode=$mode) must REOPEN the seed gate in the `finally`. No snapshot was " +
                "applied (UNVERIFIED) so `seedThenOpenGate` never ran; if the finally-reopen " +
                "is gone the gate stays CLOSED and every future live %output is swallowed " +
                "forever — a permanent black pane worse than the race.",
            isSeedGateOpenFor(pane),
        )
        // (b) The delta buffered during the window flushed to the visible screen.
        val afterHeal = visibleScreenTextFor(pane)
        assertTrue(
            "REGRESSION (#1301 fail-safe): the live %output delta buffered during the " +
                "quiesce window (mode=$mode) must be FLUSHED to the visible screen when the " +
                "gate reopens, not swallowed. Visible screen:\n" + afterHeal,
            afterHeal.contains(SWALLOW_DELTA_MARKER),
        )

        // Strongest proof the swallow is gone FOREVER: a delta fed AFTER the heal returned
        // reaches the emulator immediately (the gate is genuinely open for future output).
        feedLiveDelta(pane, "$POST_HEAL_DELTA_MARKER\r\n")
        assertTrue(
            "REGRESSION (#1301 fail-safe): after the failed reconcile the pane must accept " +
                "FUTURE live %output — a delta fed post-heal (mode=$mode) must reach the " +
                "visible screen, proving the gate is open for good. Visible screen:\n" +
                visibleScreenTextFor(pane),
            visibleScreenTextFor(pane).contains(POST_HEAL_DELTA_MARKER),
        )
    }

    // -----------------------------------------------------------------------
    // (3) EVENTLESS mid-session convergence — the continuous (periodic) reconciler.
    // -----------------------------------------------------------------------

    @Test
    fun idlePaneConvergesToTmuxTruthWithoutAnyOutputEvent() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Paint a dense, HEALTHY idle frame, then arm the real reconciler loop and let it run
        // (empty captures score HEALTHY → it cools toward the ceiling). This models an idle,
        // non-repainting Claude pane at rest.
        pane.terminalState.appendRemoteOutput(denseHealthyFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()
        // Let it cool past the widest interval (several healthy ticks).
        advanceTimeBy(60 * 1000L)
        runCurrent()

        // Inject a MID-SESSION divergence with NO event: the pane's model goes
        // fragments-over-black (a lost frame), but NO %output / reveal / switch fires — so the
        // suspect-wake path is NEVER triggered. Only the PERIODIC reconcile tick can heal it.
        pane.terminalState.appendRemoteOutput(
            fragmentsOverBlackFrame().toByteArray(Charsets.US_ASCII),
        )
        assertTrue(
            "precondition: the mid-session render lost the frame (fragments-over-black)",
            pane.terminalState.renderLooksSuspect(),
        )
        assertFalse(
            "precondition: tmux's authoritative frame is NOT yet on the visible screen",
            visibleScreenTextFor(pane).contains("RECONCILED-VIEWPORT"),
        )
        // tmux still holds the authoritative rich frame.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = richFrameLines(), isError = false),
        )

        // Advance ONE reconcile interval (the widest, plus a hot tick of slack) WITHOUT ever
        // calling recordPaneOutputActivity — no event of any kind. The next periodic tick must
        // capture, diverge, and reseed the authoritative frame.
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        // LOAD-BEARING (D33): the pane CONVERGES to authoritative tmux content — not merely
        // "a capture ran". The eventless periodic reconciler is the ONLY thing that could
        // have healed it, and it did.
        assertTrue(
            "REGRESSION (#1301 continuous reconciler): an IDLE, non-repainting pane whose " +
                "model diverges MID-SESSION with NO %output / reveal / switch event must " +
                "CONVERGE to tmux's authoritative frame within one reconcile interval — the " +
                "reconcile is PERIODIC, not event-only. Visible screen:\n" +
                visibleScreenTextFor(pane),
            visibleScreenTextFor(pane).contains("RECONCILED-VIEWPORT"),
        )
    }

    // ------------------------------------------------------------------ Frames

    // Kept comfortably within the emulator's visible viewport (24 rows) so the
    // RECONCILED-VIEWPORT marker on row 0 stays on-screen after the reseed paint, and the
    // flushed delta (cursor homed to row ~14) lands below it, also on-screen.
    private fun richFrameLines(): List<String> = buildList {
        add("RECONCILED-VIEWPORT")
        repeat(11) { add("recovered context row $it with real rendered content") }
    }

    /** A dense, confidently-healthy 80x40 viewport (32 live rows). */
    private fun denseHealthyFrame(): String = buildString {
        append(CLEAR_HOME)
        repeat(32) { append("agent response line $it with real streamed content\r\n") }
    }

    /** Fragments-over-black (#1208): a CSI 2J clear then a handful of scattered live lines. */
    private fun fragmentsOverBlackFrame(): String = buildString {
        append(CLEAR_HOME)
        append("24m 3 / 8 / 4 / 3 / 31\r\n")
        append("scattered fragment A\r\n")
        append("scattered fragment B\r\n")
        append("scattered fragment C\r\n")
        append("3\r\n")
    }

    /** A fully-blank render (a CSI 2J with no content — the lost-after-paint class). */
    private fun fullyBlankFrame(): String = CLEAR_HOME

    // ------------------------------------------------------------------ Harness

    private fun feedLiveDelta(pane: TmuxPaneState, text: String) {
        val bridge = bridgeFor(pane)
        val bytes = text.toByteArray(Charsets.US_ASCII)
        bridge.feedBytes(bytes, 0, bytes.size)
    }

    private fun bridgeFor(pane: TmuxPaneState): SshTerminalBridge {
        val field = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        return field.get(pane.terminalState) as SshTerminalBridge
    }

    private fun visibleScreenTextFor(pane: TmuxPaneState): String =
        bridgeFor(pane).emulator.screen.visibleScreenText

    /**
     * Reads the bridge's private `gated` flag directly (issue #1301 fail-safe assertion):
     * `true` = seed gate CLOSED (live output buffered), `false` = OPEN (live output flows).
     * The gate is set under `gateLock`; the single-threaded test scheduler serializes the
     * heal and this read, so an unsynchronized reflective read is deterministic here.
     */
    private fun isSeedGateOpenFor(pane: TmuxPaneState): Boolean {
        val bridge = bridgeFor(pane)
        val field = SshTerminalBridge::class.java.getDeclaredField("gated").apply {
            isAccessible = true
        }
        return !(field.getBoolean(bridge))
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
        // Silence the connect()-auto-armed watchdogs so the only capture traffic is the heal
        // under test (each test re-arms explicitly where it drives the periodic loop).
        it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
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
            CommandResponse(
                number = 2L,
                output = buildList {
                    add("initial idle frame")
                    repeat(10) { add("seed context row $it") }
                },
                isError = false,
            ),
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
        // ESC[H ESC[2J — home + clear, the overpaint that wipes the viewport to black.
        const val CLEAR_HOME: String = "[H[2J"
        const val NEWER_DELTA_MARKER: String = "NEWER-LIVE-DELTA-1301"
        const val HEALTHY_DELTA_MARKER: String = "HEALTHY-LIVE-DELTA-1301"
        const val SWALLOW_DELTA_MARKER: String = "SWALLOW-GUARD-DELTA-1301"
        const val POST_HEAL_DELTA_MARKER: String = "POST-HEAL-DELTA-1301"
    }
}
