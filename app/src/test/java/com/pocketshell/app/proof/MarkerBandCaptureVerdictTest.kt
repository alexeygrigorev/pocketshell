package com.pocketshell.app.proof

import com.pocketshell.testsupport.FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE
import com.pocketshell.testsupport.MarkerBandCaptureOutcome
import com.pocketshell.testsupport.evaluateMarkerBandCapture
import com.pocketshell.testsupport.requiredMarkerSpanPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1994 — per-push pin for the device-pixel capture verdict.
 *
 * The nightly failure this guards reported `magenta span=0 px … matchingPixels=0`
 * for the FIRST capture of `SessionSwipeSwitchE2eTest`, and named the terminal's
 * paint as the cause. Two very different worlds produce zero magenta pixels, and
 * only one of them is the terminal's fault. These tests pin which cause is named
 * — and, more importantly, pin that a missing band is NEVER turned into a pass.
 */
class MarkerBandCaptureVerdictTest {

    @Test
    fun aCompleteBandIsPainted() {
        val verdict = evaluate(paintedSpanPx = 170, matchingPixels = 4046, appWindowFocused = true)

        assertEquals(MarkerBandCaptureOutcome.PAINTED, verdict.outcome)
        assertTrue(verdict.painted)
    }

    @Test
    fun aPartialBandIsNotPaintedEvenWithTheAppFocused() {
        // The #469 dirty-clip class: only `A2` painted, ~2 cells wide.
        val verdict = evaluate(paintedSpanPx = 34, matchingPixels = 800, appWindowFocused = true)

        assertEquals(MarkerBandCaptureOutcome.NOT_PAINTED, verdict.outcome)
        assertFalse(verdict.painted)
    }

    @Test
    fun theExactNightlyObservationWithTheAppFocusedStaysARealPaintFailure() {
        // span=0, matchingPixels=0, fontWidth=17.0 — run 30961154855 shard 2.
        // With the app owning the display there is no excuse available.
        val verdict = evaluate(paintedSpanPx = 0, matchingPixels = 0, appWindowFocused = true)

        assertEquals(MarkerBandCaptureOutcome.NOT_PAINTED, verdict.outcome)
        assertTrue(verdict.message.contains("did not paint the complete 'A237-READY'"))
        assertFalse(verdict.message.contains(FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE))
    }

    @Test
    fun theSameObservationUnderAForeignWindowNamesTheForeignOwnerInstead() {
        val verdict = evaluate(
            paintedSpanPx = 0,
            matchingPixels = 0,
            appWindowFocused = false,
            activeWindowDescription =
                "active_window_pkg=android active_window_class=android.widget.FrameLayout",
        )

        assertEquals(MarkerBandCaptureOutcome.FOREIGN_WINDOW_OWNS_DISPLAY, verdict.outcome)
        assertFalse(verdict.painted)
        assertTrue(verdict.message.contains(FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE))
        assertTrue(verdict.message.contains("active_window_pkg=android"))
    }

    @Test
    fun aForeignWindowNeverConvertsAMissingBandIntoAPass() {
        // The whole point of naming the cause is diagnosis, not amnesty.
        val verdict = evaluate(
            paintedSpanPx = 0,
            matchingPixels = 0,
            appWindowFocused = false,
            activeWindowDescription = "active_window_pkg=android",
        )

        assertFalse(verdict.painted)
    }

    @Test
    fun aCompleteBandStaysPaintedEvenIfFocusWasLostAfterwards() {
        // Pixels are the authority: if the band IS on the frame, a focus report
        // must not manufacture a failure.
        val verdict = evaluate(
            paintedSpanPx = 170,
            matchingPixels = 4046,
            appWindowFocused = false,
            activeWindowDescription = "active_window_pkg=android",
        )

        assertEquals(MarkerBandCaptureOutcome.PAINTED, verdict.outcome)
    }

    @Test
    fun anUnreadTerminalRendererIsAFailureNotAPass() {
        // fontWidth=0 makes requiredSpan 0; without the explicit guard a span of
        // 0 would satisfy `0 >= 0` and the capture would pass having measured
        // nothing at all.
        val verdict = evaluate(
            paintedSpanPx = 0,
            matchingPixels = 0,
            appWindowFocused = true,
            fontWidthPx = 0f,
        )

        assertEquals(MarkerBandCaptureOutcome.NOT_PAINTED, verdict.outcome)
    }

    @Test
    fun theRequiredSpanIsTheMarkerWidthMinusTheLastCell() {
        // The span is measured between the first and last magenta pixel, so the
        // trailing cell's own width is not part of it.
        assertEquals(153, requiredMarkerSpanPx("A237-READY", 17.0f))
        assertEquals(0, requiredMarkerSpanPx("", 17.0f))
        assertEquals(0, requiredMarkerSpanPx("X", 17.0f))
    }

    @Test
    fun theMessageCarriesTheAttemptTrailSoALatePaintIsDistinguishable() {
        val verdict = evaluate(
            paintedSpanPx = 0,
            matchingPixels = 0,
            appWindowFocused = true,
            attempts = 41,
            elapsedMs = 20_000L,
        )

        assertTrue(verdict.message.contains("attempts=41"))
        assertTrue(verdict.message.contains("elapsedMs=20000"))
    }

    private fun evaluate(
        paintedSpanPx: Int,
        matchingPixels: Int,
        appWindowFocused: Boolean,
        activeWindowDescription: String = "app_window_focused=true",
        fontWidthPx: Float = 17.0f,
        attempts: Int = 1,
        elapsedMs: Long = 150L,
    ) = evaluateMarkerBandCapture(
        marker = "A237-READY",
        artifactName = "issue237-01-attached-session-a",
        paintedSpanPx = paintedSpanPx,
        matchingPixels = matchingPixels,
        fontWidthPx = fontWidthPx,
        appWindowFocused = appWindowFocused,
        activeWindowDescription = activeWindowDescription,
        attempts = attempts,
        elapsedMs = elapsedMs,
    )
}
