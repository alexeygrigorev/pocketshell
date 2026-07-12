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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
 * Issue #1494 — REPRODUCE-FIRST (D33 / G10) proof, on the REAL heal chokepoint through the
 * production VM path, that ONE uninterruptible capture can no longer wedge the pane AND its
 * supervisor.
 *
 * ## The bug
 *
 * The per-pane heal single-flight ([RenderHealCoordinator]) and the stale-render watchdog's
 * per-tick heal call ([TmuxSessionViewModel.armActivePaneStaleRenderWatchdog]) had NO timeout.
 * The `capture-pane` ceiling ([SEED_CAPTURE_TIMEOUT_MS]) is `withTimeoutOrNull` — cooperative —
 * so a capture that blocks a `Dispatchers.IO` thread uninterruptibly (half-open socket, no read
 * timeout) never trips it: the heal body never returns, the pane's single-flight lock is held
 * indefinitely, and:
 *
 *  - the watchdog LOOP freezes inside the un-timed heal call (the supervisor stalls), and
 *  - every subsequent heal for that pane parks on the still-held lock forever.
 *
 * ## Red → green — the CLASS (both bounds)
 *
 *  - [watchdogTickTimesOutOnAWedgedHealAndKeepsTicking] — the LOOP-liveness bound. The active
 *    pane's heal capture wedges (never returns). On base the watchdog's first tick parks inside
 *    the un-timed heal and the loop never advances (exactly ONE capture attempt over a long
 *    window) — RED. With the per-tick `withTimeout` the wedged heal is abandoned at
 *    [RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS] (UNVERIFIED, hot cadence) and the loop keeps ticking
 *    (many capture attempts) — GREEN.
 *  - [laterHealIsNotPermanentlyRejectedByAWedgedSingleFlight] — the future-heal bound. One heal
 *    WEDGES holding the pane's single-flight; a later heal for the SAME pane then runs. On base
 *    it parks on `withLock` forever and issues no capture (the pane can never re-heal) — RED.
 *    With the held-too-long force-reset the later heal swaps in a fresh lock, captures, and
 *    reseeds the pane — GREEN.
 *
 * The pure ownership + force-reset contract (and that a slow-but-alive holder is NEVER
 * force-reset — single-flight preserved) is asserted directly in [RenderHealCoordinatorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RenderHealTimeoutTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) LOOP-LIVENESS — a wedged heal capture must not freeze the watchdog loop.
    // -------------------------------------------------------------------------

    @Test
    fun watchdogTickTimesOutOnAWedgedHealAndKeepsTicking() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The pane looks LOST (black) on a LIVE transport, so each watchdog tick tries to heal it.
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the pane looks suspect so the watchdog heals it every tick",
            pane.terminalState.renderLooksSuspect(),
        )

        // Every heal `capture-pane` records then PARKS forever — models the uninterruptible
        // exec-lane read on a half-open socket that the cooperative 2.5 s ceiling never trips.
        client.suspendForeverOnCommandPrefix = "capture-pane"

        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        val guard = requireNotNull(vm.currentRuntimeGuardForTest())
        vm.armActivePaneStaleRenderWatchdogForTest(guard)
        runCurrent()

        val before = client.captureCount()
        // A generous window (well beyond several 4 s ticks). advanceTimeBy (NOT advanceUntilIdle,
        // which would race the parked heals) so the timing is driven purely by the tick cadence.
        advanceTimeBy(40_000L)
        runCurrent()
        val captures = client.captureCount() - before

        assertTrue(
            "REGRESSION (#1494): a heal whose capture wedges (never returns) must not freeze the " +
                "watchdog LOOP. On base the first tick parks inside the un-timed heal and the loop " +
                "never advances (exactly ONE capture attempt). With the per-tick withTimeout the " +
                "wedged heal is abandoned each tick and the loop keeps ticking. Observed " +
                "$captures capture attempts over 40s.",
            captures >= 3,
        )
    }

    // -------------------------------------------------------------------------
    // (2) FUTURE-HEAL — a wedged single-flight must not permanently reject later heals.
    // -------------------------------------------------------------------------

    @Test
    fun laterHealIsNotPermanentlyRejectedByAWedgedSingleFlight() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Drive the coordinator's held-too-long clock from the virtual scheduler so advancing the
        // test clock ages the wedged holder deterministically.
        vm.renderHealCoordinatorForTest().setClockForTest { currentTime }

        // The pane looks suspect so both heals reseed it.
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "ISSUE1494-LIVE-LINE 7\r\n").toByteArray(Charsets.US_ASCII),
        )
        advanceUntilIdle()
        assertTrue(pane.terminalState.renderLooksSuspect())

        // H1's heal capture PARKS in flight, holding the pane's single-flight lock — the wedged,
        // never-returning capture.
        val wedge = CompletableDeferred<Unit>()
        client.captureWithCursorGate = wedge
        val h1 = launch { vm.healActivePaneIfStaleRenderForTest() }
        runCurrent()
        val before = client.healCaptureCount()

        // Age the wedged holder past the held-too-long bound.
        advanceTimeBy(RenderHealCoordinator.HELD_TOO_LONG_MS + 2_000L)
        runCurrent()

        // H2: a later heal for the SAME pane. Its capture does NOT park and returns tmux's grid.
        client.captureWithCursorGate = null
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = recoveredFrameLines(), isError = false),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 100L, output = listOf("0,0"), isError = false),
        )
        val h2 = launch { vm.healActivePaneIfStaleRenderForTest() }
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#1494): while a WEDGED heal holds pane %1's single-flight (a capture " +
                "that never returns), a LATER heal for the same pane must NOT park on withLock " +
                "forever. On base it is permanently rejected and issues no capture; the held-too-" +
                "long force-reset lets it swap in a fresh lock and run. Heal captures before=" +
                "$before after=${client.healCaptureCount()}.",
            client.healCaptureCount() > before,
        )
        assertFalse(
            "the later heal reseeded the pane from tmux's grid after the force-reset",
            pane.terminalState.renderLooksSuspect(),
        )

        wedge.complete(Unit)
        advanceUntilIdle()
        h1.cancel()
        h2.cancel()
    }

    // ------------------------------------------------------------------ Frames

    private fun recoveredFrameLines(): List<String> = buildList {
        add("ISSUE1494-RECOVERED-FRAME")
        repeat(27) { add("recovered context row $it with real rendered content") }
    }

    // ------------------------------------------------------------------ Harness

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

    /** HEAL captures (`capture-pane -p -e ...`), distinct from ack-gate polls. */
    private fun FakeTmuxClient.healCaptureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") && it.contains(" -e") }

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
        // ESC[H ESC[2J — home + clear, the overpaint that wipes the viewport to black.
        const val CLEAR_ONLY: String = "\u001B[2J\u001B[H"
    }
}
