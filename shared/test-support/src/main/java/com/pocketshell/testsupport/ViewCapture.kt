package com.pocketshell.testsupport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View

/**
 * Draws [view] into a software bitmap. Hard-fails if [view] is missing or
 * has collapsed to 0x0 — a missing artifact is a test failure, never a
 * silent skip (#2206 / #2135 / #822).
 *
 * Seven `:shared:core-terminal` instrumented tests used to do:
 *
 * ```
 * if (view.width > 0 && view.height > 0) { view.draw(...) }
 * bitmap?.let { write png }
 * ```
 *
 * That is the same hole #2135 closed in the app module. This sibling
 * lives in `:shared:test-support` so `:shared:core-terminal` can import
 * it without depending on the app helper (queued on `origin/issue-2135`).
 *
 * The diagnostic names the measured size, on-screen rect, and whether a
 * drawn frame could be inspected for uniform-blank pixels, so a reviewer
 * can tell a destroyed [android.view.View] from a merely-empty pane.
 *
 * Callers still own idle-wait, file I/O, and sidecar text. This function's
 * only job is: produce the bitmap or throw.
 */
fun captureViewToBitmap(view: View?, label: String): Bitmap {
    if (view == null || view.width <= 0 || view.height <= 0) {
        throw AssertionError(
            "the authoritative viewport artifact '$label' could not be captured: " +
                "${describeViewCaptureState(view)}. A missing terminal viewport " +
                "capture is a hard failure, never a silent skip (#2206).",
        )
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
}

/**
 * One-line view-state diagnostic used by [captureViewToBitmap] and by
 * tests that assert the failure names the right fields.
 *
 * A 0x0 view cannot be drawn, so [drawnFrameIsUniformBlank] is reported
 * as `unmeasurable` rather than guessed.
 */
fun describeViewCaptureState(view: View?): String {
    if (view == null) {
        return "viewFound=false measured=n/a onScreenRect=n/a drawnFrameIsUniformBlank=n/a"
    }
    val onScreen = Rect()
    val hasVisibleRect = view.getGlobalVisibleRect(onScreen)
    val visibleWidth = if (hasVisibleRect) onScreen.width() else 0
    val visibleHeight = if (hasVisibleRect) onScreen.height() else 0
    val uniform = if (view.width > 0 && view.height > 0) {
        val probe = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(probe))
            bitmapIsUniform(probe).toString()
        } finally {
            probe.recycle()
        }
    } else {
        "unmeasurable"
    }
    return "viewFound=true attachedAndShown=${view.isShown} " +
        "measured=${view.width}x${view.height} " +
        "onScreenRect=${visibleWidth}x$visibleHeight " +
        "drawnFrameIsUniformBlank=$uniform"
}

private fun bitmapIsUniform(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return true
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val first = pixels[0]
    for (pixel in pixels) {
        if (pixel != first) return false
    }
    return true
}
