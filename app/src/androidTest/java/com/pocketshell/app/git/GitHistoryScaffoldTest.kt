package com.pocketshell.app.git

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        createState: CreateIssueUiState = CreateIssueUiState.Idle,
        onSubmitNewIssue: (String, String) -> Unit = { _, _ -> },
        onDismissCreateIssue: () -> Unit = {},
        onOpenIssueUrl: (String) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                GitHistoryScaffold(
                    hostName = "agents",
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                    createState = createState,
                    onOpenGitHub = onOpenGitHub,
                    onSubmitNewIssue = onSubmitNewIssue,
                    onDismissCreateIssue = onDismissCreateIssue,
                    onOpenIssueUrl = onOpenIssueUrl,
                )
            }
        }
    }

    private fun configuredReady(
        issues: List<GitHubIssue>? = emptyList(),
        ghHint: String? = null,
    ) = GitHistoryUiState.Ready(
        dir = "/home/u/git/proj",
        commits = listOf(commit("a1b2c3d", "Add timeline view")),
        truncated = false,
        overview = overview(),
        issues = issues,
        ghHint = ghHint,
    )

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
    fun issuesTabRendersConfiguredIssueList() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                issues = listOf(
                    GitHubIssue(
                        number = 649,
                        title = "view GitHub issues in-app",
                        state = GitHubIssueState.Open,
                        labels = listOf("enhancement"),
                        updatedAt = "2026-06-09T10:11:12Z",
                    ),
                    GitHubIssue(
                        number = 648,
                        title = "Open on GitHub action",
                        state = GitHubIssueState.Closed,
                        labels = emptyList(),
                        updatedAt = "2026-06-08T08:00:00Z",
                    ),
                ),
                ghHint = null,
            ),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_ISSUE_ROW_TAG_PREFIX + "649").assertIsDisplayed()
        compose.onNodeWithText("view GitHub issues in-app").assertIsDisplayed()
        compose.onNodeWithTag(GIT_ISSUE_ROW_TAG_PREFIX + "648").assertIsDisplayed()
    }

    @Test
    fun issuesTabShowsConfigureGhHintWhenNotConfigured() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                issues = null,
                ghHint = "install gh (https://cli.github.com) and run `gh auth login`",
            ),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_ISSUES_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithText("install gh (https://cli.github.com) and run `gh auth login`")
            .assertIsDisplayed()
    }

    @Test
    fun issuesTabShowsEmptyStateWhenConfiguredAndNoIssues() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                issues = emptyList(),
                ghHint = null,
            ),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_ISSUES_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun issuesTabShowsUnavailableWhenListingFailedDespiteConfigured() {
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                issues = null,
                ghHint = null,
            ),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_ISSUES_UNAVAILABLE_TAG).assertIsDisplayed()
    }

    // ---- create-issue form (issue #650) ------------------------------------

    @Test
    fun newIssueAffordanceShownWhenGhConfigured() {
        setState(configuredReady(issues = emptyList()))
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).assertIsDisplayed()
    }

    @Test
    fun newIssueAffordanceHiddenWhenGhNotConfigured() {
        setState(
            configuredReady(
                issues = null,
                ghHint = "install gh (https://cli.github.com) and run `gh auth login`",
            ),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        // Gated: no "New issue" row, only the configure-gh hint.
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GIT_ISSUES_HINT_TAG).assertIsDisplayed()
    }

    @Test
    fun newIssueOpensFormAndSubmitsTrimmedTitleAndBody() {
        var submitted: Pair<String, String>? = null
        setState(
            configuredReady(issues = emptyList()),
            onSubmitNewIssue = { t, b -> submitted = t to b },
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).performClick()
        compose.onNodeWithTag(GIT_CREATE_ISSUE_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GIT_CREATE_ISSUE_TITLE_TAG).performTextInput("  My title  ")
        compose.onNodeWithTag(GIT_CREATE_ISSUE_BODY_TAG).performTextInput("  body text  ")
        compose.onNodeWithTag(GIT_CREATE_ISSUE_SUBMIT_TAG).performClick()
        assertEquals("My title" to "body text", submitted)
    }

    @Test
    fun submitDisabledWhileTitleBlank() {
        setState(configuredReady(issues = emptyList()))
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).performClick()
        // No title typed yet → confirm is disabled.
        compose.onNodeWithTag(GIT_CREATE_ISSUE_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun successStateShowsUrlAndOpensIt() {
        var opened: String? = null
        setState(
            configuredReady(issues = emptyList()),
            createState = CreateIssueUiState.Success(
                "https://github.com/owner/repo/issues/701",
            ),
            onOpenIssueUrl = { opened = it },
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).performClick()
        compose.onNodeWithTag(GIT_CREATE_ISSUE_SUCCESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("github.com/owner/repo/issues/701").assertIsDisplayed()
        compose.onNodeWithTag(GIT_CREATE_ISSUE_OPEN_TAG).performClick()
        assertEquals("https://github.com/owner/repo/issues/701", opened)
    }

    @Test
    fun failureStateShowsErrorAboveForm() {
        setState(
            configuredReady(issues = emptyList()),
            createState = CreateIssueUiState.Failure("Could not resolve to a Repository"),
        )
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).performClick()
        compose.onNodeWithTag(GIT_CREATE_ISSUE_ERROR_TAG).assertIsDisplayed()
        // The form is still present so the user can fix and retry.
        compose.onNodeWithTag(GIT_CREATE_ISSUE_TITLE_TAG).assertIsDisplayed()
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
