package com.pocketshell.app.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChecklistUiTest {
    @Test
    fun checklistChipStateCountsItemsAcrossCards() {
        val state = checklistChipState(
            listOf(
                SessionCardsRemoteSource.ChecklistCard(
                    id = "a",
                    title = "Release",
                    createdAt = null,
                    updatedAt = null,
                    items = listOf(
                        SessionCardsRemoteSource.ChecklistItem("build", "Build"),
                        SessionCardsRemoteSource.ChecklistItem("test", "Test"),
                    ),
                    checkedIds = setOf("build"),
                ),
                SessionCardsRemoteSource.ChecklistCard(
                    id = "b",
                    title = "Deploy",
                    createdAt = null,
                    updatedAt = null,
                    items = listOf(SessionCardsRemoteSource.ChecklistItem("ship", "Ship")),
                    checkedIds = setOf("ship"),
                ),
            ),
        )

        assertEquals(ChecklistChipUiState(total = 3, checked = 2), state)
    }

    @Test
    fun checklistChipStateUsesSingleCardTitle() {
        val state = checklistChipState(
            listOf(
                SessionCardsRemoteSource.ChecklistCard(
                    id = "a",
                    title = "Release",
                    createdAt = null,
                    updatedAt = null,
                    items = listOf(SessionCardsRemoteSource.ChecklistItem("build", "Build")),
                    checkedIds = emptySet(),
                ),
            ),
        )

        assertEquals(ChecklistChipUiState(total = 1, checked = 0, title = "Release"), state)
    }

    @Test
    fun checklistChipStateReturnsNullForEmptyCards() {
        assertNull(checklistChipState(emptyList()))
    }
}
