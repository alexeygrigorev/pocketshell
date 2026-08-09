package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents

/**
 * Resolves a previously ambiguous submit whose proof became available after the
 * original bounded turnover wait expired.
 *
 * ## Why this is an authority CHAIN (issue #2056, hard cut of #2037's single arm)
 *
 * #2037 resolved a stuck row from ONE authority: a newly confirmed transcript turn
 * against the durable pre-Enter baseline. That baseline is `null` whenever the pane
 * had no live transcript source at submit time — a just-relaunched agent, a
 * node-wrapped Claude the detector cannot bind, a catalog TUI control
 * ([AgentSubmitDeliveryProof.TmuxEnterAccepted]) — and a `null` baseline made the
 * bridge return `false` FOREVER. The payload had been delivered, so nothing else
 * would ever move the row either: every retry hits `verifyBeforeAgentResend`'s
 * submit-attempt short circuit and reports `Unknown`. That is the maintainer's
 * "delivered, but the composer queue is never cleared".
 *
 * The cure is to make the terminal acknowledgement **route-complete**: when the
 * transcript cannot speak, ask the PANE. A row only reaches this state after the
 * paste was ack-proven on the pane and Enter was written, so "the payload is no
 * longer pending on the pane's input surface" ([AgentInputSurfaceState.Ready]) is
 * authoritative evidence that the submit was consumed. Everything else — a pane
 * still holding the payload, an unreadable prompt, a failed capture — is
 * fail-closed and preserves the durable row.
 */
internal suspend fun OutboundDeliveryLedger.resolveLateAuthoritativeSubmitAck(
    transcriptAuthority: AgentTranscriptAuthority,
    paneId: String,
    payload: String,
    sendToken: String,
    durableRow: DurableOutboundRowIdentity?,
    capturePane: OutboundPaneCapture?,
    consumeOnSuccess: Boolean = true,
): Boolean {
    if (durableRow == null || !hasSubmitAttempt(paneId, sendToken, durableRow)) return false
    val acknowledged = transcriptConfirmed(transcriptAuthority, paneId, payload, durableRow) ||
        paneConsumedSubmit(paneId, payload, capturePane)
    if (!acknowledged) return false
    if (consumeOnSuccess) clear(paneId, sendToken)
    return true
}

/**
 * Arm 1: the exact recorded transcript source produced a NEW confirmed user turn
 * for this payload since the pre-Enter baseline snapshot.
 */
private fun OutboundDeliveryLedger.transcriptConfirmed(
    transcriptAuthority: AgentTranscriptAuthority,
    paneId: String,
    payload: String,
    durableRow: DurableOutboundRowIdentity,
): Boolean {
    val baseline = submitTranscriptBaseline(durableRow) ?: return false
    return transcriptAuthority.acknowledgedFromDurableBaseline(paneId, payload, baseline)
}

/**
 * Arm 2: no transcript authority exists (or it never confirmed). The pane itself is
 * then the only authority the product has, and it is a real one: this row's paste
 * was ack-proven into the input surface before Enter, so an input surface that no
 * longer holds the payload means the agent/shell consumed the submit.
 *
 * Fail-closed on every uncertainty — no capture boundary, a failed/errored capture,
 * an unreadable prompt ([AgentInputSurfaceState.Unknown]), or the payload still
 * pending — because a false acknowledgement here silently loses a prompt.
 */
private suspend fun paneConsumedSubmit(
    paneId: String,
    payload: String,
    capturePane: OutboundPaneCapture?,
): Boolean {
    val capture = capturePane ?: return false
    val frame = capture(paneId, OUTBOUND_DELIVERY_PROBE_TIMEOUT_MS, 0) ?: return false
    if (frame.isError) return false
    val state = agentInputSurfaceState(frame, payload)
    DiagnosticEvents.record(
        "action",
        "outbound_late_pane_submit_ack",
        "pane" to paneId,
        "inputState" to state.name,
    )
    return state == AgentInputSurfaceState.Ready
}
