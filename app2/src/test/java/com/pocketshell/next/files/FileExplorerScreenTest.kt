package com.pocketshell.next.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered file explorer on the host JVM (Robolectric), the same way
 * `:shared:ui-kit` tests its primitives.
 *
 * Every assertion is on the RENDERED tree, because the four "nothing is on
 * screen" situations — still opening, genuinely empty, unreadable, and a
 * transfer in flight — must not paint the same blank. That is the exact class of
 * bug the session tree's own banner assertions exist to catch, applied here.
 */
@RunWith(AndroidJUnit4::class)
class FileExplorerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rows render their name, size and relative time`() {
        setContent(
            state(
                loaded = true,
                entries = listOf(
                    directory("/w/src"),
                    file("/w/README.md", size = 2048, modifiedEpochMs = NOW - 180_000),
                ),
            ),
        )

        composeRule.onNodeWithTag(fileRowTag("src")).assertIsDisplayed()
        composeRule.onNodeWithTag(fileRowTag("README.md")).assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB · 3m ago").assertIsDisplayed()
    }

    @Test
    fun `only files offer a download action`() {
        setContent(state(loaded = true, entries = listOf(directory("/w/src"), file("/w/a.txt"))))

        composeRule.onNodeWithTag(fileDownloadTag("a.txt")).assertIsDisplayed()
        composeRule.onNodeWithTag(fileDownloadTag("src")).assertDoesNotExist()
    }

    @Test
    fun `tapping a directory row opens it and tapping a file row opens the viewer`() {
        val openedDirectories = mutableListOf<String>()
        val openedFiles = mutableListOf<String>()
        setContent(
            state(loaded = true, entries = listOf(directory("/w/src"), file("/w/a.txt"))),
            onOpenDirectory = { openedDirectories += it.path },
            onOpenFile = { openedFiles += it.path },
        )

        composeRule.onNodeWithTag(fileRowTag("src")).performClick()
        composeRule.onNodeWithTag(fileRowTag("a.txt")).performClick()

        assertEquals(listOf("/w/src"), openedDirectories)
        assertEquals(listOf("/w/a.txt"), openedFiles)
    }

    @Test
    fun `an empty folder says so instead of rendering a blank list`() {
        setContent(state(loaded = true, entries = emptyList()))

        composeRule.onNodeWithTag(FILE_EXPLORER_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FILE_EXPLORER_ERROR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FILE_EXPLORER_LOADING_TAG).assertDoesNotExist()
    }

    @Test
    fun `an unreadable folder shows the error and a retry, never the empty state`() {
        var retried = 0
        setContent(
            state(loaded = true, entries = emptyList(), failure = "Could not open /w: permission denied"),
            onRetry = { retried += 1 },
        )

        composeRule.onNodeWithTag(FILE_EXPLORER_ERROR_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Could not open /w: permission denied").assertIsDisplayed()
        composeRule.onNodeWithTag(FILE_EXPLORER_EMPTY_TAG).assertDoesNotExist()

        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `the first load shows an opening state, not an empty folder`() {
        setContent(state(loading = true, loaded = false))

        composeRule.onNodeWithTag(FILE_EXPLORER_LOADING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FILE_EXPLORER_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `an in-flight transfer names the file and disables the actions that would collide`() {
        setContent(
            state(
                loaded = true,
                entries = listOf(file("/w/a.txt")),
                transfer = TransferState.Running("photo.png", uploading = true),
            ),
        )

        composeRule.onNodeWithTag(FILE_EXPLORER_TRANSFER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Uploading photo.png…").assertIsDisplayed()
        composeRule.onNodeWithTag(FILE_EXPLORER_UPLOAD_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(fileDownloadTag("a.txt")).assertIsNotEnabled()
        // A running transfer has no dismiss — there is nothing to dismiss yet.
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test
    fun `a finished transfer can be dismissed`() {
        var dismissed = 0
        setContent(
            state(loaded = true, transfer = TransferState.Done("Saved a.txt to your device")),
            onDismissTransfer = { dismissed += 1 },
        )

        composeRule.onNodeWithText("Saved a.txt to your device").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `breadcrumbs render every ancestor and navigate to the one tapped`() {
        val navigated = mutableListOf<String>()
        setContent(state(path = "/home/alexey/git", loaded = true), onNavigateTo = { navigated += it })

        composeRule.onNodeWithTag(FILE_EXPLORER_CRUMBS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(crumbTag("/home")).performClick()

        assertEquals(listOf("/home"), navigated)
    }

    @Test
    fun `up is disabled at the root, where there is nowhere to go`() {
        setContent(state(path = "/", loaded = true))

        composeRule.onNodeWithTag(FILE_EXPLORER_UP_TAG).assertIsNotEnabled()
    }

    @Test
    fun `up is enabled below the root`() {
        setContent(state(path = "/home", loaded = true))

        composeRule.onNodeWithTag(FILE_EXPLORER_UP_TAG).assertIsEnabled()
    }

    // --- helpers ----------------------------------------------------------

    private fun setContent(
        state: FileExplorerUiState,
        onOpenDirectory: (SftpEntry) -> Unit = {},
        onOpenFile: (SftpEntry) -> Unit = {},
        onNavigateTo: (String) -> Unit = {},
        onDismissTransfer: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                FileExplorerScreen(
                    state = state,
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = onOpenDirectory,
                    onOpenFile = onOpenFile,
                    onNavigateTo = onNavigateTo,
                    onUpload = {},
                    onDownload = {},
                    onDismissTransfer = onDismissTransfer,
                    onRetry = onRetry,
                    nowMs = NOW,
                )
            }
        }
    }

    private fun state(
        path: String = "/w",
        entries: List<SftpEntry> = emptyList(),
        loading: Boolean = false,
        loaded: Boolean = false,
        failure: String? = null,
        transfer: TransferState = TransferState.Idle,
    ) = FileExplorerUiState(
        hostId = 1,
        path = path,
        entries = entries,
        loading = loading,
        loaded = loaded,
        failure = failure,
        transfer = transfer,
    )

    private fun directory(path: String) =
        SftpEntry(path, isDirectory = true, sizeBytes = 0, modifiedEpochMs = 0)

    private fun file(path: String, size: Long = 10, modifiedEpochMs: Long = 0) =
        SftpEntry(path, isDirectory = false, sizeBytes = size, modifiedEpochMs = modifiedEpochMs)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
