package com.pocketshell.app.projects

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
 * Captures a screenshot of the clarified folder context action sheet content
 * (#517) for the implementer status comment. Renders [FolderContextActionContent]
 * directly so the artifact deterministically shows the "Add a project" section
 * with the new label + one-line description copy, without requiring a live SSH
 * session to open the real bottom sheet.
 */
class FolderContextActionSheetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureFolderContextSheetCopy() {
        compose.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Surface),
                ) {
                    FolderContextActionContent(
                        folderLabel = "projects",
                        folderPath = "/root/projects",
                        onNewSession = {},
                        onImport = {},
                        onCloneGitProject = {},
                        onEmptyProject = {},
                        onEnv = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(FOLDER_CONTEXT_EMPTY_PROJECT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "folder-context-sheet.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/folder-context-sheet")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create folder-context-sheet screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write folder-context-sheet screenshot: ${file.absolutePath}"
                }
            }
            println("FOLDER_CONTEXT_SHEET_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
