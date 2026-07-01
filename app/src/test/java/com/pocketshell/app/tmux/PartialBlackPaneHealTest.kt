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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #941 (black-screen residual B1) — DURABLE REGRESSION GUARD for the
 * maintainer's "I sent a message and everything became black" symptom, driving the
 * REAL production [TmuxSessionViewModel] paths (the switch-reveal gate and the
 * agent send path), NOT a heal seam.
 *
 * ## The bug (root cause)
 *
 * A PARTIALLY-black active pane (one live line, the rest of the prior viewport
 * gone) was auto-healed ONLY on a within-grace reattach, a beyond-grace
 * `LifecycleReattach`, and a manual Redraw — NEVER on a plain session switch or a
 * send-triggered `%output` overpaint. The root cause: the switch-reveal gate
 * ([TmuxSessionViewModel.awaitActivePaneSeededOrLoading]) and the connected blank
 * watchdog ([TmuxSessionViewModel.armConnectedBlankWatchdog]) gated purely on
 * `TerminalSurfaceState.visibleScreenIsBlank()` — pure `transcriptText.isBlank()`.
 * A partial-black pane has ONE live line, so `isBlank()` is false; both gates
 * treated it as "painted", passed it through, and it stayed black with a green dot
 * until a manual redraw. The send path had NO heal at all.
 *
 * ## The fix
 *
 *  - The switch-reveal gate heals on `blank || partialBlank` (a full clear+repaint
 *    `capture-pane`), so a switch to a partial-black pane is restored before reveal.
 *  - The send path schedules a one-shot guarded active-pane heal after the submit
 *    Enter that fires ONLY if the pane settled partial-black/blank.
 *  - Over-heal guard: partial-black is a heuristic (a real one-line prompt looks
 *    identical), so each path heals AT MOST ONCE — never loops while it persists —
 *    and a normally-painted pane is a no-op (the heal pre-checks blank/partial).
 *
 * ## Red -> green
 *
 * Each test drives a pane into the partial-black state (a `%output`/seed overpaint:
 * `ESC[2J ESC[H` + ONE live line, the EXACT "one live line, rest black" symptom),
 * then exercises the REAL switch / send entry point. The discriminator is a
 * `capture-pane` heal AFTER the entry point and the restored content:
 *  - On base (pure-`isBlank()` gates): partial-black reads "not blank" -> NO heal
 *    capture -> the pane stays partial-black -> RED.
 *  - With the fix: exactly one heal `capture-pane` -> the queued recovery frame
 *    restores the FULL viewport -> GREEN.
 *
 * Class coverage (D31/D32-G2): switch-to-partial-black AND send-overpaint-to-black,
 * each on a shell pane AND an agent pane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PartialBlackPaneHealTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) SWITCH to a partial-black pane: the switch-reveal gate must heal it.
    //     SHELL pane. RED on base (gate passes a non-blank partial-black pane),
    //     GREEN with the fix (gate detects partial-black -> one heal capture).
    // -------------------------------------------------------------------------

    @Test
    fun switchRevealHealsPartialBlackShellPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }

        // Apply a phone grid so the emulator has real row geometry (the
        // partial-blank fraction is measured against the emulator's mRows).
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Drive the pane into the PARTIAL-black state: a clear+one-live-line
        // overpaint (the maintainer's "one live line, rest black" symptom).
        overpaintPartialBlack(pane)
        assertFalse(
            "precondition: a partial-black pane is NOT fully blank (so the pre-#941 " +
                "switch-reveal gate would pass it through unhealed)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "precondition: the pane IS partial-black (the #941 symptom)",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )

        // Queue the ONE recovery frame tmux's grid returns on the heal capture.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val capturesBefore = client.captureCount()

        // THE REAL ENTRY POINT: the switch-reveal gate runs on the active pane.
        val revealed = vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#941): the switch-reveal gate must heal a partial-black active " +
                "pane (issue exactly one capture-pane). On base the pure-isBlank() gate " +
                "saw a non-blank pane and skipped the heal -> the pane stayed black.",
            client.captureCount() > capturesBefore,
        )
        assertTrue("the gate still reveals (does not spin)", revealed)
        assertTrue(
            "the heal restored the full viewport from tmux's authoritative grid",
            renderedTranscriptFor(pane).contains(RECOVERED_FRAME),
        )
    }

    // -------------------------------------------------------------------------
    // (2) SWITCH to a partial-black AGENT pane: same gate, agent framing
    //     (class coverage — shell + agent).
    // -------------------------------------------------------------------------

    @Test
    fun switchRevealHealsPartialBlackAgentPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%2", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintPartialBlack(pane)
        assertTrue(
            "precondition: the agent pane is partial-black",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val capturesBefore = client.captureCount()

        val revealed = vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#941, agent pane): the switch-reveal gate must heal a " +
                "partial-black agent pane too (not just shell)",
            client.captureCount() > capturesBefore,
        )
        assertTrue(revealed)
        assertTrue(renderedTranscriptFor(pane).contains(RECOVERED_FRAME))
    }

    // -------------------------------------------------------------------------
    // (3) SEND-overpaint to partial-black on an AGENT pane: the post-send heal
    //     must restore it. RED on base (no post-send heal at all), GREEN with fix.
    // -------------------------------------------------------------------------

    @Test
    fun sendOverpaintHealsPartialBlackAgentPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%3", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        neutralizeSubmitAckGate(vm, client)

        // The send succeeds; the agent then overpaints the pane to partial-black
        // (a `clear`+one-line redraw) — the maintainer's "sent a message -> black".
        overpaintPartialBlack(pane)
        assertTrue(
            "precondition: after the send overpaint the pane is partial-black",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )

        // Queue the ONE recovery frame tmux's grid returns on the post-send HEAL
        // capture (`capture-pane -p -e ...`), distinct from the submit-ack poll's
        // `capture-pane -p -t` (neutralized above).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // THE REAL SEND PATH (production): drives the submit + the post-send heal.
        val result = vm.sendAgentPayloadToPaneResult("%3", "hello agent", AgentKind.Codex)
        advanceUntilIdle()

        assertTrue("the send itself must succeed", result.isSuccess)
        assertTrue(
            "REGRESSION (#941): a send whose %output overpaint left the active pane " +
                "partial-black must trigger the post-send HEAL (a `capture-pane -p -e`). " +
                "On base there was NO post-send heal and the pane stayed black until a " +
                "manual redraw.",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertFalse(
            "the post-send heal restored the pane (no longer partial-black)",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )
        assertTrue(
            "the restored pane shows tmux's authoritative content",
            renderedTranscriptFor(pane).contains(RECOVERED_FRAME),
        )
    }

    // -------------------------------------------------------------------------
    // (4) SEND-overpaint to FULLY-black on a SHELL-style pane: same post-send heal
    //     covers the fully-blank end of the class too (shell + fully-black).
    // -------------------------------------------------------------------------

    @Test
    fun sendOverpaintHealsFullyBlankPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%4")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%4" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        neutralizeSubmitAckGate(vm, client)

        // A clear-only overpaint wipes the pane FULLY black.
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the send overpaint wiped the pane fully black",
            pane.terminalState.visibleScreenIsBlank(),
        )

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        val result = vm.sendAgentPayloadToPaneResult("%4", "ls -la", AgentKind.Codex)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertTrue(
            "REGRESSION (#941, fully-black): a send that left the pane fully black must " +
                "also trigger the post-send heal",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertFalse(
            "the post-send heal restored the fully-black pane",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    // -------------------------------------------------------------------------
    // (5) OVER-HEAL GUARD: a send whose overpaint left the pane NORMALLY painted
    //     must NOT issue a heal capture (no reseed-thrash on a healthy pane).
    // -------------------------------------------------------------------------

    @Test
    fun sendDoesNotHealANormallyPaintedPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%5")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%5" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        neutralizeSubmitAckGate(vm, client)

        // A normal multi-line agent response: many live rows -> neither blank nor
        // partial-black -> the post-send heal must correctly no-op.
        val fullFrame = buildString {
            append(CLEAR_ONLY)
            repeat(20) { append("agent response line $it with real content\r\n") }
        }
        pane.terminalState.appendRemoteOutput(fullFrame.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertFalse(pane.terminalState.visibleScreenIsPartiallyBlank())

        // If the heal wrongly fired it would re-capture and overpaint the pane with
        // this banner; queue it so a spurious heal is detectable.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()
        val result = vm.sendAgentPayloadToPaneResult("%5", "another prompt", AgentKind.Codex)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        org.junit.Assert.assertEquals(
            "OVER-HEAL GUARD: a send onto an already-painted pane must NOT issue any " +
                "post-send HEAL capture-pane (no reseed-thrash on a healthy pane)",
            healCapturesBefore,
            client.healCaptureCount(),
        )
        assertFalse(
            "the already-painted pane must NOT have been overpainted by a spurious heal",
            renderedTranscriptFor(pane).contains(RECOVERED_FRAME),
        )
    }

    // -------------------------------------------------------------------------
    // (6) Issue #1138: a LIVE-STREAMING alt-screen AGENT pane (Codex/Claude) goes
    //     partial-black — only the live status line painted, upper alt-screen rows
    //     black — with NO user switch/send/reattach. The STEADY-STATE stale-render
    //     watchdog is the ONLY net on this passively-observed pane. On base its sole
    //     predicate ([visibleScreenDivergesFromCapture], 25% ceiling) MISSES the
    //     sparse alt-screen frame (the surviving status line exceeds 25% of the
    //     frame's few non-blank chars) -> the watchdog SKIPS the heal (RED). With the
    //     union predicate ([visibleRenderLostFrameVsCapture]) the watchdog heals and
    //     restores the FULL frame from tmux's authoritative capture (GREEN).
    // -------------------------------------------------------------------------

    @Test
    fun steadyStateWatchdogHealsPartialBlackAltScreenAgentPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%6", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%6" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Drive the pane onto the ALTERNATE screen and paint ONLY the live status line.
        overpaintAltScreenPartialBlack(pane)
        assertTrue(
            "precondition: the live-streaming alt-screen agent pane is partial-black (#1138)",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )
        // RED discriminator: the OLD watchdog predicate MISSES the sparse alt-screen frame,
        // so on base `healActivePaneIfStaleRenderForTest` (which used this oracle) returns
        // false and the pane stays black on a live transport.
        assertFalse(
            "RED (#1138): the #966 divergence oracle ALONE SKIPS the sparse alt-screen " +
                "partial-black — its surviving band exceeds the 25% ceiling of the frame",
            pane.terminalState.visibleScreenDivergesFromCapture(SPARSE_AGENT_FRAME.joinToString("\n")),
        )

        // Queue the FULL sparse agent frame the heal `capture-pane` returns.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = SPARSE_AGENT_FRAME, isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // THE REAL PATH: one steady-state stale-render watchdog tick over the active pane.
        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1138): the steady-state watchdog must heal a partial-black " +
                "alt-screen agent pane. On base the divergence-only predicate skipped it.",
            healed,
        )
        assertTrue(
            "the watchdog issued a heal capture-pane",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertFalse(
            "the heal restored the FULL frame (no longer partial-black)",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )
        assertTrue(
            "the restored pane shows tmux's authoritative content (the upper rows repainted)",
            renderedTranscriptFor(pane).contains(AGENT_FRAME_MARKER),
        )
    }

    // -------------------------------------------------------------------------
    // (7) Issue #1138 over-heal guard: the SAME steady-state watchdog must NOT heal
    //     when tmux's alt-screen capture is ALSO near-empty (the #807 by-design void:
    //     the agent's OWN intentionally-cleared frame). No lost frame to restore ->
    //     no reseed-thrash every 4s on a legitimately-empty pane.
    // -------------------------------------------------------------------------

    @Test
    fun steadyStateWatchdogDoesNotHealByDesignAltScreenVoid() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%7", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%7" }

        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintAltScreenPartialBlack(pane)
        assertTrue(pane.terminalState.visibleScreenIsPartiallyBlank())

        // tmux ALSO returns only the status line — the agent genuinely cleared its frame.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 98L, output = listOf(" Working 7  esc to interrupt"), isError = false),
        )

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertFalse(
            "OVER-HEAL GUARD (#1138/#807): when tmux's alt-screen capture is ALSO near-empty " +
                "there is no lost frame to restore — the watchdog must NOT heal",
            healed,
        )
        assertTrue(
            "the pane is left untouched (still partial-black) — no reseed-thrash on a " +
                "by-design void",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
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

    /**
     * Drive a pane into the PARTIAL-black state: a clear + ONE live line, the
     * "one live line, rest black" symptom (#941). NOT fully blank (the line is
     * present) but the vast majority of the grid is empty.
     */
    private fun overpaintPartialBlack(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "ISSUE941-LIVE-LINE 7\r\n").toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * Issue #1138: drive the pane onto the ALTERNATE screen (where Codex/Claude run their
     * full-screen TUI) and paint ONLY the live status line at the bottom (cursor-addressed) —
     * the maintainer's partial-black: the agent's status line repaints locally while the
     * upper alt-screen rows stay black.
     */
    private fun overpaintAltScreenPartialBlack(pane: TmuxPaneState) {
        val esc = ""
        val frame = "$esc[?1049h$esc[2J$esc[H$esc[24;1H Working 7  esc to interrupt streaming the reply"
        pane.terminalState.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
    }

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

    /**
     * Count ONLY the post-send HEAL captures (`capture-pane -p -e ...`, issued by
     * the seed [TmuxClient.captureWithCursor]), distinct from the submit-ack poll's
     * `capture-pane -p -t` (no `-e`). This isolates the #941 heal from the unrelated
     * #869 ack-gate captures that also fire on the send path.
     */
    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

    /**
     * Neutralize the #869 submit-ack gate so its `capture-pane -p -t` poll does not
     * consume the heal's queued recovery frame or skew the heal-capture count: set a
     * zero floor + a single-poll timeout, and queue ONE dummy ack frame (no needle
     * match -> the poll misses and falls through immediately). The heal's
     * `capture-pane -p -e` frame stays separately queued for the heal to consume.
     */
    private fun neutralizeSubmitAckGate(vm: TmuxSessionViewModel, client: FakeTmuxClient) {
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 50L, output = listOf("ack-poll-no-match"), isError = false),
        )
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

    /**
     * The full viewport tmux's grid returns on a heal `capture-pane`: a MULTI-line
     * frame (the recovered banner + many context rows) so that after the heal the
     * pane is neither blank nor partial-black — i.e. the restore actually un-blacks
     * the pane (not a one-line frame that would itself read partial-black).
     */
    private fun recoveredFrameLines(): List<String> = buildList {
        add(RECOVERED_FRAME)
        repeat(12) { add("recovered context row $it") }
    }

    private companion object {
        const val RECOVERED_FRAME: String = "ISSUE941-RECOVERED-VIEWPORT"

        // ESC[2J ESC[H — clear screen + cursor home (the overpaint preamble).
        val CLEAR_ONLY: String = "[2J[H"

        // Issue #1138: the SPARSE alt-screen agent frame tmux's grid holds while "Working" —
        // a header, a couple of conversation lines, a large BLANK conversation area (the
        // sparse part) and the input/status line. Its non-blank content is small enough that
        // the surviving status line is > 25% of it (so the #966 divergence oracle MISSES it),
        // yet > 3 non-blank lines so the healed pane is no longer partial-black.
        const val AGENT_FRAME_MARKER: String = "ISSUE1138-AGENT-FRAME"
        val SPARSE_AGENT_FRAME: List<String> = buildList {
            add("HEADER $AGENT_FRAME_MARKER codex podwiki")
            add("You: fix the partial-black bug")
            add("Codex: sure, here is the plan and the change")
            repeat(10) { add("") } // blank conversation area while Working (the sparse part)
            add("input box: >_")
            add(" Working 7  esc to interrupt")
        }
    }
}
