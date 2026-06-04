package com.pocketshell.app.fileviewer

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
 * Stateless UI tests for the file viewer scaffold (issue #497) — drive every
 * render state without an SSH session.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerScaffoldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingStateShowsSpinner() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/tmp/a.png"),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun textStateShowsContent() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/notes.txt",
                        content = "hello from issue 497",
                        sizeBytes = 20,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_TEXT_TAG).assertIsDisplayed()
        compose.onNodeWithText("hello from issue 497").assertIsDisplayed()
    }

    @Test
    fun cannotPreviewStateShowsMessageAndRetry() {
        var retries = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/tmp/big.bin",
                        message = "File is too large to preview (limit 20 MB).",
                    ),
                    onBack = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_CANNOT_PREVIEW_TAG).assertIsDisplayed()
        compose.onNodeWithText("File is too large to preview (limit 20 MB).").assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_RETRY_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun backButtonInvokesCallback() {
        var backs = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/tmp/a.png"),
                    onBack = { backs++ },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_BACK_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }
}
