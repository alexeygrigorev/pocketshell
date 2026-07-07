package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1326 (S3): the [sessionSurfaceState] fusion is the KEYSTONE that makes a
 * contradictory (pill, surface, band) triple type-UNREPRESENTABLE. These pin the
 * fusion + the derived surface signals at the core level (the app screen renders
 * strictly from these).
 */
class SessionSurfaceStateTest {

    private val sid = SessionId("host/work")
    private val other = SessionId("host/other")

    private fun connecting() = ConnectionPhase.Connecting("h", 22, "u")
    private fun reconnecting() = ConnectionPhase.Reconnecting("h", 22, "u", 2, 3, 0L)

    @Test
    fun errorReveal_fusesToFailed_carryingTypedReason() {
        val state = sessionSurfaceState(
            RevealState.Error(sid, "work", retrying = false, reason = FailureReason.AuthFailed),
            reconnecting(),
            sid,
        )
        assertTrue(state is SessionSurfaceState.Failed)
        assertEquals(FailureReason.AuthFailed, (state as SessionSurfaceState.Failed).reason)
        // A settled failure is a calm-failure surface, never a loader.
        assertTrue(state.showsCalmFailure)
        assertFalse(state.showsCenteredLoader(panesEmpty = true))
        assertTrue(state.terminalHeld)
    }

    @Test
    fun goneReveal_fusesToGone_calmFailure() {
        val state = sessionSurfaceState(RevealState.Gone(sid, "work"), ConnectionPhase.Failed, sid)
        assertTrue(state is SessionSurfaceState.Gone)
        assertTrue(state.showsCalmFailure)
        assertFalse(state.showsCenteredLoader(panesEmpty = false))
    }

    @Test
    fun liveReveal_dominatesPhase_withinGraceSuppression() {
        // #685/#1098: a Live reveal is the surface authority — even if the phase is
        // still Reconnecting (within-grace silent heal), the surface stays Live with
        // NO loader. The load-bearing green is the SUPPRESSION.
        listOf(connecting(), reconnecting(), ConnectionPhase.Live("h", 22, "u")).forEach { phase ->
            val state = sessionSurfaceState(RevealState.Live(sid, "work", emptyList()), phase, sid)
            assertTrue("live dominates $phase", state is SessionSurfaceState.Live)
            assertFalse(state.terminalHeld)
            assertFalse("no loader over a live frame", state.showsCenteredLoader(panesEmpty = false))
            assertFalse(state.showsCalmFailure)
        }
    }

    @Test
    fun seedingHold_takesFlavorFromPhase() {
        assertTrue(
            sessionSurfaceState(RevealState.Seeding(sid, "work"), connecting(), sid)
                is SessionSurfaceState.Connecting,
        )
        assertTrue(
            sessionSurfaceState(RevealState.Seeding(sid, "work"), reconnecting(), sid)
                is SessionSurfaceState.Reconnecting,
        )
        assertTrue(
            sessionSurfaceState(RevealState.Seeding(sid, "work"), ConnectionPhase.Warm("h", 22, "u"), sid)
                is SessionSurfaceState.Attaching,
        )
        // A held reveal + a settled Failed phase resolves to the calm failure surface.
        assertTrue(
            sessionSurfaceState(RevealState.Seeding(sid, "work"), ConnectionPhase.Failed, sid)
                is SessionSurfaceState.Failed,
        )
    }

    @Test
    fun liveForAnotherTarget_isNeverPaintedAsThisScreensLive() {
        // Defensive: a Live reveal for a DIFFERENT target than the screen is keyed to
        // is a superseded frame — held, never painted as this screen's live terminal.
        val state = sessionSurfaceState(
            RevealState.Live(other, "other", emptyList()),
            connecting(),
            targetId = sid,
        )
        assertFalse(state is SessionSurfaceState.Live)
        assertTrue(state.terminalHeld)
    }

    @Test
    fun everyRevealTimesPhase_contradictionUnrepresentable() {
        // The keystone invariant across every reveal x phase pair: a centered loader
        // and a calm failure can NEVER both paint.
        val reveals = listOf(
            RevealState.Idle,
            RevealState.Navigating(sid, "work"),
            RevealState.Seeding(sid, "work"),
            RevealState.Live(sid, "work", emptyList()),
            RevealState.Gone(sid, "work"),
            RevealState.Error(sid, "work", retrying = false, reason = FailureReason.KeyMissing),
        )
        val phases = listOf(
            ConnectionPhase.Idle,
            connecting(),
            ConnectionPhase.Warm("h", 22, "u"),
            ConnectionPhase.Live("h", 22, "u"),
            reconnecting(),
            ConnectionPhase.Failed,
        )
        reveals.forEach { reveal ->
            phases.forEach { phase ->
                val state = sessionSurfaceState(reveal, phase, sid)
                listOf(true, false).forEach { panesEmpty ->
                    assertFalse(
                        "reveal=$reveal phase=$phase panesEmpty=$panesEmpty: loader AND failure",
                        state.showsCalmFailure && state.showsCenteredLoader(panesEmpty),
                    )
                    // surfaceOwnsPrimary is exactly the OR of the two surface indicators.
                    assertEquals(
                        state.showsCalmFailure || state.showsCenteredLoader(panesEmpty),
                        state.surfaceOwnsPrimary(panesEmpty),
                    )
                }
            }
        }
    }
}
