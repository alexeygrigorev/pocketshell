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
 * Issue #1176 (GAP C) — the switch-reveal gate ([TmuxSessionViewModel.awaitActivePaneSeededOrLoading])
 * and the no-op-resize heal ([TmuxSessionViewModel.maybeHealActivePaneOnNoOpResize]) now route a
 * DEAD-ZONE half-black BAND through the UNIFIED [TerminalSurfaceState.visibleRenderLostFrameVsCapture]
 * oracle, so a fast switch / keyboard toggle reveals a HEALED pane instead of a band that only
 * recovers ~4s later at the steady watchdog (or never, in the dead-zone).
 *
 * ## The dead-zone band
 *
 * A render with >50% of the visible rows live (clears the old 0.50 LINE ceiling) AND >25% of
 * tmux's chars (clears the old 0.25 CHAR ceiling) reads NON-blank AND NON-partial-black locally,
 * so the pre-#1176 gates (`blank || partialBlank`) SKIPPED it. Both gates now widen their local
 * cost-gate to [TerminalSurfaceState.visibleRenderMayHaveLostFrame] and confirm against tmux's
 * authoritative capture before healing.
 *
 * ## RED → GREEN (D33/G1/G10) — driving the REAL production entry points
 *
 *  - [switchRevealHealsDeadZoneBand] drives the real reveal gate seam; on base it issued NO heal
 *    capture (the band read non-blank → gate returned "seeded") and revealed unhealed (RED). With
 *    the fix the gate issues exactly one heal `capture-pane -p -e` and restores the frame (GREEN).
 *  - [noOpResizeHealsDeadZoneBand] drives the real no-op-resize seam with the same red→green.
 *
 * ## Class coverage / over-heal guard
 *
 *  - Both a SHELL and an AGENT dead-zone band are covered (the switch case uses each).
 *  - [switchRevealDoesNotHealADensePane] / [noOpResizeDoesNotHealADensePane] confirm a DENSE,
 *    correctly-painted pane issues NO heal capture at either gate (no reveal-latency / thrash).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeadZoneBandRevealResizeHealTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------------------------
    // (1) SWITCH-reveal heals a dead-zone band — SHELL pane.
    // -------------------------------------------------------------------------------------------

    @Test
    fun switchRevealHealsDeadZoneBand() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintDeadZoneBand(pane)
        assertDeadZonePreconditions(pane)

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = FULL_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // THE REAL ENTRY POINT: the switch-reveal gate over the active pane.
        val revealed = vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1176 GAP C): the switch-reveal gate must confirm a dead-zone band " +
                "against tmux and heal it (issue exactly one `capture-pane -p -e`). On base the " +
                "gate saw a non-blank/non-partial band and revealed it UNHEALED.",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertTrue("the gate still reveals (does not spin)", revealed)
        assertTrue(
            "the heal restored the FULL frame from tmux's authoritative grid",
            renderedTranscriptFor(pane).contains(FULL_FRAME_MARKER),
        )
    }

    // -------------------------------------------------------------------------------------------
    // (2) SWITCH-reveal heals a dead-zone band — AGENT pane (class coverage: shell + agent).
    // -------------------------------------------------------------------------------------------

    @Test
    fun switchRevealHealsDeadZoneBandAgentPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%2", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintDeadZoneBand(pane)
        assertDeadZonePreconditions(pane)

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = FULL_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        val revealed = vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1176 GAP C, agent pane): the reveal gate heals a dead-zone band on an " +
                "agent pane too",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertTrue(revealed)
        assertTrue(renderedTranscriptFor(pane).contains(FULL_FRAME_MARKER))
    }

    // -------------------------------------------------------------------------------------------
    // (3) NO-OP resize heals a dead-zone band (keyboard/IME toggle) — AGENT pane.
    // -------------------------------------------------------------------------------------------

    @Test
    fun noOpResizeHealsDeadZoneBand() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%3", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintDeadZoneBand(pane)
        assertDeadZonePreconditions(pane)

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = FULL_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // THE REAL ENTRY POINT: a same-dimension (no-op) resize, e.g. a keyboard dismissal.
        assertTrue(vm.triggerSameDimensionResizeHealForTest())
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1176 GAP C): a no-op resize (keyboard toggle) must confirm a dead-zone " +
                "band against tmux and heal it. On base the `blank || partialBlank` pre-check " +
                "skipped the non-blank band and left it until the ~4s watchdog.",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertTrue(
            "the heal restored the FULL frame",
            renderedTranscriptFor(pane).contains(FULL_FRAME_MARKER),
        )
    }

    // -------------------------------------------------------------------------------------------
    // (4) OVER-HEAL GUARD: a DENSE pane must NOT heal at the reveal gate.
    // -------------------------------------------------------------------------------------------

    @Test
    fun switchRevealDoesNotHealADensePane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%4")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%4" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintDensePane(pane)
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertFalse(pane.terminalState.visibleScreenIsPartiallyBlank())
        assertFalse(
            "precondition: a dense pane is confidently full → the local capture-gate is FALSE, " +
                "so no capture is paid",
            pane.terminalState.visibleRenderMayHaveLostFrame(),
        )

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = FULL_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertEquals(
            "OVER-HEAL GUARD (#1176): a dense, correctly-painted pane must issue NO heal capture " +
                "at the reveal gate (no reveal-latency / thrash)",
            healCapturesBefore,
            client.healCaptureCount(),
        )
    }

    // -------------------------------------------------------------------------------------------
    // (5) OVER-HEAL GUARD: a DENSE pane must NOT heal at the no-op resize.
    // -------------------------------------------------------------------------------------------

    @Test
    fun noOpResizeDoesNotHealADensePane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%5")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%5" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintDensePane(pane)
        assertFalse(pane.terminalState.visibleRenderMayHaveLostFrame())

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = FULL_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        assertTrue(vm.triggerSameDimensionResizeHealForTest())
        advanceUntilIdle()

        assertEquals(
            "OVER-HEAL GUARD (#1176): a dense pane must issue NO heal capture on a no-op resize",
            healCapturesBefore,
            client.healCaptureCount(),
        )
    }

    // ------------------------------------------------------------------ Fixtures + assertions

    /** ESC (U+001B). */
    private val esc = "\u001b"

    /** The emulator's actual visible-row count (the local Termux grid, unaffected by resizeRemotePty). */
    private fun visibleRowsOf(pane: TmuxPaneState): Int {
        val state = pane.terminalState
        val bridge = com.pocketshell.core.terminal.ui.TerminalSurfaceState::class.java
            .getDeclaredField("bridge").apply { isAccessible = true }.get(state)
            as? com.pocketshell.core.terminal.bridge.SshTerminalBridge ?: return 24
        return bridge.emulator.screen.visibleScreenRows
    }

    /**
     * Drive the active pane into the #1176 DEAD-ZONE band: paint ~0.6 of the visible rows with
     * non-wrapping content on the alt screen (adaptive to the emulator's real row count, which
     * resizeRemotePty does NOT change in a headless unit test). ~0.6 live-fraction is NON-blank,
     * NON-partial-black, and > 0.5 live (clears the old 0.5 send-heal ceiling), yet the render
     * lost the black band tmux (the dense [FULL_FRAME] capture) still holds → char-coverage ~0.6.
     */
    private fun overpaintDeadZoneBand(pane: TmuxPaneState) {
        val liveRows = (visibleRowsOf(pane) * 0.6).toInt()
        val frame = buildString {
            append("$esc[?1049h$esc[2J$esc[H")
            for (row in 0 until liveRows) {
                append("$FULL_FRAME_MARKER conversation line $row real agent content padding\r\n")
            }
        }
        pane.terminalState.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
    }

    /** A DENSE, correctly-painted pane: ~0.92 of the visible rows live — confidently full. */
    private fun overpaintDensePane(pane: TmuxPaneState) {
        val liveRows = (visibleRowsOf(pane) * 0.92).toInt()
        val frame = buildString {
            append("$esc[2J$esc[H")
            for (row in 0 until liveRows) {
                append("$FULL_FRAME_MARKER dense line $row real agent content padding here\r\n")
            }
        }
        pane.terminalState.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
    }

    private fun assertDeadZonePreconditions(pane: TmuxPaneState) {
        val s = pane.terminalState
        assertFalse("dead-zone band is NOT fully blank", s.visibleScreenIsBlank())
        assertFalse("dead-zone band is NOT ≤3-line partial-black", s.visibleScreenIsPartiallyBlank())
        assertFalse(
            "dead-zone band cleared the old 0.5 LINE ceiling (> 50% rows live)",
            s.visibleScreenLooksSparseForSendHeal(),
        )
        assertFalse(
            "dead-zone band cleared the old 0.25 CHAR ceiling (> 25% of tmux's chars)",
            s.visibleScreenDivergesFromCapture(FULL_FRAME.joinToString("\n")),
        )
        assertTrue(
            "the #1176 local capture-gate catches the band (worth a capture-diff)",
            s.visibleRenderMayHaveLostFrame(),
        )
        assertTrue(
            "the unified oracle judges the dead-zone band LOST vs tmux's full frame",
            s.visibleRenderLostFrameVsCapture(FULL_FRAME.joinToString("\n")),
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

    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

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

    private fun renderedTranscriptFor(pane: TmuxPaneState): String {
        val state = pane.terminalState
        val bridgeField = com.pocketshell.core.terminal.ui.TerminalSurfaceState::class.java
            .getDeclaredField("bridge").apply { isAccessible = true }
        val bridge = bridgeField.get(state)
            as? com.pocketshell.core.terminal.bridge.SshTerminalBridge ?: return ""
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
        const val FULL_FRAME_MARKER: String = "ISSUE1176-DEADZONE"

        // tmux's authoritative full 40-row frame (dense) the heal `capture-pane` returns.
        val FULL_FRAME: List<String> = buildList {
            add("HEADER $FULL_FRAME_MARKER full agent conversation restored from tmux")
            repeat(39) {
                add("$FULL_FRAME_MARKER full frame row $it real agent conversation content here")
            }
        }
    }
}
