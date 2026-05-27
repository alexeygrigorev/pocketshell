package com.pocketshell.uikit.model

/**
 * Display state for the trailing status indicator on a `HostCard`.
 *
 * Issue #201: the previous design used a flat
 * `enum { Connected, Connecting, Disconnected, Error }` whose
 * `Disconnected` value rendered the literal label "idle". The word was
 * ambiguous (disconnected? empty? unused?) AND it competed visually with
 * the inline `HostSetupBadge` ("needs setup") shown to the right of the
 * host name — the user saw two indicators making contradictory claims.
 *
 * The replacement vocabulary maps each label to exactly one trigger
 * condition. Setup-required wins over session-count display (per the
 * issue AC); when the underlying state is not yet known the chip
 * renders a quiet spinner rather than a stale label.
 *
 * Mapping (driven by the call site in
 * `com.pocketshell.app.hosts.HostListViewModel`):
 *
 * - [Unknown] — first launch / probe in flight. The chip renders a
 *   small progress spinner. No textual label is emitted because we have
 *   no verified state to surface yet.
 * - [NoActiveSessions] — the host is reachable AND `list-sessions`
 *   reports zero tmux sessions. Label: `"No active sessions"`.
 * - [ActiveSessions] with `count = N` — the host is reachable AND has
 *   `N >= 1` tmux sessions, none of which this app is currently
 *   attached to. Label: `"1 session"` / `"N sessions"`.
 * - [Attached] — the app holds a live `tmux -CC` client against the
 *   host AND the registered client is registered as the attached
 *   session. Label: `"Attached"`.
 * - [NeedsSetup] — the most recent bootstrap probe reported `tmux` or
 *   `quse` missing. Takes precedence over any session-count display
 *   because installing the tools is the only useful action until they
 *   exist. The trailing chip is hidden in this state — the inline
 *   `HostSetupBadge` already calls out the same condition with the
 *   actionable affordance, and showing two pills makes the row noisy.
 * - [ConnectionError] — the most recent SSH attempt against the host
 *   failed (transport error, auth failure, host unreachable). Label:
 *   `"Connection error"`.
 *
 * The sealed-interface shape (instead of an enum) is needed because
 * [ActiveSessions] carries the session count — there is no
 * "ActiveSessions(0)"; that's [NoActiveSessions]. Treating zero as a
 * distinct state instead of a degenerate ActiveSessions makes the
 * pluralisation rule unambiguous and the trigger condition explicit.
 *
 * Kept separate from [HostSetupState] (which reports bootstrap-probe
 * readiness) because the two states answer different questions:
 * "do I need to install tools?" vs "is anything running over there?".
 * The renderer in `HostCard` reads both and resolves precedence at
 * paint time.
 */
sealed interface HostStatus {
    /** Probe / first-load state — no verified info to display. */
    data object Unknown : HostStatus

    /** Host reachable, zero tmux sessions reported. */
    data object NoActiveSessions : HostStatus

    /**
     * Host reachable, [count] tmux sessions reported, none attached
     * by this app. `count >= 1` is enforced at call sites; zero must
     * be expressed via [NoActiveSessions] so the trigger condition
     * stays unambiguous.
     */
    data class ActiveSessions(val count: Int) : HostStatus

    /** App is attached to a tmux session on this host. */
    data object Attached : HostStatus

    /** Bootstrap probe reports `tmux` / `quse` missing. */
    data object NeedsSetup : HostStatus

    /** Last SSH attempt failed (transport, auth, unreachable). */
    data object ConnectionError : HostStatus
}
