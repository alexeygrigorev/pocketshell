package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind

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
