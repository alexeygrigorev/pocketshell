package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus

internal fun recordTmuxReconnectUiStateRendered(
    status: ConnectionStatus,
    hostId: Long,
    canReconnect: Boolean,
) {
    when (status) {
        is ConnectionStatus.Reconnecting -> ReconnectCauseTrail.record(
            stage = "ui_reconnect_state",
            outcome = "rendered",
            cause = "connection_status_reconnecting",
            "hostId" to hostId,
            "attempt" to status.attempt,
            "maxAttempts" to status.maxAttempts,
            "retryDelayMs" to status.retryDelayMs,
            "canReconnect" to canReconnect,
        )
        is ConnectionStatus.Failed -> ReconnectCauseTrail.record(
            stage = "ui_reconnect_state",
            outcome = "rendered",
            cause = "connection_status_failed",
            "hostId" to hostId,
            "canReconnect" to canReconnect,
        )
        else -> Unit
    }
}
