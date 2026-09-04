package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [WorkspaceRootsScreen] as a stateless composable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h4000dp")
class WorkspaceRootsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `no roots shows the empty state instead of a blank list`() {
        setContent(state = WorkspaceRootsUiState(hostName = "hetzner", loaded = true))

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `the add button is disabled until a path is typed`() {
        setContent()

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).assertIsNotEnabled()
    }

    @Test
    fun `typing a path and tapping add reports both fields and clears them`() {
        var added: Pair<String, String>? = null
        setContent(onAddRoot = { label, path -> added = label to path })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_LABEL_FIELD_TAG).performTextInput("Pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_PATH_FIELD_TAG).performTextInput("/home/alexey/git/pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).performClick()

        assertEquals("Pocketshell" to "/home/alexey/git/pocketshell", added)
        // The add control disables again once the fields it just cleared are empty.
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).assertIsNotEnabled()
    }

    @Test
    fun `an existing root renders as a row with a delete action`() {
        var deleted: WorkspaceRootRow? = null
        val root = WorkspaceRootRow(id = 1, label = "Pocketshell", path = "/home/alexey/git/pocketshell")
        setContent(
            state = WorkspaceRootsUiState(hostName = "hetzner", roots = listOf(root), loaded = true),
            onDeleteRoot = { deleted = it },
        )

        composeRule.onNodeWithTag(workspaceRootRowTag(1)).assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceRootMenuTag(1)).performClick()
        composeRule.onNodeWithTag(workspaceRootDeleteTag(1)).performClick()

        assertEquals(root, deleted)
    }

    @Test
    fun `back button fires onBack`() {
        var backCount = 0
        setContent(onBack = { backCount++ })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_BACK_TAG).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun `a blank host name renders the empty list state rather than crashing`() {
        setContent(state = WorkspaceRootsUiState(hostName = "", loaded = false))

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_EMPTY_TAG).assertIsDisplayed()
    }

    private fun setContent(
        state: WorkspaceRootsUiState = WorkspaceRootsUiState(hostName = "hetzner", loaded = true),
        onBack: () -> Unit = {},
        onAddRoot: (String, String) -> Unit = { _, _ -> },
        onDeleteRoot: (WorkspaceRootRow) -> Unit = {},
    ) {
        composeRule.setContent {
            WorkspaceRootsScreen(
                state = state,
                onBack = onBack,
                onAddRoot = onAddRoot,
                onDeleteRoot = onDeleteRoot,
            )
        }
    }
}
