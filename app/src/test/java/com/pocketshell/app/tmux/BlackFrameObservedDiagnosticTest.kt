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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1175 — the `black_frame_observed` diagnostic fingerprint.
 *
 * ## What this proves
 *
 * When the maintainer hits a black screen and taps Settings → Diagnostics → Share
 * log, the export must say WHICH class of black screen it was. Today only
 * `stale_render_heal` (fires on a SUCCESSFUL heal) reaches the exportable JSONL;
 * every other black-screen class is silent or logcat-only (unretrievable on-device).
 * This slice emits ONE richer `black_frame_observed` event with a `class`
 * discriminator + geometry/lifecycle fields at the points the heal/reveal path
 * ALREADY reaches — riding the existing gated, backed-off stale-render watchdog
 * capture and the reveal gate, adding NO new polling (the #1164 heat contrast).
 *
 * ## Class coverage (G2)
 *
 * Each of the FIVE classes is driven through the REAL production path
 * ([TmuxSessionViewModel.healActivePaneIfStaleRender] via its test seam, and the
 * switch-reveal gate [TmuxSessionViewModel.awaitActivePaneSeededOrLoading]):
 *  - `capture_empty`  — capture-pane empty on a live transport, pane WAS seeded.
 *  - `never_seeded`   — capture-pane empty, no seed has EVER landed (stamp absent).
 *  - `lost_after_paint` — render fully lost tmux's frame (renderedChars == 0).
 *  - `partial_blank`  — some live content survives, majority black.
 *  - `reveal_gate_gave_up_still_blank` — reveal gate exhausted its reseed retries.
 *
 * ## No new polling (acceptance criterion 2)
 *
 * [emissionRidesTheExistingHealCaptureNoExtraRoundTrip] proves the emission pays NO
 * capture beyond the one the heal already issues: a single
 * `healActivePaneIfStaleRenderForTest()` increments the capture-pane count by exactly
 * one. The emission adds a `Map` lookup + a `trySend` to the (off-main, bounded)
 * recorder — no new coroutine, timer, or `capture-pane` round-trip.
 *
 * ## `stale_render_heal` preserved (acceptance criterion 4)
 *
 * The successful-heal event still fires alongside the new observe-side event
 * ([lostAfterPaintAlsoPreservesStaleRenderHeal]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BlackFrameObservedDiagnosticTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // (1) capture_empty — capture-pane empty on a live transport, pane WAS seeded.
    // -------------------------------------------------------------------------

    @Test
    fun captureEmptyClassIsFingerprintedWhenSeededPaneGoesBlackAndCaptureIsAlsoEmpty() =
        runVmTest { sink ->
            val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
            val vm = connectVm(client)
            val pane = vm.panes.value.single { it.paneId == "%1" }
            vm.resizeRemotePty(80, 40)
            advanceUntilIdle()

            // The connect seed stamped paneLastSeedAtMs — this pane WAS seeded.
            assertNotNull(
                "precondition: the connect seed landed (so this is capture_empty, not never_seeded)",
                vm.paneLastSeedAtMsForTest("%1"),
            )
            // The render went fully black on a live transport.
            blankRender(pane)
            assertTrue(pane.terminalState.visibleScreenIsBlank())

            // No capture queued → the heal's capture-pane comes back EMPTY (server-side black).
            val capturesBefore = client.captureCount()
            val healed = vm.healActivePaneIfStaleRenderForTest()
            advanceUntilIdle()

            assertEquals(
                "an empty capture cannot heal — it is UNVERIFIED (issue #1294)",
                HealOutcome.Unverified,
                healed,
            )
            val event = sink.singleBlackFrameEvent()
            assertEquals("capture_empty", event.fields["class"])
            assertEquals("%1", event.fields["paneId"])
            assertEquals(0, event.fields["captureBytes"])
            // Seeded → msSinceLastSeed is a real age, not the -1 never-seeded sentinel.
            assertTrue(
                "msSinceLastSeed must be a real age (>= 0) for a seeded pane",
                (event.fields["msSinceLastSeed"] as Long) >= 0L,
            )
            assertEquals("Connected", event.fields["connectionStatus"])
            // NO new poll: the heal issued exactly ONE capture; the emission adds none.
            assertEquals(
                "the emission rides the heal's own capture — no extra round-trip",
                capturesBefore + 1,
                client.captureCount(),
            )
        }

    // -------------------------------------------------------------------------
    // (2) never_seeded — capture-pane empty, no seed has EVER landed for the pane.
    // -------------------------------------------------------------------------

    @Test
    fun neverSeededClassIsFingerprintedWhenNoSeedHasEverLanded() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%2")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Synthetically inject the "no seed ever landed" state (the connect flow
        // otherwise stamps a seed) — the #780 model: inject the failing state.
        vm.clearPaneSeedStampForTest("%2")
        blankRender(pane)

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(HealOutcome.Unverified, healed)
        val event = sink.singleBlackFrameEvent()
        assertEquals("never_seeded", event.fields["class"])
        assertEquals(0, event.fields["captureBytes"])
        assertEquals(
            "an unseeded pane reports msSinceLastSeed = -1 (unavailable)",
            -1L,
            event.fields["msSinceLastSeed"],
        )
    }

    // -------------------------------------------------------------------------
    // (3) lost_after_paint — render fully lost tmux's frame (renderedChars == 0),
    //     tmux still holds the content. Also proves stale_render_heal is preserved.
    // -------------------------------------------------------------------------

    @Test
    fun lostAfterPaintAlsoPreservesStaleRenderHeal() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%3")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Render fully black, but tmux's grid still holds a dense frame.
        blankRender(pane)
        assertEquals(0, pane.terminalState.renderedNonBlankCharCount())
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals("the render lost the frame → the heal fires", HealOutcome.Healed, healed)
        val event = sink.singleBlackFrameEvent()
        assertEquals("lost_after_paint", event.fields["class"])
        assertEquals(0, event.fields["renderedChars"])
        assertTrue("the capture carried a real frame", (event.fields["captureBytes"] as Int) > 0)
        assertTrue("the pane geometry is reported", (event.fields["visibleRows"] as Int) > 0)
        assertEquals(false, event.fields["partialBlank"])
        // Additive (criterion 4): the successful-heal event STILL fires alongside the observe event.
        assertEquals(
            "stale_render_heal must still be emitted on a successful heal",
            1,
            sink.eventsNamed("stale_render_heal").size,
        )
    }

    // -------------------------------------------------------------------------
    // (4) partial_blank — some live content survives, majority of the viewport black.
    // -------------------------------------------------------------------------

    @Test
    fun partialBlankClassIsFingerprintedWhenSomeLiveContentSurvives() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%4", title = "codex")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%4" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // One live line, the rest of the viewport black (the #941 partial-black symptom).
        partialBlackRender(pane)
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertTrue(pane.terminalState.visibleScreenIsPartiallyBlank())
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(HealOutcome.Healed, healed)
        val event = sink.singleBlackFrameEvent()
        assertEquals("partial_blank", event.fields["class"])
        assertTrue("partial-black keeps SOME rendered content", (event.fields["renderedChars"] as Int) > 0)
        assertEquals(true, event.fields["partialBlank"])
    }

    // -------------------------------------------------------------------------
    // (5) reveal_gate_gave_up_still_blank — the reveal gate exhausted its bounded
    //     reseed retries and the active pane is STILL blank (the single most
    //     user-visible black screen). Previously logcat-only.
    // -------------------------------------------------------------------------

    @Test
    fun revealGateGaveUpClassIsFingerprintedWhenReseedRetriesExhaust() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%5")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%5" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The active pane is fully blank and every reveal-gate reseed capture returns
        // empty (no capturePaneResponses queued) → the gate exhausts its retries.
        blankRender(pane)
        assertTrue(pane.terminalState.visibleScreenIsBlank())

        val revealed = vm.awaitActivePaneSeededOrLoadingForTest(client)
        advanceUntilIdle()

        assertFalse("the gate could not seed the pane → it defers (returns false)", revealed)
        val event = sink.singleBlackFrameEvent()
        assertEquals("reveal_gate_gave_up_still_blank", event.fields["class"])
        assertEquals("%5", event.fields["paneId"])
    }

    // -------------------------------------------------------------------------
    // No-new-poll acceptance (criterion 2): the emission never issues its own
    // capture-pane round-trip; it rides the heal's single capture.
    // -------------------------------------------------------------------------

    @Test
    fun emissionRidesTheExistingHealCaptureNoExtraRoundTrip() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%6")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%6" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        blankRender(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        val capturesBefore = client.captureCount()

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        // Exactly ONE capture-pane for the whole heal+observe pass: the emission adds none.
        assertEquals(capturesBefore + 1, client.captureCount())
        assertEquals(1, sink.eventsNamed("black_frame_observed").size)
    }

    // -------------------------------------------------------------------------
    // A healthy pane records NOTHING (the degenerate-frame gate keeps healthy
    // watchdog ticks silent — no over-recording flood, the #1164 contrast).
    // -------------------------------------------------------------------------

    @Test
    fun healthyPaneRecordsNoBlackFrameEvent() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%7")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%7" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // A normally-painted, dense viewport — NOT degenerate. The heal's capture
        // comes back empty (no reseed to do); nothing should be fingerprinted.
        pane.terminalState.appendRemoteOutput(
            buildString {
                append(CLEAR_ONLY)
                repeat(30) { append("healthy content line $it with real text\r\n") }
            }.toByteArray(Charsets.US_ASCII),
        )
        advanceUntilIdle()
        assertFalse(pane.terminalState.visibleScreenIsBlank())
        assertFalse(pane.terminalState.visibleScreenIsPartiallyBlank())

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "a healthy pane must NOT fingerprint a black frame (no over-recording)",
            0,
            sink.eventsNamed("black_frame_observed").size,
        )
    }

    // ------------------------------------------------------------------ Harness

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

    /** Thin wrapper so the test body can pull black-frame events by class without imports. */
    private class RecordingSink(
        private val delegate: com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink,
    ) {
        fun eventsNamed(name: String): List<RecordedDiagnosticEvent> = delegate.eventsNamed(name)

        fun singleBlackFrameEvent(): RecordedDiagnosticEvent {
            val events = eventsNamed("black_frame_observed")
            assertEquals(
                "exactly one black_frame_observed event expected, got: " +
                    events.joinToString { it.fields["class"].toString() },
                1,
                events.size,
            )
            return events.single()
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
        // Suppress the background stale-render + blank watchdogs so the ONLY heal /
        // reveal-gate pass is the one the test drives directly — no spurious events.
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

    /** Fully black render: a clear-only overpaint wipes the visible viewport. */
    private fun blankRender(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
    }

    /** Partial-black render: a clear + ONE live line (the #941 "one live line, rest black"). */
    private fun partialBlackRender(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            (CLEAR_ONLY + "ISSUE1175-LIVE-LINE 7\r\n").toByteArray(Charsets.US_ASCII),
        )
    }

    private fun FakeTmuxClient.captureCount(): Int =
        sentCommands.count { it.startsWith("capture-pane") }

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

    private fun denseFrameLines(): List<String> = buildList {
        add("ISSUE1175-RECOVERED-VIEWPORT")
        repeat(27) { add("recovered context row $it with real tmux content") }
    }

    private companion object {
        // ESC[2J ESC[H — clear screen + cursor home (the overpaint preamble).
        val CLEAR_ONLY: String = "[2J[H"
    }
}
