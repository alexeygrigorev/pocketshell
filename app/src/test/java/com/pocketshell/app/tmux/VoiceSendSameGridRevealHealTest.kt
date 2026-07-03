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
 * Issue #717 — DURABLE REGRESSION GUARD for the voice-send / keyboard-dismiss
 * black-pane bug, driving the REAL [TmuxSessionViewModel.resizeRemotePty] entry
 * point (NOT a heal seam).
 *
 * ## The bug (root cause)
 *
 * After sending a VOICE-recorded prompt the composer / soft keyboard dismisses.
 * The dictation chrome mount/unmount re-measures the `TerminalView`, which fires
 * `onTerminalSizeChanged` -> [TmuxSessionViewModel.resizeRemotePty]. For an idle
 * full-screen agent pane the IME transition can WIPE the local emulator while the
 * idle agent emits no fresh `%output`, so the pane is left BLACK (only a stray
 * cursor / a lone live line). tmux's server grid still HOLDS the content, so a
 * single `capture-pane` would heal it.
 *
 * The composer-dismiss frequently re-measures the terminal back to the EXACT same
 * grid the last `onSizeChanged` already recorded. On `origin/main`
 * [TmuxSessionViewModel.resizeRemotePty] returned BLINDLY at the top-level
 * same-dimension gate (`columns == remoteColumns && rows == remoteRows`) BEFORE
 * `maybeRefreshControlClientSize` (and its active-pane heal) was ever reached. So
 * the wiped pane stayed black — the maintainer's #717.
 *
 * ## Why the prior test passed while the bug shipped (the #657 / D32-G6 proxy gap)
 *
 * The androidTest `VoiceSendActivePaneStaysVisibleE2eTest` drives the heal via
 * [TmuxSessionViewModel.triggerSameDimensionResizeHealForTest], which sets
 * `appliedControlClient*` to the current grid and calls
 * `maybeHealActivePaneOnNoOpResize` DIRECTLY — BYPASSING `resizeRemotePty` and so
 * the top-level `:10901` early-return entirely. It proved the heal LOGIC works
 * when reached, never that the real composer-dismiss path REACHES it. This test
 * closes that gap: it calls the production `resizeRemotePty(sameGrid)` public API
 * and asserts the wiped pane is re-captured.
 *
 * ## The fix
 *
 * `resizeRemotePty`'s top-level same-dimension gate no longer returns blindly: it
 * runs the cheap active-pane heal ([maybeHealActivePaneOnNoOpResize]) when the
 * active pane looks lost (blank / partial-blank), re-capturing the lost frame with
 * NO `refresh-client -C` wire op. A normally-painted pane stays a no-op (the
 * heal pre-checks blank/partial-blank), so a routine keyboard toggle of a good
 * pane costs nothing — preserving the #285 "Compose layout churn must not spam
 * tmux" intent.
 *
 * ## Red -> green
 *
 * Each test issues a `capture-pane` ONLY when the heal runs. The discriminator is
 * `client.captureCount()` AFTER the real `resizeRemotePty(sameGrid)`:
 *  - On base `origin/main` (blind early-return): NO new capture -> RED.
 *  - With the fix: exactly one heal `capture-pane` -> GREEN, and a queued recovery
 *    frame restores the pane content.
 *
 * Same-grid is the WHOLE class: a voice-send dismiss and a keyboard-down dismiss
 * both arrive as `resizeRemotePty(sameGrid)` (the back-to-same-dims measure pass).
 * The trigger differs; the production code path under test is identical. So a
 * single real-entry-point test covers both reported inputs — see
 * [keyboardDismissSameGridResizeAlsoHealsBlankActivePane] for the explicit
 * keyboard-dismiss framing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class VoiceSendSameGridRevealHealTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) Voice-send: the active pane went BLACK on the IME transition; the
    //     composer-dismiss resolves to the SAME grid -> resizeRemotePty must heal.
    //     RED on base (blind early-return), GREEN with the fix.
    // -------------------------------------------------------------------------

    @Test
    fun voiceSendSameGridResizeHealsBlankActivePane() = runVmTest {
        // An idle full-screen pane whose attach-time capture came back EMPTY,
        // modelling the agent pane the IME transition left BLACK (tmux's grid still
        // holds the content; the local emulator is black).
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "precondition: the active pane is BLACK (the #717 voice-send symptom)",
            pane.terminalState.visibleScreenIsBlank(),
        )

        // (a) Establish the phone grid the way Compose's first onSizeChanged does:
        // a real resizeRemotePty(C, R) applies the control-client size (refresh-client
        // -C) and its reflow boundary runs the reattach reseed. Drain it so the
        // same-grid heal under test is isolated from the attach-time reflow.
        val cols = 62
        val rows = 56
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()
        assertEquals(
            "the first real resize applies the grid",
            cols to rows,
            vm.remoteDimensionsForTest(),
        )

        // Re-wipe: the empty captures during attach/reflow left the pane black; make
        // sure the precondition for the same-grid heal is a BLACK active pane and queue
        // the ONE recovery frame tmux's grid would return on the heal capture.
        assertTrue(
            "precondition for the same-grid heal: the active pane is still BLACK",
            pane.terminalState.visibleScreenIsBlank(),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = listOf(RECOVERED_FRAME), isError = false),
        )
        val capturesBefore = client.captureCount()

        // (b) THE REAL PRODUCTION ENTRY POINT (not the heal seam): the composer/voice
        // dismiss re-measures the TerminalView back to the SAME grid. On base this
        // returns blindly at the top-level same-dimension gate -> NO heal -> black pane
        // survives (RED). With the fix it runs the active-pane heal -> one capture-pane
        // -> the recovery frame restores the pane (GREEN).
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#717): a same-grid resizeRemotePty (voice/keyboard dismiss) " +
                "with a BLACK active pane must run the active-pane heal (a capture-pane). " +
                "On base origin/main the top-level early-return skipped it and the pane " +
                "stayed black.",
            client.captureCount() > capturesBefore,
        )
        assertFalse(
            "the heal restored the active pane (no longer black)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the restored pane shows tmux's authoritative content",
            renderedTranscriptFor(pane).contains(RECOVERED_FRAME),
        )
    }

    // -------------------------------------------------------------------------
    // (2) Keyboard-dismiss-to-same-grid: SAME production path, explicit framing
    //     for class coverage (D31/D32-G2 — the whole class, not one input).
    // -------------------------------------------------------------------------

    @Test
    fun keyboardDismissSameGridResizeAlsoHealsBlankActivePane() = runVmTest {
        // A keyboard-down dismiss (no voice) re-measures the terminal back to the same
        // grid just as the voice-send composer dismiss does. The reported input differs;
        // the production path (resizeRemotePty -> same-dimension gate) is identical, so
        // this is the same load-bearing assertion under the keyboard framing.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }

        val cols = 80
        val rows = 40
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()
        assertTrue(
            "precondition: the keyboard-up pane was BLACK before the dismiss",
            pane.terminalState.visibleScreenIsBlank(),
        )

        client.capturePaneResponses.addLast(
            CommandResponse(number = 98L, output = listOf(RECOVERED_FRAME), isError = false),
        )
        val capturesBefore = client.captureCount()

        // Keyboard-down dismiss -> resizeRemotePty back to the SAME grid.
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()

        assertTrue(
            "REGRESSION (#717 class): a same-grid resizeRemotePty from a keyboard-down " +
                "dismiss with a BLACK active pane must also heal it (not just voice-send)",
            client.captureCount() > capturesBefore,
        )
        assertFalse(
            "the keyboard-dismiss heal restored the pane",
            pane.terminalState.visibleScreenIsBlank(),
        )
    }

    // -------------------------------------------------------------------------
    // (3) GOOD PATH UNCHANGED: a same-grid resize on an ALREADY-PAINTED pane must
    //     stay a cheap no-op — no double-reveal, no spurious capture. (The #285
    //     "Compose layout churn must not spam tmux" intent is preserved.)
    // -------------------------------------------------------------------------

    @Test
    fun sameGridResizeIsANoOpForAnAlreadyPaintedPane() = runVmTest {
        // A GENUINELY full pane: >3 live lines so it is neither blank nor
        // partial-blank, hence the active-pane heal correctly skips it.
        val client = FakeTmuxClient().withFullyPaintedPaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertFalse(
            "precondition: the pane is painted (non-blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertFalse(
            "precondition: the pane is FULLY painted (not partial-blank), so the heal " +
                "must correctly treat it as a no-op",
            pane.terminalState.visibleScreenIsPartiallyBlank(),
        )

        val cols = 100
        val rows = 50
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()
        assertFalse(
            "the pane stays painted after the grid is applied",
            pane.terminalState.visibleScreenIsBlank(),
        )

        val capturesBefore = client.captureCount()
        // A repeated same-grid resize (routine layout churn) on a GOOD pane.
        vm.resizeRemotePty(cols, rows)
        advanceUntilIdle()

        assertEquals(
            "GOOD PATH: a same-grid resize on an already-painted pane must NOT issue any " +
                "capture-pane (no heal, no double-reveal) — it stays a cheap no-op",
            capturesBefore,
            client.captureCount(),
        )
    }

    // -------------------------------------------------------------------------
    // (4) NORMAL TEXT-SEND ANALOGUE: a real grid CHANGE still flows through the
    //     refresh-client path (no regression to the resize wire-op itself).
    // -------------------------------------------------------------------------

    @Test
    fun changedGridResizeStillRefreshesControlClient() = runVmTest {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)

        val refreshBefore = client.refreshClientCount()
        vm.resizeRemotePty(70, 45)
        advanceUntilIdle()

        assertTrue(
            "a genuinely changed grid must still issue a refresh-client -C (the resize " +
                "wire-op is unchanged for a real grid change)",
            client.refreshClientCount() > refreshBefore,
        )
        assertEquals(70 to 45, vm.remoteDimensionsForTest())
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
        // Issue #926: pin the seed-IO dispatcher (off-Main hop for the
        // attach/reattach `capture-pane`/`list-panes` IO) to the shared
        // virtual-clock scheduler so the round-trips run inline on the test
        // clock. Production defaults to `Dispatchers.IO` (off the UI thread).
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

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

    private fun FakeTmuxClient.refreshClientCount(): Int =
        sentCommands.count { it.startsWith("refresh-client -C") }

    private fun FakeTmuxClient.withSinglePaneRow(
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
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    /**
     * A single-pane session whose captures return a FULLY-painted multi-line
     * viewport (>3 live lines), so the pane is neither blank nor partial-blank.
     * Queues several identical full frames so the attach seed AND the
     * reflow-boundary reseed both leave the pane fully painted.
     */
    private fun FakeTmuxClient.withFullyPaintedPaneRow(
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
        // Issue #1214: a GENUINELY DENSE pane — enough live lines to fill the visible grid well
        // above the 0.75 live-fraction ceiling, so the #1214-widened reveal/resize pre-check
        // ([TerminalSurfaceState.visibleRenderMayHaveLostFrame]) reads it confidently-full and
        // skips the capture. A merely-sparse "painted" frame (a handful of lines on a tall grid)
        // now legitimately pays ONE authoritative capture under #1214 — the point of THIS test is
        // the #285 "a healthy DENSE pane must not spam tmux" no-op, which a dense frame models.
        val fullFrame = (1..60).map { "$sessionName painted line $it" }
        repeat(4) {
            capturePaneResponses.addLast(
                CommandResponse(number = 2L, output = fullFrame, isError = false),
            )
        }
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun FakeTmuxClient.withSinglePaneRowButEmptyCapture(
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
        // No capturePaneResponses queued: every capture falls back to the
        // FakeTmuxClient default empty response -> the pane stays black.
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
        const val RECOVERED_FRAME: String = "ISSUE717-VOICE-RECOVERED-BANNER"
    }
}
