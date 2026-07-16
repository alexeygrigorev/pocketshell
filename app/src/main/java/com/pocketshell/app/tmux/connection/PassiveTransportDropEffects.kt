package com.pocketshell.app.tmux.connection

import com.pocketshell.core.tmux.TmuxClient

/**
 * EPIC #687 Slice 2 (#1047) — the PASSIVE-TRANSPORT-DROP classification, the THIRD of the
 * four inline `TmuxSessionViewModel` `reduce*` selectors retired into the connection core
 * under the maintainer's Path-A D28 consolidation (Slice 0 retired `reduceForeground`,
 * Slice 1 retired `reduceBackground`; this slice follows the SAME proven pattern).
 *
 * Before this slice the passive `-CC` control-channel drop was classified by the inline
 * `TmuxSessionViewModel.classifyPassiveTransportDrop()` selector — a SECOND decision
 * authority alongside the [com.pocketshell.core.connection.ConnectionController], the exact
 * dual-authority condition D28 exists to end. The classification decides whether a passive
 * disconnect is acted on at all (the driver's `shouldSubmitControlChannelDrop` stale-client
 * gate) and, when it is, WHICH passive-recovery arm runs. It re-reads context the controller
 * does not have (the live `clientRef` identity, the `activeTarget`/`connectingTarget`, the
 * in-app-navigation intent) — the #685 re-read trap.
 *
 * The inline `classifyPassiveTransportDrop()` and its three passive `ConnectionDecision`
 * variants (`SkipPassiveInAppNavigation` / `PausePassiveUntilForeground` /
 * `SilentReattachWithinGrace`) are DELETED in the same change (D22 hard-cut — no shadow
 * selector, no coexistence). The shared `ConnectionDecision.Ignore` stays because the
 * network reducer (Slice 3) still uses it; the passive-drop Ignore is now
 * [PassiveDropArm.Ignore].
 */
enum class PassiveDropArm {
    /**
     * Not actionable: an explicit detach / `detach_or_replace`, OR the drop is on a STALE
     * (non-current) client — the #635 spurious-band protection. The old inline `Ignore`.
     */
    Ignore,

    /**
     * The screen stopped for an in-app navigation to a DIFFERENT session (#630) — skip the
     * pause entirely so it cannot race the new connect. The old `SkipPassiveInAppNavigation`.
     */
    SkipInAppNavigation,

    /**
     * The screen stopped (app background / explicit leave) — pause the auto-reconnect until
     * foreground rather than racing a grace reattach. The old `PausePassiveUntilForeground`.
     */
    PauseUntilForeground,

    /** Foreground passive disconnect — race the within-grace silent reattach. The old `SilentReattachWithinGrace`. */
    SilentReattachWithinGrace,
}

/**
 * The PURE passive-drop selector — the connection-core replacement for the deleted inline
 * `classifyPassiveTransportDrop()` predicate. The arm ORDER is byte-identical to the inline
 * selector (the #685 predicate-order trap — keep the EXACT inline precedence so behavior is
 * unchanged):
 *
 *   1. [isSelfInflictedClose] (ExplicitDetach/ExplicitClose reason OR `detach_or_replace` intent) -> [PassiveDropArm.Ignore]
 *   2. NOT [isCurrentClient] (the #635 client-identity guard — a stale old-client close)   -> [PassiveDropArm.Ignore]
 *   3. [hasTarget] AND NOT [screenStartedForCleared]:
 *        - [navigatingToDifferentSession] -> [PassiveDropArm.SkipInAppNavigation]
 *        - else                           -> [PassiveDropArm.PauseUntilForeground]
 *   4. else                                                                                  -> [PassiveDropArm.SilentReattachWithinGrace]
 *
 * Issue #1568 (P0-5): [isSelfInflictedClose] now also covers a `TmuxDisconnectReason.ExplicitClose`
 * — a self-inflicted `TmuxClient.close()`, NOT a passive transport loss. Before this, only
 * `ExplicitDetach`/`detach_or_replace` were ignored, so a freshly-dialed client that our OWN
 * code explicitly closed re-armed recovery (`SilentReattachWithinGrace`) and drove the next
 * dial → the `explicit_close → passive_disconnect` reconnect STORM (#1562: dial→close→re-arm→
 * dial, ~6s cadence). A self-inflicted close must NEVER re-arm; only genuine passive drops
 * (reader EOF/exception, command timeout, server exit) do.
 *
 * Issue #895 (#766 down-payment): STATUS-AGNOSTIC. There is intentionally NO
 * `inlineConnectionStatus !is Connected -> Ignore` gate — the old status gate swallowed a
 * drop that landed during the `Switching` (Attaching) window (the R1 switch-while-black
 * freeze). The real protection against acting on the brief `-CC` close of a NORMAL fast
 * switch is the client-identity guard ([isCurrentClient]): during a healthy switch the old
 * client's `disconnected` edge fires AFTER `clientRef` already points at the new client, so
 * it is correctly ignored. A drop on the CURRENT client — whatever the display status — is a
 * real transport loss and must drive recovery.
 */
