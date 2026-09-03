package com.pocketshell.next.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

/** Test tags for the rendered Markdown surfaces. */
const val MARKDOWN_VIEW_TAG: String = "viewer-markdown"
const val MARKDOWN_TABLE_TAG: String = "viewer-markdown-table"

/**
 * Renders parsed [MarkdownBlock]s into a themed column (rewrite task P-3b,
 * ported from the old client's `MarkdownView`).
 *
 * Headings scale by level, fenced code is monospaced on a tinted surface with
 * its own horizontal scroll, lists render as hanging marker/body columns
 * (recursively, so nesting survives), tables lay out as real cells with the
 * delimiter row's justification, and links are tappable.
 *
 * One change from the port: links are `LinkAnnotation.Url` inside the annotated
 * string rather than a `ClickableText` with a hand-rolled offset lookup and an
 * `ACTION_VIEW` intent. `ClickableText` is deprecated, and the annotation form
 * means the platform opens the URL, handles accessibility focus for the link,
 * and needs no `Context` — which is why this file has no Android imports at all
 * and renders in a plain Robolectric composition.
 */
@Composable
internal fun MarkdownView(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(MARKDOWN_VIEW_TAG),
    ) {
        blocks.forEachIndexed { blockIndex, block ->
            when (block) {
                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.spans)
                is MarkdownBlock.CodeBlock -> CodeBlock(block)
                is MarkdownBlock.ListBlock -> ListBlock(block, path = blockIndex.toString())
                is MarkdownBlock.BlockQuote -> BlockQuoteBlock(block)
                is MarkdownBlock.Table -> TableBlock(block)
                MarkdownBlock.HorizontalRule -> HorizontalRuleBlock()
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val size = when (block.level) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        4 -> 15.sp
        else -> 14.sp
    }
    Text(
        text = annotated(block.spans),
        color = PocketShellColors.Text,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 16.dp else 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ParagraphBlock(spans: List<InlineSpan>) {
    BodyText(
        text = annotated(spans),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun CodeBlock(block: MarkdownBlock.CodeBlock) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = RoundedCornerShape(6.dp)),
    ) {
        Text(
            text = block.content,
            color = PocketShellColors.TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            // Only fenced code keeps a horizontal scroll: everything else wraps
            // to the viewport, but a wrapped code line is unreadable.
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

@Composable
private fun ListBlock(block: MarkdownBlock.ListBlock, path: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            val itemPath = "$path:$index"
            val marker = if (block.kind == MarkdownBlock.ListBlock.Kind.ORDERED) {
                "${item.ordinal ?: (index + 1)}."
            } else {
                "•"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                // The marker measures intrinsically and the body is the weighted
                // hanging column, so every wrapped line of an item shares one
                // left edge and a wide ordinal cannot overlap its text.
                Text(
                    text = marker,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 8.dp),
                )
                BodyText(text = annotated(item.spans), modifier = Modifier.weight(1f))
            }
            item.children.forEachIndexed { childIndex, child ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                ) {
                    ListBlock(child, path = "$itemPath:$childIndex")
                }
            }
        }
    }
}

@Composable
private fun BlockQuoteBlock(block: MarkdownBlock.BlockQuote) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(color = PocketShellColors.Surface, shape = RoundedCornerShape(4.dp)),
    ) {
        BodyText(
            text = annotated(block.spans),
            color = PocketShellColors.TextSecondary,
            italic = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
    }
}

// A tint band's corner is a genuine sub-ladder "micro role" radius
// (docs/design-system.md, "Micro role badges | 3-6dp"), smaller than the named
// 8/14/20/28dp rungs. 6dp matches the sibling code block, named here so it reads
// as one intentional token rather than off-ladder drift.
private val MarkdownTableBorderShape = RoundedCornerShape(6.dp)

@Composable
private fun TableBlock(block: MarkdownBlock.Table) {
    val columnCount = block.header.size.coerceAtLeast(1)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(MARKDOWN_TABLE_TAG),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .border(1.dp, PocketShellColors.BorderSoft, MarkdownTableBorderShape),
        ) {
            TableRow(block.header, columnCount, block.alignments, isHeader = true)
            block.rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(PocketShellColors.BorderSoft),
                )
                TableRow(row, columnCount, block.alignments, isHeader = false)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<InlineSpan>>,
    columnCount: Int,
    alignments: List<MarkdownBlock.Table.Alignment>,
    isHeader: Boolean,
) {
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .then(if (isHeader) Modifier.background(PocketShellColors.SurfaceElev) else Modifier),
    ) {
        for (column in 0 until columnCount) {
            if (column > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(PocketShellColors.BorderSoft),
                )
            }
            val spans = cells.getOrNull(column) ?: listOf(InlineSpan.Text(""))
            Text(
                text = annotated(spans),
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = when (alignments.getOrNull(column)) {
                    MarkdownBlock.Table.Alignment.CENTER -> TextAlign.Center
                    MarkdownBlock.Table.Alignment.RIGHT -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier
                    .width(140.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun HorizontalRuleBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(1.dp)
            .background(PocketShellColors.BorderSoft),
    )
}

@Composable
private fun BodyText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = PocketShellColors.Text,
    italic: Boolean = false,
) {
    Text(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 14.sp,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        ),
        modifier = modifier,
    )
}

/**
 * Builds the Compose [AnnotatedString] for a run of inline spans: emphasis maps
 * to span styles, inline code to a monospaced tinted run, and a link to a
 * `LinkAnnotation.Url` so the platform opens it.
 *
 * Internal rather than private so the styling is pinned by a unit test without
 * rendering — the same reason the old client exposed it.
 */
internal fun annotated(spans: List<InlineSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is InlineSpan.Text -> withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
                ),
            ) {
                append(span.text)
            }

            is InlineSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = PocketShellColors.Accent,
                    background = PocketShellColors.SurfaceElev,
                ),
            ) {
                append(span.text)
            }

            is InlineSpan.Link -> withLink(
                LinkAnnotation.Url(
                    url = normalizeUrl(span.url),
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = PocketShellColors.Accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(span.label.ifEmpty { span.url })
            }
        }
    }
}

/**
 * A bare `example.com` in Markdown is a web address, but a URI with no scheme
 * has no handler, so the tap would silently do nothing. Anything already
 * carrying a scheme is left alone.
 */
internal fun normalizeUrl(url: String): String =
    if (url.contains("://") || url.startsWith("mailto:")) url else "https://$url"
