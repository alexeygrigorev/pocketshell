package com.pocketshell.uikit.model

import java.time.Instant
import java.time.OffsetDateTime

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
 * Parse a raw `@ps_agent_state_updated_at` option value into epoch **seconds**.
 *
 * Issue #1570: the generated host stop/idle hook records this option as
 * `datetime.now(timezone.utc).isoformat()` — an **ISO-8601 string** (e.g.
 * `2026-07-15T12:34:56.789012+00:00`), NOT an epoch integer, even though every
 * consumer's docstring said "epoch int" and every test fed epoch ints. A bare
 * `toLongOrNull()` on the real host value therefore returned `null`, so the
 * staleness rule in [resolveSessionAgentState] could never fire and a stale
 * recorded `idle` (from the agent's last turn-stop) was shown at face value as
 * "Idle" forever — even while the agent had resumed working. This is the same
 * happy-fixture-masks-reality gap that shipped the v0.4.10 connect break: no
 * fixture ever fed the format the host actually writes.
 *
 * Accepts BOTH shapes so the app is robust to whatever the host wrote: an epoch
 * integer (seconds) OR an ISO-8601 timestamp. Returns `null` for a blank/absent
 * or unparseable value.
 */
fun parseAgentStateUpdatedAtEpochSec(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    value.toLongOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()?.let { return it }
    return runCatching { Instant.parse(value).epochSecond }.getOrNull()
}

/**
 * Resolve the trustworthy agent state for a session from the raw option plus
 * freshness signals.
 *
 * The stop/idle hooks fire only on stop / waiting, so once a resting state is
 * recorded the option is NOT updated again until the next stop/waiting — even
 * if the user answers and the agent starts working. The `session_activity`
 * timestamp is the authoritative "something happened here since" signal: when
 * the session has produced output AFTER the state was recorded (beyond a small
 * [graceSec] cushion), the recorded resting state is stale.
 *
 * Issue #1570: for an [isAgentSession] (a live Claude/Codex/OpenCode session)
 * that fresh output IS the agent working again — a working Codex continuously
 * redraws its `Working (…· esc to interrupt)` timer, bumping `session_activity`
 * past the recorded idle-stop, but the stop/idle hook never records the resume.
 * So a stale resting state on a live agent resolves to
 * [SessionAgentState.Working] (the reported "working Codex shows Idle" bug). For
 * a non-agent session we cannot claim the activity is an agent, so it stays
 * [SessionAgentState.Unknown] (no chip) — honouring the "absent, not wrong, when
 * unknown" rule (#1237).
 *
 * The staleness rule is applied ONLY to the resting states ([SessionAgentState.Idle],
 * [SessionAgentState.WaitingForInput]); a [SessionAgentState.Working] session is
 * inherently producing output, so activity newer than its timestamp is expected
 * and must NOT flip it.
 *
 * @param rawState the raw `@ps_agent_state` option value (may be null/blank).
 * @param stateUpdatedAtEpochSec `@ps_agent_state_updated_at` epoch seconds
 *   (parse the raw option with [parseAgentStateUpdatedAtEpochSec] — it accepts
 *   both epoch ints and the ISO-8601 the host actually writes), or null when the
 *   host recorded no/unparseable timestamp. With no timestamp the staleness rule
 *   cannot run, so the recorded state is taken at face value (best-effort).
 * @param sessionActivityEpochSec tmux `#{session_activity}` epoch seconds for the
 *   session, or null when unknown.
 * @param isAgentSession true when this session runs a live agent
 *   (Claude/Codex/OpenCode) whose fresh post-resting output means "working".
 * @param graceSec staleness cushion; defaults to [AGENT_STATE_STALE_GRACE_SEC].
 */
fun resolveSessionAgentState(
    rawState: String?,
    stateUpdatedAtEpochSec: Long?,
    sessionActivityEpochSec: Long?,
    isAgentSession: Boolean = false,
    graceSec: Long = AGENT_STATE_STALE_GRACE_SEC,
): SessionAgentState {
    val parsed = sessionAgentStateFromOption(rawState)
    return when (parsed) {
        SessionAgentState.Idle, SessionAgentState.WaitingForInput ->
            if (isRecordedStateStale(stateUpdatedAtEpochSec, sessionActivityEpochSec, graceSec)) {
                if (isAgentSession) SessionAgentState.Working else SessionAgentState.Unknown
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
