package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentKind
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
 * Issue #1353 slice R2 — DURABLE REGRESSION GUARD that the terminal render-heal stack
 * has ONE reseed path: the unified [TmuxSessionViewModel.healActivePaneIfStaleRender]
 * chokepoint with an UNCONDITIONAL (force) mode, and NO parallel oracle-bypassing sink.
 *
 * ## The fold
 *
 * Before R2 there were TWO capture→[TerminalSurfaceState.appendRemoteOutput] paths with
 * different acceptance semantics: the oracle-gated M2 chokepoint
 * ([TmuxSessionViewModel.healActivePaneIfStaleRender], which only reseeds when the #1300
 * divergence oracle [TerminalSurfaceState.visibleRenderLostFrameVsCapture] scores the
 * render "frame lost"), and the parallel M4 sink `seedPaneFromCapture` /
 * `seedPaneFromCaptureOnce`, which every blank-watchdog / reveal / reattach caller used
 * to reseed UNCONDITIONALLY (it never consulted the oracle). R2 folds M4 into M2 as a
 * `force` mode and DELETES the parallel sink — one capture→append authority.
 *
 * ## What each test proves (the two semantics the fold must preserve)
 *
 *  - [forcedReattachReseedAppliesCaptureEvenWhenOracleWouldNotHeal] (+ the agent-pane
 *    class-coverage sibling): the LOAD-BEARING assertion — a FORCED reseed still fires
 *    the capture→append for the reattach / reveal / blank case M4 uniquely served, EVEN
 *    when the oracle would score the render HEALTHY (not lost). This is the case that a
 *    wrong fold — one that gated the forced path behind the oracle — would silently
 *    drop, reintroducing the black/blank-pane class. The GREEN assertion is that tmux's
 *    marker line lands in the render.
 *  - [oracleGatedHealLeavesTheSameNonLostCaptureUnapplied]: the CONTROL — the SAME dense
 *    pane + SAME slightly-richer capture, driven through the NON-forced (oracle-gated)
 *    path, returns [HealOutcome.Healthy] and does NOT reseed (no over-heal / reveal
 *    thrash on a confidently-dense pane). Together with the forced test this is the
 *    discriminator: identical capture, oracle-gated → no reseed, forced → reseed.
 *  - [blankWatchdogForceReseedAppliesThroughUnifiedChokepoint]: class coverage that the
 *    blank-watchdog FORCE caller ([TmuxSessionViewModel.reseedBlankVisiblePanes]) reseeds
 *    a truly-blank pane through the unified chokepoint after the fold.
 *
 * ## Red -> green (a WRONG fold, not the pre-R2 base — this slice is a refactor)
 *
 * On a variant where the forced path is (incorrectly) gated behind the oracle, the
 * forced-reseed tests go RED: the marker never lands because the oracle scores the
 * near-identical capture "not lost" and declines. With the correct force path they are
 * GREEN. Captured in the issue #1353 R2 status comment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SingleReseedPathFoldTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) FORCED reseed (the reattach/switch/foreground/blank chokepoint) must apply
    //     tmux's capture EVEN when the oracle would score the render HEALTHY — the case
    //     the deleted M4 sink uniquely served. LOAD-BEARING GREEN. SHELL pane.
    // -------------------------------------------------------------------------

    @Test
    fun forcedReattachReseedAppliesCaptureEvenWhenOracleWouldNotHeal() = runVmTest {
        val client = FakeTmuxClient().withDenseInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }

        // Precondition: the pane is densely rendered (NOT blank, NOT partial-black) — the
        // exact state the oracle scores HEALTHY, so any reseed here is UNCONDITIONAL, not
        // oracle-driven.
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertFalse(pane.terminalState.visibleScreenIsPartiallyBlank())
        assertTrue(
            "precondition: the dense pane already renders tmux's base frame",
            renderedTranscriptFor(pane).contains(BASE_MARKER),
        )

        // tmux's grid returns the SAME dense frame plus ONE extra marker line — the render
        // is missing only that 1 line, WELL under the oracle's lost-line threshold, so the
        // oracle would read the render HEALTHY (verified by the control test below).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = forcedCaptureFrame(), isError = false),
        )
        val capturesBefore = client.healCaptureCount()

        // THE REAL FORCED PATH: the attach/switch/foreground/no-op-resize/reconnect reseed
        // chokepoint (skipWhenFreshlySeeded=false → it always forces the active-pane reseed).
        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        assertTrue(
            "R2: the forced reseed must issue a capture-pane",
            client.healCaptureCount() > capturesBefore,
        )
        assertTrue(
            "R2 LOAD-BEARING: the FORCED reseed must APPLY tmux's capture even though the " +
                "oracle would score this render healthy — the marker line only tmux holds " +
                "must land in the render. A fold that gated force behind the oracle would " +
                "drop this reseed (RED).",
            renderedTranscriptFor(pane).contains(FORCED_MARKER),
        )
    }

    // -------------------------------------------------------------------------
    // (2) CONTROL: the SAME dense pane + SAME slightly-richer capture, driven through the
    //     NON-forced (oracle-gated) heal, returns Healthy and does NOT reseed. This proves
    //     the capture the forced test applied is one the oracle scores "not lost".
    // -------------------------------------------------------------------------

    @Test
    fun oracleGatedHealLeavesTheSameNonLostCaptureUnapplied() = runVmTest {
        val client = FakeTmuxClient().withDenseInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = forcedCaptureFrame(), isError = false),
        )

        // THE NON-FORCED (oracle-gated) chokepoint over the active pane.
        val outcome = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "R2: the oracle-gated path must GATE — a dense pane whose render is missing only " +
                "1 of tmux's lines is HEALTHY (not lost), so NO reseed (no over-heal / reveal " +
                "thrash). This is what makes the forced test's apply UNCONDITIONAL, not oracle-driven.",
            HealOutcome.Healthy,
            outcome,
        )
        assertFalse(
            "R2: the oracle-gated heal must NOT apply the near-identical capture — the marker " +
                "only tmux holds must NOT land when the render is scored healthy",
            renderedTranscriptFor(pane).contains(FORCED_MARKER),
        )
    }

    // -------------------------------------------------------------------------
    // (3) Class coverage: the SAME forced-reseed-bypasses-oracle guarantee on an AGENT
    //     pane (shell + agent).
    // -------------------------------------------------------------------------

    @Test
    fun forcedReattachReseedAppliesCaptureEvenWhenOracleWouldNotHealAgentPane() = runVmTest {
        val client = FakeTmuxClient().withDenseInitialFrame("work", "%2", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertFalse(pane.terminalState.visibleScreenIsPartiallyBlank())

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = forcedCaptureFrame(), isError = false),
        )
        val capturesBefore = client.healCaptureCount()

        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        assertTrue(client.healCaptureCount() > capturesBefore)
        assertTrue(
            "R2 (agent pane): the forced reseed must apply tmux's capture unconditionally",
            renderedTranscriptFor(pane).contains(FORCED_MARKER),
        )
    }

    // -------------------------------------------------------------------------
    // (4) Class coverage: the blank-watchdog FORCE caller
    //     ([TmuxSessionViewModel.reseedBlankVisiblePanes]) reseeds a truly-blank pane
    //     through the unified chokepoint after the fold.
    // -------------------------------------------------------------------------

    @Test
    fun blankWatchdogForceReseedAppliesThroughUnifiedChokepoint() = runVmTest {
        // A minimal one-line initial frame so a clear-only overpaint fully blanks the pane
        // (a dense frame would leave rows in scrollback and read non-blank).
        val client = FakeTmuxClient().withMinimalInitialFrame("work", "%3")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }

        // Wipe the pane FULLY black (a clear-only overpaint) — the blank-watchdog's target.
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the pane is fully blank (the blank watchdog's state)",
            pane.terminalState.visibleScreenIsBlank(),
        )

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = forcedCaptureFrame(), isError = false),
        )
        val capturesBefore = client.healCaptureCount()

        // THE REAL BLANK-WATCHDOG FORCE PATH.
        vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        assertTrue(
            "R2: the blank-watchdog force reseed must issue a capture through the unified chokepoint",
            client.healCaptureCount() > capturesBefore,
        )
        assertTrue(
            "R2: the blank pane is restored from tmux's authoritative grid",
            renderedTranscriptFor(pane).contains(FORCED_MARKER),
        )
        assertFalse(
            "R2: the blank pane is no longer blank after the force reseed",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    // ------------------------------------------------------------------ Harness

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
        // Isolate the reseed under test: silence the auto-armed blank + stale-render
        // watchdogs so the only capture-pane traffic is the reseed each test drives.
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
        // Apply a phone grid so the emulator has real row geometry (the oracle / blank
        // fraction is measured against the emulator's mRows).
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        return vm
    }

    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

    private fun FakeTmuxClient.withDenseInitialFrame(
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
        // The attach seed paints the dense base frame.
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = baseFrame(), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun FakeTmuxClient.withMinimalInitialFrame(
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
        const val BASE_MARKER: String = "R2FOLD-BASE-ROW"
        const val FORCED_MARKER: String = "R2FOLD-FORCED-RESEED-MARKER"

        // ESC[2J ESC[H — clear screen + cursor home.
        val CLEAR_ONLY: String = "[2J[H"

        // A DENSE 34-line base frame (well over the 0.75 suspect ceiling on a 40-row
        // viewport), so the pane renders confidently-full and the oracle scores it healthy.
        fun baseFrame(): List<String> = buildList {
            repeat(34) { add("$BASE_MARKER $it real rendered content padding here to fill the row") }
        }

        // tmux's grid returns the SAME 34 base lines PLUS one extra marker line: the render
        // is missing only 1 of the 35 capture lines, well under the oracle's
        // max(3, ceil(0.25*35)=9) lost-line threshold → the oracle scores it "not lost"
        // (verified by [oracleGatedHealLeavesTheSameNonLostCaptureUnapplied]).
        fun forcedCaptureFrame(): List<String> = buildList {
            addAll(baseFrame())
            add("$FORCED_MARKER only tmux's authoritative grid holds this line")
        }
    }
}
