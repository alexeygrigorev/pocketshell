package com.pocketshell.app.tmux.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 Slice 1 (#1047) — the connection-core CHARACTERIZATION proof for the
 * background-transition decision, the hard-cut replacement for the deleted inline
 * `TmuxSessionViewModel.reduceBackground()` selector (D28 single active path; D22 hard-cut).
 *
 * RED→GREEN (the load-bearing proof — the INJECTED-PORT path):
 *  - RED: WITHOUT the injected `hasLiveControlChannel` context, the controller/driver could
 *    NOT reproduce the arm. The controller transitions to `Backgrounded` whenever it holds a
 *    host (a TARGET is present), even from `Reconnecting`, so a controller-only selection
 *    that detaches "whenever there is a target" would WRONGLY detach a session that has NO
 *    live `-CC` client/session — the inline returned `Ignore` there. (Demonstrated by the
 *    implementer locally by stubbing [selectBackgroundArm] to drop the
 *    `hasLiveControlChannel` guard — [dispatch_targetButNoLiveControlChannel_firesNoArmOnly]
 *    and the no-live-channel selector case below FAIL.)
 *  - GREEN: with `hasLiveControlChannel` injected, the connection-core [BackgroundEffects]
 *    reproduces the EXACT inline `reduceBackground` arm for every fixture, including the
 *    NON-happy ones (live-channel-present vs absent, within-grace reconnect-pause, the
 *    multi-session reconnect-ladder precedence).
 *
 * The inline `reduceBackground()` mapping this pins (now deleted from the VM):
 *   inlineConnectionStatus is Reconnecting              -> PauseReconnectForBackground
 *   activeTarget == null && connectingTarget == null    -> Ignore (None)
 *   clientRef == null && sessionRef == null             -> Ignore (None)
 *   else (a live channel exists)                         -> DetachForBackground
 */
class BackgroundEffectsTest {

    // ---- the PURE selector: class-coverage over the input combinations ----------------

    @Test
    fun selector_reconnecting_pauses() {
        // Within-grace / in-flight reconnect ladder: pause wins, regardless of the rest.
        assertEquals(
            BackgroundArm.PauseReconnect,
            selectBackgroundArm(isReconnecting = true, hasTarget = true, hasLiveControlChannel = true),
        )
    }

    @Test
    fun selector_reconnecting_winsEvenWithoutLiveChannel() {
        // The #685 predicate-ORDER trap: the Reconnecting check is FIRST, so it pauses even
        // when there is no live client/session (exactly as the inline checked it first).
        assertEquals(
            BackgroundArm.PauseReconnect,
            selectBackgroundArm(isReconnecting = true, hasTarget = false, hasLiveControlChannel = false),
        )
    }

    @Test
    fun selector_noTarget_none() {
        assertEquals(
            BackgroundArm.None,
            selectBackgroundArm(isReconnecting = false, hasTarget = false, hasLiveControlChannel = false),
        )
    }

    @Test
    fun selector_targetButNoLiveControlChannel_none() {
        // THE INJECTED-PORT case (the load-bearing fixture): a target is present (so the
        // controller is Backgrounded) but there is NO live `-CC` client/session — the inline
        // returned Ignore. Without the injected `hasLiveControlChannel` this would WRONGLY
        // detach.
        assertEquals(
            BackgroundArm.None,
            selectBackgroundArm(isReconnecting = false, hasTarget = true, hasLiveControlChannel = false),
        )
    }

    @Test
    fun selector_targetAndLiveControlChannel_detaches() {
        assertEquals(
            BackgroundArm.DetachForBackground,
            selectBackgroundArm(isReconnecting = false, hasTarget = true, hasLiveControlChannel = true),
        )
    }

    // ---- the DISPATCHER: fires exactly the matching arm body, re-reading live state ---

    private class Recorder {
        var pause = 0
        var detach = 0
        var noArm = 0
    }

    private fun effects(
        isReconnecting: () -> Boolean,
        hasTarget: () -> Boolean,
        hasLiveControlChannel: () -> Boolean,
        rec: Recorder,
    ) = BackgroundEffects(
        isReconnecting = isReconnecting,
        hasTarget = hasTarget,
        hasLiveControlChannel = hasLiveControlChannel,
        pauseReconnectForBackground = { rec.pause += 1 },
        detachForBackground = { rec.detach += 1 },
        onNoArm = { rec.noArm += 1 },
    )

    @Test
    fun dispatch_reconnecting_firesPauseOnly() {
        val rec = Recorder()
        val arm = effects({ true }, { true }, { true }, rec).dispatch()
        assertEquals(BackgroundArm.PauseReconnect, arm)
        assertEquals(1, rec.pause)
        assertEquals(0, rec.detach)
        assertEquals(0, rec.noArm)
    }

    @Test
    fun dispatch_liveChannel_firesDetachOnly() {
        val rec = Recorder()
        val arm = effects({ false }, { true }, { true }, rec).dispatch()
        assertEquals(BackgroundArm.DetachForBackground, arm)
        assertEquals(0, rec.pause)
        assertEquals(1, rec.detach)
        assertEquals(0, rec.noArm)
    }

    @Test
    fun dispatch_targetButNoLiveControlChannel_firesNoArmOnly() {
        // The INJECTED-PORT load-bearing case: target present, no live channel -> no-op.
        val rec = Recorder()
        val arm = effects({ false }, { true }, { false }, rec).dispatch()
        assertEquals(BackgroundArm.None, arm)
        assertEquals(0, rec.pause)
        assertEquals(0, rec.detach)
        assertEquals(1, rec.noArm)
    }

    @Test
    fun dispatch_noTarget_firesNoArmOnly() {
        val rec = Recorder()
        val arm = effects({ false }, { false }, { false }, rec).dispatch()
        assertEquals(BackgroundArm.None, arm)
        assertEquals(0, rec.pause)
        assertEquals(0, rec.detach)
        assertEquals(1, rec.noArm)
    }

    /**
     * Multi-session fixture: a reconnect ladder is in flight (e.g. a fast A→B switch raised
     * `Reconnecting`) and a live client still exists — the pause arm still wins over detach,
     * exactly as the inline checked `Reconnecting` before the detach branch.
     */
    @Test
    fun dispatch_reconnectingWithLiveChannel_pausesNotDetaches() {
        val rec = Recorder()
        val arm = effects({ true }, { true }, { true }, rec).dispatch()
        assertEquals(BackgroundArm.PauseReconnect, arm)
        assertEquals(1, rec.pause)
        assertEquals(0, rec.detach)
    }

    /**
     * The #685 RE-READ trap: the dispatcher must consult the predicates at dispatch time,
     * NOT a value snapshotted at construction. A live channel that goes away between
     * dispatches (the detach consumed it) selects a different arm on the next call.
     */
    @Test
    fun dispatch_reReadsLiveStateEachCall() {
        val rec = Recorder()
        var hasChannel = true
        val fx = BackgroundEffects(
            isReconnecting = { false },
            hasTarget = { true },
            hasLiveControlChannel = { hasChannel },
            pauseReconnectForBackground = { rec.pause += 1 },
            detachForBackground = { rec.detach += 1 },
            onNoArm = { rec.noArm += 1 },
        )
        assertEquals(BackgroundArm.DetachForBackground, fx.dispatch())
        // The detach tore down the live `-CC` channel: the live predicate is now false.
        hasChannel = false
        assertEquals(BackgroundArm.None, fx.dispatch())
        assertEquals(1, rec.detach)
        assertEquals(1, rec.noArm)
    }
}
