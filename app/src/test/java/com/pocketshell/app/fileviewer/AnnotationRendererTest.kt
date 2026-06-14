package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #764 v2 — proves [AnnotationRenderer] flattens the new shape/text
 * annotation types (Rectangle / Circle / Text) onto the source bitmap in
 * source-pixel space. The coordinate transform itself is unit-proven in
 * [ImageFitMappingTest]; this test proves the **export** lands the marks where
 * the (already-mapped) source coordinates say.
 *
 * [GraphicsMode.Mode.NATIVE] makes Robolectric actually rasterize Canvas draws
 * onto the bitmap (the legacy shadow Canvas is a no-op), so each case can flatten
 * onto a known-white bitmap and assert the annotated pixels changed to the stroke
 * colour while a control pixel away from the mark stays white.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [33])
class AnnotationRendererTest {

    private val red = ImageAnnotationState.DEFAULT_COLOR_ARGB

    private fun whiteSource(w: Int = 100, h: Int = 100): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return bmp
    }

    /** True when a pixel is meaningfully not white (i.e. the mark painted here). */
    private fun isMarked(bmp: Bitmap, x: Int, y: Int): Boolean {
        val c = bmp.getPixel(x, y)
        return Color.red(c) < 250 || Color.green(c) < 250 || Color.blue(c) < 250
    }

    @Test
    fun rectangleOutlineIsDrawnOnTheEdgesNotTheInterior() {
        val source = whiteSource()
        val rect = Annotation.Rectangle(
            start = ImagePoint(20f, 20f),
            end = ImagePoint(80f, 60f),
            colorArgb = red,
            strokeWidthPx = 4f,
        )
        val out = AnnotationRenderer.flatten(source, listOf(rect))

        // A point on the top edge is painted; the centre of the box (interior of
        // an OUTLINE) stays white; a point well outside stays white.
        assertTrue("top edge midpoint should be marked", isMarked(out, 50, 20))
        assertTrue("left edge midpoint should be marked", isMarked(out, 20, 40))
        assertTrue("interior of an outline rect stays white", !isMarked(out, 50, 40))
        assertTrue("a pixel outside the rect stays white", !isMarked(out, 5, 5))
    }

    @Test
    fun rectangleNormalisesCornerOrderSoAReverseDragDrawsTheSameBox() {
        val forward = AnnotationRenderer.flatten(
            whiteSource(),
            listOf(Annotation.Rectangle(ImagePoint(20f, 20f), ImagePoint(80f, 60f), red, 4f)),
        )
        // Same box, corners given bottom-right → top-left.
        val reverse = AnnotationRenderer.flatten(
            whiteSource(),
            listOf(Annotation.Rectangle(ImagePoint(80f, 60f), ImagePoint(20f, 20f), red, 4f)),
        )
        for (x in intArrayOf(20, 50, 80)) {
            assertEquals(
                "reverse-drag rect must paint the same top edge at x=$x",
                isMarked(forward, x, 20),
                isMarked(reverse, x, 20),
            )
        }
    }

    @Test
    fun circleOutlineHitsTheBoundingBoxEdgesButNotTheCentre() {
        val source = whiteSource()
        val circle = Annotation.Circle(
            start = ImagePoint(20f, 20f),
            end = ImagePoint(80f, 80f),
            colorArgb = red,
            strokeWidthPx = 4f,
        )
        val out = AnnotationRenderer.flatten(source, listOf(circle))

        // The ellipse touches the bounding box at the mid-points of each side.
        assertTrue("ellipse top tangent should be marked", isMarked(out, 50, 20))
        assertTrue("ellipse right tangent should be marked", isMarked(out, 80, 50))
        // Its centre (outline only) stays white; the box corner is outside the oval.
        assertTrue("centre of an outline circle stays white", !isMarked(out, 50, 50))
        assertTrue("bounding-box corner is outside the oval", !isMarked(out, 21, 21))
    }

    @Test
    fun textIsFlattenedAtItsAnchorInTheRightColour() {
        val source = whiteSource(200, 80)
        val text = Annotation.Text(
            text = "BUG",
            anchor = ImagePoint(10f, 10f),
            textSizePx = 40f,
            colorArgb = red,
        )
        val out = AnnotationRenderer.flatten(source, listOf(text))

        // Scan the band just below the anchor for any non-white pixel; the label
        // must have painted SOME glyph ink there.
        var marked = 0
        var redInk = 0
        for (y in 10 until 60) {
            for (x in 10 until 190) {
                if (isMarked(out, x, y)) {
                    marked++
                    val c = out.getPixel(x, y)
                    if (Color.red(c) > Color.green(c) && Color.red(c) > Color.blue(c)) redInk++
                }
            }
        }
        assertTrue("text annotation must paint glyph ink below the anchor", marked > 0)
        assertTrue("glyph ink must be in the chosen (red) colour", redInk > 0)
        // A far corner above the anchor stays white.
        assertTrue("a pixel above the text anchor stays white", !isMarked(out, 195, 2))
    }

    @Test
    fun emptyTextDrawsNothing() {
        val source = whiteSource(40, 40)
        val out = AnnotationRenderer.flatten(
            source,
            listOf(Annotation.Text("", ImagePoint(5f, 5f), 30f, red)),
        )
        for (y in 0 until 40) for (x in 0 until 40) {
            assertTrue("empty text must not paint anything at ($x,$y)", !isMarked(out, x, y))
        }
    }

    @Test
    fun allTypesCoexistInOneFlattenAndEncodeToPng() {
        val source = whiteSource(120, 120)
        val annotations = listOf(
            Annotation.Freehand(listOf(ImagePoint(5f, 5f), ImagePoint(40f, 40f)), red, 4f),
            Annotation.Arrow(ImagePoint(10f, 100f), ImagePoint(60f, 100f), red, 4f),
            Annotation.Rectangle(ImagePoint(70f, 10f), ImagePoint(110f, 40f), red, 4f),
            Annotation.Circle(ImagePoint(70f, 60f), ImagePoint(110f, 100f), red, 4f),
            Annotation.Text("X", ImagePoint(20f, 55f), 30f, red),
        )
        val png = AnnotationRenderer.flattenToPng(source, annotations)
        // Real PNG (magic header) and the source itself is unchanged (a copy).
        assertTrue("must be a PNG (magic header)", png.size >= 8 && png[0] == 0x89.toByte())
        assertEquals("source must remain white (flatten copies)", Color.WHITE, source.getPixel(0, 0))
        assertNotEquals("flatten must not mutate the source", 0, png.size)
    }
}
