package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.SessionAgentState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1237: [FolderSessionRow.toSessionEntry] must resolve the raw
 * `@ps_agent_state` option (+ its timestamp) against the session activity into
 * the chip [SessionAgentState] the UI renders — including dropping a resting
 * state that has gone stale relative to activity, and never showing a chip for
 * an absent/unknown state.
 */
class FolderSessionRowAgentStateResolutionTest {

    private fun row(
        stateRaw: String?,
        stateUpdatedAt: Long?,
        activity: Long?,
        kind: SessionAgentKind = SessionAgentKind.Claude,
    ) = FolderSessionRow(
        sessionName = "s",
        lastActivity = activity,
        attached = true,
        cwd = "/srv/a",
        agentKind = kind,
        recordedKind = kind,
        agentStateRaw = stateRaw,
        agentStateUpdatedAt = stateUpdatedAt,
    )

    @Test
    fun idleAndWaitingResolveToTheirChipStateWhenFresh() {
        assertEquals(
            SessionAgentState.Idle,
            row("idle", stateUpdatedAt = 1_000L, activity = 1_000L).toSessionEntry().agentState,
        )
        assertEquals(
            SessionAgentState.WaitingForInput,
            row("waiting_for_input", stateUpdatedAt = 1_000L, activity = 990L)
                .toSessionEntry().agentState,
        )
    }

    @Test
    fun agentRestingStateGoesWorkingWhenActivityIsNewerThanTheHookWrite() {
        // Issue #1570: the user answered and the agent resumed work; the
        // stop/idle hook fires only on stop, so it never records the resume,
        // but session_activity is now newer than the recorded idle/waiting. For
        // a LIVE agent that fresh output IS the agent working — surface Working
        // (the "working Codex shows Idle" report), not a wrong "Idle".
        assertEquals(
            SessionAgentState.Working,
            row("idle", stateUpdatedAt = 1_000L, activity = 5_000L).toSessionEntry().agentState,
        )
        assertEquals(
            SessionAgentState.Working,
            row("waiting_for_input", stateUpdatedAt = 1_000L, activity = 5_000L)
                .toSessionEntry().agentState,
        )
    }

    @Test
    fun nonAgentStaleRestingStateStaysUnknownNoWrongChip() {
        // A plain shell is not a live agent: fresh activity cannot be attributed
        // to an agent working, so a stale recorded resting state stays Unknown
        // (no chip) — the #1237 "absent, not wrong" rule, unchanged for shells.
        assertEquals(
            SessionAgentState.Unknown,
            row("idle", stateUpdatedAt = 1_000L, activity = 5_000L, kind = SessionAgentKind.Shell)
                .toSessionEntry().agentState,
        )
    }

    @Test
    fun absentStateResolvesToUnknownNoChip() {
        assertEquals(
            SessionAgentState.Unknown,
            row(null, stateUpdatedAt = null, activity = 1_000L).toSessionEntry().agentState,
        )
        assertEquals(
            SessionAgentState.Unknown,
            row("", stateUpdatedAt = null, activity = 1_000L).toSessionEntry().agentState,
        )
    }

    @Test
    fun workingIsNotDroppedByNewerActivity() {
        assertEquals(
            SessionAgentState.Working,
            row("working", stateUpdatedAt = 1_000L, activity = 9_000L).toSessionEntry().agentState,
        )
    }
}
