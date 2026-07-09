package com.pocketshell.app.projects

internal data class FolderListProfileLists(
    val claudeProfiles: List<ClaudeProfile>,
    val codexProfiles: List<CodexProfile>,
)

/**
 * Issue #718: project host-discovered [RemoteProfile] rows onto the new-session
 * picker's per-engine profile lists.
 */
internal fun List<RemoteProfile>.toFolderListProfileLists(): FolderListProfileLists =
    FolderListProfileLists(
        claudeProfiles = filter { it.engine == RemoteProfile.ENGINE_CLAUDE }
            .map { ClaudeProfile(name = it.name, default = it.default) },
        codexProfiles = filter { it.engine == RemoteProfile.ENGINE_CODEX }
            .map { CodexProfile(name = it.name, default = it.default) },
    )
