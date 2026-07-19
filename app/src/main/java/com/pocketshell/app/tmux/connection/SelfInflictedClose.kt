package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.DropCause
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason

/**
 * Issue #1632 — the SINGLE self-inflicted-close authority for the whole connection core.
 *
 * ## Why this exists
 * The #1568 P0-5 self-inflicted filter was built ONLY on the `-CC` channel edge, as an
 * inline lambda in `TmuxSessionViewModel` that pattern-matched `TmuxDisconnectReason`.
 * The LEASE edge never got one. So `silentlyReconnectTransportAfterPassiveDisconnect`'s
 * own first act — `sshLeaseManager.disconnect(leaseKey)` — emitted an `ExplicitDisconnect`
 * lease `Closed` that [ConnectionEffectDriver] submitted to the controller as a
 * `TransportDropped`: recovery's own teardown, re-ingested as a fresh remote failure,
 * repainting `Reconnecting 1/4` and re-triggering recovery. That echo is the amplification
 * engine of the #1610 storm (historically 215 `ExplicitDisconnect/down` events against
 * only 26 numbered-ladder rungs ever — the echo dominated the real ladder ~8x).
 *
 * Per D22 (hard-cut) this is NOT a second filter beside the `-CC` one: the narrow inline
 * `-CC` lambda is DELETED and both edges now route through here. One authority, one answer.
 *
 * ## The rule: LOCAL INTENT, not local execution
 * A close is self-inflicted when **we decided the transport/channel should go away while,
 * as far as we knew, it was still usable**. It is NOT self-inflicted merely because our
 * code executed the `close()` call — a watchdog that observes a dead peer and closes the
 * corpse is REPORTING a remote death, not causing one.
 *
 * This distinction is load-bearing and deliberately conservative. Suppressing a genuine
 * failure is strictly WORSE than the storm: the app would simply stop reconnecting. So
 * when intent is ambiguous, the answer is "not self-inflicted" and recovery runs.
 *
 * Both entry points are exhaustive `when`s over their enums on purpose: a new close
 * reason must not silently default into either bucket — the compiler forces whoever adds
 * one to state its intent here, at the authority, which is what stopped this from being
 * maintained on the two edges independently in the first place.
 */
object SelfInflictedClose {

    /**
     * Issue #1666 (D28 Layer 3) — the TYPED cause of a lease `Closed` edge. This is the
     * ONE derivation authority for the lease edge: [isSelfInflictedLeaseClose] is now just
     * `this is SelfInflicted`, so there is a SINGLE exhaustive `when` (no parallel filter to
     * drift). The reducer refuses a [DropCause.SelfInflicted] drop by construction
     * (`ConnectionController.onTransportDropped`), so the storm cannot be re-fed by any close
     * path that carries this cause.
     *
     * @param reason the [SshLeaseCloseReason] the lease manager stamped on the edge at its
     *   emit site. The reason IS the emitter's intent token — every `emitStateLocked` call
     *   in `SshLeaseManager` names why it is closing — so this is emitter-side tagging, not
     *   consumer-side inference about "was that us?" (the inference that rotted into #1632).
     */
    fun dropCauseForLeaseClose(reason: SshLeaseCloseReason?): DropCause = when (reason) {
        // We asked for the teardown. `ExplicitDisconnect` is the reported defect itself:
        // recovery's own first act (`TmuxSessionViewModel.kt:8455`). This is also the class
        // the #1681 `agent_kind_classify` self-close falls into — a bounded-exec/probe that
        // tears down (or force-refreshes) the shared lease it borrowed.
        SshLeaseCloseReason.ExplicitDisconnect,
        // We evicted a warm transport to force a fresh dial (network handoff / recovery).
        SshLeaseCloseReason.ForceRefresh,
        // Our idle reaper closed a zero-reference lease. No holder, no user-visible drop.
        SshLeaseCloseReason.IdleExpired,
        SshLeaseCloseReason.IdleTrimmed,
        // App lifecycle teardown — our policy, not the peer's doing.
        SshLeaseCloseReason.ProcessStopped,
        SshLeaseCloseReason.ManagerClosed,
        // We cancelled our own in-flight connect (#1185). It never produced a live
        // transport, so there is no transport to have "dropped".
        SshLeaseCloseReason.ConnectCancelled,
        -> DropCause.SelfInflicted(reason.name)

        // The peer died under us. sshj flipped `isConnected` false — per the #1632
        // investigation a raw SSHException from a channel read means the transport was
        // ALREADY dead, so a redial is justified.
        SshLeaseCloseReason.Disconnected -> DropCause.RemoteFailure(reason.name)

        // The always-on keepalive watchdog (#945) DETECTED a silent peer death and closed
        // the corpse. We executed the close; the peer caused it. This is precisely the
        // mobile silent-drop detector — filtering it would strand the maintainer with no
        // reconnect at all, the one outcome worse than the storm.
        SshLeaseCloseReason.KeepaliveDead -> DropCause.KeepaliveDead

        // An unnamed close carries no evidence of local intent; assume genuine and recover.
        null -> DropCause.Unknown
    }

