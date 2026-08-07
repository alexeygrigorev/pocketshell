package com.pocketshell.app.tmux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.acknowledgeLateOutboundDeliveries
import com.pocketshell.app.composer.appendAttachmentPaths

/**
 * Foreground-only bridge from authoritative transcript turnover to the exact
 * durable composer row whose bounded submit acknowledgement arrived late.
 */
@Composable
internal fun TmuxOutboundLateAckEffect(
    tmuxViewModel: TmuxSessionViewModel,
    composerViewModel: PromptComposerViewModel,
    binding: TmuxOutboundQueueBinding,
) {
    val conversations by tmuxViewModel.agentConversations.collectAsState()
    val rows by composerViewModel.outboundQueueItems.collectAsState()
    LaunchedEffect(conversations, rows, binding) {
        val resolved = resolveLateOutboundAcks(
            rows = rows,
            binding = binding,
            resolveAuthoritativeAck = tmuxViewModel::resolveLateAuthoritativeOutboundAck,
        )
        if (resolved.isNotEmpty()) {
            composerViewModel.acknowledgeLateOutboundDeliveries(
                resolved,
                onAcknowledged = tmuxViewModel::consumeLateAuthoritativeOutboundAck,
            )
        }
    }
}

internal fun resolveLateOutboundAcks(
    rows: List<OutboundItem>,
    binding: TmuxOutboundQueueBinding,
    resolveAuthoritativeAck: (OutboundItem) -> Boolean,
): List<OutboundItem> = rows
    .asSequence()
    .filter { it.matchesLateAckBinding(binding) }
    .filter(resolveAuthoritativeAck)
    .toList()

/** Reject stale/foreign pane, session and tmux-generation evidence before transcript inspection. */
internal fun OutboundItem.matchesLateAckBinding(binding: TmuxOutboundQueueBinding): Boolean =
    (state == OutboundState.Queued || state == OutboundState.Failed) &&
        wireSubmitAttempted &&
        sendKey.isNotBlank() &&
        wireAttemptGeneration > 0 &&
        sessionKey == binding.targetKey &&
        paneId in binding.generationPaneIds &&
        tmuxSessionId != null &&
        tmuxSessionCreated != null &&
        tmuxSessionId == binding.tmuxSessionId &&
        tmuxSessionCreated == binding.sessionCreated

internal fun resolveLateOutboundAck(
    ledger: OutboundDeliveryLedger,
    authority: AgentTranscriptAuthority,
    item: OutboundItem,
): Boolean = ledger.resolveLateAuthoritativeTranscriptAck(
    transcriptAuthority = authority,
    paneId = item.paneId,
    payload = appendAttachmentPaths(item.cleanText, item.attachments.map { it.remotePath }),
    sendToken = item.id,
    durableRow = DurableOutboundRowIdentity(item.sessionKey, item.id),
    consumeOnSuccess = false,
)
