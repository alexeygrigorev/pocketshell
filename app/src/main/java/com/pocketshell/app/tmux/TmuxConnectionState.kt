package com.pocketshell.app.tmux

/**
 * VM-internal connection state vocabulary. It mirrors
 * `:shared:core-connection`'s `ConnectionController.ConnectionState` shape
 * while carrying the display payload needed by [TmuxSessionViewModel.ConnectionStatus].
 */
internal sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val host: String, val port: Int, val user: String) :
        ConnectionState

    /** Warm same-host switch / warm open. */
    data class Attaching(val host: String, val port: Int, val user: String) :
        ConnectionState

    /** Attached, input enabled. */
    data class Live(val host: String, val port: Int, val user: String) :
        ConnectionState

    data class Backgrounded(val host: String, val port: Int, val user: String) :
        ConnectionState

    data class Reattaching(
        val host: String,
        val port: Int,
        val user: String,
        val attempt: Int,
        val maxAttempts: Int,
        val retryDelayMs: Long,
        val reason: String,
    ) : ConnectionState

    data class Reconnecting(
        val host: String,
        val port: Int,
        val user: String,
        val attempt: Int,
        val maxAttempts: Int,
        val retryDelayMs: Long,
        val reason: String,
    ) : ConnectionState

    data class Gone(val message: String) : ConnectionState

    data class Unreachable(val message: String) : ConnectionState
}

internal fun connectionStatusFor(
    state: ConnectionState,
): TmuxSessionViewModel.ConnectionStatus =
    when (state) {
        is ConnectionState.Idle -> TmuxSessionViewModel.ConnectionStatus.Idle
        is ConnectionState.Connecting ->
            TmuxSessionViewModel.ConnectionStatus.Connecting(state.host, state.port, state.user)
        is ConnectionState.Attaching ->
            TmuxSessionViewModel.ConnectionStatus.Switching(state.host, state.port, state.user)
        is ConnectionState.Live ->
            TmuxSessionViewModel.ConnectionStatus.Connected(state.host, state.port, state.user)
        is ConnectionState.Backgrounded ->
            TmuxSessionViewModel.ConnectionStatus.Connected(state.host, state.port, state.user)
        is ConnectionState.Reattaching ->
            TmuxSessionViewModel.ConnectionStatus.Reconnecting(
                host = state.host,
                port = state.port,
                user = state.user,
                attempt = state.attempt,
                maxAttempts = state.maxAttempts,
                retryDelayMs = state.retryDelayMs,
                reason = state.reason,
            )
        is ConnectionState.Reconnecting ->
            TmuxSessionViewModel.ConnectionStatus.Reconnecting(
                host = state.host,
                port = state.port,
                user = state.user,
                attempt = state.attempt,
                maxAttempts = state.maxAttempts,
                retryDelayMs = state.retryDelayMs,
                reason = state.reason,
            )
        is ConnectionState.Gone -> TmuxSessionViewModel.ConnectionStatus.Failed(state.message)
        is ConnectionState.Unreachable -> TmuxSessionViewModel.ConnectionStatus.Failed(state.message)
    }
