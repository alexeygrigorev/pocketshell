package com.pocketshell.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #824 regression: a running tool-call card (Codex `write_stdin` long poll —
 * no paired result yet) must be collapsible by tap and STAY collapsed across
 * subsequent events / recomposition while it is still running.
 */
class ConversationToolCardExpansionTest {

    @Test
    fun runningCardWithoutResultAutoExpandsBeforeAnyTap() {
        val expanded = ConversationToolCardExpansion.isExpanded(
            userOverride = null,
            isRunning = true,
            hasResult = false,
            isSearchExpanded = false,
        )
        assertTrue("a running, untapped card auto-expands by default", expanded)
    }

    @Test
    fun tappingRunningCardCollapsesIt() {
        // This is the #824 bug: before the fix the running card could not be
        // collapsed because `isRunning && !hasResult` was OR-ed on top.
        val overrides = ConversationToolCardExpansion.toggle(
            overrides = emptyMap(),
            id = "call:write_stdin-1",
            currentlyExpanded = true,
        )
        val expanded = ConversationToolCardExpansion.isExpanded(
            userOverride = overrides["call:write_stdin-1"],
            isRunning = true,
            hasResult = false,
            isSearchExpanded = false,
        )
        assertFalse("tapping a running card must collapse it", expanded)
    }

    @Test
    fun collapsedRunningCardStaysCollapsedAcrossNewEventsWhileStillRunning() {
        // User collapsed it once.
        var overrides = ConversationToolCardExpansion.toggle(
            overrides = emptyMap(),
            id = "call:write_stdin-1",
            currentlyExpanded = true,
        )
        // A subsequent transcript event arrives; the card is STILL running
        // (still no result). The override must survive recomposition and not
        // silently re-expand.
        val expandedAfterNewEvent = ConversationToolCardExpansion.isExpanded(
            userOverride = overrides["call:write_stdin-1"],
            isRunning = true,
            hasResult = false,
            isSearchExpanded = false,
        )
        assertFalse(
            "a card the user collapsed must NOT auto-re-expand on a new event",
            expandedAfterNewEvent,
        )

        // Tapping again re-expands it.
        overrides = ConversationToolCardExpansion.toggle(
            overrides = overrides,
            id = "call:write_stdin-1",
            currentlyExpanded = false,
        )
        assertTrue(
            "tapping a collapsed running card re-expands it",
            ConversationToolCardExpansion.isExpanded(
                userOverride = overrides["call:write_stdin-1"],
                isRunning = true,
                hasResult = false,
                isSearchExpanded = false,
            ),
        )
    }

    @Test
    fun completedCardCollapsedByDefaultAndExpandsOnTap() {
        // A completed call (has result) starts collapsed.
        assertFalse(
            ConversationToolCardExpansion.isExpanded(
                userOverride = null,
                isRunning = false,
                hasResult = true,
                isSearchExpanded = false,
            ),
        )
        val overrides = ConversationToolCardExpansion.toggle(
            overrides = emptyMap(),
            id = "call:done-1",
            currentlyExpanded = false,
        )
        assertTrue(
            ConversationToolCardExpansion.isExpanded(
                userOverride = overrides["call:done-1"],
                isRunning = false,
                hasResult = true,
                isSearchExpanded = false,
            ),
        )
    }

    @Test
    fun searchExpandsCardButUserCanStillCollapseIt() {
        assertTrue(
            "a search match auto-expands the card",
            ConversationToolCardExpansion.isExpanded(
                userOverride = null,
                isRunning = false,
                hasResult = true,
                isSearchExpanded = true,
            ),
        )
        val overrides = ConversationToolCardExpansion.toggle(
            overrides = emptyMap(),
            id = "call:match-1",
            currentlyExpanded = true,
        )
        assertFalse(
            "the user can collapse a search-surfaced card",
            ConversationToolCardExpansion.isExpanded(
                userOverride = overrides["call:match-1"],
                isRunning = false,
                hasResult = true,
                isSearchExpanded = true,
            ),
        )
    }

    @Test
    fun overrideMapIsKeyedPerCardSoSiblingsAreIndependent() {
        // Collapse only the latest card; the sibling keeps its own state.
        val overrides = ConversationToolCardExpansion.toggle(
            overrides = emptyMap(),
            id = "call:latest",
            currentlyExpanded = true,
        )
        assertFalse(
            ConversationToolCardExpansion.isExpanded(
                userOverride = overrides["call:latest"],
                isRunning = true,
                hasResult = false,
                isSearchExpanded = false,
            ),
        )
        assertEquals(null, overrides["call:sibling"])
        assertTrue(
            "an untouched sibling running card keeps its auto-expand default",
            ConversationToolCardExpansion.isExpanded(
                userOverride = overrides["call:sibling"],
                isRunning = true,
                hasResult = false,
                isSearchExpanded = false,
            ),
        )
    }
}
