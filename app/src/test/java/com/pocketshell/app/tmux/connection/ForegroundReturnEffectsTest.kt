package com.pocketshell.app.tmux.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 Slice 0 (#1047) — the connection-core CHARACTERIZATION proof for the
 * foreground-return decision, the hard-cut replacement for the deleted inline
 * `TmuxSessionViewModel.reduceForeground()` selector (D28 single active path; D22 hard-cut).
 *
 * RED→GREEN (the retirement pattern this slice proves for the later slices):
 *  - RED: before the wiring, the [ConnectionController]/driver could NOT reproduce the
 *    replay-vs-resume arm — it has only DISPLAY state (Reconnecting), not the VM's
 *    `pendingReattach` / `pausedAutoReconnect` payloads, so a controller-only decision is
 *    impossible. (Demonstrated by the implementer locally by stubbing
 *    [selectForegroundReturnArm] to always return `None` — the Replay / Resume / precedence
 *    cases below FAIL.)
 *  - GREEN: with the two effect payloads wired in, the connection-core
 *    [ForegroundReturnEffects] reproduces the EXACT inline `reduceForeground` arm for every
 *    fixture, including the NON-happy ones (within-grace resume / paused-auto-reconnect)
 *    and the predicate-order precedence (the #685 trap).
 *
 * The inline `reduceForeground()` mapping this pins (now deleted from the VM):
 *   pendingReattach != null      -> ReplayPendingReattach   (replay wins)
 *   pausedAutoReconnect != null  -> ResumePausedReconnect
 *   neither                      -> Ignore / None (no-op)
 */
class ForegroundReturnEffectsTest {

    // ---- the PURE selector: class-coverage over all four input combinations ----------

    @Test
    fun selector_pendingReattachOnly_replays() {
        assertEquals(
            ForegroundReturnArm.ReplayPendingReattach,
            selectForegroundReturnArm(hasPendingReattach = true, hasPausedAutoReconnect = false),
        )
    }

    @Test
    fun selector_pausedAutoReconnectOnly_resumes() {
        // NON-happy fixture: paused-auto-reconnect (the within-grace-resume class).
        assertEquals(
            ForegroundReturnArm.ResumePausedReconnect,
            selectForegroundReturnArm(hasPendingReattach = false, hasPausedAutoReconnect = true),
        )
    }

    @Test
    fun selector_bothSet_replayWinsPrecedence() {
        // The #685 predicate-ORDER trap: a stashed replay takes precedence over a resume,
        // exactly as the inline `reduceForeground` checked pendingReattach FIRST.
        assertEquals(
            ForegroundReturnArm.ReplayPendingReattach,
            selectForegroundReturnArm(hasPendingReattach = true, hasPausedAutoReconnect = true),
        )
    }

    @Test
    fun selector_neitherSet_none() {
        assertEquals(
            ForegroundReturnArm.None,
            selectForegroundReturnArm(hasPendingReattach = false, hasPausedAutoReconnect = false),
        )
    }

    // ---- the DISPATCHER: fires exactly the matching arm body, re-reading live state ---

    private class Recorder {
        var replay = 0
        var resume = 0
        var noArm = 0
    }

    private fun effects(
        hasPendingReattach: () -> Boolean,
        hasPausedAutoReconnect: () -> Boolean,
        rec: Recorder,
    ) = ForegroundReturnEffects(
        hasPendingReattach = hasPendingReattach,
        hasPausedAutoReconnect = hasPausedAutoReconnect,
        replayPendingReattach = { rec.replay += 1 },
        resumePausedAutoReconnect = { rec.resume += 1 },
        onNoPendingArm = { rec.noArm += 1 },
    )

    @Test
    fun dispatch_pendingReattach_firesReplayOnly() {
        val rec = Recorder()
        val arm = effects({ true }, { false }, rec).dispatch()
        assertEquals(ForegroundReturnArm.ReplayPendingReattach, arm)
        assertEquals(1, rec.replay)
        assertEquals(0, rec.resume)
        assertEquals(0, rec.noArm)
    }

    @Test
    fun dispatch_pausedAutoReconnect_firesResumeOnly() {
        // NON-happy fixture: within-grace resume / paused-auto-reconnect.
        val rec = Recorder()
        val arm = effects({ false }, { true }, rec).dispatch()
        assertEquals(ForegroundReturnArm.ResumePausedReconnect, arm)
        assertEquals(0, rec.replay)
        assertEquals(1, rec.resume)
        assertEquals(0, rec.noArm)
    }

    @Test
    fun dispatch_bothSet_firesReplayOnly_precedence() {
        val rec = Recorder()
        val arm = effects({ true }, { true }, rec).dispatch()
        assertEquals(ForegroundReturnArm.ReplayPendingReattach, arm)
        assertEquals(1, rec.replay)
        assertEquals(0, rec.resume)
    }

    @Test
    fun dispatch_neitherSet_firesNoArmOnly() {
        val rec = Recorder()
        val arm = effects({ false }, { false }, rec).dispatch()
        assertEquals(ForegroundReturnArm.None, arm)
        assertEquals(0, rec.replay)
        assertEquals(0, rec.resume)
        assertEquals(1, rec.noArm)
    }

    /**
     * The #685 RE-READ trap: the dispatcher must consult the payload predicates at
     * dispatch time, NOT a value snapshotted at construction. A payload that flips from
     * present to absent between dispatches selects a different arm — proving the live
     * re-read the inline selector did is preserved.
     */
    @Test
    fun dispatch_reReadsLivePayloadsEachCall() {
        val rec = Recorder()
        var hasPending = true
        val fx = ForegroundReturnEffects(
            hasPendingReattach = { hasPending },
            hasPausedAutoReconnect = { false },
            replayPendingReattach = { rec.replay += 1 },
            resumePausedAutoReconnect = { rec.resume += 1 },
            onNoPendingArm = { rec.noArm += 1 },
        )
        assertEquals(ForegroundReturnArm.ReplayPendingReattach, fx.dispatch())
        // The replay consumed the stashed reattach: the live payload is now absent.
        hasPending = false
        assertEquals(ForegroundReturnArm.None, fx.dispatch())
        assertEquals(1, rec.replay)
        assertEquals(1, rec.noArm)
    }
}