    /**
     * Is this lease `Closed` edge OUR OWN teardown? Thin predicate over the typed
     * [dropCauseForLeaseClose] authority so the self-inflicted answer is derived in exactly
     * ONE place. Behaviour is unchanged from the pre-#1666 exhaustive `when`.
     */
    fun isSelfInflictedLeaseClose(reason: SshLeaseCloseReason?): Boolean =
        dropCauseForLeaseClose(reason) is DropCause.SelfInflicted

    /**
     * Issue #1666 (D28 Layer 3) — the TYPED cause of a `-CC` control-channel disconnect.
     * The ONE derivation authority for the `-CC` edge (the driver's control-channel submit
     * carries this straight onto the [com.pocketshell.core.connection.ConnectionEvent.TransportDropped]
     * so a self-inflicted `-CC` close the driver does NOT pre-filter is still refused by the
     * reducer). Preserves the #1568 P0-5 classification exactly.
     */
    fun dropCauseForControlChannelClose(event: TmuxDisconnectEvent?): DropCause {
        // An unnamed close carries no evidence of local intent; assume genuine and recover.
        if (event == null) return DropCause.Unknown
        // #1568: a detach performed as part of swapping/replacing the attached client.
        if (event.intent == DETACH_OR_REPLACE_INTENT) return DropCause.SelfInflicted(DETACH_OR_REPLACE_INTENT)
        return when (event.reason) {
            // Our own `TmuxClient.close()` / detach. Ignoring these is what broke the
            // #1562 dial -> close -> re-arm -> dial storm.
            TmuxDisconnectReason.ExplicitClose,
            TmuxDisconnectReason.ExplicitDetach,
            -> DropCause.SelfInflicted(event.reason.name)

            // Genuine passive losses — recovery depends on every one of these arming.
            TmuxDisconnectReason.ReaderEof,
            TmuxDisconnectReason.ReaderException,
            TmuxDisconnectReason.CommandTimeout,
            TmuxDisconnectReason.ServerExited,
            -> DropCause.RemoteFailure(event.reason.name)

            // A genuinely unattributable reader exit — the conservative not-self-inflicted
            // bucket, still recovers.
            TmuxDisconnectReason.Unknown -> DropCause.Unknown
        }
    }

    /**
     * Is this `-CC` control-channel disconnect OUR OWN close? Thin predicate over the typed
     * [dropCauseForControlChannelClose] authority. Preserves the #1568 P0-5 behavior exactly.
     */
    fun isSelfInflictedControlChannelClose(event: TmuxDisconnectEvent?): Boolean =
        dropCauseForControlChannelClose(event) is DropCause.SelfInflicted

    /** The #1568 client-swap intent marker carried on a detach we performed ourselves. */
    private const val DETACH_OR_REPLACE_INTENT = "detach_or_replace"
}
