package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Labelled raw text block used for expanded tool calls, tool results, and
 * structured system-note content. The explicit Copy action is reliable for the
 * whole block, while SelectionContainer gives users the normal Android
 * long-press selection path for partial text moves.
 */
@Composable
internal fun ConversationTextSection(
    label: String,
    body: String,
    copyTestTag: String,
    modifier: Modifier = Modifier,
    clipboardLabel: String = "conversation tool $label",
) {
    if (body.isEmpty()) return
    val displayBody = conversationTextSectionDisplayBody(body)
    val tooLong = displayBody.wasTruncated
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = PocketShellColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            ConversationCopyAction(
                text = body,
                testTag = copyTestTag,
                clipboardLabel = clipboardLabel,
            )
        }
        val container = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.TermBg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .let { base ->
                if (tooLong) base.heightIn(max = 240.dp) else base
            }
        val scrollState = rememberScrollState()
        val selectableModifier = if (tooLong) {
            container.verticalScroll(scrollState)
        } else {
            container
        }
        SelectionContainer(modifier = selectableModifier) {
            Text(
                text = displayBody.text,
                color = PocketShellColors.TermText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

internal data class ConversationTextSectionDisplayBody(
    val text: String,
    val wasTruncated: Boolean,
)

/**
 * Compose still measures the whole `Text` inside a bounded verticalScroll. For
 * very large tool outputs that can make transcript interaction or tab switching
 * stall the main thread (#605). Keep Copy wired to the full body, but cap the
 * rendered preview to the same "too long" boundary the section already used.
 */
internal fun conversationTextSectionDisplayBody(body: String): ConversationTextSectionDisplayBody {
    val lineLimit = 200
    val charLimit = 5_000
    val lineCount = body.count { it == '\n' } + 1
    if (lineCount <= lineLimit && body.length <= charLimit) {
        return ConversationTextSectionDisplayBody(text = body, wasTruncated = false)
    }

    var newlineCount = 0
    var endExclusive = 0
    while (endExclusive < body.length && endExclusive < charLimit && newlineCount < lineLimit) {
        if (body[endExclusive] == '\n') newlineCount += 1
        endExclusive += 1
    }
    val preview = body.substring(0, endExclusive).trimEnd()
    return ConversationTextSectionDisplayBody(
        text = "$preview\n\n[Output truncated in view. Copy for full text.]",
        wasTruncated = true,
    )
}
