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
}
