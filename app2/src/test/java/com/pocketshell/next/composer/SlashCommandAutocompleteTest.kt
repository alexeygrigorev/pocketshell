package com.pocketshell.next.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/`-command dropdown's three pure decisions (rewrite task P-1).
 *
 * Includes the scope-amendment guard: the catalog must stay generic. An
 * engine-specific command sneaking back in is exactly the surface the
 * 2026-09-03 amendment cut, and it would only be noticed on a device otherwise.
 */
class SlashCommandAutocompleteTest {

    @Test
    fun `the catalog carries no agent-specific commands`() {
        val commands = SlashCommandAutocomplete.CATALOG.map { it.command }
        assertTrue("the catalog must not be empty", commands.isNotEmpty())
        val agentFlavoured = listOf("claude", "codex", "agent", "model", "resume", "compact", "cost")
        commands.forEach { command ->
            agentFlavoured.forEach { word ->
                assertTrue(
                    "`$command` looks engine-specific; the amendment cut agentcommands/",
                    !command.lowercase().contains(word),
                )
            }
        }
    }

    @Test
    fun `a leading slash opens the dropdown with the whole catalog`() {
        val query = SlashCommandAutocomplete.queryFor(field("/"))
        assertEquals("", query)
        assertEquals(SlashCommandAutocomplete.CATALOG, SlashCommandAutocomplete.filter(""))
    }

    @Test
    fun `the query is the leading token after the slash`() {
        assertEquals("cle", SlashCommandAutocomplete.queryFor(field("/cle")))
    }

    @Test
    fun `text that does not start with a slash never opens the dropdown`() {
        assertNull(SlashCommandAutocomplete.queryFor(field("run /clear")))
    }

    /**
     * Once the caret has moved past the command token the user is typing an
     * argument, which is free text — filtering on it would be nonsense.
     */
    @Test
    fun `the dropdown closes once the caret leaves the leading token`() {
        assertNull(SlashCommandAutocomplete.queryFor(field("/cd ~/src", caret = 9)))
        // ...but is still open while the caret sits inside the token.
        assertEquals("cd", SlashCommandAutocomplete.queryFor(field("/cd ~/src", caret = 3)))
    }

    @Test
    fun `filtering is a case-insensitive prefix match`() {
        assertEquals(listOf("/clear"), SlashCommandAutocomplete.filter("CL").map { it.command })
        assertTrue(SlashCommandAutocomplete.filter("zzz").isEmpty())
    }

    @Test
    fun `picking a command replaces only the leading token`() {
        val result = SlashCommandAutocomplete.insert(
            field("/cl and the rest", caret = 3),
            SlashCommand("/clear", "d"),
        )
        assertEquals("/clear and the rest", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test
    fun `a command that takes an argument leaves the caret past a trailing space`() {
        val result = SlashCommandAutocomplete.insert(
            field("/c", caret = 2),
            SlashCommand("/cd", "d", argument = "path"),
        )
        assertEquals("/cd ", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun `inserting into a field with no slash token prepends`() {
        val result = SlashCommandAutocomplete.insertText(field("existing", caret = 0), "/clear")
        assertEquals("/clearexisting", result.text)
    }

    private fun field(text: String, caret: Int = text.length) =
        TextFieldValue(text = text, selection = TextRange(caret))
}
