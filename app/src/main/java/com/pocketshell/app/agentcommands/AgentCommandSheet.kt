package com.pocketshell.app.agentcommands

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme

/**
 * Modal bottom sheet listing the agent slash-commands available for [agent]
 * (issue #436, Slice A — the spine). Visually clones [the snippet picker
 * sheet][com.pocketshell.app.snippets.SnippetPickerSheet] (search field +
 * row + send chip) but is backed by its OWN [AgentCommandCatalog], NOT the
 * per-host snippets data model — snippets are user CRUD with the wrong
 * lifecycle for an app-shipped per-agent catalog (see the issue #436 spike).
 *
 * Caller wiring:
 *  - [onCommandSend]: invoked when the user taps a row's `Send` chip. The
 *    caller routes the raw [AgentCommand.command] through
 *    `TmuxSessionViewModel.sendToAgentPane(paneId, command)`, which types the
 *    literal text via `send-keys -l` then submits with Enter (the Codex
 *    submit delay is already handled inside that path).
 *  - [onDismiss]: invoked when the grabber drag or scrim tap closes the sheet.
 *
 * Slice B (usage ranking + persistence) and Slice C (destructive-confirm
 * dialog) are explicit follow-ups; this sheet always sends on tap and renders
 * the catalog in its static curated-first order. The [AgentCommand.destructive]
 * flag is carried through but not yet gated on a confirm step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AgentCommandSheet(
    agent: AgentKind,
    onDismiss: () -> Unit,
    onCommandSend: (AgentCommand) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    // Issue #453: send a session-control action (interrupt / end-input) into
    // the focused agent pane as control bytes. These were the `Ctrl-C x2` /
    // `Ctrl-D x2` band chips; the band is decluttered to "/ commands + mic",
    // so the two controls live at the top of this palette instead. Optional
    // so previews / tests that only exercise slash commands keep compiling.
    onControlSend: ((SessionControlAction) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, agent) {
        AgentCommandCatalog.filter(agent, query)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        AgentCommandSheetContent(
            agent = agent,
            commands = filtered,
            query = query,
            onQueryChange = { query = it },
            onCommandSend = { command ->
                onCommandSend(command)
                onDismiss()
            },
            onClose = onDismiss,
            // Only show the controls when query is empty so a command search
            // doesn't keep them pinned; firing one dismisses the sheet.
            onControlSend = if (onControlSend != null) {
                { action ->
                    onControlSend(action)
                    onDismiss()
                }
            } else null,
        )
    }
}

/**
 * Issue #453: a session-control action surfaced at the top of the agent
 * command palette. These replace the `Ctrl-C x2` / `Ctrl-D x2` band chips —
 * the band is decluttered to "/ commands + mic", so interrupt / end-input
 * move here. The caller maps each to the corresponding control bytes
 * (`Ctrl-C` ×2 / `Ctrl-D` ×2) sent into the focused pane.
 */
public enum class SessionControlAction(
    public val label: String,
    public val hint: String,
) {
    Interrupt(label = "Interrupt", hint = "Ctrl-C ×2 — stop the running agent"),
    EndInput(label = "End input", hint = "Ctrl-D ×2 — send EOF / exit the REPL"),
}

/**
 * Pure-renderer content for the sheet body. Pulled out so `@Preview`s can
 * render without a `ModalBottomSheet` window decor (mirrors the snippet
 * picker's split).
 */
@Composable
internal fun AgentCommandSheetContent(
    agent: AgentKind,
    commands: List<AgentCommand>,
    query: String,
    onQueryChange: (String) -> Unit,
    onCommandSend: (AgentCommand) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onControlSend: ((SessionControlAction) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Mirror the snippet picker's inset handling (#253) so the bottom
            // command rows are never drawn under the system nav bar or IME.
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
            .testTag(AGENT_COMMAND_SHEET_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${agent.displayName} commands",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 20.sp,
                )
            }
        }

        // Issue #453: session controls (interrupt / end-input) live at the
        // top of the palette — these are the former `Ctrl-C x2` / `Ctrl-D x2`
        // band chips, moved here so the session band stays "/ commands + mic".
        // Hidden while the user is searching commands so they don't pin.
        if (onControlSend != null && query.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionControlAction.values().forEach { action ->
                    SessionControlChip(
                        action = action,
                        onClick = { onControlSend(action) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AGENT_COMMAND_SEARCH_TAG),
                textStyle = TextStyle(
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search commands...",
                            color = PocketShellColors.TextMuted,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (commands.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matches for \"$query\"",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(commands, key = { it.command }) { command ->
                    AgentCommandRow(
                        command = command,
                        onSend = { onCommandSend(command) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentCommandRow(
    command: AgentCommand,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = command.label,
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = command.command,
                        color = PocketShellColors.Accent,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = command.description,
                    color = PocketShellColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        AgentCommandSendChip(
            command = command,
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Single `Send` chip — commands always submit with Enter, so there is no
 * "Send vs Send + ↵" split (simpler than the snippet row). Reuses the
 * snippet picker's accent-fill primary-chip look.
 */
@Composable
private fun AgentCommandSendChip(
    command: AgentCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(PocketShellColors.Accent, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.Accent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(agentCommandSendChipTag(command.command))
            .semantics { contentDescription = "Send ${command.command} to agent" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Send",
            color = PocketShellColors.OnAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Test tag for a command row's `Send` chip. Keyed on the raw command text
 * (which is unique within an agent's catalog), with the leading `/` dropped
 * so the tag is a plain identifier.
 */
internal fun agentCommandSendChipTag(command: String): String =
    "agent-command-send-${command.removePrefix("/")}"

/**
 * Issue #453: a session-control chip (interrupt / end-input) shown at the
 * top of the palette. A bordered "danger-soft" chip so it reads as a system
 * action distinct from the accent-filled slash-command Send chips.
 */
@Composable
private fun SessionControlChip(
    action: SessionControlAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(sessionControlChipTag(action))
            .semantics { contentDescription = "${action.label}: ${action.hint}" },
    ) {
        Text(
            text = action.label,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = action.hint,
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun sessionControlChipTag(action: SessionControlAction): String =
    "agent-command-control-${action.name}"

internal const val AGENT_COMMAND_SHEET_TAG: String = "agent-command-sheet"
internal const val AGENT_COMMAND_SEARCH_TAG: String = "agent-command-search"

// -- Previews -----------------------------------------------------------------

@Preview(name = "Agent commands - Claude", widthDp = 412, heightDp = 600)
@Composable
private fun AgentCommandSheetClaudePreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            AgentCommandSheetContent(
                agent = AgentKind.ClaudeCode,
                commands = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode),
                query = "",
                onQueryChange = {},
                onCommandSend = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Agent commands - OpenCode", widthDp = 412, heightDp = 600)
@Composable
private fun AgentCommandSheetOpenCodePreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            AgentCommandSheetContent(
                agent = AgentKind.OpenCode,
                commands = AgentCommandCatalog.commandsFor(AgentKind.OpenCode),
                query = "",
                onQueryChange = {},
                onCommandSend = {},
                onClose = {},
            )
        }
    }
}
