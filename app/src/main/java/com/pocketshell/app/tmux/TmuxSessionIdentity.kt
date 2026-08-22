package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.connection.SessionId

/**
 * Exact identity of one tmux session generation.
 *
 * A tmux session name is mutable and can be reused. The server-assigned
 * `#{session_id}` plus `#{session_created}` pair remains stable for the
 * lifetime of that session object, so destructive lifecycle mutations must
 * carry this value instead of falling back to the display name.
 */
public data class TmuxSessionGeneration(
    val sessionId: String,
    val createdEpochSeconds: Long,
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

internal fun tmuxSessionGenerationOrNull(
    tmuxSessionId: String?,
    sessionCreated: Long?,
): TmuxSessionGeneration? {
    val id = tmuxSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val created = sessionCreated?.takeIf { it > 0L } ?: return null
    return TmuxSessionGeneration(sessionId = id, createdEpochSeconds = created)
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
): TmuxSessionGeneration? {
    val body = durableSessionKey?.removePrefix("tmux:$hostId:")
        ?.takeIf { it != durableSessionKey }
        ?: return null
    val separator = body.lastIndexOf(':')
    if (separator <= 0 || separator == body.lastIndex) return null
    val id = body.substring(0, separator).trim().takeIf { it.isNotEmpty() } ?: return null
    val created = body.substring(separator + 1).toLongOrNull()?.takeIf { it > 0L } ?: return null
    return TmuxSessionGeneration(id, created)
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
