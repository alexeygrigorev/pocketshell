package com.pocketshell.app.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
        assertTrue(
            "the default merged order must keep cloned rows before GitHub-only rows",
            compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName))
                .fetchSemanticsNode()
                .boundsInRoot.top < compose.onNodeWithTag(repoCardTestTag(uncloned.fullName))
                .fetchSemanticsNode()
                .boundsInRoot.top,
        )
        compose.onNodeWithText("Sort: Default").assertIsDisplayed()
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

    @Test
    fun controls_areAccessible_composable_andResettableWithoutReloadingRows() {
        val otherOwner = uncloned.copy(
            fullName = "other-owner/llm-zoomcamp",
            owner = "other-owner",
        )
        var state by mutableStateOf(
            RepoBrowserUiState.Ready(repos = listOf(clonedRow, uncloned, otherOwner)),
        )
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = {},
                    onSearchQueryChange = { value ->
                        state = state.copy(query = state.query.copy(search = value))
                    },
                    onOwnerFilterChange = { value ->
                        state = state.copy(query = state.query.copy(owner = value))
                    },
                    onSortOrderChange = { value ->
                        state = state.copy(query = state.query.copy(sortOrder = value))
                    },
                    onClearQuery = {
                        state = state.copy(query = RepoBrowserQuery())
                    },
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_SEARCH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_OWNER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_SORT_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Search repositories by owner or name",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Repository owner filter, selected All",
        ).assertIsDisplayed()

        // Search is local and immediately removes the non-matching row.
        compose.onNodeWithTag(REPO_BROWSER_SEARCH_TAG).performTextInput("pocketshell")
        compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName)).assertIsDisplayed()
        compose.onNodeWithTag(repoCardTestTag(uncloned.fullName)).assertDoesNotExist()
        assertEquals("pocketshell", state.query.search)

        // Owner options come from the loaded list and compose with search.
        compose.onNodeWithTag(REPO_BROWSER_OWNER_TAG).performClick()
        compose.onNodeWithTag(REPO_BROWSER_OWNER_OPTION_ALL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(repoBrowserOwnerOptionTestTag("other-owner")).assertIsDisplayed()
        compose.onNodeWithTag(repoBrowserOwnerOptionTestTag("other-owner")).performClick()
        assertEquals("other-owner", state.query.owner)
        compose.onNodeWithTag(REPO_BROWSER_NO_RESULTS_TAG).assertIsDisplayed()

        // Selecting All must clear the applied owner filter and restore the
        // row hidden by that filter, not merely expose an All menu item.
        compose.onNodeWithTag(REPO_BROWSER_OWNER_TAG).performClick()
        compose.onNodeWithTag(REPO_BROWSER_OWNER_OPTION_ALL_TAG).performClick()
        assertEquals(null, state.query.owner)
        compose.onNodeWithTag(REPO_BROWSER_NO_RESULTS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName)).assertIsDisplayed()

        // Sort state is also a visible, local selection.
        compose.onNodeWithTag(REPO_BROWSER_SORT_TAG).performClick()
        compose.onNodeWithTag(
            repoBrowserSortOptionTestTag(RepoBrowserSortOrder.LAST_CHANGED),
        ).performClick()
        assertEquals(RepoBrowserSortOrder.LAST_CHANGED, state.query.sortOrder)
        compose.onNodeWithText("Sort: Last changed").assertIsDisplayed()

        // Reset clears all three controls without changing the loaded rows.
        compose.onNodeWithTag(REPO_BROWSER_RESET_TAG).performClick()
        assertEquals(RepoBrowserQuery(), state.query)
        compose.onNodeWithTag(repoCardTestTag(clonedRow.fullName)).assertIsDisplayed()
        compose.onNodeWithTag(repoCardTestTag(uncloned.fullName)).assertIsDisplayed()
    }

    @Test
    fun selectingSortOrders_reordersRenderedCards() {
        val alpha = RepoRow(
            fullName = "sort-owner/alpha",
            name = "alpha",
            owner = "sort-owner",
            cloned = false,
            path = null,
            defaultBranch = "main",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val bravo = RepoRow(
            fullName = "sort-owner/bravo",
            name = "bravo",
            owner = "sort-owner",
            cloned = false,
            path = null,
            defaultBranch = "main",
            updatedAt = "2026-08-01T00:00:00Z",
        )
        val charlie = RepoRow(
            fullName = "sort-owner/charlie",
            name = "charlie",
            owner = "sort-owner",
            cloned = false,
            path = null,
            defaultBranch = "main",
            updatedAt = "2026-04-01T00:00:00Z",
        )
        var state by mutableStateOf(
            RepoBrowserUiState.Ready(repos = listOf(charlie, alpha, bravo)),
        )
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "host",
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = {},
                    onSortOrderChange = { value ->
                        state = state.copy(query = state.query.copy(sortOrder = value))
                    },
                )
            }
        }

        fun renderedOrder(): List<String> = listOf(alpha, bravo, charlie)
            .sortedBy { row ->
                compose.onNodeWithTag(repoCardTestTag(row.fullName))
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
            .map(RepoRow::fullName)

        compose.onNodeWithTag(REPO_BROWSER_SORT_TAG).performClick()
        compose.onNodeWithTag(
            repoBrowserSortOptionTestTag(RepoBrowserSortOrder.NAME_ASC),
        ).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(alpha.fullName, bravo.fullName, charlie.fullName),
            renderedOrder(),
        )

        compose.onNodeWithTag(REPO_BROWSER_SORT_TAG).performClick()
        compose.onNodeWithTag(
            repoBrowserSortOptionTestTag(RepoBrowserSortOrder.LAST_CHANGED),
        ).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(bravo.fullName, charlie.fullName, alpha.fullName),
            renderedOrder(),
        )
    }

    @Test
    fun emptyAndFilteredNoResultsStates_areDistinct_andFilteredStateCanBeCleared() {
        var state by mutableStateOf<RepoBrowserUiState>(
            RepoBrowserUiState.Ready(repos = emptyList()),
        )
        var cleared = false
        compose.setContent {
            PocketShellTheme {
                RepoBrowserScaffold(
                    hostName = "empty-host",
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onRepoClick = {},
                    onDismissError = {},
                    onClearQuery = { cleared = true },
                )
            }
        }

        compose.onNodeWithTag(REPO_BROWSER_SEARCH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_NO_RESULTS_TAG).assertDoesNotExist()

        state = RepoBrowserUiState.Ready(
            repos = listOf(uncloned),
            query = RepoBrowserQuery(search = "does-not-exist"),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(REPO_BROWSER_EMPTY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(REPO_BROWSER_NO_RESULTS_TAG).assertIsDisplayed()
        compose.onNodeWithText("No repositories match your search or owner filter")
            .assertIsDisplayed()
        compose.onNodeWithTag(REPO_BROWSER_CLEAR_FILTERS_TAG).performClick()
        assertEquals(true, cleared)
    }
}
