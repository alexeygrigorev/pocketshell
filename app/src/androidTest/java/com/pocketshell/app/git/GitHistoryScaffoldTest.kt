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

    private fun setState(state: GitHistoryUiState, onRetry: () -> Unit = {}) {
        compose.setContent {
            PocketShellTheme {
                GitHistoryScaffold(
                    hostName = "agents",
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                )
            }
        }
    }

    @Test
    fun loadingStateShowsSpinner() {
        setState(GitHistoryUiState.Loading("/home/u/git/proj"))
        compose.onNodeWithTag(GIT_HISTORY_LOADING_TAG).assertIsDisplayed()
    }

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
            ),
        )
        compose.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + "a1b2c3d").assertIsDisplayed()
        compose.onNodeWithText("Add timeline view").assertIsDisplayed()
        compose.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + "9f8e7d6").assertIsDisplayed()
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
