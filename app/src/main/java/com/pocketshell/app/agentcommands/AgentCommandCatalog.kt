package com.pocketshell.app.agentcommands

import com.pocketshell.core.agents.AgentKind

/**
 * A single agent slash-command that the "/ commands" quick-send palette can
 * fire into a detected agent pane (issue #436, Slice A).
 *
 * Slash commands are an app-shipped, per-[AgentKind] curated catalog — not
 * user-authored per-host CRUD rows. That is the explicit reason this is a
 * separate data model from snippets (`SnippetEntity` is per-host, has no
 * agent dimension, and sorts alphabetically by design). See the issue #436
 * spike, section 4, for the full rationale.
 *
 * @property command the literal text typed into the agent REPL, including the
 *   leading `/` (e.g. `/compact`). This is routed verbatim through
 *   `TmuxSessionViewModel.sendToAgentPane(paneId, command)` which appends the
 *   submit Enter (with the Codex-specific submit delay already baked in).
 * @property label the human-facing row title (e.g. "Compact context").
 * @property description a one-line explanation shown under the label.
 * @property destructive whether the command clears or rolls back conversation
 *   context (e.g. `/clear`, `/new`, `/rewind`). Slice A only carries the flag
 *   so the catalog is complete; the confirm-before-send dialog is Slice C.
 * @property argument optional inline argument metadata. Commands with an
 *   argument do not fire bare from the sheet; the row expands first and the
 *   sheet composes `/<command> <argument>` when sent.
 */
public data class AgentCommand(
    val command: String,
    val label: String,
    val description: String,
    val destructive: Boolean = false,
    val argument: AgentCommandArgument? = null,
)

public data class AgentCommandArgument(
    val placeholder: String,
    val required: Boolean,
)

public fun AgentCommand.dispatchText(argumentText: String): String {
    if (argument == null) return command
    val trimmed = argumentText.trim()
    if (trimmed.isEmpty()) return command
    return "$command $trimmed"
}

public fun AgentCommand.canDispatch(argumentText: String): Boolean {
    val required = argument?.required ?: return true
    return !required || argumentText.trim().isNotEmpty()
}

private val goalArgument = AgentCommandArgument(
    placeholder = "Goal for this session",
    required = true,
)

private val compactArgument = AgentCommandArgument(
    placeholder = "Optional compaction instructions",
    required = false,
)

/**
 * Per-agent slash-command catalog for the quick-send palette (issue #436,
 * Slice A — the spine). Commands absent for an agent are simply not present
 * in its list, so an unavailable command is never offered (the spike's
 * "unavailable commands hidden per agent" requirement).
 *
 * Notable per-agent nuances encoded here (from the spike, section 1):
 *  - Claude Code: `/clear` is the single underlying command for both "new"
 *    and "reset" (aliases `/new`, `/reset`), so it renders as ONE row labelled
 *    "New / clear conversation". Claude has BOTH `/goal` and the new-clear row.
 *  - OpenCode: `/new` and `/clear` are aliases of each other, so they collapse
 *    to one row. OpenCode is the one agent WITHOUT `/goal` — it is omitted.
 *  - Codex: `/new` (fresh conversation) is the curated row; `/clear` (which
 *    also wipes terminal scrollback) lives in the long tail.
 *
 * Each agent's list is ordered curated-first (the handful the maintainer
 * named: new/clear, compact, goal where present, plus one agent-appropriate
 * extra) followed by the searchable long tail. Usage-driven re-ranking and
 * persistence is Slice B; this Slice A ships the static curated-first order.
 */
public object AgentCommandCatalog {

    private val claudeCode: List<AgentCommand> = listOf(
        AgentCommand(
            command = "/clear",
            label = "New / clear conversation",
            description = "Start a fresh conversation (clears current context).",
            destructive = true,
        ),
        AgentCommand(
            command = "/compact",
            label = "Compact context",
            description = "Summarise the conversation to free up context.",
            argument = compactArgument,
        ),
        AgentCommand(
            command = "/goal",
            label = "Goal",
            description = "Set a persistent objective for the session.",
            argument = goalArgument,
        ),
        AgentCommand(
            command = "/rewind",
            label = "Rewind",
            description = "Roll back to an earlier point in the conversation.",
            destructive = true,
        ),
        // Long tail (searchable).
        AgentCommand(
            command = "/resume",
            label = "Resume",
            description = "Resume a previous conversation.",
        ),
        AgentCommand(
            command = "/context",
            label = "Context",
            description = "Show the current context usage.",
        ),
        AgentCommand(
            command = "/model",
            label = "Model",
            description = "Switch the active model.",
        ),
        AgentCommand(
            command = "/cost",
            label = "Cost",
            description = "Show token cost for the session.",
        ),
        AgentCommand(
            command = "/review",
            label = "Review",
            description = "Request a code review.",
        ),
        AgentCommand(
            command = "/init",
            label = "Init",
            description = "Initialise project memory (CLAUDE.md).",
        ),
    )

