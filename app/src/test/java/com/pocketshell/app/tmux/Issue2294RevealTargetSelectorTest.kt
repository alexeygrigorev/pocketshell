package com.pocketshell.app.tmux

import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.terminalHeld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2294: exercise the render-path identity handoff, not just the VM
 * metadata promotion. The selector feeds the same [sessionSurfaceState] fusion
 * that decides whether the terminal is held or exposed by the screen.
 */
class Issue2294RevealTargetSelectorTest {

    @Test
    fun nameOnlyRouteFollowsMatchingExactRevealForRenderedSurface() {
        val routeTargetId = tmuxTargetSessionId(
            hostId = 42L,
            sessionName = "cold",
            tmuxSessionId = null,
            sessionCreated = null,
        )
        val exactTargetId = SessionId("tmux:42:\$7:1700000003")
        val reveal = RevealState.Live(
            targetId = exactTargetId,
            targetName = "cold",
            panes = listOf(Seed(exactTargetId, "%0", "prompt")),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = "cold",
            routeTmuxSessionId = null,
            routeSessionCreated = null,
            reveal = reveal,
        )
        val surface = sessionSurfaceState(
            reveal = reveal,
            phase = ConnectionPhase.Live("10.0.2.2", 2222, "alex"),
            targetId = renderedTargetId,
        )

        assertEquals("the cold route starts name-only", SessionId("42/cold"), routeTargetId)
        assertEquals("the render fence follows the exact reveal identity", exactTargetId, renderedTargetId)
        assertFalse("the matching exact reveal must expose the terminal", surface.terminalHeld)
        assertTrue(surface is SessionSurfaceState.Live)
    }

    @Test
    fun staleRevealForDifferentSessionStaysBehindRequestedRouteFence() {
        val routeTargetId = SessionId("42/cold")
        val staleTargetId = SessionId("tmux:42:\$8:1700000004")
        val staleReveal = RevealState.Live(
            targetId = staleTargetId,
            targetName = "other",
            panes = listOf(Seed(staleTargetId, "%0", "stale")),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = "cold",
            routeTmuxSessionId = null,
            routeSessionCreated = null,
            reveal = staleReveal,
        )
        val surface = sessionSurfaceState(
            reveal = staleReveal,
            phase = ConnectionPhase.Connecting("10.0.2.2", 2222, "alex"),
            targetId = renderedTargetId,
        )

        assertEquals("a different-session reveal must not retarget this route", routeTargetId, renderedTargetId)
        assertFalse("a stale reveal must not become this route's live surface", surface is SessionSurfaceState.Live)
        assertTrue(surface.terminalHeld)
    }

    @Test
    fun explicitRouteGenerationWinsOverConflictingRevealGeneration() {
        val routeTargetId = SessionId("tmux:42:\$9:1700000005")
        val staleReveal = RevealState.Live(
            targetId = SessionId("tmux:42:\$8:1700000004"),
            targetName = "cold",
            panes = emptyList(),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = "cold",
            routeTmuxSessionId = "\$9",
            routeSessionCreated = 1_700_000_005L,
            reveal = staleReveal,
        )
        val surface = sessionSurfaceState(
            reveal = staleReveal,
            phase = ConnectionPhase.Connecting("10.0.2.2", 2222, "alex"),
            targetId = renderedTargetId,
        )

        assertEquals(routeTargetId, renderedTargetId)
        assertFalse("a conflicting generation must remain fenced", surface is SessionSurfaceState.Live)
        assertTrue(surface.terminalHeld)
    }
}
