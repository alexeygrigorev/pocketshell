package com.pocketshell.app.composer

import com.pocketshell.app.composer.PromptComposerViewModel.AttachmentUploadState
import com.pocketshell.app.composer.PromptComposerViewModel.SendRequest
import com.pocketshell.app.diagnostics.DiagnosticEvents
import kotlinx.coroutines.flow.update

/**
 * Issue #891 / #1621: the watchdog-expiry effects of [PromptComposerViewModel],
 * split out of the god-object VM (D28 / file-size hygiene ratchet) into
 * cohesive same-module `internal` extensions.
 *
 * The two timers are deliberately separate owners: [onSendWatchdogExpired] is
 * the DELIVERY escape (the drain owns the wire), while
 * [onHandoffWatchdogExpired] is the write-ahead COMMIT escape and never touches
 * delivery state. Keeping them here keeps that ownership boundary readable.
 */

/**
 * Issue #891: the overall-send watchdog fired — the send has been in-flight
 * past [PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS] without resolving.
 * This is the wedged-send escape: route to the retryable failed-send state,
 * preserving the CURRENT draft + staged attachments so Retry re-sends without
 * losing the message. A no-op if the composer already left the in-flight state
 * (a benign race where the resolution callback won but the watchdog cancel had
 * not yet been observed).
 */
internal fun PromptComposerViewModel.onSendWatchdogExpired() {
    if (!_uiState.value.sendInFlight) return
    // Issue #971: the editable composer is cleared on handoff, so prefer the
    // captured in-flight request to restore the EXACT prompt + tiles. Fall
    // back to the live state only for the upload-await wedge, where the send
    // went in-flight before the request was built and the draft is still on
    // screen.
    val request = inFlightSendRequest ?: run {
        val state = _uiState.value
        val composed = appendAttachmentPaths(state.draft, state.attachments.map { it.remotePath })
        SendRequest(
            text = composed,
            withEnter = false,
            cleanDraft = state.draft,
            attachments = state.attachments,
        )
    }
    DiagnosticEvents.record(
        "action",
        "composer_send_watchdog_timeout",
        "attachmentCount" to request.attachments.size,
    )
    // Issue #971/#987: a wedged send is a connection problem, not a permanent
    // rejection — defer it to the queue so it auto-retries on reconnect
    // (Option A). When the request owns a durable queue row this keeps it
    // queued + clears the composer; the upload-await wedge (no row) falls
    // back to the composer-restore path inside markOutboundSendDeferred with
    // the watchdog-specific copy so the typed prompt is not lost and the user
    // sees that the send timed out.
    requeueStaleOutboundInFlight(staleAfterMs = 0L)
    markOutboundSendDeferred(request, noRowFallbackMessage = PromptComposerViewModel.SEND_TIMEOUT_MESSAGE)
}

/**
 * Issue #1621: the write-ahead handoff commit wedged. A handoff timeout NEVER
 * owns delivery — it releases only the commit latch and, at most, the exact
 * attachment job this handoff captured. A later composition's attachment job
 * (prompt C staged while B was still committing) must survive untouched.
 */
internal fun PromptComposerViewModel.onHandoffWatchdogExpired() {
    if (!outboundHandoffInProgress) return
    val ownedAttachment = outboundHandoffAttachmentJob
    outboundHandoffJob?.cancel()
    outboundHandoffJob = null
    outboundHandoffAttachmentJob = null
    val resetsAttachment = ownedAttachment != null && attachmentJob === ownedAttachment
    if (resetsAttachment) {
        ownedAttachment.cancel()
        attachmentJob = null
    }
    outboundHandoffInProgress = false
    _uiState.update {
        it.copy(
            outboundHandoffInProgress = false,
            attachmentUpload = if (resetsAttachment) AttachmentUploadState.Idle else it.attachmentUpload,
            error = PromptComposerViewModel.SEND_TIMEOUT_MESSAGE,
        )
    }
}
