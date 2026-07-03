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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1192 — the SIXTH `black_frame_observed` class, `surface_black_model_intact`.
 *
 * ## What this proves (the observability gap #874 GAP-1 left)
 *
 * The heal oracle ([TmuxSessionViewModel.healActivePaneIfStaleRender]) decides "black
 * screen?" by comparing the emulator MODEL grid against tmux's authoritative
 * `capture-pane`. A black screen where the on-screen SURFACE is blank but the MODEL grid
 * still matches tmux is un-catchable BY CONSTRUCTION — the model never diverges — so it
 * emits NONE of the five #1175 classes. If the maintainer's black recurs as a
 * surface-only black after #1181, the shared log would be SILENT about it.
 *
 * This slice adds a minimal SURFACE-paint-confirmation seam
 * ([com.pocketshell.core.terminal.ui.TerminalSurfaceState.onSurfaceFramePainted]) that the
 * vendored `TerminalView.onDraw` reports each frame into, and fingerprints the class at
 * the exact site the oracle short-circuits on a model-healthy pane. DIAGNOSTICS ONLY — no
 * reseed/heal (#721 self-heals every KNOWN surface-blank trigger).
 *
 * ## Red → green (criterion 1), in one run
 *
 * [surfaceHealthyPaneWithHealthyPaintRecordsNothing] is the RED side: model intact +
 * capture matches (oracle healthy) but the surface last painted CONTENT → NO event. It
 * proves the detector is load-bearing (the event exists ONLY because of the surface-black
 * signal, not the model state). [surfaceBlackWhileModelIntactIsFingerprinted] flips ONLY
 * the surface-paint signal to a BLACK frame → the `surface_black_model_intact` event fires
 * with the full field set, and lands in the exportable JSONL.
 *
 * ## Additive (criterion 2) + no new poll (criterion 3)
 *
 * [existingLostAfterPaintClassStillEmitsUnchanged] drives the pre-existing
 * `lost_after_paint` class + `stale_render_heal` through the SAME (now-modified) oracle
 * site and asserts they are unchanged AND that the new surface class does NOT also fire.
 * [emissionRidesTheExistingHealCaptureNoExtraRoundTrip] asserts the whole heal+observe
 * pass issues exactly ONE `capture-pane` — the emission adds no timer/loop/round-trip
 * (it rides the existing gated stale-render watchdog tick, #1164).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SurfaceBlackModelIntactDiagnosticTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // GREEN — model intact (oracle healthy) but the surface last painted a BLACK
    // frame → the sixth class is fingerprinted with the full field set.
    // -------------------------------------------------------------------------

    @Test
    fun surfaceBlackWhileModelIntactIsFingerprinted() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // MODEL grid holds a dense frame that MATCHES tmux — the oracle would call this
        // pane HEALTHY (it does not lose the frame vs capture).
        renderDenseFrame(pane)
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 0)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        assertFalse(
            "precondition: the model matches tmux (oracle healthy) so none of the five " +
                "classes can fire — only the surface-paint seam can",
            pane.terminalState.visibleRenderLostFrameVsCapture(
                denseFrameLines().joinToString("\n"),
            ),
        )

        // Inject the failing SURFACE state synthetically (#780 model — the CI JVM cannot
        // run a real View onDraw): the most-recent painted frame was the BLACK fallback.
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 1L)
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 2L)
        assertTrue(
            "the surface is confirmed black while the model holds content",
            pane.terminalState.surfaceIsBlackWhileModelHasContent(),
        )

        val surfaceRepaintsBefore = pane.terminalState.surfaceRepaintRequestCountForTest()
        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertFalse(
            "a model-healthy pane is not MODEL-reseeded (no capture-pane swap) — the recovery is " +
                "a surface repaint, not a model heal",
            healed,
        )
        // Issue #1203: the auto-heal now RECOVERS this class with a SURFACE force-repaint
        // (re-bind emulator + full-clip invalidate) instead of only fingerprinting it.
        assertEquals(
            "the auto-heal must request exactly one surface force-repaint to recover the " +
                "surface-only-black the model reseed cannot touch",
            surfaceRepaintsBefore + 1,
            pane.terminalState.surfaceRepaintRequestCountForTest(),
        )
        val event = sink.singleBlackFrameEvent()
        assertEquals("surface_black_model_intact", event.fields["class"])
        assertEquals("%1", event.fields["paneId"])
        // Full field set (same as the other five classes).
        assertTrue("the model still renders content", (event.fields["renderedChars"] as Int) > 0)
        assertTrue("the capture carried a real frame", (event.fields["captureBytes"] as Int) > 0)
        assertTrue("the pane geometry is reported", (event.fields["visibleRows"] as Int) > 0)
        assertTrue(
            "msSinceLastSeed must be a real age for a seeded pane",
            (event.fields["msSinceLastSeed"] as Long) >= 0L,
        )
        assertEquals("Connected", event.fields["connectionStatus"])
        assertTrue("foreground is reported", event.fields.containsKey("foreground"))
        assertTrue("screenOn is reported", event.fields.containsKey("screenOn"))
        // Diagnostics-only: NO successful heal event was emitted (the model was healthy).
        assertEquals(
            "no stale_render_heal — the oracle never touched the healthy model",
            0,
            sink.eventsNamed("stale_render_heal").size,
        )
    }

    // -------------------------------------------------------------------------
    // Issue #1203 — RECOVERY red→green. The MANUAL Redraw forces a SURFACE repaint
    // that recovers a surface-only-black (model intact, surface black); the model
    // reseed alone (base) does NOT.
    //
    // RED (base, pre-fix): redrawActivePane only runs the model reseed
    // (reseedActivePaneForReattach). The model already matches tmux, so the reseed
    // restores nothing and the surface stays black — surfaceRepaintRequestCount stays 0
    // (no surface repaint requested) and surfaceIsBlackWhileModelHasContent() stays true.
    //
    // GREEN (fixed): redrawActivePane ALSO fires requestSurfaceRepaint(); driving that
    // request through the surface (as TerminalSurface's collector → onDraw content paint
    // does) clears the surface-black.
    // -------------------------------------------------------------------------

    @Test
    fun manualRedrawForcesSurfaceRepaintForSurfaceOnlyBlack() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%10")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%10" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // MODEL intact + capture matches tmux (oracle healthy), surface confirmed black.
        renderDenseFrame(pane)
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 0)
        // Redraw's reseed pays a capture-pane; return the SAME dense frame so the
        // non-destructive-swap guard would keep the model intact (nothing to restore).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 1L)
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 2L)
        assertTrue(
            "precondition: the surface is confirmed black while the model holds content",
            pane.terminalState.surfaceIsBlackWhileModelHasContent(),
        )

        vm.redrawActivePane()
        advanceUntilIdle()

        // LOAD-BEARING (RED on base = 0): Redraw fired the surface force-repaint. Without
        // the fix redrawActivePane only reseeds the model, which restores nothing here (the
        // model already matches tmux) → this count stays 0 and the surface stays black.
        assertTrue(
            "manual Redraw must request a surface force-repaint to recover a surface-only-black " +
                "(the model reseed alone recovers nothing here)",
            pane.terminalState.surfaceRepaintRequestCountForTest() >= 1,
        )

        // The requested surface repaint drives the real production path
        // (TerminalSurface's collector → view.forceSurfaceRepaint() → the next onDraw takes
        // the CONTENT path and reports it via the paint-confirmation seam). We mirror that
        // resulting content paint here to prove the recovery actually CLEARS the
        // surface-black. On base (no fix) no repaint is requested, so the maintainer would
        // never reach this content paint and the surface stays black.
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 100L)
        assertFalse(
            "after Redraw's surface repaint drove a content paint, the surface is no longer black",
            pane.terminalState.surfaceIsBlackWhileModelHasContent(),
        )
    }

    @Test
    fun manualRedrawDoesNotSpuriouslyRepaintAHealthySurface() = runVmTest { sink ->
        // Redraw always fires ONE surface repaint (idempotent full-clip invalidate). This
        // pins that a healthy surface is recovered by exactly ONE request, never a loop.
        val client = FakeTmuxClient().withSinglePaneRow("work", "%11")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%11" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        // A healthy, content-painted surface.
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 2L)
        assertFalse(pane.terminalState.surfaceIsBlackWhileModelHasContent())

        vm.redrawActivePane()
        advanceUntilIdle()

        assertEquals(
            "one surface repaint per Redraw tap — no thrash loop on a healthy pane",
            1,
            pane.terminalState.surfaceRepaintRequestCountForTest(),
        )
    }

    // -------------------------------------------------------------------------
    // Issue #1203 — class coverage (G2): the AUTO-HEAL fires the surface repaint for an
    // ALT-SCREEN / agent pane too (Claude/Codex idle alt-screen never re-emits %output),
    // not just a shell pane. Same detector, different pane content.
    // -------------------------------------------------------------------------

    @Test
    fun autoHealForcesSurfaceRepaintForAgentAltScreenPane() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%12")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%12" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // A sparse alt-screen agent frame that still MATCHES tmux (oracle healthy).
        renderAgentAltScreenFrame(pane)
        assertTrue(pane.terminalState.renderedNonBlankCharCount() > 0)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = agentAltScreenLines(), isError = false),
        )
        assertFalse(
            "precondition: the agent pane's model matches tmux (oracle healthy)",
            pane.terminalState.visibleRenderLostFrameVsCapture(
                agentAltScreenLines().joinToString("\n"),
            ),
        )
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 3L)
        assertTrue(pane.terminalState.surfaceIsBlackWhileModelHasContent())

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertFalse("no model reseed for an oracle-healthy agent pane", healed)
        assertTrue(
            "the auto-heal must request a surface force-repaint for a surface-only-black " +
                "agent/alt-screen pane too (class coverage, not just shell panes)",
            pane.terminalState.surfaceRepaintRequestCountForTest() >= 1,
        )
        val event = sink.singleBlackFrameEvent()
        assertEquals("surface_black_model_intact", event.fields["class"])
    }

    // -------------------------------------------------------------------------
    // Issue #1203 — class coverage (G2): with MULTIPLE panes present, the auto-heal's
    // surface repaint targets the ACTIVE VISIBLE pane (page 0) — the one the user is
    // looking at after a reveal/switch — not a background pane. Proves the recovery is
    // pane-local and reaches whichever pane is active, the on-device "black after switch".
    // -------------------------------------------------------------------------

    @Test
    fun autoHealForcesSurfaceRepaintOnActiveVisiblePaneWithMultiplePanes() = runVmTest { sink ->
        val client = FakeTmuxClient().withTwoPaneRows("work", "%13", "%14")
        val vm = connectVm(client)
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // The active visible pane is page 0 — the head of the published pane list.
        val activePane = vm.panes.value.first()
        val backgroundPane = vm.panes.value.first { it.paneId != activePane.paneId }

        renderDenseFrame(activePane)
        assertTrue(activePane.terminalState.renderedNonBlankCharCount() > 0)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        // The ACTIVE pane's surface goes black while its model is intact.
        activePane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 4L)
        assertTrue(activePane.terminalState.surfaceIsBlackWhileModelHasContent())

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertTrue(
            "the auto-heal must recover the surface-only-black on the ACTIVE visible pane",
            activePane.terminalState.surfaceRepaintRequestCountForTest() >= 1,
        )
        assertEquals(
            "the background pane must NOT be surface-repainted (the heal is pane-local to the " +
                "active visible pane)",
            0,
            backgroundPane.terminalState.surfaceRepaintRequestCountForTest(),
        )
        assertEquals(
            "surface_black_model_intact",
            sink.singleBlackFrameEvent().fields["class"],
        )
    }

    // -------------------------------------------------------------------------
    // Issue #1203 — a MODEL-healthy + SURFACE-healthy pane must NOT request a surface
    // repaint from the auto-heal (no thrash on a genuinely-fine pane).
    // -------------------------------------------------------------------------

    @Test
    fun autoHealDoesNotForceSurfaceRepaintWhenSurfaceIsHealthy() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%15")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%15" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 5L)
        assertFalse(pane.terminalState.surfaceIsBlackWhileModelHasContent())

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "a healthy surface must NOT trigger a surface repaint from the auto-heal",
            0,
            pane.terminalState.surfaceRepaintRequestCountForTest(),
        )
    }

    // -------------------------------------------------------------------------
    // GREEN → export: the new class flows into the EXPORTABLE JSONL (criterion 4).
    // -------------------------------------------------------------------------

    @Test
    fun surfaceBlackClassLandsInExportableJsonl() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%2")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%2" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 5L)

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        // The exportable ring is the same JSONL the Settings → Diagnostics "Share log"
        // export reads; the event name + class must be present in it.
        val exported = sink.eventsNamed("black_frame_observed")
        assertEquals(1, exported.size)
        assertEquals("surface_black_model_intact", exported.single().fields["class"])
    }

    // -------------------------------------------------------------------------
    // RED side — model intact + capture matches (oracle healthy) but the surface last
    // painted CONTENT → NO event. Proves the surface-paint signal is load-bearing.
    // -------------------------------------------------------------------------

    @Test
    fun surfaceHealthyPaneWithHealthyPaintRecordsNothing() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%3")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%3" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        // The surface's most-recent paint was CONTENT — not black.
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 1L)
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 2L)
        assertFalse(
            "a surface that painted content is NOT black",
            pane.terminalState.surfaceIsBlackWhileModelHasContent(),
        )

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "a healthy model + healthy surface must NOT fingerprint any black frame",
            0,
            sink.eventsNamed("black_frame_observed").size,
        )
    }

    // -------------------------------------------------------------------------
    // RED side — a surface that has never painted at all (cold, pre-first-onDraw) is
    // NOT fingerprinted even with a black model, so a freshly-seeded pane is silent.
    // -------------------------------------------------------------------------

    @Test
    fun surfaceThatHasNeverPaintedIsNotFingerprinted() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%4")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%4" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        // NO paint reported at all — the cold pre-first-onDraw transient.
        assertFalse(pane.terminalState.surfaceIsBlackWhileModelHasContent())

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "a surface that has not painted yet is a transient, never a fingerprint",
            0,
            sink.eventsNamed("black_frame_observed").size,
        )
    }

    // -------------------------------------------------------------------------
    // Additive (criterion 2) — the pre-existing `lost_after_paint` class + the
    // `stale_render_heal` success event STILL emit unchanged through the modified
    // oracle site, and the new surface class does NOT also fire on that path.
    // -------------------------------------------------------------------------

    @Test
    fun existingLostAfterPaintClassStillEmitsUnchanged() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%5")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%5" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        // Render fully black, tmux still holds a dense frame → the render LOST the frame,
        // so the oracle takes the (unchanged) lost_after_paint + heal path, NOT my new
        // model-healthy branch. A surface-black signal is present but must be ignored here.
        blankRender(pane)
        assertEquals(0, pane.terminalState.renderedNonBlankCharCount())
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 9L)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )

        val healed = vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertTrue("the render lost the frame → the heal fires", healed)
        val event = sink.singleBlackFrameEvent()
        assertEquals(
            "the lost_after_paint class is unchanged — the surface branch never runs when " +
                "the model itself lost the frame",
            "lost_after_paint",
            event.fields["class"],
        )
        assertEquals(
            "stale_render_heal must still fire on a successful heal",
            1,
            sink.eventsNamed("stale_render_heal").size,
        )
    }

    // -------------------------------------------------------------------------
    // No new poll (criterion 3) — the whole heal+observe pass issues exactly ONE
    // capture-pane; the surface-black emission rides it and adds no round-trip/loop.
    // -------------------------------------------------------------------------

    @Test
    fun emissionRidesTheExistingHealCaptureNoExtraRoundTrip() = runVmTest { sink ->
        val client = FakeTmuxClient().withSinglePaneRow("work", "%6")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%6" }
        vm.resizeRemotePty(80, 40)
        advanceUntilIdle()

        renderDenseFrame(pane)
        client.capturePaneResponses.addLast(
            CommandResponse(number = 99L, output = denseFrameLines(), isError = false),
        )
        pane.terminalState.recordSurfaceFramePaintedForTest(paintedEmulatorContent = false, atMs = 7L)
        val capturesBefore = client.captureCount()

        vm.healActivePaneIfStaleRenderForTest()
        advanceUntilIdle()

        assertEquals(
            "exactly ONE capture-pane for the whole heal+observe pass — no new poll",
            capturesBefore + 1,
            client.captureCount(),
        )
        assertEquals(1, sink.eventsNamed("black_frame_observed").size)
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
        // Suppress the background watchdogs so the ONLY heal pass is the one the test
        // drives directly — no spurious events.
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

    /** Dense, correctly-painted viewport that MATCHES tmux (oracle healthy). */
    private fun renderDenseFrame(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            buildString {
                append(CLEAR_ONLY)
                denseFrameLines().forEach { append(it).append("\r\n") }
            }.toByteArray(Charsets.US_ASCII),
        )
    }

    /** Fully black render: a clear-only overpaint wipes the visible viewport. */
    private fun blankRender(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(CLEAR_ONLY.toByteArray(Charsets.US_ASCII))
    }

    /**
     * A sparse alt-screen AGENT viewport (Claude/Codex idle) that still MATCHES tmux —
     * fewer live rows than a shell frame, but enough non-blank content that the model
     * grid holds a frame (oracle healthy). Used to prove the recovery covers agent panes.
     */
    private fun renderAgentAltScreenFrame(pane: TmuxPaneState) {
        pane.terminalState.appendRemoteOutput(
            buildString {
                append(CLEAR_ONLY)
                agentAltScreenLines().forEach { append(it).append("\r\n") }
            }.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun agentAltScreenLines(): List<String> = buildList {
        add("ISSUE1203-AGENT-ALT-SCREEN")
        repeat(10) { add("claude idle alt-screen row $it with real agent content") }
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

    private fun FakeTmuxClient.withTwoPaneRows(
        sessionName: String,
        firstPaneId: String,
        secondPaneId: String,
        title: String = sessionName,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "$firstPaneId\t@0\t\$0\t$sessionName\t$title\t0",
                    "$secondPaneId\t@1\t\$0\t$sessionName\t$title\t0",
                ),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 3L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 4L, output = listOf("0,0"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 5L, output = listOf("0,0"), isError = false),
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
        add("ISSUE1192-SURFACE-BLACK-MODEL-INTACT")
        repeat(27) { add("recovered context row $it with real tmux content") }
    }

    private companion object {
        // ESC[2J ESC[H — clear screen + cursor home (the overpaint preamble).
        val CLEAR_ONLY: String = "[2J[H"
    }
}
