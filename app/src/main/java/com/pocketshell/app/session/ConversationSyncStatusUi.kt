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
    onRetry: (() -> Unit)? = null,
) {
    val (label, color) = when (syncStatus) {
        AgentConversationSyncStatus.Live -> conversationSyncStatusLabel(syncStatus) to PocketShellColors.Green
        AgentConversationSyncStatus.Stale -> conversationSyncStatusLabel(syncStatus) to PocketShellColors.Amber
        AgentConversationSyncStatus.LogUnavailable -> conversationSyncStatusLabel(syncStatus) to PocketShellColors.Red
        AgentConversationSyncStatus.Retrying -> conversationSyncStatusLabel(syncStatus) to PocketShellColors.Amber
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
        if (onRetry != null && syncStatus.canRetryAgentStream) {
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
    }

internal val AgentConversationSyncStatus.canRetryAgentStream: Boolean
    get() = this == AgentConversationSyncStatus.Stale ||
        this == AgentConversationSyncStatus.LogUnavailable

internal const val CONVERSATION_SYNC_RETRY_TAG: String = "conversation_sync_retry"
