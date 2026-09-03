package com.pocketshell.next.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * One row of the `/`-triggered command dropdown.
 *
 * [argument] is the placeholder shown after the command when it takes one; a
 * pick with an argument leaves the caret past a trailing space so the user can
 * type straight into it.
 */
internal data class SlashCommand(
    val command: String,
    val description: String,
    val argument: String? = null,
)

/**
 * The `/`-triggered inline command autocomplete (rewrite task P-1, ported from
 * the old client's `SlashCommandAutocomplete`).
 *
 * ## What changed in the port: the catalog is generic, not per-engine
 *
 * The old version delegated its rows to `AgentCommandCatalog`, which offered a
 * different `/`-command set depending on which agent CLI was detected in the
 * pane. That catalog is cut (the 2026-09-03 scope amendment drops all
 * engine-specific surfaces), and with it the `AgentKind` parameter — so the
 * dropdown is now a small fixed list of commands that mean the same thing in
 * ANY interactive program: clear the screen, exit, and the two the user is most
 * likely to want typed for them.
 *
 * That is a real trim, not a stub. A `/`-command is just text the composer
 * inserts into the draft — the host decides what it means — so nothing here
 * needs to know what is running on the other end. If a session's program does
 * not understand `/clear`, the user sees the program say so, exactly as if they
 * had typed it.
 *
 * The three pure decisions the dropdown is made of live here so the composable
 * stays a renderer and the behaviour is unit-tested without an emulator:
 *
 *  - [queryFor] — is the dropdown open, and what is the filter?
 *  - [filter] — the rows matching that query.
 *  - [insert] — the field value after a row is chosen.
 */
internal object SlashCommandAutocomplete {

    /**
     * Commands offered for any session.
     *
     * Kept deliberately short. A long menu of things the host might not
     * understand is worse than no menu: the value of the dropdown is that
     * typing `/` gets you the two or three you actually reach for.
     */
    val CATALOG: List<SlashCommand> = listOf(
        SlashCommand("/clear", "Clear the screen or the program's context"),
        SlashCommand("/help", "Ask the running program for its own help"),
        SlashCommand("/exit", "Leave the running program"),
        SlashCommand("/cd", "Change directory", argument = "path"),
    )

    /**
     * The query for the dropdown, or `null` when it must be CLOSED.
     *
     * Open only when the field's LEADING token starts with `/` and the caret
     * sits inside that token — so once the user has typed a space and moved on
     * to the argument the list closes, because a command's argument is free
     * text and not a filter. The returned query is everything after the leading
     * `/` up to the first whitespace, so a bare `/` (blank query) shows the
     * whole catalog: type one character, see the list.
     */
    fun queryFor(value: TextFieldValue): String? {
        val text = value.text
        if (!text.startsWith("/")) return null
        val tokenEnd = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val caret = value.selection.start
        if (caret < 0 || caret > tokenEnd) return null
        return text.substring(1, tokenEnd)
    }

    /**
     * Catalog rows matching [query] (a blank query matches everything).
     *
     * Prefix match on the command, case-insensitive: `/cl` finds `/clear`, and
     * a query that matches nothing closes the list rather than showing a
     * "no results" row nobody can act on.
     */
    fun filter(query: String): List<SlashCommand> {
        if (query.isBlank()) return CATALOG
        val needle = query.lowercase()
        return CATALOG.filter { it.command.removePrefix("/").lowercase().startsWith(needle) }
    }

    /**
     * The field value after [command] is picked: the chosen text replaces ONLY
     * the leading slash token (anything the user already typed after a space
     * survives), a trailing space is added when the command takes an argument,
     * and the caret lands at the end of the insertion.
     */
    fun insert(value: TextFieldValue, command: SlashCommand): TextFieldValue =
        insertText(value, if (command.argument != null) command.command + " " else command.command)

    /** [insert] for arbitrary text — the shared entry point for any "prefill the composer" action. */
    fun insertText(value: TextFieldValue, commandText: String): TextFieldValue {
        val text = value.text
        val tokenEnd = if (text.startsWith("/")) {
            text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        } else {
            0
        }
        val newText = commandText + text.substring(tokenEnd)
        return TextFieldValue(text = newText, selection = TextRange(commandText.length))
    }
}
