package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.isLiveAgent
import com.pocketshell.uikit.model.resolveSessionAgentState

internal fun TreeRemoteSource.TreeNode.toHydratedNode(): HostTreeModel.HydratedNode =
    HostTreeModel.HydratedNode(
        sessionName = session,
        order = order,
        folderPath = folderPath,
        collapsed = collapsed,
        foreignGuess = foreignKind.toSessionAgentKindOrNull(),
    )

internal fun HostTreeModel.HydratedNode.toTreeNode(): TreeRemoteSource.TreeNode =
    TreeRemoteSource.TreeNode(
        session = sessionName,
        order = order,
        folderPath = folderPath,
        collapsed = collapsed,
        foreignKind = foreignGuess?.toRegistryKindString(),
    )

private fun String?.toSessionAgentKindOrNull(): SessionAgentKind? = when (this) {
    "claude" -> SessionAgentKind.Claude
    "codex" -> SessionAgentKind.Codex
    "opencode" -> SessionAgentKind.OpenCode
    else -> null
}

private fun SessionAgentKind.toRegistryKindString(): String? = when (this) {
    SessionAgentKind.Claude -> "claude"
    SessionAgentKind.Codex -> "codex"
    SessionAgentKind.OpenCode -> "opencode"
    else -> null
}

internal fun FolderSessionRow.toSessionEntry(): FolderSessionEntry =
    FolderSessionEntry(
        sessionName = sessionName,
        lastActivity = lastActivity,
        attached = attached,
        agentKind = agentKind,
        // Issue #1237/#1570: resolve the raw @ps_agent_state option to a chip
        // state. The hook fires only on stop/waiting, so fresh session activity
        // after a recorded idle/waiting means the agent resumed — for a live
        // agent session that resolves to Working (the "working Codex shows Idle"
        // report), for a non-agent session to Unknown (no chip).
        agentState = resolveSessionAgentState(
            rawState = agentStateRaw,
            stateUpdatedAtEpochSec = agentStateUpdatedAt,
            sessionActivityEpochSec = lastActivity,
            isAgentSession = agentKind.isLiveAgent(),
        ),
        recordedProfile = recordedProfile,
        tmuxSessionId = tmuxSessionId,
        sessionCreated = sessionCreated,
        windows = windows.map { window ->
            FolderSessionWindowEntry(
                index = window.index,
                name = window.name,
                active = window.active,
                command = window.command,
                agentKind = window.agentKind,
                windowId = window.windowId,
            )
        },
    )
