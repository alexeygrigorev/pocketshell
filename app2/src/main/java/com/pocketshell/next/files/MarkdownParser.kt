package com.pocketshell.next.files

/**
 * Pure Markdown parser for the in-app file viewer (rewrite task P-3b; ported
 * unchanged from the old client, minus its `isMarkdownPath` helper which now
 * lives on [FileKindDetector] beside the rest of the type decision).
 *
 * Parses a well-bounded CommonMark subset — ATX headings, fenced/indented code
 * blocks, ordered/unordered lists, block quotes, thematic breaks, paragraphs,
 * and the inline runs bold/italic/strikethrough/inline-code/links — into the
 * [MarkdownBlock]/[InlineSpan] model the Compose renderer consumes. No third
 * party dependency (see [MarkdownBlock] doc for the rationale).
 *
 * Visible-for-test: every decision is a pure function so the block/inline
 * structure is pinned without an emulator.
 */
internal object MarkdownParser {

    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*(.*)$")
    private val ATX = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val UL_ITEM = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val OL_ITEM = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?(.*)$")

    /**
     * A GFM table delimiter cell: dashes with an optional leading/trailing `:`
     * for alignment (e.g. `:---`, `---:`, `:--:`, `---`). At least one dash.
     */
    private val TABLE_DELIMITER_CELL = Regex("^:?-+:?$")

