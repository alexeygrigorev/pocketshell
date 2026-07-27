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

    /**
     * Base-compatible D33/G10 runtime regression for issue #1714. This method
     * intentionally uses only model observables present before and after the
     * structural hard cut (`ListBlock.items`, `Item.spans`, and `Paragraph`),
     * so applying this test to untouched base fails behaviorally rather than at
     * compilation.
     *
     * Exact issue-time excerpt from
     * DataTalksClub/ai-dev-tools-zoomcamp@132e601061ec3bb46c61a4e594a2bdc431754ca2,
     * `01-overview/article.md` lines 48-53, blob
     * 833c847fdd2b94f6ea76d78d8c9ce507e5a67a29. The full article SHA-256 is
     * 38703da0717d46fb6b61fb242f7a617e754229f526f02488d686e936b9563383;
     * the report date is 2026-07-22. Only this excerpt is embedded here.
     */
    @Test
    fun `issue 1714 exact unordered excerpt owns both continued item bodies`() {
        val source = """
            There are two levels of specifications:

            - Project-level - what the project is. We create it once and don't
              modify often.
            - Feature-level - what a change should do and how you'll know it
              worked, written per task and thrown away after.
        """.trimIndent()

        val blocks = MarkdownParser.parse(source)
        val list = blocks.filterIsInstance<MarkdownBlock.ListBlock>().single()
        assertEquals(2, list.items.size)
        assertEquals(
            "Project-level - what the project is. We create it once and don't modify often.",
            inlineText(list.items[0].spans),
        )
        assertEquals(
            "Feature-level - what a change should do and how you'll know it worked, " +
                "written per task and thrown away after.",
            inlineText(list.items[1].spans),
        )
        assertTrue(
            "continuations must not escape into detached paragraphs",
            blocks.filterIsInstance<MarkdownBlock.Paragraph>().none {
                inlineText(it.spans).startsWith("modify often") ||
                    inlineText(it.spans).startsWith("worked, written")
            },
        )
    }

    /**
     * Second exact excerpt from the same pinned source, lines 156-163. Like the
     * unordered test, this remains source-compatible with the flat base model
     * and fails on base because physical continuations split the four-item list.
     */
    @Test
    fun `issue 1714 exact ordered excerpt owns all four continued item bodies`() {
        val source = """
            Every task has four sections:

            1. Goal - one or two sentences on what should be true afterwards.
            2. Acceptance criteria - checkable statements. Not "it works" but
               things where you can point at the screen and say yes or no.
            3. Out of scope - what this change must not do.
            4. Constraints - files it should stay inside, libraries it may not
               add, patterns it must follow.
        """.trimIndent()

        val blocks = MarkdownParser.parse(source)
        val list = blocks.filterIsInstance<MarkdownBlock.ListBlock>().single()
        assertEquals(4, list.items.size)
        assertEquals(listOf(1, 2, 3, 4), list.items.map { it.ordinal })
        assertEquals(
            "Acceptance criteria - checkable statements. Not \"it works\" but things " +
                "where you can point at the screen and say yes or no.",
            inlineText(list.items[1].spans),
        )
        assertEquals(
            "Constraints - files it should stay inside, libraries it may not add, " +
                "patterns it must follow.",
            inlineText(list.items[3].spans),
        )
    }

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
        assertEquals("UNORDERED", listKindName(list))
        assertEquals(2, list.items.size)
        assertTrue(childrenOf(list.items[0]).isEmpty())
        val child = childrenOf(list.items[1]).single()
        assertEquals("UNORDERED", listKindName(child))
        assertEquals("nested", inlineText(child.items.single().spans))
    }

    @Test
    fun `ordered list keeps ordinals`() {
        val blocks = MarkdownParser.parse("1. first\n2. second")
        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals("ORDERED", listKindName(list))
        assertEquals(1, list.items[0].ordinal)
        assertEquals(2, list.items[1].ordinal)
    }

    @Test
    fun `mixed list kinds retain parentage marker dialects and source ordinals`() {
        val source = """
            - unordered root
              7) ordered child
                 * unordered grandchild
                   42. ordered great-grandchild
                       + plus-marker fifth level
              123456789) wide ordered sibling
            * star-marker root sibling

            4. ordered root
               - unordered below ordered
                 9) ordered below unordered again
        """.trimIndent()

        val roots = MarkdownParser.parse(source).filterIsInstance<MarkdownBlock.ListBlock>()
        assertEquals(2, roots.size)

        val unorderedRoot = roots[0]
        assertEquals("UNORDERED", listKindName(unorderedRoot))
        assertEquals(2, unorderedRoot.items.size)
        val orderedChild = childrenOf(unorderedRoot.items[0]).single()
        assertEquals("ORDERED", listKindName(orderedChild))
        assertEquals(listOf(7, 123456789), orderedChild.items.map { it.ordinal })
        val unorderedGrandchild = childrenOf(orderedChild.items[0]).single()
        assertEquals("UNORDERED", listKindName(unorderedGrandchild))
        val orderedGreatGrandchild = childrenOf(unorderedGrandchild.items.single()).single()
        assertEquals("ORDERED", listKindName(orderedGreatGrandchild))
        assertEquals(42, orderedGreatGrandchild.items.single().ordinal)
        val plusMarkerLevel = childrenOf(orderedGreatGrandchild.items.single()).single()
        assertEquals("UNORDERED", listKindName(plusMarkerLevel))
        assertEquals("plus-marker fifth level", inlineText(plusMarkerLevel.items.single().spans))

        val orderedRoot = roots[1]
        assertEquals("ORDERED", listKindName(orderedRoot))
        assertEquals(4, orderedRoot.items.single().ordinal)
        val unorderedChild = childrenOf(orderedRoot.items.single()).single()
        assertEquals("UNORDERED", listKindName(unorderedChild))
        val orderedGrandchild = childrenOf(unorderedChild.items.single()).single()
        assertEquals("ORDERED", listKindName(orderedGrandchild))
        assertEquals(9, orderedGrandchild.items.single().ordinal)
    }

    @Test
    fun `recursive list parser has no four-level depth clamp`() {
        val depth = 12
        val source = buildString {
            repeat(depth) { level ->
                append("  ".repeat(level))
                if (level % 2 == 0) {
                    append("-")
                } else {
                    append("${level * 10 + 7}.")
                }
                append(" depth-").append(level).append('\n')
            }
        }

        var list = MarkdownParser.parse(source).single() as MarkdownBlock.ListBlock
        repeat(depth) { level ->
            val expectedKind = if (level % 2 == 0) "UNORDERED" else "ORDERED"
            assertEquals("kind at depth $level", expectedKind, listKindName(list))
            assertEquals("depth-$level", inlineText(list.items.single().spans))
            if (level < depth - 1) {
                list = childrenOf(list.items.single()).single()
            } else {
                assertTrue(childrenOf(list.items.single()).isEmpty())
            }
        }
    }

    @Test
    fun `continuations stay with their current sibling only`() {
        val source = """
            - first physical line
              first continuation
            - second physical line
              second continuation
        """.trimIndent()

        val list = MarkdownParser.parse(source).single() as MarkdownBlock.ListBlock
        assertEquals(
            listOf(
                "first physical line first continuation",
                "second physical line second continuation",
            ),
            list.items.map { inlineText(it.spans) },
        )
    }

    @Test
    fun `every supported block boundary and outdent closes continuation ownership`() {
        val cases = listOf(
            BoundaryCase(
                name = "blank paragraph",
                source = "- item\n  owned continuation\n\n  detached paragraph",
                followingType = "Paragraph",
            ),
            BoundaryCase(
                name = "heading",
                source = "- item\n  owned continuation\n  ## detached heading",
                followingType = "Heading",
            ),
            BoundaryCase(
                name = "fence",
                source = "- item\n  owned continuation\n  ```text\n  detached code\n  ```",
                followingType = "CodeBlock",
            ),
            BoundaryCase(
                name = "table",
                source = "- item\n  owned continuation\n  | A | B |\n  |---|---|\n  | 1 | 2 |",
                followingType = "Table",
            ),
            BoundaryCase(
                name = "blockquote",
                source = "- item\n  owned continuation\n  > detached quote",
                followingType = "BlockQuote",
            ),
            BoundaryCase(
                name = "thematic rule",
                source = "- item\n  owned continuation\n  ---",
                followingType = "HorizontalRule",
            ),
            BoundaryCase(
                name = "outdent paragraph",
                source = "- item\n  owned continuation\nDetached outdent",
                followingType = "Paragraph",
            ),
        )

        cases.forEach { case ->
            val blocks = MarkdownParser.parse(case.source)
            val list = blocks.first() as MarkdownBlock.ListBlock
            assertEquals(case.name, "item owned continuation", inlineText(list.items.single().spans))
            assertEquals(case.name, case.followingType, blocks[1]::class.simpleName)
        }
    }

    /**
     * Reviewer regression for #1714: both rule spellings also match `UL_ITEM`'s
     * marker shape when indented. Boundary recognition must win before recursive
     * list-marker recognition, leaving the owning body, rule, and outdented
     * paragraph as three separate structures.
     */
    @Test
    fun `indented star and dash thematic rules are boundaries not nested list items`() {
        listOf("* * *", "- - -").forEach { rule ->
            val blocks = MarkdownParser.parse(
                "- item\n  owned continuation\n  $rule\nFollowing paragraph",
            )

            assertEquals("block sequence for $rule", 3, blocks.size)
            val list = blocks[0] as MarkdownBlock.ListBlock
            assertEquals(
                "list body before $rule",
                "item owned continuation",
                inlineText(list.items.single().spans),
            )
            assertTrue("$rule must be a thematic rule", blocks[1] === MarkdownBlock.HorizontalRule)
            val paragraph = blocks[2] as MarkdownBlock.Paragraph
            assertEquals(
                "paragraph after $rule",
                "Following paragraph",
                inlineText(paragraph.spans),
            )
        }
    }

    @Test
    fun `nested outdent returns ownership to the correct ancestor and sibling`() {
        val source = """
            - parent
              7. child
                 child continuation
              42. child sibling
            - root sibling
        """.trimIndent()

        val root = MarkdownParser.parse(source).single() as MarkdownBlock.ListBlock
        assertEquals(2, root.items.size)
        val child = childrenOf(root.items[0]).single()
        assertEquals(listOf(7, 42), child.items.map { it.ordinal })
        assertEquals("child child continuation", inlineText(child.items[0].spans))
        assertEquals("child sibling", inlineText(child.items[1].spans))
        assertEquals("root sibling", inlineText(root.items[1].spans))
    }

    /**
     * The real article's lines 95-101 are a paragraph, not a list. This exact
     * excerpt pins the URL provenance; the separate synthetic test below moves
     * the same prose/link under mixed nested list markers to cover #1714's list
     * ownership class without claiming that the source article did so.
     */
    @Test
    fun `issue 1714 exact soft-wrapped article link keeps its target`() {
        val source = """
            This is how I built
            [SQLiteSearch](https://alexeyondata.substack.com/p/how-i-built-sqlitesearch-a-lightweight),
            a small SQLite-backed search library. First a long chat session to get
            the design straight, then I downloaded the `plan.md` file and started
            coding. That file had all five sections: what the library is, how it
            differs from `minsearch`, when you should use it, when you shouldn't,
            and the architecture.
        """.trimIndent()
        val paragraph = MarkdownParser.parse(source).single() as MarkdownBlock.Paragraph
        assertEquals(
            ISSUE1714_LINK_BODY,
            inlineText(paragraph.spans),
        )
        assertEquals(
            ISSUE1714_LINK,
            paragraph.spans.filterIsInstance<InlineSpan.Link>().single().url,
        )
    }

    @Test
    fun `synthetic continued nested item keeps complete body and exact link`() {
        val source = """
            - synthetic root
              7. This is how I built
                 [SQLiteSearch]($ISSUE1714_LINK),
                 a small SQLite-backed search library. First a long chat session to get
                 the design straight, then I downloaded the `plan.md` file and started
                 coding. That file had all five sections: what the library is, how it
                 differs from `minsearch`, when you should use it, when you shouldn't,
                 and the architecture.
        """.trimIndent()

        val root = MarkdownParser.parse(source).single() as MarkdownBlock.ListBlock
        val nested = childrenOf(root.items.single()).single().items.single()
        assertEquals(
            ISSUE1714_LINK_BODY,
            inlineText(nested.spans),
        )
        assertEquals(
            ISSUE1714_LINK,
            nested.spans.filterIsInstance<InlineSpan.Link>().single().url,
        )
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

    /**
     * Returns the structural kind through runtime observation so the whole test
     * source remains compilable on the pre-#1714 flat model. On that base,
     * `getOrdered()` supplies the legacy observation and structural tests fail
     * at runtime because parentage is absent; on fixed code `getKind()` is used.
     */
    private fun listKindName(list: MarkdownBlock.ListBlock): String {
        val kindGetter = list.javaClass.methods.firstOrNull {
            it.name == "getKind" && it.parameterCount == 0
        }
        if (kindGetter != null) return kindGetter.invoke(list).toString()
        val ordered = list.javaClass.methods.first {
            it.name == "getOrdered" && it.parameterCount == 0
        }.invoke(list) as Boolean
        return if (ordered) "ORDERED" else "UNORDERED"
    }

    /**
     * Same base-compatibility device for child ownership. The old item has no
     * `children` property, so it observes an empty list and fails structural
     * assertions behaviorally rather than making the RED patch uncompilable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun childrenOf(item: Any): List<MarkdownBlock.ListBlock> {
        val getter = item.javaClass.methods.firstOrNull {
            it.name == "getChildren" && it.parameterCount == 0
        } ?: return emptyList()
        return getter.invoke(item) as List<MarkdownBlock.ListBlock>
    }

    private fun inlineText(spans: List<InlineSpan>): String = spans.joinToString("") { span ->
        when (span) {
            is InlineSpan.Text -> span.text
            is InlineSpan.Code -> span.text
            is InlineSpan.Link -> span.label.ifEmpty { span.url }
        }
    }

    private data class BoundaryCase(
        val name: String,
        val source: String,
        val followingType: String,
    )

    private companion object {
        const val ISSUE1714_LINK =
            "https://alexeyondata.substack.com/p/how-i-built-sqlitesearch-a-lightweight"
        const val ISSUE1714_LINK_BODY =
            "This is how I built SQLiteSearch, a small SQLite-backed search library. " +
                "First a long chat session to get the design straight, then I downloaded " +
                "the plan.md file and started coding. That file had all five sections: " +
                "what the library is, how it differs from minsearch, when you should use " +
                "it, when you shouldn't, and the architecture."
    }
}
