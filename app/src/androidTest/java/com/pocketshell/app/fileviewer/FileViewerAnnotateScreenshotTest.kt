package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #764 evidence — capture, on a real emulator, the image-annotation mode:
 * an image preview, the Markup toggle, and the annotate toolbar (Pan/Pen/Arrow/
 * Rect/Circle/Text + swatches + Undo + Done) with every committed annotation type
 * drawn over the image, plus the post-submit saved sheet with the #764 v2
 * "Attach to current session" action. Proves the live `ImagePanel` overlay +
 * `AnnotationToolbar` + `AnnotationSavedSheet` render on-device against the design.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerAnnotateScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun annotateModeWithDrawnAnnotations() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue764-screenshot.png").apply {
            if (exists()) delete()
            writeBytes(checkerboardPng(600, 400))
        }

        // One committed annotation of EVERY type, in source-pixel space, so the
        // overlay renders real Pen / Arrow / Rect / Circle / Text markup.
        val red = ImageAnnotationState.DEFAULT_COLOR_ARGB
        val green = 0xFF22C55E.toInt()
        val amber = 0xFFF59E0B.toInt()
        val pen = (0..36).map { i ->
            val a = Math.toRadians((i * 10).toDouble())
            ImagePoint((150 + 55 * Math.cos(a)).toFloat(), (150 + 55 * Math.sin(a)).toFloat())
        }
        var annotationState by mutableStateOf(
            ImageAnnotationState(
                active = true,
                tool = AnnotationTool.Pen,
                annotations = listOf(
                    Annotation.Freehand(points = pen, colorArgb = red, strokeWidthPx = 6f),
                    Annotation.Arrow(
                        start = ImagePoint(560f, 360f),
                        end = ImagePoint(230f, 190f),
                        colorArgb = red,
                        strokeWidthPx = 6f,
                    ),
                    Annotation.Rectangle(
                        start = ImagePoint(330f, 40f),
                        end = ImagePoint(560f, 170f),
                        colorArgb = green,
                        strokeWidthPx = 6f,
                    ),
                    Annotation.Circle(
                        start = ImagePoint(350f, 230f),
                        end = ImagePoint(520f, 370f),
                        colorArgb = amber,
                        strokeWidthPx = 6f,
                    ),
                    Annotation.Text(
                        text = "BUG",
                        anchor = ImagePoint(30f, 30f),
                        textSizePx = 44f,
                        colorArgb = red,
                    ),
                ),
            ),
        )
        var saved by mutableStateOf<AnnotationSubmitEvent.Success?>(null)

        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/home/agent/shots/login-screen.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    annotationState = annotationState,
                    submittedAnnotation = saved,
                    onBack = {},
                    onRetry = {},
                    onSetAnnotationTool = { annotationState = annotationState.withTool(it) },
                    onDismissSubmittedAnnotation = { saved = null },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_CANVAS_TAG).assertExists()
        compose.waitForIdle()
        // The overlay maps source→screen once the viewport is measured; let the
        // first measure/layout pass settle so the marks are drawn before capture.
        SystemClock.sleep(800)

        // Show the shape/text tools' selection highlight + the drawn markup.
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_TOOL_RECT_TAG).performClick()
        compose.waitForIdle()
        SystemClock.sleep(400)
        capture("file-viewer-annotate-all-types.png")
        capture("file-viewer-annotate-rect-tool.png")

        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_TOOL_TEXT_TAG).performClick()
        compose.waitForIdle()
        SystemClock.sleep(200)
        capture("file-viewer-annotate-text-tool.png")

        // Post-submit saved sheet with the Attach-to-session action (#764 v2).
        saved = AnnotationSubmitEvent.Success(
            host = "agents",
            remotePath = "/home/agent/inbox/pocketshell/annotations/login-screen-20260614-101010.png",
        )
        compose.waitForIdle()
        SystemClock.sleep(400)
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_ATTACH_TAG).assertExists()
        capture("file-viewer-annotate-saved-attach.png")

        imageFile.delete()
    }

    private fun checkerboardPng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(30, 40, 55))
        val light = Paint().apply { color = Color.rgb(60, 75, 95) }
        val cell = 50
        var y = 0
        var row = 0
        while (y < height) {
            var x = if (row % 2 == 0) 0 else cell
            while (x < width) {
                c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), light)
                x += cell * 2
            }
            y += cell
            row++
        }
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/file-viewer-annotate").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FILE_VIEWER_ANNOTATE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
