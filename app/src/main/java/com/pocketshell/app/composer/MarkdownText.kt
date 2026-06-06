package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * long-press copy), and adds zero new dependencies. Trade-off: tables
 * and HTML pass-through aren't rendered, but the agent output we see
 * in practice doesn't use them.
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
}

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
            blocks += MarkdownBlock.Bullet("•", unordered.groupValues[2].trim())
            index += 1
            continue
        }
        val ordered = OrderedBulletRegex.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(
                marker = "${ordered.groupValues[2]}.",
                text = ordered.groupValues[3].trim(),
            )
            index += 1
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
        index += 1
    }
    flushParagraph()
    return blocks
}

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

private val FencedCodeRegex = Regex("^```[a-zA-Z0-9_+-]*\\s*$")
private val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val UnorderedBulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val OrderedBulletRegex = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$")
