package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper over [android.graphics.pdf.PdfRenderer] (API 21+, built into
 * the platform — no third-party PDF dependency) for the in-app PDF viewer
 * (issue #498).
 *
 * Opens a cached PDF file and renders one page at a time to a [Bitmap]. All
 * calls are blocking and must run on a background dispatcher — PdfRenderer is
 * single-threaded and not main-thread-friendly, and bitmap allocation for a
 * full-resolution page is heavy.
 *
 * [PdfRenderer] only allows one page open at a time; this class opens, renders,
 * and closes each page within a single [renderPage] call so callers never have
 * to manage page lifecycles.
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    /** Number of pages in the document (always >= 1 for a valid open). */
    val pageCount: Int get() = renderer.pageCount

    /**
     * Render page [index] (0-based) to an ARGB_8888 bitmap. The bitmap is
     * sized so its longest edge is at most [maxEdgePx], preserving the page
     * aspect ratio, which keeps memory bounded for large pages while staying
     * crisp enough to read and zoom.
     */
    fun renderPage(index: Int, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): Bitmap {
        require(index in 0 until pageCount) {
            "page $index out of bounds (0 until $pageCount)"
        }
        renderer.openPage(index).use { page ->
            val (width, height) = scaledDimensions(page.width, page.height, maxEdgePx)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PdfRenderer ignores transparent regions, so paint white first or
            // a page with no opaque background renders as black on the canvas.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Longest-edge cap for a rendered page bitmap. ~2000px keeps a single
         * page well under ~16 MB (2000 * ~1500 * 4 bytes) so paging through a
         * document doesn't accumulate toward the OOM cliff, while leaving
         * enough resolution to pinch-zoom into fine print.
         */
        const val DEFAULT_MAX_EDGE_PX = 2000

        /**
         * Open [file] for rendering. Throws if the file isn't a valid PDF (the
         * caller — the view model / panel — turns that into a "can't preview"
         * message). Visible-for-test sizing logic is in [scaledDimensions].
         */
        fun open(file: File): PdfPageRenderer {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfPageRenderer(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        /**
         * Scale (pageWidth, pageHeight) so the longest edge is at most
         * [maxEdgePx], preserving aspect ratio. Never upscales beyond the
         * source page and never returns a zero dimension. Pure — unit-tested.
         */
        internal fun scaledDimensions(
            pageWidth: Int,
            pageHeight: Int,
            maxEdgePx: Int,
        ): Pair<Int, Int> {
            val w = pageWidth.coerceAtLeast(1)
            val h = pageHeight.coerceAtLeast(1)
            val longest = maxOf(w, h)
            if (longest <= maxEdgePx) return w to h
            val scale = maxEdgePx.toFloat() / longest
            val scaledW = (w * scale).toInt().coerceAtLeast(1)
            val scaledH = (h * scale).toInt().coerceAtLeast(1)
            return scaledW to scaledH
        }
    }
}
