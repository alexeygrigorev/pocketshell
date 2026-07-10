package com.pocketshell.uikit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1237 — the `@ps_agent_state` option ↔ [SessionAgentState] mapping and
 * the staleness-aware resolver. The stop/idle hook bus writes only `idle` /
 * `waiting_for_input`, so the option value space is {idle, waiting_for_input,
 * absent}; the mapping must surface an absent/unrecognised option as
 * [SessionAgentState.Unknown] (no chip — "absent, not wrong") and NEVER guess
 * `working`.
 */
class SessionAgentStateTest {

    @Test
    fun `option maps idle and waiting hook values`() {
        assertEquals(SessionAgentState.Idle, sessionAgentStateFromOption("idle"))
        assertEquals(SessionAgentState.Idle, sessionAgentStateFromOption("FINISHED"))
        assertEquals(
            SessionAgentState.WaitingForInput,
            sessionAgentStateFromOption("waiting_for_input"),
        )
        assertEquals(SessionAgentState.WaitingForInput, sessionAgentStateFromOption(" Waiting "))
    }

    @Test
    fun `option maps an explicit working value but never guesses it`() {
        // A host that DOES record `working` renders it; but an absent option is
        // Unknown, never Working — the app must not infer working from attach.
        assertEquals(SessionAgentState.Working, sessionAgentStateFromOption("working"))
        assertEquals(SessionAgentState.Unknown, sessionAgentStateFromOption(null))
        assertEquals(SessionAgentState.Unknown, sessionAgentStateFromOption(""))
        assertEquals(SessionAgentState.Unknown, sessionAgentStateFromOption("   "))
    }

    @Test
    fun `unrecognised option value is Unknown, not a wrong chip`() {
        assertEquals(SessionAgentState.Unknown, sessionAgentStateFromOption("bogus"))
        assertEquals(SessionAgentState.Unknown, sessionAgentStateFromOption("running-forever?"))
    }

    @Test
    fun `chipLabel is null only for Unknown`() {
        assertEquals("Idle", SessionAgentState.Idle.chipLabel)
        assertEquals("Waiting", SessionAgentState.WaitingForInput.chipLabel)
        assertEquals("Working", SessionAgentState.Working.chipLabel)
        assertNull(SessionAgentState.Unknown.chipLabel)
    }

    // --- resolveSessionAgentState: staleness ------------------------------

    @Test
    fun `resolver keeps a fresh idle when activity is not newer than the state write`() {
        // Hook wrote idle at T=1000; the pane's last output was at-or-before T
        // (the completion output happens just before the Stop hook fires).
        assertEquals(
            SessionAgentState.Idle,
            resolveSessionAgentState(
                rawState = "idle",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 1_000L,
            ),
        )
        assertEquals(
            SessionAgentState.WaitingForInput,
            resolveSessionAgentState(
                rawState = "waiting_for_input",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 995L,
            ),
        )
    }

    @Test
    fun `resolver drops a stale resting state when activity is newer than the write`() {
        // The user answered and the agent went back to work; the hook does NOT
        // re-fire for "working", so session_activity bumps past the recorded
        // idle/waiting timestamp. We must show NO chip (Unknown), not a wrong
        // "Idle"/"Waiting".
        assertEquals(
            SessionAgentState.Unknown,
            resolveSessionAgentState(
                rawState = "idle",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 1_100L,
            ),
        )
        assertEquals(
            SessionAgentState.Unknown,
            resolveSessionAgentState(
                rawState = "waiting_for_input",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 2_000L,
            ),
        )
    }

    @Test
    fun `resolver tolerates a small grace so a same-instant race does not flip fresh state`() {
        // Activity a couple seconds after the write (within the grace) is treated
        // as the same event, not new work — the fresh state survives.
        assertEquals(
            SessionAgentState.Idle,
            resolveSessionAgentState(
                rawState = "idle",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 1_000L + AGENT_STATE_STALE_GRACE_SEC,
            ),
        )
        // Just past the grace → stale → Unknown.
        assertEquals(
            SessionAgentState.Unknown,
            resolveSessionAgentState(
                rawState = "idle",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 1_000L + AGENT_STATE_STALE_GRACE_SEC + 1L,
            ),
        )
    }

    @Test
    fun `resolver does not apply staleness to Working`() {
        // A working agent is inherently producing output, so activity newer than
        // its timestamp is expected and must NOT drop it to Unknown.
        assertEquals(
            SessionAgentState.Working,
            resolveSessionAgentState(
                rawState = "working",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 9_999L,
            ),
        )
    }

    @Test
    fun `resolver takes recorded state at face value when a freshness signal is missing`() {
        // Older host CLI that recorded no timestamp: staleness cannot run, so we
        // still show the best-effort recorded state.
        assertEquals(
            SessionAgentState.Idle,
            resolveSessionAgentState(
                rawState = "idle",
                stateUpdatedAtEpochSec = null,
                sessionActivityEpochSec = 9_999L,
            ),
        )
        assertEquals(
            SessionAgentState.WaitingForInput,
            resolveSessionAgentState(
                rawState = "waiting_for_input",
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = null,
            ),
        )
    }

    @Test
    fun `resolver returns Unknown for an absent option regardless of timestamps`() {
        assertEquals(
            SessionAgentState.Unknown,
            resolveSessionAgentState(
                rawState = null,
                stateUpdatedAtEpochSec = 1_000L,
                sessionActivityEpochSec = 500L,
            ),
        )
        assertEquals(
            SessionAgentState.Unknown,
            resolveSessionAgentState(
                rawState = "",
                stateUpdatedAtEpochSec = null,
                sessionActivityEpochSec = null,
            ),
        )
    }
}
