package com.pocketshell.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2159 — the status the Conversation pane displays must be a TOTAL
 * function of `(syncStatus, hasEvents)`.
 *
 * The maintainer's screenshot showed a green `Conversation: Live` directly above
 * "No conversation events yet." while the same session's Terminal was rendering a
 * live Codex agent mid-turn. `Live` asserts the feed is healthy and current, so
 * over an empty pane it is a false claim — and it is the difference between "the
 * agent simply hasn't spoken yet" and "we're bound to nothing".
 *
 * `Issue2159LiveOverEmptyConversationTest` pins the ViewModel's own state
 * transitions; this pins the render-boundary collapse so no call site — present
 * or future — can put the green dot back over an empty feed.
 *
 * Mutation that must redden this: make [resolvedConversationSyncStatus] return
 * its `syncStatus` argument unchanged.
 */
class Issue2159ConversationStatusInvariantTest {

    @Test
    fun noStatusEverResolvesToLiveOverAnEmptyFeed() {
        // Exhaustive over the enum, so a future status cannot slip through with
        // an unchecked green dot.
        for (status in AgentConversationSyncStatus.entries) {
            assertNotEquals(
                "#2159: `$status` over an EMPTY feed must not display as `Live` — " +
                    "a green dot above \"No conversation events yet.\" is the " +
                    "maintainer's reported screen.",
                AgentConversationSyncStatus.Live,
                resolvedConversationSyncStatus(status, hasEvents = false),
            )
        }
    }

    @Test
    fun anEmptyLiveFeedReportsNoMessagesAndOffersARetry() {
        val resolved = resolvedConversationSyncStatus(
            AgentConversationSyncStatus.Live,
            hasEvents = false,
        )
        assertEquals(AgentConversationSyncStatus.NoMessages, resolved)
        assertEquals("No messages yet", conversationSyncStatusLabel(resolved))
        assertTrue(
            "#2159: an empty feed must offer an actionable next step (Retry).",
            resolved.canRetryAgentStream,
        )
    }

    @Test
    fun aPopulatedFeedKeepsItsStatusVerbatim() {
        // Selectivity (G6): the collapse must fire ONLY on the empty feed. If it
        // rewrote populated rows too, the mutation above would redden this as
        // well and the proof would be over-broad rather than targeted.
        for (status in AgentConversationSyncStatus.entries) {
            assertEquals(
                "#2159: a populated feed's status must pass through untouched.",
                status,
                resolvedConversationSyncStatus(status, hasEvents = true),
            )
        }
    }

    @Test
    fun aFailedOrStaleEmptyFeedKeepsItsOwnHonestStatus() {
        // The empty-feed collapse must not flatten a genuine failure into the
        // softer "no messages yet" — an unreadable log stays red + retryable.
        assertEquals(
            AgentConversationSyncStatus.LogUnavailable,
            resolvedConversationSyncStatus(
                AgentConversationSyncStatus.LogUnavailable,
                hasEvents = false,
            ),
        )
        assertEquals(
            AgentConversationSyncStatus.Stale,
            resolvedConversationSyncStatus(
                AgentConversationSyncStatus.Stale,
                hasEvents = false,
            ),
        )
    }
}
