package com.pocketshell.app.fileexplorer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SortField
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
        transfer: FileTransferState = FileTransferState.Idle,
        onOpenDirectory: (RemoteEntry) -> Unit = {},
        onOpenFile: (RemoteEntry) -> Unit = {},
        onDownloadFile: (RemoteEntry) -> Unit = {},
        onUpload: () -> Unit = {},
        onDismissTransfer: () -> Unit = {},
        onUp: () -> Unit = {},
        onCrumb: (String) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Ready(path, entries, truncated),
                    transfer = transfer,
                    onBack = {},
                    onUp = onUp,
                    onOpenDirectory = onOpenDirectory,
                    onOpenFile = onOpenFile,
                    onDownloadFile = onDownloadFile,
                    onUpload = onUpload,
                    onDismissTransfer = onDismissTransfer,
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
                    transfer = FileTransferState.Idle,
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onDownloadFile = {},
                    onUpload = {},
                    onDismissTransfer = {},
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
                    transfer = FileTransferState.Idle,
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onDownloadFile = {},
                    onUpload = {},
                    onDismissTransfer = {},
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

    @Test
    fun readyStateShowsUploadAction() {
        var uploads = 0
        setReady(listOf(dir("sub")), onUpload = { uploads++ })
        compose.onNodeWithTag(FILE_EXPLORER_UPLOAD_TAG).assertIsDisplayed().performClick()
        assertEquals(1, uploads)
    }

    @Test
    fun fileRowHasDownloadAffordance() {
        var downloaded: RemoteEntry? = null
        setReady(listOf(file("report.txt", 512)), onDownloadFile = { downloaded = it })
        compose.onNodeWithTag(FILE_EXPLORER_DOWNLOAD_TAG_PREFIX + "report.txt")
            .assertIsDisplayed()
            .performClick()
        assertEquals("report.txt", downloaded?.name)
    }

    @Test
    fun directoryRowHasNoDownloadAffordance() {
        setReady(listOf(dir("sub")))
        compose.onNodeWithTag(FILE_EXPLORER_DOWNLOAD_TAG_PREFIX + "sub").assertDoesNotExist()
    }

    @Test
    fun inProgressTransferShowsBannerAndHidesUpload() {
        setReady(
            listOf(file("a.txt", 1)),
            transfer = FileTransferState.InProgress(name = "a.txt", isUpload = true),
        )
        compose.onNodeWithTag(FILE_EXPLORER_TRANSFER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Uploading a.txt…").assertIsDisplayed()
        // Upload is suppressed while a transfer is running.
        compose.onNodeWithTag(FILE_EXPLORER_UPLOAD_TAG).assertDoesNotExist()
    }

    @Test
    fun successTransferShowsDismissibleBanner() {
        var dismisses = 0
        setReady(
            listOf(file("a.txt", 1)),
            transfer = FileTransferState.Success("Uploaded a.txt to /home/u/proj"),
            onDismissTransfer = { dismisses++ },
        )
        compose.onNodeWithText("Uploaded a.txt to /home/u/proj").assertIsDisplayed()
        compose.onNodeWithTag(FILE_EXPLORER_TRANSFER_DISMISS_TAG).performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun failureTransferShowsMessage() {
        setReady(
            listOf(file("a.txt", 1)),
            transfer = FileTransferState.Failure("Upload failed: permission denied"),
        )
        compose.onNodeWithText("Upload failed: permission denied").assertIsDisplayed()
    }

    // --- #762: redesigned rows + Sort menu ---

    @Test
    fun fileRowShowsSizeAndModifiedSubtitle() {
        val withMtime = RemoteEntry(
            name = "report.txt",
            type = RemoteEntry.Type.FILE,
            sizeBytes = 1536,
            // 2024-01-15 — an older year so the date is stable ("Jan 2024").
            modifiedEpochSec = 1705312800L,
        )
        setReady(listOf(withMtime))
        compose.onNodeWithText("1.5 KB · Jan 2024").assertIsDisplayed()
    }

    @Test
    fun readyStateShowsSortAction() {
        setReady(listOf(dir("sub")))
        compose.onNodeWithTag(FILE_EXPLORER_SORT_TAG).assertIsDisplayed()
    }

    @Test
    fun sortMenuOffersFieldsAndReportsSelection() {
        var chosen: Pair<SortField, Boolean>? = null
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Ready(
                        "/home/u/proj",
                        listOf(dir("sub"), file("a.txt", 1)),
                        false,
                    ),
                    transfer = FileTransferState.Idle,
                    sort = FileExplorerViewModel.SortMode(SortField.NAME, ascending = true),
                    onSetSort = { field, asc -> chosen = field to asc },
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onDownloadFile = {},
                    onUpload = {},
                    onDismissTransfer = {},
                    onCrumb = {},
                    onGoToPath = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_EXPLORER_SORT_TAG).performClick()
        compose.onNodeWithTag(FILE_EXPLORER_SORT_ITEM_TAG_PREFIX + SortField.MODIFIED.name)
            .assertIsDisplayed()
            .performClick()
        assertEquals(SortField.MODIFIED to true, chosen)
    }

    @Test
    fun reTappingActiveSortFieldFlipsDirection() {
        var chosen: Pair<SortField, Boolean>? = null
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Ready("/home/u", listOf(dir("sub")), false),
                    transfer = FileTransferState.Idle,
                    // NAME is already the active ascending sort.
                    sort = FileExplorerViewModel.SortMode(SortField.NAME, ascending = true),
                    onSetSort = { field, asc -> chosen = field to asc },
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onDownloadFile = {},
                    onUpload = {},
                    onDismissTransfer = {},
                    onCrumb = {},
                    onGoToPath = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_EXPLORER_SORT_TAG).performClick()
        compose.onNodeWithTag(FILE_EXPLORER_SORT_ITEM_TAG_PREFIX + SortField.NAME.name)
            .performClick()
        // Re-tapping the active field flips to descending.
        assertEquals(SortField.NAME to false, chosen)
    }
}
