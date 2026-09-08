package com.pocketshell.next.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable tags for the usable transfer history surface (design-kit frame 64). */
const val TRANSFERS_SCREEN_TAG: String = FILE_EXPLORER_TRANSFERS_TAG
const val TRANSFERS_IN_PROGRESS_TAG: String = "file-transfers-in-progress"
const val TRANSFERS_COMPLETED_TAG: String = "file-transfers-completed"
const val TRANSFERS_FAILED_TAG: String = "file-transfers-failed"
const val TRANSFERS_EMPTY_TAG: String = "file-transfers-empty"

fun transferRowTag(id: Long): String = "file-transfer-$id"

fun transferRetryTag(id: Long): String = "file-transfer-retry-$id"

/**
 * A full page rather than a transient banner so a user can inspect a failed
 * transfer after returning from the Android document picker.
 */
@Composable
fun TransfersScreen(
    state: FileExplorerUiState,
    onBack: () -> Unit,
    onRetry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TRANSFERS_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Transfers",
            subtitle = "Remote file transfers",
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )

        if (state.transferRecords.isEmpty()) {
            EmptyState(
                title = "No transfers",
                description = "Uploads and downloads will appear here.",
                modifier = Modifier
                    .weight(1f)
                    .testTag(TRANSFERS_EMPTY_TAG),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
            ) {
                val inProgress = state.transferRecords.filter {
                    it.status == FileTransferStatus.Running
                }
                val completed = state.transferRecords.filter {
                    it.status == FileTransferStatus.Completed
                }
                val failed = state.transferRecords.filter {
                    it.status == FileTransferStatus.Failed
                }
                if (inProgress.isNotEmpty()) {
                    item {
                        SectionHeader(
                            label = "In progress",
                            modifier = Modifier
                                .padding(horizontal = PocketShellSpacing.md)
                                .testTag(TRANSFERS_IN_PROGRESS_TAG),
                        )
                    }
                    items(inProgress, key = { it.id }) { record ->
                        TransferRow(record = record, onRetry = onRetry)
                    }
                }
                if (completed.isNotEmpty()) {
                    item {
                        SectionHeader(
                            label = "Completed",
                            modifier = Modifier
                                .padding(horizontal = PocketShellSpacing.md)
                                .testTag(TRANSFERS_COMPLETED_TAG),
                        )
                    }
                    items(completed, key = { it.id }) { record ->
                        TransferRow(record = record, onRetry = onRetry)
                    }
                }
                if (failed.isNotEmpty()) {
                    item {
                        SectionHeader(
                            label = "Failed",
                            modifier = Modifier
                                .padding(horizontal = PocketShellSpacing.md)
                                .testTag(TRANSFERS_FAILED_TAG),
                        )
                    }
                    items(failed, key = { it.id }) { record ->
                        TransferRow(record = record, onRetry = onRetry)
                    }
                }
                item {
                    Text(
                        text = "Source and destination stay visible so a failed transfer can be retried deliberately.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(
                            horizontal = PocketShellSpacing.md,
                            vertical = PocketShellSpacing.md,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    record: FileTransferRecord,
    onRetry: (Long) -> Unit,
) {
    val direction = if (record.uploading) "Uploading to" else "Downloading from"
    val subtitle = "$direction ${if (record.uploading) record.destination else record.source}"
    val statusText = when (record.status) {
        FileTransferStatus.Running -> {
            val total = record.totalBytes
            if (total != null && total > 0L) {
                "${record.bytesTransferred.coerceAtMost(total) * 100 / total}% · " +
                    "${formatSize(record.bytesTransferred)} of ${formatSize(total)}"
            } else {
                "Working… · ${formatSize(record.bytesTransferred)}"
            }
        }

        FileTransferStatus.Completed -> record.message ?: "Completed"
        FileTransferStatus.Failed -> record.message ?: "Transfer failed"
    }
    ListRow(
        title = record.name,
        subtitle = "$subtitle · $statusText",
        leading = { FileTypeIcon(iconClass = fileIconClassForName(record.name)) },
        trailing = {
            if (record.status == FileTransferStatus.Failed) {
                PocketShellButton(
                    text = "Retry",
                    onClick = { onRetry(record.id) },
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(transferRetryTag(record.id)),
                )
            }
        },
        modifier = Modifier.testTag(transferRowTag(record.id)),
    )
    if (record.status == FileTransferStatus.Running) {
        val total = record.totalBytes
        if (total != null && total > 0L) {
            ProgressBar(
                progress = record.bytesTransferred.toFloat() / total.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellSpacing.md)
                    .testTag("${transferRowTag(record.id)}-progress"),
            )
        } else {
            Banner(
                text = "Waiting for the host to report progress",
                role = BannerRole.Info,
                maxLines = 2,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .testTag("${transferRowTag(record.id)}-indeterminate"),
            )
        }
    }
}
