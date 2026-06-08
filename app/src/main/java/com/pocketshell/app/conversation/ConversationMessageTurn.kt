package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Dense timeline message row for the Conversation tab.
 *
 * The scan state is one line: actor badge, truncated preview, copy affordance,
 * and a right-aligned timestamp. Long or multi-line messages expand in-place
 * to the full markdown-rendered body.
 */
@Composable
internal fun ConversationMessageTurn(
    event: ConversationEvent.Message,
    modifier: Modifier = Modifier,
    onRetrySend: (String) -> Unit = {},
    onLinkTap: ((ConversationLink) -> Unit)? = null,
    isExpanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    val isUser = event.role == ConversationRole.User
    val semantic = LocalPocketShellSemantic.current
    val roleColor = if (isUser) semantic.accent else semantic.agentAccent
    val roleLabel = if (isUser) "USER" else "ASSISTANT"
    val timestamp = remember(event.atMillis) { event.timelineTimestamp() }
    val canExpand = remember(event.text) {
        event.text.length > 96 || event.text.any { it == '\n' || it == '\r' }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ConversationTurnVerticalPadding)
            .let { base -> if (canExpand) base.clickable(onClick = onToggleExpanded) else base },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationTurnMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelineBadge(label = roleLabel, color = roleColor)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = PocketShellSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
            ) {
                if (event.streaming) {
                    StreamingBadge(roleColor = roleColor)
                }
                SendStateLabels(
                    event = event,
                    onRetrySend = onRetrySend,
                )
                Text(
                    text = event.text.lineSequence().firstOrNull().orEmpty(),
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyMono,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) {
                    Text(
                        text = if (isExpanded) "v" else "›",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                    )
                }
                ConversationCopyAction(
                    text = event.text,
                    testTag = CONVERSATION_COPY_TAG_PREFIX + event.id,
                )
                if (timestamp != null) {
                    Text(
                        text = timestamp,
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(ConversationTimestampWidth)
                            .testTag(CONVERSATION_TIMESTAMP_TAG_PREFIX + event.id),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (isExpanded && canExpand) {
            ExpandedMessageBody(
                text = event.text,
                onLinkTap = onLinkTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ConversationBodyIndent,
                        top = PocketShellSpacing.xs,
                        end = PocketShellSpacing.xs,
                    ),
            )
        }
    }
}

@Composable
private fun SendStateLabels(
    event: ConversationEvent.Message,
    onRetrySend: (String) -> Unit,
) {
    when (event.sendState) {
        MessageSendState.Pending -> StatusBadge(
            text = "sending…",
            color = LocalPocketShellSemantic.current.statusConnecting,
            modifier = Modifier.testTag(CONVERSATION_PENDING_TAG_PREFIX + event.id),
        )
        MessageSendState.Failed -> StatusBadge(
            text = "failed · retry",
            color = LocalPocketShellSemantic.current.statusError,
            modifier = Modifier
                .clickable { onRetrySend(event.id) }
                .testTag(CONVERSATION_RETRY_TAG_PREFIX + event.id),
        )
        MessageSendState.Confirmed -> Unit
    }
}

@Composable
private fun StatusBadge(
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
                shape = PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.22f),
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = PocketShellSpacing.xs, vertical = ConversationBadgeVerticalPadding),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ExpandedMessageBody(
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

@Composable
private fun TimelineBadge(
    label: String,
    color: Color,
) {
    Text(
        text = label,
        color = color,
        style = PocketShellType.labelMono,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(ConversationBadgeWidth)
            .background(
                color = color.copy(alpha = 0.12f),
                shape = PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.24f),
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = PocketShellSpacing.xs, vertical = ConversationBadgeVerticalPadding),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StreamingBadge(roleColor: Color) {
    Row(
        modifier = Modifier
            .background(
                color = roleColor.copy(alpha = 0.14f),
                shape = PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = roleColor.copy(alpha = 0.20f),
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = PocketShellSpacing.xs, vertical = ConversationBadgeVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "live",
            color = roleColor,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal val LocalConversationFontSizeSp: ProvidableCompositionLocal<Float> =
    staticCompositionLocalOf { PocketShellType.bodyDense.fontSize.value }

internal const val CONVERSATION_TIMESTAMP_TAG_PREFIX: String = "conversation-timestamp-"
internal const val CONVERSATION_PENDING_TAG_PREFIX: String = "conversation-pending-"
internal const val CONVERSATION_RETRY_TAG_PREFIX: String = "conversation-retry-"

private val ConversationTurnVerticalPadding = PocketShellSpacing.xs / 2
private val ConversationBadgeVerticalPadding = PocketShellSpacing.xs / 2
private val ConversationTurnMinHeight = PocketShellDensity.rowMinHeight
private val ConversationBadgeWidth = 82.dp
private val ConversationTimestampWidth = 84.dp
private val ConversationBodyIndent = ConversationBadgeWidth + PocketShellSpacing.sm