    private val codex: List<AgentCommand> = listOf(
        AgentCommand(
            command = "/new",
            label = "New conversation",
            description = "Start a fresh conversation in this CLI session.",
            destructive = true,
        ),
        AgentCommand(
            command = "/compact",
            label = "Compact context",
            description = "Summarise the conversation to free up context.",
            argument = compactArgument,
        ),
        AgentCommand(
            command = "/goal",
            label = "Goal",
            description = "Set a persistent objective for the session.",
            argument = goalArgument,
        ),
        AgentCommand(
            command = "/diff",
            label = "Diff",
            description = "Show the working-tree diff.",
        ),
        // Long tail (searchable).
        AgentCommand(
            command = "/clear",
            label = "Clear",
            description = "Clear the terminal and start a fresh chat.",
            destructive = true,
        ),
        AgentCommand(
            command = "/resume",
            label = "Resume",
            description = "Resume a previous conversation.",
        ),
        AgentCommand(
            command = "/review",
            label = "Review",
            description = "Request a code review.",
        ),
        AgentCommand(
            command = "/status",
            label = "Status",
            description = "Show the current session status.",
        ),
        AgentCommand(
            command = "/model",
            label = "Model",
            description = "Switch the active model.",
        ),
        AgentCommand(
            command = "/init",
            label = "Init",
            description = "Initialise project memory.",
        ),
    )

    private val openCode: List<AgentCommand> = listOf(
        AgentCommand(
            command = "/new",
            label = "New / clear conversation",
            description = "Start a fresh conversation (clears current context).",
            destructive = true,
        ),
        AgentCommand(
            command = "/compact",
            label = "Compact context",
            description = "Summarise the conversation to free up context.",
            argument = compactArgument,
        ),
        AgentCommand(
            command = "/sessions",
            label = "Sessions",
            description = "Browse and resume previous sessions.",
        ),
        AgentCommand(
            command = "/undo",
            label = "Undo",
            description = "Undo the last change.",
            destructive = true,
        ),
        // Long tail (searchable). OpenCode has NO /goal — it is omitted.
        AgentCommand(
            command = "/redo",
            label = "Redo",
            description = "Redo the last undone change.",
        ),
        AgentCommand(
            command = "/share",
            label = "Share",
            description = "Create a shareable link for the session.",
        ),
        AgentCommand(
            command = "/export",
            label = "Export",
            description = "Export the conversation.",
        ),
        AgentCommand(
            command = "/models",
            label = "Models",
            description = "Switch the active model.",
        ),
        AgentCommand(
            command = "/init",
            label = "Init",
            description = "Initialise project memory.",
        ),
    )

    /**
     * The full ordered command list for [agent], curated-first then long
     * tail. Never null — every supported [AgentKind] has a catalog.
     */
    public fun commandsFor(agent: AgentKind): List<AgentCommand> = when (agent) {
        AgentKind.ClaudeCode -> claudeCode
        AgentKind.Codex -> codex
        AgentKind.OpenCode -> openCode
    }

    /**
     * Issue #1584: the per-agent set of TUI-only slash-command roots — the
     * genuine alt-screen picker / interactive commands (`/model`, `/config`,
     * permission pickers …) that the agent handles ENTIRELY in its terminal UI
     * and that write NOTHING to the JSONL transcript. On the Conversation tab
     * these get no optimistic echo and raise the "Open in Terminal" notice,
     * because the picker they open shows only on the covered Terminal pane.
     *
     * This allowlist REPLACES the old grammar-only "every `/word` is TUI-only"
     * heuristic (hard-cut, D22). A command NOT in this set is a TEXT command
     * (e.g. `/goal`, `/goal resume`, `/compact`, `/review`, `/diff`) that reaches
     * the agent and produces transcript output, so it routes as normal agent
     * payload (echo + transcript) and NEVER raises the notice.
     *
     * Roots are stored lowercased and WITHOUT arguments — matched against the
     * leading `/word` token only, so `/goal resume` (root `/goal`) is text while
     * `/model sonnet` (root `/model`) is a picker.
     */
    private val claudeTuiOnly: Set<String> = setOf(
        "/model", "/config", "/login", "/logout",
        "/permissions", "/agents", "/mcp", "/resume", "/rewind",
    )

    private val codexTuiOnly: Set<String> = setOf(
        "/model", "/approvals", "/mcp", "/resume",
    )

    private val openCodeTuiOnly: Set<String> = setOf(
        "/models", "/sessions", "/themes", "/editor",
    )

    private fun tuiOnlyRoots(agent: AgentKind): Set<String> = when (agent) {
        AgentKind.ClaudeCode -> claudeTuiOnly
        AgentKind.Codex -> codexTuiOnly
        AgentKind.OpenCode -> openCodeTuiOnly
    }

    /**
     * True when [commandRoot] (a leading `/word` token, case-insensitive) is a
     * genuine TUI-only alt-screen picker for [agent]. See [tuiOnlyRoots].
     */
    public fun isTuiOnlyCommand(agent: AgentKind, commandRoot: String): Boolean =
        tuiOnlyRoots(agent).contains(commandRoot.lowercase())

    /**
     * Client-side substring filter over [commandsFor], matching against the
     * command text, label, and description (case-insensitive). A blank query
     * returns the full ordered list unchanged.
     */
    public fun filter(agent: AgentKind, query: String): List<AgentCommand> {
        val all = commandsFor(agent)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return all
        return all.filter { command ->
            command.command.lowercase().contains(needle) ||
                command.label.lowercase().contains(needle) ||
                command.description.lowercase().contains(needle)
        }
    }
}
