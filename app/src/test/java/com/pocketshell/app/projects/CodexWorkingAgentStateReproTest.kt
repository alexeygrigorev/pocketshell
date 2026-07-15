package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1570 reproduce-first (D33/G10): a Codex agent actively working is
 * mislabeled **"Idle"** in the session tree.
 *
 * ## The real-host format the happy fixtures never fed
 *
 * The generated host stop/idle hook records `@ps_agent_state_updated_at` as
 * `datetime.now(timezone.utc).isoformat()` — an **ISO-8601 string**
 * (`2023-11-14T22:13:20+00:00`), NOT the epoch integer every prior fixture used
 * and every consumer docstring claimed. `SshFolderListGateway.parseRow` read it
 * with a bare `toLongOrNull()`, so the real value parsed to `null`, the
 * staleness rule in `resolveSessionAgentState` could never fire, and a stale
 * recorded `idle` (from the agent's last turn-stop) was shown at face value as
 * "Idle" forever — even while the agent had resumed working and was
 * continuously redrawing its `Working (…· esc to interrupt)` timer (which bumps
 * `session_activity` well past the recorded idle-stop).
 *
 * This exercises the REAL parse path end-to-end (list-sessions row →
 * `parseListSessionsRows` → `toSessionEntry`) with the ISO format the host
 * actually writes.
 *
 * RED on base: every row below resolves to `Idle` (ISO → null → never stale).
 * GREEN with the fix: the working Codex/Claude rows resolve to `Working`.
 *
 * Class coverage (D32 G2): Codex working, Codex idle-at-prompt, Claude working,
 * plus the epoch-int backward-compat shape and the non-agent (shell) case.
 */
class CodexWorkingAgentStateReproTest {

    // `2023-11-14T22:13:20+00:00` == epoch 1_700_000_000. A working agent's
    // session_activity is well past that (it keeps emitting output); an
    // idle-at-prompt agent's activity sits at the recorded stop time.
    private val recordedIdleIso = "2023-11-14T22:13:20+00:00"
    private val recordedIdleEpoch = 1_700_000_000L
    private val laterActivity = 1_700_010_000L

    private fun agentStateOf(row: String): SessionAgentState {
        val rows = SshFolderListGateway.parseListSessionsRows(row + "\n")
        assertEquals("expected exactly one parsed row for: $row", 1, rows.size)
        return rows[0].toSessionEntry().agentState
    }

    @Test
    fun workingCodexWithIsoTimestampShowsWorkingNotIdle() {
        // The exact reported scenario: a Codex (gpt-5.x) session recorded `idle`
        // on its last turn-stop, then resumed and is actively working; the host
        // wrote the ISO timestamp. It MUST read Working, not "Idle".
        assertEquals(
            SessionAgentState.Working,
            agentStateOf(
                "git-ai-shipping-labs::\$7::1699990000::$laterActivity::1::codex::::" +
                    "idle::$recordedIdleIso::/home/alexey/git/labs",
            ),
        )
    }

    @Test
    fun idleAtPromptCodexWithIsoTimestampStaysIdle() {
        // A Codex resting at its prompt produces no new output, so activity sits
        // at the recorded stop time (within grace) — it MUST stay Idle, not flip
        // to a wrong Working.
        assertEquals(
            SessionAgentState.Idle,
            agentStateOf(
                "codex-idle::\$8::1699990000::$recordedIdleEpoch::1::codex::::" +
                    "idle::$recordedIdleIso::/srv/idle",
            ),
        )
    }

    @Test
    fun workingClaudeWithIsoTimestampShowsWorking() {
        assertEquals(
            SessionAgentState.Working,
            agentStateOf(
                "claude-work::\$9::1699990000::$laterActivity::1::claude::::" +
                    "idle::$recordedIdleIso::/srv/cw",
            ),
        )
    }

    @Test
    fun workingCodexWithEpochIntTimestampStillShowsWorking() {
        // Backward-compat: a host that wrote an epoch-int timestamp resolves the
        // same way — stale idle on a live agent → Working.
        assertEquals(
            SessionAgentState.Working,
            agentStateOf(
                "codex-epoch::\$5::1699990000::$laterActivity::1::codex::::" +
                    "idle::$recordedIdleEpoch::/srv/e",
            ),
        )
    }

    @Test
    fun headerActiveCountAndPerRowBadgeAgreeForWorkingAgents() {
        // Issue #1570 criterion #3: the host header "N active · M idle" counts a
        // DIFFERENT axis than the per-row state badge — "active/idle" partitions
        // by agent KIND (agent vs plain shell, #489/#663), while the badge shows
        // agent STATE (idle/working/waiting). The maintainer's reported
        // contradiction ("21 active · 0 idle" while rows read "Idle") was caused
        // by working agents being MIS-badged "Idle". With the detection fix a
        // working Codex is counted "active" (an agent) AND badged "Working" — the
        // two signals now agree: an active row is never contradicted by an "Idle"
        // badge on a working agent.
        val entries = SshFolderListGateway.parseListSessionsRows(
            "git-labs::\$1::1699990000::$laterActivity::1::codex::::" +
                "idle::$recordedIdleIso::/srv/a\n" +
                "git-app::\$2::1699990000::$laterActivity::1::codex::::" +
                "idle::$recordedIdleIso::/srv/b\n",
        ).map { it.toSessionEntry() }

        val groups = FlatSessionGroups.from(entries)
        // Header count: both working Codex sessions are agent-kind → "active",
        // zero shells → "idle". No session is counted idle-kind.
        assertEquals(2, groups.activeCount)
        assertEquals(0, groups.idleCount)
        // Per-row badge for those same "active" rows: Working, NOT the wrong
        // "Idle" the maintainer saw — so the header and the badges agree.
        assertEquals(
            listOf(SessionAgentState.Working, SessionAgentState.Working),
            groups.active.map { it.agentState },
        )
    }

    @Test
    fun staleShellWithIsoTimestampStaysUnknownNoChip() {
        // A plain shell is not a live agent: fresh activity after a recorded
        // resting state cannot be attributed to an agent, so it stays Unknown
        // (no chip), never a guessed Working.
        assertEquals(
            SessionAgentState.Unknown,
            agentStateOf(
                "build-shell::\$6::1699990000::$laterActivity::1::shell::::" +
                    "idle::$recordedIdleIso::/srv/sh",
            ),
        )
    }
}
