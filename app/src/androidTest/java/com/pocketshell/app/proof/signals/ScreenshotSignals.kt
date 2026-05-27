package com.pocketshell.app.proof.signals

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Default minimum number of distinct ARGB values required for a
 * screenshot to be considered "not blank". Three covers the common
 * almost-uniform false-positives:
 *
 *  - A solid black bitmap reports 1 colour.
 *  - A header-only frame (black background + one foreground tint)
 *    reports 2 colours.
 *  - Any real UI sample with text, icons, or a status bar reports
 *    dozens to thousands of distinct ARGB values, so 3 is a generous
 *    safety margin.
 */
internal const val DEFAULT_MIN_DISTINCT_COLORS: Int = 3

/**
 * Downsample side used when counting distinct ARGB values. 64x64 = 4096
 * pixels — large enough to keep a real screenshot well above the
 * default threshold, small enough that decode + iterate is well under
 * 50 ms on a CI emulator.
 */
internal const val DEFAULT_DOWNSAMPLE_SIDE: Int = 64

/**
 * Asserts that the PNG at [file] contains at least [minDistinctColors]
 * distinct ARGB values when downsampled to a [DEFAULT_DOWNSAMPLE_SIDE]
 * square. Throws [AssertionError] otherwise.
 *
 * Why this exists:
 *
 * Connected tests upload screenshots as evidence of "the user saw
 * something useful" — but the capture path has several known
 * failure modes that produce a perfectly valid PNG file that contains
 * only black (or only the status-bar tint, or only one chrome colour):
 *
 *  - `instrumentation.uiAutomation.takeScreenshot()` taken before the
 *    first frame after activity launch.
 *  - Capture taken while the device is in the AOD/dim state.
 *  - SurfaceView / TextureView contents on the wrong window-layer
 *    falling back to the background colour.
 *  - A capture taken from a destroyed scenario where the decor view
 *    has already been removed.
 *
 * Counting distinct ARGB values is a cheap, decode-once gate that
 * catches all of these without trying to assert on specific content.
 * It does not (and should not) verify the screenshot shows the
 * "correct" UI — the dogfood / visual-audit tests already do that
 * with text and bounds assertions. The function's only job is to
 * reject obviously-blank artifacts before they're treated as release
 * evidence.
 *
 * Expected upper bound on CI: <100 ms per call (PNG decode + 4096
 * pixel hashmap insert + assert). The dogfood / release artifact
 * passes call it once per captured screenshot.
 *
 * @param file PNG file to inspect. Must exist and be a decodable PNG.
 * @param minDistinctColors lower bound on distinct ARGB pixel values.
 *   Defaults to [DEFAULT_MIN_DISTINCT_COLORS]. Pass a higher value
 *   when the screenshot is supposed to show a colourful UI.
 * @throws AssertionError if the file does not exist, cannot be
 *   decoded, or contains fewer than [minDistinctColors] distinct ARGB
 *   values in the downsampled grid.
 */
fun assertScreenshotNotBlank(
    file: File,
    minDistinctColors: Int = DEFAULT_MIN_DISTINCT_COLORS,
) {
    if (!file.exists()) {
        throw AssertionError("screenshot file does not exist: ${file.absolutePath}")
    }
    val raw: Bitmap = BitmapFactory.decodeFile(file.absolutePath)
        ?: throw AssertionError(
            "screenshot at ${file.absolutePath} could not be decoded as a bitmap " +
                "(size=${file.length()} bytes)",
        )
    val distinct = try {
        countDistinctArgbInDownsample(raw, side = DEFAULT_DOWNSAMPLE_SIDE)
    } finally {
        raw.recycle()
    }
    if (distinct < minDistinctColors) {
        throw AssertionError(
            "screenshot at ${file.absolutePath} looks blank: " +
                "distinctColors=$distinct, minRequired=$minDistinctColors " +
                "(downsample=${DEFAULT_DOWNSAMPLE_SIDE}x${DEFAULT_DOWNSAMPLE_SIDE})",
        )
    }
}

/**
 * Scales [source] to a [side]x[side] bitmap and returns the number of
 * distinct ARGB pixel values in it. Both bitmaps are recycled before
 * returning (the source remains the caller's responsibility — only the
 * intermediate downsample is recycled here).
 */
private fun countDistinctArgbInDownsample(source: Bitmap, side: Int): Int {
    val scaled = Bitmap.createScaledBitmap(source, side, side, /* filter = */ true)
    return try {
        val pixels = IntArray(side * side)
        scaled.getPixels(pixels, 0, side, 0, 0, side, side)
        val seen = HashSet<Int>(pixels.size)
        for (px in pixels) seen.add(px)
        seen.size
    } finally {
        if (scaled !== source) scaled.recycle()
    }
}
