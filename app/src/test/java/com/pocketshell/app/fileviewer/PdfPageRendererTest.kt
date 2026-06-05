package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over [PdfPageRenderer.scaledDimensions] — the page-bitmap
 * sizing that bounds memory for the in-app PDF viewer (issue #498). No Android
 * graphics or PdfRenderer needed; this is integer math.
 */
class PdfPageRendererTest {

    @Test
    fun `small page is not upscaled`() {
        val (w, h) = PdfPageRenderer.scaledDimensions(600, 800, maxEdgePx = 2000)
        assertEquals(600, w)
        assertEquals(800, h)
    }

    @Test
    fun `oversized portrait page is scaled so longest edge fits`() {
        val (w, h) = PdfPageRenderer.scaledDimensions(3000, 4000, maxEdgePx = 2000)
        // longest edge (height) clamped to 2000, aspect ratio preserved.
        assertEquals(2000, h)
        assertEquals(1500, w)
    }

    @Test
    fun `oversized landscape page is scaled so longest edge fits`() {
        val (w, h) = PdfPageRenderer.scaledDimensions(4000, 1000, maxEdgePx = 2000)
        assertEquals(2000, w)
        assertEquals(500, h)
    }

    @Test
    fun `never returns a zero dimension for a sliver page`() {
        val (w, h) = PdfPageRenderer.scaledDimensions(4000, 1, maxEdgePx = 2000)
        assertEquals(2000, w)
        assertTrue("height must be at least 1, was $h", h >= 1)
    }

    @Test
    fun `degenerate zero input is coerced to at least one pixel`() {
        val (w, h) = PdfPageRenderer.scaledDimensions(0, 0, maxEdgePx = 2000)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }
}
