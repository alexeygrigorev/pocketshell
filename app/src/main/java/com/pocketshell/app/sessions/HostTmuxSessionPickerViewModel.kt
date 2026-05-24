package com.pocketshell.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HostTmuxSessionPickerViewModel @Inject constructor(
    private val gateway: HostTmuxSessionsGateway,
) : ViewModel() {
    private val _state: MutableStateFlow<HostTmuxSessionPickerState> =
        MutableStateFlow(HostTmuxSessionPickerState.Idle)
    val state: StateFlow<HostTmuxSessionPickerState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun load(request: HostTmuxSessionPickerRequest) {
        loadJob?.cancel()
        _state.value = HostTmuxSessionPickerState.Loading(request)
        loadJob = viewModelScope.launch {
            _state.value = when (val result = gateway.listSessions(request.host, request.keyPath, request.passphrase)) {
                is HostTmuxSessionListResult.Sessions -> HostTmuxSessionPickerState.Ready(
                    request = request,
                    rows = result.rows.sortedWith(
                        compareByDescending<HostTmuxSessionRow> { it.lastActivity ?: it.createdAt ?: 0L }
                            .thenBy { it.name },
                    ),
                    message = if (result.rows.isEmpty()) "No tmux sessions found." else null,
                )
                HostTmuxSessionListResult.ToolUnavailable -> HostTmuxSessionPickerState.Fallback(
                    request = request,
                    message = "tmuxctl/tmux is not available on this host.",
                )
                is HostTmuxSessionListResult.Failed -> HostTmuxSessionPickerState.Fallback(
                    request = request,
                    message = result.message,
                )
                is HostTmuxSessionListResult.ConnectFailed -> HostTmuxSessionPickerState.ConnectError(
                    request = request,
                    summary = summarizeConnectError(result.cause),
                )
            }
        }
    }

    /**
     * Issue #109: Retry button on the connect-error sheet. Re-runs the
     * same `load(...)` with the last [HostTmuxSessionPickerRequest] held
     * in the current `ConnectError` state. No-op if the user dismissed
     * the sheet already (state is `Idle`).
     */
    fun retry() {
        val current = _state.value
        val request = when (current) {
            is HostTmuxSessionPickerState.ConnectError -> current.request
            is HostTmuxSessionPickerState.Fallback -> current.request
            is HostTmuxSessionPickerState.Loading -> current.request
            is HostTmuxSessionPickerState.Ready -> current.request
            HostTmuxSessionPickerState.Idle -> null
        } ?: return
        load(request)
    }

    /**
     * Issue #109: Cancel button shown next to the "Connecting to ..."
     * spinner. Aborts the in-flight gateway call (so the SSH connect
     * suspension is cancelled cooperatively) and returns the sheet to
     * `Idle`, dismissing it.
     */
    fun cancelLoading() {
        loadJob?.cancel()
        loadJob = null
        _state.value = HostTmuxSessionPickerState.Idle
    }

    fun dismiss() {
        loadJob?.cancel()
        loadJob = null
        _state.value = HostTmuxSessionPickerState.Idle
    }
}
