package com.pocketshell.uikit.model

/**
 * Resting-state classifier for an agent session, surfaced as a compact
 * agent-state chip on host cards and session rows (issue #1237). It answers
 * "which running agent is idle / working / waiting-for-input?" at a glance so
 * the user does not have to open each session to find the one that just asked
 * a question.
 *
 * ## Source of truth — the stop/idle hook bus, NOT a poll
 *
 * The host-side generated Claude / Codex / OpenCode hook handlers best-effort
 * write two session-scoped tmux user options when running inside tmux (landed
 * host-side in PR #1373):
 *
 *  - `@ps_agent_state` — `FINISHED` → `idle`, `WAITING_FOR_INPUT` →
 *    `waiting_for_input`.
 *  - `@ps_agent_state_updated_at` — epoch-seconds timestamp of that write, for
 *    the staleness rule below.
 *
 * The hooks fire ONLY on stop / waiting, so `working` is NOT authoritative from
 * the bus. The option value space the app actually reads is therefore `idle`,
 * `waiting_for_input`, or absent/empty. [Working] exists so that a host that
 * DOES record an authoritative `working` (future) renders correctly, but the
 * app never GUESSES it: an attached-but-not-idle session is [Unknown], never
 * [Working] (issue #1237 scope: "absent — not wrong — when unknown").
 *
 * The chip is read back on the SAME warm-session list-sessions read paths the
 * folder tree / dashboard already use (the periodic SSH reconcile probe and the
 * dashboard's existing list-sessions poll) — no new poll loop is added
 * (D21-compliant).
 */
enum class SessionAgentState {
    /** The agent finished its turn and is resting (hook `FINISHED`). */
    Idle,

    /** The agent is blocked on a prompt/permission and needs the user (hook `WAITING_FOR_INPUT`). */
    WaitingForInput,

    /**
     * The agent is actively working. NOT derivable from the stop/idle hook bus
     * (it fires only on stop/waiting), so this is rendered ONLY when a host
     * authoritatively records `working` — never guessed from attach/liveness.
     */
    Working,

    /**
     * No authoritative state is known — the option is absent/empty, unrecognised,
     * or a resting state has gone stale (see [resolveSessionAgentState]). The UI
     * renders NO chip for this: absent, never a wrong chip.
     */
    Unknown,
    ;

    /** True when there is an authoritative state to show a chip for. */
    val isKnown: Boolean get() = this != Unknown

    /**
     * Short chip label, or `null` for [Unknown] (render no chip). Kept here so
     * the host-card chip and the session-row chip stay consistent.
     */
    val chipLabel: String?
        get() = when (this) {
            Idle -> "Idle"
            WaitingForInput -> "Waiting"
            Working -> "Working"
            Unknown -> null
        }
}

/**
 * Map a raw host-side `@ps_agent_state` tmux user-option value to a
 * [SessionAgentState]. An unset option expands to empty in tmux's `-F` output
 * (NOT a missing column), which maps to [SessionAgentState.Unknown] so a
 * foreign / never-hooked session shows no chip rather than a wrong one.
 *
 * Kept as the single option↔state mapping so the read-back paths
 * (folder-list enumeration, dashboard) never drift.
 */
fun sessionAgentStateFromOption(raw: String?): SessionAgentState =
    when (raw?.trim()?.lowercase()) {
        "idle", "finished" -> SessionAgentState.Idle
        "waiting_for_input", "waiting" -> SessionAgentState.WaitingForInput
        "working", "running", "busy" -> SessionAgentState.Working
        else -> SessionAgentState.Unknown
    }

/**
 * Default staleness grace, in seconds, for [resolveSessionAgentState]. A small
 * cushion so a same-second race between the hook's option write and the pane's
 * final output does not spuriously flip a fresh state to [SessionAgentState.Unknown].
 */
const val AGENT_STATE_STALE_GRACE_SEC: Long = 3L

/**
 * Resolve the trustworthy agent state for a session from the raw option plus
 * freshness signals.
 *
 * The stop/idle hooks fire only on stop / waiting, so once a resting state is
 * recorded the option is NOT updated again until the next stop/waiting — even
 * if the user answers and the agent starts working. The `session_activity`
 * timestamp is the authoritative "something happened here since" signal: when
 * the session has produced output AFTER the state was recorded (beyond a small
 * [graceSec] cushion), the recorded resting state is stale and we can no longer
 * authoritatively claim it. In that case we return [SessionAgentState.Unknown]
 * (no chip) rather than showing a wrong "Idle"/"Waiting" — honouring the
 * "absent, not wrong, when unknown" rule.
 *
 * The staleness rule is applied ONLY to the resting states ([SessionAgentState.Idle],
 * [SessionAgentState.WaitingForInput]); a [SessionAgentState.Working] session is
 * inherently producing output, so activity newer than its timestamp is expected
 * and must NOT flip it to Unknown.
 *
 * @param rawState the raw `@ps_agent_state` option value (may be null/blank).
 * @param stateUpdatedAtEpochSec `@ps_agent_state_updated_at` epoch seconds, or
 *   null when the host did not record a timestamp (older host CLI). With no
 *   timestamp the staleness rule cannot run, so the recorded state is taken
 *   at face value (best-effort, still better than nothing).
 * @param sessionActivityEpochSec tmux `#{session_activity}` epoch seconds for the
 *   session, or null when unknown.
 * @param graceSec staleness cushion; defaults to [AGENT_STATE_STALE_GRACE_SEC].
 */
fun resolveSessionAgentState(
    rawState: String?,
    stateUpdatedAtEpochSec: Long?,
    sessionActivityEpochSec: Long?,
    graceSec: Long = AGENT_STATE_STALE_GRACE_SEC,
): SessionAgentState {
    val parsed = sessionAgentStateFromOption(rawState)
    return when (parsed) {
        SessionAgentState.Idle, SessionAgentState.WaitingForInput ->
            if (isRecordedStateStale(stateUpdatedAtEpochSec, sessionActivityEpochSec, graceSec)) {
                SessionAgentState.Unknown
            } else {
                parsed
            }
        SessionAgentState.Working -> parsed
        SessionAgentState.Unknown -> SessionAgentState.Unknown
    }
}

private fun isRecordedStateStale(
    stateUpdatedAtEpochSec: Long?,
    sessionActivityEpochSec: Long?,
    graceSec: Long,
): Boolean {
    if (stateUpdatedAtEpochSec == null || sessionActivityEpochSec == null) return false
    return sessionActivityEpochSec > stateUpdatedAtEpochSec + graceSec
}
