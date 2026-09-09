package com.pocketshell.next.files

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

@RunWith(AndroidJUnit4::class)
class TransfersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `transfer page groups progress and retains retryable failures`() {
        val retries = mutableListOf<Long>()
        composeRule.setContent {
            PocketShellTheme {
                TransfersScreen(
                    state = FileExplorerUiState(
                        hostName = "hetzner",
                        path = "/w",
                        transferRecords = listOf(
                            FileTransferRecord(
                                id = 1,
                                name = "workspace.png",
                                uploading = true,
                                source = "This device",
                                destination = "/w/workspace.png",
                                bytesTransferred = 65,
                                totalBytes = 100,
                            ),
                            FileTransferRecord(
                                id = 2,
                                name = "README.md",
                                uploading = false,
                                source = "/w/README.md",
                                destination = "This device",
                                status = FileTransferStatus.Failed,
                                message = "Download failed: destination unavailable",
                            ),
                        ),
                    ),
                    onBack = {},
                    onRetry = { retries += it },
                )
            }
        }

        composeRule.onNodeWithTag(TRANSFERS_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("hetzner · /w").assertIsDisplayed()
        composeRule.onNodeWithText("In progress").assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertDoesNotExist()
        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("workspace.png").assertIsDisplayed()
        composeRule.onNodeWithText("README.md").assertIsDisplayed()
        composeRule.onNodeWithTag(transferRetryTag(2)).performClick()

        assertEquals(listOf(2L), retries)
    }
}
