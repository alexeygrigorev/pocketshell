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
}
