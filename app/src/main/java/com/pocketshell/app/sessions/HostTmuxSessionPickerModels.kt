package com.pocketshell.app.sessions

import com.pocketshell.core.storage.entity.HostEntity

data class HostTmuxSessionRow(
    val name: String,
    val createdAt: Long? = null,
    val lastActivity: Long? = null,
    val attached: Boolean = false,
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
}

sealed interface HostTmuxSessionPickerState {
    data object Idle : HostTmuxSessionPickerState
    data class Loading(val hostName: String) : HostTmuxSessionPickerState
    data class Ready(
        val request: HostTmuxSessionPickerRequest,
        val rows: List<HostTmuxSessionRow>,
        val message: String? = null,
    ) : HostTmuxSessionPickerState
    data class Fallback(
        val request: HostTmuxSessionPickerRequest,
        val message: String,
    ) : HostTmuxSessionPickerState
}
