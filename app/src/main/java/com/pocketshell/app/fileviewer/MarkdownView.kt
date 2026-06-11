package com.pocketshell.app.fileviewer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

const val FILE_VIEWER_MARKDOWN_TAG = "fileViewerMarkdown"

/** The clickable-text URL annotation tag for Markdown links. */
private const val MD_URL_TAG = "md_url"

/**
 * Renders parsed [MarkdownBlock]s into a themed Compose column (issue #696).
 *
 * Headers scale by level, fenced code blocks are monospaced on a tinted
 * surface with their own horizontal scroll, lists are bulleted/numbered with
 * indentation, links are tappable (open in the browser), and bold/italic/
 * strikethrough/inline-code carry through. Block layout always wraps to the
 * viewport; only fenced code keeps a horizontal scroll so a wide code line is
 * still readable. Styling is derived from [PocketShellColors] so it matches the
 * rest of the app.
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
            .testTag(FILE_VIEWER_MARKDOWN_TAG),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.spans)
                is MarkdownBlock.CodeBlock -> CodeBlock(block)
                is MarkdownBlock.ListBlock -> ListBlock(block)
                is MarkdownBlock.BlockQuote -> BlockQuoteBlock(block)
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
    LinkableText(
        text = annotated(spans),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun CodeBlock(block: MarkdownBlock.CodeBlock) {
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(6.dp),
            ),
    ) {
        Text(
            text = block.content,
            color = PocketShellColors.TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier
                .horizontalScroll(hScroll)
                .padding(12.dp),
        )
    }
}

@Composable
private fun ListBlock(block: MarkdownBlock.ListBlock) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        block.items.forEachIndexed { index, item ->
            val marker = if (block.ordered) {
                "${item.ordinal ?: (index + 1)}. "
            } else {
                "• "
            }
            LinkableText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = PocketShellColors.TextSecondary)) {
                        append(marker)
                    }
                    append(annotated(item.spans))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (12 * (item.indentLevel + 1)).dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun BlockQuoteBlock(block: MarkdownBlock.BlockQuote) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        LinkableText(
            text = annotated(block.spans),
            color = PocketShellColors.TextSecondary,
            italic = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
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

/**
 * A [Text]/[ClickableText] that opens any `md_url`-annotated link in the
 * browser when tapped. When the annotated string carries no link annotation a
 * plain [Text] is used (cheaper, fully selectable-friendly upstream).
 */
@Composable
private fun LinkableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = PocketShellColors.Text,
    italic: Boolean = false,
) {
    val context = LocalContext.current
    val hasLink = remember(text) {
        text.getStringAnnotations(MD_URL_TAG, 0, text.length).isNotEmpty()
    }
    val style = TextStyle(
        color = color,
        fontSize = 14.sp,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )
    if (hasLink) {
        ClickableText(
            text = text,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                text.getStringAnnotations(MD_URL_TAG, offset, offset).firstOrNull()?.let { ann ->
                    openLink(context, ann.item)
                }
            },
        )
    } else {
        Text(text = text, style = style, modifier = modifier)
    }
}

private fun openLink(context: android.content.Context, url: String) {
    val normalized = if (url.contains("://") || url.startsWith("mailto:")) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Couldn't open link", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Build a Compose [AnnotatedString] from inline spans: emphasis maps to
 * span styles, inline code to a monospaced tinted run, and links to an accent
 * underlined run carrying a [MD_URL_TAG] string annotation so a tap can resolve
 * the URL. Visible-for-test so the styling is pinned without rendering.
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
            is InlineSpan.Link -> {
                pushStringAnnotation(tag = MD_URL_TAG, annotation = span.url)
                withStyle(
                    SpanStyle(
                        color = PocketShellColors.Accent,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(span.label.ifEmpty { span.url })
                }
                pop()
            }
        }
    }
}
