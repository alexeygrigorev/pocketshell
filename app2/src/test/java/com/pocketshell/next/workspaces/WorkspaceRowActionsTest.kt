package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2635 N1 (maintainer-approved route change): the deleted workspace
 * page's per-workspace utilities live on the row's LONG-PRESS.
 *
 * `docs/design-language.md` has always said "long-press = always available
 * alternate action"; before this it was a rule with almost no consumers,
 * because the actions had a page of their own. These tests are the class
 * coverage for "the page went away and nothing it owned went with it".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceRowActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Fails on the pre-N1 row, which had no long-press at all: the actions
     * were only reachable by opening the page a tap now bypasses.
     */
    @Test
    fun `long-pressing a workspace row opens its actions`() {
        setContent()

        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH)).performTouchInput { longClick() }

        composeRule.onNodeWithTag(WORKSPACE_ROW_ACTIONS_TAG).assertIsDisplayed()
        // Every row the deleted page carried, and no others.
        composeRule.onNodeWithTag(WORKSPACE_ROW_NEW_SESSION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROW_BROWSE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROW_COPY_PATH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROW_REORDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORKSPACE_ROW_REMOVE_TAG).assertIsDisplayed()
    }

    /** A plain tap must still open the terminal — the long-press is additive. */
    @Test
    fun `a plain tap still opens the workspace, not its actions`() {
        val opened = mutableListOf<String>()
        setContent(onOpenWorkspace = { opened += it })

        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH)).performClick()

        assertEquals(listOf(BUSY_PATH), opened)
        composeRule.onNodeWithTag(WORKSPACE_ROW_ACTIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun `new session from the row actions starts one in that workspace`() {
        val started = mutableListOf<String>()
        setContent(onStartSessionAtPath = { started += it })

        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(WORKSPACE_ROW_NEW_SESSION_TAG).performClick()

        assertEquals(listOf(BUSY_PATH), started)
    }

    /**
     * "Remove from list" is VISIBILITY only (`spec/DesignSystem.md` § State
     * vocabulary), so it confirms first and says what it does not do.
     */
    @Test
    fun `remove from list confirms and names what survives`() {
        val removed = mutableListOf<String>()
        setContent(onRemoveWorkspaceFromList = { removed += it })

        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(WORKSPACE_ROW_REMOVE_TAG).performClick()

        assertEquals("removing must confirm first", emptyList<String>(), removed)
        composeRule.onNodeWithText("Remove from list?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "\"pocketshell\" stops showing on this host. The folder and any " +
                "running sessions are untouched.",
        ).assertIsDisplayed()

        composeRule.onNodeWithTag(WORKSPACE_ROW_REMOVE_CONFIRM_TAG).performClick()
        assertEquals(listOf(BUSY_PATH), removed)
    }

    /** Reorder is also on the HOST kebab — it is a host-list operation. */
    @Test
    fun `the host kebab carries reorder workspaces`() {
        var reorders = 0
        setContent(onOpenReorder = { reorders += 1 })

        composeRule.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOST_WORKSPACES_REORDER_TAG).performClick()

        assertEquals(1, reorders)
    }

    private fun setContent(
        onOpenWorkspace: (String) -> Unit = {},
        onStartSessionAtPath: (String) -> Unit = {},
        onRemoveWorkspaceFromList: (String) -> Unit = {},
        onOpenReorder: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = HostWorkspacesUiState(
                        hostId = 7,
                        hostLabel = "hetzner",
                        loaded = true,
                        roots = listOf(
                            WorkspaceRootProjection(
                                key = "/home/alexey/git",
                                label = "Git",
                                displayPath = "~/git",
                                path = "/home/alexey/git",
                                workspaces = listOf(
                                    WorkspaceProjection(
                                        path = BUSY_PATH,
                                        label = "pocketshell",
                                        displayPath = "~/git/pocketshell",
                                        sessions = listOf(session("main")),
                                        durable = true,
                                    ),
                                ),
                                rootSessions = emptyList(),
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onOpenWorkspace = onOpenWorkspace,
                    onStartSessionAtPath = onStartSessionAtPath,
                    onRemoveWorkspaceFromList = onRemoveWorkspaceFromList,
                    onOpenReorder = onOpenReorder,
                    onOpenSession = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun session(name: String): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = BUSY_PATH,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = null,
        activityEpoch = null,
    )

    private companion object {
        const val BUSY_PATH = "/home/alexey/git/pocketshell"
    }
}
