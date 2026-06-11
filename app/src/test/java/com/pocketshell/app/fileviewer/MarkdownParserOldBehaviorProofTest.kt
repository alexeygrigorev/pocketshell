package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failing-on-old PROOF for the #696 review fixes. These tests reconstruct the
 * exact pre-fix logic locally (the backreference HR regex and the first-`)` URL
 * truncation) and demonstrate that it (a) StackOverflows on a long divider line
 * and (b) truncates a parens-containing URL. They are red against the OLD code
 * and stay green only because the NEW code lives in MarkdownParser — i.e. they
 * pin the regression these review findings were about, independent of the fix.
 */
class MarkdownParserOldBehaviorProofTest {

    /** The exact pre-fix thematic-break regex (with the recursive backref). */
    private val oldHrRegex = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*\$")

    @Test
    fun `old backref HR regex StackOverflows on a long dash line`() {
        var crashed = false
        try {
            oldHrRegex.matchEntire("-".repeat(5000))
        } catch (e: StackOverflowError) {
            crashed = true
        }
        assertTrue(
            "the pre-fix backref HR regex must overflow the stack on 5000 dashes",
            crashed,
        )
        // And the shipped parser must NOT — same input, no crash, renders an HR.
        val blocks = MarkdownParser.parse("-".repeat(5000))
        assertTrue(blocks.any { it === MarkdownBlock.HorizontalRule })
    }

    /** Replicates the pre-fix URL scan that stopped at the first `)`. */
    private fun oldParseLinkUrl(text: String): String? {
        val open = text.indexOf("](")
        if (open < 0) return null
        var i = open + 2
        val start = i
        while (i < text.length && text[i] != ')') i++
        if (i >= text.length) return null
        return text.substring(start, i)
    }

    @Test
    fun `old parseLink truncated a parens url, new parser keeps it whole`() {
        val md = "[wiki](https://en.wikipedia.org/wiki/Foo_(bar))"
        // Old: lost the tail at the first `)`.
        assertNotEquals("https://en.wikipedia.org/wiki/Foo_(bar)", oldParseLinkUrl(md))
        // New: full URL preserved.
        val link = MarkdownParser.parseInline(md).filterIsInstance<InlineSpan.Link>().single()
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", link.url)
    }
}
