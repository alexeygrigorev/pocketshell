package com.pocketshell.app.tmux

import com.pocketshell.core.connection.SessionId

internal fun durableTmuxSessionKey(
    hostId: Long,
    tmuxSessionId: String?,
    sessionCreated: Long?,
): String? {
    val id = tmuxSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val created = sessionCreated ?: return null
    return "tmux:$hostId:$id:$created"
}

internal fun tmuxTargetSessionId(
    hostId: Long,
    sessionName: String,
    tmuxSessionId: String?,
    sessionCreated: Long?,
): SessionId =
    SessionId(durableTmuxSessionKey(hostId, tmuxSessionId, sessionCreated) ?: "$hostId/$sessionName")
