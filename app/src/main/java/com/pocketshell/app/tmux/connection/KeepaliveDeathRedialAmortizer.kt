package com.pocketshell.app.tmux.connection

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Issue #928 Slice 1b (T6 from the definitive connection-stability audit):
 * how long a host must stay free of keepalive-death episodes before its
 * escalated redial backoff resets to the instant first-episode grace. ~5min —
 * far beyond the observed ~2min NAT-reap flap period, so a genuinely flapping
 * host keeps its widened cadence while a genuinely recovered one returns to
 * seamless instant healing.
 */
internal const val KEEPALIVE_DEATH_REDIAL_QUIET_RESET_MS: Long = 300_000L

/**
 * Issue #928 (T6): cross-episode amortization for the KEEPALIVE-DEATH redial —
 * the per-host idle-flap recovery path the #1522 amortization did not cover.
 *
 * ## Why this exists
 *
 * The #1522 `NetworkLossBandDebouncer` amortizes the bare-`NetworkLost` band
 * arm only. The keepalive-death chain never passes through it: the transport
 * keepalive declares the idle host's NAT-reaped socket dead
 * (`SshSessionCloseCause.KeepaliveDead`) → `-CC` reader EOF → passive
 * disconnect → `silentReattachWithinPassiveGrace` re-dials IMMEDIATELY (250ms
 * spacing inside the episode) — and nothing counted episodes per host. A host
 * whose carrier NAT reaps its idle mapping every ~2 minutes therefore reloaded
 * the window every ~2 minutes forever, at a FIXED cadence with zero widening —
 * the maintainer's per-host connect/disconnect flap.
 *
 * ## What it does (the exact [NetworkLossBandDebouncer] shape)
 *
 * The silent-reattach grace job consults [awaitRedialGrace] BEFORE its redial
 * loop, only when the episode's close cause is `KeepaliveDead`:
 *
 *  - the FIRST episode waits nothing — a one-off death keeps today's seamless
 *    instant recovery;
 *  - the Nth-in-a-row episode waits the connection's own auto-reconnect ladder
 *    rung ([backoffLadderMs], capped at its last rung) under the honest calm
 *    band the controller already painted (`Live → Reattaching`), with the
 *    working #1521 Reconnect button available the whole time;
 *  - a sustained quiet period ([quietResetMs] with no new episode) resets the
 *    host back to instant.
 *
 * A MANUAL reconnect / Send (T8) never waits here: the user path cancels the
 * passive-disconnect grace job (this wait runs inside it) and dials through its
 * own entrypoint immediately. Non-keepalive drop classes (plain reader EOF,
 * explicit teardown, network handoffs) never consult this at all.
 *
 * Per-host state ([ConnectionTarget.hostId]-keyed) because the flap is per-host
 * — one dying idle host must not slow a healthy sibling's recovery. All state
 * is confined to the ViewModel's main-dispatcher [scope] (same discipline as
 * [NetworkLossBandDebouncer]); the jobs are virtual-clock driven in tests and
 * cancelled with the scope in production.
 */
internal class KeepaliveDeathRedialAmortizer(
    private val scope: CoroutineScope,
    private val backoffLadderMs: () -> List<Long>,
    private val quietResetMs: () -> Long,
) {
    /**
     * Keepalive-death episodes seen per host in the current flap window
     * (capped at the ladder's last rung index so the grace saturates).
     */
    private val episodeCountByHost = mutableMapOf<Long, Int>()
    private val quietResetJobByHost = mutableMapOf<Long, Job>()

    /**
     * Enter one keepalive-death episode for [target]'s host: record the
     * amortization decision, then suspend for the episode's grace (nothing for
     * the first episode; the escalating ladder rung for the Nth-in-a-row).
     * Cancelling the caller (a manual reconnect superseding the passive grace
     * job) abandons the wait — the episode still counts, so the flap window
     * keeps escalating.
     */
    suspend fun awaitRedialGrace(target: ConnectionTarget, generation: Long) {
        val hostId = target.hostId
        quietResetJobByHost.remove(hostId)?.cancel()
        val ladder = backoffLadderMs()
        val count = episodeCountByHost[hostId] ?: 0
        val graceMs =
            if (count == 0 || ladder.isEmpty()) 0L
            else ladder[count.coerceAtMost(ladder.size - 1)]
        episodeCountByHost[hostId] = (count + 1).coerceAtMost(ladder.size.coerceAtLeast(1))
        armQuietReset(hostId)
        recordKeepaliveDeathRedialEpisode(
            target = target,
            generation = generation,
            episode = count + 1,
            graceMs = graceMs,
        )
        if (graceMs > 0L) delay(graceMs)
    }

    /**
     * Arm (or re-arm) the per-host quiet window: if no new episode lands within
     * [quietResetMs], the host's backoff resets to the instant first-episode
     * grace. Armed from the episode START so the window measures "time since
     * the last death", the property that distinguishes a flapping host from a
     * recovered one.
     */
    private fun armQuietReset(hostId: Long) {
        val job = scope.launch {
            delay(quietResetMs())
            episodeCountByHost.remove(hostId)
        }
        quietResetJobByHost[hostId] = job
        job.invokeOnCompletion {
            if (quietResetJobByHost[hostId] === job) quietResetJobByHost.remove(hostId)
        }
    }
}

