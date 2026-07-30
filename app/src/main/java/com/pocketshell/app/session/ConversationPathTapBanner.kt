package com.pocketshell.app.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton

internal const val CONVERSATION_PATH_TAP_STATUS_TAG = "conversation:path-tap:status"
internal const val CONVERSATION_PATH_TAP_DISMISS_TAG = "conversation:path-tap:dismiss"

/** Visible, in-place feedback while a Conversation path tap is checked. */
@Composable
internal fun ConversationPathTapBanner(
    state: ConversationPathTapState,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is ConversationPathTapState.Checking &&
        state !is ConversationPathTapState.Failed
    ) {
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(CONVERSATION_PATH_TAP_STATUS_TAG),
    ) {
        when (state) {
            is ConversationPathTapState.Checking -> Column {
                Text(
                    text = "Checking ${state.resolvedPath}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                LoadingIndicator.Bar()
            }
            is ConversationPathTapState.Failed -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = "${state.reason} Tried: ${state.resolvedPath}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                PocketShellButton(
                    text = "Dismiss",
                    onClick = { onDismiss(state.requestId) },
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(CONVERSATION_PATH_TAP_DISMISS_TAG),
                )
            }
            else -> Unit
        }
    }
}
