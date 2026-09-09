package com.pocketshell.next.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

const val COMPOSER_SLASH_SEARCH_TAG: String = "composer-slash-search"
const val COMPOSER_SLASH_NOTE_TAG: String = "composer-slash-note"

/**
 * One row of the `/`-triggered command dropdown.
 *
 * [argument] is the placeholder shown after the command when it takes one; a
 * pick with an argument leaves the caret past a trailing space so the user can
 * type straight into it.
 */
data class SlashCommand(
    val command: String,
    val description: String,
    val argument: String? = null,
)

/**
 * The `/`-triggered inline command autocomplete (rewrite task P-1, ported from
 * the old client's `SlashCommandAutocomplete`).
 *
 * The selected session supplies the command catalog. Unknown and shell-only
 * sessions intentionally expose no invented commands.
 *
 * The selected session supplies the command catalog. A command is only offered
 * when aplexer reported a known agent for that session; unknown and shell-only
 * sessions intentionally expose no invented commands. The composer inserts
 * text only — it never executes a command — so capability scoping is the
 * safety boundary for this picker.
 *
 * The three pure decisions the dropdown is made of live here so the composable
 * stays a renderer and the behaviour is unit-tested without an emulator:
 *
 *  - [queryFor] — is the dropdown open, and what is the filter?
 *  - [filter] — the rows matching that query.
 *  - [insert] — the field value after a row is chosen.
 */
object SlashCommandAutocomplete {

    /**
     * Example data retained for isolated previews and unit tests. Production
     * passes the selected session's catalog explicitly through [commandsFor].
     */

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
     * Commands known for the agent aplexer actually detected in the selected
     * session. Unknown, absent, and shell-only agents intentionally return an
     * empty list: a plausible command is not evidence that the program accepts
     * it.
     */
    fun commandsFor(agent: String?): List<SlashCommand> = when (agent?.trim()?.lowercase()) {
        "claude" -> listOf(
            SlashCommand("/clear", "Start a fresh conversation"),
            SlashCommand("/compact", "Summarise the conversation", argument = "instructions"),
            SlashCommand("/goal", "Set a session goal", argument = "goal"),
            SlashCommand("/rewind", "Roll back to an earlier point"),
            SlashCommand("/resume", "Resume a previous conversation"),
            SlashCommand("/context", "Show context usage"),
            SlashCommand("/model", "Switch the active model"),
            SlashCommand("/cost", "Show token cost"),
            SlashCommand("/review", "Request a code review"),
            SlashCommand("/init", "Initialise project memory"),
        )
        "codex", "zcodex" -> listOf(
            SlashCommand("/new", "Start a fresh conversation"),
            SlashCommand("/compact", "Summarise the conversation", argument = "instructions"),
            SlashCommand("/goal", "Set a session goal", argument = "goal"),
            SlashCommand("/diff", "Show the working-tree diff"),
            SlashCommand("/clear", "Clear the terminal and start a fresh chat"),
            SlashCommand("/resume", "Resume a previous conversation"),
            SlashCommand("/review", "Request a code review"),
            SlashCommand("/status", "Show session status"),
            SlashCommand("/model", "Switch the active model"),
            SlashCommand("/init", "Initialise project memory"),
        )
        "opencode" -> listOf(
            SlashCommand("/new", "Start a fresh conversation"),
            SlashCommand("/compact", "Summarise the conversation", argument = "instructions"),
            SlashCommand("/sessions", "Browse and resume previous sessions"),
            SlashCommand("/undo", "Undo the last change"),
            SlashCommand("/redo", "Redo the last undone change"),
            SlashCommand("/share", "Create a shareable session link"),
            SlashCommand("/export", "Export the conversation"),
            SlashCommand("/models", "Switch the active model"),
            SlashCommand("/init", "Initialise project memory"),
        )
        "grok" -> listOf(
            SlashCommand("/new", "Start a fresh conversation"),
            SlashCommand("/compact", "Summarise the conversation", argument = "instructions"),
            SlashCommand("/export", "Export the current session"),
            SlashCommand("/context", "Show context usage"),
        )
        else -> emptyList()
    }

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
    fun filter(query: String, commands: List<SlashCommand> = CATALOG): List<SlashCommand> {
        if (query.isBlank()) return commands
        val needle = query.lowercase()
        return commands.filter {
            it.command.removePrefix("/").lowercase().startsWith(needle) ||
                it.description.lowercase().contains(needle)
        }
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

/**
 * Native command picker for the production composer.
 *
 * The draft editor owns the selected command and this sheet only searches the
 * capability list supplied by the selected session. Keeping the body separate
 * makes the command surface testable without asking Robolectric to settle a
 * modal sheet animation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlashCommandSheet(
    commands: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
        modifier = modifier,
    ) {
        SlashCommandSheetContent(
            commands = commands,
            onPick = onPick,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun SlashCommandSheetContent(
    commands: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val rows = SlashCommandAutocomplete.filter(query, commands)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg)
            .testTag(COMPOSER_SLASH_TAG),
    ) {
        SheetHeader(
            title = "Slash commands",
            onClose = onDismiss,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_SLASH_SEARCH_TAG),
            singleLine = true,
            placeholder = { Text("Find a command") },
            leadingIcon = {
                Icon(
                    imageVector = PocketShellIcons.Search,
                    contentDescription = null,
                )
            },
        )
        if (rows.isEmpty()) {
            Text(
                text = "No matching commands.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
                modifier = Modifier.padding(vertical = PocketShellSpacing.lg),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(rows, key = { it.command }) { command ->
                    ListRow(
                        title = command.command,
                        subtitle = command.description,
                        onClick = { onPick(command) },
                        modifier = Modifier.testTag(composerSlashRowTag(command.command)),
                    )
                }
            }
        }
        Text(
            text = "Inserted into your draft. Nothing runs until you send.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            modifier = Modifier
                .padding(top = PocketShellSpacing.md)
                .testTag(COMPOSER_SLASH_NOTE_TAG),
        )
    }
}
