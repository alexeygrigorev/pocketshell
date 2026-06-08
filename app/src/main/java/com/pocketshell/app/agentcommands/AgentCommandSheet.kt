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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Modal bottom sheet listing the agent slash-commands available for [agent]
 * (issue #436, Slice A — the spine). Visually clones [the snippet picker
 * sheet][com.pocketshell.app.snippets.SnippetPickerSheet] (search field +
 * dense rows) but is backed by its OWN [AgentCommandCatalog], NOT the
 * per-host snippets data model — snippets are user CRUD with the wrong
 * lifecycle for an app-shipped per-agent catalog (see the issue #436 spike).
 *
 * Caller wiring:
 *  - [onCommandSend]: invoked when the user taps a plain command row or sends
 *    an expanded parameterized row. The caller routes [AgentCommand.command]
 *    through
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
    // Issue #453/#543: send a Claude session-control action (interrupt /
    // end-input) into the focused agent pane as control bytes. Optional so
    // previews / tests that only exercise slash commands keep compiling.
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
            onCommandSend = { command, argumentText ->
                onCommandSend(command.copy(command = command.dispatchText(argumentText)))
                onDismiss()
            },
            onClose = onDismiss,
            // The current double Ctrl-C / Ctrl-D semantics are Claude-specific;
            // do not show them in Codex/OpenCode command sheets.
            onControlSend = if (agent == AgentKind.ClaudeCode && onControlSend != null) {
                { action ->
                    onControlSend(action)
                    onDismiss()
                }
            } else null,
        )
    }
}

/**
 * Issue #453/#543: a Claude session-control action surfaced at the bottom of
 * the agent command palette. The caller maps each to the corresponding control
 * bytes (`Ctrl-C` ×2 / `Ctrl-D` ×2) sent into the focused pane.
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
    onCommandSend: (AgentCommand, argumentText: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onControlSend: ((SessionControlAction) -> Unit)? = null,
) {
    var expandedCommand by remember(commands) { mutableStateOf<String?>(null) }
    var argumentText by remember(expandedCommand) { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Mirror the snippet picker's inset handling (#253) so the bottom
            // command rows are never drawn under the system nav bar or IME.
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg)
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
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = PocketShellShapes.small,
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = PocketShellShapes.small,
                )
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.md),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AGENT_COMMAND_SEARCH_TAG),
                textStyle = PocketShellType.bodyDense.copy(color = PocketShellColors.Text),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search commands...",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.bodyDense,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(modifier = Modifier.height(PocketShellSpacing.md))

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
                    style = PocketShellType.bodyDense,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                items(commands, key = { it.command }) { command ->
                    val expanded = expandedCommand == command.command
                    AgentCommandRow(
                        command = command,
                        expanded = expanded,
                        argumentText = if (expanded) argumentText else "",
                        onArgumentChange = { argumentText = it },
                        onRowClick = {
                            if (command.argument == null) {
                                onCommandSend(command, "")
                            } else {
                                expandedCommand = command.command
                            }
                        },
                        onSend = {
                            if (command.canDispatch(argumentText)) {
                                onCommandSend(command, argumentText)
                            }
                        },
                        onCancel = {
                            expandedCommand = null
                            argumentText = ""
                        },
                    )
                }
            }
        }

        if (onControlSend != null && query.isEmpty()) {
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
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
    }
}

@Composable
private fun AgentCommandRow(
    command: AgentCommand,
    expanded: Boolean,
    argumentText: String,
    onArgumentChange: (String) -> Unit,
    onRowClick: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            ),
    ) {
        ListRow(
            title = command.label,
            subtitle = command.description,
            modifier = Modifier.testTag(agentCommandRowTag(command.command)),
            onClick = onRowClick,
            trailing = {
                Badge(label = command.command, role = BadgeRole.Agent, mono = false)
                AgentCommandSendChip(
                    command = command,
                    label = if (command.argument == null) "Send" else "Fill",
                    enabled = true,
                    onClick = if (command.argument == null) onSend else onRowClick,
                    tag = if (command.argument == null) {
                        agentCommandSendChipTag(command.command)
                    } else {
                        agentCommandArgumentOpenChipTag(command.command)
                    },
                )
            },
        )

        if (expanded && command.argument != null) {
            Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
            AgentCommandArgumentEditor(
                command = command,
                value = argumentText,
                onValueChange = onArgumentChange,
                onSend = onSend,
                onCancel = onCancel,
            )
        }
    }
}

/**
 * Compact trailing chip. Commands always submit with Enter, so there is no
 * "Send vs Send + ↵" split.
 */
@Composable
private fun AgentCommandSendChip(
    command: AgentCommand,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = agentCommandSendChipTag(command.command),
) {
    Box(
        modifier = modifier
            .background(
                if (enabled) PocketShellColors.Accent else PocketShellColors.Surface,
                PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = if (enabled) PocketShellColors.Accent else PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            )
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            )
            .testTag(tag)
            .semantics { contentDescription = "Send ${command.command} to agent" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AgentCommandArgumentEditor(
    command: AgentCommand,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val enabled = command.canDispatch(value)
    Column {
        Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PocketShellColors.Surface, PocketShellShapes.small)
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.Border,
                        shape = PocketShellShapes.small,
                    )
                    .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellDensity.chipPadH),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(agentCommandArgumentFieldTag(command.command)),
                textStyle = PocketShellType.bodyDense.copy(color = PocketShellColors.Text),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                singleLine = true,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = command.argument?.placeholder.orEmpty(),
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.bodyDense,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(
                        horizontal = PocketShellDensity.chipPadH,
                        vertical = PocketShellDensity.chipPadV,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Cancel",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            AgentCommandSendChip(
                command = command,
                label = if (enabled) "Send" else "Required",
                enabled = enabled,
                onClick = onSend,
            )
        }
    }
}

/**
 * Test tag for a command row's `Send` chip. Keyed on the raw command text
 * (which is unique within an agent's catalog), with the leading `/` dropped
 * so the tag is a plain identifier.
 */
internal fun agentCommandSendChipTag(command: String): String =
    "agent-command-send-${command.removePrefix("/")}"

internal fun agentCommandRowTag(command: String): String =
    "agent-command-row-${command.removePrefix("/")}"

internal fun agentCommandArgumentFieldTag(command: String): String =
    "agent-command-argument-${command.removePrefix("/")}"

internal fun agentCommandArgumentOpenChipTag(command: String): String =
    "agent-command-argument-open-${command.removePrefix("/")}"

/**
 * Issue #453/#543: a session-control chip (interrupt / end-input) shown at the
 * bottom of the palette. A bordered "danger-soft" chip so it reads as a system
 * action distinct from the accent-filled slash-command Send chips.
 */
@Composable
private fun SessionControlChip(
    action: SessionControlAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            ),
    ) {
        ListRow(
            title = action.label,
            subtitle = action.hint,
            modifier = Modifier
                .testTag(sessionControlChipTag(action))
                .semantics { contentDescription = "${action.label}: ${action.hint}" },
            onClick = onClick,
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
                onCommandSend = { _, _ -> },
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
                onCommandSend = { _, _ -> },
                onClose = {},
            )
        }
    }
}
