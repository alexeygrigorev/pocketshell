package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Chat-style message block for the Conversation tab (#561).
 *
 * Each event renders as a full message block with:
 * - Header row: role label + timestamp aligned right
 * - Body: full multi-line content with pre-wrap
 * - Tool call cards inline within assistant messages
 *
 * This replaces the old dense timeline row (single-line preview, expand-on-click)
 * with the chat transcript paradigm from the mockup.
 */
@Composable
internal fun ConversationMessageTurn(
    event: ConversationEvent.Message,
    modifier: Modifier = Modifier,
    onRetrySend: (String) -> Unit = {},
    onLinkTap: ((ConversationLink) -> Unit)? = null,
) {
    val isUser = event.role == ConversationRole.User
    val semantic = LocalPocketShellSemantic.current
    val roleColor = if (isUser) semantic.accent else semantic.agentAccent
    val roleLabel = if (isUser) "USER" else "ASSISTANT"
    val timestamp = remember(event.atMillis) { event.timelineTimestamp() }
    val timeLabel = if (event.streaming) "· streaming" else timestamp?.let { "· $it" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MessageBlockBottomPadding),
    ) {
        // Message header: role label + streaming badge + send state + timestamp + copy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MessageHeadBottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = roleLabel,
                color = roleColor,
                style = MessageHeadStyle,
                fontWeight = FontWeight.Bold,
                letterSpacing = MessageHeadLetterSpacing,
            )
            if (event.streaming) {
                Spacer(modifier = Modifier.width(MessageHeadGap))
                StreamingDot(roleColor = roleColor)
            }
            SendStateLabels(
                event = event,
                onRetrySend = onRetrySend,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (timeLabel != null) {
                Text(
                    text = timeLabel,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.testTag(CONVERSATION_TIMESTAMP_TAG_PREFIX + event.id),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConversationCopyAction(
                text = event.text,
                testTag = CONVERSATION_COPY_TAG_PREFIX + event.id,
                clipboardLabel = if (isUser) "user message" else "assistant message",
            )
        }

        // Message body — full content rendered inline
        MessageBody(
            text = event.text,
            onLinkTap = onLinkTap,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SendStateLabels(
    event: ConversationEvent.Message,
    onRetrySend: (String) -> Unit,
) {
    when (event.sendState) {
        MessageSendState.Pending -> SendStateBadge(
            text = "sending…",
            color = LocalPocketShellSemantic.current.statusConnecting,
            modifier = Modifier
                .padding(start = MessageHeadGap)
                .testTag(CONVERSATION_PENDING_TAG_PREFIX + event.id),
        )
        MessageSendState.Failed -> SendStateBadge(
            text = "failed · retry",
            color = LocalPocketShellSemantic.current.statusError,
            modifier = Modifier
                .padding(start = MessageHeadGap)
                .clickable { onRetrySend(event.id) }
                .testTag(CONVERSATION_RETRY_TAG_PREFIX + event.id),
        )
        MessageSendState.Confirmed -> Unit
    }
}

@Composable
private fun SendStateBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = PocketShellType.labelMono,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(
                color = color.copy(alpha = 0.10f),
                shape = RoundedCornerShape(MessageBadgeRadius),
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.22f),
                shape = RoundedCornerShape(MessageBadgeRadius),
            )
            .padding(horizontal = PocketShellSpacing.sm, vertical = MessageBadgeVerticalPadding),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StreamingDot(roleColor: Color) {
    Text(
        text = "live",
        color = roleColor,
        style = PocketShellType.labelMono,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(
                color = roleColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(MessageBadgeRadius),
            )
            .border(
                width = 1.dp,
                color = roleColor.copy(alpha = 0.20f),
                shape = RoundedCornerShape(MessageBadgeRadius),
            )
            .padding(horizontal = PocketShellSpacing.sm, vertical = MessageBadgeVerticalPadding),
        maxLines = 1,
    )
}

/**
 * Full message body with multi-line content, inline code, and word-break.
 * Per mockup: 14sp, line-height 1.55, color Text, word-break.
 */
@Composable
private fun MessageBody(
    text: String,
    onLinkTap: ((ConversationLink) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val displayBody = remember(text) { conversationExpandedMessageDisplayBody(text) }
    val baseFontSize = PocketShellType.bodyDense.fontSize
    val baseLineHeight = PocketShellType.bodyDense.lineHeight
    val targetFontSp = LocalConversationFontSizeSp.current
    val scale = if (baseFontSize.value > 0f) targetFontSp / baseFontSize.value else 1f
    val scaledLineHeight = (baseLineHeight.value * scale).sp

    Column(modifier = modifier) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.merge(
                TextStyle(lineHeight = scaledLineHeight),
            ),
        ) {
            MarkdownText(
                text = displayBody.text,
                color = PocketShellColors.Text,
                fontSize = targetFontSp.sp,
                fontFamily = FontFamily.Monospace,
                onLinkTap = onLinkTap,
            )
        }
    }
}

/**
 * Inline tool call card rendered within an assistant message body.
 * Per mockup: surface background, 1dp border-soft, 10dp radius, flex row
 * with chevron + tool name + command preview.
 */
@Composable
internal fun InlineToolCallCard(
    toolName: String,
    command: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier = modifier
        .fillMaxWidth()
        .background(
            color = PocketShellColors.Surface,
            shape = RoundedCornerShape(ToolCallCardRadius),
        )
        .border(
            width = 1.dp,
            color = PocketShellColors.BorderSoft,
            shape = RoundedCornerShape(ToolCallCardRadius),
        )
        .let { base ->
            if (onClick != null) base.clickable(onClick = onClick) else base
        }
        .padding(horizontal = ToolCallCardHPadding, vertical = ToolCallCardVPadding)

    Row(
        modifier = cardModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallCardItemGap),
    ) {
        Text(
            text = "›",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
            fontSize = 14.sp,
        )
        Text(
            text = toolName,
            color = PocketShellColors.Accent,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = command,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A streaming cursor indicator for use at the end of a streaming message.
 */
@Composable
internal fun StreamingCursor(
    modifier: Modifier = Modifier,
) {
    Text(
        text = "▌",
        color = PocketShellColors.Purple,
        fontSize = 14.sp,
        modifier = modifier,
    )
}

internal val LocalConversationFontSizeSp: ProvidableCompositionLocal<Float> =
    staticCompositionLocalOf { PocketShellType.bodyDense.fontSize.value }

internal const val CONVERSATION_TIMESTAMP_TAG_PREFIX: String = "conversation-timestamp-"
internal const val CONVERSATION_PENDING_TAG_PREFIX: String = "conversation-pending-"
internal const val CONVERSATION_RETRY_TAG_PREFIX: String = "conversation-retry-"

// --- Design tokens from mockup CSS ---

private val MessageBlockBottomPadding = 22.dp
private val MessageHeadBottomPadding = 8.dp
private val MessageHeadGap = 8.dp
private val MessageHeadLetterSpacing = 0.8.sp
private val MessageHeadStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
)
private val MessageBadgeRadius = 6.dp
private val MessageBadgeVerticalPadding = 2.dp

// Tool call card tokens (from .tool-call CSS)
private val ToolCallCardRadius = 10.dp
private val ToolCallCardHPadding = 12.dp
private val ToolCallCardVPadding = 10.dp
private val ToolCallCardItemGap = 8.dp
