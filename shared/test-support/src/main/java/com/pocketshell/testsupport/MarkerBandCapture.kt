package com.pocketshell.testsupport

/**
 * Issue #1994 — the pure DECISION behind "did the device actually paint the
 * seeded marker band?".
 *
 * ## The failure this exists to stop misreporting
 *
 * `SessionSwipeSwitchE2eTest` proves the switched-to session's content is really
 * on the device surface, not merely in the emulator's text model, by seeding a
 * unique true-colour magenta cell background behind the marker and measuring the
 * magenta span on the UIAutomation screenshot. On nightly run 30961154855
 * (shard 2, exact main `1ddeadb3`) it reported:
 *
 * ```
 * device-visible viewport did not paint the complete 'A237-READY' cell band …
 * magenta span=0 px, required>=153 px, fontWidth=17.0, matchingPixels=0
 * ```
 *
 * ZERO matching pixels, on the very FIRST capture — before any switch, so before
 * any cached-runtime restore could be involved. The healthy run paints ~4046
 * matching pixels, so "0" is not a partial paint: nothing magenta was on the
 * captured frame at all. Two states produce exactly that, and the old oracle
 * blamed the terminal for both:
 *
 *  1. **The frame had not reached the display yet.** The oracle took ONE
 *     screenshot after `waitForIdleSync()` + a fixed 150 ms sleep. The main
 *     thread being idle does not mean SurfaceFlinger has composited; under
 *     hosted swiftshader load the model can legitimately lead the painted
 *     surface by more than 150 ms. There is no retry, so a late-but-correct
 *     paint is reported as a paint failure.
 *  2. **A foreign window owned the display.** Any dialog — most importantly a
 *     framework error / ANR dialog, package `android` — dims the whole screen
 *     behind it. A dim scrim multiplies every pixel, so an exact
 *     `r>=245, g<=10, b>=245` match drops to zero while the band is still there.
 *     The same shard reported `active_window_pkg=android
 *     active_window_class=android.widget.FrameLayout` for five other classes, so
 *     that state demonstrably existed on that device.
 *
 * Blaming the terminal's paint for (2) is the wrong-cost failure (G6): the green
 * assertion is on a signal that was never the app's to satisfy.
 *
 * ## The decision
 *
 * [evaluateMarkerBandCapture] is deliberately conservative **in the direction of
 * blaming the product**: it never converts "the band is missing" into a pass. It
 * only decides WHICH cause the failure names, and it insists the app window held
 * focus before a missing band may be attributed to the terminal at all.
 */

/** Outcome kinds of [evaluateMarkerBandCapture]. */
enum class MarkerBandCaptureOutcome {
    /** The painted span covers the whole marker — the capture is authoritative. */
    PAINTED,

    /**
     * The band is missing AND the app window did not own the display. The
     * terminal is not the cause; the failure must name the foreign owner.
     */
    FOREIGN_WINDOW_OWNS_DISPLAY,

    /**
     * The band is missing while the app window owned the display for the whole
     * bounded observation. This is the real device-paint failure.
     */
    NOT_PAINTED,
}

/**
 * Stable phrase for the "a foreign window owned the display" attribution, so a
 * red shard can be grepped for the real cause instead of the paint message.
 */
const val FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE: String =
    "the captured frame was not the app's: a foreign window owned the display " +
        "while the viewport screenshot was taken, so a missing marker band says " +
        "nothing about what the terminal painted."

/** Result of [evaluateMarkerBandCapture]. */
data class MarkerBandCaptureVerdict(
    val outcome: MarkerBandCaptureOutcome,
    val message: String,
) {
    val painted: Boolean get() = outcome == MarkerBandCaptureOutcome.PAINTED
}

/**
 * Decide a single bounded capture observation.
 *
 * @param marker the seeded marker text, e.g. `A237-READY`.
 * @param artifactName the artifact base name, so the message points at the PNG.
 * @param paintedSpanPx widest horizontal magenta run found on the frame.
 * @param matchingPixels total magenta pixels found (0 distinguishes "nothing"
 *   from "partial").
 * @param fontWidthPx the renderer's cell width; 0 means the terminal renderer
 *   was never read, which is itself a failure (never a pass).
 * @param appWindowFocused whether the app window held input focus across the
 *   observation window.
 * @param activeWindowDescription `describeActiveWindow()` output, only meaningful
 *   when [appWindowFocused] is false.
 * @param attempts how many screenshots the bounded pump took.
 * @param elapsedMs wall time the bounded pump spent.
 */
fun evaluateMarkerBandCapture(
    marker: String,
    artifactName: String,
    paintedSpanPx: Int,
    matchingPixels: Int,
    fontWidthPx: Float,
    appWindowFocused: Boolean,
    activeWindowDescription: String,
    attempts: Int,
    elapsedMs: Long,
): MarkerBandCaptureVerdict {
    val requiredSpanPx = requiredMarkerSpanPx(marker, fontWidthPx)
    val observation = "magenta span=$paintedSpanPx px, required>=$requiredSpanPx px, " +
        "fontWidth=$fontWidthPx, matchingPixels=$matchingPixels, attempts=$attempts, " +
        "elapsedMs=$elapsedMs"
    if (fontWidthPx > 0f && paintedSpanPx >= requiredSpanPx) {
        return MarkerBandCaptureVerdict(
            outcome = MarkerBandCaptureOutcome.PAINTED,
            message = "device-visible viewport painted the complete '$marker' cell band for " +
                "$artifactName: $observation",
        )
    }
    if (!appWindowFocused) {
        return MarkerBandCaptureVerdict(
            outcome = MarkerBandCaptureOutcome.FOREIGN_WINDOW_OWNS_DISPLAY,
            message = "$FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE Capture '$artifactName' for " +
                "'$marker': $observation; $activeWindowDescription",
        )
    }
    return MarkerBandCaptureVerdict(
        outcome = MarkerBandCaptureOutcome.NOT_PAINTED,
        message = "device-visible viewport did not paint the complete '$marker' cell band for " +
            "$artifactName: $observation. The app window owned the display for the whole " +
            "bounded observation, so the emulator model leading the painted surface is " +
            "excluded — this is a real device-paint failure.",
    )
}

/**
 * Width, in device pixels, the marker's cell band must span. The band is
 * measured between the first and last magenta pixel, so the last cell's own
 * width is not part of the span — hence `length - 1`.
 */
fun requiredMarkerSpanPx(marker: String, fontWidthPx: Float): Int =
    (fontWidthPx * (marker.length - 1).coerceAtLeast(0)).toInt()
