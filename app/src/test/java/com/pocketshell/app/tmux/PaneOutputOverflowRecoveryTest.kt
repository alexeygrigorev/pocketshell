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
import com.pocketshell.core.terminal.bridge.TerminalSeedGateOverflowException
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
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
 * Issue #1205 — REPRODUCE-FIRST (D33/G10) JVM proof that a pane's delivery
 * backlog overflow (and the 2 MB seed-gate overflow) RESEEDS-AND-REATTACHES the
 * pane instead of latching it into a permanently-dead `surfaceError` card.
 *
 * THE BUG (2026-07-03 black-screen audit on #874): per-pane `%output` delivery
 * is a bounded `Channel(4096)` fed by non-blocking `trySend`. Under a sustained
 * Claude alt-screen burst with a contended main thread the channel overflows and
 * drops. On the FIRST dropped frame the app cancelled the producer, detached the
 * pane, and latched `surfaceError` — a permanently dead pane the blank/stale
 * watchdog and heal oracle both early-return on, so nothing self-heals and the
 * user must tap "Recreate terminal". The seed-gate 2 MB overflow latched the
 * same way. The KDoc on `TmuxClient.outputBacklogOverflows` already prescribes
 * the correct behavior: this is LOCAL renderer backpressure, so recover by
 * RESEEDING from `capture-pane` — a transient burst costs one reseed, not the
 * pane.
 *
 * RED on base (the load-bearing assertion that FAILS without the fix): after an
 * overflow the pane is latched `surfaceError == true`. With the fix the pane is
 * reseeded from a fresh `capture-pane`, the producer is reattached, live output
 * resumes, and `surfaceError` stays false — with NO user action.
 *
 * Class coverage (G2): the live-output backlog overflow AND the seed-gate 2 MB
 * overflow both recover, and the bounded-retry exhaustion path still lands on the
 * `surfaceError` card (never an infinite reseed loop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PaneOutputOverflowRecoveryTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
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
        createdVms.add(it)
    }

    /**
     * Connect a single-pane session painted with a content-rich frame, then
     * silence the connect-armed watchdogs so the only `capture-pane` traffic is
     * the one the recovery under test issues.
     */
    private fun TestScope.connectVmWithRichFrame(client: FakeTmuxClient): TmuxSessionViewModel {
        val live = AlwaysConnectedSession(id = "live")
        val connector = SingleSessionConnector(live)
        val leaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        runCurrent()
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
        frame: List<String> = contentRichFrame,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = frame, isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    /** Queue a content-rich recovery capture tagged so the test can see it landed. */
    private fun FakeTmuxClient.queueRecoveryCapture(marker: String, number: Long) {
        capturePaneResponses.addLast(
            CommandResponse(
                number = number,
                output = contentRichFrame.map { it.replace("idle", marker) },
                isError = false,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // (1) Live-output backlog overflow — the maintainer's reported class.
    // -----------------------------------------------------------------------

    @Test
    fun backlogOverflowReseedsAndReattachesInsteadOfLatchingSurfaceError() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse("precondition: pane is not errored before the overflow", pane.surfaceError)
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 100)

        client.queueRecoveryCapture(marker = "BACKLOG-RECOVERED", number = 9L)
        val sentBefore = client.sentCommands.size

        // Drive the REAL overflow path: emit an overflow the same way the tmux
        // client's `trySend`-drop does; it flows through the VM's real
        // `outputBacklogOverflows` collector into `handleTerminalOutputBacklogOverflow`.
        client.outputBacklogOverflowEvents.tryEmit(
            TmuxOutputBacklogOverflow(paneId = "%1", droppedEvents = 1),
        )
        advanceUntilIdle()

        // LOAD-BEARING red→green: on base the pane is latched `surfaceError`; the
        // fix reseeds-and-reattaches it instead, with NO user action.
        val recovered = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(
            "Issue #1205: a backlog overflow must NOT latch the pane into surfaceError " +
                "— it must reseed-and-reattach",
            recovered.surfaceError,
        )
        // The pane was reseeded from a fresh capture-pane (the recovery chokepoint).
        val sentDuring = client.sentCommands.drop(sentBefore)
        assertTrue(
            "the recovery must reseed the pane from tmux's server-side grid " +
                "(sent: $sentDuring)",
            sentDuring.any { it.startsWith("capture-pane") },
        )
        assertTrue(
            "the reseed must restore fresh authoritative content",
            renderedTranscriptFor(recovered).contains("BACKLOG-RECOVERED"),
        )
        // The producer was reattached to the LIVE client — live %output resumes
        // with no user action. Bound-to-live-client is the deterministic proof the
        // producer pipeline is restored (the render of a post-recovery emit runs on
        // the producer's own dispatcher, which the virtual clock can't sync).
        assertTrue(
            "the pane producer must be reattached and active after recovery",
            vm.paneProducerActiveForTest("%1"),
        )
        assertEquals(
            "the reattached producer must be bound to the live client",
            System.identityHashCode(client),
            vm.paneProducerClientIdentityForTest("%1"),
        )
    }

    // -----------------------------------------------------------------------
    // (2) Seed-gate 2 MB overflow — the other class (G2).
    // -----------------------------------------------------------------------

    @Test
    fun seedGateOverflowReseedsInsteadOfLatchingSurfaceError() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(pane.surfaceError)

        client.queueRecoveryCapture(marker = "SEEDGATE-RECOVERED", number = 9L)
        val sentBefore = client.sentCommands.size

        // The seed-gate overflow originates in the terminal bridge's feed-failure
        // callback (`onTerminalFeedFailure`), which a JVM test can't raise without
        // a real 2 MB feed — drive the SAME production handler the callback calls.
        vm.handleTerminalSeedGateOverflowForTest(
            paneId = "%1",
            overflow = TerminalSeedGateOverflowException(
                pendingBytes = 2_000_000,
                incomingBytes = 100_000,
                maxBytes = 2_097_152,
            ),
        )
        advanceUntilIdle()

        val recovered = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(
            "Issue #1205: a seed-gate overflow must NOT latch the pane into surfaceError",
            recovered.surfaceError,
        )
        val sentDuring = client.sentCommands.drop(sentBefore)
        assertTrue(
            "the seed-gate recovery must reseed from capture-pane (sent: $sentDuring)",
            sentDuring.any { it.startsWith("capture-pane") },
        )
        assertTrue(
            "the reseed must restore fresh authoritative content",
            renderedTranscriptFor(recovered).contains("SEEDGATE-RECOVERED"),
        )
        assertTrue(
            "the pane producer must be reattached and active after seed-gate recovery",
            vm.paneProducerActiveForTest("%1"),
        )
    }

    // -----------------------------------------------------------------------
    // (3) Bounded-retry exhaustion — a still-saturated channel must NOT loop
    //     into a reseed storm; after the budget it lands on the card (G2).
    // -----------------------------------------------------------------------

    @Test
    fun boundedRetryExhaustionLandsOnSurfaceErrorCardNotAnInfiniteLoop() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        assertFalse(vm.panes.value.single { it.paneId == "%1" }.surfaceError)

        // Attempts 1 and 2 reseed (within the OVERFLOW_RECOVERY_MAX_ATTEMPTS budget).
        client.queueRecoveryCapture(marker = "RETRY-1", number = 11L)
        client.queueRecoveryCapture(marker = "RETRY-2", number = 12L)

        repeat(OVERFLOW_RECOVERY_MAX_ATTEMPTS) { attempt ->
            client.outputBacklogOverflowEvents.tryEmit(
                TmuxOutputBacklogOverflow(paneId = "%1", droppedEvents = attempt + 1),
            )
            advanceUntilIdle()
            assertFalse(
                "attempt ${attempt + 1} is within budget — the pane must still recover, not latch",
                vm.panes.value.single { it.paneId == "%1" }.surfaceError,
            )
        }

        // The (budget + 1)th overflow, with the channel STILL saturated, exhausts
        // the budget — the pane now falls to the actionable card exactly once (no
        // infinite reseed loop).
        client.outputBacklogOverflowEvents.tryEmit(
            TmuxOutputBacklogOverflow(paneId = "%1", droppedEvents = 99),
        )
        advanceUntilIdle()

        assertTrue(
            "Issue #1205: after OVERFLOW_RECOVERY_MAX_ATTEMPTS reseeds a still-saturated " +
                "channel must land on the surfaceError card, not loop forever",
            vm.panes.value.single { it.paneId == "%1" }.surfaceError,
        )
    }

    // -----------------------------------------------------------------------
    // (4) De-dup: a BURST of overflow signals (one per dropped frame) must
    //     trigger exactly ONE reseed, never one reseed per dropped frame.
    // -----------------------------------------------------------------------

    @Test
    fun aBurstOfOverflowSignalsTriggersASingleReseedNotOnePerDroppedFrame() = runVmTest {
        val client = FakeTmuxClient().withRichInitialFrame("work", "%1")
        val vm = connectVmWithRichFrame(client)
        client.queueRecoveryCapture(marker = "BURST-RECOVERED", number = 9L)
        val sentBefore = client.sentCommands.size

        // A single burst fires the overflow signal once per dropped frame.
        repeat(50) { i ->
            client.outputBacklogOverflowEvents.tryEmit(
                TmuxOutputBacklogOverflow(paneId = "%1", droppedEvents = i + 1),
            )
        }
        advanceUntilIdle()

        val capturesDuring = client.sentCommands.drop(sentBefore).count { it.startsWith("capture-pane") }
        assertEquals(
            "a burst of 50 overflow signals must de-dup to exactly ONE reseed capture, " +
                "not 50 (a reseed storm)",
            1,
            capturesDuring,
        )
        assertFalse(vm.panes.value.single { it.paneId == "%1" }.surfaceError)
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
