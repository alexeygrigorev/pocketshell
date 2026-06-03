package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [sessionKindLabel] (issue #431). The folder-tree session row
 * must render type/agent and activity state as two orthogonal facets:
 *
 *  - Shell session  -> "Shell", with no agent activity state.
 *  - Agent session  -> "<agent> · <state>", e.g. "Codex · Idle", so the state
 *    word never appears bare without its agent.
 *
 * "Idle" specifically means the agent is waiting for input / not actively
 * working. There is no per-session working/idle signal on [FolderSessionEntry]
 * yet, so the agent state defaults to "Idle" (a richer signal is a #430/#438
 * follow-up); these tests pin the rendering that is correct for today's data.
 */
class SessionKindLabelTest {

    private fun entry(
        agentKind: SessionAgentKind,
        attached: Boolean = false,
    ): FolderSessionEntry = FolderSessionEntry(
        sessionName = "demo",
        lastActivity = 0L,
        attached = attached,
        agentKind = agentKind,
    )

    // --- Acceptance: a shell session renders "Shell", no agent activity state ---

    @Test
    fun shellSessionRendersShell() {
        assertEquals("Shell", sessionKindLabel(entry(SessionAgentKind.Shell)))
    }

    @Test
    fun attachedShellStillRendersShellNotActive() {
        // Regression: the old label collapsed Shell to "Active"/"Idle". A shell
        // must never carry an agent-style activity word.
        assertEquals("Shell", sessionKindLabel(entry(SessionAgentKind.Shell, attached = true)))
    }

    // --- Acceptance: agent session renders "<agent> · <state>", never bare ---

    @Test
    fun claudeAgentRendersNameAndState() {
        assertEquals("Claude · Idle", sessionKindLabel(entry(SessionAgentKind.Claude)))
    }

    @Test
    fun codexAgentRendersNameAndState() {
        assertEquals("Codex · Idle", sessionKindLabel(entry(SessionAgentKind.Codex)))
    }

    @Test
    fun openCodeAgentRendersNameAndState() {
        assertEquals("OpenCode · Idle", sessionKindLabel(entry(SessionAgentKind.OpenCode)))
    }

    @Test
    fun noAgentLabelIsEverBareIdle() {
        // The conflated-label bug surfaced as a row reading just "Idle". Assert
        // that any agent state word always travels with its agent identity.
        val agents = listOf(
            SessionAgentKind.Claude,
            SessionAgentKind.Codex,
            SessionAgentKind.OpenCode,
        )
        agents.forEach { kind ->
            val label = sessionKindLabel(entry(kind))
            assertEquals(true, label.contains(" · "))
            assertEquals(false, label == "Idle")
        }
    }

    // --- Probing / Exited edge states ---

    @Test
    fun probingRendersDetecting() {
        // Agent identity not yet known: no state word, no bare "Idle".
        assertEquals("Detecting", sessionKindLabel(entry(SessionAgentKind.Probing)))
    }

    @Test
    fun exitedRendersShell() {
        // The agent process is gone; the session is a plain shell again.
        assertEquals("Shell", sessionKindLabel(entry(SessionAgentKind.Exited)))
    }
}
