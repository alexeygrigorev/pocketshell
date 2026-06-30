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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Issue #989 — REPRODUCE-FIRST (D33/G10) JVM proof that the manual Redraw and the
 * attach/within-grace reseed NEVER clear a content-rich pane to black, and that
 * Redraw FORCES the app to repaint (`send-keys C-l`) before capturing.
 *
 * The bug (confirmed in the spike): both Redraw and re-attach funnel through
 * `reseedActivePaneForReattach -> seedPaneFromCapture -> seedPaneFromCaptureOnce
 * -> appendRemoteOutput(toTerminalViewportBytes(...))`. `toTerminalViewportBytes`
 * unconditionally prepends `CSI 2J` (clear) before painting the captured lines.
 * For an IDLE alternate-screen agent (Claude/Codex) `capture-pane` returns a
 * near-blank-but-NON-EMPTY grid (whitespace rows + a stray fragment) — it passes
 * the `output.isEmpty()` guard and is painted, so the clear wipes the prior full
 * frame and the near-blank capture replaces it. Net = black-with-a-fragment (the
 * maintainer's #989 screenshot).
 *
 * The fix (all at the shared chokepoint, so Redraw AND attach/return both
 * benefit):
 *   1. FORCE the agent to repaint first (`send-keys C-l`) before capture.
 *   2. NON-DESTRUCTIVE swap: refuse to paint a near-blank capture that would clear
 *      an existing content-rich frame ([captureWouldClearVisibleContent]); keep
 *      the last frame until a materially-non-blank capture arrives.
 *   3. Feedback (Toast) when Redraw can't act (no client / disconnected / no
 *      target) instead of a silent no-op (the original #989 report).
 *
 * RED on base (the assertions that fail without the fix):
 *   - [redrawDoesNotClearContentRichPaneToBlackForIdleAltScreenAgent]:
 *     after Redraw the pane keeps its content (rendered chars stay high) — on base
 *     the near-blank capture clears it to ~0.
 *   - same test asserts `send-keys C-l` was sent before the final capture — on base
 *     Redraw never forces a repaint.
 *   - [reattachDoesNotClearContentRichPaneToBlackForIdleAltScreenAgent]: the
 *     attach/return reseed shares the chokepoint, so it inherits the same RED→GREEN.
 *   - [redrawWithNoLiveClientSurfacesFeedbackInsteadOfSilentNoOp]: a no-client
 *     Redraw emits the feedback message — on base it silently returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RedrawNonDestructiveReseedTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        com.pocketshell.app.tmux.LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            for (vm in createdVms) {
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
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
        createdVms.add(it)
    }

    /**
     * Connect a single-pane session whose attach-time capture paints a CONTENT-RICH
     * frame (a full idle Claude alt-screen), so the pane is genuinely non-blank
     * (well above the non-destructive-swap render floor) before the Redraw / reseed
     * under test. Returns the VM with its active pane painted.
     */
    private fun TestScope.connectVmWithRichFrame(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
        // Isolate the Redraw / reseed under test: silence the connect()-auto-armed
        // blank + stale-render watchdogs so the only capture-pane traffic is the one
        // the Redraw / reattach issues (they would otherwise add their own captures).
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
     * A full idle alt-screen agent frame — many populated rows, hundreds of
     * non-blank chars, so the pane is content-rich (NOT near-blank) before the
     * Redraw / reseed under test.
     */
    private val contentRichFrame: List<String> = buildList {
        add("╭──────────────────────────────────────────────╮")
        add("│  Claude Code  •  idle                          │")
        add("├──────────────────────────────────────────────┤")
        repeat(10) { i -> add("│  context line $i — real rendered content here  │") }
        add("│  > waiting for your next message…              │")
        add("╰──────────────────────────────────────────────╯")
    }

    /**
     * The idle alt-screen capture the maintainer's pane returns: whitespace rows
     * plus ONE stray status fragment. Non-empty (passes the `isEmpty()` guard) but
     * materially blank — painting it with the leading `CSI 2J` clears the rich
     * frame to black-with-a-fragment. This is the #989 capture.
     */
    private val nearBlankCapture: List<String> = buildList {
        repeat(13) { add("") }
        add("3")
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
        // The attach seed paints the content-rich frame.
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = contentRichFrame, isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    // -----------------------------------------------------------------------
    // (1) Manual Redraw — must NOT clear a content-rich pane to black, and must
    //     FORCE a repaint (`send-keys C-l`) before capturing.
    // -----------------------------------------------------------------------

    @Test
    fun redrawDoesNotClearContentRichPaneToBlackForIdleAltScreenAgent() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        val renderedBefore = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(
            "precondition: the idle agent pane is content-rich before Redraw " +
                "(rendered=$renderedBefore)",
            renderedBefore > 100,
        )
        assertFalse(pane.terminalState.visibleScreenIsBlank())

        // The idle agent does NOT re-emit its frame on its own, so the next
        // `capture-pane` returns the near-blank grid. WITHOUT the fix, Redraw paints
        // it (after a `CSI 2J` clear) and wipes the pane to black-with-a-fragment.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        val sentBefore = client.sentCommands.size

        vm.redrawActivePane()
        advanceUntilIdle()

        // FIX part 1: Redraw FORCED a repaint before it captured.
        val sentDuringRedraw = client.sentCommands.drop(sentBefore)
        val forceRepaintIndex = sentDuringRedraw.indexOfFirst {
            // Issue #1104: flags BEFORE the key operand — `send-keys -t %1 C-l`.
            // The old `send-keys C-l -t %1` ordering made tmux treat `-t` as a
            // literal key and never targeted the pane, so this must pin the
            // correct order (it FAILS if the key-before-`-t` bug returns).
            it == "send-keys -t %1 C-l"
        }
        assertTrue(
            "Redraw must send `send-keys -t %1 C-l` to FORCE the app to repaint " +
                "(sent during redraw: $sentDuringRedraw)",
            forceRepaintIndex >= 0,
        )
        val firstCaptureIndex = sentDuringRedraw.indexOfFirst { it.startsWith("capture-pane") }
        assertTrue(
            "the forced repaint must be sent BEFORE the capture, not after " +
                "(C-l@$forceRepaintIndex capture@$firstCaptureIndex)",
            firstCaptureIndex < 0 || forceRepaintIndex < firstCaptureIndex,
        )

        // FIX part 2 (the load-bearing assertion): the pane was NOT cleared to black.
        // The near-blank capture was REFUSED (non-destructive swap kept the last
        // frame). On base the rich frame collapses to ~1 char (the stray fragment).
        val renderedAfter = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(
            "Redraw must NOT clear the content-rich pane to black — the last frame " +
                "must be kept when the capture is near-blank " +
                "(rendered before=$renderedBefore after=$renderedAfter)",
            renderedAfter > 100,
        )
        assertFalse(
            "the pane must NOT be blank after Redraw",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the original rendered content must still be on the grid after Redraw",
            renderedTranscriptFor(pane).contains("context line 5"),
        )
    }

    @Test
    fun redrawRestoresTheRealFrameWhenTheForcedRepaintLands() = runVmTest {
        // The full recovery path: the forced `C-l` makes the idle agent re-emit its
        // frame, so the NEXT capture is content-rich and the pane is restored from
        // (a possibly-degraded) state back to the real content.
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())

        // First capture (immediately after C-l, agent slow) is near-blank → REFUSED;
        // the retry capture (agent has now honored C-l) carries the recovered frame.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 10L,
                output = contentRichFrame.map { it.replace("idle", "REDRAW-RECOVERED") },
                isError = false,
            ),
        )

        vm.redrawActivePane()
        advanceUntilIdle()

        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertTrue(
            "the recovered real frame must be restored after the forced repaint lands",
            renderedTranscriptFor(pane).contains("REDRAW-RECOVERED"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) Attach / within-grace return reseed — SAME chokepoint, SAME guarantee.
    // -----------------------------------------------------------------------

    @Test
    fun reattachDoesNotClearContentRichPaneToBlackForIdleAltScreenAgent() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        val renderedBefore = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(renderedBefore > 100)

        // The within-grace reattach reseed (the same path Redraw uses) re-captures
        // the active pane; for an idle agent it comes back near-blank.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        val sentBefore = client.sentCommands.size

        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        assertTrue(
            // Issue #1104: correct arg order — flags (`-t %1`) BEFORE the `C-l`
            // key operand; pins it so the key-before-`-t` bug can't silently return.
            "the attach/return reseed must FORCE a repaint too with `send-keys -t %1 C-l` " +
                "(sent: $sentDuring)",
            sentDuring.any { it == "send-keys -t %1 C-l" },
        )
        val renderedAfter = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(
            "the attach/return reseed must NOT clear the content-rich pane to black " +
                "(before=$renderedBefore after=$renderedAfter)",
            renderedAfter > 100,
        )
        assertFalse(pane.terminalState.visibleScreenIsBlank())
    }

    // -----------------------------------------------------------------------
    // (3) Redraw feedback — no live client / disconnected / no target must give
    //     user-visible feedback instead of a silent no-op.
    // -----------------------------------------------------------------------

    @Test
    fun redrawWithNoLiveClientSurfacesFeedbackInsteadOfSilentNoOp() = runVmTest {
        // A VM with NO connection — `clientRef` is null. Before #989 Redraw logged
        // and returned silently; now it must emit a user-visible feedback message.
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()

        val feedback = mutableListOf<String>()
        val collectorJob = launch(StandardTestDispatcher(testScheduler)) {
            vm.redrawFeedback.collect { feedback.add(it) }
        }
        runCurrent()

        vm.redrawActivePane()
        advanceUntilIdle()

        assertTrue(
            "Redraw with no live client must surface a user-visible message, not a " +
                "silent no-op (feedback=$feedback)",
            feedback.isNotEmpty(),
        )
        assertTrue(
            "the feedback must tell the user Redraw can't act (reconnecting) " +
                "(feedback=$feedback)",
            feedback.any { it.contains("redraw", ignoreCase = true) },
        )
        collectorJob.cancel()
    }

    @Test
    fun redrawWithDisconnectedClientSurfacesFeedback() = runVmTest {
        // Class coverage: the disconnected-client branch (a dropped link mid-session)
        // must give feedback too, not just the no-client branch.
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)

        val feedback = mutableListOf<String>()
        val collectorJob = launch(StandardTestDispatcher(testScheduler)) {
            vm.redrawFeedback.collect { feedback.add(it) }
        }
        runCurrent()

        // Drop the link the way a real disconnect does.
        client.markDisconnectedForTest(
            com.pocketshell.core.tmux.TmuxDisconnectEvent(
                reason = com.pocketshell.core.tmux.TmuxDisconnectReason.ExplicitClose,
                source = "test_redraw_disconnected",
                intent = "characterization",
            ),
        )
        runCurrent()

        vm.redrawActivePane()
        advanceUntilIdle()

        assertTrue(
            "Redraw on a disconnected client must surface feedback (feedback=$feedback)",
            feedback.any { it.contains("redraw", ignoreCase = true) },
        )
        collectorJob.cancel()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
}
