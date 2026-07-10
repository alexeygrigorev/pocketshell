package com.pocketshell.core.terminal.ui

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1443 — the pixel-truth surface-black probe, the LAST code-confirmed
 * black-screen blind spot.
 *
 * ## The blind spot this pins
 *
 * Every surface-black signal in the app is derived from the emulator MODEL:
 * `TerminalView.onDraw` reports `paintedEmulatorContent` from
 * `hasNonBlankVisibleRow()` (a MODEL check, NO pixel readback — the explicit #1296
 * non-goal), which drives [TerminalSurfaceState.surfaceIsBlackWhileModelHasContent].
 * So a GENUINE pixel/GPU-layer black — the composited surface is black while the
 * model still carries the frame (a lost HWUI hardware layer, a RenderNode that
 * stopped compositing, a Compose layer that dropped the child View's buffer) — is
 * INVISIBLE: `onDraw` runs, the model has content, so a CONTENT paint is recorded,
 * `surfaceIsBlackWhileModelHasContent()` returns false, and nothing fingerprints it.
 *
 * ## Red → green, in one run (the #780 synthetic model)
 *
 * A real GPU-layer black cannot be produced in the CI JVM (no real surface /
 * PixelCopy), so — like the #1192/#1296 tests — this drives the exact blind-spot
 * STATE synthetically: the model carries content AND the surface's most-recent
 * frame was a CONTENT paint (so the model-derived detector reads HEALTHY), while an
 * injected pixel sampler reports the surface pixels are (near-)uniformly black.
 *
 *  - RED (the blind spot): in that exact state
 *    [TerminalSurfaceState.surfaceIsBlackWhileModelHasContent] returns `false` — the
 *    model-derived detector is blind to it BY CONSTRUCTION, so on base nothing sees
 *    or records the pixel-black.
 *  - GREEN (the probe): [TerminalSurfaceState.probePixelBlackWhileModelHasContent]
 *    samples the actual pixels and FIRES (returns true, increments the observation
 *    count) on the SAME state — the occurrence is now attributable.
 *
 * The load-bearing assertion is the probe firing; reverting the probe's
 * `pixelBlackModelHasContentObservedCount += 1; return true` to `return false`
 * turns [pixelBlackWhileModelHasContent_modelDetectorBlind_pixelProbeFires] RED.
 *
 * DIAGNOSTICS ONLY: the probe wires NO heal — this file asserts detection +
 * fingerprint firing, no reseed/repaint is requested.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStatePixelBlackProbeTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /**
     * Drive the state to the exact blind-spot precondition: attach a producer, seed
     * the model with visible content, and record a CONTENT surface paint so the
     * MODEL-derived detector reads HEALTHY (the surface-only-black class the #1192
     * detector CAN see is explicitly excluded here — this is the pixel-only class).
     */
    private fun withContentBearingState(
        recordContentPaint: Boolean = true,
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
                "[2J[HISSUE1443-PIXEL-BLACK-MODEL-HAS-CONTENT\r\n".toByteArray(Charsets.US_ASCII),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "precondition: the MODEL grid must hold content — otherwise the probe's " +
                    "content-present gate makes it vacuously false and proves nothing",
                state.renderedNonBlankCharCount() > 0,
            )
            if (recordContentPaint) {
                // The most-recent painted frame was a CONTENT paint → the model-derived
                // detector reads HEALTHY. This is the blind spot: the surface can still
                // be black at the pixel/GPU layer while this says all is well.
                state.recordSurfaceFramePaintedForTest(paintedEmulatorContent = true, atMs = 1L)
                assertFalse(
                    "RED (the blind spot): the MODEL-derived surface-black detector is FALSE " +
                        "in the exact pixel-black state — it cannot see a pixel/GPU-layer black",
                    state.surfaceIsBlackWhileModelHasContent(),
                )
            }
            block(state)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    /**
     * Criterion 1 (red→green) — model content + model detector blind, but the pixel
     * sampler reports (near-)uniform black → the probe FIRES and records the
     * observation. This is the load-bearing green assertion.
     */
    @Test
    fun pixelBlackWhileModelHasContent_modelDetectorBlind_pixelProbeFires() =
        withContentBearingState { state ->
            state.setSurfacePixelSampler { true } // synthetic: surface pixels are black

            assertEquals(0, state.pixelBlackModelHasContentObservedCountForTest())
            assertTrue(
                "#1443 GREEN: the pixel probe must FIRE when the surface is pixel-black while " +
                    "the model carries content — the class the model detectors are blind to",
                state.probePixelBlackWhileModelHasContent(nowMs = 10_000L),
            )
            assertEquals(
                "the pixel-black observation must be fingerprinted exactly once",
                1,
                state.pixelBlackModelHasContentObservedCountForTest(),
            )
        }

    /**
     * Criterion 2 (no false positive) — the SAME content-bearing state, but the pixel
     * sampler reports the surface carries visible content → the probe does NOT fire.
     * Proves the probe is load-bearing on the pixel sample, not just always-firing.
     */
    @Test
    fun pixelSampleHasContent_probeDoesNotFire() =
        withContentBearingState { state ->
            state.setSurfacePixelSampler { false } // synthetic: surface pixels carry content

            assertFalse(
                "#1443: a content-bearing pixel sample must NOT fire the probe (no false positive)",
                state.probePixelBlackWhileModelHasContent(nowMs = 10_000L),
            )
            assertEquals(0, state.pixelBlackModelHasContentObservedCountForTest())
        }

    /**
     * No sampler bound (the plain-SSH / pre-attach window) → the probe no-ops. It must
     * never fingerprint without positive pixel evidence.
     */
    @Test
    fun noSamplerBound_probeDoesNotFire() =
        withContentBearingState { state ->
            assertFalse(state.probePixelBlackWhileModelHasContent(nowMs = 10_000L))
            assertEquals(0, state.pixelBlackModelHasContentObservedCountForTest())
        }

    /**
     * The surface could not be sampled (no host window / 0-size / PixelCopy error) →
     * the production sampler returns `null` ("no evidence") → the probe does NOT fire.
     */
    @Test
    fun sampleUnavailable_null_probeDoesNotFire() =
        withContentBearingState { state ->
            state.setSurfacePixelSampler { null }

            assertFalse(
                "#1443: a null pixel sample is 'no evidence' and must never be fingerprinted",
                state.probePixelBlackWhileModelHasContent(nowMs = 10_000L),
            )
            assertEquals(0, state.pixelBlackModelHasContentObservedCountForTest())
        }

    /**
     * Content-present gate (never vacuous) — a black pixel sample over a model with NO
     * content is the ordinary blank pane the model detectors already handle, NOT this
     * class. The probe requires model content-present, so it does NOT fire here.
     */
    @Test
    fun modelHasNoContent_probeDoesNotFire_evenWhenPixelsBlack() = runBlocking {
        val state = TerminalSurfaceState()
        state.setSurfacePixelSampler { true } // pixels black...
        assertEquals(
            "precondition: no model content",
            0,
            state.renderedNonBlankCharCount(),
        )
        assertFalse(
            "#1443: with NO model content the probe must not fire — it is never vacuously true",
            state.probePixelBlackWhileModelHasContent(nowMs = 10_000L),
        )
        assertEquals(0, state.pixelBlackModelHasContentObservedCountForTest())
    }

    /**
     * BLOCKING soundness (reviewer round 2) — the `PixelCopy` readback blocks its
     * thread on an async callback (`latch.await`), and the production probe is invoked
     * from the watchdog's `Dispatchers.Main.immediate` scope. Running the blocking
     * sample on the UI thread would stall the render path for up to the PixelCopy
     * timeout — worst case exactly during a wedged/black surface, making the freeze
     * worse. The probe MUST dispatch the sample OFF the caller thread. This captures
     * the thread the sampler runs on and asserts it is neither the probe's caller
     * thread NOR the main looper thread.
     *
     * RED (remove the `withContext(pixelProbeDispatcher)`): the sampler runs on the
     * caller thread → `assertNotSame(callerThread, …)` fails. GREEN (dispatched to
     * IO): the sample runs on an IO-pool thread.
     */
    @Test
    fun pixelSample_dispatchedOffCallerThread_andNotOnMain() =
        withContentBearingState { state ->
            val callerThread = Thread.currentThread()
            val sampledOn = AtomicReference<Thread?>()
            state.setSurfacePixelSampler {
                sampledOn.set(Thread.currentThread())
                true
            }

            assertTrue(state.probePixelBlackWhileModelHasContent(nowMs = 20_000L))

            val sampledThread = sampledOn.get()
            assertNotNull("the sampler must have run", sampledThread)
            assertNotSame(
                "#1443: the blocking PixelCopy sample must NOT run on the probe's caller (UI) thread",
                callerThread,
                sampledThread,
            )
            assertNotSame(
                "#1443: the sample must never run on the main looper thread",
                Looper.getMainLooper().thread,
                sampledThread,
            )
        }

    /**
     * Criterion (off render path / bounded) — even a hot caller cannot turn the
     * PixelCopy into a per-frame cost: the probe rate-limits itself to at most one
     * real sample per window. Two calls inside the window fingerprint ONCE; a call
     * past the window fingerprints again.
     */
    @Test
    fun probeIsRateBounded_atMostOneSamplePerWindow() =
        withContentBearingState { state ->
            state.setSurfacePixelSampler { true }

            assertTrue(state.probePixelBlackWhileModelHasContent(nowMs = 1_000L))
            assertEquals(1, state.pixelBlackModelHasContentObservedCountForTest())

            // Within the min-interval window: suppressed, no second sample/fingerprint.
            assertFalse(
                "#1443: a call inside the rate-limit window must be suppressed (bounded cost)",
                state.probePixelBlackWhileModelHasContent(nowMs = 1_500L),
            )
            assertEquals(1, state.pixelBlackModelHasContentObservedCountForTest())

            // Past the window: a fresh sample is allowed and fires again.
            assertTrue(state.probePixelBlackWhileModelHasContent(nowMs = 9_000L))
            assertEquals(2, state.pixelBlackModelHasContentObservedCountForTest())
        }
}
