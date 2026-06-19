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

    /**
     * Epic #821 Slice 1 (foreign-session classification): a session we did
     * NOT launch and that carries no recorded host-side `@ps_agent_kind`
     * tmux option. The maintainer's Option B decision is that we do NOT guess
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
         * Ordered Claude / Codex / OpenCode / Shell — agents first (the
         * common case), shell last.
         */
        val pickable: List<SessionAgentKind> =
            listOf(Claude, Codex, OpenCode, Shell)
    }
}

/**
 * The value written into / read back from the host-side `@ps_agent_kind`
 * tmux user option (epic #821). Mirrors the strings the `pocketshell agent`
 * wrapper records server-side (`record_agent_kind` in `agents.py`):
 * `claude` / `codex` / `opencode`, plus `shell` for a manually-classified
 * plain shell session. Returns `null` for kinds that are not durable
 * recorded classifications ([Probing], [Exited], [Unknown]) — there is
 * nothing meaningful to persist for those.
 */
fun SessionAgentKind.tmuxOptionValue(): String? = when (this) {
    SessionAgentKind.Claude -> "claude"
    SessionAgentKind.Codex -> "codex"
    SessionAgentKind.OpenCode -> "opencode"
    SessionAgentKind.Shell -> "shell"
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    SessionAgentKind.Unknown -> null
}

/**
 * Map a raw host-side `@ps_agent_kind` tmux user-option value back to a
 * [SessionAgentKind]. The single inverse of [tmuxOptionValue] — kept here so
 * the read-back path (folder-list enumeration, in-session change-kind UI) and
 * the write path (`ManualKindWriter`, `record_agent_kind`) never drift.
 *
 * Returns `null` for a blank/absent option (a session we did NOT launch and
 * have NOT classified — surfaced as [SessionAgentKind.Unknown] by the caller)
 * or an unrecognised value, so the caller falls back rather than mislabeling.
 */
fun sessionAgentKindFromOption(raw: String?): SessionAgentKind? =
    when (raw?.trim()?.lowercase()) {
        "claude" -> SessionAgentKind.Claude
        "codex" -> SessionAgentKind.Codex
        "opencode" -> SessionAgentKind.OpenCode
        "shell" -> SessionAgentKind.Shell
        else -> null
    }
