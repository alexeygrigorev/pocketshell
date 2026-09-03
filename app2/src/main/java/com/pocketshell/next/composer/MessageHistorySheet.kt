package com.pocketshell.next.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test tags for the sent-message history. */
const val COMPOSER_HISTORY_SHEET_TAG: String = "composer-history-sheet"
const val COMPOSER_HISTORY_EMPTY_TAG: String = "composer-history-empty"

fun composerHistoryRowTag(id: Long): String = "composer-history-row:$id"

/**
 * The per-session sent-message history (rewrite task P-1, maintainer request
 * 2026-09-03).
 *
 * ## What this is NOT
 *
 * It is not a queue and not a delivery surface. There is no "retry", no "send
 * all", no per-item state and no badge counting anything outstanding — every
 * one of those existed in the deleted outbound-queue UI and every one of them
 * implied the app would eventually deliver something on the user's behalf.
 * Tapping a row puts its text back in the draft; the user sends it, or does
 * not. That is the entire interaction.
 *
 * The `NOT DELIVERED` pill on a row is a record of what happened when the
 * message was composed, so the user can see at a glance which one to re-send.
 * Nothing in the app acts on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageHistorySheet(
    messages: List<SentMessage>,
    onPick: (SentMessage) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(COMPOSER_HISTORY_SHEET_TAG),
        ) {
            SheetHeader(
                title = "Recent messages",
                subtitle = "Tap one to put it back in the composer.",
                onClose = onDismiss,
            )

            if (messages.isEmpty()) {
                // A plain line rather than the shared EmptyState: that component
                // fills its parent, which inside a wrap-content sheet would make
                // an empty history taller than a full one.
                Text(
                    text = "Nothing sent from this session yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PocketShellColors.TextSecondary,
                    modifier = Modifier
                        .padding(vertical = PocketShellSpacing.lg)
                        .testTag(COMPOSER_HISTORY_EMPTY_TAG),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                items(messages, key = { it.id }) { message ->
                    ListRow(
                        title = message.label,
                        subtitle = formatSentAt(message.sentAtMs),
                        onClick = { onPick(message) },
                        trailing = if (message.delivered) {
                            null
                        } else {
                            { Pill(label = "not delivered", kind = PillKind.Warn) }
                        },
                        modifier = Modifier.testTag(composerHistoryRowTag(message.id)),
                    )
                }
            }
        }
    }
}

/**
 * Pinned to [Locale.US] and a machine-ish `MMM d HH:mm`, matching the rest of
 * app2's timestamps: a history list is scanned for "was that the one from this
 * morning", which a stable 24-hour format answers and a locale-shifting one
 * makes ambiguous.
 */
internal fun formatSentAt(epochMs: Long): String =
    SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(epochMs))

private val LIST_MAX_HEIGHT = 420.dp
