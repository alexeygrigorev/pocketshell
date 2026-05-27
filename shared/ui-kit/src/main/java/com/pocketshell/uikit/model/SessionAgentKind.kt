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
 * Pass `null` to fall back to the legacy cyan `AccentSoft` badge — that
 * preserves the existing dashboard rendering and keeps the
 * `SessionRow` API backwards-compatible at the source level.
 */
enum class SessionAgentKind {
    Claude,
    Codex,
    OpenCode,
    Shell,
    Probing,
    Exited,
}
