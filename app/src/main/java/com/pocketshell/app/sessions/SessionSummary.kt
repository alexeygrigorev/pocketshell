package com.pocketshell.app.sessions

/**
 * One tmux session discovered on a connected host. Aggregated across
 * every host with a live [com.pocketshell.core.tmux.TmuxClient] in
 * [ActiveTmuxClients], sorted by recency (most-recent first) by
 * [SessionsDashboardViewModel] so the host list can derive per-host
 * status chips without rendering a flat all-host sessions surface.
 *
 * The fields are the cross-host session projection plus the host
 * identifiers needed by lifecycle helpers and tests:
 *
 * @property hostId persistent host row id — matches
 *   [com.pocketshell.core.storage.entity.HostEntity.id]. Used by the
 *   tap-to-open helpers so callers can re-resolve SSH parameters.
 * @property hostName user-facing host label (e.g. `"hetzner"`).
 * @property sessionName tmux session name as reported by `list-sessions`'s
 *   `#{session_name}` format — passed verbatim to
 *   [com.pocketshell.app.nav.AppDestination.TmuxSession.sessionName] on
 *   navigation.
 * @property lastActivity tmux session activity timestamp in **seconds**
 *   since the Unix epoch — the raw value of `#{session_activity}`. Kept
 *   as `Long` so the aggregate can sort numerically.
 * @property attached `true` when the session has at least one attached
 *   tmux client (i.e. `#{session_attached}` is non-zero). Not surfaced
 *   visually in v1 but parsed so a future visual hint (e.g. a dot, or
 *   bumping attached sessions above idle ones at equal recency) is a
 *   pure UI change.
 */
data class SessionSummary(
    val hostId: Long,
    val hostName: String,
    val sessionName: String,
    val lastActivity: Long,
    val attached: Boolean,
)
