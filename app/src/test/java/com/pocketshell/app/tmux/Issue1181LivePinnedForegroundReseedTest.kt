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
 * Issue #1181 — REPRODUCE-FIRST (D33/G10) JVM proof for the BLACK terminal on a
 * notification-tap foreground-resume onto a STILL-LIVE (port-forward-pinned) connection.
 *
 * ## The maintainer's report (dogfood 2026-07-02)
 *
 * The FGS keeps the SSH/tmux connection ALIVE while backgrounded (#1159). Tapping the
 * connection notification brings the app to the foreground — and the terminal pane is
 * BLACK, even though the connection is still up.
 *
 * ## Root cause (the spike, verified in code)
 *
 * When a port-forward is active, #1159 Part 3 SUPPRESSES the bounded-grace teardown, so the
 * VM's background detach bookkeeping (`detachForBackground → pendingReattach`) NEVER runs and
 * the `-CC` control client stays live. On the notification-tap foreground return beyond the
 * grace deadline, `onAppForegrounded(resumedWithinGrace=false)` finds NO `pendingReattach`
 * and NO `pausedAutoReconnect`, so `dispatchPostGraceForegroundArmIfPending()` returns having
 * done NOTHING — no replay, no reseed, no watchdog re-arm. This is the ONLY live-connection
 * foreground path that drives ZERO repaint, so a surface that went black while backgrounded
 * (model intact) is never repainted → permanent black.
 *
 * ## The fix (this change)
 *
 * On that beyond-grace / live-connection / nothing-pending branch of `onAppForegrounded`,
 * force ONE unconditional full-viewport reseed of the active pane over the WARM client —
 * REUSING the shared #553/#721/#892 reseed chokepoint (`reseedActivePaneForReattach` →
 * `seedPaneFromCapture` → a fresh `capture-pane` + `_fullRepaintRequests` full clear+repaint),
 * exactly like the within-grace reseed and manual Redraw. No reconnect, no new lease, no
 * polling/timer (#1164).
 *
 * ## Load-bearing red→green (this is the deterministic gated sibling of the E2E)
 *
 * The one signal that ONLY the fix produces is a FRESH `capture-pane` server round-trip for
 * the active pane when we foreground onto a live no-pending connection. We connect a live VM
 * (no `pendingReattach`, exactly the pinned beyond-grace state), queue a distinct RECOVERED
 * capture, call `onAppForegrounded(false)`, and require:
 *  - a `capture-pane` was issued for the active pane (the reseed fired), and
 *  - the RECOVERED frame is applied back into the pane buffer (the reseed landed).
 *
 * On base neither happens (the branch does nothing) → RED. With the fix both happen → GREEN.
 * Class coverage: a SHELL pane and an idle AGENT/alt-screen pane (which never re-emits
 * `%output` to heal itself — the maintainer's exact pane kind).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1181LivePinnedForegroundReseedTest {

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
     * Connect a single-pane session whose attach-time capture paints a CONTENT-RICH frame,
     * so the pane is genuinely non-blank BEFORE the foreground-resume under test. The VM ends
     * up LIVE with NO `pendingReattach` — exactly the port-forward-pinned state the bug needs
     * (the pin suppressed the grace teardown, so nothing was ever stashed).
     */
    private fun TestScope.connectLiveVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
        // Isolate the foreground reseed under test: silence the connect()-auto-armed blank +
        // stale-render watchdogs so the only capture-pane traffic is the one the foreground
        // resume issues (they would otherwise add their own captures).
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

    private val contentRichFrame: List<String> = buildList {
        add("╭──────────────────────────────────────────────╮")
        add("│  Claude Code  •  idle                          │")
        add("├──────────────────────────────────────────────┤")
        repeat(10) { i -> add("│  context line $i — real rendered content here  │") }
        add("│  > waiting for your next message…              │")
        add("╰──────────────────────────────────────────────╯")
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
            CommandResponse(number = 2L, output = contentRichFrame, isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    // -----------------------------------------------------------------------
    // (1) SHELL pane — foreground-resume onto a live no-pending connection must
    //     RESEED the active pane (fresh capture-pane) so a black surface heals.
    // -----------------------------------------------------------------------

    @Test
    fun foregroundResumeOnLiveNoPendingConnectionReseedsShellPane() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.terminalState.visibleScreenIsBlank())

        // Precondition: this is the pinned beyond-grace state — NOTHING is pending to replay.
        assertFalse(
            "precondition: the live port-forward-pinned resume has NO pendingReattach " +
                "(the grace teardown was suppressed, so nothing was stashed)",
            vm.hasPendingReattachForTest(),
        )

        // The reseed's fresh capture returns a distinct RECOVERED frame (proves the pane was
        // re-captured from tmux's authoritative grid, not just re-rendered from the buffer).
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 9L,
                output = contentRichFrame.map { it.replace("idle", "RESUME-RECOVERED-SHELL") },
                isError = false,
            ),
        )
        val sentBefore = client.sentCommands.size

        // The real notification-tap foreground return beyond grace: ProcessLifecycle ON_START
        // → onAppForegrounded(resumedWithinGrace=false) with a live client + no pendingReattach.
        vm.onAppForegrounded(resumedWithinGrace = false)
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        // LOAD-BEARING red→green: the resume issued a FRESH capture-pane for the active pane
        // (the full-viewport reseed). On base this branch does NOTHING → no capture → RED.
        assertTrue(
            "foreground-resume onto a live no-pending connection must issue a fresh " +
                "capture-pane for the active pane (the #1181 reseed); sent during resume: " +
                "$sentDuring",
            sentDuring.any { it.startsWith("capture-pane") },
        )
        // And the RECOVERED frame is applied back into the pane buffer (the reseed landed).
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertTrue(
            "the resume reseed must re-apply the recaptured frame from tmux's grid",
            renderedTranscriptFor(pane).contains("RESUME-RECOVERED-SHELL"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) Idle AGENT / alt-screen pane — the maintainer's exact pane kind. It never
    //     re-emits %output to incrementally heal, so WITHOUT the resume reseed it stays
    //     black forever. Same chokepoint, same guarantee (class coverage, D32 G2).
    // -----------------------------------------------------------------------

    @Test
    fun foregroundResumeOnLiveNoPendingConnectionReseedsIdleAgentPane() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectLiveVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(vm.hasPendingReattachForTest())

        // The idle agent's next capture carries its recovered alt-screen frame — recovered
        // from tmux's authoritative `-CC` server-side grid via a fresh `capture-pane`, NOT by
        // injecting a repaint keystroke. #1151 removed the `send-keys C-l` nudge: the -CC grid
        // is authoritative, so the recapture recovers the full alt-screen frame on its own.
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 9L,
                output = contentRichFrame.map { it.replace("idle", "RESUME-RECOVERED-AGENT") },
                isError = false,
            ),
        )
        val sentBefore = client.sentCommands.size

        vm.onAppForegrounded(resumedWithinGrace = false)
        advanceUntilIdle()

        val sentDuring = client.sentCommands.drop(sentBefore)
        // #1151 contract: the resume reseed recovers the idle agent's frame from tmux's
        // authoritative `-CC` server grid via `capture-pane` — it must NOT inject a Ctrl+L
        // (0x0C) repaint keystroke into the pane (that byte is APPLICATION INPUT the agent CLI
        // reacts to). Mirror RedrawNonDestructiveReseedTest.assertNoCtrlLInjected.
        val ctrlLOffenders = sentDuring.filter {
            it.contains("send-keys") && (it.contains("C-l") || it.contains("\u000C"))
        }
        assertTrue(
            "the resume reseed must NOT inject a Ctrl+L (0x0C) keystroke into the idle agent " +
                "pane — #1151 recovers the frame from tmux's server-side grid via capture-pane, " +
                "not a `send-keys ... C-l` nudge (offenders: $ctrlLOffenders; sent: $sentDuring)",
            ctrlLOffenders.isEmpty(),
        )
        assertTrue(
            "the resume must issue a fresh capture-pane for the idle agent pane; sent: $sentDuring",
            sentDuring.any { it.startsWith("capture-pane") },
        )
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertTrue(
            "the resume reseed must re-apply the recaptured agent frame from tmux's grid",
            renderedTranscriptFor(pane).contains("RESUME-RECOVERED-AGENT"),
        )
    }

    // -----------------------------------------------------------------------
    // (3) Guard: the reseed is scoped to the LIVE-client no-pending branch. With no live
    //     client it is a no-op (no crash, no capture) — the beyond-grace non-pinned resume
    //     that DID tear down never lands here; it always has an arm to dispatch instead.
    // -----------------------------------------------------------------------

    @Test
    fun foregroundResumeWithNoLiveClientDoesNotReseedOrCrash() = runVmTest {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager =
            testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()

        // No connect() — clientRef is null. The new branch must defensively no-op.
        vm.onAppForegrounded(resumedWithinGrace = false)
        advanceUntilIdle()
        // Reaching here without an exception is the assertion (a no-op guard).
        assertFalse(vm.hasPendingReattachForTest())
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
