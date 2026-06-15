package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun plainTextStaysAsSingleParagraphBlock() {
        val blocks = parseMarkdownBlocks("Hello world.")

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("Hello world.", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun fencedCodeBlockSurvivesBackticksWithLanguageTag() {
        val blocks = parseMarkdownBlocks(
            """
            Intro
            ```kotlin
            val x = 1
            val y = 2
            ```
            Outro
            """.trimIndent(),
        )

        val codeBlocks = blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size)
        assertEquals("val x = 1\nval y = 2", codeBlocks[0].text)
    }

    @Test
    fun headingsKeepTheirLevel() {
        val blocks = parseMarkdownBlocks("## Heading two\nbody line")

        assertEquals(MarkdownBlock.Heading(2, "Heading two"), blocks[0])
    }

    @Test
    fun bulletListsParseAsBulletBlocks() {
        val blocks = parseMarkdownBlocks("- alpha\n- beta\n* gamma")

        val bullets = blocks.filterIsInstance<MarkdownBlock.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals("alpha", bullets[0].text)
        assertEquals("•", bullets[2].marker)
    }

    @Test
    fun orderedListsKeepNumbering() {
        val blocks = parseMarkdownBlocks("1. first\n2. second")
        val bullets = blocks.filterIsInstance<MarkdownBlock.Bullet>()

        assertEquals("1.", bullets[0].marker)
        assertEquals("first", bullets[0].text)
        assertEquals("2.", bullets[1].marker)
    }

    @Test
    fun inlineFormattingProducesAnnotatedSpans() {
        val rendered = renderInline("**bold** and *italic* and `code`")

        // Plain text content survives — asterisks/backticks should be
        // stripped from the visible characters.
        assertEquals("bold and italic and code", rendered.text)
    }

    @Test
    fun plainTextWithStrayAsterisksIsNotGarbled() {
        // A lone unmatched `*` should not start italic and eat the rest
        // of the line. The renderer falls back to literal characters.
        val rendered = renderInline("hello * world")
        assertEquals("hello * world", rendered.text)
    }

    @Test
    fun linksRenderTheirLabelText() {
        val rendered = renderInline("see [docs](https://example.com) please")

        assertEquals("see docs please", rendered.text)
    }

    // --- GFM tables (#781) ---

    @Test
    fun pipeTableParsesHeaderDelimiterAndRows() {
        val blocks = parseMarkdownBlocks(
            """
            | # | Issue | Labels |
            |---|-------|--------|
            | 1 | Tables | bug, ui |
            | 2 | Voice  | feature |
            """.trimIndent(),
        )

        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("#", "Issue", "Labels"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "Tables", "bug, ui"), table.rows[0])
        assertEquals(listOf("2", "Voice", "feature"), table.rows[1])
    }

    @Test
    fun tableWithoutOuterPipesStillParses() {
        val blocks = parseMarkdownBlocks(
            """
            a | b | c
            --- | --- | ---
            1 | 2 | 3
            """.trimIndent(),
        )

        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("a", "b", "c"), table.header)
        assertEquals(listOf("1", "2", "3"), table.rows.single())
    }

    @Test
    fun alignmentMarkersAreHonored() {
        val aligns = parseTableAlignments("| :--- | :---: | ---: | --- |")

        assertEquals(
            listOf(
                ColumnAlignment.Start,
                ColumnAlignment.Center,
                ColumnAlignment.End,
                ColumnAlignment.Start,
            ),
            aligns,
        )
    }

    @Test
    fun alignmentsAreCarriedOnTheParsedTableBlock() {
        val blocks = parseMarkdownBlocks(
            """
            | left | mid | right |
            | :--- | :---: | ---: |
            | a | b | c |
            """.trimIndent(),
        )

        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(
            listOf(ColumnAlignment.Start, ColumnAlignment.Center, ColumnAlignment.End),
            table.alignments,
        )
    }

    @Test
    fun ragRowsArePaddedToColumnCount() {
        val blocks = parseMarkdownBlocks(
            """
            | a | b | c |
            |---|---|---|
            | 1 | 2 |
            """.trimIndent(),
        )

        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(3, table.alignments.size)
        assertEquals(listOf("1", "2", ""), table.rows.single())
    }

    @Test
    fun escapedPipeStaysInsideCell() {
        val cells = splitTableRow("""| a \| b | c |""")

        assertEquals(listOf("a | b", "c"), cells)
    }

    @Test
    fun delimiterRowDetection() {
        assertTrue(isTableDelimiterRow("|---|---|"))
        assertTrue(isTableDelimiterRow("| :--- | ---: |"))
        assertTrue(isTableDelimiterRow("--- | ---"))
        // A normal body row is not a delimiter row.
        assertEquals(false, isTableDelimiterRow("| 1 | 2 |"))
        // A heading line with no dashes is not a delimiter row.
        assertEquals(false, isTableDelimiterRow("# heading"))
    }

    @Test
    fun pipeBearingProseWithoutDelimiterStaysParagraph() {
        // A single `|` in prose with no following delimiter row must NOT be
        // promoted to a table (no regression to ordinary text).
        val blocks = parseMarkdownBlocks("use `a | b` to pipe output")

        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun tableDoesNotRegressSurroundingMarkdown() {
        val blocks = parseMarkdownBlocks(
            """
            ## Results

            | a | b |
            |---|---|
            | 1 | 2 |

            - bullet after table
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks.any { it is MarkdownBlock.Table })
        assertTrue(blocks.any { it is MarkdownBlock.Bullet })
        assertTrue(blocks.any { it is MarkdownBlock.CodeBlock })
    }
}
