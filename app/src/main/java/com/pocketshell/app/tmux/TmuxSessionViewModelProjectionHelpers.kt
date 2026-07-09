package com.pocketshell.app.tmux

import android.os.SystemClock
import com.pocketshell.app.tmux.connection.ConnectionStatusProjection
import com.pocketshell.core.tmux.CommandResponse

internal fun tmuxControlClientSizeMessage(
    event: String,
    target: TmuxSessionViewModel.ConnectionTarget,
    columns: Int,
    rows: Int,
    milestone: AttachMilestone?,
    detail: String = "",
): String = buildString {
    append(event)
    append(" host=")
    append(target.host)
    append(" port=")
    append(target.port)
    append(" user=")
    append(target.user)
    append(" session=")
    append(target.sessionName)
    append(" cols=")
    append(columns)
    append(" rows=")
    append(rows)
    milestone?.let {
        append(" attempt=")
        append(it.attempt)
        append(" elapsedMs=")
        append(SystemClock.elapsedRealtime() - it.startedAtMs)
    }
    if (detail.isNotBlank()) {
        append(' ')
        append(detail)
    }
}

internal fun tmuxCommandErrorDetailText(
    error: Throwable?,
    response: CommandResponse?,
): String =
    error?.let { "error=${it.javaClass.simpleName}:${it.message.orEmpty()}" }
        ?: response?.output
            ?.joinToString(separator = " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "tmuxError=$it" }
        ?: "tmuxError=unknown"

internal fun connectionStatusHostPortUserFor(
    inlineState: ConnectionState,
    activeTarget: TmuxSessionViewModel.ConnectionTarget?,
    connectingTarget: TmuxSessionViewModel.ConnectionTarget?,
): ConnectionStatusProjection.HostPortUser =
    when (inlineState) {
        is ConnectionState.Connecting ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Attaching ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Live ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Backgrounded ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Reattaching ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Reconnecting ->
            ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
        is ConnectionState.Idle,
        is ConnectionState.Gone,
        is ConnectionState.Unreachable -> {
            val target = activeTarget ?: connectingTarget
            if (target != null) {
                ConnectionStatusProjection.HostPortUser(target.host, target.port, target.user)
            } else {
                ConnectionStatusProjection.HostPortUser("", 0, "")
            }
        }
    }
