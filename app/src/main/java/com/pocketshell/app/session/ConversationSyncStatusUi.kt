package com.pocketshell.app.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.theme.PocketShellColors

@Composable
internal fun ConversationSyncStatusRow(
    syncStatus: AgentConversationSyncStatus,
    // Issue #2185: required. A default of `true` silently disables the
    // Live-over-empty fence at any future call site that forgets the
    // argument — and the call site still looks correct.
    hasEvents: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    // Issue #2159: the row NEVER renders the raw status. `Live` over an empty
    // feed is the reported contradiction, so the displayed status is a total
    // function of (status, hasEvents) — no ViewModel path can put the green dot
    // next to "No conversation events yet." again.
    val resolved = resolvedConversationSyncStatus(syncStatus, hasEvents)
    val (label, color) = when (resolved) {
        AgentConversationSyncStatus.Live -> conversationSyncStatusLabel(resolved) to PocketShellColors.Green
        AgentConversationSyncStatus.Stale -> conversationSyncStatusLabel(resolved) to PocketShellColors.Amber
        AgentConversationSyncStatus.LogUnavailable -> conversationSyncStatusLabel(resolved) to PocketShellColors.Red
        AgentConversationSyncStatus.Retrying -> conversationSyncStatusLabel(resolved) to PocketShellColors.Amber
        AgentConversationSyncStatus.NoMessages -> conversationSyncStatusLabel(resolved) to PocketShellColors.Amber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color = color, shape = RoundedCornerShape(50)),
        )
        Text(
            text = "Conversation: $label",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
        if (onRetry != null && resolved.canRetryAgentStream) {
            PocketShellButton(
                text = "Retry",
                onClick = onRetry,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(CONVERSATION_SYNC_RETRY_TAG),
            )
        }
    }
}

internal fun conversationSyncStatusLabel(syncStatus: AgentConversationSyncStatus): String =
    when (syncStatus) {
        AgentConversationSyncStatus.Live -> "Live"
        AgentConversationSyncStatus.Stale -> "Stale"
        AgentConversationSyncStatus.LogUnavailable -> "Log unavailable"
        AgentConversationSyncStatus.Retrying -> "Retrying"
        AgentConversationSyncStatus.NoMessages -> "No messages yet"
    }

/**
 * Issue #2159: the status the user is actually shown, given the feed it sits
 * above.
 *
 * The maintainer photographed a green `Conversation: Live` directly above
 * "No conversation events yet." while the same session's Terminal showed a live
 * Codex mid-turn. `Live` asserts the feed is healthy and current; over an empty
 * pane that assertion is simply false, and it is the difference between "we are
 * still resolving / there is nothing yet" and "everything is fine". The two must
 * not be able to disagree, so the render path collapses that combination to the
 * honest, retryable [AgentConversationSyncStatus.NoMessages].
 *
 * The ViewModel also stamps [AgentConversationSyncStatus.NoMessages] directly
 * when a load completes with no events — this is the second fence, so a status
 * written by ANY path (a seeded placeholder, a restore, a future call site)
 * cannot reintroduce the contradiction at the pixel level.
 */
internal fun resolvedConversationSyncStatus(
    syncStatus: AgentConversationSyncStatus,
    hasEvents: Boolean,
): AgentConversationSyncStatus =
    if (!hasEvents && syncStatus == AgentConversationSyncStatus.Live) {
        AgentConversationSyncStatus.NoMessages
    } else {
        syncStatus
    }

internal val AgentConversationSyncStatus.canRetryAgentStream: Boolean
    get() = this == AgentConversationSyncStatus.Stale ||
        this == AgentConversationSyncStatus.LogUnavailable ||
        // Issue #2159: an empty feed is retryable — re-resolving the source and
        // re-reading the window is exactly the actionable next step for a pane
        // that is bound but showing nothing.
        this == AgentConversationSyncStatus.NoMessages

internal const val CONVERSATION_SYNC_RETRY_TAG: String = "conversation_sync_retry"
