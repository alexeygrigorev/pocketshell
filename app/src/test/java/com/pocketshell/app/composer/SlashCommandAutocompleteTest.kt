package com.pocketshell.app.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.agentcommands.AgentCommandArgument
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.core.agents.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #767: unit tests for the `/`-autocomplete trigger detection, catalog
 * filtering, and the reusable insert path (also #770). These are the pure
 * decisions behind the dropdown — the on-screen layout / keyboard-up reachability
 * is the emulator's job (see PromptComposerSlashAutocompleteImeTest), but the
 * open/closed/filter/insert behaviour is fully testable on the JVM here.
 */
class SlashCommandAutocompleteTest {

    private fun field(text: String, caret: Int = text.length): TextFieldValue =
        TextFieldValue(text = text, selection = TextRange(caret))

    // -- slashQueryFor: when the dropdown opens + what query --------------------

    @Test
    fun `bare slash opens the dropdown with an empty query`() {
        assertEquals("", SlashCommandAutocomplete.slashQueryFor(field("/")))
    }

    @Test
    fun `slash with a partial command yields the typed query`() {
        assertEquals("comp", SlashCommandAutocomplete.slashQueryFor(field("/comp")))
    }

    @Test
    fun `empty draft closes the dropdown`() {
        assertNull(SlashCommandAutocomplete.slashQueryFor(field("")))
    }

    @Test
    fun `text not starting with slash closes the dropdown`() {
        assertNull(SlashCommandAutocomplete.slashQueryFor(field("hello /clear")))
    }

    @Test
    fun `a space after the slash command closes the dropdown (argument is free text)`() {
        // `/goal ` — caret past the leading token: the user is now typing the
        // argument, not filtering commands.
        assertNull(SlashCommandAutocomplete.slashQueryFor(field("/goal ship it")))
    }

    @Test
    fun `a newline bounds the leading slash token (multi-line composer)`() {
        // #777 G3: the doc contract says the leading token ends at the FIRST
        // whitespace — space, tab, OR newline. In a multi-line draft the caret
        // inside the leading token still yields the token's query, bounded by
        // the newline (NOT the whole text). "/comp\nmore" with the caret right
        // after "/comp" → "comp".
        val value = field("/comp\nmore", caret = 5)
        assertEquals("comp", SlashCommandAutocomplete.slashQueryFor(value))
    }

    @Test
    fun `a tab bounds the leading slash token`() {
        // The tab is whitespace too: "/comp\tmore" with the caret inside the
        // leading token yields "comp" — the token stops at the tab.
        val value = field("/comp\tmore", caret = 5)
        assertEquals("comp", SlashCommandAutocomplete.slashQueryFor(value))
    }

    @Test
    fun `a caret past a newline boundary closes the dropdown`() {
        // The newline ends the leading token; once the caret moves onto the
        // SECOND line the dropdown closes (the second line is free text, not a
        // command filter) — same rule the space case enforces.
        val value = field("/comp\nmore", caret = 8)
        assertNull(SlashCommandAutocomplete.slashQueryFor(value))
    }

    @Test
    fun `a newline immediately after the slash yields an empty query`() {
        // "/\nmore" with the caret on the bare slash: the token is just "/",
        // so the query is empty (the full catalog shows) — the newline bounds
        // an already-empty token exactly like the bare "/" case.
        val value = field("/\nmore", caret = 1)
        assertEquals("", SlashCommandAutocomplete.slashQueryFor(value))
    }

    @Test
    fun `caret before the slash token end still opens with the bounded query`() {
        // "/compact" with the caret after "/comp" — query is bounded by the token
        // end, not the caret, so it still matches against the whole token.
        val value = field("/compact", caret = 5)
        assertEquals("compact", SlashCommandAutocomplete.slashQueryFor(value))
    }

    @Test
    fun `caret moved out of the leading token closes the dropdown`() {
        val value = field("/clear extra", caret = 10)
        assertNull(SlashCommandAutocomplete.slashQueryFor(value))
    }

    // -- filteredCommands: per-agent catalog + null agent -----------------------

    @Test
    fun `null agent yields no commands (shell pane never offers the dropdown)`() {
        assertTrue(SlashCommandAutocomplete.filteredCommands(null, "").isEmpty())
    }

    @Test
    fun `empty query returns the full agent catalog`() {
        val claude = SlashCommandAutocomplete.filteredCommands(AgentKind.ClaudeCode, "")
        assertEquals(AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode), claude)
    }

    @Test
    fun `query filters the catalog per engine`() {
        val filtered = SlashCommandAutocomplete.filteredCommands(AgentKind.ClaudeCode, "comp")
        assertTrue(filtered.isNotEmpty())
        assertTrue(
            "every filtered row should match the query",
            filtered.all {
                it.command.contains("comp", true) ||
                    it.label.contains("comp", true) ||
                    it.description.contains("comp", true)
            },
        )
        assertTrue(filtered.any { it.command == "/compact" })
    }

    @Test
    fun `the dropdown reflects the detected engine — codex has slash diff, claude does not`() {
        val codex = SlashCommandAutocomplete.filteredCommands(AgentKind.Codex, "diff")
        assertTrue(codex.any { it.command == "/diff" })
        val claude = SlashCommandAutocomplete.filteredCommands(AgentKind.ClaudeCode, "diff")
        assertTrue(claude.none { it.command == "/diff" })
    }

    // -- insertCommand: zero-arg vs arg-bearing ---------------------------------

    @Test
    fun `picking a zero-arg command replaces the slash token with the command, caret at end`() {
        val clear = AgentCommand("/clear", "New / clear", "desc", destructive = true)
        val result = SlashCommandAutocomplete.insertCommand(field("/cl"), clear)
        assertEquals("/clear", result.text)
        assertEquals(TextRange("/clear".length), result.selection)
    }

    @Test
    fun `picking an arg-bearing command appends a trailing space and lands the caret after it`() {
        val goal = AgentCommand(
            "/goal",
            "Goal",
            "desc",
            argument = AgentCommandArgument("Goal", required = true),
        )
        val result = SlashCommandAutocomplete.insertCommand(field("/go"), goal)
        assertEquals("/goal ", result.text)
        assertEquals(TextRange("/goal ".length), result.selection)
    }

    // -- insertCommandText: the #770 reuse path ---------------------------------

    @Test
    fun `inserting into an empty field prepends the command (issue 770 tap-to-compose)`() {
        val result = SlashCommandAutocomplete.insertCommandText(field(""), "/clear")
        assertEquals("/clear", result.text)
        assertEquals(TextRange("/clear".length), result.selection)
    }

    @Test
    fun `inserting replaces only the leading slash token, preserving trailing text`() {
        // The user had `/cl rest of prompt`; tapping /clear replaces just `/cl`.
        val value = field("/cl rest", caret = 3)
        val result = SlashCommandAutocomplete.insertCommandText(value, "/clear")
        assertEquals("/clear rest", result.text)
        assertEquals(TextRange("/clear".length), result.selection)
    }
}
