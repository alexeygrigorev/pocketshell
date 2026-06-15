package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.detectConversationLinks
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Lightweight Compose Markdown renderer.
 *
 * Issue #160 (markdown rendering piece): agent messages frequently
 * contain Markdown (code fences, lists, inline code, bold/italic),
 * but until now the conversation pane rendered them as raw text — so
 * users saw `**bold**` and triple-backticks instead of formatted
 * output.
 *
 * ### Why an in-tree renderer
 *
 * The reviewed candidate library was `dev.jeziellago:compose-markdown`,
 * published on JitPack as `com.github.jeziellago:compose-markdown`.
 * It would pull in:
 *
 * - JitPack as a new dependency repository.
 * - Markwon (a TextView-based Android library) which the wrapper
 *   embeds via an `AndroidView` — i.e. the rendered text is not native
 *   Compose, and selection / scrolling behave subtly differently from
 *   the rest of the app.
 * - Coil, AppCompat, and core-ktx pins older than the project's own
 *   versions, which trigger dependency-resolution warnings against the
 *   Compose BOM 2025.05.00 / Kotlin 2.1.21 stack.
 *
 * The agent-message dialect we render is intentionally small —
 * headings, bold, italic, inline `code`, links, fenced code blocks,
 * and bullet lists. Hand-rolling an AnnotatedString-based renderer for
 * that surface fits in well under a hundred lines, keeps everything
 * inside Compose (so [SelectionContainer] works end-to-end for
 * long-press copy), and adds zero new dependencies. GFM pipe tables
 * are rendered as laid-out columns (#781); HTML pass-through still
 * falls through as plain text.
 *
 * ### Rendering rules
 *
 * - Triple-backtick fences and 4-space-indented blocks become a code
 *   block with a dark background, monospace font, horizontal scroll
 *   for overflowing lines, and no further inline parsing.
 * - Each non-code line is parsed with [renderInline] into an
 *   [AnnotatedString], so bold (`**x**` / `__x__`), italic (`*x*` /
 *   `_x_`), inline code (`` `x` ``), and links (`[label](url)`)
 *   propagate as `SpanStyle`s.
 * - Bullet lines (`- `, `* `, `+ `) and ordered lines (`1. `) get a
 *   leading marker so lists render with the expected indentation.
 * - Headings (`#`, `##`, `###`) bump the font size + weight on the
 *   surrounding paragraph.
 * - GFM pipe tables (a `|`-bearing header row immediately followed by a
 *   `---`/`:--`/`--:`/`:--:` delimiter row) render as aligned columns
 *   with a bold header; column alignment markers are honoured and the
 *   whole table scrolls horizontally as one unit when wide (#781).
 *
 * Anything we don't recognise falls through as plain text — that's the
 * "don't garble prose with stray asterisks" requirement from the
 * issue.
 */
@Composable
public fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PocketShellColors.Text,
    fontSize: TextUnit = 13.sp,
    fontFamily: FontFamily? = null,
    // Issue #557: when supplied, file paths / directories / URLs detected in the
    // rendered text become tappable links — a tap routes the detected
    // [ConversationLink] here (file → viewer, directory → file browser, URL →
    // open) instead of falling through to the composer/keyboard. Default null
    // keeps existing callers (screenshot tests, the composer preview) rendering
    // plain, non-clickable text.
    onLinkTap: ((ConversationLink) -> Unit)? = null,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.CodeBlock -> CodeBlock(block.text)
                    is MarkdownBlock.Heading -> Text(
                        text = renderInline(block.text, onLinkTap),
                        color = color,
                        fontSize = headingSize(block.level),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fontFamily,
                    )
                    is MarkdownBlock.Bullet -> BulletRow(
                        marker = block.marker,
                        text = renderInline(block.text, onLinkTap),
                        color = color,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                    )
                    is MarkdownBlock.Paragraph -> Text(
                        text = renderInline(block.text, onLinkTap),
                        color = color,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                    )
                    is MarkdownBlock.Table -> TableBlock(
                        block = block,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        onLinkTap = onLinkTap,
                    )
                }
            }
        }
    }
}

private fun headingSize(level: Int): TextUnit = when (level) {
    1 -> 18.sp
    2 -> 16.sp
    else -> 15.sp
}

