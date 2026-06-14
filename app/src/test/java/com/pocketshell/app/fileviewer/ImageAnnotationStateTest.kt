package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #764 — pure [ImageAnnotationState] ops (toggle / tool / colour / add /
 * undo / note / clear). Same discipline as [ReviewStateTest].
 */
class ImageAnnotationStateTest {

    private fun freehand() = Annotation.Freehand(
        points = listOf(ImagePoint(1f, 1f), ImagePoint(2f, 2f)),
        colorArgb = ImageAnnotationState.DEFAULT_COLOR_ARGB,
        strokeWidthPx = 4f,
    )

    @Test
    fun togglingActivateThenDeactivateResetsToolToPan() {
        val on = ImageAnnotationState().withTool(AnnotationTool.Pen).toggledActive()
        assertTrue(on.active)
        // toggledActive only flips `active`; the tool stays until toggled off.
        assertEquals(AnnotationTool.Pen, on.tool)

        val off = on.toggledActive()
        assertFalse(off.active)
        assertEquals("leaving annotate mode resets the tool to Pan", AnnotationTool.Pan, off.tool)
    }

    @Test
    fun addingAnnotationsGrowsTheListAndUndoPopsTheLast() {
        var s = ImageAnnotationState()
        assertFalse(s.hasAnnotations)

        s = s.withAnnotation(freehand())
        s = s.withAnnotation(freehand())
        assertEquals(2, s.annotations.size)
        assertTrue(s.hasAnnotations)

        s = s.undone()
        assertEquals(1, s.annotations.size)

        s = s.undone()
        assertFalse(s.hasAnnotations)

        // Undo on an empty list is a no-op (no crash, still empty).
        assertEquals(s, s.undone())
    }

    @Test
    fun toolAndColorSelectionAreApplied() {
        val s = ImageAnnotationState()
            .withTool(AnnotationTool.Arrow)
            .withColor(0xFF22C55E.toInt())
        assertEquals(AnnotationTool.Arrow, s.tool)
        assertEquals(0xFF22C55E.toInt(), s.colorArgb)
    }

    @Test
    fun noteIsTrimmedAndBlankClears() {
        var s = ImageAnnotationState().withNote("  circled button misaligned  ")
        assertEquals("circled button misaligned", s.note)

        s = s.withNote("   ")
        assertNull("a blank note clears the field", s.note)
    }

    @Test
    fun clearedKeepsModeToolAndColourButDropsAnnotations() {
        val s = ImageAnnotationState(active = true)
            .withTool(AnnotationTool.Pen)
            .withColor(0xFFF59E0B.toInt())
            .withAnnotation(freehand())
            .withNote("keep nothing")
            .copy(submitting = true)

        val cleared = s.cleared()
        assertFalse("annotations dropped", cleared.hasAnnotations)
        assertNull("note dropped", cleared.note)
        assertFalse("submitting reset", cleared.submitting)
        assertTrue("annotate mode stays active", cleared.active)
        assertEquals("tool kept", AnnotationTool.Pen, cleared.tool)
        assertEquals("colour kept", 0xFFF59E0B.toInt(), cleared.colorArgb)
    }

    @Test
    fun swatchesIncludeTheDefaultColour() {
        assertTrue(ImageAnnotationState.SWATCHES.contains(ImageAnnotationState.DEFAULT_COLOR_ARGB))
    }
}
