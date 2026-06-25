package com.pocketshell.app.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #859 Slice B: prove the renderer registry is genuinely generic — the
 * feed dispatches by `type` through the registry, not a `when (type)` ladder.
 */
class SessionCardRenderersTest {

    @Test
    fun checklistTypeResolvesToChecklistRenderer() {
        assertSame(
            ChecklistCardRenderer,
            SessionCardRenderers.rendererFor(SessionCardsRemoteSource.TYPE_CHECKLIST),
        )
    }

    @Test
    fun noteTypeResolvesToNoteRenderer() {
        // The whole point of Slice B: `note` is a registered renderer, so it
        // dispatches without a feed code change.
        assertSame(
            NoteCardRenderer,
            SessionCardRenderers.rendererFor(SessionCardsRemoteSource.TYPE_NOTE),
        )
    }

    @Test
    fun unknownTypeFallsBackToUnknownRenderer() {
        assertSame(
            UnknownCardRenderer,
            SessionCardRenderers.rendererFor("approval"),
        )
        assertSame(
            UnknownCardRenderer,
            SessionCardRenderers.rendererFor(""),
        )
    }

    @Test
    fun registeredTypesAreChecklistAndNote() {
        assertEquals(setOf("checklist", "note"), SessionCardRenderers.registeredTypes())
    }

    @Test
    fun cardFeedChipCountsAllCardTypes() {
        val state = cardFeedChipState(
            listOf(
                SessionCardsRemoteSource.ChecklistCard(
                    id = "c", title = null, createdAt = null, updatedAt = null,
                    items = listOf(SessionCardsRemoteSource.ChecklistItem("i", "Build")),
                    checkedIds = emptySet(),
                ),
                SessionCardsRemoteSource.NoteCard(
                    id = "n", title = null, createdAt = null, updatedAt = null,
                    text = "fyi", read = false,
                ),
            ),
        )
        assertEquals(SessionCardFeedChipUiState(cardCount = 2), state)
    }

    @Test
    fun cardFeedChipNullForEmptyFeed() {
        assertTrue(cardFeedChipState(emptyList()) == null)
    }
}
