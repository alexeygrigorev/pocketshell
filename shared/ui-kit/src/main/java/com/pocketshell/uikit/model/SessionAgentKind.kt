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
    Grok,
    Shell,
    Probing,
    Exited,

    /**
     * Epic #821 Slice 1 (foreign-session classification): a session we did
     * NOT launch and that carries no recorded host-side `@ps_agent_kind`
     * agent metadata. The maintainer's Option B decision is that we do NOT guess
     * what such a session is — instead the UI surfaces it as [Unknown] and
     * offers a picker ("we don't know this session — choose"). On pick, the
     * chosen kind is written host-side via `ManualKindWriter` and the session
     * then reads back as that durable recorded kind.
     */
    Unknown,
    ;

    companion object {
        /**
         * The kinds a user can manually assign to a session through the
         * "change kind" / "unknown → pick" picker (epic #821 Slice 1).
         * [Probing], [Exited], and [Unknown] are transient/derived states,
         * not user-assignable classifications, so they are excluded.
         *
         * Ordered Claude / Codex / OpenCode / Grok / Shell — agents first (the
         * common case), shell last.
         */
        val pickable: List<SessionAgentKind> =
            listOf(Claude, Codex, OpenCode, Grok, Shell)
    }
}

/**
 * True when this kind is a live agent engine — Claude / Codex / OpenCode.
 *
 * Issue #1570: used by [resolveSessionAgentState] to decide that fresh output
 * after a recorded resting state means the agent RESUMED working (Working),
 * versus a non-agent session where the same activity cannot be attributed to an
 * agent (stays Unknown). [Probing]/[Exited]/[Shell]/[Unknown] are deliberately
 * excluded so a probing/foreign session never gets a guessed Working chip.
 */
fun SessionAgentKind.isLiveAgent(): Boolean = when (this) {
    SessionAgentKind.Claude,
    SessionAgentKind.Codex,
    SessionAgentKind.OpenCode,
    SessionAgentKind.Grok,
    -> true
    SessionAgentKind.Shell,
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    SessionAgentKind.Unknown,
    -> false
}
