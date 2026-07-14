package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.tmux.TmuxClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 Slice 2 (#1047) — the connection-core CHARACTERIZATION proof for the
 * passive-transport-drop classification, the hard-cut replacement for the deleted inline
 * `TmuxSessionViewModel.classifyPassiveTransportDrop()` selector (D28 single active path;
 * D22 hard-cut).
 *
 * RED→GREEN (the load-bearing proof — the #635 client-identity guard + the #895 status-agnostic
 * contract):
 *  - RED: drop the `!isCurrentClient -> Ignore` guard from [selectPassiveDropArm] (i.e. the
 *    controller/driver acting on EVERY `-CC` close without re-reading the live `clientRef`
 *    identity). Then a STALE old-client close on a healthy fast switch (the #635 spurious-band
 *    case) is WRONGLY treated as actionable, and the per-call re-read fixture
 *    ([classify_reReadsCurrentClientEachCall]) fails. (Demonstrated by stubbing the selector.)
 *  - GREEN: with the client-identity guard, the connection-core [PassiveTransportDropEffects]
 *    reproduces the EXACT inline arm for every fixture, including the NON-happy ones (explicit
 *    detach, stale client, in-app navigation, screen-stopped pause, foreground silent reattach).
 *
 * The inline `classifyPassiveTransportDrop()` mapping this pins (now deleted from the VM):
 *   ExplicitDetach reason / `detach_or_replace` intent          -> Ignore
 *   clientRef !== client (stale old-client close, #635)         -> Ignore
 *   target present && !screenStartedForCleared && navigating    -> SkipInAppNavigation (#630)
 *   target present && !screenStartedForCleared && !navigating   -> PauseUntilForeground
 *   else                                                        -> SilentReattachWithinGrace
 *
 * Issue #895 (#766 down-payment): the selector is STATUS-AGNOSTIC — there is NO connection-status
 * input, so a drop on the CURRENT client during the `Switching`/Attaching window is NOT swallowed
 * (it classifies to a recovery arm), exactly the switch-while-black-band freeze fix. The only gate
 * against acting on a NORMAL fast switch is the client-identity guard, not the status.
 */
class PassiveTransportDropEffectsTest {

    // ---- the PURE selector: class-coverage over the input combinations ----------------

    @Test
    fun selector_explicitDetach_ignored() {
        // Explicit detach / `detach_or_replace` wins FIRST, regardless of the rest (#685 ORDER).
        assertEquals(
            PassiveDropArm.Ignore,
            selectPassiveDropArm(
                isSelfInflictedClose = true,
                isCurrentClient = true,
                hasTarget = true,
                screenStartedForCleared = false,
                navigatingToDifferentSession = true,
            ),
        )
    }

    @Test
    fun selector_staleClient_ignored() {
        // THE #635 load-bearing fixture: a stale old-client `-CC` close on a healthy fast switch
        // (clientRef already points at the NEW client) — must stay Ignore so no spurious band.
        assertEquals(
            PassiveDropArm.Ignore,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = false,
                hasTarget = true,
                screenStartedForCleared = false,
                navigatingToDifferentSession = false,
            ),
        )
    }

    @Test
    fun selector_currentClient_navigatingToDifferentSession_skips() {
        // #630: the screen stopped for an in-app navigation to a DIFFERENT session — skip the
        // pause so it cannot race the new connect.
        assertEquals(
            PassiveDropArm.SkipInAppNavigation,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = true,
                hasTarget = true,
                screenStartedForCleared = false,
                navigatingToDifferentSession = true,
            ),
        )
    }

    @Test
    fun selector_currentClient_screenStopped_notNavigating_pauses() {
        assertEquals(
            PassiveDropArm.PauseUntilForeground,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = true,
                hasTarget = true,
                screenStartedForCleared = false,
                navigatingToDifferentSession = false,
            ),
        )
    }

    @Test
    fun selector_currentClient_noTarget_silentReattach() {
        assertEquals(
            PassiveDropArm.SilentReattachWithinGrace,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = true,
                hasTarget = false,
                screenStartedForCleared = false,
                navigatingToDifferentSession = false,
            ),
        )
    }

    @Test
    fun selector_currentClient_screenStartedForCleared_silentReattach() {
        // screenStartedForCleared = true (a foreground drop) -> the within-grace silent reattach,
        // NOT the pause/skip arm (the pause/skip branch is gated on `!screenStartedForCleared`).
        assertEquals(
            PassiveDropArm.SilentReattachWithinGrace,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = true,
                hasTarget = true,
                screenStartedForCleared = true,
                navigatingToDifferentSession = true,
            ),
        )
    }

    @Test
    fun selector_staleClient_winsOverNavigationArm() {
        // The #685 predicate-ORDER trap: the client-identity guard is checked BEFORE the
        // target/navigation branch, so a stale client is Ignore even when it would otherwise
        // look like an in-app navigation.
        assertEquals(
            PassiveDropArm.Ignore,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = false,
                hasTarget = true,
                screenStartedForCleared = false,
                navigatingToDifferentSession = true,
            ),
        )
    }

    @Test
    fun selector_statusAgnostic_currentClientDropAlwaysRecovers() {
        // Issue #895: there is NO status input — a drop on the CURRENT client (whatever the
        // display status, including the Switching/Attaching window) is NEVER swallowed; it
        // classifies to a recovery arm. Here: current client, no in-app nav -> silent reattach.
        assertEquals(
            PassiveDropArm.SilentReattachWithinGrace,
            selectPassiveDropArm(
                isSelfInflictedClose = false,
                isCurrentClient = true,
                hasTarget = false,
                screenStartedForCleared = true,
                navigatingToDifferentSession = false,
            ),
        )
    }

    // ---- Issue #1568 (P0-2): the within-grace silent-reattach RUNG selector -------------
    // Class coverage (G2): warm-lease × transport-vouch combinations. `preferFreshTransport`
    // (dial the lease-EVICTING fresh transport FIRST) must be TRUE only when a warm lease is
    // held AND the transport vouch FAILS (a genuine transport death) — a vouched-alive channel
    // death recovers over the LIVE transport (rung 2) so a `-CC` hiccup never costs the lease.

    @Test
    fun preferFreshTransport_warmLease_transportVouchedAlive_recoversOverLiveTransport() {
        // THE #1568 load-bearing fixture (channel dead, transport warm): do NOT prefer the
        // lease-evicting fresh dial — recover the channel over the live transport instead.
        assertEquals(
            false,
            preferFreshTransportForPassiveReattach(
                warmLeaseHeld = true,
                transportVouchedAlive = true,
            ),
        )
    }

    @Test
    fun preferFreshTransport_warmLease_transportDead_escalatesToFreshDial() {
        // Non-masking: a genuine transport death (vouch FAILS) with a warm lease still prefers
        // the fresh dial — exactly the old `leaseRef != null` behavior for a dead transport.
        assertEquals(
            true,
            preferFreshTransportForPassiveReattach(
                warmLeaseHeld = true,
                transportVouchedAlive = false,
            ),
        )
    }

    @Test
    fun preferFreshTransport_noWarmLease_neverPrefersFreshDial() {
        // No warm lease to prefer: the warm reattach is the cheap path (the fresh-dial fallback
        // still runs later if the warm session is itself gone) — never prefer fresh first.
        assertEquals(
            false,
            preferFreshTransportForPassiveReattach(
                warmLeaseHeld = false,
                transportVouchedAlive = true,
            ),
        )
        assertEquals(
            false,
            preferFreshTransportForPassiveReattach(
                warmLeaseHeld = false,
                transportVouchedAlive = false,
            ),
        )
    }

    // ---- the CLASSIFIER: re-reads live state per call (#685 re-read trap) --------------

    private val client: TmuxClient = FakeTmuxClient()

    private fun effects(
        isSelfInflictedClose: (TmuxClient) -> Boolean = { false },
        isCurrentClient: (TmuxClient) -> Boolean = { true },
        hasTarget: () -> Boolean = { true },
        screenStartedForCleared: () -> Boolean = { false },
        navigatingToDifferentSession: () -> Boolean = { false },
    ) = PassiveTransportDropEffects(
        isSelfInflictedClose = isSelfInflictedClose,
        isCurrentClient = isCurrentClient,
        hasTarget = hasTarget,
        screenStartedForCleared = screenStartedForCleared,
        navigatingToDifferentSession = navigatingToDifferentSession,
    )

    @Test
    fun classify_explicitDetach_ignored() {
        assertEquals(
            PassiveDropArm.Ignore,
            effects(isSelfInflictedClose = { true }).classify(client),
        )
    }

    @Test
    fun classify_currentClient_navigating_skips() {
        assertEquals(
            PassiveDropArm.SkipInAppNavigation,
            effects(navigatingToDifferentSession = { true }).classify(client),
        )
    }

    @Test
    fun classify_currentClient_screenStopped_pauses() {
        assertEquals(
            PassiveDropArm.PauseUntilForeground,
            effects().classify(client),
        )
    }

    @Test
    fun classify_currentClient_foreground_silentReattach() {
        assertEquals(
            PassiveDropArm.SilentReattachWithinGrace,
            effects(screenStartedForCleared = { true }).classify(client),
        )
    }

    /**
     * The #685 RE-READ trap + the #635 client-identity guard: the classifier must consult the
     * live `clientRef` identity at CALL time, NOT a value snapshotted at construction. A fast
     * switch swaps in a new current client; the OLD client's late `-CC` close must then classify
     * to Ignore (no spurious band), while a drop on the new current client still recovers.
     */
    @Test
    fun classify_reReadsCurrentClientEachCall() {
        val oldClient: TmuxClient = FakeTmuxClient()
        val newClient: TmuxClient = FakeTmuxClient()
        var currentClient: TmuxClient = oldClient
        val fx = effects(isCurrentClient = { it === currentClient })

        // While oldClient is current, its drop recovers (no target/screenStopped -> here pause).
        assertEquals(PassiveDropArm.PauseUntilForeground, fx.classify(oldClient))
        // A fast switch swaps in newClient: the OLD client's late close is now a stale drop.
        currentClient = newClient
        assertEquals(PassiveDropArm.Ignore, fx.classify(oldClient))
        // The new current client's drop still recovers.
        assertEquals(PassiveDropArm.PauseUntilForeground, fx.classify(newClient))
    }
}
