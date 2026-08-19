package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun PromptComposerQueueBanners(
    pendingItems: List<PendingTranscriptionItem>,
    retryingIds: Set<String>,
    pendingListExpanded: Boolean,
    onTogglePendingList: () -> Unit,
    onRetryPending: (String) -> Unit,
    onDiscardPending: (String) -> Unit,
    onSavePendingAsAudio: (String) -> Unit,
    outboundQueueItems: List<OutboundItem>,
    outboundRetryingIds: Set<String>,
    outboundRetryBlockedByHealthyOwner: Boolean,
    outboundWireWritable: Boolean,
    connectionDegraded: Boolean,
    outboundQueueExpanded: Boolean,
    onToggleOutboundQueue: () -> Unit,
    onDeleteOutboundItem: (String) -> Unit,
    onRetryOutboundItem: (String) -> Unit,
    onResendAllOutbound: () -> Unit,
) {
    if (pendingItems.isNotEmpty()) {
        PendingTranscriptionsBanner(
            items = pendingItems,
            retryingIds = retryingIds,
            expanded = pendingListExpanded,
            onToggle = onTogglePendingList,
            onRetry = onRetryPending,
            onDiscard = onDiscardPending,
            onSaveAsAudio = onSavePendingAsAudio,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (outboundQueueItems.isNotEmpty()) {
        OutboundQueueBanner(
            items = outboundQueueItems,
            retryingIds = outboundRetryingIds,
            retryBlockedByHealthyOwner = outboundRetryBlockedByHealthyOwner,
            wireWritable = outboundWireWritable,
            connectionDegraded = connectionDegraded,
            expanded = outboundQueueExpanded,
            onToggle = onToggleOutboundQueue,
            onDelete = onDeleteOutboundItem,
            onRetry = onRetryOutboundItem,
            onResendAll = onResendAllOutbound,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Issue #900: committed outbound prompts for the current session. This is a
 * foreground visibility/action surface only; durable retry behavior lands in
 * the owning VM/store slice.
 */
@Composable
private fun OutboundQueueBanner(
    items: List<OutboundItem>,
    retryingIds: Set<String>,
    retryBlockedByHealthyOwner: Boolean,
    wireWritable: Boolean,
    connectionDegraded: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: (String) -> Unit,
    onRetry: (String) -> Unit,
    onResendAll: () -> Unit,
) {
    val collapsedRetryItem = if (expanded) null else retryableOutboundQueueItem(items)
    val queueProgress by AttachmentUploadProgressPort.queueProgress.collectAsState()
    val uploadingProgress = items.singleOrNull()
        ?.takeIf { item -> item.state == OutboundState.Uploading }
        ?.let { item -> queueProgress[AttachmentUploadProgressKey(item.sessionKey, item.id)] }
    val summary = outboundQueueSummary(items, connectionDegraded, uploadingProgress)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .testTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide queued prompts" else "Show queued prompts",
                    onClick = onToggle,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.primary,
                        color = if (summary.attention) PocketShellColors.Amber else PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(COMPOSER_OUTBOUND_QUEUE_STATUS_TAG),
                    )
                    if (summary.attentionSuffix != null) {
                        Text(
                            text = " · ${summary.attentionSuffix}",
                            color = PocketShellColors.Amber,
                            style = PocketShellType.bodyDense,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.testTag(COMPOSER_OUTBOUND_QUEUE_FAILURE_SEGMENT_TAG),
                        )
                    }
                    if (items.size == 1 && summary.preview != null) {
                        Text(
                            text = " · ${summary.preview}",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.bodyDense,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (items.size > 1 && summary.preview != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary.preview,
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (collapsedRetryItem != null) {
                val retryAction = outboundRetryActionState(
                    collapsedRetryItem,
                    retryingIds,
                    retryBlockedByHealthyOwner,
                    wireWritable,
                )
                Spacer(modifier = Modifier.width(8.dp))
                PendingActionButton(
                    label = retryAction.label,
                    primary = true,
                    enabled = retryAction.enabled,
                    onClick = { onRetry(collapsedRetryItem.id) },
                    modifier = Modifier.testTag(
                        composerOutboundQueueRetryTestTag(collapsedRetryItem.id),
                    ),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            DisclosureIcon(
                expanded = expanded,
                tint = PocketShellColors.TextSecondary,
            )
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(PocketShellColors.BorderSoft),
            )
            val resendableCount = items.count {
                it.state == OutboundState.Queued || it.state == OutboundState.Failed
            }
            if (resendableCount >= 2) {
                val resendEnabled = wireWritable && !retryBlockedByHealthyOwner && retryingIds.isEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(ComposerQueueButtonShape)
                        .background(PocketShellColors.Accent, ComposerQueueButtonShape)
                        .clickable(
                            enabled = resendEnabled,
                            role = Role.Button,
                            onClick = onResendAll,
                        )
                        .padding(vertical = 10.dp)
                        .testTag(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            resendEnabled -> "Resend all ($resendableCount)"
                            !wireWritable -> "Waiting for connection"
                            else -> "Waiting for current send"
                        },
                        color = PocketShellColors.OnAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(PocketShellColors.BorderSoft),
                )
            }
            items.forEach { item ->
                OutboundQueueRow(
                    item = item,
                    retryAction = outboundRetryActionState(
                        item,
                        retryingIds,
                        retryBlockedByHealthyOwner,
                        wireWritable,
                    ),
                    onDelete = { onDelete(item.id) },
                    onRetry = { onRetry(item.id) },
                )
            }
        }
    }
}

@Composable
private fun OutboundQueueRow(
    item: OutboundItem,
    retryAction: OutboundRetryActionState,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val statusLayoutRegistration = PromptComposerQueueStatusLayoutTestObserver.observerForComposition()
    val statusLayoutBinding = remember(statusLayoutRegistration, item.id) {
        statusLayoutRegistration?.bind(item.id)
    }
    DisposableEffect(statusLayoutBinding) {
        onDispose { statusLayoutBinding?.close() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(composerOutboundQueueItemRowTestTag(item.id))
            .then(
                if (statusLayoutBinding == null) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { coordinates ->
                        statusLayoutBinding.recordRow(coordinates)
                    }
                },
            ),
    ) {
        Text(
            text = formatRelativeTimestamp(item.createdAtMs, System.currentTimeMillis()),
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .testTag(composerOutboundQueueStatusTestTag(item.id))
                .then(
                    if (statusLayoutBinding == null) {
                        Modifier
                    } else {
                        Modifier.onGloballyPositioned { coordinates ->
                            statusLayoutBinding.recordStatus(coordinates)
                        }
                    },
                ),
        ) {
            val queueProgress by AttachmentUploadProgressPort.queueProgress.collectAsState()
            val uploadProgress = queueProgress[AttachmentUploadProgressKey(item.sessionKey, item.id)]
                .takeIf { item.state == OutboundState.Uploading }
            if (uploadProgress != null && retryAction.status == null) {
                AttachmentTransferProgressBlock(
                    progress = uploadProgress,
                    textColor = PocketShellColors.TextSecondary,
                )
            } else {
                Text(
                    text = retryAction.status ?: outboundQueueStateLabel(item, uploadProgress),
                    color = if (item.state == OutboundState.Failed) {
                        PocketShellColors.Amber
                    } else {
                        PocketShellColors.TextSecondary
                    },
                    style = PocketShellType.bodyDense,
                )
            }
        }
        if (item.cleanText.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.cleanText,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = outboundAttachmentCountLabel(item.attachments.size),
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
        if (item.state == OutboundState.Queued || item.state == OutboundState.Failed) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                PendingActionButton(
                    label = "Delete",
                    primary = false,
                    onClick = onDelete,
                    modifier = Modifier.testTag(composerOutboundQueueDeleteTestTag(item.id)),
                )
                PendingActionButton(
                    label = retryAction.label,
                    primary = true,
                    enabled = retryAction.enabled,
                    onClick = onRetry,
                    modifier = Modifier.testTag(composerOutboundQueueRetryTestTag(item.id)),
                )
            }
        }
    }
}

/**
 * Issue #180: banner + expandable list rendered above the mic row when
 * the failed/offline-queued transcription list is non-empty.
 */
@Composable
private fun PendingTranscriptionsBanner(
    items: List<PendingTranscriptionItem>,
    retryingIds: Set<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onSaveAsAudio: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.AccentSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Accent.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .testTag(COMPOSER_PENDING_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(COMPOSER_PENDING_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pendingSummaryHeadline(items),
                    color = PocketShellColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val subline = pendingSummarySubline(items)
                if (subline.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subline,
                        color = PocketShellColors.Accent.copy(alpha = 0.85f),
                        style = PocketShellType.bodyDense,
                    )
                }
            }
            DisclosureIcon(
                expanded = expanded,
                tint = PocketShellColors.Accent,
            )
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(PocketShellColors.Accent.copy(alpha = 0.25f)),
            )
            items.forEach { item ->
                PendingTranscriptionRow(
                    item = item,
                    retrying = item.id in retryingIds,
                    onRetry = { onRetry(item.id) },
                    onDiscard = { onDiscard(item.id) },
                    onSaveAsAudio = { onSaveAsAudio(item.id) },
                )
            }
        }
    }
}

@Composable
private fun PendingTranscriptionRow(
    item: PendingTranscriptionItem,
    retrying: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onSaveAsAudio: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(composerPendingItemRowTestTag(item.id)),
    ) {
        Text(
            text = formatRelativeTimestamp(item.recordingTimestampMs, System.currentTimeMillis()),
            color = PocketShellColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val statusText = when {
            retrying -> PENDING_RETRYING_MESSAGE
            item.isWaitingForNetwork -> PendingTranscriptionItem.NETWORK_WAITING_MESSAGE
            item.lastErrorMessage != null -> item.lastErrorMessage
            else -> "Queued — tap retry to send"
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = statusText,
            color = PocketShellColors.Accent.copy(alpha = 0.85f),
            style = PocketShellType.bodyDense,
        )
        if (item.retryCount > 0 && !item.atRetryCap) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Attempt ${item.retryCount + 1} of " +
                    "${PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS}",
                color = PocketShellColors.Accent.copy(alpha = 0.7f),
                style = PocketShellType.bodyDense,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (!item.atRetryCap) {
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
                )
                PendingActionButton(
                    label = if (retrying) "Retrying…" else "Retry",
                    primary = true,
                    enabled = !retrying,
                    onClick = onRetry,
                    modifier = Modifier.testTag(composerPendingRetryTestTag(item.id)),
                )
            } else {
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
                )
                PendingActionButton(
                    label = "Save audio",
                    primary = true,
                    onClick = onSaveAsAudio,
                    modifier = Modifier.testTag(composerPendingSaveTestTag(item.id)),
                )
            }
        }
    }
}

