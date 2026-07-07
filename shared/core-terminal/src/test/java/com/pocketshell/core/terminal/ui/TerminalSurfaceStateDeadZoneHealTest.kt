package com.pocketshell.core.terminal.ui

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream

/**
 * Issue #1176 (GAP A) — the heal-oracle DEAD-ZONE the maintainer kept hitting on the latest
 * release (spike #874): a >3-line half-black BAND covering ~a third-to-a-half of the screen was
 * judged HEALTHY by the pre-#1176 ceilings and NEVER healed on any path.
 *
 * These tests pin that the oracle heals such a band. Since #1300 the oracle is a per-line-hash
 * content diff ([TerminalSurfaceState.visibleRenderLostFrameVsCapture]) — heal when the render is
 * missing at least a QUARTER of tmux's content lines — so a half-to-two-thirds black band is
 * judged LOST while a dense / only-slightly-behind pane is not.
 *
 * ## RED → GREEN (D33/G1/G10)
 *
 * [deadZoneBandClearsBothOldCeilingsYetIsHealedByUnifiedOracle] composes a >3-line half-black
 * band whose live share of the rows is > 0.50 (clears the old LINE ceiling —
 * `visibleScreenLooksSparseForSendHeal` is FALSE). The oracle judges it LOST (GREEN). The
 * geometry is visible-render-vs-capture-pane, not a proxy.
 *
 * ## Class coverage (D32-G2)
 *
 * [unifiedOracleHealsAcrossTheBlackBandSpectrum] parametrizes 30/40/50/60/70% black measured by
 * BOTH char-fraction AND line-fraction (uniform-density bands, so the two are equal), asserting
 * the whole dead-zone is closed as a class — not the single reported band. [regressionCasesStill]
 * confirms fully-black, ≤3-line partial, the >3-line half-black at the exact #1153 0.50 boundary,
 * and the scattered-fragment #966 case all still heal, and a DENSE pane does NOT (no over-heal).
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateDeadZoneHealTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun withAttachedSurface(block: (TerminalSurfaceState) -> Unit) = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            block(state)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    /** ESC (U+001B), so the test feeds real control sequences (a literal `[2J` is just text). */
    private val esc = "\u001b"

    /** A single non-wrapping content line: 20 non-blank chars, well under the 80-col grid. */
    private val contentLine = "abcdefghij klmnopqrst"

    /**
     * tmux's authoritative capture: a full 24-row viewport of [contentLine] (the emulator's
     * visible-row count is 24). A render that painted only [liveRows] of those rows lost a black
     * BAND that tmux still holds — its char-coverage AND its live-line fraction are both
     * `liveRows / 24` (uniform density), so a band is defined identically by BOTH metrics.
     */
    private fun fullCapture(): String = buildString { repeat(24) { append("$contentLine\n") } }

    /** Paint [liveRows] non-wrapping content lines on a cleared screen — a contiguous black band above. */
    private fun paintBand(state: TerminalSurfaceState, liveRows: Int) {
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(liveRows) { append("$contentLine\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // -----------------------------------------------------------------------------------------
    // RED → GREEN: the exact dead-zone band the two old ceilings BOTH judged healthy.
    // -----------------------------------------------------------------------------------------

    @Test
    fun deadZoneBandClearsBothOldCeilingsYetIsHealedByUnifiedOracle() = withAttachedSurface { state ->
        // 14 of 24 rows live (~42% black band): live-fraction 0.58 (> 0.50) AND char-coverage
        // ~0.58 (> 0.25) — the render cleared BOTH old ceilings.
        paintBand(state, liveRows = 14)
        val capture = fullCapture()

        assertFalse(
            "precondition: a half-black band is NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertFalse(
            "precondition: it has MORE than 3 live lines, so it is NOT the ≤3-line partial-black",
            state.visibleScreenIsPartiallyBlank(),
        )
        // The old LINE ceiling (50%) judged it healthy: live share of the rows > 0.50, so the
        // 0.5-live-fraction send-heal pre-check reads NON-sparse.
        assertFalse(
            "RED premise: > 50% of the visible rows are live → the old 50%-line ceiling judged " +
                "the band HEALTHY (cleared the LINE ceiling)",
            state.visibleScreenLooksSparseForSendHeal(),
        )

        // GREEN: the unified char-coverage oracle judges the band LOST (on base this returns
        // FALSE — the dead-zone — so this assertion is the red→green discriminator).
        assertTrue(
            "GREEN (#1176): the unified oracle must judge a >3-line, >50%-rows-live half-black " +
                "band LOST — the render reproduces only ~58% of tmux's visible content",
            state.visibleRenderLostFrameVsCapture(capture),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Class coverage: 30/40/50/60/70% black measured by BOTH char-fraction AND line-fraction.
    // -----------------------------------------------------------------------------------------

    @Test
    fun unifiedOracleHealsAcrossTheBlackBandSpectrum() {
        // (approxBlackPercent, liveRows-of-24). Uniform density → char-coverage == line-fraction
        // == liveRows/24, so each band is the target %black by BOTH metrics simultaneously.
        // The 30% and 40% bands (>50% rows live) are the #1176 dead-zone that was NEVER healed on
        // base; 50/60/70% were already caught by the old 50%-line ceiling — all must heal now.
        val spectrum = listOf(
            30 to 17, // ~29% black, live-fraction 0.71 → DEAD-ZONE (base: not healed)
            40 to 14, // ~42% black, live-fraction 0.58 → DEAD-ZONE (base: not healed)
            50 to 12, // 50% black, live-fraction 0.50   → #1153 boundary
            60 to 10, // ~58% black, live-fraction 0.42
            70 to 7,  // ~71% black, live-fraction 0.29
        )
        val capture = fullCapture()
        for ((blackPercent, liveRows) in spectrum) {
            withAttachedSurface { state ->
                paintBand(state, liveRows)
                val liveFraction = liveRows / 24.0
                // Sanity: the band really is ~blackPercent black by the LINE metric.
                assertTrue(
                    "band $blackPercent% black: live-fraction $liveFraction should be ~${1 - blackPercent / 100.0}",
                    kotlin.math.abs((1 - liveFraction) - blackPercent / 100.0) < 0.06,
                )
                assertTrue(
                    "#1176 class coverage: a $blackPercent%-black band (by BOTH char & line " +
                        "fraction) must be judged LOST by the unified oracle",
                    state.visibleRenderLostFrameVsCapture(capture),
                )
                if (liveFraction > 0.5) {
                    // The DEAD-ZONE members: confirm they cleared the old 50%-line send-heal
                    // ceiling (so they were genuinely un-healed on the pre-#1176 line ceiling),
                    // closing the dead-zone as a class not a point.
                    assertFalse(
                        "band $blackPercent%: cleared the old 50%-line ceiling",
                        state.visibleScreenLooksSparseForSendHeal(),
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Regression: the previously-covered cases still heal; a dense pane still does NOT.
    // -----------------------------------------------------------------------------------------

    @Test
    fun regressionCasesStill() {
        val capture = fullCapture()

        // Fully black (clear-only) still heals.
        withAttachedSurface { state ->
            state.appendRemoteOutput("$esc[2J$esc[H".toByteArray(Charsets.US_ASCII))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("precondition: fully blank", state.visibleScreenIsBlank())
            assertTrue(
                "regression: a fully-black pane against a full capture still heals",
                state.visibleRenderLostFrameVsCapture(capture),
            )
        }

        // ≤3-line partial-black (#1138 class) still heals.
        withAttachedSurface { state ->
            paintBand(state, liveRows = 2)
            assertTrue("precondition: ≤3-line partial-black", state.visibleScreenIsPartiallyBlank())
            assertTrue(
                "regression: a ≤3-line partial-black pane still heals",
                state.visibleRenderLostFrameVsCapture(capture),
            )
        }

        // The >3-line half-black at the exact #1153 0.50 line-fraction boundary still heals.
        withAttachedSurface { state ->
            paintBand(state, liveRows = 12)
            assertFalse("precondition: not ≤3-line partial-black", state.visibleScreenIsPartiallyBlank())
            assertTrue(
                "regression (#1153): the >3-line half-black at the 0.50 boundary still heals",
                state.visibleRenderLostFrameVsCapture(capture),
            )
        }

        // A DENSE render (well over 3/4 of the rows live) must NOT heal — no over-heal.
        withAttachedSurface { state ->
            paintBand(state, liveRows = 22)
            assertFalse(
                "over-heal guard: a dense render (only ~8% black) must NOT heal — a near-complete " +
                    "pane a few rows behind a streaming agent is not a lost frame",
                state.visibleRenderLostFrameVsCapture(capture),
            )
        }
    }
}
