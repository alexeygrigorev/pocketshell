package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [ReviewState] edit/delete/clear ops (issue #714).
 * These are the model behind the per-line + whole-file comment UI; keeping them
 * pure means the comment lifecycle is verifiable without Compose or SSH.
 */
class ReviewStateTest {

    @Test
    fun `default state is empty and inactive`() {
        val s = ReviewState()
        assertFalse(s.active)
        assertFalse(s.hasPending)
        assertEquals(0, s.pendingCount)
        assertNull(s.fileComment)
        assertTrue(s.lineComments.isEmpty())
    }

    @Test
    fun `set line comment adds and pending count tracks it`() {
        val s = ReviewState().withLineComment(42, "hoist this")
        assertTrue(s.hasLineComment(42))
        assertEquals("hoist this", s.lineComments[42])
        assertEquals(1, s.pendingCount)
        assertTrue(s.hasPending)
    }

    @Test
    fun `set line comment trims whitespace`() {
        val s = ReviewState().withLineComment(1, "  spaced  ")
        assertEquals("spaced", s.lineComments[1])
    }

    @Test
    fun `set line comment overwrites the existing one`() {
        val s = ReviewState()
            .withLineComment(7, "first")
            .withLineComment(7, "second")
        assertEquals("second", s.lineComments[7])
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `setting a blank line comment clears it`() {
        val s = ReviewState()
            .withLineComment(7, "first")
            .withLineComment(7, "   ")
        assertFalse(s.hasLineComment(7))
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `delete line comment removes only that line`() {
        val s = ReviewState()
            .withLineComment(1, "a")
            .withLineComment(2, "b")
            .withoutLineComment(1)
        assertFalse(s.hasLineComment(1))
        assertTrue(s.hasLineComment(2))
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `delete missing line comment is a no-op`() {
        val s = ReviewState().withLineComment(1, "a")
        val after = s.withoutLineComment(99)
        assertEquals(s, after)
    }

    @Test
    fun `file comment set and counted`() {
        val s = ReviewState().withFileComment("overall good")
        assertEquals("overall good", s.fileComment)
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `blank file comment clears to null`() {
        val s = ReviewState().withFileComment("note").withFileComment("  ")
        assertNull(s.fileComment)
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `delete file comment nulls it`() {
        val s = ReviewState().withFileComment("note").withoutFileComment()
        assertNull(s.fileComment)
    }

    @Test
    fun `pending count combines line and file comments`() {
        val s = ReviewState()
            .withLineComment(1, "a")
            .withLineComment(2, "b")
            .withFileComment("file")
        assertEquals(3, s.pendingCount)
    }

    @Test
    fun `cleared wipes comments but keeps active flag`() {
        val s = ReviewState(active = true)
            .withLineComment(1, "a")
            .withFileComment("file")
            .copy(submitting = true)
            .cleared()
        assertTrue(s.active)
        assertFalse(s.hasPending)
        assertNull(s.fileComment)
        assertTrue(s.lineComments.isEmpty())
        assertFalse(s.submitting)
    }
}
