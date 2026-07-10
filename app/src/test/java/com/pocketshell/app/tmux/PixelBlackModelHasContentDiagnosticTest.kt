package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream

/**
 * Issue #1443 — the DIAGNOSTIC pixel-truth fingerprint on the WATCHDOG SUSPICION
 * PATH (the app-side wiring of the pixel probe added to
 * [TerminalSurfaceState.probePixelBlackWhileModelHasContent]).
 *
 * [StaleRenderSparseWakeBaselines.rememberIfHealthySparse] is invoked from the
 * stale-render watchdog's `HealOutcome.Healthy` tick — exactly the
 * MODEL-reports-content-present window where a genuine pixel/GPU-layer black hides
 * (every model-derived detector reads healthy). This proves that on that path:
 *
 *  - GREEN: when the surface pixels are (near-)uniformly black over a content-bearing
 *    model, the distinct `pixel_black_model_has_content` class is emitted into the
 *    SAME exportable `black_frame_observed` JSONL ring the other classes use — so the
 *    occurrence is no longer invisible.
 *  - RED (no false positive): when the surface pixels carry content, NOTHING is
 *    emitted — the fingerprint is load-bearing on the pixel sample, not always-firing.
 *
 * DIAGNOSTICS ONLY: this asserts an emitted diagnostic event; NO heal/reseed/repaint
 * is requested off the signal (the surface-repaint request count stays 0).
 *
 * The real GPU-layer black cannot be produced in the CI JVM, so the pixel sample is
 * injected synthetically (the #780 model); the real PixelCopy wiring
 * ([com.termux.view.TerminalView.sampleSurfaceNearUniformBlack]) is exercised
 * on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PixelBlackModelHasContentDiagnosticTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun withContentBearingPane(
        block: suspend (TerminalSurfaceState) -> Unit,
    ) = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            state.appendRemoteOutput(
                "[2J[HISSUE1443-WATCHDOG-PATH-PIXEL-BLACK\r\n".toByteArray(Charsets.US_ASCII),
            )
            shadowOf(Looper.getMainLooper()).idle()
            // Model has content AND the last surface paint was CONTENT → the
            // model-derived detector reads HEALTHY (the exact blind-spot state).
            state.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 1L)
            assertTrue(state.renderedNonBlankCharCount() > 0)
            block(state)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    private fun paneWith(state: TerminalSurfaceState): TmuxPaneState =
        TmuxPaneState(
            paneId = "%1",
            windowId = "@0",
            sessionId = "\$0",
            title = "",
            terminalState = state,
        )

    @Test
    fun watchdogHealthyTick_pixelBlackSurface_fingerprintsNewClass() =
        withContentBearingPane { state ->
            state.setSurfacePixelSampler { true } // synthetic: surface pixels are black
            val diagnostics = installRecordingDiagnosticSink()
            try {
                val baselines = StaleRenderSparseWakeBaselines()

                baselines.rememberIfHealthySparse(paneWith(state))

                val event = diagnostics.eventsNamed(BLACK_FRAME_OBSERVED_EVENT).single {
                    it.fields["class"] == BLACK_FRAME_CLASS_PIXEL_BLACK_MODEL_HAS_CONTENT
                }
                assertEquals("terminal", event.category)
                assertEquals("%1", event.fields["paneId"])
                assertEquals("@0", event.fields["windowId"])

                // DIAGNOSTICS ONLY: the signal must NOT have triggered any heal.
                assertEquals(
                    "#1443: the pixel-black fingerprint must wire NO heal — surface repaint count stays 0",
                    0,
                    state.surfaceRepaintRequestCountForTest(),
                )
            } finally {
                diagnostics.close()
            }
        }

    @Test
    fun watchdogHealthyTick_pixelSurfaceHasContent_emitsNothing() =
        withContentBearingPane { state ->
            state.setSurfacePixelSampler { false } // synthetic: surface pixels carry content
            val diagnostics = installRecordingDiagnosticSink()
            try {
                val baselines = StaleRenderSparseWakeBaselines()

                baselines.rememberIfHealthySparse(paneWith(state))

                assertTrue(
                    "#1443: a content-bearing surface must emit NO pixel-black class (no false positive)",
                    diagnostics.eventsNamed(BLACK_FRAME_OBSERVED_EVENT).none {
                        it.fields["class"] == BLACK_FRAME_CLASS_PIXEL_BLACK_MODEL_HAS_CONTENT
                    },
                )
            } finally {
                diagnostics.close()
            }
        }
}
