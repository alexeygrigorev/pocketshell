package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #764 — the screen↔source coordinate transform under `ContentScale.Fit`.
 *
 * This is the TOP RISK the design concept flagged: if the mapping is wrong, the
 * annotations land offset in the flattened PNG. The transform is a pure function
 * so it is proven here with no emulator. Cases cover both letterbox axes (wide
 * source in a tall viewport and vice-versa), the center/corner anchors, the
 * round-trip inverse, and clamping into the letterbox bars.
 */
class ImageFitMappingTest {

    /**
     * Wide source (200x100) in a square 400x400 viewport. Fit scale = min(2, 4) =
     * 2, so the image displays 400x200 centered → 100px letterbox bars top+bottom.
     */
    @Test
    fun wideSourceLetterboxedTopBottom() {
        val m = ImageFitMapping.of(sourceWidth = 200, sourceHeight = 100, viewportWidth = 400f, viewportHeight = 400f)

        assertEquals(2f, m.scale, 1e-4f)
        assertEquals(400f, m.displayedWidth, 1e-4f)
        assertEquals(200f, m.displayedHeight, 1e-4f)
        assertEquals(0f, m.offsetX, 1e-4f)
        assertEquals(100f, m.offsetY, 1e-4f)

        // Top-left of the displayed image (screen 0,100) maps to source (0,0).
        val tl = m.screenToSource(0f, 100f)
        assertEquals(0f, tl.x, 1e-3f)
        assertEquals(0f, tl.y, 1e-3f)

        // Bottom-right of the displayed image (screen 400,300) → source (200,100).
        val br = m.screenToSource(400f, 300f)
        assertEquals(200f, br.x, 1e-3f)
        assertEquals(100f, br.y, 1e-3f)

        // Center of the viewport (200,200) → center of the source (100,50).
        val c = m.screenToSource(200f, 200f)
        assertEquals(100f, c.x, 1e-3f)
        assertEquals(50f, c.y, 1e-3f)
    }

    /**
     * Tall source (100x200) in a square 400x400 viewport. Fit scale = min(4, 2) =
     * 2, so the image displays 200x400 centered → 100px bars left+right.
     */
    @Test
    fun tallSourceLetterboxedLeftRight() {
        val m = ImageFitMapping.of(sourceWidth = 100, sourceHeight = 200, viewportWidth = 400f, viewportHeight = 400f)

        assertEquals(2f, m.scale, 1e-4f)
        assertEquals(200f, m.displayedWidth, 1e-4f)
        assertEquals(400f, m.displayedHeight, 1e-4f)
        assertEquals(100f, m.offsetX, 1e-4f)
        assertEquals(0f, m.offsetY, 1e-4f)

        val tl = m.screenToSource(100f, 0f)
        assertEquals(0f, tl.x, 1e-3f)
        assertEquals(0f, tl.y, 1e-3f)

        val br = m.screenToSource(300f, 400f)
        assertEquals(100f, br.x, 1e-3f)
        assertEquals(200f, br.y, 1e-3f)
    }

    /** A screen point round-trips back to itself through source space. */
    @Test
    fun screenToSourceToScreenIsIdentityInsideTheImage() {
        val m = ImageFitMapping.of(sourceWidth = 640, sourceHeight = 480, viewportWidth = 1080f, viewportHeight = 1500f)
        // A point inside the displayed image rect.
        val screenX = 540f
        val screenY = 700f
        val source = m.screenToSource(screenX, screenY)
        val (rx, ry) = m.sourceToScreen(source)
        assertEquals(screenX, rx, 1e-2f)
        assertEquals(screenY, ry, 1e-2f)
    }

    /** Points in the letterbox bars clamp onto the image edges, never off-canvas. */
    @Test
    fun pointsOutsideTheImageClampToTheEdges() {
        val m = ImageFitMapping.of(sourceWidth = 200, sourceHeight = 100, viewportWidth = 400f, viewportHeight = 400f)

        // Above the displayed image (in the top bar) → clamps to y=0.
        val above = m.screenToSource(200f, 0f)
        assertEquals(0f, above.y, 1e-3f)
        assertEquals(100f, above.x, 1e-3f)

        // Below the displayed image (in the bottom bar) → clamps to y=100 (max).
        val below = m.screenToSource(200f, 400f)
        assertEquals(100f, below.y, 1e-3f)

        // Never negative, never beyond the source dims.
        val way = m.screenToSource(-50f, 9999f)
        assertTrue(way.x in 0f..200f)
        assertTrue(way.y in 0f..100f)
    }

    /** Stroke widths convert from screen to source pixels by the inverse scale. */
    @Test
    fun strokeWidthConvertsByInverseScale() {
        val m = ImageFitMapping.of(sourceWidth = 200, sourceHeight = 100, viewportWidth = 400f, viewportHeight = 400f)
        // scale = 2, so an 8px screen stroke is 4 source pixels.
        assertEquals(4f, m.screenToSourceLength(8f), 1e-4f)
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitMapping.of(0, 100, 400f, 400f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitMapping.of(100, 100, 0f, 400f)
        }
    }
}
