package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordedDiagnosticEvent
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1294 — the stale-render heal watchdog scored a capture FAILURE identically to a
 * confirmed-healthy tick and backed off to 16s exactly while the pane was black and the
 * shared `-CC` capture mutex was wedged by a Claude burst; and the #1203 surface-black
 * auto-heal sat behind the capture-success early-return, so a capture failure blocked the one
 * recovery that needs no capture at all.
 *
 * ## Mechanism (the two conflations the fix removes)
 *
 * Before #1294 [TmuxSessionViewModel.healActivePaneIfStaleRender] returned a BOOLEAN that
 * conflated "capture timed out / errored / came back empty" with "render confirmed healthy",
 * and the watchdog did `stableTicks = if (healed) 0 else stableTicks + 1` — so consecutive
 * capture FAILURES throttled the watchdog identically to consecutive confirmed-healthy ticks
 * (interval `4s << min(stableTicks, 2)`, capped 16s). The fix makes the oracle return a
 * three-state [HealOutcome] (HEALED / HEALTHY / UNVERIFIED); only HEALTHY earns the #1219
 * back-off, UNVERIFIED keeps the hot cadence, and the surface repaint runs BEFORE the capture.
 *
 * ## Red → green (captured this run)
 *
 *  - [captureFailuresKeepHotCadenceAndDoNotClimbTo16s] — the LOAD-BEARING watchdog guard: a
 *    black pane whose heal captures FAIL every tick keeps the hot 4s cadence (~15 captures /
 *    60s), NOT the backed-off 16s (~5). RED on the pre-fix scoring (Unverified conflated with
 *    healthy → backs off); GREEN with the fix.
 *  - [surfaceOnlyBlackFiresSurfaceRepaintEvenWhenCaptureTimesOut] — the surface-heal-not-gated
 *    guard: a surface-only-black (model intact, surface confirmed black) fires
 *    `requestSurfaceRepaint()` even when the heal capture TIMES OUT. RED on base (repaint sat
 *    behind the capture-success branch → never fired) = 0; GREEN = 1.
 *  - [unverifiedStreakIsRecordedInBlackFrameDiagnostics] — criterion 4: each UNVERIFIED tick
 *    fingerprints a `heal_capture_unverified` event with a climbing `unverifiedStreak`, so an
 *    export distinguishes "watchdog blind" from "watchdog throttled".
 *  - Class coverage (G2 / criterion 6): [captureTimeoutIsUnverified], [captureErrorIsUnverified],
 *    and [emptyCaptureOnLiveTransportIsUnverified] each drive one member of the UNVERIFIED class
 *    through the real oracle and assert the [HealOutcome.Unverified] verdict.
 *
 * Criterion 3 (confirmed-healthy still backs off 4s→8s→16s) and criterion 5 (a successful model
 * heal resets the back-off) are covered by [StaleRenderWatchdogGatingTest.stableForegroundPaneBacksOff]
 * and [StaleRenderWatchdogGatingTest.divergingTickSnapsCadenceBackToHot] respectively, now
 * modelled against confirmed-healthy (matching-capture) ticks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HealCaptureUnverifiedWatchdogTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Criterion 1 (LOAD-BEARING) — a black pane whose heal captures FAIL every tick keeps the
    // hot 4s cadence and does NOT climb to 16s. RED on the pre-fix scoring; GREEN with the fix.
    // -------------------------------------------------------------------------

    @Test
    fun captureFailuresKeepHotCadenceAndDoNotClimbTo16s() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The pane goes BLACK on a LIVE transport (a render bug, not a drop) — a CSI 2J
        // overpaint wipes the visible viewport.
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the pane looks LOST/suspect (black) so a failed capture is UNVERIFIED",
            pane.terminalState.renderLooksSuspect(),
        )

        // Every heal capture-pane now FAILS (throws) — models a Claude burst holding the shared
        // -CC capture mutex so the bounded seed capture times out each watchdog tick.
        client.failBestEffortOnCommandPrefix = "capture-pane"

        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        val before = client.captureCount()
        // 60s window with NO %output (pure poll — no suspect-wake shortcuts), so timing is set
        // ENTIRELY by the backoff scoring. advanceTimeBy (NOT advanceUntilIdle, which drains
        // the bounded loop to maxTicks).
        advanceTimeBy(61 * 1000L)
        runCurrent()
        val captures = client.captureCount() - before

        // With the #1294 fix every UNVERIFIED (failed) capture keeps the HOT 4s cadence, so a
        // 60s window pays ~15 captures (t=4,8,...,60). On base the failure was scored as a
        // healthy tick and the watchdog backed off 4s->8s->16s (t=4,12,28,44,60 = ~5) —
        // throttling recovery exactly while the pane was black. Assert the cadence stayed hot.
        assertTrue(
            "REGRESSION (#1294): consecutive heal-capture FAILURES on a black pane must keep " +
                "the hot 4s cadence (watchdog stays BLIND-but-fast), NOT back off to 16s. A 60s " +
                "window pays ~15 hot captures; the backed-off bug pays ~5. Observed $captures.",
            captures >= 12,
        )
    }

    // -------------------------------------------------------------------------
    // Criterion 2 (LOAD-BEARING) — a surface-only-black fires requestSurfaceRepaint() even when
    // the heal capture TIMES OUT. On base the repaint sat behind the capture-success branch, so
    // a capture failure blocked the one recovery that needs no capture at all.
    // -------------------------------------------------------------------------

    @Test
    fun surfaceOnlyBlackFiresSurfaceRepaintEvenWhenCaptureTimesOut() = runVmTest { _ ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // MODEL intact (dense frame) + SURFACE confirmed black = the #1192 surface-only-black
        // class, un-catchable by the model-vs-tmux oracle by construction.
        renderDenseFrame(pane)
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 0)
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 5L)
        assertTrue(pane.terminalState.surfaceIsBlackWhileModelHasContent())

        // The heal's capture-pane TIMES OUT (throws) — the Claude-burst / wedged-mutex window.
        client.failBestEffortOnCommandPrefix = "capture-pane"

        val repaintsBefore = pane.terminalState.surfaceRepaintRequestCountForTest()
        val outcome = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "REGRESSION (#1294): a surface-only-black must fire requestSurfaceRepaint() even " +
                "when the heal capture TIMES OUT — the surface heal must run BEFORE / independent " +
                "of the capture round-trip, not gated behind capture success (base = 0).",
            repaintsBefore + 1,
            pane.terminalState.surfaceRepaintRequestCountForTest(),
        )
        assertEquals(
            "a capture that could not confirm the render is UNVERIFIED",
            HealOutcome.Unverified,
            outcome,
        )
    }

    // -------------------------------------------------------------------------
    // Criterion 4 — each UNVERIFIED watchdog tick fingerprints a `heal_capture_unverified`
    // event with a climbing `unverifiedStreak`, so a shared-log export tells "watchdog blind"
    // (streak climbing) from "watchdog throttled" (a healthy backed-off pane).
    // -------------------------------------------------------------------------

    @Test
    fun unverifiedStreakIsRecordedInBlackFrameDiagnostics() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        client.failBestEffortOnCommandPrefix = "capture-pane"

        vm.setStaleRenderWatchdogMaxTicksForTest(1000)
        vm.setPaneLastOutputAtMsForTest("%1", 0L)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        // Three hot (4s) ticks → three UNVERIFIED heal passes (t=4, t=8, t=12).
        advanceTimeBy(13 * 1000L)
        runCurrent()

        val unverified = sink.eventsNamed("black_frame_observed")
            .filter { it.fields["class"] == "heal_capture_unverified" }
        assertTrue(
            "REGRESSION (#1294): each UNVERIFIED watchdog tick must fingerprint a " +
                "heal_capture_unverified event so an export distinguishes 'watchdog blind' from " +
                "'watchdog throttled'. Found ${unverified.size}: " +
                unverified.map { it.fields["unverifiedStreak"] },
            unverified.size >= 3,
        )
        val streaks = unverified.map { it.fields["unverifiedStreak"] as Int }
        assertEquals("the UNVERIFIED streak climbs monotonically (watchdog BLIND, not a blip)", 1, streaks[0])
        assertEquals(2, streaks[1])
        assertEquals(3, streaks[2])
    }

    // -------------------------------------------------------------------------
    // Class coverage (G2 / criterion 6) — the UNVERIFIED class covers a capture TIMEOUT, an
    // ERROR response, AND an empty-capture-on-live-transport; each is driven through the real
    // oracle on a black pane and asserted UNVERIFIED.
    // -------------------------------------------------------------------------

    @Test
    fun captureTimeoutIsUnverified() = runVmTest { _ ->
        val vm = blackPaneVm("%1") { client ->
            // The bounded seed capture times out on a wedged-but-alive channel → throws.
            client.failBestEffortOnCommandPrefix = "capture-pane"
            client.bestEffortException = TmuxClientException("tmux command timed out")
        }
        val outcome = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()
        assertEquals(
            "a heal-capture TIMEOUT on a black pane is UNVERIFIED (keep hot)",
            HealOutcome.Unverified,
            outcome,
        )
    }

    @Test
    fun captureErrorIsUnverified() = runVmTest { _ ->
        val vm = blackPaneVm("%1") { client ->
            // tmux returned an ERROR block for capture-pane.
            client.capturePaneResponses.addLast(
                CommandResponse(number = 90L, output = listOf("capture-pane: no such pane"), isError = true),
            )
        }
        val outcome = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()
        assertEquals(
            "a heal-capture ERROR response on a black pane is UNVERIFIED (keep hot)",
            HealOutcome.Unverified,
            outcome,
        )
    }

    @Test
    fun emptyCaptureOnLiveTransportIsUnverified() = runVmTest { _ ->
        val vm = blackPaneVm("%1") { client ->
            // Empty capture on a LIVE transport (the queue is empty → default empty success).
            client.capturePaneResponses.clear()
            client.defaultCaptureResponse = null
        }
        assertTrue("precondition: the transport is still connected", !vm.clientDisconnectedForTest())
        val outcome = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()
        assertEquals(
            "an EMPTY capture on a live transport over a black pane is UNVERIFIED (keep hot)",
            HealOutcome.Unverified,
            outcome,
        )
    }

    // ------------------------------------------------------------------ Harness

    /**
     * A connected VM whose active pane %<id> is rendered BLACK (a CSI 2J overpaint) on a live
     * transport, with [configureFailure] applied to stage the desired UNVERIFIED capture mode.
     */
    private fun TestScope.blackPaneVm(
        paneId: String,
        configureFailure: (FakeTmuxClient) -> Unit,
    ): TmuxSessionViewModel {
        val client = FakeTmuxClient().withSinglePaneRow("work", paneId)
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == paneId }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        configureFailure(client)
        return vm
    }

    /** Dense, correctly-painted viewport that MATCHES tmux (model intact / oracle healthy). */
    private fun renderDenseFrame(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            buildString {
                append(CLEAR_ONLY)
                repeat(28) { append("ISSUE1294 dense context row $it with real tmux content\r\n") }
            }.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

    private fun runVmTest(body: suspend TestScope.(RecordingSink) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        val sink = installRecordingDiagnosticSink()
        try {
            body(RecordingSink(sink))
        } finally {
            sink.close()
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

    private class RecordingSink(
        private val delegate: com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink,
    ) {
        fun eventsNamed(name: String): List<RecordedDiagnosticEvent> = delegate.eventsNamed(name)
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
        // Drive the watchdog / heal seams explicitly; keep connect() from auto-arming its own
        // loop so capture counts + timings stay deterministic.
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
        // ESC[2J ESC[H — a full clear + home, the overpaint that wipes a viewport to black.
        const val CLEAR_ONLY: String = "[2J[H"
    }
}
