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
 * Issue #1151 — REPRODUCE-FIRST (D33/G10) JVM proof that the manual Redraw and the
 * attach / within-grace / switch / foreground-return / no-op-resize / reconnect
 * reseed NEVER inject a Ctrl+L (0x0C) keystroke into the pane, AND still reseed the
 * pane with fresh authoritative content (never a stale / black frame).
 *
 * THE BUG (maintainer dogfood, #1151): on session-switch and background→foreground
 * return the reseed chokepoint [TmuxSessionViewModel.reseedActivePaneForReattach] →
 * [seedPaneFromCapture] sent `tmux send-keys -t <pane> C-l` (the #989
 * `forceFullRepaint` nudge) to "make an idle agent repaint before capture". But
 * `send-keys C-l` is APPLICATION INPUT — tmux delivers the byte 0x0C into the pane's
 * PTY exactly as if the user pressed Ctrl+L. A Claude/GLM CLI binds Ctrl+L and, mid
 * task, surfaces the red "Ctrl+L is disabled while a task is in progress" banner on
 * EVERY switch / foreground-return; Codex silently swallows it (so PocketShell was
 * still sending an unsolicited control byte). The injection was universal (every
 * active pane, not agent-gated — the `forceAgentRepaint` name was misleading).
 *
 * THE FIX (#1151, all at the shared chokepoint so manual Redraw AND the automatic
 * switch / foreground / no-op-resize / reconnect paths all benefit):
 *   - DELETE the `send-keys C-l` keystroke path entirely (the `forceFullRepaint`
 *     primitive + the `forceAgentRepaint` param + the settle). No `send-keys` is
 *     ever issued to the pane on a reseed.
 *   - Reseed PURELY from `capture-pane` of tmux's authoritative server-side grid,
 *     which (in `-CC` control mode) holds the pane's full content independent of
 *     whether the app re-emits. So capture returns the current content directly.
 *   - KEEP the #989 NON-DESTRUCTIVE swap ([captureWouldClearVisibleContent] in
 *     [seedPaneFromCaptureOnce]): if a capture is momentarily near-blank it is
 *     REFUSED and the last good frame is kept, and the seed loop re-captures — so
 *     the reseed lands fresh authoritative content or keeps the prior frame, NEVER
 *     black, and NEVER a stray keystroke.
 *
 * RED on base (the load-bearing assertions that FAIL without the fix): every test
 * asserts NO `send-keys ... C-l` reaches the pane on its reseed path — on base the
 * `forceFullRepaint` default fires `send-keys -t <pane> C-l`, recorded in
 * [FakeTmuxClient.sentCommands], so the assertion fails RED. After the fix the
 * command is never sent → GREEN. The "reseed still happens / fresh content lands /
 * not cleared to black" assertions guard that the fix did not merely delete the
 * repaint.
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
    private fun TestScope.connectVmWithRichFrame(
        client: FakeTmuxClient,
    ): TmuxSessionViewModel {
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
     * A content-rich SHELL pane frame (no box-drawing chrome) — proves the fix is
     * UNIVERSAL, not agent-gated: a shell pane reseed must be C-l-free too.
     */
    private val shellRichFrame: List<String> = buildList {
        repeat(12) { i ->
            add("alex@dev:~/project\$ ls -la output-line-$i.txt  # rendered shell content")
        }
        add("alex@dev:~/project\$ ")
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
        frame: List<String> = contentRichFrame,
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
            CommandResponse(number = 2L, output = frame, isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    /**
     * Issue #1151 — the load-bearing assertion: NO Ctrl+L (0x0C) keystroke may reach
     * the pane on any reseed path. `send-keys ... C-l` is the byte the agent CLI
     * reacts to; assert none was issued (either as the `C-l` key name or the raw
     * form-feed 0x0C).
     */
    private fun assertNoCtrlLInjected(
        sentCommands: List<String>,
        context: String,
    ) {
        val offenders = sentCommands.filter {
            it.contains("send-keys") && (it.contains("C-l") || it.contains("\u000C"))
        }
        assertTrue(
            "Issue #1151: $context must NOT inject a Ctrl+L (0x0C) keystroke into the " +
                "pane — no `send-keys ... C-l` may reach the pane on a reseed " +
                "(offending commands: $offenders; all sent: $sentCommands)",
            offenders.isEmpty(),
        )
    }

    private fun assertReseedIssuedCapture(
        sentCommands: List<String>,
        context: String,
    ) {
        assertTrue(
            "Issue #1151: $context must STILL reseed the pane from tmux's server-side " +
                "grid — a `capture-pane` must be issued so the fix did not merely delete " +
                "the repaint (sent: $sentCommands)",
            sentCommands.any { it.startsWith("capture-pane") },
        )
    }

    // -----------------------------------------------------------------------
    // (1) Manual Redraw — must NOT inject Ctrl+L, must still reseed, and must not
    //     clear a content-rich pane to black.
    // -----------------------------------------------------------------------

    @Test
    fun redrawReseedsFromServerSideGridWithoutInjectingCtrlL() = runVmTest {
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
        // `capture-pane` returns the near-blank grid. The non-destructive swap keeps
        // the last frame; the point of THIS test is that NO Ctrl+L is injected.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        val sentBefore = client.sentCommands.size

        vm.redrawActivePane()
        advanceUntilIdle()

        val sentDuringRedraw = client.sentCommands.drop(sentBefore)
        // LOAD-BEARING red→green: on base the `forceFullRepaint` default sent
        // `send-keys -t %1 C-l` here; the fix removes it.
        assertNoCtrlLInjected(sentDuringRedraw, "manual Redraw")
        // The reseed still happens — a capture-pane was issued from the server grid.
        assertReseedIssuedCapture(sentDuringRedraw, "manual Redraw")

        // The pane was NOT cleared to black — the near-blank capture was REFUSED
        // (non-destructive swap kept the last frame).
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
    fun reseedRestoresFreshFrameFromServerSideGridWithoutCtrlL() = runVmTest {
        // Proves the reseed STILL gets FRESH content without any keystroke: the first
        // capture is near-blank (REFUSED by the non-destructive swap), the retry
        // capture carries the fresh server-side grid, and the pane is restored to it.
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())

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
        val sentBefore = client.sentCommands.size

        vm.redrawActivePane()
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        assertNoCtrlLInjected(sentDuring, "manual Redraw (fresh-content recovery)")
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertTrue(
            "the fresh server-side-grid frame must be restored WITHOUT any keystroke " +
                "nudge — proving `capture-pane` alone gets fresh content",
            renderedTranscriptFor(pane).contains("REDRAW-RECOVERED"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) Attach / within-grace / switch / foreground-return / no-op-resize /
    //     reconnect reseed — the SINGLE shared chokepoint
    //     [reseedActivePaneForReattach]. Its KDoc documents that these are the
    //     paths that funnel through it, so driving the chokepoint seam class-covers
    //     all of them. SAME no-Ctrl+L guarantee.
    // -----------------------------------------------------------------------

    @Test
    fun reattachReseedDoesNotInjectCtrlLAndKeepsFrame() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        val renderedBefore = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(renderedBefore > 100)

        // The within-grace reattach / switch / no-op-resize / reconnect reseed (all
        // the same chokepoint) re-captures the active pane; for an idle agent it
        // comes back near-blank.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        val sentBefore = client.sentCommands.size

        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        assertNoCtrlLInjected(
            sentDuring,
            "the attach/switch/foreground/no-op-resize/reconnect reseed chokepoint",
        )
        assertReseedIssuedCapture(sentDuring, "the reattach reseed chokepoint")
        val renderedAfter = pane.terminalState.renderedNonBlankCharCount()
        assertTrue(
            "the reattach reseed must NOT clear the content-rich pane to black " +
                "(before=$renderedBefore after=$renderedAfter)",
            renderedAfter > 100,
        )
        assertFalse(pane.terminalState.visibleScreenIsBlank())
    }

    @Test
    fun reattachReseedLandsFreshServerSideGridWithoutCtrlL() = runVmTest {
        // The common `-CC` case: the server-side grid holds the full frame, so the
        // FIRST capture is content-rich and is applied directly — no keystroke, no
        // near-blank retry. Proves fresh content still lands on the happy path.
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())

        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 9L,
                output = contentRichFrame.map { it.replace("idle", "SWITCH-FRESH") },
                isError = false,
            ),
        )
        val sentBefore = client.sentCommands.size

        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        assertNoCtrlLInjected(sentDuring, "the reattach reseed (fresh grid)")
        assertReseedIssuedCapture(sentDuring, "the reattach reseed (fresh grid)")
        assertTrue(
            "the fresh server-side-grid frame must be applied on reseed WITHOUT a " +
                "keystroke nudge",
            renderedTranscriptFor(pane).contains("SWITCH-FRESH"),
        )
    }

    @Test
    fun reattachReseedDoesNotInjectCtrlLForShellPane() = runVmTest {
        // Class coverage: the fix is UNIVERSAL (not agent-gated). A plain SHELL pane
        // reseed must also send no Ctrl+L — a shell absorbs 0x0C invisibly, which is
        // exactly why the injection went unnoticed before an agent surfaced it.
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1", frame = shellRichFrame)
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 100)

        client.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = nearBlankCapture, isError = false),
        )
        val sentBefore = client.sentCommands.size

        vm.reseedActivePaneForReattachForTest(vm.currentRuntimeGuardForTest())
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        assertNoCtrlLInjected(sentDuring, "the reattach reseed for a SHELL pane")
        assertReseedIssuedCapture(sentDuring, "the reattach reseed for a SHELL pane")
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

        // Issue #866: the link stays down for this characterization — the dropped
        // client cannot reattach. Arm the clean-outage seam so the passive-disconnect
        // grace loop FAILS its reattach cleanly (it returns before re-pointing the
        // current-client port) and bounds itself via retry+grace, instead of trying
        // to reattach. This is required because `connectVmWithRichFrame` installs a
        // single-instance factory (`setTmuxClientFactoryForTest { _, _, _ -> client }`)
        // that hands the SAME — now disconnected — client back as the reattach
        // "replacement"; a real reattach always builds a genuinely fresh client whose
        // `disconnected` is false, so re-pointing the port at it is a no-op edge. With
        // the same-instance factory, re-pointing the port re-subscribes the still-true
        // `disconnected` oracle and re-fires the drop, relaunching the grace loop
        // forever. The clean-outage seam keeps the client DISCONNECTED (the exact state
        // under test) without that unrealistic same-instance reattach churn, so the
        // Redraw-on-a-disconnected-client feedback assertion below is unchanged.
        vm.forceCleanOutageForTest = true

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
