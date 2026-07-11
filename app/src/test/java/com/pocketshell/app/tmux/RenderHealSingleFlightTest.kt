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
 * Issue #1353 slice R3 — REPRODUCE-FIRST (D33 / G1 / G10) proof for the PER-PANE
 * SINGLE-FLIGHT guard around the render-heal chokepoint
 * ([TmuxSessionViewModel.healActivePaneIfStaleRender]), on the REAL bridge + real emulator +
 * the real chokepoint (two concurrent heals through the production VM path, not a proxy).
 *
 * ## The race (spike §3 "double-fire / race")
 *
 * `healActivePaneIfStaleRender` had NO cross-launcher single-flight: the send-overpaint heal
 * (L1), the stale-render watchdog (L2), and the reveal/switch heal (L5) all call it on the
 * SAME active pane with no shared lock. The M9 seed gate is a SINGLE latch on the bridge, so
 * two heals could interleave: heal H_apply closes the gate and PARKS on its (in-flight, slow)
 * `capture-pane` while a NEWER live `%output` delta buffers behind the gate; a SIBLING heal
 * H_open (a fast/empty capture — the #1294 starved-capture class) then reaches its
 * `openSeedGateWithoutSeed` finally and OPENS the gate, FLUSHING H_apply's buffered delta out
 * to the emulator; then H_apply's (older) snapshot lands and its `CSI 2J` clear WIPES the
 * just-flushed delta. That is the exact capture-clobbers-newer-delta class M9 was built to
 * prevent, re-introduced by concurrency. The heal's own `finally` fail-safe guards a
 * *stuck-closed* gate, NOT a *premature open* by a sibling.
 *
 * ## Red -> green (LOAD-BEARING = the no-clobber / delta-preserved property, G6)
 *
 * [concurrentHealsDoNotClobberNewerDeltaViaPrematureGateOpen] drives H_apply (parked, applies
 * tmux's rich snapshot) and H_open (fast, empty capture, opens the gate) CONCURRENTLY on one
 * pane through `healActivePaneIfStaleRenderForTest()` (the real chokepoint). On BASE (no
 * per-pane mutex) H_open crosses into H_apply's window and opens the gate before H_apply's
 * snapshot lands → the newer delta is clobbered → the delta marker is ABSENT → RED. With the
 * R3 per-pane single-flight, H_open BLOCKS on H_apply's pane mutex and cannot open the gate;
 * the delta stays buffered inside H_apply's window and `seedThenOpenGate` re-applies it ON TOP
 * of the snapshot (newest-wins) → PRESENT → GREEN.
 *
 * ## Per-pane, not global (the guard must not serialize DIFFERENT panes)
 *
 * [differentPanesAreNotSerialisedAgainstEachOther] proves the single-flight is keyed PER PANE:
 * two heals on DIFFERENT panes both reach their parked `capture-pane` at the same time (both
 * seed gates closed simultaneously) — a global lock would have blocked the second before it
 * could close its gate. (The pure ownership contract — same pane id → same mutex, different id
 * → different mutex — is asserted directly in [RenderHealCoordinatorTest].)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RenderHealSingleFlightTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -----------------------------------------------------------------------
    // (1) THE RACE — two concurrent heals on ONE pane must not clobber a newer
    //     delta via a sibling's premature seed-gate open.
    // -----------------------------------------------------------------------

    @Test
    fun concurrentHealsDoNotClobberNewerDeltaViaPrematureGateOpen() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The pane render looks SUSPECT (fragments-over-black), so BOTH heals quiesce (close
        // the seed gate) and try to reseed it — the exact state where the launchers pile up.
        pane.terminalState.appendRemoteOutput(fragmentsOverBlackFrame().toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "precondition: the render looks suspect so both heals quiesce + reseed it",
            pane.terminalState.renderLooksSuspect(),
        )
        assertFalse(
            "precondition: the newer delta marker is not already on the visible screen",
            visibleScreenTextFor(pane).contains(NEWER_DELTA_MARKER),
        )

        // H_apply's capture: tmux's authoritative RICH frame (cursor homed low so the flushed
        // delta lands below the snapshot rows, on-screen). This is the snapshot that, applied
        // with the gate wrongly opened, would clobber the delta. Reserved for the PARKED (slow)
        // capture so it is deterministic regardless of coroutine run order.
        client.gatedCaptureResponse =
            CommandResponse(number = 90L, output = richFrameLines(), isError = false)
        client.gatedCursorReply = "0,14"
        // H_open's capture: EMPTY → scores UNVERIFIED (no snapshot), then its finally OPENS the
        // seed gate — the premature open. Served from the FIFO to the FAST (unparked) capture.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = emptyList(), isError = false),
        )

        // Park H_apply's capture in flight so H_open can race it.
        val applyGate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = applyGate

        // H_apply enters first: closes the seed gate, parks on the (slow) capture.
        val applyJob = launch { vm.healActivePaneIfStaleRenderForTest() }
        runCurrent()
        assertFalse(
            "H_apply must have CLOSED the seed gate around its suspect capture",
            isSeedGateOpenFor(pane),
        )

        // A NEWER live %output delta arrives while H_apply's capture is parked — buffered
        // behind the closed gate (the race window).
        feedLiveDelta(pane, "$NEWER_DELTA_MARKER\r\n")
        assertFalse(
            "the buffered delta must not paint while the gate is closed",
            visibleScreenTextFor(pane).contains(NEWER_DELTA_MARKER),
        )

        // H_open enters CONCURRENTLY (its capture does NOT park). On base it opens the gate,
        // flushing the delta out before H_apply's snapshot lands; with the R3 single-flight it
        // blocks on H_apply's pane mutex and cannot touch the gate.
        client.captureWithCursorGate = null
        val openJob = launch { vm.healActivePaneIfStaleRenderForTest() }
        runCurrent()

        // Release H_apply's parked capture; it applies the rich snapshot.
        applyGate.complete(Unit)
        advanceUntilIdle()
        applyJob.cancel()
        openJob.cancel()

        val visible = visibleScreenTextFor(pane)
        assertTrue(
            "REGRESSION (#1353 R3 concurrent premature-open clobber): a NEWER in-flight " +
                "%output delta buffered inside one heal's seed-gate window must SURVIVE a " +
                "CONCURRENT sibling heal on the same pane. On base the sibling opens the gate " +
                "before this heal's snapshot lands, so the snapshot's CSI 2J clobbers the " +
                "delta; the per-pane single-flight serializes the window so the delta stays " +
                "buffered and is re-applied on top of the snapshot. Marker missing:\n" + visible,
            visible.contains(NEWER_DELTA_MARKER),
        )
        assertTrue(
            "the authoritative tmux frame must still be on the visible screen after the heal",
            visible.contains("RECONCILED-VIEWPORT"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) PER-PANE, not global — two DIFFERENT panes heal concurrently.
    // -----------------------------------------------------------------------

    @Test
    fun differentPanesAreNotSerialisedAgainstEachOther() = runVmTest {
        // Two live panes on the same session. Pane A's heal PARKS on a (slow) capture; while it
        // is parked and holding pane A's single-flight mutex, pane B's heal must run to
        // completion and reseed pane B. A GLOBAL lock would block pane B behind pane A's held
        // lock (pane B stays black until pane A's capture returns); per-pane, pane B heals now.
        val client = FakeTmuxClient().withTwoRichPanes("work", "%1", "%2")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()
        val paneA = vm.panes.value.single { it.paneId == "%1" }
        val paneB = vm.panes.value.single { it.paneId == "%2" }

        // Pane A: suspect-but-not-blank so its active heal quiesces + parks on its capture.
        paneA.terminalState.appendRemoteOutput(fragmentsOverBlackFrame().toByteArray(Charsets.US_ASCII))
        // Pane B: fully blank so the force blank-reseed net targets it.
        paneB.terminalState.appendRemoteOutput(CLEAR_HOME.toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(paneA.terminalState.renderLooksSuspect())
        assertTrue(paneB.terminalState.visibleScreenIsBlank())

        // Pane A's PARKED capture returns tmux's rich frame (reserved for the parked capture).
        client.gatedCaptureResponse =
            CommandResponse(number = 90L, output = richFrameLines(), isError = false)
        client.gatedCursorReply = "0,0"
        // Pane B's (unparked) force reseed captures a rich frame carrying pane B's marker.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 91L, output = paneBFrameLines(), isError = false),
        )

        // Park pane A's active heal in flight (it closes its own gate and awaits the capture).
        val paneAGate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = paneAGate
        val jobA = launch { vm.healActivePaneIfStaleRenderForTest() }
        runCurrent()
        assertFalse(
            "pane A's heal must have closed its seed gate and parked on its capture",
            isSeedGateOpenFor(paneA),
        )
        assertFalse(
            "precondition: pane B's marker is not on pane B yet",
            visibleScreenTextFor(paneB).contains(PANE_B_MARKER),
        )

        // Pane B's blank-reseed runs CONCURRENTLY (its capture does NOT park). With the R3
        // per-pane single-flight it heals pane B immediately even though pane A's mutex is held.
        client.captureWithCursorGate = null
        val jobB = launch { vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest()) }
        runCurrent()

        assertTrue(
            "pane A's heal is still parked (its capture has not been released)",
            jobA.isActive,
        )
        assertTrue(
            "REGRESSION (#1353 R3 per-pane): while pane A's heal holds pane A's single-flight " +
                "mutex (parked on its capture), a heal on the DIFFERENT pane B must run and " +
                "reseed pane B — the guard is keyed PER PANE. A GLOBAL lock would block pane B " +
                "behind pane A and leave it black until pane A's capture returns. Pane B:\n" +
                visibleScreenTextFor(paneB),
            visibleScreenTextFor(paneB).contains(PANE_B_MARKER),
        )

        paneAGate.complete(Unit)
        advanceUntilIdle()
        jobA.cancel()
        jobB.cancel()
    }

    // ------------------------------------------------------------------ Frames

    private fun richFrameLines(): List<String> = buildList {
        add("RECONCILED-VIEWPORT")
        repeat(11) { add("recovered context row $it with real rendered content") }
    }

    private fun paneBFrameLines(): List<String> = buildList {
        add(PANE_B_MARKER)
        repeat(11) { add("pane B recovered context row $it with real content") }
    }

    private fun fragmentsOverBlackFrame(): String = buildString {
        append(CLEAR_HOME)
        append("24m 3 / 8 / 4 / 3 / 31\r\n")
        append("scattered fragment A\r\n")
        append("scattered fragment B\r\n")
        append("scattered fragment C\r\n")
        append("3\r\n")
    }

    // ------------------------------------------------------------------ Harness

    private fun feedLiveDelta(pane: TmuxPaneState, text: String) {
        val bridge = bridgeFor(pane)
        val bytes = text.toByteArray(Charsets.US_ASCII)
        bridge.feedBytes(bytes, 0, bytes.size)
    }

    private fun bridgeFor(pane: TmuxPaneState): SshTerminalBridge {
        val field = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        return field.get(pane.terminalState) as SshTerminalBridge
    }

    private fun visibleScreenTextFor(pane: TmuxPaneState): String =
        bridgeFor(pane).emulator.screen.visibleScreenText

    private fun isSeedGateOpenFor(pane: TmuxPaneState): Boolean {
        val bridge = bridgeFor(pane)
        val field = SshTerminalBridge::class.java.getDeclaredField("gated").apply {
            isAccessible = true
        }
        return !(field.getBoolean(bridge))
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
            CommandResponse(
                number = 2L,
                output = buildList {
                    add("initial idle frame")
                    repeat(10) { add("seed context row $it") }
                },
                isError = false,
            ),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun FakeTmuxClient.withTwoRichPanes(
        sessionName: String,
        paneIdA: String,
        paneIdB: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "$paneIdA\t@0\t\$0\t$sessionName\t$sessionName\t0",
                    "$paneIdB\t@0\t\$0\t$sessionName\t$sessionName\t0",
                ),
                isError = false,
            ),
        )
        // Attach seeds for both panes (each pane's cold-open capture + cursor).
        repeat(2) {
            capturePaneResponses.addLast(
                CommandResponse(
                    number = 2L,
                    output = buildList {
                        add("initial idle frame")
                        repeat(10) { r -> add("seed context row $r") }
                    },
                    isError = false,
                ),
            )
            cursorQueryResponses.addLast(
                CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }
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
        const val CLEAR_HOME: String = "\u001B[H\u001B[2J"
        const val NEWER_DELTA_MARKER: String = "NEWER-LIVE-DELTA-1353R3"
        const val PANE_B_MARKER: String = "PANE-B-RESEEDED-1353R3"
    }
}
