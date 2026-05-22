package com.pocketshell.app.sessions

/**
 * One row in the dashboard's "Sessions" section — a tmux session
 * discovered on a connected host. Aggregated across every host with a
 * live [com.pocketshell.core.tmux.TmuxClient] in
 * [ActiveTmuxClients], sorted by recency (most-recent first) by
 * [SessionsDashboardViewModel].
 *
 * The fields are exactly the projection needed by
 * `docs/mockups/dashboard.html` under "Sessions" plus the host
 * identifiers required to navigate to
 * [com.pocketshell.app.nav.AppDestination.TmuxSession]:
 *
 * @property hostId persistent host row id — matches
 *   [com.pocketshell.core.storage.entity.HostEntity.id]. Used by the
 *   tap-to-open path so the screen can re-resolve the SSH parameters.
 * @property hostName user-facing host label (e.g. `"hetzner"`) shown next
 *   to the session name as ` · <hostName>`. Matches the `.sess-host`
 *   suffix in the mockup.
 * @property sessionName tmux session name as reported by `list-sessions`'s
 *   `#{session_name}` format — passed verbatim to
 *   [com.pocketshell.app.nav.AppDestination.TmuxSession.sessionName] on
 *   navigation.
 * @property lastActivity tmux session activity timestamp in **seconds**
 *   since the Unix epoch — the raw value of `#{session_activity}`. Kept
 *   as `Long` so the dashboard can sort numerically; the relative-time
 *   formatter ([formatRelativeTime]) lives at the render boundary.
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
