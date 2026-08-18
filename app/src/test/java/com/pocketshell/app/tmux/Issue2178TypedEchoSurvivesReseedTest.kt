package com.pocketshell.app.tmux

import android.os.Looper
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
import com.pocketshell.testsupport.drainMainLooperUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Issue #2178 — REPRODUCE-FIRST (D33/G10) proof that a `capture-pane` reseed can
 * never discard bytes that reached the screen after its snapshot was taken.
 *
 * ## The reported defect
 *
 * Typing within roughly 300 ms of a pane appearing silently lost the LEADING
 * characters of what was typed — echoed locally, then gone, with no error. The
 * reveal gate paints a healed capture BEFORE the attach finishes, so the
 * marker-visible wait returns while [TmuxSessionViewModel.reseedActivePaneForReattach]
 * is still in flight; when that reseed's `capture-pane` lands it REPLACES the
 * local screen (its viewport bytes lead with `CSI 2J`) with a server snapshot
 * taken earlier. The captured CI artifact shows the prompt shifted left by
 * exactly two columns — `ISSUE423-PROMPT-HEAD` rendered as `SUE423-PROMPT-HEAD`,
 * first row 60 columns against continuations at 61–62 on a 62-column pane. Two
 * leading bytes gone, the rest slid left: loss, not lateness. The window was
 * measured at 245–460 ms and is in the device log on EVERY run; whether it bites
 * depends only on whether anyone types inside it.
 *
 * ## Why this test is deterministic where the field symptom is a coin flip
 *
 * [FakeTmuxClient.captureWithCursorGate] PARKS the reseed's capture round-trip
 * exactly where the real one is merely slow, so each test can:
 *
 *  1. drive a REAL production reseed entry point;
 *  2. deliver the pane's echo as live `%output` through the production
 *     `attachExternalProducer` → [SshTerminalBridge] path while the capture is
 *     parked, and HARD-ASSERT it reached the emulator (the precondition — without
 *     it the test would pass vacuously);
 *  3. release the capture, which then returns a snapshot that PREDATES that echo
 *     — the exact server state the field artifact recorded.
 *
 * The load-bearing assertion is on the VISIBLE screen (not the scrollback-bearing
 * transcript, which a `CSI 2J` can leave the wiped rows in): the typed bytes must
 * still be on screen. On base the stale snapshot is painted and they are not
 * (RED); with the guard the snapshot is refused, the bounded retry re-captures,
 * and both the typed bytes AND tmux's fresh content are present (GREEN).
 *
 * Each stale frame queued here is deliberately CONTENT-RICH so it clears the
 * #989 non-destructive-swap floor ([TerminalSurfaceState.captureWouldClearVisibleContent]).
 * A near-blank stale frame would be refused for that unrelated reason and every
 * assertion below would pass with the defect fully present.
 *
 * ## Class coverage (G2)
 *
 * The reveal/attach/reseed path has more than one entry and more than one apply
 * site, so this covers:
 *
 *  - the FIRST-ATTACH reflow completion (`resizeRemotePty` →
 *    `maybeRefreshControlClientSize` → `reseedActivePaneForReattach`) — the exact
 *    entry the field artifact logged (`tmux-refresh-client-size-ok` immediately
 *    followed by `tmux-reattach-full-viewport-reseed pane=%1 partialBlank=true`);
 *  - the REATTACH / switch / foreground-return / no-op-resize / reconnect
 *    chokepoint (`reseedActivePaneForReattach`), whose KDoc documents that all of
 *    those funnel through it;
 *  - the manual Redraw entry (`redrawActivePane`);
 *  - the ORACLE-GATED (non-forced) stale-render heal, a SECOND capture→apply site
 *    with its own clobber window whenever the pane is not quiesced;
 *  - the PREWARM SEED's #1206 recovery retry, a THIRD capture→apply site, reached
 *    on a visible typed-into pane through the #423 "Recreate terminal" control —
 *    covered by the seed gate (buffer-and-replay) rather than by refusal, because
 *    its last resort fires ON live output and a refusal would starve the
 *    fragments-over-black recovery.
 *
 * A real emulator+Docker session switch on the production journey is covered by
 * `Issue2178TypedEchoSurvivesReseedE2eTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2178TypedEchoSurvivesReseedTest {

    private val fixture = StandaloneTmuxVmFixture()
    private val factoryScope get() = fixture.factoryScope

    // -------------------------------------------------------------------------
    // (1) The reported entry: first attach. The post-`refresh-client -C` reflow
    //     completion reseeds the active pane while the user is already typing
    //     into the revealed pane.
    // -------------------------------------------------------------------------

    @Test
    fun typedEchoSurvivesTheFirstAttachReflowReseed() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }

        queueStaleThenFreshCaptures(client)
        val gate = client.parkNextCapture()

        // THE REAL FIRST-ATTACH ENTRY: the grid the phone reports differs from the
        // (unset) applied grid, so `refresh-client -C` runs and its completion
        // block reseeds the active pane — the artifact's ordering exactly.
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        val typed = typeEchoWhileCaptureParked(client, pane, "%1")
        gate.complete(Unit)
        awaitReseedApplied(pane)

        assertTypedEchoSurvived(pane, typed, "the first-attach reflow reseed")
        assertFreshContentStillHealed(pane, "the first-attach reflow reseed")
    }

    // -------------------------------------------------------------------------
    // (2) The shared reattach chokepoint — within-grace reattach, session switch,
    //     foreground return, no-op resize and reconnect all funnel through it.
    // -------------------------------------------------------------------------

    @Test
    fun typedEchoSurvivesTheReattachAndSwitchReseedChokepoint() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        val pane = vm.panes.value.single { it.paneId == "%1" }

        queueStaleThenFreshCaptures(client)
        val gate = client.parkNextCapture()

        val reseed = launch { vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest()) }
        advanceUntilIdle()

        val typed = typeEchoWhileCaptureParked(client, pane, "%1")
        gate.complete(Unit)
        awaitReseedApplied(pane)
        reseed.join()

        assertTypedEchoSurvived(pane, typed, "the reattach/switch reseed chokepoint")
        assertFreshContentStillHealed(pane, "the reattach/switch reseed chokepoint")
    }

    // -------------------------------------------------------------------------
    // (3) The manual Redraw entry — the user-facing button on the same chokepoint.
    // -------------------------------------------------------------------------

    @Test
    fun typedEchoSurvivesTheManualRedrawReseed() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        val pane = vm.panes.value.single { it.paneId == "%1" }

        queueStaleThenFreshCaptures(client)
        val gate = client.parkNextCapture()

        vm.redrawActivePane()
        advanceUntilIdle()

        val typed = typeEchoWhileCaptureParked(client, pane, "%1")
        gate.complete(Unit)
        awaitReseedApplied(pane)

        assertTypedEchoSurvived(pane, typed, "manual Redraw")
        assertFreshContentStillHealed(pane, "manual Redraw")
    }

    // -------------------------------------------------------------------------
    // (4) The SECOND apply site: the oracle-gated (non-forced) stale-render heal.
    //     It only quiesces live deltas for a pane whose render looks SUSPECT; a
    //     confidently-dense pane keeps the seed gate OPEN, so its divergence apply
    //     has the very same clobber window.
    // -------------------------------------------------------------------------

    @Test
    fun typedEchoSurvivesTheOracleGatedStaleRenderHeal() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        val pane = vm.panes.value.single { it.paneId == "%1" }

        // A confidently-dense local render (34 live rows of 40 — above the 0.75
        // suspect ceiling) so the heal does NOT quiesce and this guard, not the
        // seed gate, is what stands between the snapshot and the typed bytes.
        pane.terminalState.appendRemoteOutput(denseLocalFrameBytes())
        drainTerminalFeed()
        assertFalse(
            "precondition: the pane must NOT look suspect, or the heal quiesces live " +
                "deltas and this apply site is not the one under test",
            pane.terminalState.renderLooksSuspect(),
        )

        // tmux holds a materially DIFFERENT dense frame, so the #1300 lost-frame
        // oracle fires and the divergence apply is reached.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = staleServerFrame(), isError = false),
        )
        val gate = client.parkNextCapture()

        var reason: HealAttemptReason? = null
        val heal = launch { reason = vm.healActivePaneIfStaleRenderResultForTest().reason }
        advanceUntilIdle()

        val typed = typeEchoWhileCaptureParked(client, pane, "%1")
        gate.complete(Unit)
        advanceUntilIdle()
        heal.join()
        drainTerminalFeed()

        assertTypedEchoSurvived(pane, typed, "the oracle-gated stale-render heal")
        assertEquals(
            "the oracle-gated heal must report the raced snapshot so the watchdog keeps " +
                "its hot cadence and re-captures rather than backing off as healthy",
            HealAttemptReason.SnapshotRacedLiveEcho,
            reason,
        )
    }

    // -------------------------------------------------------------------------
    // (5) The THIRD capture→apply site: the prewarm seed's #1206 recovery retry,
    //     reached on a VISIBLE, typed-into pane through the #423 "Recreate
    //     terminal" control (recreateTerminalSurface → reseedRecoveredSurface →
    //     seedPrewarmedPane). Its first capture opens the seed gate before
    //     handing off to schedulePrewarmSeedRecovery, so the retry used to
    //     capture-and-apply with the gate OPEN — the same clobber window.
    //
    //     This site is covered by the OTHER mechanism (close the gate around the
    //     capture+apply, buffer-and-replay) rather than refuse-and-re-capture,
    //     because the recovery's last resort fires ON live output and a refusal
    //     rule would starve the fragments-over-black recovery on exactly the
    //     streaming pane that needs it.
    // -------------------------------------------------------------------------

    @Test
    fun typedEchoSurvivesThePrewarmSeedRecoveryReseed() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The recovery retry's capture: the server grid as it was BEFORE the echo.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 92L, output = staleServerFrame(), isError = false),
        )
        client.lastCaptureThreadName = null
        val gate = client.parkNextCapture()

        // Force the FIRST prewarm capture empty (before the wire round-trip) so
        // seedPrewarmedPane OPENS the seed gate and hands off to the recovery —
        // the real #1206 non-happy state, injected the #780 way.
        PrewarmSeedFaultTestOverride.setForcedEmptyFirstCaptures(1)
        try {
            vm.recreateTerminalSurface("%1")
            // runCurrent-only ticks: the recovery's backoff must NOT fire yet.
            val firstAttemptRan = drainMainLooperUntil(
                onTick = {
                    shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
                    runCurrent()
                },
            ) {
                PrewarmSeedFaultTestOverride.seedAttemptCount >= 1
            }
            assertTrue(
                "precondition: the #423 recovery must reach the prewarm seed path",
                firstAttemptRan,
            )
            val pane = vm.panes.value.single { it.paneId == "%1" }

            // Let the bounded retry start and park mid-capture. From here the
            // apply is held, so anything the pane echoes lands inside the window.
            // This MUST use drainTerminalFeedTick (`advanceUntilIdle`) so the
            // 1.6 s recovery backoff is taken; do NOT probe the ungated live
            // path first — that backoff closes the seed gate, and an ungated
            // epoch wait would then burn its 30 s budget.
            val captureInFlight = drainMainLooperUntil(onTick = { drainTerminalFeedTick() }) {
                PrewarmSeedFaultTestOverride.seedAttemptCount >= 2 &&
                    client.lastCaptureThreadName != null
            }
            assertTrue(
                "precondition: the #1206 recovery retry must reach its `capture-pane` " +
                    "round-trip, or there is no window to type into",
                captureInFlight,
            )

            // The pane echoes what was typed WHILE that capture is in flight. With
            // the gate closed the bytes are invisible until the apply replays them,
            // so the non-vacuity check is the producer's own mutation epoch: it
            // ticks for GATED bytes too, and proves the echo reached the bridge
            // BEFORE the snapshot was released.
            val mutationBefore = pane.terminalState
                .renderModelOwnerSnapshotForTesting().mutationEpoch
            assertTrue(
                "the fixture must accept the pane's echo",
                client.tryEmitPaneOutput("%1", "$TYPED_ECHO\r\n".toByteArray(Charsets.US_ASCII)),
            )
            val echoReachedTheBridge = drainMainLooperUntil(onTick = { drainTerminalFeedTick() }) {
                pane.terminalState.renderModelOwnerSnapshotForTesting().mutationEpoch >
                    mutationBefore
            }
            assertTrue(
                "precondition: the typed echo must reach the pane's producer while the " +
                    "recovery capture is in flight — that is the state the defect needs",
                echoReachedTheBridge,
            )

            gate.complete(Unit)
            awaitReseedApplied(pane)

            assertTypedEchoSurvived(pane, TYPED_ECHO, "the #1206 prewarm-seed recovery reseed")
        } finally {
            PrewarmSeedFaultTestOverride.clear()
        }
    }

    // -------------------------------------------------------------------------
    // (6) NO REVEAL-TIMING REGRESSION. The fix must not buy byte safety by making
    //     the session slower to appear (#184 chrome compaction / the black-screen
    //     work depend on the reveal), so:
    //       (a) the pane is revealed and Connected WHILE the reseed is still
    //           mid-capture — the reveal is not gated on the reseed settling; and
    //       (b) a quiet reseed (nothing raced) still applies on its FIRST capture,
    //           so no extra round-trip was added to the common path.
    // -------------------------------------------------------------------------

    @Test
    fun revealIsNotGatedOnTheReseedAndTheQuietPathCostsOneCapture() = runVmTest {
        val client = FakeTmuxClient().withInitialFrame("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        val pane = vm.panes.value.single { it.paneId == "%1" }

        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = freshServerFrame(), isError = false),
        )
        val gate = client.parkNextCapture()
        val reseed = launch { vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest()) }
        advanceUntilIdle()

        // (a) mid-reseed, with the capture still in flight, the user already has a
        //     revealed, connected, readable pane.
        assertTrue(
            "the reveal must not wait for the reseed to settle — the pane must already " +
                "show the attach frame while the reseed capture is in flight",
            pane.terminalState.visibleScreenTextSnapshot().contains(INITIAL_MARKER),
        )
        assertTrue(
            "the transport must already be Connected while the reseed is in flight " +
                "(observed ${vm.connectionStatus.value})",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val capturesBefore = client.healCaptureCount()
        gate.complete(Unit)
        awaitReseedApplied(pane)
        reseed.join()

        // (b) nothing raced, so the FIRST capture is applied: exactly one heal
        //     capture-pane, and tmux's fresh content landed.
        assertEquals(
            "a quiet reseed must still apply on its first capture — the guard must add " +
                "no round-trip to the common path",
            capturesBefore + 1,
            client.healCaptureCount(),
        )
        assertTrue(
            "the quiet reseed must apply tmux's authoritative frame",
            pane.terminalState.visibleScreenTextSnapshot().contains(FRESH_MARKER),
        )
    }

    // ------------------------------------------------------------------ Harness

    private fun runVmTest(body: suspend TestScope.() -> Unit) = fixture.runVmTest(body = body)

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
        // Issue #2178 / #1849 Shape A: pin the live `%output` producer to THIS
        // test's scheduler. The no-arg `TerminalSurfaceState()` uses a real
        // `Dispatchers.IO`, so `tryEmit` can land (or sit in the extra buffer)
        // while the collector has not yet been scheduled; a 30 s looper pump
        // then burns its budget waiting for bytes that will never reach the
        // emulator on this thread. That is the exact
        // `typedEchoSurvivesTheFirstAttachReflowReseed` signature the reviewer
        // saw (`time=30.057` next to 40 ms siblings). Sibling VM tests already
        // pin this factory; this class must too.
        it.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(externalProducerDispatcher = StandardTestDispatcher(testScheduler))
        }
        fixture.track(it)
    }

    private fun TestScope.connectVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val vm = newVm(registry = ActiveTmuxClients(), sshLeaseManager = leaseManager)
        runCurrent()
        // Isolate the reseed under test: the auto-armed blank + stale-render
        // watchdogs would otherwise add their own capture-pane traffic.
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
        advanceUntilIdle()
        return vm
    }

    /**
     * The two responses the SERVER really returns around the echo: first the grid
     * as it was BEFORE the typed bytes reached it (the stale snapshot that used to
     * be painted over them), then — one round-trip later — the grid that HAS them,
     * carrying a marker only tmux holds so a landed heal is observable.
     */
    private fun queueStaleThenFreshCaptures(client: FakeTmuxClient) {
        client.capturePaneResponses.addLast(
            CommandResponse(number = 90L, output = staleServerFrame(), isError = false),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = freshServerFrame(), isError = false),
        )
    }

    /** Park the next `capture-pane` round-trip where the real one is merely slow. */
    private fun FakeTmuxClient.parkNextCapture(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        captureWithCursorGate = gate
        return gate
    }

    /**
     * Deliver the pane's echo of the typed characters as live `%output` through
     * the production producer → [SshTerminalBridge] path while the reseed's
     * capture is parked, and HARD-ASSERT it reached the emulator. Without that
     * assertion the "the bytes survived" checks could pass over bytes that never
     * arrived — the vacuous shape this whole class exists to avoid.
     */
    private fun TestScope.typeEchoWhileCaptureParked(
        client: FakeTmuxClient,
        pane: TmuxPaneState,
        paneId: String,
    ): String {
        awaitLiveOutputPathConnected(client, pane, paneId)
        val echo = "$TYPED_ECHO\r\n"
        assertTrue(
            "the fixture must accept the pane's echo",
            client.tryEmitPaneOutput(paneId, echo.toByteArray(Charsets.US_ASCII)),
        )
        val landed = drainMainLooperUntil(onTick = { drainTerminalFeedTick() }) {
            pane.terminalState.visibleScreenTextSnapshot().contains(TYPED_ECHO)
        }
        assertTrue(
            "precondition: the typed echo must reach the emulator (ungated) while the " +
                "reseed capture is in flight — that is the state the defect needs",
            landed,
        )
        return TYPED_ECHO
    }

    /**
     * THE DE-FLAKE (#1048 / #1633 class, and the reason this file reddened the
     * required `:app:testReleaseUnitTest` once under load).
     *
     * Two things have to be true before a typed echo can reach the emulator:
     * the pane's producer must be collecting, and the seed gate must be OPEN.
     * The producer is pinned to this test's scheduler (see [newVm]); a hop
     * that is only `idleFor` + `runCurrent()` can miss a continuation that
     * needs virtual time, which is how
     * `typedEchoSurvivesTheFirstAttachReflowReseed` burned the full 30 s
     * budget (`time=30.057`) next to 40 ms siblings. Align the probe with the
     * working sibling [drainTerminalFeedTick] (`advanceUntilIdle` + `idleFor`
     * + `runCurrent`).
     *
     * The probe itself is a zero-glyph `CSI 0 m` (SGR reset — no cursor
     * movement, no glyph, not stripped by
     * [com.pocketshell.core.terminal.bridge.TerminalQueryResponseSanitizer]).
     * An advance of
     * [com.pocketshell.core.terminal.ui.TerminalSurfaceState.liveOutputAppliedEpoch]
     * is the non-vacuity proof: the collector is subscribed AND the gate is
     * open. After that, a `true` from `tryEmit` means the typed echo is
     * delivered, not sitting unseen behind a closed gate.
     *
     * Safe to call while a capture is parked on a `CompletableDeferred` —
     * that wait is an external signal, so `advanceUntilIdle` does not
     * complete it. Do NOT use this helper while a DELAYED retry is still
     * pending and must not fire yet (the prewarm first-attempt wait uses a
     * `runCurrent`-only tick for that reason).
     */
    private fun TestScope.awaitLiveOutputPathConnected(
        client: FakeTmuxClient,
        pane: TmuxPaneState,
        paneId: String,
    ) {
        val epochBefore = pane.terminalState.liveOutputAppliedEpoch()
        val connected = drainMainLooperUntil(
            onTick = {
                client.tryEmitPaneOutput(paneId, LIVE_PATH_PROBE)
                drainTerminalFeedTick()
            },
        ) {
            pane.terminalState.liveOutputAppliedEpoch() > epochBefore
        }
        assertTrue(
            "precondition: the pane's live `%output` producer must be subscribed AND the " +
                "seed gate open before anything is typed — otherwise a `tryEmit` is dropped " +
                "on the floor (SharedFlow replay=0 with no subscriber) or buffered behind " +
                "the gate, and every assertion below would be measuring nothing",
            connected,
        )
    }

    /**
     * Wait — bounded, HARD-FAILING (#1048 Shape B) — until the reseed has actually
     * applied a server snapshot, so the survival check below runs AFTER the paint
     * that used to destroy the typed bytes rather than before it. Both worlds
     * reach this: on base the STALE frame lands, with the guard the retry's FRESH
     * frame does, and both carry [SERVER_ROW_MARKER]. Asserting survival without
     * this wait would be green on base purely by arriving early.
     */
    private fun TestScope.awaitReseedApplied(pane: TmuxPaneState) {
        val applied = drainMainLooperUntil(sleepMs = 2L, onTick = { drainTerminalFeedTick() }) {
            pane.terminalState.visibleScreenTextSnapshot().contains(SERVER_ROW_MARKER)
        }
        assertTrue(
            "the reseed never applied ANY server snapshot, so the survival assertion " +
                "below would be vacuous. Visible screen:\n" +
                pane.terminalState.visibleScreenTextSnapshot(),
            applied,
        )
    }

    /** Drain the bridge's real Handler/Looper feed plus the virtual scheduler. */
    private fun TestScope.drainTerminalFeedTick() {
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
        runCurrent()
    }

    private fun TestScope.drainTerminalFeed() {
        repeat(4) { drainTerminalFeedTick() }
    }

    /** THE LOAD-BEARING ASSERTION — the reported symptom, byte-for-byte. */
    private fun assertTypedEchoSurvived(pane: TmuxPaneState, typed: String, entry: String) {
        val visible = pane.terminalState.visibleScreenTextSnapshot()
        assertTrue(
            "REGRESSION (#2178): $entry applied a `capture-pane` snapshot taken BEFORE " +
                "the typed bytes reached the screen, so the repaint discarded them — the " +
                "on-device symptom is losing the leading characters of anything typed " +
                "within ~300ms of a pane appearing. Visible screen:\n$visible",
            visible.contains(typed),
        )
    }

    /**
     * Refusing a stale snapshot must not degrade into "never heal": the bounded
     * retry re-captures and tmux's authoritative content still lands.
     */
    private fun assertFreshContentStillHealed(pane: TmuxPaneState, entry: String) {
        assertTrue(
            "$entry must still reseed from tmux's authoritative grid after refusing the " +
                "stale snapshot — the retry's fresh capture must land",
            pane.terminalState.visibleScreenTextSnapshot().contains(FRESH_MARKER),
        )
    }

    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

    private fun FakeTmuxClient.withInitialFrame(
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
            CommandResponse(number = 2L, output = attachFrame(), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun denseLocalFrameBytes(): ByteArray = buildString {
        append(CLEAR_ONLY)
        repeat(34) { append("$LOCAL_MARKER $it real rendered content padding out this row\r\n") }
    }.toByteArray(Charsets.US_ASCII)

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
        const val INITIAL_MARKER: String = "ISSUE2178-ATTACHED"
        const val TYPED_ECHO: String = "ISSUE2178-TYPED-HEAD"
        const val FRESH_MARKER: String = "ISSUE2178-SERVER-SAW-THE-ECHO"
        const val LOCAL_MARKER: String = "ISSUE2178-LOCAL-ROW"
        const val SERVER_ROW_MARKER: String = "ISSUE2178-SERVER-ROW"

        // ESC[2J ESC[H — clear screen + cursor home.
        const val CLEAR_ONLY: String = "[2J[H"

        /**
         * `CSI 0 m` — an SGR reset. Zero glyphs, no cursor movement, and not a
         * query REPLY so the bridge's sanitizer leaves it alone: the cheapest
         * byte sequence that proves the live `%output` path reaches the emulator
         * without changing anything the assertions read.
         */
        val LIVE_PATH_PROBE: ByteArray = "[0m".toByteArray(Charsets.US_ASCII)

        /**
         * The attach seed the reveal paints: a shell prompt on a tall phone grid.
         * Deliberately sparse, because that is the reported state — the field
         * artifact logs `tmux-reattach-full-viewport-reseed pane=%1
         * partialBlank=true` on a 58-row pane showing one prompt line, and it is
         * that partial-blank reading which stops the reflow completion from
         * skipping the re-capture for a freshly-seeded pane.
         */
        fun attachFrame(): List<String> = listOf(INITIAL_MARKER, "~ $ ")

        /**
         * The server grid as it was BEFORE the echo — content-rich, so the #989
         * non-destructive-swap floor cannot refuse it for an unrelated reason and
         * leave this test green over the defect.
         */
        fun staleServerFrame(): List<String> = buildList {
            add(INITIAL_MARKER)
            repeat(24) { add("$SERVER_ROW_MARKER $it holding real authoritative grid content") }
        }

        /** The server grid one round-trip later: it HAS the echoed bytes. */
        fun freshServerFrame(): List<String> = buildList {
            addAll(staleServerFrame())
            add(TYPED_ECHO)
            add("$FRESH_MARKER only tmux's authoritative grid holds this line")
        }
    }
}
