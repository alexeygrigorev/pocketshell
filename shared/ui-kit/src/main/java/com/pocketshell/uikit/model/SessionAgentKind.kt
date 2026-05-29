package com.pocketshell.uikit.model

/**
 * Session classifier mirrored from `core-agents` (`AgentKind`) without
 * pulling that module into `ui-kit`. The app layer maps
 * `AgentKind -> SessionAgentKind`, and the folder list converts the
 * value into readable [Tag] chips.
 */
enum class SessionAgentKind {
    Claude,
    Codex,
    OpenCode,
    Shell,
    Probing,
    Exited,
}
