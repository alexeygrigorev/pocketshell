package com.pocketshell.app.fileexplorer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stateless UI tests for the file explorer scaffold (issue #528) — drive every
 * render state and tap interaction without an SSH session.
 */
@RunWith(AndroidJUnit4::class)
class FileExplorerScaffoldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun dir(name: String) = RemoteEntry(name, RemoteEntry.Type.DIRECTORY, 0L, null)
    private fun file(name: String, size: Long) =
        RemoteEntry(name, RemoteEntry.Type.FILE, size, null)

    private fun setReady(
        entries: List<RemoteEntry>,
        path: String = "/home/u/proj",
        truncated: Boolean = false,
        onOpenDirectory: (RemoteEntry) -> Unit = {},
        onOpenFile: (RemoteEntry) -> Unit = {},
        onUp: () -> Unit = {},
        onCrumb: (String) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Ready(path, entries, truncated),
                    onBack = {},
                    onUp = onUp,
                    onOpenDirectory = onOpenDirectory,
                    onOpenFile = onOpenFile,
                    onCrumb = onCrumb,
                    onGoToPath = {},
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun loadingStateShowsSpinner() {
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Loading("/home/u"),
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onCrumb = {},
                    onGoToPath = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_EXPLORER_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun readyStateListsFoldersAndFiles() {
        setReady(listOf(dir("sub"), file("report.txt", 512)))
        compose.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "sub").assertIsDisplayed()
        compose.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "report.txt").assertIsDisplayed()
        compose.onNodeWithText("512 B").assertIsDisplayed()
    }

    @Test
    fun tappingAFolderDescends() {
        var opened: RemoteEntry? = null
        setReady(listOf(dir("sub")), onOpenDirectory = { opened = it })
        compose.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "sub").performClick()
        assertEquals("sub", opened?.name)
    }

    @Test
    fun tappingAFileOpensTheViewer() {
        var opened: RemoteEntry? = null
        setReady(listOf(file("a.txt", 3)), onOpenFile = { opened = it })
        compose.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "a.txt").performClick()
        assertEquals("a.txt", opened?.name)
    }

    @Test
    fun parentRowGoesUp() {
        var ups = 0
        setReady(listOf(dir("sub")), onUp = { ups++ })
        compose.onNodeWithTag(FILE_EXPLORER_UP_TAG).assertIsDisplayed().performClick()
        assertEquals(1, ups)
    }

    @Test
    fun rootHasNoParentRow() {
        setReady(listOf(dir("etc")), path = "/")
        compose.onNodeWithTag(FILE_EXPLORER_UP_TAG).assertDoesNotExist()
    }

    @Test
    fun emptyFolderShowsEmptyState() {
        setReady(emptyList())
        compose.onNodeWithTag(FILE_EXPLORER_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun truncatedListingShowsNote() {
        setReady(listOf(file("f1", 1), file("f2", 1)), truncated = true)
        compose.onNodeWithTag(FILE_EXPLORER_TRUNCATED_TAG).assertIsDisplayed()
    }

    @Test
    fun crumbTapJumpsToAncestor() {
        var jumped: String? = null
        setReady(listOf(dir("x")), path = "/home/u/proj", onCrumb = { jumped = it })
        compose.onNodeWithText("home").performClick()
        assertEquals("/home", jumped)
    }

    @Test
    fun failedStateShowsMessageAndRetry() {
        var retries = 0
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Failed(
                        currentPath = "/root",
                        message = "Permission denied: you can't read /root.",
                    ),
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onCrumb = {},
                    onGoToPath = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithTag(FILE_EXPLORER_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Permission denied: you can't read /root.").assertIsDisplayed()
        compose.onNodeWithTag(FILE_EXPLORER_RETRY_TAG).performClick()
        assertEquals(1, retries)
    }
}
