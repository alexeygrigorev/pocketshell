package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
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
 * Issue #722 — FAST JVM CHARACTERIZATION tests pinning the CURRENT (post-#721)
 * behavior of the reseed / blank-watchdog cluster in [TmuxSessionViewModel]:
 *
 *   - [TmuxSessionViewModel.reseedBlankVisiblePanes] (~`:6825`)
 *   - [TmuxSessionViewModel.armConnectedBlankWatchdog] (~`:6909`)
 *
 * The black-screen bugs (#662 blank-after-reconnect, #721 stays-black-after-
 * reattach) live in this cluster, yet the audit (#684) found these two
 * functions had ZERO direct unit tests — they were only exercised by slow
 * Docker E2E. These tests pin what the cluster does TODAY so the gated refactor
 * can't silently reintroduce the black screen.
 *
 * They are characterization (golden-master) tests: they capture the observable
 * behavior of the CURRENT implementation, they do NOT change production
 * behavior. They drive the cluster two ways, both deterministic on the virtual
 * test scheduler (no wall-clock sleeps, so the contended box can't make them
 * flaky):
 *
 *   1. Through the real [TmuxSessionViewModel.connect] attach/reveal path (the
 *      cluster runs for real, including the #721 reveal-keeps-overlay gate), and
 *   2. Directly via the minimal `*ForTest` seams added for #722
 *      ([TmuxSessionViewModel.currentRuntimeGuardForTest],
 *      [TmuxSessionViewModel.reseedBlankVisiblePanesForTest],
 *      [TmuxSessionViewModel.armConnectedBlankWatchdogForTest],
 *      [TmuxSessionViewModel.setSwitchHidesTerminalForTest]). These build a
 *      [TmuxSessionViewModel.RuntimeRefreshGuard] pinned to the live runtime
 *      exactly the way the production reveal call sites do, so the cluster runs
 *      against a known runtime without driving the whole connect coroutine
 *      state machine.
 *
 * The #721 boundary (a blank active-pane reveal must KEEP the loading overlay
 * rather than reveal a black Connected pane, then heal once a frame lands) is
 * exercised end-to-end through the real connect() reveal path in
 * [emptyActivePaneSeedKeepsOverlayThenWatchdogHealsIt].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReseedBlankWatchdogCharacterizationTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every VM created during a test is tracked so [runVmTest] can tear it down
    // deterministically INSIDE the runTest body (before runTest's uncompleted-
    // coroutine check). The reseed/watchdog cluster launches into the VM's
    // [bridgeScope] (parented to viewModelScope, which here runs on the test
    // scheduler), so a test that advances time past a disconnect can leave a
    // VM-owned coroutine parked on a delay — `clearForTest()` cancels the scope
    // and drains it, keeping the suite free of `UncompletedCoroutinesError`.
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // EPIC #792 Slice D: disable the VM LivenessProbe auto-start — its infinite
        // periodic `delay` loop would otherwise spin `advanceUntilIdle()` forever on
        // this virtual-clock Main (this file does not use MainDispatcherRule).
        com.pocketshell.app.tmux.LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            // Tear down every VM the body created, draining its background
            // coroutines on the virtual clock so runTest sees a quiescent scope.
            for (vm in createdVms) {
                // Don't park the runtime for a background grace — we want a
                // clean, immediate teardown (cancel viewModelScope, close the
                // connection), not a deferred handoff that keeps timers alive.
                runCatching { vm.setProcessForegroundForClearedForTest(false) }
                runCatching { vm.clearForTest() }
            }
            advanceUntilIdle()
            createdVms.clear()
            com.pocketshell.app.tmux.LivenessProbeTestOverride.clear()
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
        // Issue #926: pin the seed-IO dispatcher (the off-Main hop for the
        // attach/reattach `capture-pane`/`list-panes` IO) to the shared
        // virtual-clock scheduler so the round-trips run inline on the test
        // clock and `advanceUntilIdle` drains them deterministically. Production
        // defaults to `Dispatchers.IO` (a real thread off the UI thread).
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
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
        // Issue #886: suppress the connect()-auto-armed blank-watchdog so the
        // focused watchdog tests below drive ONE manually-armed watchdog in
        // isolation (the auto-armed one would otherwise run a SECOND concurrent
        // watchdog over the same blank pane and, on exhaustion, surface the #886
        // retryable error mid-setup). The dedicated #886 end-to-end stuck-reveal
        // test uses [connectVmExhaustingWatchdog], which leaves auto-arm ENABLED.
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        // Issue #973 (v0.4.18 regression): these tests characterize the BLANK
        // watchdog's reseed behavior in isolation. The #966/#967 stale-render
        // watchdog is a SEPARATE net (its own E2E coverage); the blank watchdog
        // now hands off to it when it recovers a frame, which issues its own
        // `capture-pane`. Suppress the stale-render auto-arm here so the blank
        // watchdog's capture-count assertions stay focused on the blank reseed.
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
     * Issue #1177 (black-screen GAP B): connect a VM the way [connectVm] does —
     * suppress the connect()-auto-armed BLANK watchdog so a manually-armed one runs
     * in isolation — but LEAVE the #966/#1166 stale-render auto-arm ENABLED (the
     * production default). This lets the blank-watchdog EXHAUSTION hand-off actually
     * arm the stale-render heal watchdog (the fix), which a stale-render-disabled VM
     * would silently suppress.
     */
    private fun TestScope.connectVmReattachBlack(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
        // Isolate the manually-armed blank watchdog (as connectVm does), but keep
        // the stale-render auto-arm ON so the GAP B exhaustion hand-off can fire.
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
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
     * Issue #1177: a recovery `capture-pane` frame rich enough to clear the
     * STALE_RENDER_MIN_CAPTURE_CHARS floor (a single short line is below it, so the
     * heal oracle would not fire on it). The marker line proves the healed frame
     * landed on the pane's grid.
     */
    private fun issue1177RecoveredFrame(): List<String> = buildList {
        add("ISSUE1177-REATTACH-HEALED — recovered viewport after silent-reattach")
        repeat(24) { add("recovered context row $it xxxxxxxxxxxxxxxxxxxxxxxxxxxx") }
    }

    /**
     * Issue #886: connect a VM whose active pane never seeds, leaving the
     * connect()-auto-armed blank-watchdog at the PRODUCTION bound, then drain to
     * idle so the watchdog runs to EXHAUSTION end-to-end through the real
     * connect/reveal path. This is the strongest #886 proof: the retryable error
     * surfaces from the real connect path, not a manually-armed watchdog.
     */
    private fun TestScope.connectVmExhaustingWatchdog(
        client: FakeTmuxClient,
    ): TmuxSessionViewModel {
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

    // ---------------------------------------------------------------------
    // (a) reseedBlankVisiblePanes — a blank visible pane gets re-seeded.
    // ---------------------------------------------------------------------

    @Test
    fun reseedBlankVisiblePanesReseedsABlankPaneFromCapture() = runVmTest {
        // Attach a session whose active pane's seed came back EMPTY: the pane
        // row resolves (session attaches) but the emulator stays BLACK — the
        // #662 symptom.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "precondition: an empty-seed pane must be blank (the black-pane symptom)",
            pane.terminalState.visibleScreenIsBlank(),
        )

        // Queue a recovery capture and invoke the cluster against the live
        // runtime guard.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf("ISSUE722-RESEED-A"), isError = false),
        )
        val capturesBefore = client.captureCount()
        vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        // The cluster issued a fresh capture-pane for the blank pane...
        assertTrue(
            "reseedBlankVisiblePanes must issue a capture-pane for a blank pane " +
                "(captures: ${client.captureCount()} vs $capturesBefore)",
            client.captureCount() > capturesBefore,
        )
        // ...and the recovered frame healed the pane (no longer black).
        assertFalse(
            "the pane must be healed (non-blank) after the reseed",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the recovered frame must be on the pane's grid",
            renderedTranscriptFor(pane).contains("ISSUE722-RESEED-A"),
        )
    }

    @Test
    fun reseedBlankVisiblePanesIsANoOpForAlreadyPaintedPanes() = runVmTest {
        // Characterize the cheap-by-design property: a pane that already shows
        // content costs NOTHING — reseedBlankVisiblePanes skips it entirely.
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(
            "precondition: the seeded pane is painted (non-blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )

        val capturesBefore = client.captureCount()
        vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        assertEquals(
            "reseedBlankVisiblePanes must NOT capture a pane that already shows " +
                "content (it is a blank-only safety net)",
            capturesBefore,
            client.captureCount(),
        )
    }

    @Test
    fun reseedBlankVisiblePanesIsANoOpWhenNoClientIsAttached() = runVmTest {
        // The very first guard the cluster checks: no live client -> no-op.
        // [currentRuntimeGuardForTest] returns null when nothing is attached,
        // and reseedBlankVisiblePanes(null) must short-circuit on `clientRef`.
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()

        assertEquals(
            "no runtime is attached, so the guard seam returns null",
            null,
            vm.currentRuntimeGuardForTest(),
        )
        vm.reseedBlankVisiblePanesForTest(null)
        advanceUntilIdle()
        assertTrue("no panes exist without a connect", vm.panes.value.isEmpty())
    }

    @Test
    fun reseedBlankVisiblePanesAbortsForASupersededRuntimeGuard() = runVmTest {
        // Characterize the runtime-guard abort: a guard captured BEFORE a
        // switch/reconnect bumped the generation must STOP re-seeding — the
        // cluster's per-pane [isCurrentRuntime] check short-circuits so a stale
        // runtime never repaints over the live one. We model the supersede with
        // a guard stamped at `connectGeneration - 1` (the live client stays
        // attached — disconnecting it would trigger the VM's auto-reconnect,
        // which is a different scenario; here the runtime is superseded by a
        // generation bump, not a dropped link).
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.visibleScreenIsBlank())

        val staleGuard = requireNotNull(vm.supersededRuntimeGuardForTest()) {
            "a connected VM must yield a superseded-generation guard"
        }
        // Queue a capture that WOULD heal the pane if the guard let it through.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf("SHOULD-NOT-LAND"), isError = false),
        )
        val capturesBefore = client.captureCount()
        vm.reseedBlankVisiblePanesForTest(staleGuard)
        advanceUntilIdle()

        assertEquals(
            "a reseed gated on a superseded runtime generation must issue NO capture-pane",
            capturesBefore,
            client.captureCount(),
        )
        assertTrue(
            "and the pane stays blank — the cluster did not paint a stale frame",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    // ---------------------------------------------------------------------
    // (b) armConnectedBlankWatchdog — arms, re-seeds, fires/cancels.
    // ---------------------------------------------------------------------

    @Test
    fun connectedBlankWatchdogReseedsBlankActivePaneAndClearsLoadingOverlay() = runVmTest {
        // The watchdog's reason for existing: a Connected pane that is BLACK gets
        // re-seeded on a timer until a real frame lands, at which point the
        // loading overlay (switchHidesTerminal) is dropped.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "the session reached Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertTrue(
            "an empty-seed reveal keeps the loading overlay raised (never black-reveal)",
            vm.switchHidesTerminal.value,
        )
        assertTrue(pane.terminalState.visibleScreenIsBlank())

        // The next capture recovers the frame.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf("ISSUE722-WATCHDOG-RECOVER"), isError = false),
        )
        val capturesBefore = client.captureCount()

        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        // The watchdog delays one tick before its first reseed attempt; nothing
        // happens until virtual time crosses CONNECTED_BLANK_WATCHDOG_TICK_MS.
        runCurrent()
        assertEquals(
            "the watchdog must NOT re-seed before its first tick elapses",
            capturesBefore,
            client.captureCount(),
        )

        // Advance one tick: the watchdog wakes, re-seeds the blank pane, the
        // recovery frame lands, and it clears the overlay and stops.
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 1)
        advanceUntilIdle()

        assertTrue(
            "the watchdog must have re-seeded the blank pane on its first tick",
            client.captureCount() > capturesBefore,
        )
        assertFalse(
            "the watchdog healed the pane (non-blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertFalse(
            "once a real frame lands the watchdog drops the loading overlay",
            vm.switchHidesTerminal.value,
        )
        assertTrue(
            renderedTranscriptFor(pane).contains("ISSUE722-WATCHDOG-RECOVER"),
        )
    }

    @Test
    fun connectedBlankWatchdogClearsOverlayImmediatelyWhenPaneAlreadyPainted() = runVmTest {
        // Characterize the early-out: if the active pane is ALREADY non-blank
        // when the first tick fires, the watchdog drops the overlay and returns
        // WITHOUT issuing any reseed capture.
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(
            "the pane is painted (non-blank) before the watchdog arms",
            pane.terminalState.visibleScreenIsBlank(),
        )
        // Recreate the handed-off state: overlay raised over a (now) painted pane.
        vm.setSwitchHidesTerminalForTest(true)

        val capturesBefore = client.captureCount()
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 1)
        advanceUntilIdle()

        assertFalse(
            "a non-blank active pane makes the watchdog drop the overlay",
            vm.switchHidesTerminal.value,
        )
        assertEquals(
            "the watchdog must NOT reseed when the pane already shows content",
            capturesBefore,
            client.captureCount(),
        )
    }

    @Test
    fun connectedBlankWatchdogCancelsWhenClientDisconnects() = runVmTest {
        // Characterize the disconnect guard: the watchdog stops the instant the
        // client goes disconnected (a reconnect path owns recovery then), leaving
        // the overlay untouched rather than re-seeding a dead client.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.visibleScreenIsBlank())
        assertTrue(vm.switchHidesTerminal.value)

        // Isolate the WATCHDOG's disconnect guard from the VM's reconnect ladder:
        //   - grace=0 makes the silent-reattach grace a no-op (it would otherwise
        //     spin reattaching the deliberately-always-disconnected fake client), and
        //   - an ExplicitClose disconnect reason does NOT schedule an auto-reconnect
        //     (only ReaderEof/ReaderException/CommandTimeout do).
        // So the disconnect settles cleanly in bounded virtual time and the only
        // behavior under test is the watchdog bailing on a disconnected client.
        // (The reason is irrelevant to the watchdog — it bails on ANY disconnect —
        // so this stays a faithful characterization of its disconnect guard.)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L)

        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        runCurrent()

        // Client drops before the first tick fires.
        client.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitClose,
                source = "test_watchdog_disconnect",
                intent = "characterization",
            ),
        )
        val capturesBefore = client.captureCount()
        // Drive past the watchdog's first tick. With the reattach grace at 0 the
        // disconnect settles, so it is safe to drain to idle: the watchdog wakes
        // on its tick, observes the disconnected client, and returns WITHOUT
        // issuing a capture — the behavior under test.
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 1)
        advanceUntilIdle()

        assertEquals(
            "a disconnected client must stop the watchdog before it re-seeds",
            capturesBefore,
            client.captureCount(),
        )
        assertTrue(
            "the watchdog leaves the overlay raised when it bails on a dead client",
            vm.switchHidesTerminal.value,
        )
    }

    @Test
    fun connectedBlankWatchdogIsBoundedWhenEveryCaptureStaysEmpty() = runVmTest {
        // Characterize the bound: when every reseed capture comes back EMPTY (a
        // persistently degraded link), the watchdog re-seeds each tick but STOPS
        // after CONNECTED_BLANK_WATCHDOG_MAX_TICKS rather than spinning forever.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.visibleScreenIsBlank())
        assertTrue(vm.switchHidesTerminal.value)
        // No further capture responses queued -> every capture is the default
        // empty reply, so the pane stays blank for the whole watchdog run.

        val capturesBefore = client.captureCount()
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)

        // Drive past the bound. Each tick triggers one reseed attempt (itself a
        // bounded empty-capture retry loop). advanceUntilIdle drains all of it
        // deterministically on the virtual clock.
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS * (CONNECTED_BLANK_WATCHDOG_MAX_TICKS + 5))
        advanceUntilIdle()

        assertTrue(
            "the watchdog must have attempted at least one reseed on a blank pane",
            client.captureCount() > capturesBefore,
        )
        // And it terminated (bounded). Drive a lot more virtual time: no further
        // captures are issued, proving the loop ended rather than spinning.
        val capturesAfterBound = client.captureCount()
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS * 50)
        advanceUntilIdle()
        assertEquals(
            "the watchdog is bounded by CONNECTED_BLANK_WATCHDOG_MAX_TICKS; it must " +
                "issue NO further captures after the bound",
            capturesAfterBound,
            client.captureCount(),
        )
    }

    // ---------------------------------------------------------------------
    // (d) Issue #886 — the stuck-attach-reveal safety net (Part A).
    //     When the blank-watchdog exhausts WITHOUT a frame ever landing (the
    //     seed wedged behind a streaming agent channel), the reveal must NOT
    //     stay an infinite "Attaching…" — it surfaces a retryable error + the
    //     #823 Reconnect affordance.
    // ---------------------------------------------------------------------

    @Test
    fun stuckAttachRevealSurfacesRetryableErrorAndReconnectInsteadOfInfiniteSpinner() = runVmTest {
        // The reported #886 scenario, END-TO-END through the real connect/reveal
        // path: a CONNECTED session (green dot) whose active pane never seeds
        // (every capture-pane comes back empty — the wedged-seed stand-in for the
        // capture stuck behind a streaming agent channel). Before #886 the
        // connect()-auto-armed watchdog fell off the end SILENTLY, leaving
        // switchHidesTerminal raised forever (the infinite "Attaching…" spinner).
        // Now it must surface a retryable error + the #823 Reconnect affordance.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVmExhaustingWatchdog(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        // The pane row resolved (the session attached, green dot) but it is BLACK.
        assertTrue(
            "the session attached on a Connected channel but the pane never seeded",
            pane.terminalState.visibleScreenIsBlank(),
        )

        // Part A: the spinner must be GONE — the user is never left on an
        // infinite "Attaching…". (connectVmExhaustingWatchdog drained to idle, so
        // the auto-armed watchdog already exhausted its 20 ticks.)
        assertFalse(
            "the loading overlay must be dropped on watchdog exhaustion — never an " +
                "infinite Attaching spinner (#886)",
            vm.switchHidesTerminal.value,
        )
        // The reveal projects to the honest-error surface (RevealState.Error,
        // not retrying) so the screen shows a clear message, not a spinner.
        val reveal = vm.revealState.value
        assertTrue(
            "the reveal must be the honest-error surface (was $reveal)",
            reveal is com.pocketshell.core.connection.RevealState.Error &&
                !reveal.retrying,
        )
        // The view-facing status is Failed with a "Tap Reconnect" message.
        val status = vm.connectionStatus.value
        assertTrue(
            "the connection status must surface a retryable Failed (was $status)",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "the failure message must mention the attach stall and Reconnect",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message.contains("Reconnect"),
        )
        // And the #823 Reconnect affordance is available to escape it.
        assertTrue(
            "the Reconnect affordance must be enabled so the user can retry",
            vm.canReconnect.value,
        )
    }

    @Test
    fun watchdogThatHealsBeforeExhaustionDoesNotSurfaceAnError() = runVmTest {
        // Class coverage (the happy / shell-attach path): the safety net must
        // ONLY fire on genuine exhaustion. A pane that seeds before the bound (a
        // frame lands) reveals Live normally — NO error, NO spurious Reconnect.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.visibleScreenIsBlank())

        // A good capture is queued — the first watchdog tick heals the pane.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf("ISSUE886-HEALED"), isError = false),
        )
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 1)
        advanceUntilIdle()

        assertFalse(
            "the healed pane drops the overlay (revealed Live)",
            vm.switchHidesTerminal.value,
        )
        assertTrue(
            "a healed reveal must NOT surface a Failed error",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val reveal = vm.revealState.value
        assertFalse(
            "a healed reveal must NOT be the honest-error surface (was $reveal)",
            reveal is com.pocketshell.core.connection.RevealState.Error,
        )
    }

    // ---------------------------------------------------------------------
    // (e) Issue #1177 (black-screen GAP B) — the SILENT-REATTACH hole.
    //     The passive/transport silent-reattach paths (:8077/:8319) arm the
    //     blank watchdog with surfaceErrorOnExhaustion=false. When the reconnect
    //     seed NEVER lands within the blank window the watchdog used to fall off
    //     the end SILENTLY (:10658) — a PERMANENT BLACK pane on a LIVE (green)
    //     transport (the maintainer's post_grace_foreground full-reconnect black,
    //     #874). The fix: on exhaustion, hand off to the lifetime stale-render
    //     heal watchdog so a seed that never landed keeps being healed against
    //     tmux's authoritative grid (a fully-black pane IS caught by the heal
    //     oracle, visibleRenderLostFrameVsCapture).
    // ---------------------------------------------------------------------

    @Test
    fun reattachBlankWatchdogExhaustionArmsStaleRenderHealInsteadOfSilentBlack() = runVmTest {
        // Model the reattach silent-black: a reconnected session (green dot) whose
        // active pane's seed keeps coming back EMPTY — the pane is BLACK on a live
        // transport, exactly the surface armConnectedBlankWatchdog(false) guards on
        // the :8077/:8319 reattach paths. Stale-render auto-arm is LEFT ON (the
        // production default) so the exhaustion hand-off can arm it.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVmReattachBlack(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "precondition: the reconnect seed came back empty -> the pane is BLACK on a live transport",
            pane.terminalState.visibleScreenIsBlank(),
        )
        // Foreground + screen-on so the stale-render watchdog actually captures
        // once it is armed (#1166 gate).
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)

        // Baseline: right after connect no stale-render watchdog is running (the
        // reveal skips arming it while the pane is blank).
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no stale-render watchdog is armed before the blank watchdog exhausts",
                it == null || it.isCancelled,
            )
        }

        // Arm the blank watchdog the way the reattach paths do
        // (armConnectedBlankWatchdogForTest passes the default
        // surfaceErrorOnExhaustion=false), and drive it to EXHAUSTION with every
        // capture empty — the pane stays black for the whole window.
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        // Each blank-watchdog tick also runs the bounded empty-capture reseed retry
        // (500ms tick + up to 4 attempts x 120ms), so 20 ticks span ~17s of virtual
        // time. Advance a comfortable margin past that so the watchdog fully
        // EXHAUSTS on a still-blank pane (the GAP B condition).
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS * CONNECTED_BLANK_WATCHDOG_MAX_TICKS * 4)
        runCurrent()

        // GAP B fix: on exhaustion the reattach path MUST hand off to the stale-
        // render heal watchdog rather than exiting silently into a permanent black.
        // RED (base): the branch returns silently -> no watchdog armed -> the pane
        // is stranded BLACK forever. GREEN (fix): a live stale-render watchdog runs.
        val staleJob = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1177: on blank-watchdog exhaustion the silent-reattach path MUST " +
                "arm the stale-render heal watchdog (not exit silently into a permanent " +
                "black on a live transport). job=$staleJob",
            staleJob != null && staleJob.isActive,
        )

        // ...and it actually HEALS the black pane: tmux's grid now carries a real
        // frame, so the next stale-render capture repaints the pane. The recovery
        // frame is rich enough to clear the STALE_RENDER_MIN_CAPTURE_CHARS floor.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = issue1177RecoveredFrame(), isError = false),
        )
        // The stale-render watchdog's first tick fires 4s after it was armed; a
        // generous advance past the widest backed-off interval guarantees a tick.
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + STALE_RENDER_WATCHDOG_TICK_MS + 100)
        runCurrent()

        assertFalse(
            "Issue #1177: the stale-render heal must repaint the black reattached pane " +
                "on a live transport — never a permanent black.",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the recovered frame must be on the pane's grid",
            renderedTranscriptFor(pane).contains("ISSUE1177-REATTACH-HEALED"),
        )
    }

    // ---------------------------------------------------------------------
    // (c) #721 boundary end-to-end — the reveal/seed gate keeps the overlay
    //     (never black) and the watchdog heals it. Driven through connect().
    // ---------------------------------------------------------------------

    @Test
    fun emptyActivePaneSeedKeepsOverlayThenWatchdogHealsIt() = runVmTest {
        // Characterizes the #721/#693 contract the reseed cluster guards: a
        // session ATTACHES (pane row resolves, channel Connected) but its active
        // pane's capture keeps coming back EMPTY during the reveal gate, so the
        // emulator never paints. The reveal must KEEP the loading overlay (never
        // reveal a black Connected pane) and hand off to the watchdog — then a
        // later capture recovers the frame and the overlay drops. Driven through
        // the REAL connect() reveal path.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)

        // The pane row resolved (the session attached) but the pane is BLACK.
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "precondition: an empty active-pane seed leaves the pane black",
            pane.terminalState.visibleScreenIsBlank(),
        )
        // The reveal gate kept the loading overlay rather than revealing black.
        assertTrue(
            "the reveal must KEEP the loading overlay (switchHidesTerminal=true) " +
                "for a blank active pane, never reveal a black Connected pane",
            vm.switchHidesTerminal.value,
        )
        assertTrue(
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Now the link recovers: queue a good capture and arm the watchdog the
        // way the reveal handed off. It heals the pane and drops the overlay.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf("ISSUE722-721-HEALED"), isError = false),
        )
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armConnectedBlankWatchdogForTest(guard)
        advanceTimeBy(CONNECTED_BLANK_WATCHDOG_TICK_MS + 1)
        advanceUntilIdle()

        assertFalse(
            "after recovery the pane is no longer black",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertFalse(
            "after recovery the watchdog drops the loading overlay",
            vm.switchHidesTerminal.value,
        )
        assertTrue(
            renderedTranscriptFor(pane).contains("ISSUE722-721-HEALED"),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers (replicated minimally from TmuxSessionWarmOpenTest, which keeps
    // these private to that class).
    // ---------------------------------------------------------------------

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

    private fun FakeTmuxClient.withSinglePaneRow(
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
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    /**
     * A single-pane session whose attach-time `capture-pane` always returns
     * EMPTY — the degraded-but-connected channel that leaves the active pane
     * black on a green dot. The list-panes row still resolves (so the session
     * "attaches"), but every capture is empty (FakeTmuxClient's default reply),
     * so the pane's emulator never paints content.
     */
    private fun FakeTmuxClient.withSinglePaneRowButEmptyCapture(
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
        // No capturePaneResponses queued: every capture falls back to the
        // FakeTmuxClient default empty response -> the pane stays black.
    }

    private fun renderedTranscriptFor(pane: TmuxPaneState): String {
        val state = pane.terminalState
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
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
