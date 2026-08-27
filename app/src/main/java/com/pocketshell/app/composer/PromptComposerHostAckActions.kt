package com.pocketshell.app.composer

import com.pocketshell.app.diagnostics.DiagnosticEvents
import kotlinx.coroutines.flow.update

/** Issue #2240: acknowledge an unknown row locally without claiming delivery. */
public fun PromptComposerViewModel.markOutboundHandled(id: String) {
    val item = outboundQueueStore.item(id) ?: return
    if (!item.isComposerQueueHostAckUnknown()) return
    DiagnosticEvents.record("action", "composer_host_ack_marked_handled", "rowId" to id)
    // Use the existing lifecycle/physical-owner disposal path. A direct store
    // removal here would race an in-flight drain or strand sidecar cleanup.
    discardOutboundItemThroughLifecycleCoordinator(id)
}

/** Standalone composer fallback: explain why a pane check needs a host screen. */
public fun PromptComposerViewModel.showHostAckCheckUnavailable(id: String) {
    val item = outboundQueueStore.item(id) ?: return
    if (!item.isComposerQueueHostAckUnknown()) return
    _uiState.update { current ->
        current.copy(error = "Open this session to check the pane without sending again.")
    }
    DiagnosticEvents.record("action", "composer_host_ack_check_unavailable", "rowId" to id)
}

/**
 * Issue #2240: explicit duplicate-risk action for a HostAck unknown row.
 * This is intentionally not an alias for ordinary Retry: it clears the typed
 * outcome only after confirmation and carries the one-shot host opt-in.
 */
public fun PromptComposerViewModel.resendUnknownOutboundItem(id: String): Boolean {
    val item = outboundQueueStore.item(id) ?: return false
    if (!item.isComposerQueueHostAckUnknown()) return false
    if (!isSendTransportWritable()) {
        _uiState.update { current ->
            current.copy(error = "Waiting for connection — the prompt may already have landed.")
        }
        return false
    }
    val rearmed = outboundQueueStore.requeueForExplicitHostAckResend(id) ?: return false
    rearmed.recordQueueRowState("UnknownMayHaveLanded", "Queued", "explicit_resend_confirmed")
    markOutboundRetrying(id)
    val accepted = dispatchOutboundItem(id, resendInterrupted = true)
    if (!accepted) {
        // A rejected handoff must restore attention state; it must not turn into
        // an ordinary automatic send after the duplicate-risk confirmation.
        outboundQueueStore.markHostAckUnknown(id)
        clearOutboundRetrying(id)
    }
    refreshOutboundQueueItemsFor(rearmed.sessionKey)
    return accepted
}
