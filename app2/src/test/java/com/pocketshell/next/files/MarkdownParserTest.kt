package com.pocketshell.next.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown parser (task P-3b). Pure JVM.
 *
 * The parser is a straight port from the old client, so this suite pins the
 * block/inline shapes the RENDERER depends on rather than re-deriving the old
 * module's full case matrix: if any of these drift, the viewer paints raw
 * syntax or drops content, which is exactly the user-visible failure.
 */
class MarkdownParserTest {

    @Test
    fun `atx headings carry their level and inline content`() {
        val blocks = MarkdownParser.parse("# Title\n\n### Deeper\n")

        assertEquals(2, blocks.size)
        val first = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, first.level)
        assertEquals("Title", first.spans.text())
        assertEquals(3, (blocks[1] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `soft-wrapped lines join into one paragraph`() {
        val blocks = MarkdownParser.parse("one\ntwo\n\nthree\n")

        assertEquals("one two", (blocks[0] as MarkdownBlock.Paragraph).spans.text())
        assertEquals("three", (blocks[1] as MarkdownBlock.Paragraph).spans.text())
    }

    @Test
    fun `a fenced code block keeps its language and verbatim body`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = **not bold**\n```\n")

        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = **not bold**", code.content)
    }

    @Test
    fun `nested lists keep their structure and source ordinals`() {
        val blocks = MarkdownParser.parse(
            """
            1. first
            2. second
               - child a
               - child b
            """.trimIndent(),
        )

        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(MarkdownBlock.ListBlock.Kind.ORDERED, list.kind)
        assertEquals(listOf(1, 2), list.items.map { it.ordinal })
        val children = list.items[1].children.single()
        assertEquals(MarkdownBlock.ListBlock.Kind.UNORDERED, children.kind)
        assertEquals(listOf("child a", "child b"), children.items.map { it.spans.text() })
    }

    @Test
    fun `a pipe table parses its header, alignment and rows`() {
        val blocks = MarkdownParser.parse(
            """
            | name | size |
            |:-----|-----:|
            | a.txt | 12 |
            | b.txt | 34 |
            """.trimIndent(),
        )

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("name", "size"), table.header.map { it.text() })
        assertEquals(
            listOf(MarkdownBlock.Table.Alignment.LEFT, MarkdownBlock.Table.Alignment.RIGHT),
            table.alignments,
        )
        assertEquals(listOf("a.txt", "12"), table.rows[0].map { it.text() })
        assertEquals(2, table.rows.size)
    }

    @Test
    fun `a prose line with a pipe above a thematic break is not a table`() {
        val blocks = MarkdownParser.parse("a | b\n---\nnext\n")

        assertTrue("expected a paragraph, got ${blocks[0]}", blocks[0] is MarkdownBlock.Paragraph)
        assertEquals(MarkdownBlock.HorizontalRule, blocks[1])
    }

    @Test
    fun `a very long divider does not blow the stack`() {
        // The old parser used a backreference regex here and overflowed the JVM
        // stack on a long divider, crashing the viewer on a real document.
        assertTrue(MarkdownParser.isThematicBreak("-".repeat(5_000)))
        assertEquals(
            listOf(MarkdownBlock.HorizontalRule),
            MarkdownParser.parse("*".repeat(5_000)),
        )
    }

    @Test
    fun `inline runs cover emphasis, code and links`() {
        val spans = MarkdownParser.parseInline(
            "plain **bold** _italic_ ~~gone~~ `code` [label](https://example.com/a_(b))",
        )

        val bold = spans.filterIsInstance<InlineSpan.Text>().single { it.bold }
        assertEquals("bold", bold.text)
        assertTrue(spans.filterIsInstance<InlineSpan.Text>().single { it.italic }.text == "italic")
        assertTrue(spans.filterIsInstance<InlineSpan.Text>().single { it.strikethrough }.text == "gone")
        assertEquals("code", spans.filterIsInstance<InlineSpan.Code>().single().text)
        val link = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("label", link.label)
        // Nested parens in the URL survive — a Wikipedia-style link would
        // otherwise lose its tail.
        assertEquals("https://example.com/a_(b)", link.url)
    }

    @Test
    fun `emphasis inside inline code stays literal`() {
        val spans = MarkdownParser.parseInline("`**not bold**`")

        assertEquals("**not bold**", spans.filterIsInstance<InlineSpan.Code>().single().text)
    }

    @Test
    fun `a block quote collapses its lines`() {
        val blocks = MarkdownParser.parse("> first\n> second\n")

        assertEquals("first second", (blocks.single() as MarkdownBlock.BlockQuote).spans.text())
    }

    private fun List<InlineSpan>.text(): String = joinToString("") { span ->
        when (span) {
            is InlineSpan.Text -> span.text
            is InlineSpan.Code -> span.text
            is InlineSpan.Link -> span.label
        }
    }
}
