package com.pocketshell.app.sessions

import com.pocketshell.core.storage.entity.HostEntity

data class HostTmuxSessionRow(
    val name: String,
    val createdAt: Long? = null,
    val lastActivity: Long? = null,
    val attached: Boolean = false,
    /**
     * Issue #463: the session's working directory (`#{session_path}`),
     * populated by the warm live-client `list-sessions` query so the
     * in-session project switcher can group sessions by project/folder.
     * Null when the source list shape did not carry a path (e.g. the
     * `pocketshell sessions list` proxy or the fallback regex parse).
     */
    val path: String? = null,
)

data class HostTmuxSessionPickerRequest(
    val host: HostEntity,
    val keyPath: String,
    val passphrase: CharArray?,
)

sealed interface HostTmuxSessionListResult {
    data class Sessions(val rows: List<HostTmuxSessionRow>) : HostTmuxSessionListResult
    data object ToolUnavailable : HostTmuxSessionListResult
    data class Failed(val message: String) : HostTmuxSessionListResult

    /**
     * Issue #109: the gateway no longer flattens the SSH connect
     * exception into a string. The view-model classifies the cause
     * chain into a user-facing summary and the sheet renders a real
     * "Connection failed" state instead of "Tmux sessions" with a stack
     * trace.
     */
    data class ConnectFailed(val cause: Throwable) : HostTmuxSessionListResult
}

sealed interface HostTmuxSessionPickerState {
    data object Idle : HostTmuxSessionPickerState

    /**
     * Issue #109: now carries the originating [request] so the sheet can
     * render "Connecting to user@host:port…" and the Cancel button can
     * re-display the host coordinates without a separate lookup.
     */
    data class Loading(
        val request: HostTmuxSessionPickerRequest,
    ) : HostTmuxSessionPickerState {
        val hostName: String get() = request.host.name
    }

    data class Ready(
        val request: HostTmuxSessionPickerRequest,
        val rows: List<HostTmuxSessionRow>,
        val message: String? = null,
    ) : HostTmuxSessionPickerState

    data class Fallback(
        val request: HostTmuxSessionPickerRequest,
        val message: String,
    ) : HostTmuxSessionPickerState

    /**
     * Issue #109: distinct state for the connect-error path. Sheet title
     * is "Connection failed", not "Tmux sessions", and the body is the
     * user-facing summary from [HostConnectErrorSummary]. The full
     * exception text lives in [HostConnectErrorSummary.details] and is
     * shown behind a "Show details" disclosure.
     */
    data class ConnectError(
        val request: HostTmuxSessionPickerRequest,
        val summary: HostConnectErrorSummary,
    ) : HostTmuxSessionPickerState
}
