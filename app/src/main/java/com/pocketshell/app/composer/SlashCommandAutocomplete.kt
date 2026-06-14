package com.pocketshell.app.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.core.agents.AgentKind

/**
 * Issue #767: pure, JVM-testable logic for the `/`-triggered inline command
 * autocomplete dropdown in the prompt composer.
 *
 * The composer opens a floating list of [AgentCommand]s (from
 * [AgentCommandCatalog]) the moment the draft's LEADING token starts with `/`
 * and the caret sits within that token — exactly the Slack / Discord / ChatGPT
 * slash-command pattern. This object owns the three pure decisions that drive
 * that surface so the composable stays a thin renderer and the behaviour is
 * unit-tested without an emulator:
 *
 *  - [slashQueryFor]   — is the dropdown open, and what is the filter query?
 *  - [filteredCommands] — the catalog rows matching the current query.
 *  - [insertCommand]    — the new field value after a row is chosen.
 *
 * This is ALSO the reusable insert path #770 ("tap a rendered engine command →
 * composer pre-filled") calls: [insertCommandText] inserts an arbitrary command
 * string into the field exactly like an autocomplete pick, so the tap handler
 * and the dropdown share one entry point.
 */
internal object SlashCommandAutocomplete {

    /**
     * The query string for the autocomplete dropdown, or null when the dropdown
     * should be CLOSED.
     *
     * The dropdown opens only when:
     *  - the draft's leading token starts with `/` (the field begins with `/`),
     *    and
     *  - the caret is positioned WITHIN that leading token (so once the user has
     *    typed a space and moved on to the argument / a second word, the list
     *    closes — a slash-command's argument is free text, not a filter).
     *
     * The returned query is the substring AFTER the leading `/` up to the first
     * whitespace (e.g. `/comp` -> `comp`, `/` -> ``). A blank query keeps the
     * dropdown open showing the full catalog, which is what makes a bare `/`
     * low-friction: type `/` and the whole list appears.
     */
    fun slashQueryFor(value: TextFieldValue): String? {
        val text = value.text
        if (!text.startsWith("/")) return null
        // The leading token ends at the first whitespace (space, tab, newline).
        val tokenEnd = text.indexOfFirst { it.isWhitespace() }.let {
            if (it < 0) text.length else it
        }
        // The caret must be inside the leading slash token. Use the selection
        // start so a range selection that begins outside the token also closes
        // the dropdown.
        val caret = value.selection.start
        if (caret < 0 || caret > tokenEnd) return null
        // Substring after the leading `/`, bounded by the token end.
        return text.substring(1, tokenEnd)
    }

    /**
     * The catalog rows to show for [agent] given the current [query]. Returns an
     * empty list when there is no agent (a plain shell pane) — the dropdown is an
     * agent affordance and never offers commands when no engine is detected.
     */
    fun filteredCommands(agent: AgentKind?, query: String): List<AgentCommand> {
        if (agent == null) return emptyList()
        return AgentCommandCatalog.filter(agent, query)
    }

    /**
     * The new field value after the user picks [command] from the dropdown. The
     * chosen command text replaces ONLY the leading slash token (preserving any
     * trailing text the user already typed after a space), a single trailing
     * space is added when the command takes an argument (so the caret lands ready
     * to type the argument), and the caret is placed at the end of the inserted
     * command.
     */
    fun insertCommand(value: TextFieldValue, command: AgentCommand): TextFieldValue {
        val insertion = if (command.argument != null) command.command + " " else command.command
        return insertCommandText(value, insertion)
    }

    /**
     * The new field value after [commandText] is inserted as the leading slash
     * token. Shared with #770: a tap on an engine command the terminal already
     * rendered routes through here so the composer is pre-filled identically to
     * an autocomplete pick. When the field already holds a leading slash token it
     * is REPLACED; otherwise [commandText] is prepended (so #770's "open the
     * composer with /clear" lands the command at the start, caret after it).
     */
    fun insertCommandText(value: TextFieldValue, commandText: String): TextFieldValue {
        val text = value.text
        val tokenEnd = if (text.startsWith("/")) {
            text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        } else {
            0
        }
        val trailing = text.substring(tokenEnd)
        val newText = commandText + trailing
        val caret = commandText.length
        return TextFieldValue(text = newText, selection = TextRange(caret))
    }
}
