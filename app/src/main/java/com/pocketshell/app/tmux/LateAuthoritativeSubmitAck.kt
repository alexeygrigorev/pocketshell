package com.pocketshell.app.tmux

/**
 * Resolves a previously ambiguous submit from a transcript turn that became
 * authoritative after the original bounded turnover wait expired.
 *
 * The durable pre-Enter baseline keeps this fail-closed: only a newly
 * confirmed exact-payload turn on the same recorded source can acknowledge the
 * row, and a successful acknowledgement consumes the volatile attempt ledger
 * without issuing another paste or Enter.
 */
internal fun OutboundDeliveryLedger.resolveLateAuthoritativeTranscriptAck(
    transcriptAuthority: AgentTranscriptAuthority,
    paneId: String,
    payload: String,
    sendToken: String,
    durableRow: DurableOutboundRowIdentity?,
): Boolean {
    if (durableRow == null || !hasSubmitAttempt(paneId, sendToken, durableRow)) return false
    val baseline = submitTranscriptBaseline(durableRow) ?: return false
    if (!transcriptAuthority.acknowledgedFromDurableBaseline(paneId, payload, baseline)) return false
    clear(paneId, sendToken)
    return true
}
