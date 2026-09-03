package com.pocketshell.core.hostapi

/**
 * One session as the host CLI listed it (`pocketshell sessions list --json`,
 * schema 2).
 *
 * Everything except [name], [backend] and [attached] is nullable because the
 * two managers populate different subsets: a plain tmux session has no [id],
 * [tag], [engine] or [profile]; an aplexer session has all of them. A `null`
 * here always means "the host did not report this", never "unset by default".
 */
data class SessionRow(
    val name: String,
    val backend: Backend,
    val id: String?,
    val workspace: String?,
    val tag: String?,
    val engine: String?,
    val profile: String?,
    val agentState: AgentState?,
    val agentStateSource: AgentStateSource?,
    val attached: Boolean,
    val createdEpoch: Long?,
    val activityEpoch: Long?,
)

/**
 * The whole `sessions list --json` document.
 *
 * [errors] is never dropped and never folded into an exception: a backend that
 * failed to enumerate produces an entry here while the other backend's sessions
 * still arrive. The UI must render a partial-list banner when it is non-empty,
 * otherwise "aplexer is broken" is indistinguishable from "aplexer has no
 * sessions" (the regression the host-side schema 2 was built to end).
 */
data class SessionsListing(
    val sessions: List<SessionRow>,
    val errors: List<BackendError>,
)

/** One backend's enumeration failure. [manager] is the raw wire string. */
data class BackendError(
    val manager: String,
    val message: String,
)
