package com.pocketshell.app.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

internal const val CONVERSATION_COPY_TAG_PREFIX: String = "conversation-copy-"
internal const val CONVERSATION_TOOL_COPY_TAG_PREFIX: String = "conversation-tool-copy-"

@Composable
internal fun ConversationCopyAction(
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
    clipboardLabel: String = "conversation message",
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .clickable(
                enabled = text.isNotBlank(),
                role = Role.Button,
                onClick = {
                    copyConversationTextToClipboard(
                        context = context,
                        label = clipboardLabel,
                        text = text,
                    )
                },
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Copy",
            color = PocketShellColors.Accent,
            fontSize = 11.sp,
        )
    }
}

internal fun copyConversationTextToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