@Composable
private fun CodeBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.TermBg,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            color = PocketShellColors.TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * Render a GFM pipe table (#781).
 *
 * Overflow behaviour (chosen): the WHOLE table is wrapped in a single
 * [horizontalScroll]. A wide table scrolls sideways as one unit and never
 * pushes the rest of the transcript off-screen or clips it. Individual cells
 * wrap their text within a per-column max width so a long single cell doesn't
 * force an unusably wide table. The header row is bold; a thin border + header
 * underline match the compact, dark, terminal-adjacent design system.
 */
@Composable
private fun TableBlock(
    block: MarkdownBlock.Table,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    onLinkTap: ((ConversationLink) -> Unit)?,
) {
    val columnCount = block.alignments.size
    if (columnCount == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(6.dp),
            ),
    ) {
        TableRow(
            cells = block.header,
            alignments = block.alignments,
            fontSize = fontSize,
            fontFamily = fontFamily,
            isHeader = true,
            onLinkTap = onLinkTap,
        )
        block.rows.forEach { row ->
            TableRow(
                cells = row,
                alignments = block.alignments,
                fontSize = fontSize,
                fontFamily = fontFamily,
                isHeader = false,
                onLinkTap = onLinkTap,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    alignments: List<ColumnAlignment>,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    isHeader: Boolean,
    onLinkTap: ((ConversationLink) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .background(
                color = if (isHeader) PocketShellColors.SurfaceElev else Color.Transparent,
            )
            .border(width = TableHairline, color = PocketShellColors.BorderSoft),
    ) {
        alignments.forEachIndexed { index, alignment ->
            val cell = cells.getOrElse(index) { "" }
            val textAlign = when (alignment) {
                ColumnAlignment.Start -> TextAlign.Start
                ColumnAlignment.Center -> TextAlign.Center
                ColumnAlignment.End -> TextAlign.End
            }
            Box(
                modifier = Modifier
                    .width(TableCellMaxWidth)
                    .border(width = TableHairline, color = PocketShellColors.BorderSoft)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = renderInline(cell, onLinkTap),
                    color = if (isHeader) PocketShellColors.Text else PocketShellColors.TextSecondary,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BulletRow(
    marker: String,
    text: AnnotatedString,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            color = color,
            fontSize = fontSize,
            fontFamily = fontFamily,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(text = text, color = color, fontSize = fontSize, fontFamily = fontFamily)
    }
}

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val marker: String, val text: String) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock

    /**
     * A GFM pipe table (#781). [header] is the single header row; [rows] are the
     * body rows; [alignments] holds the per-column alignment parsed from the
     * delimiter row (`:--` → Start, `:--:` → Center, `--:` → End, `--` → Start).
     * All three lists are normalised to the same column count.
     */
    data class Table(
        val header: List<String>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
}

/** Column alignment parsed from a GFM table delimiter cell (#781). */
internal enum class ColumnAlignment { Start, Center, End }

/**
 * Split [input] into block-level Markdown elements. Visible for tests
 * so the block boundary logic can be exercised on the host JVM.
 */
internal fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    if (input.isEmpty()) return emptyList()
    val lines = input.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fenced = FencedCodeRegex.matchEntire(line)
        if (fenced != null) {
            flushParagraph()
            val code = StringBuilder()
            index += 1
            while (index < lines.size && !FencedCodeRegex.matches(lines[index])) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index += 1
            }
            blocks += MarkdownBlock.CodeBlock(code.toString())
            if (index < lines.size) index += 1 // consume the closing fence
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index += 1
            continue
        }
        // GFM pipe table (#781): a header row containing `|` immediately
        // followed by a delimiter row of `---` / `:--` / `--:` / `:--:` cells.
        if (line.contains('|') && index + 1 < lines.size && isTableDelimiterRow(lines[index + 1])) {
            val alignments = parseTableAlignments(lines[index + 1])
            val header = splitTableRow(line)
            index += 2 // consume header + delimiter
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                // A new fence/heading would end the table; pipe-bearing prose
                // inside a fence is already consumed above, so a plain `|` line
                // here is a table body row.
                rows += splitTableRow(lines[index])
                index += 1
            }
            flushParagraph()
            val columnCount = maxOf(
                header.size,
                alignments.size,
                rows.maxOfOrNull { it.size } ?: 0,
            )
            blocks += MarkdownBlock.Table(
                header = header.padToColumns(columnCount),
                alignments = alignments.padAlignments(columnCount),
                rows = rows.map { it.padToColumns(columnCount) },
            )
            continue
        }
        val heading = HeadingRegex.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length.coerceIn(1, 6),
                text = heading.groupValues[2].trim(),
            )
            index += 1
            continue
        }
        val unordered = UnorderedBulletRegex.matchEntire(line)
        if (unordered != null) {
            flushParagraph()
            index += 1
            val text = StringBuilder(unordered.groupValues[2].trim())
            while (index < lines.size && isIndentedContinuationLine(lines[index])) {
                text.append('\n')
                text.append(lines[index].trimStart())
                index += 1
            }
            blocks += MarkdownBlock.Bullet("•", text.toString())
            continue
        }
        val ordered = OrderedBulletRegex.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            index += 1
            val text = StringBuilder(ordered.groupValues[3].trim())
            while (index < lines.size && isIndentedContinuationLine(lines[index])) {
                text.append('\n')
                text.append(lines[index].trimStart())
                index += 1
            }
            blocks += MarkdownBlock.Bullet(
                marker = "${ordered.groupValues[2]}.",
                text = text.toString(),
            )
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
        index += 1
    }
    flushParagraph()
    return blocks
}

