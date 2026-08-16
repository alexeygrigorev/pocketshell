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
import kotlinx.coroutines.delay

/**
 * Issue #2056: the late authority is asked again on a bounded foreground cadence
 * while a row is still awaiting it.
 *
 * #2037 evaluated the authority exactly once per queue/transcript snapshot change.
 * That is enough for a streaming agent (its transcript churns), but it is nothing
 * at all for the case this issue is about — a pane with no transcript, whose ONLY
 * authority is its own input surface. Its evidence arrives a second or two after
 * the bounded turnover wait gave up, and no snapshot changes in between, so the
 * row sat unresolved forever. The loop is foreground-only (D21), self-terminating
 * (a fixed poll bound), and stops the instant the row resolves or disappears.
 */
internal const val OUTBOUND_LATE_ACK_POLL_INTERVAL_MS: Long = 1_000L
internal const val OUTBOUND_LATE_ACK_MAX_POLLS: Int = 6

/**
 * Foreground-only bridge from an authoritative late submit proof to the exact
 * durable composer row whose bounded acknowledgement arrived late.
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
        reconcileLateOutboundAcks(
            rows = rows,
            binding = binding,
            resolveAuthoritativeAck = tmuxViewModel::resolveLateAuthoritativeOutboundAck,
            acknowledge = { resolved ->
                composerViewModel.acknowledgeLateOutboundDeliveries(
                    resolved,
                    onAcknowledged = tmuxViewModel::consumeLateAuthoritativeOutboundAck,
                )
            },
        )
    }
}

/**
 * Poll the late authority for every binding-matched row until one batch resolves
 * or the bounded budget is spent. Returns the acknowledged rows (empty when the
 * authority never spoke), so a caller/test reads the outcome without the composer.
 */
internal suspend fun reconcileLateOutboundAcks(
    rows: List<OutboundItem>,
    binding: TmuxOutboundQueueBinding,
    resolveAuthoritativeAck: suspend (OutboundItem) -> Boolean,
    acknowledge: (List<OutboundItem>) -> Unit = {},
    maxPolls: Int = OUTBOUND_LATE_ACK_MAX_POLLS,
    pollIntervalMs: Long = OUTBOUND_LATE_ACK_POLL_INTERVAL_MS,
): List<OutboundItem> {
    val candidates = rows.filter { it.matchesLateAckBinding(binding) }
    if (candidates.isEmpty()) return emptyList()
    repeat(maxPolls.coerceAtLeast(1)) { poll ->
        if (poll > 0) delay(pollIntervalMs)
        val resolved = resolveLateOutboundAcks(candidates, binding, resolveAuthoritativeAck)
        if (resolved.isNotEmpty()) {
            acknowledge(resolved)
            return resolved
        }
    }
    return emptyList()
}

/** One authority pass over [rows]: the binding-matched rows the authority confirmed. */
internal suspend fun resolveLateOutboundAcks(
    rows: List<OutboundItem>,
    binding: TmuxOutboundQueueBinding,
    resolveAuthoritativeAck: suspend (OutboundItem) -> Boolean,
): List<OutboundItem> = rows
    .filter { it.matchesLateAckBinding(binding) }
    .filter { resolveAuthoritativeAck(it) }

/** Reject stale/foreign pane, session and tmux-generation evidence before authority inspection. */
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

internal suspend fun resolveLateOutboundAck(
    ledger: OutboundDeliveryLedger,
    authority: AgentTranscriptAuthority,
    item: OutboundItem,
    capturePane: OutboundPaneCapture?,
): Boolean {
    // Issue #2124 hard-switch probe (legacy-stack entry point).
    OutboundLegacyStackProbe.lateAckReconciler.incrementAndGet()
    return ledger.resolveLateAuthoritativeSubmitAck(
        transcriptAuthority = authority,
        paneId = item.paneId,
        payload = appendAttachmentPaths(item.cleanText, item.attachments.map { it.remotePath }),
        sendToken = item.id,
        durableRow = DurableOutboundRowIdentity(item.sessionKey, item.id),
        capturePane = capturePane,
        consumeOnSuccess = false,
    )
}
