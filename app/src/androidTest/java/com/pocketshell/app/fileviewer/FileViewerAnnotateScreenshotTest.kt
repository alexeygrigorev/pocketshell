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
 * an image preview, the Markup toggle, and the annotate toolbar (Pan/Pen/Arrow +
 * swatches + Undo + Done) with a committed Pen stroke + Arrow drawn over the
 * image. Proves the live `ImagePanel` overlay + `AnnotationToolbar` render
 * on-device against the design.
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

        // A committed Pen stroke (a rough circle) and an Arrow, in source-pixel
        // space, so the overlay renders real markup over the image.
        val red = ImageAnnotationState.DEFAULT_COLOR_ARGB
        val circle = (0..36).map { i ->
            val a = Math.toRadians((i * 10).toDouble())
            ImagePoint((180 + 70 * Math.cos(a)).toFloat(), (160 + 70 * Math.sin(a)).toFloat())
        }
        var annotationState by mutableStateOf(
            ImageAnnotationState(
                active = true,
                tool = AnnotationTool.Pen,
                annotations = listOf(
                    Annotation.Freehand(points = circle, colorArgb = red, strokeWidthPx = 6f),
                    Annotation.Arrow(
                        start = ImagePoint(520f, 360f),
                        end = ImagePoint(260f, 200f),
                        colorArgb = red,
                        strokeWidthPx = 6f,
                    ),
                ),
            ),
        )

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
                    onBack = {},
                    onRetry = {},
                    onSetAnnotationTool = { annotationState = annotationState.withTool(it) },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_CANVAS_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(400)
        capture("file-viewer-annotate-pen.png")

        // Switch to the Arrow tool to show the tool selection highlight.
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_TOOL_ARROW_TAG).performClick()
        compose.waitForIdle()
        SystemClock.sleep(300)
        capture("file-viewer-annotate-arrow-tool.png")

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
