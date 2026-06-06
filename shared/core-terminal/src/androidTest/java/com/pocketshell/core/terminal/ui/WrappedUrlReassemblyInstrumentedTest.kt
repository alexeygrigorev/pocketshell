package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #558 bug 2 — connected coverage for soft-wrapped URL reassembly.
 *
 * A long URL the emulator wraps across two visual rows must be detected as ONE
 * logical target so tapping any visual fragment opens the COMPLETE URL. Renders
 * the URL into a deliberately narrow grid so it wraps, runs [findVisibleUrls],
 * and asserts that every emitted region — on every visual row the URL covers —
 * carries the FULL URL string (not just its per-visual-line slice).
 *
 * Captures a viewport screenshot under `additional_test_output/issue-558/`.
 */
@RunWith(AndroidJUnit4::class)
class WrappedUrlReassemblyInstrumentedTest {

    @Test
    fun urlWrappedAcrossRowsIsReassembledIntoOneFullTarget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        // A long GitHub URL guaranteed to soft-wrap on a narrow grid.
        val longUrl =
            "https://github.com/alexeygrigorev/pocketshell/blob/main/docs/" +
                "agent-awareness.md#claude-code-runtime-detection-and-conversation-view"

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                // Narrow width so the URL must wrap across rows.
                val widthSpec = View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            state.appendRemoteOutput("See $longUrl\r\n".toByteArray(Charsets.US_ASCII))

            val urls = arrayOfNulls<List<UrlRegion>>(1)
            withTimeout(5_000) {
                while (urls[0].isNullOrEmpty()) {
                    delay(20)
                    instrumentation.runOnMainSync { urls[0] = findVisibleUrls(view) }
                }
            }
            val found = requireNotNull(urls[0])

            assertTrue(
                "the long URL should soft-wrap across more than one visual row: $found",
                found.size >= 2,
            )
            assertTrue(
                "every wrapped fragment must carry the FULL url, not just its " +
                    "visual-line slice: ${found.map { it.url }}",
                found.all { it.url == longUrl },
            )
            // And the fragments span distinct rows (proving they are the wrap
            // pieces of one logical line, not two separate matches).
            assertTrue(
                "wrapped fragments must occupy distinct rows: $found",
                found.map { it.row }.distinct().size >= 2,
            )

            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-558-wrapped-url-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    private fun saveViewportScreenshot(view: TerminalView, fileName: String) {
        runCatching {
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaRoot = ctx.externalMediaDirs.firstOrNull { it != null }
                ?: ctx.getExternalFilesDir(null)
                ?: ctx.cacheDir
            val dir = File(mediaRoot, "additional_test_output/issue-558")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE558_VIEWPORT ${outFile.absolutePath}")
        }
    }
}