private fun isIndentedContinuationLine(line: String): Boolean =
    line.isNotBlank() &&
        (line.startsWith(" ") || line.startsWith("\t")) &&
        HeadingRegex.matchEntire(line.trimStart()) == null &&
        UnorderedBulletRegex.matchEntire(line) == null &&
        OrderedBulletRegex.matchEntire(line) == null

/**
 * Build a Compose [AnnotatedString] from a Markdown line. Visible for
 * tests so the inline-span parsing can be exercised on the host JVM.
 *
 * Issue #557: when [onLinkTap] is supplied, file paths / directories / URLs
 * detected in the line ([detectConversationLinks]) are emitted as
 * [LinkAnnotation.Clickable] spans (underlined, accent-coloured) so a tap routes
 * the [ConversationLink] to the caller instead of falling through to the
 * keyboard. Detection runs on the RAW line text; because a path/URL never
 * contains a Markdown control char (`*`, `_`, backtick, `[`), a link can only
 * begin at a literal position, so the linkified span and the markdown spans
 * never straddle each other.
 */
internal fun renderInline(
    text: String,
    onLinkTap: ((ConversationLink) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    val linksByStart: Map<Int, ConversationLink> =
        if (onLinkTap == null) emptyMap()
        else detectConversationLinks(text).associateBy { it.start }
    var index = 0
    while (index < text.length) {
        // A detected file/dir/url link that starts exactly here is rendered as a
        // single clickable span, taking priority over inline markdown parsing.
        val link = linksByStart[index]
        if (link != null && onLinkTap != null) {
            appendConversationLink(text, link, onLinkTap)
            index = link.endExclusive
            continue
        }
        val ch = text[index]
        if (ch == '\\' && index + 1 < text.length) {
            append(text[index + 1])
            index += 2
            continue
        }
        if (ch == '`') {
            val end = text.indexOf('`', startIndex = index + 1)
            if (end > index) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = PocketShellColors.TermBg)) {
                    append(text.substring(index + 1, end))
                }
                index = end + 1
                continue
            }
        }
        if (ch == '[' ) {
            val close = text.indexOf(']', startIndex = index + 1)
            if (close > index && close + 1 < text.length && text[close + 1] == '(') {
                val urlEnd = text.indexOf(')', startIndex = close + 2)
                if (urlEnd > close) {
                    val label = text.substring(index + 1, close)
                    withStyle(
                        SpanStyle(
                            color = PocketShellColors.Accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(label)
                    }
                    index = urlEnd + 1
                    continue
                }
            }
        }
        if (ch == '*' || ch == '_') {
            val isDouble = index + 1 < text.length && text[index + 1] == ch
            if (isDouble) {
                val needle = "" + ch + ch
                val end = text.indexOf(needle, startIndex = index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                    continue
                }
            } else {
                val end = text.indexOf(ch, startIndex = index + 1)
                if (end > index && text.getOrNull(index - 1)?.isLetterOrDigit() != true) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                    continue
                }
            }
        }
        append(ch)
        index += 1
    }
}

