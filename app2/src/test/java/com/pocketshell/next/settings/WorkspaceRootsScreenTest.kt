package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [WorkspaceRootsScreen] and [AddWorkspaceRootScreen] as stateless composables. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h4000dp")
class WorkspaceRootsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `project roots page shows the empty state and primary add action`() {
        setListContent(state = WorkspaceRootsUiState(hostName = "hetzner", loaded = true))

        composeRule.onNodeWithText("Project roots").assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_PROJECT_ROOT_TAG).assertIsDisplayed()
    }

    @Test
    fun `the project roots add action opens the focused form`() {
        var opened = 0
        setListContent(onOpenAddRoot = { opened++ })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_PROJECT_ROOT_TAG).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the focused form keeps the primary action disabled until a path is typed`() {
        setFormContent()

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).assertIsNotEnabled()
    }

    @Test
    fun `typing a path and tapping add reports both fields`() {
        var added: Pair<String, String>? = null
        setFormContent(onAddRoot = { label, path -> added = label to path })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_LABEL_FIELD_TAG).performTextInput("Pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_PATH_FIELD_TAG)
            .performTextInput("/home/alexey/git/pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).performClick()

        assertEquals("Pocketshell" to "/home/alexey/git/pocketshell", added)
    }

    @Test
    fun `a missing root exposes the explicit create action`() {
        var created: Pair<String, String>? = null
        setFormContent(
            state = WorkspaceRootsUiState(
                hostName = "hetzner",
                loaded = true,
                failure = "That folder does not exist. You can create it on the host.",
                canCreate = true,
                createPath = "/home/alexey/new-root",
            ),
            onCreateRoot = { label, path -> created = label to path },
        )

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_PATH_FIELD_TAG)
            .performTextInput("/home/alexey/new-root")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_CREATE_TAG).performClick()

        assertEquals("" to "/home/alexey/new-root", created)
    }

    @Test
    fun `an existing root opens its action sheet and removes only after confirmation`() {
        var deleted: WorkspaceRootRow? = null
        val root = WorkspaceRootRow(id = 1, label = "Pocketshell", path = "/home/alexey/git/pocketshell")
        setListContent(
            state = WorkspaceRootsUiState(hostName = "hetzner", roots = listOf(root), loaded = true),
            onDeleteRoot = { deleted = it },
        )

        composeRule.onNodeWithTag(workspaceRootRowTag(1)).assertIsDisplayed()
        composeRule.onNodeWithText("~/git/pocketshell").assertIsDisplayed()
        composeRule.onNodeWithText("0 workspaces").assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceRootRowTag(1)).performClick()
        composeRule.onNodeWithText("Remove root").performClick()
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_REMOVE_CONFIRM_TAG).performClick()

        assertEquals(root, deleted)
    }

    @Test
    fun `back button fires on the list`() {
        var backCount = 0
        setListContent(onBack = { backCount++ })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_BACK_TAG).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun `back button fires on the focused form`() {
        var backCount = 0
        setFormContent(onBack = { backCount++ })

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_BACK_TAG).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun `a blank host name still renders the list empty state`() {
        setListContent(state = WorkspaceRootsUiState(hostName = "", loaded = false))

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_EMPTY_TAG).assertIsDisplayed()
    }

    private fun setListContent(
        state: WorkspaceRootsUiState = WorkspaceRootsUiState(hostName = "hetzner", loaded = true),
        onBack: () -> Unit = {},
        onOpenAddRoot: () -> Unit = {},
        onDeleteRoot: (WorkspaceRootRow) -> Unit = {},
    ) {
        composeRule.setContent {
            WorkspaceRootsScreen(
                state = state,
                onBack = onBack,
                onDeleteRoot = onDeleteRoot,
                onOpenAddRoot = onOpenAddRoot,
            )
        }
    }

    private fun setFormContent(
        state: WorkspaceRootsUiState = WorkspaceRootsUiState(hostName = "hetzner", loaded = true),
        onBack: () -> Unit = {},
        onAddRoot: (String, String) -> Unit = { _, _ -> },
        onCreateRoot: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            AddWorkspaceRootScreen(
                state = state,
                onBack = onBack,
                onAddRoot = onAddRoot,
                onCreateRoot = onCreateRoot,
            )
        }
    }
}
