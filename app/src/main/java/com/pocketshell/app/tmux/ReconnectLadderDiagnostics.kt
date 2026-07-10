package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.startup.StartupTiming

/**
 * Issue #1328 (S5): the auto-reconnect ladder's per-rung DIAGNOSTIC emission, split out
 * of the [TmuxSessionViewModel] god-object (the connection-VM ratchet, issue #1047). These
 * are PURE logging — [DiagnosticEvents] / [ReconnectCauseTrail] / [StartupTiming] / [Log]
 * only — behaviour-identical to the inline blocks that lived in `scheduleAutoReconnectBody`,
 * with the SAME event names, field names, and field ORDER (the maintainer's `PsTmuxReconnect`
 * trail parses them). The VM keeps the coupled IO body and passes the already-computed
 * `shortAppSwitchReconnectFields` array + primitives; nothing here touches VM state.
 */

/** The `reconnect_start` diagnostics for one ladder rung — emitted before the backoff delay. */
internal fun recordReconnectRungScheduled(
    target: TmuxSessionViewModel.ConnectionTarget,
    trigger: TmuxConnectTrigger,
    attemptNo: Int,
    maxAttempts: Int,
    delayMs: Long,
    generation: Long,
    reason: String,
    shortAppSwitchFields: Array<Pair<String, Any?>>,
) {
    DiagnosticEvents.record(
        "connection",
        "reconnect_start",
        "attempt" to attemptNo,
        "maxAttempts" to maxAttempts,
        "retryDelayMs" to delayMs,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "trigger" to trigger.logValue,
        "reason" to reason,
        "generation" to generation,
        *shortAppSwitchFields,
    )
    ReconnectCauseTrail.record(
        stage = "reconnect_attempt",
        outcome = "scheduled",
        cause = "auto_reconnect_loop",
        trigger = trigger.logValue,
        "attempt" to attemptNo,
        "maxAttempts" to maxAttempts,
        "retryDelayMs" to delayMs,
        "hostId" to target.hostId,
        "generation" to generation,
    )
}

/** The `connect_start` / `connect_attempt` diagnostics for one ladder rung — emitted just
 *  before the fresh dial. [dialAttempt] is the global `TMUX_CONNECT_ATTEMPTS` counter. */
internal fun recordReconnectRungDialAttempt(
    target: TmuxSessionViewModel.ConnectionTarget,
    trigger: TmuxConnectTrigger,
    dialAttempt: Int,
    retryAttempt: Int,
    maxAttempts: Int,
    generation: Long,
    clientHash: Int?,
    previousClientPresent: Boolean,
    shortAppSwitchFields: Array<Pair<String, Any?>>,
) {
    StartupTiming.mark(
        "tmux-connect-attempt",
        "attempt" to dialAttempt,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "session" to target.sessionName,
        "trigger" to trigger.logValue,
        "requestedTrigger" to trigger.logValue,
        "generation" to generation,
    )
    Log.i(
        ISSUE_145_RECONNECT_TAG,
        "tmux-connect-attempt count=$dialAttempt trigger=${trigger.logValue} " +
            "requestedTrigger=${trigger.logValue} generation=$generation " +
            targetLogFields(target),
    )
    DiagnosticEvents.record(
        "connection",
        "connect_start",
        "attempt" to dialAttempt,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "trigger" to trigger.logValue,
        "generation" to generation,
        "clientHash" to clientHash,
        *shortAppSwitchFields,
    )
    ReconnectCauseTrail.record(
        stage = "connect_attempt",
        outcome = "reconnect",
        cause = "auto_reconnect_loop",
        trigger = trigger.logValue,
        "attempt" to dialAttempt,
        "retryAttempt" to retryAttempt,
        "maxAttempts" to maxAttempts,
        "hostId" to target.hostId,
        "generation" to generation,
        "previousClientPresent" to previousClientPresent,
    )
}
