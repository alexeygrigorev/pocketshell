package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuietWorkspaceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `root appears once and its whole workspace row opens the canonical path`() {
        val path = "/home/alexey/git/pocketshell"
        val opened = mutableListOf<String>()
        setHostContent(
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
                                path = path,
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
            onOpenWorkspace = { opened += it },
        )

        composeRule.onAllNodesWithTag(workspaceRootTag("/home/alexey/git"))
            .assertCountEquals(1)
        composeRule.onNodeWithTag(workspaceRowTag(path)).assertIsDisplayed().performClick()
        assertEquals(listOf(path), opened)
        composeRule.onNodeWithText("~/git/pocketshell", substring = true).assertIsDisplayed()
    }

    @Test
    fun `workspace search matches canonical display paths`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                searchQuery = "mobile",
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/git/pocketshell",
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                    WorkspaceRootProjection(
                        key = "/home/alexey/work",
                        label = "Work",
                        displayPath = "~/work",
                        path = "/home/alexey/work",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/work/mobile",
                                label = "mobile",
                                displayPath = "~/work/mobile",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceRowTag("/home/alexey/work/mobile"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceRowTag("/home/alexey/git/pocketshell"))
            .assertDoesNotExist()
    }

    @Test
    fun `workspace search never returns root session rows`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                searchQuery = "root-shell",
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/git/pocketshell",
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = listOf(session("root-shell", "/home/alexey/git")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceSessionRowTag("root-shell"))
            .assertDoesNotExist()
        composeRule.onNodeWithText("No matching workspaces").assertIsDisplayed()
    }

    @Test
    fun `root sessions use In this root and do not create a child workspace`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = emptyList(),
                        rootSessions = listOf(session("root-shell", "/home/alexey/git")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText(HOST_WORKSPACES_IN_ROOT_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceSessionRowTag("root-shell")).assertIsDisplayed()
        composeRule.onNodeWithText("No workspaces yet").assertDoesNotExist()
    }

    @Test
    fun `an empty registered root stays visible`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/work",
                        label = "Work",
                        displayPath = "~/work",
                        path = "/home/alexey/work",
                        workspaces = emptyList(),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Work").assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_ROOT_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `offline host reports status unavailable`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                failure = "connection lost",
            ),
        )
        composeRule.onNodeWithTag(HOST_WORKSPACES_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No workspaces").assertDoesNotExist()
    }

    @Test
    fun `workspace screen opens a real session`() {
        val opened = mutableListOf<String>()
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                workspaceSessions = listOf(session("claude-main", "/home/alexey/git/pocketshell")),
            ),
            onOpenSession = { opened += it },
        )

        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed().performClick()
        assertEquals(listOf("claude-main"), opened)
    }

    @Test
    fun `populated workspace puts new session after the rows`() {
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                workspaceSessions = listOf(
                    session("claude-main", "/home/alexey/git/pocketshell"),
                ),
            ),
        )

        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(WORKSPACE_NEW_SESSION_TAG).assertCountEquals(1)
    }

    @Test
    fun `empty workspace offers new session`() {
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/empty",
                loaded = true,
            ),
        )
        composeRule.onNodeWithText("No sessions").assertIsDisplayed()
        composeRule.onAllNodesWithTag(WORKSPACE_NEW_SESSION_TAG).assertCountEquals(1)
    }

    private fun setHostContent(
        state: HostWorkspacesUiState,
        onOpenWorkspace: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = state,
                    onRefresh = {},
                    onOpenWorkspace = onOpenWorkspace,
                    onOpenSession = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setWorkspaceContent(
        state: SessionTreeUiState,
        onOpenSession: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                WorkspaceScreen(
                    state = state,
                    onRefresh = {},
                    onOpenSession = onOpenSession,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun session(name: String, workspace: String): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = 1L,
        activityEpoch = null,
    )
}
