package com.pocketshell.core.hostapi

/**
 * One session as the host CLI listed it (`pocketshell sessions list --json`,
 * schema 3).
 *
 * Everything except [name] and [attached] is nullable because the host may
 * omit optional session metadata. A `null` here always means "the host did
 * not report this", never "unset by default".
 */
data class SessionRow(
    val name: String,
    val id: String?,
    val workspace: String?,
    val tag: String?,
    val engine: String?,
    val profile: String?,
    /**
     * Which agent aplexer detected RUNNING inside this session's workload
     * ("claude", "codex", "opencode", "grok"), lowercase, verbatim off the
     * wire (issue #2579).
     *
     * Distinct from [engine], which is only what the session was STARTED as:
     * every aplexer session on the maintainer's box is `engine: "shell"`
     * (`a start … -- /bin/bash -l`) with the agent launched inside it
     * afterwards, so [engine] answers nothing about what is running now.
     * aplexer owns the workload process tree, so it is the one place that can
     * answer; the client never probes for this itself.
     *
     * `null` means the host reported no agent, or a host CLI did not emit the
     * key at all. Both collapse to "no focus" at the call site.
     */
    val agent: String?,
    val agentState: AgentState?,
    val agentStateSource: AgentStateSource?,
    val attached: Boolean,
    val createdEpoch: Long?,
    val activityEpoch: Long?,
)

/**
 * The whole `sessions list --json` document.
 *
 * [errors] is never dropped and never folded into an exception. The UI must
 * render the error when the host could not enumerate sessions, so an
 * unavailable aplexer is distinguishable from an empty host.
 */
data class SessionsListing(
    val sessions: List<SessionRow>,
    val errors: List<SessionListError>,
)

/** One session-list enumeration failure. */
data class SessionListError(
    val message: String,
)