fun selectPassiveDropArm(
    isSelfInflictedClose: Boolean,
    isCurrentClient: Boolean,
    hasTarget: Boolean,
    screenStartedForCleared: Boolean,
    navigatingToDifferentSession: Boolean,
): PassiveDropArm = when {
    isSelfInflictedClose -> PassiveDropArm.Ignore
    !isCurrentClient -> PassiveDropArm.Ignore
    hasTarget && !screenStartedForCleared ->
        if (navigatingToDifferentSession) {
            PassiveDropArm.SkipInAppNavigation
        } else {
            PassiveDropArm.PauseUntilForeground
        }

    else -> PassiveDropArm.SilentReattachWithinGrace
}

/**
 * Issue #1568 (P0-2): the PURE within-grace silent-reattach RUNG selector. When a warm SSH
 * lease is held AND the SSH transport is VOUCHED alive (the `-CC` control channel died but
 * the transport itself is healthy — `isConnected && !isCloseInitiated`), recover the channel
 * over the LIVE transport (rung 2, [TmuxSessionViewModel.silentlyReattachAfterPassiveDisconnect]
 * — one channel-open + tmux attach, no TCP/KEX/auth/lease churn) FIRST, instead of the
 * lease-EVICTING fresh dial ([TmuxSessionViewModel.silentlyReconnectTransportAfterPassiveDisconnect],
 * whose first act is `sshLeaseManager.disconnect(leaseKey)` — it shoots the shared per-host
 * transport that the conversation loader / upload sidecar also ride, the #1562 amplification).
 *
 * `preferFreshTransport` is TRUE (dial fresh first) ONLY when a warm lease is held AND the
 * transport vouch FAILS — i.e. a genuine transport death (sshj flips `isConnected` false; the
 * #1222 `isCloseInitiated` covers the ~2s async-close staleness window). That is exactly the
 * old `leaseRef != null` behavior for a dead transport, so a real death still escalates to the
 * fresh dial and is never masked. With no warm lease the warm reattach is the cheap path
 * regardless (fresh-dial fallback runs if the warm session is itself gone — unchanged). The
 * vouch precedents are #979 (fatal-timeout ride-through) and #1533 (restore-probe vouch).
 */
fun preferFreshTransportForPassiveReattach(
    warmLeaseHeld: Boolean,
    transportVouchedAlive: Boolean,
): Boolean = warmLeaseHeld && !transportVouchedAlive

/**
 * The connection-core passive-transport-drop authority: the SINGLE owner of the passive
 * `-CC` drop classification. Every passive-drop call site (the driver's
 * `shouldSubmitControlChannelDrop` stale-client gate, the clean-drop test seam, the
 * stale-client breadcrumb observer, and the real `handlePassiveClientDisconnect` dispatch)
 * routes its decision through [classify].
 *
 * The predicates re-read the VM's live state at classification time (the #685 trap: the
 * decision must read CURRENT state — the live `clientRef`, `activeTarget`/`connectingTarget`,
 * the in-app-navigation intent — not a snapshot captured at construction). This type holds
 * the DECISION; the VM holds the state + the per-arm recovery IO.
 */
class PassiveTransportDropEffects(
    private val isCurrentClient: (TmuxClient) -> Boolean,
    private val hasTarget: () -> Boolean,
    private val screenStartedForCleared: () -> Boolean,
    private val navigatingToDifferentSession: () -> Boolean,
) {
    /**
     * Classify the passive drop of [client] from the live predicates. Pure read; fires no IO
     * (the caller — `handlePassiveClientDisconnect` — owns the per-arm recovery IO with its
     * handler-local `target`/`reason`/`disconnectEvent`).
     */
    fun classify(client: TmuxClient): PassiveDropArm =
        selectPassiveDropArm(
            // Issue #1632: the self-inflicted answer comes from the ONE authority
            // ([SelfInflictedClose]) that also answers for the lease edge. The
            // injectable `isSelfInflictedClose` lambda this used to take — the narrow
            // #1568 `-CC`-only filter, inlined in `TmuxSessionViewModel` — is DELETED
            // (D22 hard-cut): two independently-maintained filters is precisely how the
            // lease edge silently never got one and the #1610 storm survived #1568.
            isSelfInflictedClose =
                SelfInflictedClose.isSelfInflictedControlChannelClose(client.disconnectEvent.value),
            isCurrentClient = isCurrentClient(client),
            hasTarget = hasTarget(),
            screenStartedForCleared = screenStartedForCleared(),
            navigatingToDifferentSession = navigatingToDifferentSession(),
        )
}
