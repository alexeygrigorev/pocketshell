package com.pocketshell.app.snippets

import android.graphics.Bitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Emulator evidence for #861: captures the migrated `SnippetAddDialog` (now on
 * the shared `FormDialog`) on-device so the issue carries an authoritative
 * after-migration screenshot of a real production input dialog — title +
 * OutlinedTextField content slot + kind toggles + the canonical Cancel/Save
 * action row, all rendered on the device under `PocketShellTheme`.
 */
@RunWith(AndroidJUnit4::class)
class FormDialogMigrationScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun snippetAddDialogOnFormDialog_capturesScreenshot() {
        compose.setContent {
            PocketShellTheme {
                SnippetAddDialog(
                    initialKind = SnippetKind.Prompt,
                    onDismiss = {},
                    onSave = { _, _ -> },
                )
            }
        }
        // Type so the field shows content and Save enables — the migration's
        // behaviour (content-slot text gating the Primary confirm).
        compose.onNodeWithText("Snippet text").performTextInput("git status")
        compose.waitForIdle()
        compose.onNodeWithText("Add snippet").assertExists()
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Cancel").assertExists()

        // Full-device capture so the dialog window (a separate platform window)
        // is included.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val shot = instrumentation.uiAutomation.takeScreenshot()
        if (shot != null) {
            writePng("form-dialog-snippet-add-after.png", shot)
        } else {
            // Fallback: capture the dialog content node directly.
            val image = compose.onNodeWithText("Add snippet").captureToImage()
            val bmp = createBitmap(image.width, image.height)
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            bmp.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            writePng("form-dialog-snippet-add-after.png", bmp)
        }
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(root, "additional_test_output/issue861-form-dialog").apply {
            if (!exists()) mkdirs()
        }
        FileOutputStream(File(dir, name)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
