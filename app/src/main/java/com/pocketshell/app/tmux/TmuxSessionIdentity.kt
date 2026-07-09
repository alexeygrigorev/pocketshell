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

internal fun sessionCardsTargetKey(
    hostId: Long,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    sessionName: String,
): String = buildString {
    append(hostId)
    append('|')
    append(port)
    append('|')
    appendKeyPart(host)
    append('|')
    appendKeyPart(user)
    append('|')
    appendKeyPart(keyPath)
    append('|')
    appendKeyPart(sessionName.trim())
}

private fun StringBuilder.appendKeyPart(value: String) {
    append(value.length)
    append(':')
    append(value)
}
