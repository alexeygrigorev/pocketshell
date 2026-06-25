package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Markdown block/inline parser (issue #696) without an emulator: the
 * structure the Compose [MarkdownView] renders is decided here.
 */
class MarkdownParserTest {

    @Test
    fun `markdown extensions are detected, others are not`() {
        assertTrue(MarkdownParser.isMarkdownPath("/home/me/README.md"))
        assertTrue(MarkdownParser.isMarkdownPath("notes.markdown"))
        assertTrue(MarkdownParser.isMarkdownPath("a.MD"))
        assertTrue(MarkdownParser.isMarkdownPath("doc.mkd"))
        assertFalse(MarkdownParser.isMarkdownPath("/etc/hosts"))
        assertFalse(MarkdownParser.isMarkdownPath("main.kt"))
        assertFalse(MarkdownParser.isMarkdownPath("config.txt"))
    }

    @Test
    fun `atx headings parse with their level`() {
        val blocks = MarkdownParser.parse("# Title\n## Sub\n###### Deep")
        assertEquals(3, blocks.size)
        val h1 = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Title", (h1.spans.first() as InlineSpan.Text).text)
        assertEquals(2, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(6, (blocks[2] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `fenced code block keeps verbatim content and language`() {
        val src = "Intro\n\n```python\ndef f(x):\n    return x * 2\n```\n\nDone"
        val blocks = MarkdownParser.parse(src)
        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("python", code.language)
        assertEquals("def f(x):\n    return x * 2", code.content)
        // The `*` inside code must NOT be treated as emphasis.
        assertTrue(code.content.contains("x * 2"))
    }

    @Test
    fun `unordered list items parse with nesting`() {
        val blocks = MarkdownParser.parse("- one\n- two\n  - nested")
        val list = blocks.single() as MarkdownBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(3, list.items.size)
        assertEquals(0, list.items[0].indentLevel)
        assertTrue(list.items[2].indentLevel >= 1)
    }

    @Test
    fun `ordered list keeps ordinals`() {
        val blocks = MarkdownParser.parse("1. first\n2. second")
        val list = blocks.single() as MarkdownBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(1, list.items[0].ordinal)
        assertEquals(2, list.items[1].ordinal)
    }

    @Test
    fun `inline bold italic and strikethrough parse`() {
        val spans = MarkdownParser.parseInline("a **bold** and *italic* and ~~gone~~")
        val bold = spans.filterIsInstance<InlineSpan.Text>().first { it.bold }
        assertEquals("bold", bold.text)
        val italic = spans.filterIsInstance<InlineSpan.Text>().first { it.italic && !it.bold }
        assertEquals("italic", italic.text)
        val strike = spans.filterIsInstance<InlineSpan.Text>().first { it.strikethrough }
        assertEquals("gone", strike.text)
    }

    @Test
    fun `inline code is preserved and not emphasised`() {
        val spans = MarkdownParser.parseInline("run `git **status**` now")
        val code = spans.filterIsInstance<InlineSpan.Code>().single()
        assertEquals("git **status**", code.text)
    }

    @Test
    fun `links parse label and url`() {
        val spans = MarkdownParser.parseInline("see [docs](https://example.com/x) here")
        val link = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("docs", link.label)
        assertEquals("https://example.com/x", link.url)
    }

    @Test
    fun `thematic break and blockquote parse`() {
        val blocks = MarkdownParser.parse("> quoted line\n\n---\n\npara")
        assertTrue(blocks.any { it is MarkdownBlock.BlockQuote })
        assertTrue(blocks.any { it === MarkdownBlock.HorizontalRule })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `plain paragraph joins soft-wrapped lines`() {
        val blocks = MarkdownParser.parse("line one\nline two")
        val para = blocks.single() as MarkdownBlock.Paragraph
        assertEquals("line one line two", (para.spans.single() as InlineSpan.Text).text)
    }

    /**
     * Regression for the backreference-regex StackOverflow (issue #696 review):
     * a long divider line of repeated `-`/`*`/`_` must NOT overflow the stack.
     * The old `^\s{0,3}([-*_])(?:\s*\1){2,}\s*$` recursed per char and crashed
     * the viewer at ~2000+ chars; the plain-scan detector handles any length.
     */
    @Test
    fun `very long thematic-break line does not crash the parser`() {
        for (marker in listOf('-', '*', '_')) {
            val line = marker.toString().repeat(5000)
            // Must not throw StackOverflowError; renders as a horizontal rule.
            val blocks = MarkdownParser.parse(line)
            assertTrue(
                "a $line.length-char run of '$marker' should parse to an HR",
                blocks.any { it === MarkdownBlock.HorizontalRule },
            )
        }
    }

    @Test
    fun `mixed long markers are not a thematic break and do not crash`() {
        // Not all the same char: not an HR, must not crash either.
        val line = "-*".repeat(3000)
        val blocks = MarkdownParser.parse(line)
        assertFalse(blocks.any { it === MarkdownBlock.HorizontalRule })
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun `short thematic break still parses with leading spaces`() {
        assertTrue(MarkdownParser.isThematicBreak("---"))
        assertTrue(MarkdownParser.isThematicBreak("   ***"))
        assertTrue(MarkdownParser.isThematicBreak("- - -"))
        assertFalse(MarkdownParser.isThematicBreak("--")) // only two markers
        assertFalse(MarkdownParser.isThematicBreak("    ---")) // 4 leading spaces
        assertFalse(MarkdownParser.isThematicBreak("-x-")) // mixed
    }

    /**
     * Regression: a link whose URL legitimately contains balanced parens (a
     * Wikipedia disambiguation URL) must keep its full URL, not truncate at the
     * first `)` and leak the tail into the visible text (issue #696 review).
     */
    @Test
    fun `link with parens in url keeps the full url`() {
        val spans = MarkdownParser.parseInline(
            "see [wiki](https://en.wikipedia.org/wiki/Foo_(bar)) here",
        )
        val link = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("wiki", link.label)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", link.url)
        // The trailing `)` must NOT leak into the plain text after the link.
        val plain = spans.filterIsInstance<InlineSpan.Text>().joinToString("") { it.text }
        assertFalse("URL tail must not leak as text", plain.trim().startsWith(")"))
        assertTrue(plain.contains("here"))
    }

    // ---- Issue #921: GFM pipe tables ----

    /**
     * Reproduce-first (D33/G10): a GitHub-flavored pipe table — header row +
     * `|---|---|` delimiter + body rows — must parse to a [MarkdownBlock.Table],
     * NOT a [MarkdownBlock.Paragraph] of raw pipe text. Before the fix the
     * parser had no table branch, so this same source parsed as a single
     * paragraph whose text still contained the literal `|` and `---` delimiter —
     * this assertion was red on base.
     */
    @Test
    fun `gfm pipe table parses as a table block, not a raw paragraph`() {
        val src = """
            | Name | Score |
            |------|-------|
            | foo  | 12    |
            | bar  | 34    |
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        // The bug: parsed as a paragraph carrying the raw `|---|` delimiter text.
        assertFalse(
            "table source must NOT parse as a raw paragraph",
            blocks.any {
                it is MarkdownBlock.Paragraph &&
                    (it.spans.filterIsInstance<InlineSpan.Text>().any { s -> s.text.contains("---") } ||
                        it.spans.filterIsInstance<InlineSpan.Text>().any { s -> s.text.contains("|") })
            },
        )
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        // Header has two columns.
        assertEquals(2, table.header.size)
        assertEquals("Name", (table.header[0].single() as InlineSpan.Text).text)
        assertEquals("Score", (table.header[1].single() as InlineSpan.Text).text)
        // Two body rows, each with two cells.
        assertEquals(2, table.rows.size)
        assertEquals("foo", (table.rows[0][0].single() as InlineSpan.Text).text)
        assertEquals("12", (table.rows[0][1].single() as InlineSpan.Text).text)
        assertEquals("bar", (table.rows[1][0].single() as InlineSpan.Text).text)
        assertEquals("34", (table.rows[1][1].single() as InlineSpan.Text).text)
    }

    @Test
    fun `table delimiter row is detected with and without outer pipes`() {
        assertTrue(MarkdownParser.isTableDelimiterRow("|---|---|"))
        assertTrue(MarkdownParser.isTableDelimiterRow("---|---"))
        assertTrue(MarkdownParser.isTableDelimiterRow("| :--- | ---: | :--: |"))
        assertTrue(MarkdownParser.isTableDelimiterRow("|-|"))
        // Not a delimiter row: a header / data row of words.
        assertFalse(MarkdownParser.isTableDelimiterRow("| Name | Score |"))
        // Not a delimiter row: no dashes at all.
        assertFalse(MarkdownParser.isTableDelimiterRow("| : | : |"))
        // Plain text with no pipe is not a delimiter row.
        assertFalse(MarkdownParser.isTableDelimiterRow("just text"))
    }

    @Test
    fun `delimiter colons set per-column alignment`() {
        val src = """
            | L | C | R | N |
            | :--- | :--: | ---: | --- |
            | a | b | c | d |
        """.trimIndent()
        val table = MarkdownParser.parse(src).filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(
            listOf(
                MarkdownBlock.Table.Alignment.LEFT,
                MarkdownBlock.Table.Alignment.CENTER,
                MarkdownBlock.Table.Alignment.RIGHT,
                MarkdownBlock.Table.Alignment.NONE,
            ),
            table.alignments,
        )
    }

    @Test
    fun `inline markup inside table cells is parsed`() {
        val src = """
            | Field | Value |
            |-------|-------|
            | **bold** | `code` |
        """.trimIndent()
        val table = MarkdownParser.parse(src).filterIsInstance<MarkdownBlock.Table>().single()
        val boldCell = table.rows[0][0]
        assertTrue(boldCell.filterIsInstance<InlineSpan.Text>().any { it.bold && it.text == "bold" })
        val codeCell = table.rows[0][1]
        assertEquals("code", codeCell.filterIsInstance<InlineSpan.Code>().single().text)
    }

    @Test
    fun `a pipe line without a delimiter row stays a paragraph`() {
        // A lone "| a | b |" with no `|---|` row underneath is NOT a table.
        val blocks = MarkdownParser.parse("| a | b |\nnext line")
        assertTrue(blocks.all { it !is MarkdownBlock.Table })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `table ends at a blank line and following content parses separately`() {
        val src = """
            | A | B |
            |---|---|
            | 1 | 2 |

            After the table.
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(1, table.rows.size)
        val para = blocks.filterIsInstance<MarkdownBlock.Paragraph>().single()
        assertEquals("After the table.", (para.spans.single() as InlineSpan.Text).text)
    }

    @Test
    fun `escaped pipe inside a cell is literal, not a column separator`() {
        val src = """
            | Expr | Note |
            |------|------|
            | a \| b | or |
        """.trimIndent()
        val table = MarkdownParser.parse(src).filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(2, table.rows[0].size)
        assertEquals("a | b", (table.rows[0][0].single() as InlineSpan.Text).text)
    }

    /**
     * Regression (issue #921 review): a `|`-containing PROSE line immediately
     * followed by a `---` thematic break must NOT be misparsed as a table. A
     * single `---` is a valid 1-cell delimiter row, so the bare delimiter check
     * swallowed the horizontal rule. The GFM guard is that the delimiter row's
     * cell count must EQUAL the header row's cell count — `"foo | bar"` has 2
     * columns, `"---"` has 1, so it is not a table.
     */
    @Test
    fun `pipe prose line above a thematic break is a paragraph plus rule, not a table`() {
        val blocks = MarkdownParser.parse("foo | bar\n---\nnext para")
        assertTrue("must not be a table", blocks.none { it is MarkdownBlock.Table })
        assertEquals(
            listOf("Paragraph", "HorizontalRule", "Paragraph"),
            blocks.map { it::class.simpleName },
        )
        assertEquals(
            "foo | bar",
            ((blocks[0] as MarkdownBlock.Paragraph).spans.single() as InlineSpan.Text).text,
        )
    }

    @Test
    fun `pipe heading line above a closing thematic break is not a table`() {
        val blocks = MarkdownParser.parse("Heading text | more\n---")
        assertTrue("must not be a table", blocks.none { it is MarkdownBlock.Table })
        assertEquals(
            listOf("Paragraph", "HorizontalRule"),
            blocks.map { it::class.simpleName },
        )
    }

    /**
     * The column-count guard must also reject a delimiter row whose cell count
     * differs from the header for any other reason (a genuinely malformed table
     * stays prose rather than rendering a lopsided grid).
     */
    @Test
    fun `delimiter row with a different column count than the header is not a table`() {
        // Header has 2 columns, delimiter has 3 — not a table.
        val blocks = MarkdownParser.parse("| a | b |\n|---|---|---|\n| 1 | 2 |")
        assertTrue("mismatched column count must not parse as a table", blocks.none { it is MarkdownBlock.Table })
    }
}
