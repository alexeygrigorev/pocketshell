package com.pocketshell.uikit.model

/**
 * Visual classifier for the leading badge of a `SessionRow` — issue #171.
 *
 * Mirrors the agent vocabulary from `core-agents` (`AgentKind`) without
 * pulling that module into `ui-kit` (the design system stays
 * dependency-free above pure Compose + theme). The mapping
 * `AgentKind -> SessionAgentKind` lives at the app layer.
 *
 * The kind drives the leading badge's tint per the design-system tokens
 * locked in the issue-171 spike:
 *
 * - [Claude] -> `Accent` (cyan)
 * - [Codex] / [OpenCode] -> `Purple`
 * - [Shell] -> `TextSecondary` (neutral plain-tmux pane)
 * - [Probing] -> `Amber` (detection in flight)
 * - [Exited] -> `TextMuted` (an agent was seen recently but has exited)
 *
 * Pass `null` for the neutral cyan `AccentSoft` badge used by the
 * dashboard (#202), where the badge is a plain visual anchor and
 * deliberately does not encode agent kind. The folder-list surface
 * (#171) passes an explicit kind to tint the badge instead.
 */
enum class SessionAgentKind {
    Claude,
    Codex,
    OpenCode,
    Shell,
    Probing,
    Exited,
}
