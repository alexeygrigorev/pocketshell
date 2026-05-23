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
        _state.value = HostTmuxSessionPickerState.Loading(request.host.name)
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
            }
        }
    }

    fun dismiss() {
        loadJob?.cancel()
        loadJob = null
        _state.value = HostTmuxSessionPickerState.Idle
    }
}
