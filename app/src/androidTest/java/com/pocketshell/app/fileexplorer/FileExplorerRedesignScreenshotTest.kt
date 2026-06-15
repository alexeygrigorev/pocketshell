package com.pocketshell.app.fileexplorer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SortField
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a full-device screenshot of the REDESIGNED file explorer (#762) for
 * the implementer status comment + reviewer/maintainer sign-off.
 *
 * Drives [FileExplorerScaffold] directly with a realistic listing — folders,
 * a symlink, code / image / archive / binary files, real modification times, and
 * a deliberately very long file name — so the artifact deterministically shows:
 *  - leading file-TYPE icons (no more cramped `DIR`/`FILE` text glyph),
 *  - the two-line `size · modified` secondary line,
 *  - navigational chevrons for folders/links + the `↓` download for files (no
 *    redundant Folder/Link/Other pills), and
 *  - a long name ellipsising cleanly above its meta line.
 */
class FileExplorerRedesignScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mtime(year: Int, month: Int, day: Int): Long =
        java.time.ZonedDateTime
            .of(year, month, day, 10, 0, 0, 0, java.time.ZoneId.systemDefault())
            .toEpochSecond()

    @Test
    fun captureRedesignedExplorer() {
        val entries = listOf(
            RemoteEntry("agents-pool-untracked", RemoteEntry.Type.DIRECTORY, 0L, mtime(2026, 6, 12)),
            RemoteEntry("docs", RemoteEntry.Type.DIRECTORY, 0L, mtime(2026, 5, 30)),
            RemoteEntry("current-link", RemoteEntry.Type.SYMLINK, 0L, mtime(2026, 6, 10)),
            RemoteEntry("issue-103-starter.patch", RemoteEntry.Type.FILE, 8_812L, mtime(2026, 6, 12)),
            RemoteEntry("Main.kt", RemoteEntry.Type.FILE, 2_345L, mtime(2026, 6, 14)),
            RemoteEntry("screenshot-2026-06-14.png", RemoteEntry.Type.FILE, 1_258_291L, mtime(2026, 6, 14)),
            RemoteEntry("release-bundle.tar.gz", RemoteEntry.Type.FILE, 46_137_344L, mtime(2025, 1, 20)),
            RemoteEntry(
                "a-deliberately-very-long-binary-artifact-filename-that-must-ellipsise.bin",
                RemoteEntry.Type.FILE,
                268_435_456L,
                mtime(2024, 3, 2),
            ),
        )

        compose.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                ) {
                    FileExplorerScaffold(
                        hostName = "hetzner",
                        state = FileExplorerUiState.Ready(
                            currentPath = "/home/alexey/git/pocketshell",
                            entries = entries,
                            truncated = false,
                        ),
                        transfer = FileTransferState.Idle,
                        sort = FileExplorerViewModel.SortMode(SortField.NAME, ascending = true),
                        onSetSort = { _, _ -> },
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

        // Sanity: the redesigned rows + the new Sort action are present.
        compose.onNodeWithTag(FILE_EXPLORER_SORT_TAG).assertExists()
        compose.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "current-link").assertExists()
        compose.onNodeWithTag(
            FILE_EXPLORER_DOWNLOAD_TAG_PREFIX + "issue-103-starter.patch",
        ).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(250)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "file-explorer-redesign.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/file-explorer-redesign")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create file-explorer-redesign screenshot directory: ${dir.absolutePath}"
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
                    "Could not write file-explorer-redesign screenshot: ${file.absolutePath}"
                }
            }
            println("FILE_EXPLORER_REDESIGN_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
