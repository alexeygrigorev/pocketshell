package com.pocketshell.app.fileviewer

/**
 * Pure Markdown parser for the in-app file viewer (issue #696).
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

    /** Detect Markdown by file extension — the viewer's render-vs-raw switch. */
    fun isMarkdownPath(path: String): Boolean {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext == "md" || ext == "markdown" || ext == "mdown" || ext == "mkd"
    }

    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*(.*)$")
    private val ATX = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val UL_ITEM = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val OL_ITEM = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?(.*)$")

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

            // List (ordered or unordered) — consume consecutive item lines.
            if (UL_ITEM.matchEntire(line) != null || OL_ITEM.matchEntire(line) != null) {
                val items = mutableListOf<MarkdownBlock.ListBlock.Item>()
                val ordered = OL_ITEM.matchEntire(line) != null
                while (i < lines.size) {
                    val ul = UL_ITEM.matchEntire(lines[i])
                    val ol = OL_ITEM.matchEntire(lines[i])
                    when {
                        ol != null && ordered -> {
                            items += MarkdownBlock.ListBlock.Item(
                                indentLevel = indentLevel(ol.groupValues[1]),
                                ordinal = ol.groupValues[2].toIntOrNull(),
                                spans = parseInline(ol.groupValues[3]),
                            )
                            i++
                        }
                        ul != null && !ordered -> {
                            items += MarkdownBlock.ListBlock.Item(
                                indentLevel = indentLevel(ul.groupValues[1]),
                                ordinal = null,
                                spans = parseInline(ul.groupValues[3]),
                            )
                            i++
                        }
                        else -> break
                    }
                }
                blocks += MarkdownBlock.ListBlock(ordered = ordered, items = items)
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
                    OL_ITEM.matchEntire(next) != null
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

    private fun indentLevel(leading: String): Int {
        val width = leading.fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }
        return (width / 2).coerceAtMost(4)
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
