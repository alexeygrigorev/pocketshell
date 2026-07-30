package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CONVERSATION_FULL_TEXT_DIALOG_TAG = "conversation-full-text-dialog"
internal const val CONVERSATION_FULL_TEXT_LIST_TAG = "conversation-full-text-list"
internal const val CONVERSATION_FULL_TEXT_CHUNK_TAG = "conversation-full-text-chunk"
internal const val CONVERSATION_FULL_TEXT_COPY_TAG = "conversation-full-text-copy"
internal const val CONVERSATION_FULL_TEXT_CLOSE_TAG = "conversation-full-text-close"
internal const val CONVERSATION_SHOW_ALL_TAG_PREFIX = "conversation-show-all-"

internal data class ConversationFullTextRequest(
    val title: String,
    val body: String,
    val clipboardLabel: String,
)

/**
 * Rows use the pane-level presenter when one is available. This keeps a full
 * reader alive even when opening it causes the source LazyColumn row to leave
 * composition. Standalone previews fall back to their own local dialog state.
 */
internal val LocalConversationFullTextPresenter:
    ProvidableCompositionLocal<((ConversationFullTextRequest) -> Unit)?> =
    staticCompositionLocalOf { null }

/**
 * Full-text reader for content whose transcript preview is capped by #605.
 *
 * The body is never handed to one Compose Text/Markdown node. A background
 * pass computes bounded UTF-16-safe ranges, then LazyColumn materializes only
 * the ranges in the current viewport. Copy deliberately retains the original
 * String as its exact payload.
 */
@Composable
internal fun ConversationFullTextDialog(
    title: String,
    body: String,
    clipboardLabel: String,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var chunks by remember(body) {
        mutableStateOf<ConversationFullTextChunks?>(null)
    }
    LaunchedEffect(body) {
        chunks = withContext(Dispatchers.Default) {
            conversationFullTextChunks(body)
        }
    }
    // Dialog content is hosted in a separate window composition. Read the
    // state in this parent composition so the completed background index
    // invalidates and rebuilds that window with the ready range list.
    val readyChunks = chunks

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag(CONVERSATION_FULL_TEXT_DIALOG_TAG),
            color = PocketShellColors.Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(PocketShellColors.Surface)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    FullTextAction(
                        label = "Copy",
                        testTag = CONVERSATION_FULL_TEXT_COPY_TAG,
                        onClick = {
                            // Same production clipboard route as the transcript
                            // header action; the payload stays byte-for-byte the
                            // String supplied to this reader.
                            copyConversationTextToClipboard(
                                context = context,
                                label = clipboardLabel,
                                text = body,
                            )
                        },
                    )
                    FullTextAction(
                        label = "Close",
                        testTag = CONVERSATION_FULL_TEXT_CLOSE_TAG,
                        onClick = onDismissRequest,
                    )
                }

                if (readyChunks == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(CONVERSATION_FULL_TEXT_LIST_TAG),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Preparing full text…",
                            color = PocketShellColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(CONVERSATION_FULL_TEXT_LIST_TAG),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        items(
                            count = readyChunks.size,
                            key = { index -> index },
                        ) { index ->
                            SelectionContainer {
                                Text(
                                    text = readyChunks[index],
                                    color = PocketShellColors.Text,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(CONVERSATION_FULL_TEXT_CHUNK_TAG),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationShowAllAction(
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FullTextAction(
        label = "Show all",
        testTag = testTag,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun FullTextAction(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Range-backed list: only the IntArray of boundaries is allocated eagerly.
 * Substrings are created on demand for LazyColumn's visible items.
 */
internal class ConversationFullTextChunks internal constructor(
    private val body: String,
    private val endOffsets: IntArray,
) {
    val size: Int
        get() = endOffsets.size

    operator fun get(index: Int): String {
        require(index in endOffsets.indices)
        val start = if (index == 0) 0 else endOffsets[index - 1]
        return body.substring(start, endOffsets[index])
    }
}

internal fun conversationFullTextChunks(body: String): ConversationFullTextChunks {
    if (body.isEmpty()) return ConversationFullTextChunks(body, IntArray(0))

    val ends = ArrayList<Int>((body.length / FULL_TEXT_CHUNK_CHAR_LIMIT) + 1)
    var start = 0
    while (start < body.length) {
        var end = start
        var newlineCount = 0
        while (
            end < body.length &&
            end - start < FULL_TEXT_CHUNK_CHAR_LIMIT &&
            newlineCount < FULL_TEXT_CHUNK_LINE_LIMIT
        ) {
            if (body[end] == '\n') newlineCount += 1
            end += 1
        }
        // Do not split a Unicode surrogate pair. Including the low surrogate
        // may make this one range one UTF-16 code unit over the nominal limit.
        if (
            end < body.length &&
            end > start &&
            body[end - 1].isHighSurrogate() &&
            body[end].isLowSurrogate()
        ) {
            end += 1
        }
        ends += end
        start = end
    }
    return ConversationFullTextChunks(body, ends.toIntArray())
}

internal const val FULL_TEXT_CHUNK_CHAR_LIMIT = 2_048
internal const val FULL_TEXT_CHUNK_LINE_LIMIT = 80
