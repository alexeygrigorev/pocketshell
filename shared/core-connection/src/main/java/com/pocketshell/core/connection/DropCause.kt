package com.pocketshell.core.connection

/**
 * Issue #1666 (D28 Layer 3) — the TYPED cause of a transport drop, carried on
 * [ConnectionEvent.TransportDropped] so the reducer can PROVE why a transport went
 * away instead of reading a free-text `reason` string it cannot reason about.
 *
 * ## Why the type exists
 * The reconnect storm (#1610/#1680) is self-inflicted teardown-churn: an app-owned
 * close site tears down the shared per-host `-CC` lease, the reader hits EOF, and that
 * self-inflicted EOF is re-ingested as a fresh remote failure — which re-arms the loud
 * auto-reconnect ladder against ourselves, whose own teardown echoes again. The #1643
 * driver filter suppresses the echo at the effect layer, but the pure reducer still
 * could not distinguish "we did this" from "the peer died": any future close path that
 * reaches [ConnectionController.onTransportDropped] could storm-feed the machine.
 *
 * Typing the cause moves the self-inflicted/real decision INTO the event, so the reducer
 * refuses a self-inflicted drop BY CONSTRUCTION — defense in depth BENEATH the driver
 * filter (#1666). The derivation happens ONCE, at the app's single close authority
 * (`SelfInflictedClose`), off the reason the lease manager / `-CC` channel named at its
 * own emit site; consumers never infer "was that us?" from a string (the #1632 rot).
 *
 * ## The rule mirrors [SelfInflictedClose]: LOCAL INTENT, not local execution
 * A drop is [SelfInflicted] only when WE decided the transport should go away while, as
 * far as we knew, it was still usable. A watchdog closing a corpse it observed dead is
 * [RemoteFailure]/[KeepaliveDead] — REPORTING a remote death, not causing one. This is
 * deliberately conservative: suppressing a genuine failure is strictly WORSE than the
 * storm (the app would simply stop reconnecting), so when intent is ambiguous the answer
 * is a non-self-inflicted cause and recovery runs.
 */
sealed interface DropCause {
    /**
     * WE tore the transport/channel down on purpose while it was still usable — recovery's
     * own `disconnect()`, a force-refresh eviction, an idle reap, a lifecycle teardown, a
     * client-swap detach, a bounded-exec/agent-classify self-close. An ECHO of an action
     * already in flight, NOT news of a failure. The reducer REFUSES to advance the episode
     * on this cause: no ladder, no counter, no state change (#1666). [reason] is the
     * canonical cause token, for the log only — it never re-enters the decision.
     */
    data class SelfInflicted(val reason: String) : DropCause

    /**
     * The peer died under us: sshj flipped `isConnected` false, a raw channel-read
     * `SSHException` (the transport was already dead), the `-CC` reader hit EOF/exception,
     * a command timed out, the server exited, a liveness probe confirmed a silent drop.
     * A GENUINE loss — recovery MUST run. The reducer walks the honest ladder
     * (Live → Reattaching → Reconnecting(n) → … → Unreachable).
     */
    data class RemoteFailure(val reason: String) : DropCause

    /**
     * The always-on keepalive watchdog (#945) DETECTED a silent peer death and closed the
     * corpse. We executed the close; the peer caused it — precisely the mobile silent-drop
     * detector. Filtering it would strand the maintainer with no reconnect at all, the one
     * outcome worse than the storm, so this walks the ladder exactly like [RemoteFailure].
     */
    data object KeepaliveDead : DropCause

    /**
     * A drop with no evidence of local intent — the conservative not-self-inflicted bucket
     * (a genuinely unattributable `-CC` reader exit). Carries no local-intent claim, so the
     * reducer treats it as a real drop and recovers. NEVER used to launder a self-inflicted
     * close into a real one: an app-originated close is stamped [SelfInflicted] at the
     * authority; [Unknown] is only the honest "we do not know, so assume genuine" answer.
     */
    data object Unknown : DropCause
}
