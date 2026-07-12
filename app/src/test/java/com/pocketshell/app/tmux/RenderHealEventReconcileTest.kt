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
 * Issue #1353 slice R4 — REPRODUCE-FIRST (D33 / G1 / G10) proof for the EVENT-SUBMISSION entry
 * [TmuxSessionViewModel.requestReconcile]. R4's first step folds L1 (the #941/#1153 post-send
 * agent-overpaint heal, formerly the private `scheduleSendOverpaintHeal` 350 ms×4 poll) into
 * this shared reconcile-event entry, and DELETES the bespoke L1 poll. This test proves the two
 * load-bearing properties of that fold:
 *
 *  1. **Post-send heal STILL fires via the event path** — a send-triggered
 *     `requestReconcile(reason = Send)` on a SUSPECT active pane still runs the authoritative
 *     `capture-pane` heal through the single chokepoint and RESEEDS the pane. If the L1 poll
 *     were deleted WITHOUT wiring the event, no heal fires and the pane stays black → RED. With
 *     the event wired, the heal fires and restores tmux's grid → GREEN. (The full REAL send path
 *     — `sendAgentPayloadToPaneResult` / `writeInputToPaneResult` — is covered end-to-end by
 *     [PartialBlackPaneHealTest]; those tests stay green precisely because this fold preserved
 *     the post-send heal.)
 *
 *  2. **The event path goes through the R3 per-pane single-flight** — the reconcile heal routes
 *     through [TmuxSessionViewModel.healActivePaneIfStaleRender], which R3 wrapped in
 *     `renderHealCoordinator.paneHealMutex(paneId).withLock`. So while a sibling holds the pane's
 *     single-flight mutex, a `requestReconcile(Send)` for the SAME pane must WAIT on that mutex
 *     (no concurrent heal → no new M9 seed-gate race), and only heals once the mutex is released.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RenderHealEventReconcileTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) The send reconcile EVENT heals a suspect active pane (behavior preserved
    //     through the event path, not the deleted private send-only poll).
    // -------------------------------------------------------------------------

    @Test
    fun reconcileSendEventHealsSuspectActivePane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The post-send agent overpaint left the active pane partial-black (the #941 symptom).
        overpaintPartialBlack(pane)
        assertTrue(
            "precondition: the overpaint left the pane partial-black (renderLooksSuspect)",
            pane.terminalState.renderLooksSuspect(),
        )

        // Queue the ONE recovery frame tmux's grid returns on the reconcile HEAL capture.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // THE EVENT PATH: the entry the send path now calls (formerly the private
        // `scheduleSendOverpaintHeal`).
        vm.requestReconcileForTest("%1", ReconcileReason.Send)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1353 R4): a send reconcile event on a suspect active pane must run " +
                "the authoritative capture-pane heal through the chokepoint. If L1's private " +
                "poll were deleted without wiring requestReconcile, no heal fires and the pane " +
                "stays black.",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertFalse(
            "the reconcile heal restored the pane (no longer suspect)",
            pane.terminalState.renderLooksSuspect(),
        )
        assertTrue(
            "the restored pane shows tmux's authoritative content",
            renderedTranscriptFor(pane).contains(RECOVERED_FRAME),
        )
    }

    // -------------------------------------------------------------------------
    // (2) OVER-HEAL GUARD: a reconcile event on a DENSE, normally-painted pane must
    //     NOT issue any heal capture (the cheap local pre-check no-ops it).
    // -------------------------------------------------------------------------

    @Test
    fun reconcileSendEventDoesNotHealANormallyPaintedPane() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%2", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // A dense, normally-painted response — not suspect.
        val fullFrame = buildString {
            append(CLEAR_ONLY)
            repeat(32) { append("agent response line $it with real content\r\n") }
        }
        pane.terminalState.appendRemoteOutput(fullFrame.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertFalse(pane.terminalState.renderLooksSuspect())

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        vm.requestReconcileForTest("%2", ReconcileReason.Send)
        advanceUntilIdle()

        assertEquals(
            "OVER-HEAL GUARD: a reconcile event onto an already-painted pane must NOT issue any " +
                "heal capture-pane (the cheap local renderLooksSuspect pre-check no-ops it).",
            healCapturesBefore,
            client.healCaptureCount(),
        )
    }

    // -------------------------------------------------------------------------
    // (3) The reconcile event serializes behind the R3 per-pane single-flight: while
    //     a sibling holds the pane's heal mutex, the event's heal WAITS (no concurrent
    //     heal / no new M9 gate race) and only heals once the mutex is released.
    // -------------------------------------------------------------------------

    @Test
    fun reconcileSendEventSerializesBehindPerPaneSingleFlight() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%3", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        overpaintPartialBlack(pane)
        assertTrue(pane.terminalState.renderLooksSuspect())

        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        val healCapturesBefore = client.healCaptureCount()

        // A SIBLING launcher holds pane %3's single-flight mutex (the SAME instance the reconcile
        // heal will try to acquire inside healActivePaneIfStaleRender's withLock).
        val paneMutex = vm.renderHealCoordinatorForTest().paneHealMutex("%3")
        assertTrue("test precondition: acquire the pane's single-flight mutex", paneMutex.tryLock())

        // Fire the reconcile event and let it reach its heal attempt: it must BLOCK on the held
        // mutex before issuing any capture.
        vm.requestReconcileForTest("%3", ReconcileReason.Send)
        advanceUntilIdle()

        assertEquals(
            "REGRESSION (#1353 R4 / R3 single-flight): while a sibling holds pane %3's " +
                "single-flight mutex, the reconcile event's heal must WAIT — it must NOT run a " +
                "concurrent capture that could race the M9 seed gate. No heal capture yet.",
            healCapturesBefore,
            client.healCaptureCount(),
        )

        // Release the mutex: the queued reconcile heal now proceeds through the chokepoint.
        paneMutex.unlock()
        advanceUntilIdle()

        assertTrue(
            "once the single-flight mutex is released the reconcile heal runs and reseeds the pane",
            client.healCaptureCount() > healCapturesBefore,
        )
        assertFalse(
            "the reconcile heal restored the pane after it acquired the mutex",
            pane.terminalState.renderLooksSuspect(),
        )
    }

    // ------------------------------------------------------------------ Harness

    private fun overpaintPartialBlack(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "ISSUE941-LIVE-LINE 7\r\n").toByteArray(Charsets.US_ASCII),
        )
    }

    /** Count ONLY the HEAL captures (`capture-pane -p -e ...`), distinct from ack-gate polls. */
    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

    private fun recoveredFrameLines(): List<String> = buildList {
        add(RECOVERED_FRAME)
        repeat(27) { add("recovered context row $it") }
    }

    private fun renderedTranscriptFor(pane: TmuxPaneState): String {
        val state = pane.terminalState
        val bridgeField = com.pocketshell.core.terminal.ui.TerminalSurfaceState::class.java
            .getDeclaredField("bridge").apply { isAccessible = true }
        val bridge = bridgeField.get(state)
            as? com.pocketshell.core.terminal.bridge.SshTerminalBridge ?: return ""
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
        const val RECOVERED_FRAME: String = "ISSUE1353R4-RECOVERED-VIEWPORT"

        // ESC[2J ESC[H — clear screen + cursor home (the overpaint preamble).
        val CLEAR_ONLY: String = "[2J[H"
    }
}
