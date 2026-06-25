package com.pocketshell.app.cards

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Issue #859 Slice B: prove the generic feed renders a HETEROGENEOUS card list
 * through the renderer registry (no `when (type)` in the feed):
 *
 * - a checklist card still renders + ticks as before (no regression),
 * - a `note` card renders + marks-read via the registry (new type, no feed
 *   code change),
 * - a genuinely unknown type renders a graceful "unsupported card" row.
 */
class SessionCardFeedRegistryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingInteractions : SessionCardInteractions {
        val checklistToggles = mutableListOf<Triple<String, String, Boolean>>()
        val noteReads = mutableListOf<Pair<String, Boolean>>()

        override fun onToggleChecklistItem(cardId: String, itemId: String, checked: Boolean) {
            checklistToggles += Triple(cardId, itemId, checked)
        }

        override fun onSetNoteRead(cardId: String, read: Boolean) {
            noteReads += cardId to read
        }
    }

    @Test
    fun feedRendersChecklistNoteAndUnknownTypesViaRegistry() {
        val interactions = RecordingInteractions()
        composeRule.setContent {
            PocketShellTheme {
                SessionCardFeedContent(
                    cards = listOf(
                        SessionCardsRemoteSource.ChecklistCard(
                            id = "cl",
                            title = "Deploy",
                            createdAt = null,
                            updatedAt = null,
                            items = listOf(
                                SessionCardsRemoteSource.ChecklistItem("build-0", "Build"),
                            ),
                            checkedIds = emptySet(),
                        ),
                        SessionCardsRemoteSource.NoteCard(
                            id = "nt",
                            title = "Heads up",
                            createdAt = null,
                            updatedAt = null,
                            text = "Deploy finished",
                            read = false,
                        ),
                        SessionCardsRemoteSource.UnknownCard(
                            id = "uk",
                            type = "approval",
                            title = "Approve?",
                            createdAt = null,
                            updatedAt = null,
                        ),
                    ),
                    interactions = interactions,
                    onClose = {},
                )
            }
        }

        // Checklist card + item rendered through the registry's checklist renderer.
        composeRule.onNodeWithTag(SESSION_CHECKLIST_CARD_TAG_PREFIX + "cl").assertIsDisplayed()
        composeRule.onNodeWithText("Build").assertIsDisplayed()
        // Note card rendered through the registry's note renderer.
        composeRule.onNodeWithTag(SESSION_NOTE_CARD_TAG_PREFIX + "nt").assertIsDisplayed()
        composeRule.onNodeWithText("Deploy finished").assertIsDisplayed()
        // Unknown type rendered as the graceful fallback row.
        composeRule.onNodeWithTag(SESSION_UNKNOWN_CARD_TAG_PREFIX + "uk").assertIsDisplayed()
    }

    @Test
    fun checklistItemTickRoutesThroughRegistryRenderer() {
        val interactions = RecordingInteractions()
        composeRule.setContent {
            PocketShellTheme {
                SessionCardFeedContent(
                    cards = listOf(
                        SessionCardsRemoteSource.ChecklistCard(
                            id = "cl",
                            title = null,
                            createdAt = null,
                            updatedAt = null,
                            items = listOf(
                                SessionCardsRemoteSource.ChecklistItem("build-0", "Build"),
                            ),
                            checkedIds = emptySet(),
                        ),
                    ),
                    interactions = interactions,
                    onClose = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(SESSION_CHECKLIST_ITEM_TAG_PREFIX + "cl:build-0")
            .performClick()

        assertEquals(listOf(Triple("cl", "build-0", true)), interactions.checklistToggles)
    }

    @Test
    fun noteMarkReadRoutesThroughRegistryRenderer() {
        val interactions = RecordingInteractions()
        composeRule.setContent {
            PocketShellTheme {
                SessionCardFeedContent(
                    cards = listOf(
                        SessionCardsRemoteSource.NoteCard(
                            id = "nt",
                            title = null,
                            createdAt = null,
                            updatedAt = null,
                            text = "Deploy finished",
                            read = false,
                        ),
                    ),
                    interactions = interactions,
                    onClose = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(SESSION_NOTE_READ_TOGGLE_TAG_PREFIX + "nt")
            .performClick()

        assertEquals(listOf("nt" to true), interactions.noteReads)
    }

    @Test
    fun noteRendererRendersStandaloneViaRegistryLookup() {
        // The registry is the single dispatch site: ask for the `note` renderer
        // and render it directly — no feed-level `when (type)` needed.
        val interactions = RecordingInteractions()
        composeRule.setContent {
            PocketShellTheme {
                SessionCardRenderers
                    .rendererFor(SessionCardsRemoteSource.TYPE_NOTE)
                    .Render(
                        card = SessionCardsRemoteSource.NoteCard(
                            id = "nt",
                            title = "Heads up",
                            createdAt = null,
                            updatedAt = null,
                            text = "Standalone note",
                            read = true,
                        ),
                        interactions = interactions,
                        modifier = Modifier,
                    )
            }
        }

        composeRule.onNodeWithText("Standalone note").assertIsDisplayed()
        composeRule.onNodeWithText("Heads up").assertIsDisplayed()
    }
}
