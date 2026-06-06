package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #559 evidence — capture the "act on the opened file" surface so the
 * reviewer can see, on a real emulator:
 *
 *  - `file-viewer-text-selectable.png`: the text viewer with the header
 *    Share / Copy actions, the "Copy all" action, and a long-press selection
 *    over the mono body (so the OS copy/share handles are visible).
 *  - `file-viewer-image-actions.png`: the image viewer with the header
 *    Share / Copy actions.
 *  - `file-viewer-share-sheet.png`: the system share sheet that appears after
 *    tapping Share on the image — proving `ACTION_SEND` + the `.fileprovider`
 *    grant resolves and the chooser is shown.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerActOnFileScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun textViewerSelectableWithShareCopyActions() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/agent/notes/release-checklist.txt",
                        content = TEXT_BODY,
                        sizeBytes = TEXT_BODY.length.toLong(),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_SHARE_TAG).assertExists()
        compose.onNodeWithTag(FILE_VIEWER_COPY_TAG).assertExists()
        compose.onNodeWithTag(FILE_VIEWER_COPY_ALL_TAG).assertExists()
        // Long-press the mono surface to start a text selection so the OS
        // selection handles + floating copy/share toolbar show up.
        compose.onNodeWithTag(FILE_VIEWER_TEXT_TAG).performTouchInput { longClick() }
        compose.waitForIdle()
        SystemClock.sleep(400)
        captureFullDevice("file-viewer-text-selectable.png")
    }

    @Test
    fun imageViewerShareSheetAppears() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue559-evidence.png").apply {
            if (exists()) delete()
            writeBytes(checkerPngBytes())
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/home/agent/shots/diagram.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_SHARE_TAG).assertExists()
        compose.onNodeWithTag(FILE_VIEWER_COPY_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice("file-viewer-image-actions.png")

        // Tap Share → the system chooser should appear over the activity.
        compose.onNodeWithTag(FILE_VIEWER_SHARE_TAG).performClick()
        SystemClock.sleep(1500)
        captureFullDevice("file-viewer-share-sheet.png")
        imageFile.delete()
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/file-viewer-act").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FILE_VIEWER_ACT_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    /** A small visibly-patterned PNG so the image preview is non-blank. */
    private fun checkerPngBytes(): ByteArray {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val on = ((x / 8) + (y / 8)) % 2 == 0
                bmp.setPixel(x, y, if (on) 0xFF22D3EE.toInt() else 0xFF0F172A.toInt())
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private companion object {
        val TEXT_BODY: String = buildString {
            appendLine("# Release checklist (issue 559 evidence)")
            appendLine()
            appendLine("- [x] Share action wired to ACTION_SEND")
            appendLine("- [x] Copy action puts file URI on clipboard")
            appendLine("- [x] Text is selectable (long-press to select a range)")
            appendLine("- [x] Copy all copies the whole body as plain text")
            appendLine()
            appendLine("Long-press anywhere in this body to start a selection.")
        }
    }
}