    /**
     * Thematic break (`---`, `***`, `___`) detected with a plain character scan
     * — never a backreference regex. A backref like `([-*_])(?:\s*\1){2,}`
     * makes the JDK engine recurse per repetition and overflows the stack on a
     * long divider line (a 2000+ char run of `-`/`*`/`_` crashes the viewer),
     * so we scan instead: ≤3 leading spaces, then every non-space char must be
     * the same one of `{-,*,_}` and there must be ≥3 of them. Linear, no stack.
     */
    fun isThematicBreak(line: String): Boolean {
        var i = 0
        var leadingSpaces = 0
        // Count leading spaces/tabs (a tab still counts as indentation width).
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
            leadingSpaces++
            i++
        }
        if (leadingSpaces > 3) return false
        if (i >= line.length) return false
        val marker = line[i]
        if (marker != '-' && marker != '*' && marker != '_') return false
        var count = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == marker -> count++
                c == ' ' || c == '\t' -> {} // interior/trailing spaces allowed
                else -> return false
            }
            i++
        }
        return count >= 3
    }

    /** Parse a full document into block elements. */
    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Blank line — skip.
            if (line.isBlank()) {
                i++
                continue
            }

            // Fenced code block.
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val fenceToken = fence.groupValues[1]
                val lang = fence.groupValues[2].trim()
                val body = StringBuilder()
                i++
                while (i < lines.size) {
                    val close = FENCE.matchEntire(lines[i])
                    if (close != null && close.groupValues[1][0] == fenceToken[0] &&
                        close.groupValues[1].length >= fenceToken.length &&
                        close.groupValues[2].isBlank()
                    ) {
                        i++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                blocks += MarkdownBlock.CodeBlock(language = lang, content = body.toString())
                continue
            }

            // Thematic break.
            if (isThematicBreak(line)) {
                blocks += MarkdownBlock.HorizontalRule
                i++
                continue
            }

            // ATX heading.
            val atx = ATX.matchEntire(line)
            if (atx != null) {
                blocks += MarkdownBlock.Heading(
                    level = atx.groupValues[1].length,
                    spans = parseInline(atx.groupValues[2]),
                )
                i++
                continue
            }

            // Block quote (consume consecutive `>` lines).
            if (BLOCKQUOTE.matchEntire(line) != null) {
                val quoted = StringBuilder()
                while (i < lines.size) {
                    val m = BLOCKQUOTE.matchEntire(lines[i]) ?: break
                    if (quoted.isNotEmpty()) quoted.append(' ')
                    quoted.append(m.groupValues[1].trim())
                    i++
                }
                blocks += MarkdownBlock.BlockQuote(parseInline(quoted.toString()))
                continue
            }

            // GFM pipe table — a header row plus a delimiter row, then body rows
            // (issue #921). Only a header line immediately followed by a valid
            // delimiter row whose column count EQUALS the header's begins a
            // table; otherwise the `|` text is a normal paragraph. The
            // column-count match is the GFM guard that keeps a prose line with a
            // pipe above a `---` thematic break (a 1-cell delimiter) from being
            // swallowed as a table (issue #921 review).
            if (line.contains('|') && i + 1 < lines.size && isTableDelimiterRow(lines[i + 1]) &&
                splitTableRow(lines[i + 1]).size == splitTableRow(line).size
            ) {
                val alignments = parseDelimiterAlignments(lines[i + 1])
                val header = splitTableRow(line)
                i += 2 // past header + delimiter
                val rows = mutableListOf<List<List<InlineSpan>>>()
                while (i < lines.size) {
                    val rowLine = lines[i]
                    // A table ends at a blank line or any line without a pipe.
                    if (rowLine.isBlank() || !rowLine.contains('|')) break
                    rows += splitTableRow(rowLine).map { parseInline(it) }
                    i++
                }
                blocks += MarkdownBlock.Table(
                    header = header.map { parseInline(it) },
                    alignments = alignments,
                    rows = rows,
                )
                continue
            }

            // Structural list. A marker owns its indented soft-continuation
            // prose; deeper markers become child list blocks. This preserves
            // complete item bodies, mixed nesting, and source ordinals (#1714).
            if (listMarker(line) != null) {
                val parsed = parseList(lines, i)
                blocks += parsed.block
                i = parsed.nextIndex
                continue
            }

            // Paragraph — gather consecutive non-blank lines that aren't a new
            // block start, joined with spaces (soft wraps).
            val para = StringBuilder(line.trim())
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank() ||
                    FENCE.matchEntire(next) != null ||
                    isThematicBreak(next) ||
                    ATX.matchEntire(next) != null ||
                    BLOCKQUOTE.matchEntire(next) != null ||
                    UL_ITEM.matchEntire(next) != null ||
                    OL_ITEM.matchEntire(next) != null ||
                    // A table header starting here (pipe line followed by a
                    // matching-width delimiter row) ends the paragraph so the
                    // table parses. The column-count match mirrors the table
                    // branch's GFM guard so a `---` rule below a pipe prose line
                    // does not falsely split the paragraph.
                    (next.contains('|') && i + 1 < lines.size && isTableDelimiterRow(lines[i + 1]) &&
                        splitTableRow(lines[i + 1]).size == splitTableRow(next).size)
                ) {
                    break
                }
                para.append(' ').append(next.trim())
                i++
            }
            blocks += MarkdownBlock.Paragraph(parseInline(para.toString()))
        }
        return blocks
    }

    private data class ListMarker(
        val indent: Int,
        val kind: MarkdownBlock.ListBlock.Kind,
        val ordinal: Int?,
        val body: String,
    )

    private data class MutableListItem(
        val ordinal: Int?,
        val bodyLines: MutableList<String>,
        val children: MutableList<MarkdownBlock.ListBlock> = mutableListOf(),
    )

    private data class ParsedList(
        val block: MarkdownBlock.ListBlock,
        val nextIndex: Int,
    )

    /**
     * Parses one list whose sibling markers share the first marker's indentation
     * and kind. Deeper markers recursively become children of the current item;
     * an outdent or same-depth kind switch returns to the caller.
     *
     * Supported block starts take precedence over marker-shaped lines: indented
     * thematic rules such as `* * *` and `- - -` must close the list rather than
     * become nested unordered items. An indented non-marker line is a soft
     * continuation only until a blank or another supported block start.
     * Loose/multi-paragraph items and block contents inside list items remain
     * outside this bounded Markdown subset. Recursion follows source structure
     * and deliberately has no depth clamp.
     */
    private fun parseList(lines: List<String>, start: Int): ParsedList {
        val first = requireNotNull(listMarker(lines[start]))
        val baseIndent = first.indent
        val kind = first.kind
        val items = mutableListOf<MutableListItem>()
        var current: MutableListItem? = null
        var i = start

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || isSupportedBlockStart(lines, i)) break

            val marker = listMarker(line)
            if (marker != null) {
                when {
                    marker.indent < baseIndent -> break
                    marker.indent == baseIndent && marker.kind != kind -> break
                    marker.indent == baseIndent -> {
                        current = MutableListItem(
                            ordinal = marker.ordinal,
                            bodyLines = mutableListOf(marker.body.trim()),
                        )
                        items += current
                        i++
                    }
                    else -> {
                        val owner = current ?: break
                        val child = parseList(lines, i)
                        owner.children += child.block
                        i = child.nextIndex
                    }
                }
                continue
            }

            val leadingIndent = indentationWidth(line.takeWhile { it == ' ' || it == '\t' })
            if (leadingIndent <= baseIndent) break

            val owner = current ?: break
            owner.bodyLines += line.trim()
            i++
        }

        return ParsedList(
            block = MarkdownBlock.ListBlock(
                kind = kind,
                items = items.map { item ->
                    MarkdownBlock.ListBlock.Item(
                        ordinal = item.ordinal,
                        spans = parseInline(item.bodyLines.joinToString(" ")),
                        children = item.children.toList(),
                    )
                },
            ),
            nextIndex = i,
        )
    }

    private fun listMarker(line: String): ListMarker? {
        OL_ITEM.matchEntire(line)?.let { match ->
            return ListMarker(
                indent = indentationWidth(match.groupValues[1]),
                kind = MarkdownBlock.ListBlock.Kind.ORDERED,
                ordinal = match.groupValues[2].toIntOrNull(),
                body = match.groupValues[3],
            )
        }
        UL_ITEM.matchEntire(line)?.let { match ->
            return ListMarker(
                indent = indentationWidth(match.groupValues[1]),
                kind = MarkdownBlock.ListBlock.Kind.UNORDERED,
                ordinal = null,
                body = match.groupValues[3],
            )
        }
        return null
    }

    private fun indentationWidth(leading: String): Int =
        leading.fold(0) { width, char -> width + if (char == '\t') 4 else 1 }

    private fun isSupportedBlockStart(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return FENCE.matchEntire(line) != null ||
            isThematicBreak(line) ||
            ATX.matchEntire(line) != null ||
            BLOCKQUOTE.matchEntire(line) != null ||
            (
                line.contains('|') &&
                    index + 1 < lines.size &&
                    isTableDelimiterRow(lines[index + 1]) &&
                    splitTableRow(lines[index + 1]).size == splitTableRow(line).size
                )
    }

    /**
     * Whether [line] is a valid GFM table delimiter row: at least one cell, and
     * every `|`-separated cell is dashes with optional alignment colons. The
     * leading/trailing pipes are optional, so we split tolerantly first.
     */
    fun isTableDelimiterRow(line: String): Boolean {
        val cells = splitTableRow(line)
        if (cells.isEmpty()) return false
        return cells.all { it.isNotEmpty() && TABLE_DELIMITER_CELL.matches(it) }
    }

    /** Per-column alignment parsed from the delimiter row's `:` markers. */
    private fun parseDelimiterAlignments(line: String): List<MarkdownBlock.Table.Alignment> =
        splitTableRow(line).map { cell ->
            val left = cell.startsWith(':')
            val right = cell.endsWith(':')
            when {
                left && right -> MarkdownBlock.Table.Alignment.CENTER
                right -> MarkdownBlock.Table.Alignment.RIGHT
                left -> MarkdownBlock.Table.Alignment.LEFT
                else -> MarkdownBlock.Table.Alignment.NONE
            }
        }

    /**
     * Split one table row into trimmed cell strings. Leading/trailing pipes are
     * stripped, and a backslash-escaped `\|` inside a cell is kept literal (it
     * is not a column separator). Empty leading/trailing cells from the outer
     * pipes are dropped; interior empty cells are preserved.
     */
    fun splitTableRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                    current.append('|') // escaped pipe — literal
                    i += 2
                }
                c == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        cells += current.toString().trim()
        // Drop the empty cells produced by an outer leading/trailing pipe.
        if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
        return cells
    }

    /**
     * Parse inline runs: links, inline code, bold, italic, strikethrough. A
     * left-to-right scanner — simple and predictable rather than a full
     * CommonMark emphasis resolver. Inline code (`` ` ``) wins over emphasis so
     * `**` inside backticks stays literal.
     */
    fun parseInline(text: String): List<InlineSpan> {
        val out = mutableListOf<InlineSpan>()
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += InlineSpan.Text(plain.toString())
                plain.clear()
            }
        }

        while (i < text.length) {
            val c = text[i]

            // Inline code.
            if (c == '`') {
                val tickRun = countRun(text, i, '`')
                val close = findClosingTicks(text, i + tickRun, tickRun)
                if (close >= 0) {
                    flushPlain()
                    out += InlineSpan.Code(text.substring(i + tickRun, close).trim())
                    i = close + tickRun
                    continue
                }
            }

            // Link [label](url).
            if (c == '[') {
                val link = parseLink(text, i)
                if (link != null) {
                    flushPlain()
                    out += link.first
                    i = link.second
                    continue
                }
            }

            // Emphasis runs: ***, ___, **, __, *, _, ~~.
            val emphasis = parseEmphasis(text, i)
            if (emphasis != null) {
                flushPlain()
                out += emphasis.first
                i = emphasis.second
                continue
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return if (out.isEmpty()) listOf(InlineSpan.Text("")) else out
    }

    private fun countRun(s: String, start: Int, ch: Char): Int {
        var n = 0
        while (start + n < s.length && s[start + n] == ch) n++
        return n
    }

    private fun findClosingTicks(s: String, from: Int, runLen: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                val run = countRun(s, i, '`')
                if (run == runLen) return i
                i += run
            } else {
                i++
            }
        }
        return -1
    }

    private fun parseLink(text: String, start: Int): Pair<InlineSpan.Link, Int>? {
        // [label](url)
        var i = start + 1
        var depth = 1
        val labelStart = i
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            if (depth == 0) break
            i++
        }
        if (i >= text.length || text[i] != ']') return null
        val label = text.substring(labelStart, i)
        i++ // past ]
        if (i >= text.length || text[i] != '(') return null
        i++ // past (
        val urlStart = i
        // Balance nested parens so a URL like `.../Foo_(bar)` keeps its tail
        // instead of stopping at the first `)`. The closing `)` is the one that
        // returns paren depth to zero.
        var parenDepth = 1
        while (i < text.length && parenDepth > 0) {
            when (text[i]) {
                '(' -> parenDepth++
                ')' -> parenDepth--
            }
            if (parenDepth == 0) break
            i++
        }
        if (i >= text.length || text[i] != ')') return null
        val url = text.substring(urlStart, i).trim()
        i++ // past )
        return InlineSpan.Link(label = label, url = url) to i
    }

    private fun parseEmphasis(text: String, start: Int): Pair<InlineSpan.Text, Int>? {
        val markers = listOf(
            "***" to Triple(true, true, false),
            "___" to Triple(true, true, false),
            "**" to Triple(true, false, false),
            "__" to Triple(true, false, false),
            "~~" to Triple(false, false, true),
            "*" to Triple(false, true, false),
            "_" to Triple(false, true, false),
        )
        for ((marker, style) in markers) {
            if (!text.startsWith(marker, start)) continue
            val contentStart = start + marker.length
            val close = text.indexOf(marker, contentStart)
            if (close < 0) continue
            val inner = text.substring(contentStart, close)
            if (inner.isEmpty()) continue
            val (bold, italic, strike) = style
            return InlineSpan.Text(
                text = inner,
                bold = bold,
                italic = italic,
                strikethrough = strike,
            ) to (close + marker.length)
        }
        return null
    }
}
