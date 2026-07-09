package com.pocketshell.app.tmux

import android.os.SystemClock
import com.pocketshell.app.tmux.connection.ConnectionStatusProjection
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxDisconnectReason

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

internal fun tmuxReconnectSourceCandidate(
    trigger: TmuxConnectTrigger?,
    sourceCandidate: String?,
): String =
    when {
        sourceCandidate == "terminal_surface" -> "terminal_ui_failure"
        sourceCandidate == "passive_disconnect" -> "tmux_eof_or_reader_disconnect"
        sourceCandidate == "background_teardown" -> "lifecycle_teardown"
        sourceCandidate == "foreground_reattach" -> "manual_or_foreground_reattach"
        sourceCandidate == "network_observer" -> "network_replay_or_handoff"
        trigger == TmuxConnectTrigger.LifecycleReattach -> "manual_or_foreground_reattach"
        trigger == TmuxConnectTrigger.NetworkReconnect -> "network_replay_or_handoff"
        trigger == TmuxConnectTrigger.Reconnect -> "manual_or_foreground_reattach"
        trigger == TmuxConnectTrigger.AutoReconnect -> "tmux_eof_or_reader_disconnect"
        else -> "none"
    }

internal fun tmuxAttachMilestoneMessage(
    attempt: Int,
    target: TmuxSessionViewModel.ConnectionTarget,
    startedAtMs: Long,
    event: String,
    trigger: TmuxConnectTrigger,
    detail: String = "",
): String = buildString {
    append(event)
    append(" attempt=")
    append(attempt)
    append(" trigger=")
    append(trigger.logValue)
    append(' ')
    append(targetLogFields(target))
    append(" elapsedMs=")
    append(SystemClock.elapsedRealtime() - startedAtMs)
    if (detail.isNotBlank()) {
        append(' ')
        append(detail)
    }
}

internal fun tmuxDisconnectReasonPrefix(reason: TmuxDisconnectReason): String =
    when (reason) {
        TmuxDisconnectReason.ReaderEof -> "Transport EOF"
        TmuxDisconnectReason.ReaderException -> "Transport read failed"
        TmuxDisconnectReason.CommandTimeout -> "Tmux command timed out"
        TmuxDisconnectReason.ExplicitClose -> "Connection closed locally"
        TmuxDisconnectReason.ExplicitDetach -> "Tmux client detached"
        TmuxDisconnectReason.ServerExited -> "Tmux server restarted"
        TmuxDisconnectReason.Unknown -> "Disconnected"
    }

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
