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
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1495 — coverage holes in the stale-render watchdog (the ONLY zero-interaction retry net
 * for a wedged pane). This file covers the two IN-SCOPE parts (Part 1 — background panes never
 * checked — is deferred to the #1353 single-reconciler scheduling and NOT covered here):
 *
 *  - **Part 2 — the 10k-tick ceiling loses the net.** Before the fix the watchdog loop was
 *    `while (tick < staleRenderWatchdogMaxTicks)`, so after the ceiling it EXITED and a long-
 *    lived foregrounded pinned pane lost its only net until an event re-arm. The fix rolls the
 *    ceiling (a rolling epoch) so the loop keeps checking for the pane's lifetime.
 *    [watchdogNetSurvivesPastTickCeilingAndHealsLaterDivergence].
 *
 *  - **Part 3 — the re-arm cancellation-window race.** The reattach / pinned-resume reseed
 *    coroutines ran `reseedActivePaneForReattach(guard)` and then a BARE
 *    `if (isCurrentRuntime(guard)) armActivePaneStaleRenderWatchdog(guard)`. A cancel / teardown
 *    throw in that reseed→arm window SKIPPED the arm, leaving a still-current runtime watchdog-
 *    less. The fix wraps it in try/finally so the re-arm still runs. Reproduced synthetically
 *    (#780 model) via [TmuxSessionViewModel.reseedArmWindowFailureForTest], on BOTH named sites:
 *    the within-grace reattach ([reseedArmWindowCancelStillArmsWatchdog_withinGraceReattach]) and
 *    the pinned beyond-grace resume ([reseedArmWindowCancelStillArmsWatchdog_pinnedBeyondGrace]).
 *
 * All three tests are JVM/Robolectric and run in the per-push Unit gate (`:app:testDebugUnitTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1495WatchdogCoverageTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Part 2 (red→green) — the watchdog net must SURVIVE past the old tick ceiling on a
    // continuously-foregrounded session and heal a divergence that lands AFTER the ceiling.
    // RED on base (`while (tick < max)` exits at the ceiling → the later divergence is never
    // healed); GREEN with the rolling-epoch fix.
    // -------------------------------------------------------------------------

    @Test
    fun watchdogNetSurvivesPastTickCeilingAndHealsLaterDivergence() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // A TINY lifetime ceiling so the loop reaches it in a handful of ticks. The bypass seam
        // arms the loop directly (auto-arm stays off, so this is the SOLE watchdog under test).
        vm.setStaleRenderWatchdogMaxTicksForTest(3)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        // The pane is BLACK on a live transport (a render bug, not a drop) AND every heal capture
        // FAILS — a persistently UNVERIFIED / "blind" pane (#1294). This is the pane that most needs
        // the net, and its net must NEVER exhaust the ceiling while it still needs healing.
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "IDLE-SPINNER-ONLY\r\n").toByteArray(Charsets.US_ASCII),
        )
        client.failBestEffortOnCommandPrefix = "capture-pane"
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Run WELL past the 3-tick ceiling. On base (`while (tick < max)`, which counts EVERY tick)
        // the loop has long since EXITED here; with the fix each UNVERIFIED tick resets idleTicks so
        // the net rolls indefinitely while the pane needs work.
        advanceTimeBy(180_000L)
        runCurrent()

        // Secondary signal: on the fix the loop is still armed past the ceiling; on base it is
        // completed. (The load-bearing assertion is the heal below.)
        val job = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1495 (Part 2): the watchdog must still be ARMED past the old tick ceiling while " +
                "the pane keeps needing heals (UNVERIFIED resets the ceiling, not a permanent stop). job=$job",
            job != null && job.isActive,
        )
        assertFalse(
            "precondition: the diverged pane does NOT yet carry the recovered frame",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )

        // The capture RECOVERS (the -CC mutex frees / the transport un-wedges): tmux's authoritative
        // grid is now readable, so the still-armed watchdog repaints the diverged idle pane.
        client.failBestEffortOnCommandPrefix = null
        client.defaultCaptureResponse =
            CommandResponse(number = 91L, output = idleClaudeRecoveredFrame(), isError = false)

        // LOAD-BEARING: the watchdog that survived the ceiling captures tmux's grid, sees the
        // divergence, and repaints the diverged idle pane. RED on base — the loop exited at the
        // ceiling, so the divergence is never healed and the pane stays black forever.
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + 1_000L)
        runCurrent()
        assertTrue(
            "Issue #1495 (Part 2): the watchdog must keep healing PAST the old tick ceiling — a " +
                "long-lived foregrounded pane that still needs work may never lose its only net",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
    }

    // -------------------------------------------------------------------------
    // Part 2 (regression guard for the #1517 CI hang) — an armed watchdog on a BACKGROUNDED
    // (non-capturing) VM must let a virtual-clock `advanceUntilIdle()` reach idle, NOT spin the
    // delay loop forever. The #1517 fix's first cut replaced the terminating `while (tick < max)`
    // ceiling with a bare unbounded `while (true)` rolling loop; that hung `:app:testDebugUnitTest`
    // for 35 min because an unrelated backgrounded VM test (AttachmentUploadTeardownReconnectTest)
    // drains virtual time with `advanceUntilIdle()` against the auto-armed watchdog. The hard
    // `@Test(timeout=...)` makes the regression DETERMINISTIC: if the idle loop is ever unbounded
    // again this test hangs and trips the timeout (RED); with the bounded idle ceiling the loop
    // exits after `staleRenderWatchdogMaxTicks` non-productive ticks and the drain completes (GREEN).
    // -------------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun backgroundedWatchdogLetsAdvanceUntilIdleReachIdle() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // A small ceiling so the bounded idle drain finishes quickly; BACKGROUNDED so every tick is
        // the non-productive gate-skip that must count toward the ceiling and exit the loop.
        vm.setStaleRenderWatchdogMaxTicksForTest(5)
        vm.setProcessForegroundForClearedForTest(false)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()
        assertTrue(
            "precondition: the watchdog is armed before the drain",
            vm.staleRenderWatchdogJobForTest()?.isActive == true,
        )

        // LOAD-BEARING: this drains ALL scheduled virtual-time tasks. With an unbounded loop it
        // never returns (the #1517 hang); with the bounded idle ceiling the backgrounded loop
        // exits after 5 non-productive ticks so the scheduler goes idle and this call returns.
        advanceUntilIdle()

        val job = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1495/#1517: a backgrounded armed watchdog must TERMINATE its idle loop (so a " +
                "virtual-clock advanceUntilIdle reaches idle), not spin forever. job=$job",
            job == null || !job.isActive,
        )
    }

    // -------------------------------------------------------------------------
    // Part 3 (red→green) — the reseed→arm CANCELLATION-WINDOW race. A cancel/teardown throw
    // between the reseed and the re-arm line must NOT leave a still-current runtime watchdog-
    // less. Reproduced synthetically (#780 model) via `reseedArmWindowFailureForTest`. RED on
    // base (bare `if (isCurrentRuntime) arm` — the throw skips it → un-armed); GREEN with the
    // try/finally fence. Two sites for class coverage (G2): within-grace reattach + pinned resume.
    // -------------------------------------------------------------------------

    @Test
    fun reseedArmWindowCancelStillArmsWatchdog_withinGraceReattach() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        vm.markActiveLeaseWarmForTest()

        // Precondition: no steady watchdog (connectVm disabled auto-arm — simulating a superseded/
        // ceiling'd connect-time loop). Enable production auto-arm so the reattach path is the SOLE
        // arming opportunity.
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no watchdog before the reattach",
                it == null || it.isCancelled,
            )
        }
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        // Inject the cancellation into the reseed→arm window: the reseed completes, then the
        // window throws (the exact interleaving the issue names). On base this skips the arm.
        vm.reseedArmWindowFailureForTest =
            CancellationException("synthetic #1495 reseed→arm cancel (within-grace)")

        // The real within-grace background→foreground reattach reseed (site B).
        vm.onAppForegrounded(resumedWithinGrace = true)
        repeat(8) { runCurrent() }

        // LOAD-BEARING (a): the reseed→arm window was cancelled, yet the still-current runtime must
        // NOT be left watchdog-less. RED on base — the cancel skips the bare arm line → null.
        val armed = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1495 (Part 3): a cancel in the reseed→arm window must NOT leave the still-" +
                "current runtime watchdog-less — the finally fence must re-arm it. job=$armed",
            armed != null && armed.isActive,
        )

        // LOAD-BEARING (b): the re-armed watchdog is a LIVE net — it heals a divergence that lands
        // after the cancelled reattach (not merely a launched job).
        vm.reseedArmWindowFailureForTest = null
        assertHealsIdleDivergence(vm, client, pane)
    }

    @Test
    fun reseedArmWindowCancelStillArmsWatchdog_pinnedBeyondGrace() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        client.defaultCaptureResponse =
            CommandResponse(number = 80L, output = listOf("work ready"), isError = false)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Pinned beyond-grace state: live client, NOTHING pending → onAppForegrounded(false) lands
        // on reseedActivePaneOnLivePinnedForeground() (site A).
        assertFalse(
            "precondition: the live pinned resume has NO pendingReattach",
            vm.hasPendingReattachForTest(),
        )
        vm.staleRenderWatchdogJobForTest().let {
            assertTrue(
                "precondition: no watchdog before the pinned resume",
                it == null || it.isCancelled,
            )
        }
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)

        vm.reseedArmWindowFailureForTest =
            CancellationException("synthetic #1495 reseed→arm cancel (pinned)")

        // The real notification-tap foreground return BEYOND grace onto the live pinned client
        // with nothing pending → reseedActivePaneOnLivePinnedForeground() (site A).
        vm.onAppForegrounded(resumedWithinGrace = false)
        repeat(8) { runCurrent() }

        val armed = vm.staleRenderWatchdogJobForTest()
        assertTrue(
            "Issue #1495 (Part 3): a cancel in the pinned-resume reseed→arm window must NOT leave " +
                "the still-current runtime watchdog-less — the finally fence must re-arm it. job=$armed",
            armed != null && armed.isActive,
        )

        vm.reseedArmWindowFailureForTest = null
        assertHealsIdleDivergence(vm, client, pane)
    }

    // ------------------------------------------------------------------ Harness

    /** Drive an idle-pane divergence and assert the (re-)armed watchdog heals it from tmux's grid. */
    private fun TestScope.assertHealsIdleDivergence(
        vm: TmuxSessionViewModel,
        client: FakeTmuxClient,
        pane: TmuxPaneState,
    ) {
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "IDLE-SPINNER-ONLY\r\n").toByteArray(Charsets.US_ASCII),
        )
        assertFalse(
            "precondition: the diverged pane does NOT yet carry the recovered frame",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
        client.defaultCaptureResponse =
            CommandResponse(number = 91L, output = idleClaudeRecoveredFrame(), isError = false)
        advanceTimeBy(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS + 1_000L)
        runCurrent()
        assertTrue(
            "Issue #1495 (Part 3): the re-armed watchdog must be a LIVE net — heal the diverged " +
                "idle pane from tmux's grid, never leave it stranded",
            renderedTranscriptFor(pane).contains("IDLE-CLAUDE-RECOVERED"),
        )
    }

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
        const val CLEAR_ONLY: String = "[2J[H"
    }
}
