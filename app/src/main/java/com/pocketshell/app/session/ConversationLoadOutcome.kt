package com.pocketshell.app.session

/**
 * Issue #2159: the honest outcome of a Conversation transcript read.
 *
 * ### Why this exists
 *
 * The maintainer photographed the Conversation tab reporting a green
 * `Conversation: Live` directly above "No conversation events yet.", while the
 * SAME tmux session's Terminal showed a live Codex agent mid-turn with a full
 * transcript. On the host the session carried `@ps_agent_kind = codex` and
 * `@ps_agent_source_generation`, but `@ps_agent_source` was never written, so the
 * client fell back to its same-kind selector — which DID bind the healthy rollout.
 * The transcript READ is what came back with nothing.
 *
 * `Live` is a CLAIM: "the feed is healthy and current". It used to be stamped
 * unconditionally by `markAgentTailLive`, so a read that failed — or that
 * silently produced nothing, because every transcript read runs behind
 * `2>/dev/null || true` and an unresolvable/errored/version-skewed host answer is
 * an empty string — was presented as a clean, healthy, empty conversation with no
 * retry offered. That is strictly worse than an error: it tells the user nothing
 * is wrong.
 *
 * These two functions are the single decision point for "what did that read
 * actually mean", shared by the first-open path and the reconnect-restore path so
 * they cannot drift apart.
 */

/**
 * The transcript freshness to report after a load completed.
 *
 * - [readFailed] — the read threw, or the host produced no usable window for a
 *   source that demonstrably has content ([ConversationEventsWindow.sourceUnavailable]).
 *   Retryable and red; the row's load state goes [ConversationLoadState.Failed]
 *   so the screen shows the retry affordance directly.
 * - no events — bound, nothing to show yet. Honest and retryable, never `Live`.
 * - events present — genuinely [AgentConversationSyncStatus.Live].
 */
internal fun conversationSyncStatusForLoad(
    readFailed: Boolean,
    hasEvents: Boolean,
): AgentConversationSyncStatus = when {
    readFailed -> AgentConversationSyncStatus.LogUnavailable
    !hasEvents -> AgentConversationSyncStatus.NoMessages
    else -> AgentConversationSyncStatus.Live
}

/**
 * The load state to report after a load completed, given the same two facts.
 *
 * A failed read with nothing to show is a clear, retryable [ConversationLoadState.Failed]
 * — NOT a `Ready` feed, which is what the reconnect-restore path used to force
 * unconditionally, erasing the failure. A failed read that still has previously
 * restored events keeps those events readable ([ConversationLoadState.Ready]);
 * the status carries the failure instead of blanking the pane (#1057).
 */
internal fun conversationLoadStateForOutcome(
    readFailed: Boolean,
    hasEvents: Boolean,
): ConversationLoadState = when {
    readFailed && !hasEvents -> ConversationLoadState.Failed
    !hasEvents -> ConversationLoadState.Empty
    else -> ConversationLoadState.Ready
}
