package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Issue #260: dense terminal-flavored message turn. Role is carried by a
 * compact glyph + color/indent instead of a full-width labelled card.
 */
@Composable
internal fun ConversationMessageTurn(
    event: ConversationEvent.Message,
    modifier: Modifier = Modifier,
) {
    val isUser = event.role == ConversationRole.User
    val roleColor = if (isUser) PocketShellColors.Accent else PocketShellColors.Purple
    val glyph = if (isUser) "›" else "A"
    val startIndent = if (isUser) 0.dp else 10.dp

    // Issue #474: compact per-message timestamp so the user can tell WHEN
    // the message was sent (e.g. when the agent reported it finished)
    // without reading terminal logs. Null when the transcript entry had no
    // timestamp — render nothing rather than an empty chip.
    val timestamp = remember(event.atMillis) { ConversationTimeFormat.format(event.atMillis) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = glyph,
            color = roleColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.width(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (event.streaming || timestamp != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (event.streaming) {
                        StreamingBadge(roleColor = roleColor)
                    }
                    if (timestamp != null) {
                        Text(
                            text = timestamp,
                            color = PocketShellColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.testTag(CONVERSATION_TIMESTAMP_TAG_PREFIX + event.id),
                        )
                    }
                }
            }
            MarkdownText(
                text = event.text,
                color = PocketShellColors.Text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Issue #474: test tag prefix for the per-message timestamp label so
 * instrumentation/unit-screenshot checks can locate it deterministically.
 */
internal const val CONVERSATION_TIMESTAMP_TAG_PREFIX: String = "conversation-timestamp-"

@Composable
private fun StreamingBadge(roleColor: Color) {
    Row(
        modifier = Modifier
            .background(
                color = roleColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "live",
            color = roleColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
