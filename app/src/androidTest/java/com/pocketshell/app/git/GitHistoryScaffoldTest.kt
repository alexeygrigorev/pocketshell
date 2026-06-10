package com.pocketshell.app.git

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stateless UI tests for the Git history scaffold (issue #646) — drive every
 * render state without an SSH session.
 */
@RunWith(AndroidJUnit4::class)
class GitHistoryScaffoldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun commit(hash: String, subject: String) =
        GitCommit(hash, "Ada Lovelace", "2 hours ago", subject)

    private fun setState(
        state: GitHistoryUiState,
        onRetry: () -> Unit = {},
        onOpenGitHub: (String) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                GitHistoryScaffold(
                    hostName = "agents",
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                    onOpenGitHub = onOpenGitHub,
                )
            }
        }
    }

    @Test
    fun loadingStateShowsSpinner() {
        setState(GitHistoryUiState.Loading("/home/u/git/proj"))
        compose.onNodeWithTag(GIT_HISTORY_LOADING_TAG).assertIsDisplayed()
    }

    private fun overview() = GitRepoOverview(
        status = GitRepoStatus(
            currentBranch = "main",
            upstream = "origin/main",
            ahead = 1,
            behind = 0,
            dirty = true,
            changedFiles = 2,
            lastCommit = "a1b2c3d Add timeline view",
            hasNoCommits = false,
        ),
        branches = listOf(
            GitBranch("main", current = true, upstream = "origin/main", subject = "Add timeline"),
            GitBranch("feature/x", current = false, upstream = null, subject = "WIP"),
        ),
        worktrees = listOf(
            GitWorktree("/home/u/git/proj", branch = "main", head = "a1b2c3d", bare = false, detached = false),
            GitWorktree("/home/u/git/proj-feature", branch = "feature/x", head = "9f8e7d6", bare = false, detached = false),
        ),
    )

    @Test
    fun readyStateListsCommits() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(
                    commit("a1b2c3d", "Add timeline view"),
                    commit("9f8e7d6", "Fix parser edge case"),
                ),
                truncated = false,
                overview = overview(),
            ),
        )
        // Default tab is Overview — switch to History first.
        compose.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + "a1b2c3d").assertIsDisplayed()
        compose.onNodeWithText("Add timeline view").assertIsDisplayed()
        compose.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + "9f8e7d6").assertIsDisplayed()
    }

    @Test
    fun overviewTabShowsStatusBranchesAndWorktrees() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
            ),
        )
        // Overview is the default landing tab.
        compose.onNodeWithTag(GIT_OVERVIEW_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GIT_BRANCH_ROW_TAG_PREFIX + "main").assertIsDisplayed()
        compose.onNodeWithTag(GIT_BRANCH_ROW_TAG_PREFIX + "feature/x").assertIsDisplayed()
        compose.onNodeWithTag(GIT_WORKTREE_ROW_TAG_PREFIX + "/home/u/git/proj").assertIsDisplayed()
        compose.onNodeWithText("Dirty").assertIsDisplayed()
    }

    @Test
    fun openOnGitHubShownAndFiresUrlWhenGitHubRepo() {
        var opened: String? = null
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                gitHubUrl = "https://github.com/owner/repo",
            ),
            onOpenGitHub = { opened = it },
        )
        // Overview is the default landing tab; the action sits at the top.
        compose.onNodeWithTag(GIT_OPEN_ON_GITHUB_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GIT_OPEN_ON_GITHUB_TAG).performClick()
        assertEquals("https://github.com/owner/repo", opened)
    }

    @Test
    fun openOnGitHubHiddenWhenNotGitHubRepo() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                gitHubUrl = null,
            ),
        )
        compose.onNodeWithTag(GIT_OPEN_ON_GITHUB_TAG).assertDoesNotExist()
    }

    @Test
    fun overviewUnavailableWhenNullOverview() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = null,
            ),
        )
        compose.onNodeWithTag(GIT_OVERVIEW_STATUS_UNAVAILABLE_TAG).assertIsDisplayed()
    }

    @Test
    fun emptyRepoShowsEmptyState() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/fresh",
                commits = emptyList(),
                truncated = false,
            ),
        )
        compose.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_HISTORY_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun truncatedListingShowsNote() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "one")),
                truncated = true,
            ),
        )
        compose.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_HISTORY_TRUNCATED_TAG).assertIsDisplayed()
    }

    @Test
    fun notARepoShowsGuidance() {
        setState(GitHistoryUiState.NotARepo("/home/u/notrepo"))
        compose.onNodeWithTag(GIT_HISTORY_NOT_A_REPO_TAG).assertIsDisplayed()
        compose.onNodeWithText("Not a git repository").assertIsDisplayed()
    }

    @Test
    fun failedStateShowsMessageAndRetry() {
        var retries = 0
        setState(
            GitHistoryUiState.Failed(
                dir = "/home/u/git/proj",
                message = "Couldn't read git history: connection reset",
            ),
            onRetry = { retries++ },
        )
        compose.onNodeWithTag(GIT_HISTORY_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Couldn't read git history: connection reset").assertIsDisplayed()
        compose.onNodeWithTag(GIT_HISTORY_RETRY_TAG).performClick()
        assertEquals(1, retries)
    }
}
