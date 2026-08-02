package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.connection.SessionId

internal data class DurableTmuxSessionIdentity(
    val tmuxSessionId: String,
    val sessionCreated: Long,
)

/** Correlated navigation payload; identity travels with the user action. */
public data class TmuxSessionNavigationTarget(
    val sessionName: String,
    val tmuxSessionId: String? = null,
    val sessionCreated: Long? = null,
)

internal fun HostTmuxSessionRow.navigationTargetOrNull(): TmuxSessionNavigationTarget? {
    val id = tmuxSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val created = createdAt?.takeIf { it > 0L } ?: return null
    return TmuxSessionNavigationTarget(name, id, created)
}

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

internal fun parseDurableTmuxSessionIdentity(
    hostId: Long,
    durableSessionKey: String?,
): DurableTmuxSessionIdentity? {
    val body = durableSessionKey?.removePrefix("tmux:$hostId:")
        ?.takeIf { it != durableSessionKey }
        ?: return null
    val separator = body.lastIndexOf(':')
    if (separator <= 0 || separator == body.lastIndex) return null
    val id = body.substring(0, separator).trim().takeIf { it.isNotEmpty() } ?: return null
    val created = body.substring(separator + 1).toLongOrNull()?.takeIf { it > 0L } ?: return null
    return DurableTmuxSessionIdentity(id, created)
}

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
