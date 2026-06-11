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
 * UI state for the agent-conversation pane.
 *
 * @property detection the detected agent runtime, or null when no agent is
 *   running in the active pane.
 * @property events the rendered conversation transcript.
 * @property selectedTab the active in-session tab (Terminal vs Conversation).
 * @property syncStatus transcript freshness for the live-dot / banner.
 * @property searchQuery issue #154: persisted search query for the
 *   conversation pane. The value lives on the ViewModel state (not as a local
 *   `remember` in the pane composable) so the query survives Terminal ↔
 *   Conversation tab switches. The pane is the only consumer of [searchQuery],
 *   and the ViewModel exposes a setter (`setAgentSearchQuery` on
 *   `TmuxSessionViewModel`) that the composer wires `onValueChange` into.
 */
public data class AgentConversationUiState(
    val detection: AgentDetection? = null,
    val events: List<ConversationEvent> = emptyList(),
    val selectedTab: SessionTab = SessionTab.Terminal,
    val syncStatus: AgentConversationSyncStatus = AgentConversationSyncStatus.Live,
    val searchQuery: String = "",
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
