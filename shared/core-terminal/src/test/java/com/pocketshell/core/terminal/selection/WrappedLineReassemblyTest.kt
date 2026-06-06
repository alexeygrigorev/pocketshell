package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for soft-wrap reassembly + span mapping used to detect a URL/path
 * wrapped across terminal rows as one logical target (issue #558 bug 2).
 */
class WrappedLineReassemblyTest {

    @Test
    fun `non-wrapping rows become one logical line each`() {
        val rows = listOf(
            VisualRow(0, "first", wrapsToNext = false),
            VisualRow(1, "second", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(2, logical.size)
        assertEquals("first", logical[0].text)
        assertEquals("second", logical[1].text)
    }

    @Test
    fun `wrapped rows join into one logical line`() {
        val rows = listOf(
            VisualRow(0, "https://github.com/owner/", wrapsToNext = true),
            VisualRow(1, "very/long/path", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(1, logical.size)
        assertEquals("https://github.com/owner/very/long/path", logical[0].text)
    }

    @Test
    fun `three-row wrap joins fully`() {
        val rows = listOf(
            VisualRow(0, "aaaa", wrapsToNext = true),
            VisualRow(1, "bbbb", wrapsToNext = true),
            VisualRow(2, "cccc", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(1, logical.size)
        assertEquals("aaaabbbbcccc", logical[0].text)
    }

    @Test
    fun `single-row span maps to one row segment`() {
        val logical = LogicalLine(listOf(VisualRow(5, "see /etc/hosts now", wrapsToNext = false)))
        // span covering "/etc/hosts" = indices 4..14
        val spans = logical.mapSpanToRows(4, 14)
        assertEquals(listOf(RowSpan(row = 5, startCol = 4, endColExclusive = 14)), spans)
    }

    @Test
    fun `wrapped span maps to one segment per visual row`() {
        val rows = listOf(
            VisualRow(0, "https://github.com/owner/", wrapsToNext = true),
            VisualRow(1, "very/long/path", wrapsToNext = false),
        )
        val logical = LogicalLine(rows)
        // The whole URL spans the entire logical line: 0..39.
        val spans = logical.mapSpanToRows(0, logical.text.length)
        assertEquals(
            listOf(
                RowSpan(row = 0, startCol = 0, endColExclusive = 25),
                RowSpan(row = 1, startCol = 0, endColExclusive = 14),
            ),
            spans,
        )
    }

    @Test
    fun `span starting mid-first-row across the wrap maps both rows`() {
        val rows = listOf(
            VisualRow(0, "open https://a.com/b/", wrapsToNext = true),
            VisualRow(1, "c/d", wrapsToNext = false),
        )
        val logical = LogicalLine(rows)
        // URL begins at index 5 and runs to end (length 24).
        val spans = logical.mapSpanToRows(5, logical.text.length)
        assertEquals(
            listOf(
                RowSpan(row = 0, startCol = 5, endColExclusive = 21),
                RowSpan(row = 1, startCol = 0, endColExclusive = 3),
            ),
            spans,
        )
    }
}
