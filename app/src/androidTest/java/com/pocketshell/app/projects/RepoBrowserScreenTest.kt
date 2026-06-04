package com.pocketshell.app.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Compose UI test for the repos-browse screen — issue #230.
 *
 * Drives the stateless [RepoBrowserScaffold] across every state so the
 * list + clone-on-tap affordances are validated without an SSH session:
 *
 *  - Cloned repos render an "Open" pill; GitHub-only repos render a
 *    "Clone" pill.
 *  - Tapping a row dispatches the row to the click callback (the view
 *    model then runs clone/open and the navigator opens the session).
 *  - A pending action shows the per-row progress spinner and blocks
 *    further taps.
 *  - A clone/open failure surfaces a dismissible banner.
 */
@RunWith(AndroidJUnit4::class)
class RepoBrowserScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val clonedRow = RepoRow(
        fullName = "alexeygrigorev/pocketshell",
        name = "pocketshell",
        owner = "alexeygrigorev",
        cloned = true,
        path = "/home/alexey/git/pocketshell",
        defaultBranch = "main",
        updatedAt = "2026-05-28T00:00:00Z",
    )

    private val uncloned = RepoRow(
        fullName = "alexeygrigorev/llm-zoomcamp",
        name = "llm-zoomcamp",
        owner = "alexeygrigorev",
        cloned = false,
        path = null,
        defaultBranch = "main",
        updatedAt = "2026-05-27T00:00:00Z",
    )

    @Test
    fun readyState_rendersOpenAndClonePillsAndDispatchesTap() {
        var tapped: RepoRow? = null
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "issue230-host",
                    state = RepoBrowserUiState.Ready(repos = listOf(clonedRow, uncloned)),
                    onBack = {},
                    onRetry = {},
                    onRepoClick = { tapped = it },
                    onDismissError = {},
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithText("issue230-host").assertIsDisplayed()

        // Cloned repo → "Open"; GitHub-only repo → "Clone".
        compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName)).assertIsDisplayed()
        compose.onNodeWithTag(repoCardTestTag(uncloned.fullName)).assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Clone").assertIsDisplayed()

        // Tapping the GitHub-only row dispatches it to the clone handler.
        compose.onNodeWithTag(repoCardTestTag(uncloned.fullName)).performClick()
        assertEquals(uncloned.fullName, tapped?.fullName)
    }

    @Test
    fun pendingState_showsRowSpinnerAndBlocksFurtherTaps() {
        var tapCount = 0
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = RepoBrowserUiState.Ready(
                        repos = listOf(clonedRow, uncloned),
                        pendingFullName = uncloned.fullName,
                    ),
                    onBack = {},
                    onRetry = {},
                    onRepoClick = { tapCount++ },
                    onDismissError = {},
                )
            }
        }

        // The pending row shows its progress spinner.
        compose.onNodeWithTag(repoCardPendingTestTag(uncloned.fullName)).assertIsDisplayed()

        // Taps are blocked while an action is in flight.
        compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName)).performClick()
        assertEquals(0, tapCount)
    }

    @Test
    fun actionError_showsDismissibleBanner() {
        var dismissed = false
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = RepoBrowserUiState.Ready(
                        repos = listOf(uncloned),
                        actionError = "Couldn't clone llm-zoomcamp: clone_failed",
                    ),
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_ACTION_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Couldn't clone llm-zoomcamp: clone_failed").assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_ACTION_ERROR_DISMISS_TAG).performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun toolUnavailable_rendersHint() {
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "no-tool-host",
                    state = RepoBrowserUiState.ToolUnavailable,
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = {},
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("pocketshell is not installed on no-tool-host.").assertIsDisplayed()
    }

    @Test
    fun failedState_retryButtonInvokesCallback() {
        var retried = false
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = RepoBrowserUiState.Failed("boom"),
                    onBack = {},
                    onRetry = { retried = true },
                    onRepoClick = {},
                    onDismissError = {},
                )
            }
        }

        compose.onNodeWithText("boom").assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_RETRY_TAG).performClick()
        assertEquals(true, retried)
    }

    @Test
    fun loadingState_showsSpinner() {
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = RepoBrowserUiState.Loading,
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = {},
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_LOADING_TAG).assertIsDisplayed()
    }
}
