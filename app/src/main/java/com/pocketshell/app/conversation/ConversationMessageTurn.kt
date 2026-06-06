package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #260: dense terminal-flavored message turn. Role is carried by a
 * compact glyph + color/indent instead of a full-width labelled card.
 */
@Composable
internal fun ConversationMessageTurn(
    event: ConversationEvent.Message,
    modifier: Modifier = Modifier,
    // Issue #494: invoked when the user taps the retry affordance on a
    // failed optimistic user turn. The optimistic id is passed so the
    // ViewModel can drop the failed placeholder and re-send its text. No-op
    // by default for confirmed/pending turns and for screenshot callers.
    onRetrySend: (String) -> Unit = {},
) {
    val isUser = event.role == ConversationRole.User
    // Slice E1b (#539): source the role colour from the shared semantic
    // vocabulary (`LocalPocketShellSemantic`) instead of raw palette tokens —
    // user = accent, agent = agentAccent (purple). This is the same role
    // colour the shared `Badge` uses (`BadgeRole.Agent` -> `agentAccent`), so
    // the dense glyph indicator and any badge stay in lockstep without
    // re-encoding the colour per call site.
    val semantic = LocalPocketShellSemantic.current
    val roleColor = if (isUser) semantic.accent else semantic.agentAccent
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
            // Issue #493: shrink the turn's own vertical padding so the gap
            // between a turn and its neighbours (next turn, tool-call row,
            // SystemNote row) is tighter. This padding stacks on top of the
            // conversation LazyColumn's inter-item spacing, so trimming it
            // here is the per-turn lever that densifies the feed.
            .padding(start = startIndent, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Slice E1b (#539): the role indicator is the compact mono glyph
        // (#260/#493 dense terminal-flavoured turn — deliberately a single
        // glyph + colour/indent, NOT a full-width labelled pill). Its size now
        // rides the shared `bodyMono` rung instead of a raw `16.sp` literal,
        // and its colour comes from the semantic role vocabulary (above) — the
        // same `agentAccent`/`accent` the shared `Badge` paints.
        Text(
            text = glyph,
            color = roleColor,
            style = PocketShellType.bodyMono,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp),
            // Issue #493: tighten the intra-turn gap between the
            // timestamp/streaming row and the message body.
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val isPending = event.sendState == MessageSendState.Pending
            val isFailed = event.sendState == MessageSendState.Failed
            if (event.streaming || timestamp != null || isPending || isFailed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (event.streaming) {
                        StreamingBadge(roleColor = roleColor)
                    }
                    // Issue #494: optimistic send-state affordance. "sending…"
                    // until the transcript confirms the turn; "failed · retry"
                    // (tappable) if the send could not be delivered.
                    if (isPending) {
                        Text(
                            text = "sending…",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.labelMono,
                            modifier = Modifier.testTag(
                                CONVERSATION_PENDING_TAG_PREFIX + event.id,
                            ),
                        )
                    }
                    if (isFailed) {
                        Text(
                            text = "failed · retry",
                            color = PocketShellColors.Red,
                            style = PocketShellType.labelMono,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { onRetrySend(event.id) }
                                .testTag(CONVERSATION_RETRY_TAG_PREFIX + event.id),
                        )
                    }
                    if (timestamp != null) {
                        Text(
                            text = timestamp,
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.labelMono,
                            modifier = Modifier.testTag(CONVERSATION_TIMESTAMP_TAG_PREFIX + event.id),
                        )
                    }
                }
            }
            // Issue #493: render the message body at the Slice 0
            // `bodyDense` rung (13sp @ ~1.35 line height, #461 §3.5). The
            // compact `lineHeight` is provided via `LocalTextStyle` so the
            // markdown paragraphs pick up a tight, deterministic leading
            // instead of the looser default platform leading — that default
            // leading is what made the line spacing feel too large. The
            // monospace family is kept for the terminal-flavored turn look;
            // only the size and line height come from `bodyDense`.
            //
            // Issue #496: the user can scale the conversation body via
            // Settings → Terminal → "Conversation font size". The chosen
            // size flows in through [LocalConversationFontSizeSp]; the base
            // bodyDense line-height is scaled by the same ratio so the
            // ~1.35× leading is preserved at every size. The local defaults
            // to the bodyDense size (13sp), so an app that never provides it
            // — and the default-config user — renders exactly as before.
            val baseFontSize = PocketShellType.bodyDense.fontSize
            val baseLineHeight = PocketShellType.bodyDense.lineHeight
            val targetFontSp = LocalConversationFontSizeSp.current
            val scale = if (baseFontSize.value > 0f) targetFontSp / baseFontSize.value else 1f
            val scaledLineHeight = (baseLineHeight.value * scale).sp
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.merge(
                    TextStyle(lineHeight = scaledLineHeight),
                ),
            ) {
                MarkdownText(
                    text = event.text,
                    color = PocketShellColors.Text,
                    fontSize = targetFontSp.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Issue #496: the conversation message-body font size in scale-independent
 * pixels, supplied from the app root (which observes the persisted
 * `AppSettings.conversationFontSizeSp`). [ConversationMessageTurn] reads this
 * to scale the agent-conversation body text up or down.
 *
 * The default is the compact `bodyDense` rung (13sp, #493), so any composition
 * that does NOT provide it — including the density screenshot tests — renders
 * exactly as it did before this setting existed. The provider lives at the
 * activity root so both the plain-SSH and tmux session screens pick up the
 * same value without each having to thread it through.
 */
internal val LocalConversationFontSizeSp: ProvidableCompositionLocal<Float> =
    staticCompositionLocalOf { PocketShellType.bodyDense.fontSize.value }

/**
 * Issue #474: test tag prefix for the per-message timestamp label so
 * instrumentation/unit-screenshot checks can locate it deterministically.
 */
internal const val CONVERSATION_TIMESTAMP_TAG_PREFIX: String = "conversation-timestamp-"

/**
 * Issue #494: test tag prefix for the "sending…" pending indicator shown on
 * an optimistic user turn that has not yet been confirmed by the transcript.
 */
internal const val CONVERSATION_PENDING_TAG_PREFIX: String = "conversation-pending-"

/**
 * Issue #494: test tag prefix for the tappable "failed · retry" affordance
 * shown on an optimistic user turn whose send could not be delivered.
 */
internal const val CONVERSATION_RETRY_TAG_PREFIX: String = "conversation-retry-"

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
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.Medium,
        )
    }
}
