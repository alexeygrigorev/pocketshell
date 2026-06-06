package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `wrapped github issue URL emits one full-target decoration region per visual row`() {
        val url =
            "https://github.com/alexeygrigorev/pocketshell/issues/558" +
                "#issuecomment-4638326371"
        val splitAt = url.indexOf("#issuecomment") + 4
        val rows = listOf(
            VisualRow(10, "open ${url.take(splitAt)}", wrapsToNext = true),
            VisualRow(11, url.drop(splitAt), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 80, matcher = DefaultTerminalMatcher())
        val urlRegions = regions.filter { it.match is TerminalMatch.Url }

        assertEquals(2, urlRegions.size)
        assertEquals(listOf(10, 11), urlRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete URL: $urlRegions",
            urlRegions.all { it.match.value == url },
        )
        assertEquals(5, urlRegions[0].startCol)
        assertEquals(rows[0].text.length, urlRegions[0].endColExclusive)
        assertEquals(0, urlRegions[1].startCol)
        assertEquals(rows[1].text.length, urlRegions[1].endColExclusive)
    }

    @Test
    fun `single-line URL still emits one full-target decoration region`() {
        val url = "https://github.com/alexeygrigorev/pocketshell/issues/558"
        val rows = listOf(VisualRow(3, "see $url", wrapsToNext = false))

        val regions = terminalMatchRegionsForRows(rows, columns = 100, matcher = DefaultTerminalMatcher())
        val region = regions.single { it.match is TerminalMatch.Url }

        assertEquals(url, region.match.value)
        assertEquals(3, region.row)
        assertEquals(4, region.startCol)
        assertEquals(4 + url.length, region.endColExclusive)
    }

    @Test
    fun `wrapped tilde path emits one full-target decoration region per visual row`() {
        val path =
            "~/projects/pocketshell/shared/core-terminal/src/main/java/" +
                "com/pocketshell/core/terminal/selection/SelectionScanner.kt"
        val splitAt = path.indexOf("com/pocketshell")
        val rows = listOf(
            VisualRow(20, "file ${path.take(splitAt)}", wrapsToNext = true),
            VisualRow(21, path.drop(splitAt), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 96, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(2, pathRegions.size)
        assertEquals(listOf(20, 21), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete path: $pathRegions",
            pathRegions.all { it.match.value == path },
        )
        assertEquals(5, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
    }
}
