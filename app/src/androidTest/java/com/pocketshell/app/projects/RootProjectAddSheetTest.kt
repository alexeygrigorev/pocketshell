package com.pocketshell.app.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RootProjectAddSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contentFiresQuickActionsAndStartSession() {
        val root = FolderTreeRoot(
            path = "/home/alexey/git",
            label = "git",
            folders = emptyList(),
            isWatched = true,
        )
        val candidates = listOf(
            RootProjectCandidate(
                path = "/home/alexey/git/llm-zoomcamp",
                label = "llm-zoomcamp",
                source = RootProjectSource.History,
            ),
            RootProjectCandidate(
                path = "/home/alexey/git/alpha",
                label = "alpha",
                source = RootProjectSource.Scanned,
            ),
        )
        val events = mutableListOf<String>()

        compose.setContent {
            PocketShellTheme {
                RootProjectAddSheetContent(
                    root = root,
                    candidates = candidates,
                    onStartSession = { events += "start:${it.path}" },
                    onCreateEmptyProject = { events += "empty" },
                    onCloneGitProject = { events += "clone" },
                )
            }
        }

        compose.onNodeWithTag(ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG).performClick()
        compose.onNodeWithTag(ROOT_PROJECT_ADD_CLONE_TAG).performClick()
        compose.onNodeWithTag(
            rootProjectCandidateSourceTestTag("/home/alexey/git/llm-zoomcamp"),
            useUnmergedTree = true,
        ).assertTextEquals("Recent")
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/alexey/git/llm-zoomcamp"))
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            listOf("empty", "clone", "start:/home/alexey/git/llm-zoomcamp"),
            events,
        )
    }

    @Test
    fun searchFieldFuzzyFiltersCandidatesInSheet() {
        val root = FolderTreeRoot(
            path = "/home/alexey/git",
            label = "git",
            folders = emptyList(),
            isWatched = true,
        )
        val candidates = listOf(
            RootProjectCandidate(
                path = "/home/alexey/git/pocketshell",
                label = "pocketshell",
                source = RootProjectSource.History,
            ),
            RootProjectCandidate(
                path = "/home/alexey/git/llm-zoomcamp",
                label = "llm-zoomcamp",
                source = RootProjectSource.Scanned,
            ),
            RootProjectCandidate(
                path = "/home/alexey/git/data-engineering",
                label = "data-engineering",
                source = RootProjectSource.Scanned,
            ),
        )

        compose.setContent {
            PocketShellTheme {
                RootProjectAddSheetContent(
                    root = root,
                    candidates = candidates,
                    onStartSession = {},
                    onCreateEmptyProject = {},
                    onCloneGitProject = {},
                )
            }
        }

        // All candidates visible before filtering.
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/alexey/git/pocketshell")).assertExists()
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/alexey/git/llm-zoomcamp")).assertExists()

        // Typing an abbreviation fuzzy-matches pocketshell only (subsequence p-s-h).
        compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG).performTextInput("psh")

        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/alexey/git/pocketshell")).assertIsDisplayed()
        assertTrue(
            "expected non-matching candidate to be filtered out",
            compose.onAllNodesWithTag(rootProjectCandidateTestTag("/home/alexey/git/llm-zoomcamp"))
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "expected non-matching candidate to be filtered out",
            compose.onAllNodesWithTag(rootProjectCandidateTestTag("/home/alexey/git/data-engineering"))
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
