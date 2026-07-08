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
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
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
        diffState: GitDiffUiState = GitDiffUiState.Hidden,
        onSubmitNewIssue: (String, String) -> Unit = { _, _ -> },
        onDismissCreateIssue: () -> Unit = {},
        onOpenIssueUrl: (String) -> Unit = {},
        onCommitSelected: (String, String) -> Unit = { _, _ -> },
        onDismissDiff: () -> Unit = {},
        onOpenCommitOnGitHub: (String) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                GitHistoryScaffold(
                    hostName = "agents",
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                    createState = createState,
                    diffState = diffState,
                    onOpenGitHub = onOpenGitHub,
                    onSubmitNewIssue = onSubmitNewIssue,
                    onDismissCreateIssue = onDismissCreateIssue,
                    onOpenIssueUrl = onOpenIssueUrl,
                    onCommitSelected = onCommitSelected,
                    onDismissDiff = onDismissDiff,
                    onOpenCommitOnGitHub = onOpenCommitOnGitHub,
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
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_LOADING_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_ROW_TAG_PREFIX + "a1b2c3d")
        compose.onNodeWithText("Add timeline view").assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_ROW_TAG_PREFIX + "9f8e7d6")
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
        compose.assertNodeFullyWithinRoot(GIT_OVERVIEW_STATUS_TAG)
        compose.assertNodeFullyWithinRoot(GIT_BRANCH_ROW_TAG_PREFIX + "main")
        compose.assertNodeFullyWithinRoot(GIT_BRANCH_ROW_TAG_PREFIX + "feature/x")
        compose.assertNodeFullyWithinRoot(GIT_WORKTREE_ROW_TAG_PREFIX + "/home/u/git/proj")
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
        compose.assertNodeFullyWithinRoot(GIT_OPEN_ON_GITHUB_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_OVERVIEW_STATUS_UNAVAILABLE_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_EMPTY_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_TRUNCATED_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_ISSUE_ROW_TAG_PREFIX + "649")
        compose.onNodeWithText("view GitHub issues in-app").assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(GIT_ISSUE_ROW_TAG_PREFIX + "648")
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
        compose.assertNodeFullyWithinRoot(GIT_ISSUES_HINT_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_ISSUES_EMPTY_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_ISSUES_UNAVAILABLE_TAG)
    }

    // ---- create-issue form (issue #650) ------------------------------------
    //
    // The create-issue form renders inside a Material3 [ModalBottomSheet], which
    // composes into a SEPARATE window/root. `assertNodeFullyWithinRoot` reads a
    // single `onRoot()` (the activity root) to bound the node, so it cannot
    // correctly contain nodes that live in the sheet's own window. For the
    // sheet-internal controls (sheet container, title/body fields, error/success
    // surfaces, open-URL action) we therefore keep `assertIsDisplayed()`; the
    // reachability-as-containment migration (issue #856) applies to the main
    // scaffold nodes (GIT_NEW_ISSUE_TAG above, etc.), not the modal-window ones.

    @Test
    fun newIssueAffordanceShownWhenGhConfigured() {
        setState(configuredReady(issues = emptyList()))
        compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        compose.assertNodeFullyWithinRoot(GIT_NEW_ISSUE_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_ISSUES_HINT_TAG)
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

    // ---- unified diff viewer (issue #1242) ---------------------------------

    private fun sampleDiff(
        ref: String = "a1b2c3d",
        truncated: Boolean = false,
        longLine: Boolean = false,
    ) = GitCommitDiff(
        ref = ref,
        lines = listOf(
            DiffLine("", "commit ${ref}0000", DiffLineKind.CommitMeta),
            DiffLine("", "diff --git a/Foo.kt b/Foo.kt", DiffLineKind.FileHeader),
            DiffLine("", "@@ -1,3 +1,4 @@", DiffLineKind.HunkHeader),
            DiffLine(" ", "fun foo() {", DiffLineKind.Context),
            DiffLine("-", "    val old = 1", DiffLineKind.Removed),
            DiffLine(
                "+",
                if (longLine) "    val new = ${"x".repeat(400)}" else "    val new = 2",
                DiffLineKind.Added,
            ),
            DiffLine(" ", "}", DiffLineKind.Context),
        ),
        truncated = truncated,
    )

    @Test
    fun tappingCommitInvokesOnCommitSelected() {
        var selected: Pair<String, String>? = null
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
            ),
            onCommitSelected = { hash, subject -> selected = hash to subject },
        )
        compose.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        compose.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + "a1b2c3d").performClick()
        assertEquals("a1b2c3d" to "Add timeline view", selected)
    }

    @Test
    fun diffReadyRendersGuttersAndContent() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Add timeline view", sampleDiff()),
        )
        compose.assertNodeFullyWithinRoot(GIT_DIFF_VIEW_TAG)
        compose.assertNodeFullyWithinRoot(GIT_DIFF_CONTENT_TAG)
        // The added/removed code and the subject header are visible.
        compose.onNodeWithText("Add timeline view").assertIsDisplayed()
        compose.onNodeWithText("    val new = 2").assertIsDisplayed()
        compose.onNodeWithText("    val old = 1").assertIsDisplayed()
    }

    @Test
    fun diffLongLineRendersWithoutWrapping() {
        // A very long added line must render (single-line, horizontally
        // scrollable) — the full-device scroll proof is the emulator screenshot.
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Long line", sampleDiff(longLine = true)),
        )
        compose.assertNodeFullyWithinRoot(GIT_DIFF_CONTENT_TAG)
        compose.onNodeWithTag(GIT_DIFF_LINE_TAG_PREFIX + "5").assertExists()
    }

    @Test
    fun diffTruncatedShowsMarker() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Big commit", sampleDiff(truncated = true)),
        )
        compose.assertNodeFullyWithinRoot(GIT_DIFF_TRUNCATED_TAG)
    }

    @Test
    fun diffNotTruncatedHidesMarker() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Small commit", sampleDiff(truncated = false)),
        )
        compose.onNodeWithTag(GIT_DIFF_TRUNCATED_TAG).assertDoesNotExist()
    }

    @Test
    fun diffLoadingShowsSpinner() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Loading("a1b2c3d", "Add timeline view"),
        )
        compose.assertNodeFullyWithinRoot(GIT_DIFF_LOADING_TAG)
    }

    @Test
    fun diffFailedShowsError() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Failed("a1b2c3d", "gone", "Unknown commit: a1b2c3d"),
        )
        compose.assertNodeFullyWithinRoot(GIT_DIFF_ERROR_TAG)
        compose.onNodeWithText("Unknown commit: a1b2c3d").assertIsDisplayed()
    }

    @Test
    fun diffBackDismisses() {
        var dismissed = 0
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Add timeline view", sampleDiff()),
            onDismissDiff = { dismissed++ },
        )
        compose.onNodeWithTag(GIT_DIFF_BACK_TAG).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun diffOpenOnGitHubFiresCommitUrlWhenRepoIsGitHub() {
        var opened: String? = null
        setState(
            GitHistoryUiState.Ready(
                dir = "/home/u/git/proj",
                commits = listOf(commit("a1b2c3d", "Add timeline view")),
                truncated = false,
                overview = overview(),
                gitHubUrl = "https://github.com/owner/repo",
            ),
            diffState = GitDiffUiState.Ready("Add timeline view", sampleDiff(ref = "a1b2c3d")),
            onOpenCommitOnGitHub = { opened = it },
        )
        compose.onNodeWithTag(GIT_DIFF_OPEN_ON_GITHUB_TAG).performClick()
        assertEquals("https://github.com/owner/repo/commit/a1b2c3d", opened)
    }

    @Test
    fun diffOpenOnGitHubHiddenWhenRepoNotGitHub() {
        setState(
            configuredReady(),
            diffState = GitDiffUiState.Ready("Add timeline view", sampleDiff()),
        )
        compose.onNodeWithTag(GIT_DIFF_OPEN_ON_GITHUB_TAG).assertDoesNotExist()
    }

    @Test
    fun notARepoShowsGuidance() {
        setState(GitHistoryUiState.NotARepo("/home/u/notrepo"))
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_NOT_A_REPO_TAG)
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
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_ERROR_TAG)
        compose.onNodeWithText("Couldn't read git history: connection reset").assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(GIT_HISTORY_RETRY_TAG)
        compose.onNodeWithTag(GIT_HISTORY_RETRY_TAG).performClick()
        assertEquals(1, retries)
    }
}
