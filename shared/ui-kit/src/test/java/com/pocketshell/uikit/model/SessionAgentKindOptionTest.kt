package com.pocketshell.uikit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Epic #821 Slice 1 — the single `@ps_agent_kind` option ↔ [SessionAgentKind]
 * mapping. [tmuxOptionValue] (write) and [sessionAgentKindFromOption] (read)
 * must be exact inverses for every recordable kind so the manual-classify
 * round-trip is durable and never drifts from the server-side
 * `record_agent_kind` strings.
 */
class SessionAgentKindOptionTest {

    @Test
    fun `option value matches the server-side record_agent_kind strings`() {
        assertEquals("claude", SessionAgentKind.Claude.tmuxOptionValue())
        assertEquals("codex", SessionAgentKind.Codex.tmuxOptionValue())
        assertEquals("opencode", SessionAgentKind.OpenCode.tmuxOptionValue())
        assertEquals("shell", SessionAgentKind.Shell.tmuxOptionValue())
    }

    @Test
    fun `transient and unknown kinds have no recordable option value`() {
        assertNull(SessionAgentKind.Probing.tmuxOptionValue())
        assertNull(SessionAgentKind.Exited.tmuxOptionValue())
        assertNull(SessionAgentKind.Unknown.tmuxOptionValue())
    }

    @Test
    fun `write then read round-trips every recordable kind`() {
        SessionAgentKind.pickable.forEach { kind ->
            val option = kind.tmuxOptionValue()
            assertEquals(kind, sessionAgentKindFromOption(option))
        }
    }

    @Test
    fun `read maps the known option values case-insensitively`() {
        assertEquals(SessionAgentKind.Claude, sessionAgentKindFromOption("CLAUDE"))
        assertEquals(SessionAgentKind.Codex, sessionAgentKindFromOption(" codex "))
        assertEquals(SessionAgentKind.OpenCode, sessionAgentKindFromOption("opencode"))
        assertEquals(SessionAgentKind.Shell, sessionAgentKindFromOption("shell"))
    }

    @Test
    fun `read returns null for a foreign session with no recorded option`() {
        // The signal Workstream B keys on: a blank/absent `@ps_agent_kind`
        // means foreign / not-yet-classified — never a guessed kind.
        assertNull(sessionAgentKindFromOption(null))
        assertNull(sessionAgentKindFromOption(""))
        assertNull(sessionAgentKindFromOption("   "))
        assertNull(sessionAgentKindFromOption("not-an-agent"))
    }

    @Test
    fun `pickable excludes transient and unknown states`() {
        assertEquals(
            listOf(
                SessionAgentKind.Claude,
                SessionAgentKind.Codex,
                SessionAgentKind.OpenCode,
                SessionAgentKind.Shell,
            ),
            SessionAgentKind.pickable,
        )
    }
}