private inline fun AnnotatedString.Builder.withStyle(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val token = pushStyle(style)
    try {
        block()
    } finally {
        pop(token)
    }
}

/**
 * Append [link]'s literal text (taken verbatim from [raw] so any trailing
 * punctuation the detector trimmed stays as plain text, not part of the tap
 * target) wrapped in a [LinkAnnotation.Clickable]. The whole span is underlined
 * + accent-coloured and, on tap, routes the [ConversationLink] to [onLinkTap]
 * (issue #557).
 */
private fun AnnotatedString.Builder.appendConversationLink(
    raw: String,
    link: ConversationLink,
    onLinkTap: (ConversationLink) -> Unit,
) {
    val clickable = LinkAnnotation.Clickable(
        tag = CONVERSATION_LINK_TAG,
        styles = TextLinkStyles(
            style = SpanStyle(
                color = PocketShellColors.Accent,
                textDecoration = TextDecoration.Underline,
            ),
        ),
    ) { onLinkTap(link) }
    withLink(clickable) {
        append(raw.substring(link.start, link.endExclusive))
    }
}

/** Test/inspection tag carried by every conversation tap-to-open link span. */
internal const val CONVERSATION_LINK_TAG: String = "conversation-link"

// Per-column max width for table cells. Wide tables scroll horizontally as a
// unit; a long single cell wraps inside this bound rather than ballooning the
// whole table (#781).
private val TableCellMaxWidth = 160.dp
private val TableHairline = 0.5.dp

private val FencedCodeRegex = Regex("^```[a-zA-Z0-9_+-]*\\s*$")
private val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val UnorderedBulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val OrderedBulletRegex = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$")

// A single GFM delimiter cell: optional leading `:`, one-or-more `-`, optional
// trailing `:`, surrounded by optional whitespace.
private val TableDelimiterCellRegex = Regex("^\\s*:?-+:?\\s*$")

/**
 * True when [line] is a GFM table delimiter row — every pipe-separated cell is a
 * dashes-with-optional-colons marker and there is at least one cell. Visible for
 * tests (#781).
 */
internal fun isTableDelimiterRow(line: String): Boolean {
    if (!line.contains('-')) return false
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return false
    return cells.all { TableDelimiterCellRegex.matches(it) }
}

/**
 * Split a GFM table row on unescaped `|`, dropping the optional leading/trailing
 * pipe so `| a | b |` and `a | b` both yield `[a, b]`. A backslash-escaped `\|`
 * stays as a literal `|` inside a cell. Visible for tests (#781).
 */
internal fun splitTableRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (ch == '\\' && i + 1 < line.length && line[i + 1] == '|') {
            current.append('|')
            i += 2
            continue
        }
        if (ch == '|') {
            cells += current.toString().trim()
            current.clear()
            i += 1
            continue
        }
        current.append(ch)
        i += 1
    }
    cells += current.toString().trim()
    // Drop the empty cells produced by an optional leading/trailing pipe.
    if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
    return cells
}

/**
 * Parse the per-column alignment from a GFM delimiter row. Visible for tests
 * (#781). `:--` → Start, `:--:` → Center, `--:` → End, `--` → Start (default).
 */
internal fun parseTableAlignments(delimiterRow: String): List<ColumnAlignment> =
    splitTableRow(delimiterRow).map { cell ->
        val token = cell.trim()
        val left = token.startsWith(":")
        val right = token.endsWith(":")
        when {
            left && right -> ColumnAlignment.Center
            right -> ColumnAlignment.End
            else -> ColumnAlignment.Start
        }
    }

private fun List<String>.padToColumns(columns: Int): List<String> =
    if (size >= columns) this else this + List(columns - size) { "" }

private fun List<ColumnAlignment>.padAlignments(columns: Int): List<ColumnAlignment> =
    if (size >= columns) this else this + List(columns - size) { ColumnAlignment.Start }
