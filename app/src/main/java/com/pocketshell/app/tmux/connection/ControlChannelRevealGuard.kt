package com.pocketshell.app.tmux.connection

import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientException

/**
 * Issue #1863 — the reveal-time control-channel liveness guard.
 *
 * An attach that resolves is not the same as an attach that resolved over a LIVE
 * wire. The reported ordering:
 *
 * ```
 * 34.529  tmux-command-write-failed cause=JobCancellationException
 * 34.540  tmux-refresh-client-size-error ... TmuxClientException: client is closed
 * 34.571  app-navigator-current destination=FolderList          <- the back-press
 * 34.957  tmux-connect-ready attempt=1                          <- over the corpse
 * ```
 *
 * The connect coroutine was NOT cancelled — a different, screen-scoped caller was
 * — so it walked to the end of its happy path and published `tmux-connect-ready`
 * + `ConnectionState.Live` over a client whose `disconnected` latch was already
 * set. Downstream that reads as a healthy session: the next open of the same
 * identity is deduped as a no-op (the prior VM status was Connected and
 * activeTarget == target`, which is why `tmux-connect-attempt` stayed at 1), and
 * the liveness gate was closed by the very `disconnected` flag it should have
 * been reacting to.
 *
 * The rule: a connect/attach/reattach may only declare the wire live if the wire
 * is still there at the moment it declares it. [requireControlChannelAliveForReveal]
 * runs on four of the five production paths that publish `Live` after an attach — the
 * two cold/warm connect paths immediately before the reveal, and the two
 * passive-recovery rungs before the host registration + lifecycle-hook install, so a
 * dead replacement leaves no registration applied (the same exit shape as those rungs'
 * `!ready` arms; the throw lands in their existing catch, which restores the stale
 * client, closes the replacement and advances the ladder). The network ride-through
 * uses the non-throwing [controlChannelAliveForReveal] instead, because it reports
 * through an early return rather than an exception.
 *
 * The fifth publisher, `restoreCachedRuntime`, is deliberately NOT guarded here and it
 * is not an oversight: `takeCachedRuntimeForActivation` already checks
 * `cached.client.disconnected.value` and `tryActivateCachedRuntime` re-checks
 * `cachedRuntimeControlChannelHealthy`, and every function between those checks and the
 * reveal is non-`suspend`, so there is no in-flight window for the channel to die in.
 * Adding a second check there would be a duplicate authority, not extra safety.
 *
 * ## What this guard structurally CANNOT do
 *
 * It is a POINT-IN-TIME check. It closes the reported window — the channel died
 * BEFORE the reveal decision (in the report, ~0.4s before `tmux-connect-ready`) —
 * and nothing more. A channel that dies strictly AFTER the check and before the user
 * notices is not, and cannot be, caught here: there is no suspension-free way to make
 * "still alive" hold across the reveal. That half of the class is owned by the
 * liveness probe's `DeadChannel` arm
 * ([com.pocketshell.app.tmux.connection.LivenessProbeGate.DeadChannel]), which bounds
 * it at one probe interval. Both halves are pinned by
 * `Issue1863DeadWireAfterCancelledConnectTest` — the guard half by the two
 * gate-parked connect/fast-switch tests, the probe half by
 * `deadWireAfterTheRevealGuardIsDeclaredByTheProbeWithinOneInterval`. Neither is
 * sufficient alone.
 *
 * The failure is raised as a [TmuxClientException] whose message carries
 * `control channel closed`, so
 * [com.pocketshell.app.tmux.TmuxSessionFailureClassifiers.isStaleChannelSymptom]
 * already classifies it: the cold path routes it through `failConnectAttempt`
 * (poisoned lease evicted, single auto-reconnect ladder, actionable band) and the
 * fast-switch path through its existing stale-channel re-dial arm. No new
 * recovery writer is introduced (D28).
 */
fun controlChannelClosedBeforeReveal(session: String, stage: String): TmuxClientException =
    TmuxClientException(
        "control channel closed before the $stage could reveal session `$session` as live",
    )

/**
 * Throws [controlChannelClosedBeforeReveal] when [client] is already
 * disconnected. Call immediately before publishing `Live` for [session].
 */
fun requireControlChannelAliveForReveal(client: TmuxClient, session: String, stage: String) {
    if (client.disconnected.value) throw controlChannelClosedBeforeReveal(session, stage)
}

/**
 * Non-throwing sibling for the recovery paths that report success/failure with a
 * `Boolean` rather than an exception (the passive silent-reattach rungs). Returns
 * true when [client] is still usable for a reveal.
 */
fun controlChannelAliveForReveal(client: TmuxClient): Boolean = !client.disconnected.value
