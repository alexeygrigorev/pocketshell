package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent

/**
 * Shared agent-conversation UI state types.
 *
 * These were originally declared inside the raw-SSH `SessionViewModel`, which
 * was deleted in #684 (C1) once its `AppDestination.Session` route was found
 * to be unreachable in production. The types themselves are shared with the
 * live `tmux -CC` path — `TmuxSessionViewModel`, `TmuxSessionScreen`,
 * `TmuxSessionRuntimeCache`, and the `ConversationSyncStatusUi` helpers all
 * consume them — so they survive the screen/VM removal here.
 */

/** The two tabs the in-session surface toggles between. */
public enum class SessionTab { Terminal, Conversation }

/**
 * Freshness of the agent-conversation transcript relative to the on-server
 * JSONL log the pane tails.
 */
public enum class AgentConversationSyncStatus {
    Live,
    Stale,
    LogUnavailable,
    Retrying,
}

/**
 * Issue #793: lifecycle of the *initial transcript load* for an
 * agent-conversation pane — distinct from [AgentConversationSyncStatus],
 * which describes the freshness of an already-loaded transcript relative to
 * the live log.
 *
 * The Conversation tab previously had only "detection landed → render the
 * feed" vs. "detection pending → spin a `Waiting for agent…` placeholder".
 * That conflated two different things and let the placeholder spin forever
 * when the first tail read never completed (e.g. the transport flap in epic
 * #792). This enum gives the screen a concrete, terminal state machine for
 * the open-an-existing-conversation journey:
 *
 *  - [Loading]   — the first-paint tail read is in flight. Shown as
 *    "Loading conversation…" (NOT "Waiting for agent…", which is reserved
 *    for a genuinely-pending live agent turn).
 *  - [Ready]     — the tail rendered; the feed is populated (or live-tailing
 *    will populate it as the agent writes).
 *  - [Empty]     — the load completed but the transcript has no events yet
 *    (a fresh agent with no turns). A clear terminal state, not a spinner.
 *  - [Failed]    — the first-paint read could not complete (transport drop /
 *    log unavailable). A clear terminal state with a retry affordance, not an
 *    infinite spinner.
 */
public enum class ConversationLoadState {
    Loading,
    Ready,
    Empty,
    Failed,
}

/**
 * UI state for the agent-conversation pane.
 *
 * @property detection the detected agent runtime, or null when no agent is
 *   running in the active pane.
 * @property events the rendered conversation transcript.
 * @property selectedTab the active in-session tab (Terminal vs Conversation).
 * @property syncStatus transcript freshness for the live-dot / banner.
 */
public data class AgentConversationUiState(
    val detection: AgentDetection? = null,
    val events: List<ConversationEvent> = emptyList(),
    val selectedTab: SessionTab = SessionTab.Terminal,
    val syncStatus: AgentConversationSyncStatus = AgentConversationSyncStatus.Live,
    /**
     * Issue #793: lifecycle of the initial tail load. The screen consults
     * this to show "Loading conversation…", a clear empty/failed terminal
     * state, or the populated feed — instead of an infinite
     * "Waiting for agent…" spinner. Defaults to [ConversationLoadState.Ready]
     * so the many pre-existing callers that synthesize a populated state
     * (tests, restored runtimes, optimistic seeds) keep rendering the feed
     * without opting in; the production initial-load path explicitly moves
     * the row through [ConversationLoadState.Loading] → terminal.
     */
    val loadState: ConversationLoadState = ConversationLoadState.Ready,
    /**
     * Issue #793: true when older messages exist *before* the currently
     * loaded tail window and can be paged in on upward scroll. The tail-first
     * load deliberately fetches only the most recent messages on open; this
     * flag tells the pane to surface a "load older" affordance / trigger.
     */
    val hasMoreOlderEvents: Boolean = false,
    /**
     * Issue #793: true while a page-older fetch is in flight, so the pane can
     * show a top-of-list progress row and avoid firing duplicate paging
     * requests for the same scroll.
     */
    val isPagingOlder: Boolean = false,
    /**
     * Issue #878: true when this detection-less placeholder row was seeded
     * AUTOMATICALLY at pane-add for a presumed-agent pane (the #818
     * black-screen cure: show the Conversation "Loading…" placeholder during
     * the detection window instead of the black Terminal), as opposed to a
     * user DELIBERATELY tapping the Conversation tab (#778).
     *
     * The distinction matters when live detection comes back NULL (the pane is
     * a genuine shell, not an agent): a user-tapped placeholder is RETAINED
     * (the user explicitly asked for the Conversation surface, so its watchdog
     * resolves it to a clear Failed state), but an auto-seeded placeholder must
     * be DROPPED so a shell pane the user never opted into does not linger on
     * "Loading conversation…" → "Failed". Cleared (to false) the moment a real
     * detection lands, since the row is then a genuine agent row.
     */
    val autoSeededPlaceholder: Boolean = false,
    /**
     * Issue #819 (Slice A2): true when this detection-less placeholder row was
     * seeded from [com.pocketshell.app.tmux.AgentSessionMemory] for a window
     * that was KNOWN to host an agent before a reconnect (the #495 reattach
     * seed), and is holding "resolving" while live re-detection re-anchors the
     * route-true transcript source.
     *
     * The #495 seed used to restore the remembered [AgentDetection] BLIND —
     * carrying its `sourcePath` — and render it `Live` immediately, before live
     * re-detection confirmed the source was still the route's own session. When
     * that remembered source had been captured during a prior same-cwd same-kind
     * mis-pick (a sibling / sub-agent / second window/worktree Codex rollout),
     * the Conversation tab rendered the WRONG transcript under a route-true
     * Terminal until the live `/proc/<pid>/fd` round-trip landed (#819). So the
     * seed now restores only the remembered KIND + the user's tab choice as a
     * resolving placeholder, never the stale source, and live
     * `detectRecordedSessionForPane` binds the real source.
     *
     * Like [autoSeededPlaceholder] this is the auto-seed's own teardown marker
     * for [com.pocketshell.app.tmux.TmuxSessionViewModel] `clearAgentDetectionForPane`
     * (drop on a confirmed exit, do not strand on "Loading…"), but unlike it the
     * window is a CONFIRMED-prior agent, so a transient null after reattach is
     * held and re-confirmed for `AGENT_EXIT_CONFIRMATIONS` (the #554 no-flap
     * guarantee) before teardown. Cleared (to false) the moment a real detection
     * lands, since the row is then a genuine agent row.
     */
    val rememberedAgentPlaceholder: Boolean = false,
)

/**
 * Default host parameters for the terminal lab / proof harness — same values
 * the Phase 0 proof-of-life screen used. Consumed by
 * [com.pocketshell.app.terminal.TerminalLabActivity].
 */
public object SessionDefaults {
    public const val HOST: String = "10.0.2.2"
    public const val PORT: Int = 2222
    public const val USER: String = "testuser"
}
