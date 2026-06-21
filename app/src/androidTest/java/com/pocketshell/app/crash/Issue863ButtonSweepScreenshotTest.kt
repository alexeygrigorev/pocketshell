package com.pocketshell.app.crash

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #863: authoritative full-device screenshots of a migrated surface — the
 * crash-reports "Delete all reports?" confirm dialog whose Cancel action was
 * migrated from the private `AppBarTextButton` (raw clickable Box) to the shared
 * `PocketShellButton(variant = Text, compact = true)`. The capture shows the
 * migrated muted Cancel sitting beside the red "Delete all" confirm — the
 * canonical Text-action look from `docs/design-system.md`.
 */
class Issue863ButtonSweepScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureMigratedDeleteAllConfirmDialog() {
        compose.setContent {
            PocketShellTheme {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    ConfirmDeleteAllDialog(
                        count = 3,
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CANCEL_TAG).assertExists()
        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(250)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "issue-863-crash-delete-all-confirm.png"))
    }

    @Test
    fun captureMigratedFileExplorerErrorPanel() {
        compose.setContent {
            PocketShellTheme {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    com.pocketshell.app.fileexplorer.FileExplorerScaffold(
                        hostName = "agents",
                        state = com.pocketshell.app.fileexplorer.FileExplorerUiState.Failed(
                            currentPath = "/root",
                            message = "Permission denied: you can't read /root.",
                        ),
                        transfer = com.pocketshell.app.fileexplorer.FileTransferState.Idle,
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
        }

        compose.onNodeWithTag(
            com.pocketshell.app.fileexplorer.FILE_EXPLORER_RETRY_TAG,
        ).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(250)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "issue-863-file-explorer-error-retry-goup.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-863-button-sweep")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-863-button-sweep screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-863 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_863_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
