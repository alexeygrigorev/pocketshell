package com.pocketshell.app.cards

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionChecklistUiInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checklistCardsContentItemRowInvokesToggleWithNextCheckedState() {
        val toggles = mutableListOf<ToggleEvent>()

        composeRule.setContent {
            PocketShellTheme {
                ChecklistCardsContent(
                    cards = listOf(
                        checklistCard(
                            id = "card-1",
                            items = listOf(
                                SessionCardsRemoteSource.ChecklistItem("item-1", "Build"),
                            ),
                            checkedIds = emptySet(),
                        ),
                    ),
                    onToggle = { cardId, itemId, checked ->
                        toggles += ToggleEvent(cardId, itemId, checked)
                    },
                    onClose = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(SESSION_CHECKLIST_ITEM_TAG_PREFIX + "card-1:item-1")
            .performClick()

        assertEquals(listOf(ToggleEvent("card-1", "item-1", checked = true)), toggles)
    }

    @Test
    fun checklistCardsContentCloseInvokesOnClose() {
        var closeCount = 0

        composeRule.setContent {
            PocketShellTheme {
                ChecklistCardsContent(
                    cards = listOf(
                        checklistCard(
                            id = "card-1",
                            items = listOf(
                                SessionCardsRemoteSource.ChecklistItem("item-1", "Build"),
                            ),
                            checkedIds = emptySet(),
                        ),
                    ),
                    onToggle = { _, _, _ -> },
                    onClose = { closeCount += 1 },
                )
            }
        }

        composeRule
            .onNodeWithTag(SESSION_CHECKLIST_CLOSE_TAG)
            .performClick()

        assertEquals(1, closeCount)
    }

    private data class ToggleEvent(
        val cardId: String,
        val itemId: String,
        val checked: Boolean,
    )

    private fun checklistCard(
        id: String,
        items: List<SessionCardsRemoteSource.ChecklistItem>,
        checkedIds: Set<String>,
    ): SessionCardsRemoteSource.ChecklistCard = SessionCardsRemoteSource.ChecklistCard(
        id = id,
        title = null,
        createdAt = null,
        updatedAt = null,
        items = items,
        checkedIds = checkedIds,
    )
}
