package com.pocketshell.app.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
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
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
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
        ).assertTextEquals("Used before")
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/alexey/git/llm-zoomcamp"))
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            listOf("empty", "clone", "start:/home/alexey/git/llm-zoomcamp"),
            events,
        )
    }
}
