package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents

/** Refresh the exact pane recorded by a HostAck-unknown queue row. */
public fun TmuxSessionViewModel.refreshPaneForOutboundCheck(
    paneId: String,
    onCompleted: (() -> Unit)? = null,
): Boolean {
    val pane = paneRows[paneId]
    val client = clientRef
    val target = activeTarget
    HostAckPaneRefreshProbe.recordStage(paneId, "requested")
    Log.i(
        ISSUE_145_RECONNECT_TAG,
        "tmux-host-ack-check-request pane=$paneId hasPane=${pane != null} " +
            "hasClient=${client != null} hasTarget=${target != null} " +
            "clientDisconnected=${client?.disconnected?.value}",
    )
    if (pane == null || client == null || target == null || client.disconnected.value) {
        HostAckPaneRefreshProbe.recordStage(paneId, "unavailable")
        _redrawFeedback.tryEmit(HOST_ACK_PANE_REFRESH_UNAVAILABLE_MESSAGE)
        return false
    }
    val guard = RuntimeRefreshGuard(
        generation = connectGeneration,
        target = target,
        client = client,
    )
    DiagnosticEvents.record(
        "action",
        "host_ack_check_pane",
        "paneId" to paneId,
        "session" to target.sessionName,
        "generation" to connectGeneration,
    )
    launchContainedTeardown {
        try {
            HostAckPaneRefreshProbe.recordStage(paneId, "started")
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-host-ack-check-start pane=$paneId")
            if (client.disconnected.value || !isCurrentRuntime(guard)) {
                HostAckPaneRefreshProbe.recordStage(paneId, "superseded-before-capture")
                return@launchContainedTeardown
            }
            val outcome = runCatching {
                healActivePaneIfStaleRender(
                    client = client,
                    pane = pane,
                    refreshGuard = guard,
                    force = true,
                    recordMilestone = false,
                )
            }.getOrDefault(HealOutcome.Unverified)
            if (!isCurrentRuntime(guard)) {
                HostAckPaneRefreshProbe.recordStage(paneId, "superseded-after-capture")
                return@launchContainedTeardown
            }
            val feedback = if (outcome == HealOutcome.Unverified) {
                HOST_ACK_PANE_REFRESH_UNAVAILABLE_MESSAGE
            } else {
                HOST_ACK_PANE_REFRESHED_MESSAGE
            }
            _redrawFeedback.tryEmit(feedback)
            HostAckPaneRefreshProbe.recordCompletion(paneId, outcome, feedback)
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-host-ack-check-complete pane=$paneId outcome=$outcome",
            )
            onCompleted?.invoke()
        } catch (cause: Throwable) {
            HostAckPaneRefreshProbe.recordStage(paneId, "failed:${cause.javaClass.simpleName}")
            Log.w(ISSUE_145_RECONNECT_TAG, "tmux-host-ack-check-failed pane=$paneId", cause)
            throw cause
        }
    }
    return true
}

/** Route acknowledged payloads, including an explicit resend, through HostAck. */
internal suspend fun HostAckDeliveryPort.sendIf(
    paneId: String,
    payload: String,
    sendToken: String,
    resendInterrupted: Boolean,
): Result<Unit>? {
    if (!active && !resendInterrupted) return null
    return sendAgentPayload(paneId, payload, sendToken, resendInterrupted)
}
