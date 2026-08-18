package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1158 (recurrence of #962/#1057) — the SERVER-TRUTH `#{alternate_on}`
 * signal that restores the Conversation tab for an agent launched directly inside
 * a `@ps_agent_kind=shell` session (where live detection never binds and the
 * client emulator never sees the alt buffer). These tests lock the SOURCE of the
 * signal: the `list-panes` format string requests `#{alternate_on}`, and
 * [parsePaneRow] reads it into [TmuxSessionViewModel.ParsedPane.alternateOn]
 * WITHOUT disturbing any earlier field's index. The latch behaviour that consumes
 * this field is covered by
 * `TmuxSessionAgentDetectionStateTest.altBufferAgent*` (red→green).
 */
class TmuxPaneAlternateOnParseTest {

    /** Build a `list-panes -F` row in the production `#{...}` field order. */
    private fun row(
        paneId: String = "%3",
        windowId: String = "@1",
        windowIndex: Int = 2,
        sessionId: String = "$0",
        sessionName: String = "work",
        title: String = "claude",
        paneIndex: Int = 0,
        cwd: String = "/home/u/proj",
        currentCommand: String = "node",
        paneTty: String = "/dev/pts/5",
        inMode: String = "0",
        panePid: Long = 4321L,
        alternateOn: String = "1",
        sessionCreated: Long = 1_700_000_123L,
        sourceGeneration: String = "gen-1",
    ): String = listOf(
        paneId, windowId, windowIndex.toString(), sessionId, sessionName, title,
        paneIndex.toString(), cwd, currentCommand, paneTty, inMode, panePid.toString(),
        alternateOn,
        sessionCreated.toString(),
        sourceGeneration,
    ).joinToString(LIST_PANES_FIELD_SEPARATOR)

    @Test
    fun listingCommandRequestsAlternateOnAndSourceGenerationAsFinalFields() {
        val command = buildTmuxPaneListingCommand("work")
        assertTrue(
            "the reconcile must request the SERVER-TRUTH alt-buffer flag",
            "#{alternate_on}" in command,
        )
        assertTrue(
            "#2155: the existing list-panes reconcile must also request the " +
                "live @ps_agent_source_generation so a same-command relaunch " +
                "retriggers detection (no extra SSH, no poll)",
            "#{@ps_agent_source_generation}" in command,
        )
        // Source generation is appended LAST so every earlier field keeps its
        // historical index (session_created, alternate_on, pane_pid, …).
        assertTrue(
            "source generation must be the final requested field",
            command.trimEnd().endsWith("#{@ps_agent_source_generation}'"),
        )
        assertTrue("pane_pid must still precede alternate_on", "#{pane_pid}" in command)
        assertTrue(
            "session_created must still precede the source-generation field",
            command.indexOf("#{session_created}") <
                command.indexOf("#{@ps_agent_source_generation}"),
        )
    }

    @Test
    fun parsesAlternateOnTrueForFullScreenAgentPane() {
        val parsed = requireNotNull(parsePaneRow(row(alternateOn = "1")))
        assertTrue(
            "#1158: `#{alternate_on}=1` (a full-screen agent TUI) parses to alternateOn=true",
            parsed.alternateOn,
        )
        // The field-index invariant: adding alternate_on last must NOT shift the
        // earlier fields the foreign-kind guess + detection depend on.
        assertEquals("%3", parsed.paneId)
        assertEquals(4321L, parsed.panePid)
        assertEquals("node", parsed.currentCommand)
        assertEquals("/dev/pts/5", parsed.paneTty)
        assertEquals(1_700_000_123L, parsed.sessionCreated)
        assertEquals("gen-1", parsed.sourceGeneration)
    }

    @Test
    fun parsesSourceGenerationFromTheListPanesRow() {
        val parsed = requireNotNull(parsePaneRow(row(sourceGeneration = "issue2155-launch-2")))
        assertEquals(
            "#2155: `#{@ps_agent_source_generation}` must parse so a generation " +
                "bump on the existing reconcile can retrigger detection",
            "issue2155-launch-2",
            parsed.sourceGeneration,
        )
        // Field-index invariant: appending last must not shift earlier fields.
        assertEquals("%3", parsed.paneId)
        assertEquals(4321L, parsed.panePid)
        assertEquals(1_700_000_123L, parsed.sessionCreated)
        assertTrue(parsed.alternateOn)
    }

    @Test
    fun missingSourceGenerationFieldDefaultsEmptyForOlderTmux() {
        val withoutGeneration = listOf(
            "%3", "@1", "2", "$0", "work", "claude", "0", "/home/u/proj", "node",
            "/dev/pts/5", "0", "4321", "1", "1700000123",
        ).joinToString(LIST_PANES_FIELD_SEPARATOR)
        val parsed = requireNotNull(parsePaneRow(withoutGeneration))
        assertEquals(
            "older format omitting `#{@ps_agent_source_generation}` must default empty",
            "",
            parsed.sourceGeneration,
        )
        assertEquals(1_700_000_123L, parsed.sessionCreated)
    }

    @Test
    fun parsesAlternateOnFalseForPlainShellPane() {
        val parsed = requireNotNull(parsePaneRow(row(alternateOn = "0")))
        assertFalse(
            "#894 no-flap: a plain shell at a prompt reads `#{alternate_on}=0`",
            parsed.alternateOn,
        )
    }

    @Test
    fun missingAlternateOnFieldDefaultsFalseForOlderTmux() {
        // Missing-data / older-tmux row (the format field absent entirely): the
        // parser must default alternateOn to false — no spurious latch, no crash.
        val legacyRow = listOf(
            "%3", "@1", "2", "$0", "work", "claude", "0", "/home/u/proj", "node",
            "/dev/pts/5", "0", "4321",
        ).joinToString(LIST_PANES_FIELD_SEPARATOR)
        val parsed = requireNotNull(parsePaneRow(legacyRow))
        assertFalse(
            "older tmux omitting `#{alternate_on}` must default alternateOn=false",
            parsed.alternateOn,
        )
        assertEquals("pane_pid still parses on the legacy row", 4321L, parsed.panePid)
    }
}