/**
 * Issue #928 (T6): the per-episode amortization decision, visible in field logs
 * so a widening flap cadence is directly observable on-device (the audit's exit
 * criterion for slice 1b).
 */
private fun recordKeepaliveDeathRedialEpisode(
    target: ConnectionTarget,
    generation: Long,
    episode: Int,
    graceMs: Long,
) {
    DiagnosticEvents.record(
        "connection",
        "keepalive_death_redial_amortized",
        "source" to "passive_disconnect",
        "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
        "cause" to KEEPALIVE_DEAD_REASON,
        "episode" to episode,
        "graceMs" to graceMs,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "generation" to generation,
    )
    ReconnectCauseTrail.record(
        stage = "keepalive_death_redial",
        outcome = if (graceMs > 0L) "amortized" else "instant",
        cause = KEEPALIVE_DEAD_REASON,
        trigger = TmuxConnectTrigger.AutoReconnect.logValue,
        "hostId" to target.hostId,
        "episode" to episode,
        "graceMs" to graceMs,
        "generation" to generation,
    )
}

/**
 * Issue #928 (VM-shrink extraction): the canonical `passive_disconnect`
 * diagnostic + `session_disconnect` reconnect-cause trail — a REAL tmux control
 * channel closed for the current client. Field set unchanged from the former
 * inline body of `TmuxSessionViewModel.recordPassiveDisconnectDiagnostic`.
 */
internal fun recordPassiveDisconnect(
    clientHash: Int,
    target: ConnectionTarget?,
    disconnectEvent: TmuxDisconnectEvent,
    generation: Long,
    attempt: Int?,
    activeTrigger: String?,
    status: String,
    shortAppSwitchFields: Array<out Pair<String, Any?>>,
) {
    DiagnosticEvents.record(
        "connection",
        "passive_disconnect",
        "source" to "tmux_client_disconnected",
        "classification" to "real_tmux_control_channel_closed",
        "disconnectReason" to disconnectEvent.reason.logValue,
        "disconnectSource" to disconnectEvent.source,
        "disconnectIntent" to disconnectEvent.intent,
        "commandKind" to disconnectEvent.commandKind,
        "timeoutMode" to disconnectEvent.timeoutMode,
        "exceptionClass" to disconnectEvent.exceptionClass,
        "message" to disconnectEvent.message,
        "hostId" to target?.hostId,
        "host" to target?.host,
        "port" to target?.port,
        "user" to target?.user,
        "session" to target?.sessionName,
        "clientHash" to clientHash,
        "generation" to generation,
        "attempt" to attempt,
        "activeTrigger" to activeTrigger,
        "status" to status,
        *shortAppSwitchFields,
    )
    ReconnectCauseTrail.record(
        stage = "session_disconnect",
        outcome = "passive_disconnect",
        cause = disconnectEvent.reason.logValue,
        trigger = TmuxConnectTrigger.AutoReconnect.logValue,
        "hostId" to target?.hostId,
        "generation" to generation,
        "attempt" to attempt,
        "clientHash" to clientHash,
        "status" to status,
        "disconnectSource" to disconnectEvent.source,
        "disconnectIntent" to disconnectEvent.intent,
    )
}

/**
 * EPIC #792 #833 / issue #928 (VM-shrink extraction): the `silent_reattach_start`
 * diagnostic — the passive-disconnect silent-reattach grace loop is starting for
 * [target]. Field set unchanged from the former inline record in
 * `TmuxSessionViewModel.silentlyReattachWithinPassiveGrace`.
 */
internal fun recordSilentReattachStart(
    target: ConnectionTarget,
    staleClientHash: Int,
    graceMs: Long,
    preferFreshTransport: Boolean,
    shortAppSwitchFields: Array<out Pair<String, Any?>>,
) {
    DiagnosticEvents.record(
        "connection",
        "silent_reattach_start",
        "source" to "passive_disconnect",
        "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "clientHash" to staleClientHash,
        "timeoutMs" to graceMs,
        "preferFreshTransport" to preferFreshTransport,
        *shortAppSwitchFields,
    )
}

/**
 * EPIC #792 #833 / issue #928 (VM-shrink extraction): the `silent_reattach_fail`
 * diagnostic — the grace window elapsed without recovery.
 * [transportReattachAttempts] is how many fresh-transport re-dials the resilient
 * grace loop made across the outage window before grace elapsed. Field set
 * unchanged from the former inline record.
 */
internal fun recordSilentReattachFail(
    target: ConnectionTarget,
    staleClientHash: Int,
    transportReattachAttempts: Int,
    shortAppSwitchFields: Array<out Pair<String, Any?>>,
) {
    DiagnosticEvents.record(
        "connection",
        "silent_reattach_fail",
        "source" to "passive_disconnect",
        "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "clientHash" to staleClientHash,
        "cause" to "grace_elapsed",
        "transportReattachAttempts" to transportReattachAttempts,
        *shortAppSwitchFields,
    )
}