@Composable
private fun PendingActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor = if (primary) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val borderColor = if (primary) PocketShellColors.Accent else PocketShellColors.Border
    val contentColor = if (primary) PocketShellColors.OnAccent else PocketShellColors.Text
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .background(
                color = containerColor.copy(alpha = alpha),
                shape = ComposerQueueButtonShape,
            )
            .border(
                width = 1.dp,
                color = borderColor.copy(alpha = alpha),
                shape = ComposerQueueButtonShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = contentColor.copy(alpha = alpha),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun pendingSummaryHeadline(items: List<PendingTranscriptionItem>): String = when (items.size) {
    0 -> ""
    1 -> "1 pending transcription"
    else -> "${items.size} pending transcriptions"
}

internal fun pendingSummarySubline(items: List<PendingTranscriptionItem>): String {
    val first = items.firstOrNull() ?: return ""
    return when {
        first.isWaitingForNetwork -> "Waiting for network — tap to view"
        first.atRetryCap -> "Tap to save or discard"
        first.lastErrorMessage != null -> "Tap to retry"
        else -> "Tap to retry"
    }
}

/**
 * Issue #2056: a delivery whose outcome could not be proven must be STATED as
 * unproven. "Queued — sending next" was an outright lie for these rows: nothing was
 * being sent, nothing ever would be, and the payload had usually already run — so
 * the user could not tell which visibly-queued prompts had already landed, and a
 * Retry risked duplicating one the agent had already accepted.
 */
internal const val OUTBOUND_DELIVERY_UNCONFIRMED_SUMMARY: String = "Sent — delivery unconfirmed"

internal const val OUTBOUND_DELIVERY_UNCONFIRMED_MESSAGE: String =
    "Sent, but delivery could not be confirmed — check the terminal before retrying"

internal data class OutboundQueueSummary(
    val primary: String,
    val preview: String? = null,
    val attention: Boolean = false,
    val attentionSuffix: String? = null,
)

internal fun outboundQueueSummary(
    items: List<OutboundItem>,
    connectionDegraded: Boolean,
    uploadProgress: AttachmentTransferProgress? = null,
): OutboundQueueSummary {
    val oldest = items.minByOrNull { it.createdAtMs } ?: return OutboundQueueSummary("")
    val previewText = oldest.cleanText.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?: outboundAttachmentCountLabel(oldest.attachments.size).takeIf { oldest.attachments.isNotEmpty() }
    val preview = previewText?.let { "“$it”" }
    if (items.size == 1) {
        if (oldest.isComposerQueueDeliveryUnconfirmed()) {
            return OutboundQueueSummary(OUTBOUND_DELIVERY_UNCONFIRMED_SUMMARY, preview, attention = true)
        }
        val primary = when (oldest.state) {
            OutboundState.Queued -> if (connectionDegraded) {
                "Queued — will send on reconnect"
            } else {
                "Queued — sending next"
            }
            OutboundState.Uploading -> outboundUploadingLabel(uploadProgress)
            OutboundState.InFlight -> "Sending"
            OutboundState.Delivered -> "Delivered"
            OutboundState.Failed -> "Failed — tap Retry"
        }
        return OutboundQueueSummary(primary, preview, oldest.state == OutboundState.Failed)
    }

    val unconfirmedCount = items.count { it.isComposerQueueDeliveryUnconfirmed() }
    val failedCount = items.count { it.state == OutboundState.Failed }
    if (unconfirmedCount > 0) {
        return OutboundQueueSummary(
            primary = "${items.size} queued",
            preview = preview,
            attention = true,
            attentionSuffix = "$unconfirmedCount unconfirmed",
        )
    }
    val primary = when {
        failedCount > 0 -> "${items.size} queued"
        connectionDegraded -> "${items.size} queued · will send on reconnect"
        oldest.state == OutboundState.Uploading -> "${items.size} queued · uploading oldest first"
        else -> "${items.size} queued · sending oldest first"
    }
    return OutboundQueueSummary(
        primary = primary,
        preview = preview,
        attentionSuffix = "$failedCount failed".takeIf { failedCount > 0 },
    )
}

internal fun outboundQueueStateLabel(
    item: OutboundItem,
    uploadProgress: AttachmentTransferProgress? = null,
): String = if (
    item.isComposerQueueDeliveryUnconfirmed()
) {
    OUTBOUND_DELIVERY_UNCONFIRMED_MESSAGE
} else when (item.state) {
    OutboundState.Queued -> PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE
    OutboundState.Uploading -> outboundUploadingLabel(uploadProgress)
    OutboundState.InFlight -> "Sending"
    OutboundState.Delivered -> "Delivered"
    OutboundState.Failed -> item.lastError?.takeIf { it.isNotBlank() }?.let { "Failed — $it" } ?: "Failed"
}

internal data class OutboundRetryActionState(
    val label: String,
    val enabled: Boolean,
    val status: String? = null,
)

/** Reopened #1602: a rendered Retry must state whether it can act right now. */
internal fun outboundRetryActionState(
    item: OutboundItem,
    retryingIds: Set<String>,
    blockedByHealthyOwner: Boolean,
    wireWritable: Boolean,
): OutboundRetryActionState = when {
    !item.isComposerQueueRetryable() -> OutboundRetryActionState(label = "Retry", enabled = false)
    !wireWritable -> OutboundRetryActionState(
        label = "Offline",
        enabled = false,
        status = "Waiting — connection is offline",
    )
    item.id in retryingIds -> OutboundRetryActionState(
        label = "Retrying…",
        enabled = false,
        status = "Retrying — starting delivery",
    )
    blockedByHealthyOwner -> OutboundRetryActionState(
        label = "Waiting…",
        enabled = false,
        status = "Waiting — another prompt is still sending",
    )
    else -> OutboundRetryActionState(label = "Retry", enabled = true)
}

internal fun outboundAttachmentCountLabel(count: Int): String =
    "$count attachment${if (count == 1) "" else "s"}"

internal fun retryableOutboundQueueItem(items: List<OutboundItem>): OutboundItem? =
    items
        .filter { it.state == OutboundState.Queued || it.state == OutboundState.Failed }
        .minByOrNull { it.createdAtMs }

internal fun isComposerResendMode(
    draft: String,
    hasAttachments: Boolean,
    retryableItem: OutboundItem?,
    sendInFlight: Boolean,
): Boolean = draft.isEmpty() && !hasAttachments && retryableItem != null && !sendInFlight

internal fun showComposerSendProgress(
    sendInFlight: Boolean,
    items: List<OutboundItem>,
): Boolean = sendInFlight && items.none {
    it.state == OutboundState.Uploading || it.state == OutboundState.InFlight
}

internal fun formatRelativeTimestamp(timestampMs: Long, nowMs: Long): String {
    val delta = (nowMs - timestampMs).coerceAtLeast(0L)
    return when {
        delta < 30_000L -> "Just now"
        delta < 60_000L -> "${delta / 1_000L} seconds ago"
        delta < 60L * 60_000L -> {
            val minutes = delta / 60_000L
            if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        delta < 24L * 60L * 60_000L -> {
            val hours = delta / (60L * 60_000L)
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        else -> {
            val days = delta / (24L * 60L * 60_000L)
            if (days == 1L) "1 day ago" else "$days days ago"
        }
    }
}

// Genuine sub-ladder "micro role" geometry: the outbound-queue action buttons
// use a 6dp radius from the design-system micro role range.
private val ComposerQueueButtonRadius = 6.dp
private val ComposerQueueButtonShape = RoundedCornerShape(ComposerQueueButtonRadius)

internal const val COMPOSER_PENDING_BANNER_TAG = "prompt-composer-pending-banner"
internal const val COMPOSER_PENDING_TOGGLE_TAG = "prompt-composer-pending-toggle"
internal const val COMPOSER_OUTBOUND_QUEUE_BANNER_TAG = "prompt-composer-outbound-queue"
internal const val COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG = "prompt-composer-outbound-queue-toggle"
internal const val COMPOSER_OUTBOUND_QUEUE_STATUS_TAG = "prompt-composer-outbound-queue-status"
internal const val COMPOSER_OUTBOUND_QUEUE_FAILURE_SEGMENT_TAG =
    "prompt-composer-outbound-queue-failure-segment"
internal const val COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG = "prompt-composer-outbound-queue-resend-all"
internal const val PENDING_RETRYING_MESSAGE = "Retrying…"

internal fun composerPendingItemRowTestTag(id: String): String =
    "prompt-composer-pending-row:$id"

internal fun composerPendingRetryTestTag(id: String): String =
    "prompt-composer-pending-retry:$id"

internal fun composerPendingDiscardTestTag(id: String): String =
    "prompt-composer-pending-discard:$id"

internal fun composerPendingSaveTestTag(id: String): String =
    "prompt-composer-pending-save:$id"

internal fun composerOutboundQueueItemRowTestTag(id: String): String =
    "prompt-composer-outbound-queue-row:$id"

internal fun composerOutboundQueueDeleteTestTag(id: String): String =
    "prompt-composer-outbound-queue-delete:$id"

internal fun composerOutboundQueueRetryTestTag(id: String): String =
    "prompt-composer-outbound-queue-retry:$id"

internal fun composerOutboundQueueStatusTestTag(id: String): String =
    "prompt-composer-outbound-queue-status:$id"

/**
 * Default-inert layout probe for connected/component tests of the queue status row.
 *
 * A registration owns row bindings captured by one composition. Each binding retains
 * the independently replaceable, latest exact row and status [LayoutCoordinates], so
 * tests read both handles in one window coordinate space only after scrolling and
 * settling instead of retaining callback-time snapshots. Registration and row-binding
 * identity checks reject late callbacks, stale closes, and mixed-generation pairs.
 */
internal object PromptComposerQueueStatusLayoutTestObserver {
    private val nextGeneration = AtomicLong(0L)
    private val active = AtomicReference<Registration?>(null)

    /**
     * Deliberately does not use boundsInWindow(): that API intersects a node with
     * ancestor clipping and can collapse a still-attached, visible descendant to
     * Rect.Zero inside the full composer sheet. Both handles are measured from
     * their raw origin in the same window space plus their own measured size.
     */
    private fun rawWindowBounds(coordinates: LayoutCoordinates): Rect? = runCatching {
        val position = coordinates.positionInWindow()
        val measuredSize = coordinates.size
        val left = position.x
        val top = position.y
        val right = left + measuredSize.width.toFloat()
        val bottom = top + measuredSize.height.toFloat()
        if (
            left.isFinite() &&
            top.isFinite() &&
            right.isFinite() &&
            bottom.isFinite()
        ) {
            Rect(left = left, top = top, right = right, bottom = bottom)
        } else {
            null
        }
    }.getOrNull()

    private fun isAttachedSafely(coordinates: LayoutCoordinates?): Boolean =
        coordinates != null && runCatching { coordinates.isAttached }.getOrDefault(false)

    internal fun install(): Registration {
        val registration = Registration(nextGeneration.incrementAndGet())
        check(active.compareAndSet(null, registration)) {
            "queue status layout observer already installed"
        }
        return registration
    }

    internal fun observerForComposition(): Registration? = active.get()

    internal class Registration internal constructor(
        internal val generation: Long,
    ) : AutoCloseable {
        private val nextBindingToken = AtomicLong(0L)
        private val bindings = ConcurrentHashMap<String, RowBinding>()

        internal fun bind(rowId: String): RowBinding? {
            if (active.get() !== this) return null
            val binding = RowBinding(
                rowId = rowId,
                token = nextBindingToken.incrementAndGet(),
            )
            bindings[rowId] = binding
            if (active.get() !== this) {
                bindings.remove(rowId, binding)
                return null
            }
            return binding
        }

        internal fun observedRowIds(): Set<String> =
            if (active.get() === this) {
                bindings.entries
                    .asSequence()
                    .filter { (rowId, binding) ->
                        binding.hasRecordedCoordinatePair() && bindings[rowId] === binding
                    }
                    .map { it.key }
                    .toSet()
            } else {
                emptySet()
            }

        internal fun currentCoordinates(rowId: String): CoordinatePair? =
            if (active.get() === this) bindings[rowId]?.currentCoordinates() else null

        internal fun currentWindowGeometry(rowId: String): WindowGeometrySnapshot =
            bindings[rowId]?.currentWindowGeometry()
                ?: WindowGeometrySnapshot.missingBinding(
                    rowId = rowId,
                    generation = generation,
                    ownerCurrent = active.get() === this,
                )

        private fun isCurrent(binding: RowBinding): Boolean =
            active.get() === this && bindings[binding.rowId] === binding

        private fun release(binding: RowBinding) {
            bindings.remove(binding.rowId, binding)
        }

        override fun close() {
            if (active.compareAndSet(this, null)) {
                bindings.values.forEach(RowBinding::detach)
                bindings.clear()
            }
        }

        internal inner class RowBinding internal constructor(
            internal val rowId: String,
            internal val token: Long,
        ) : AutoCloseable {
            private val rowCoordinates = AtomicReference<LayoutCoordinates?>(null)
            private val statusCoordinates = AtomicReference<LayoutCoordinates?>(null)

            internal fun recordRow(current: LayoutCoordinates) {
                if (isCurrent(this)) {
                    rowCoordinates.set(current)
                }
            }

            internal fun recordStatus(current: LayoutCoordinates) {
                if (isCurrent(this)) {
                    statusCoordinates.set(current)
                }
            }

            internal fun hasRecordedCoordinatePair(): Boolean =
                isCurrent(this) && rowCoordinates.get() != null && statusCoordinates.get() != null

            internal fun currentCoordinates(): CoordinatePair? {
                if (!isCurrent(this)) return null
                val row = rowCoordinates.get() ?: return null
                val status = statusCoordinates.get() ?: return null
                if (!row.isAttached || !status.isAttached) return null
                return CoordinatePair(row = row, status = status).takeIf {
                    isCurrent(this) &&
                        rowCoordinates.get() === row &&
                        statusCoordinates.get() === status &&
                        row.isAttached &&
                        status.isAttached
                }
            }

            internal fun currentWindowGeometry(): WindowGeometrySnapshot {
                val ownerCurrentAtStart = active.get() === this@Registration
                val bindingCurrentAtStart = isCurrent(this)
                val row = rowCoordinates.get()
                val status = statusCoordinates.get()
                val handlesCurrentAtStart =
                    rowCoordinates.get() === row && statusCoordinates.get() === status
                val rowAttachedAtStart = isAttachedSafely(row)
                val statusAttachedAtStart = isAttachedSafely(status)
                val pairReadableAtStart =
                    ownerCurrentAtStart &&
                        bindingCurrentAtStart &&
                        handlesCurrentAtStart &&
                        rowAttachedAtStart &&
                        statusAttachedAtStart
                val rowBounds = if (pairReadableAtStart && row != null) {
                    rawWindowBounds(row)
                } else {
                    null
                }
                val statusBounds = if (pairReadableAtStart && status != null) {
                    rawWindowBounds(status)
                } else {
                    null
                }
                val ownerCurrentAtEnd = active.get() === this@Registration
                val bindingCurrentAtEnd = isCurrent(this)
                val handlesCurrentAtEnd =
                    rowCoordinates.get() === row && statusCoordinates.get() === status
                val rowAttachedAtEnd = isAttachedSafely(row)
                val statusAttachedAtEnd = isAttachedSafely(status)
                return WindowGeometrySnapshot(
                    rowId = rowId,
                    generation = generation,
                    bindingToken = token,
                    ownerCurrent = ownerCurrentAtStart && ownerCurrentAtEnd,
                    bindingCurrent = bindingCurrentAtStart && bindingCurrentAtEnd,
                    rowHandlePresent = row != null,
                    statusHandlePresent = status != null,
                    rowAttached = rowAttachedAtStart && rowAttachedAtEnd,
                    statusAttached = statusAttachedAtStart && statusAttachedAtEnd,
                    handlesStillCurrent = handlesCurrentAtStart && handlesCurrentAtEnd,
                    rowBounds = rowBounds,
                    statusBounds = statusBounds,
                )
            }

            internal fun detach() {
                rowCoordinates.set(null)
                statusCoordinates.set(null)
            }

            override fun close() {
                release(this)
                detach()
            }
        }
    }

    internal data class CoordinatePair(
        val row: LayoutCoordinates,
        val status: LayoutCoordinates,
    )

    internal data class WindowGeometrySnapshot(
        val rowId: String,
        val generation: Long,
        val bindingToken: Long?,
        val ownerCurrent: Boolean,
        val bindingCurrent: Boolean,
        val rowHandlePresent: Boolean,
        val statusHandlePresent: Boolean,
        val rowAttached: Boolean,
        val statusAttached: Boolean,
        val handlesStillCurrent: Boolean,
        val rowBounds: Rect?,
        val statusBounds: Rect?,
    ) {
        internal fun failureTerms(): List<String> = buildList {
            if (!ownerCurrent) add("owner_not_current")
            if (!bindingCurrent) add("binding_not_current")
            if (!rowHandlePresent) add("row_handle_missing")
            if (!statusHandlePresent) add("status_handle_missing")
            if (rowHandlePresent && !rowAttached) add("row_detached")
            if (statusHandlePresent && !statusAttached) add("status_detached")
            if (!handlesStillCurrent) add("handle_pair_replaced_during_read")
            val row = rowBounds
            val status = statusBounds
            if (rowAttached && row == null) add("row_bounds_unavailable")
            if (statusAttached && status == null) add("status_bounds_unavailable")
            if (row != null && (row.width <= 0f || row.height <= 0f)) add("row_bounds_empty")
            if (status != null && (status.width <= 0f || status.height <= 0f)) add("status_bounds_empty")
            if (row != null && status != null) {
                if (status.left != row.left) add("status_left_not_full_width")
                if (status.right != row.right) add("status_right_not_full_width")
                if (status.left < row.left) add("status_left_outside_row")
                if (status.top < row.top) add("status_top_outside_row")
                if (status.right > row.right) add("status_right_outside_row")
                if (status.bottom > row.bottom) add("status_bottom_outside_row")
            }
        }

        internal fun isCurrentAttachedNonEmptyContainedFullWidthPair(): Boolean =
            failureTerms().isEmpty()

        internal fun diagnosticSummary(): String =
            "rowId=$rowId generation=$generation bindingToken=$bindingToken " +
                "failures=${failureTerms()} ownerCurrent=$ownerCurrent " +
                "bindingCurrent=$bindingCurrent handlesStillCurrent=$handlesStillCurrent " +
                "rowPresent=$rowHandlePresent rowAttached=$rowAttached rowBounds=$rowBounds " +
                "statusPresent=$statusHandlePresent statusAttached=$statusAttached " +
                "statusBounds=$statusBounds"

        internal companion object {
            fun missingBinding(
                rowId: String,
                generation: Long,
                ownerCurrent: Boolean,
            ): WindowGeometrySnapshot = WindowGeometrySnapshot(
                rowId = rowId,
                generation = generation,
                bindingToken = null,
                ownerCurrent = ownerCurrent,
                bindingCurrent = false,
                rowHandlePresent = false,
                statusHandlePresent = false,
                rowAttached = false,
                statusAttached = false,
                handlesStillCurrent = false,
                rowBounds = null,
                statusBounds = null,
            )
        }
    }
}
