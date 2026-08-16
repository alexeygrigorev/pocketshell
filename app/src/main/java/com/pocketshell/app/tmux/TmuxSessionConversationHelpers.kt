package com.pocketshell.app.tmux

import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.conversationSyncStatusForLoad
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole

/**
 * Issue #1555 (D28 / file-size hygiene ratchet): pure, VM-state-free
 * conversation/agent-detection helpers split out of the [TmuxSessionViewModel]
 * god-object into cohesive same-package top-level functions. Extraction only —
 * byte-identical bodies, zero behaviour change; the VM calls them unqualified
 * (same package) exactly as it called the former private members.
 */

/**
 * The text a conversation event contributes to port-offer scanning, or `null`
 * when the event carries nothing scannable (assistant messages, tool results,
 * and system notes carry offers; tool calls and blank text do not).
 */
internal fun ConversationEvent.portOfferText(): String? = when (this) {
    is ConversationEvent.Message -> text.takeIf {
        role == ConversationRole.Assistant && it.isNotBlank()
    }
    is ConversationEvent.ToolResult -> output.takeIf { it.isNotBlank() }
    is ConversationEvent.SystemNote -> content.takeIf { it.isNotBlank() }
    is ConversationEvent.ToolCall -> null
}

/**
 * Issue #495: two detections describe the same live agent session when
 * the agent kind and the log source path match. Confidence and
 * sessionId can drift between a seeded reconnect verdict and the live
 * re-detection without meaning "a different agent" — treating that as a
 * new agent would discard the user's tab choice.
 */
internal fun sameAgentSource(left: AgentDetection?, right: AgentDetection?): Boolean =
    left != null &&
        right != null &&
        left.agent == right.agent &&
        left.sourcePath == right.sourcePath

/**
 * Issue #2159 (extracted from the [TmuxSessionViewModel] god-object per the
 * #1047 downward ratchet): the pure reducer behind `markAgentTailLive`.
 *
 * [readFailed] is what makes the status honest. `Live` is a CLAIM — "the feed is
 * healthy and current" — and it used to be stamped unconditionally, so a
 * transcript read that failed, or that silently produced nothing (every read runs
 * behind `2>/dev/null || true`, so an unresolvable / errored / version-skewed
 * host answer is an empty string), painted a green dot over
 * "No conversation events yet." while the same session's Terminal showed a live
 * agent mid-turn. That is the maintainer's reported screen in issue #2159. The
 * status now comes from [conversationSyncStatusForLoad].
 */
internal fun nextAgentTailLiveState(
    current: AgentConversationUiState?,
    detection: AgentDetection,
    initialEvents: List<ConversationEvent>,
    preserveDifferentDetection: Boolean,
    readFailed: Boolean,
    openTimeDefaultTab: SessionTab,
): AgentConversationUiState {
    fun statusFor(events: List<ConversationEvent>) =
        conversationSyncStatusForLoad(readFailed, events.isNotEmpty())
    // A fresh POSITIVE agent detection landed on a pane with no existing
    // conversation row. This is the OPEN/initial-tab moment for the agent
    // session, so the new row lands on the user's configured open-time default
    // (#818) — Conversation by default (the black-screen cure), Terminal if the
    // user opted out. This is NOT a mid-session yank: there is no existing row,
    // so no tab the user is currently viewing is being changed (the #815 line is
    // about detection/refresh on an ALREADY-open session, handled by the
    // `current != null` branches below, which preserve the user's tab). A
    // remembered/explicit per-session choice still wins —
    // `seedAgentConversationFromMemory` runs first and would have created the row
    // already if a remembered choice existed.
    if (current == null) {
        val events = boundedDistinctEvents(initialEvents)
        return AgentConversationUiState(
            detection = detection,
            events = events,
            selectedTab = openTimeDefaultTab,
            syncStatus = statusFor(events),
        )
    }
    if (current.detection != detection && preserveDifferentDetection) return current
    // Issue #495: when live detection refines the SAME agent on the SAME log for
    // this window (only confidence/sessionId drifted — e.g. a seeded reconnect
    // verdict promoted from RecentFile to ProcessConfirmed), keep the user's
    // selected tab. The previous unconditional reset-to-Terminal here bounced a
    // user who was in Conversation back to Terminal on every reconnect.
    if (current.detection != detection && sameAgentSource(current.detection, detection)) {
        val events = boundedDistinctEvents(current.events + initialEvents)
        return current.copy(
            detection = detection,
            events = events,
            syncStatus = statusFor(events),
        )
    }
    // A DIFFERENT agent (no same-source continuity) took over this pane's window.
    // This is a detection/refresh on an ALREADY-open session, NOT an open-time
    // event, so it must NOT yank the user onto another view in EITHER direction
    // (#815): PRESERVE the tab the user is currently viewing rather than apply the
    // open-time default. (Applying it here would yank a user on Terminal onto
    // Conversation on a mid-session takeover — exactly the #815 regression.)
    if (current.detection != detection) {
        val events = boundedDistinctEvents(initialEvents)
        return AgentConversationUiState(
            detection = detection,
            events = events,
            selectedTab = current.selectedTab,
            syncStatus = statusFor(events),
        )
    }
    val events = boundedDistinctEvents(current.events + initialEvents)
    return current.copy(events = events, syncStatus = statusFor(events))
}
