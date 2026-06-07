package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.FilePathRegion
import com.pocketshell.core.terminal.selection.findVisibleFilePaths
import com.pocketshell.core.terminal.selection.hitTestFilePath
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #500 — connected coverage for tappable file-path detection on the
 * terminal viewport.
 *
 * Renders a PNG path and a text path the way an agent would print them into
 * the visible viewport, runs [findVisibleFilePaths] to recover grid
 * coordinates, hit-tests the centre of each path's bounding box with
 * [hitTestFilePath], and exercises the same `onTapMaybeUrl` tap hook the
 * production [TerminalSurface] installs — asserting the tapped path string is
 * delivered to the host (which navigates to the in-app file viewer, #497).
 *
 * Mirrors [TerminalSurfaceSelectionInstrumentedTest]'s URL scenario; driving
 * the scanner + hit-test + tap hook directly gives end-to-end coverage of the
 * gesture-routing math without pulling Compose pointer machinery into this
 * module.
 *
 * Captures a viewport screenshot under
 * `additional_test_output/issue-500/` for reviewer inspection.
 */
@RunWith(AndroidJUnit4::class)
class FilePathTapInstrumentedTest {

    @Test
    fun pngAndTextPathsAreScannedAndTapRoutesToFileViewerPath() = runBlocking {
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

        // The two paths an agent would emit: a project-relative PNG (the
        // maintainer's motivating case) and a project-relative text path. Kept
        // short enough to fit on one terminal row; soft-wrapped targets are
        // covered separately by the reassembly path (issue #558 bug 2, see
        // WrappedLineReassemblyTest) which stitches a wrapped path/URL into one
        // logical match before scanning.
        val pngPath = "tmp/textures/alpine-hex-b03.png"
        val txtPath = "out/report.txt"

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            // Print the PNG path on row 0 and the text path on row 1, each
            // prefixed with prose so we also prove the scanner does NOT link
            // the surrounding words.
            val pngPrefix = "Wrote image to "
            val txtPrefix = "Report saved: "
            val output = "$pngPrefix$pngPath\r\n$txtPrefix$txtPath\r\n"
            state.appendRemoteOutput(output.toByteArray(Charsets.US_ASCII))

            // Wait until both paths are visible on the viewport.
            val paths = arrayOfNulls<List<FilePathRegion>>(1)
            withTimeout(3_000) {
                while ((paths[0]?.size ?: 0) < 2) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        paths[0] = findVisibleFilePaths(view)
                    }
                }
            }
            val found = requireNotNull(paths[0])

            assertEquals(
                "scanner should surface exactly the two emitted file paths " +
                    "(prose words must NOT be linked): $found",
                2,
                found.size,
            )
            val pngRegion = found.firstOrNull { it.path == pngPath }
            val txtRegion = found.firstOrNull { it.path == txtPath }
            assertNotNull("PNG path should be detected", pngRegion)
            assertNotNull("text path should be detected", txtRegion)

            // Grid math: each path begins right after its prose prefix.
            assertEquals("PNG path starts after its prefix", pngPrefix.length, pngRegion!!.startCol)
            assertEquals(
                "PNG path ends one past its last char",
                pngPrefix.length + pngPath.length,
                pngRegion.endColExclusive,
            )
            assertTrue(
                "text path should render on a row below the PNG path",
                txtRegion!!.row > pngRegion.row,
            )

            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()

            fun centreX(region: FilePathRegion): Float =
                (region.startCol + (region.endColExclusive - region.startCol) / 2f) * fontWidth

            fun centreY(region: FilePathRegion): Float =
                rowOffset + (region.row - view.topRow + 0.5f) * lineSpacing

            // Hit-test the centre of each path; both must resolve.
            val pngHit = hitTestFilePath(view, found, centreX(pngRegion), centreY(pngRegion))
            assertNotNull("centre-of-PNG tap should resolve to the PNG path", pngHit)
            assertEquals(pngPath, pngHit!!.path)

            val txtHit = hitTestFilePath(view, found, centreX(txtRegion), centreY(txtRegion))
            assertNotNull("centre-of-text tap should resolve to the text path", txtHit)
            assertEquals(txtPath, txtHit!!.path)

            // A tap on column 0 (the prose word) must NOT resolve to a path.
            val proseMiss = hitTestFilePath(view, found, 0.5f * fontWidth, centreY(pngRegion))
            assertNull("tap on prose text must fall through (no file path link)", proseMiss)

            // End-to-end: install the production tap hook (file-path routing
            // mirrors how TerminalSurface wires onTapMaybeUrl when the host
            // supplies onFilePathTap) and synthesise single-tap-up gestures.
            val tappedPaths = mutableListOf<String>()
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestFilePath(view, found, x, y)
                if (hit != null) {
                    tappedPaths.add(hit.path)
                    true
                } else {
                    false
                }
            }
            try {
                fun synthTap(x: Float, y: Float) {
                    instrumentation.runOnMainSync {
                        val now = SystemClock.uptimeMillis()
                        val tap = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
                        try {
                            client.onSingleTapUp(tap)
                        } finally {
                            tap.recycle()
                        }
                    }
                }
                synthTap(centreX(pngRegion), centreY(pngRegion))
                synthTap(centreX(txtRegion), centreY(txtRegion))

                assertEquals(
                    "both file-path taps should reach the host (→ open in file viewer)",
                    listOf(pngPath, txtPath),
                    tappedPaths,
                )

                // The prose tap must return false while a file-path tap
                // returns true and routes to the host.
                instrumentation.runOnMainSync {
                    assertFalse(
                        "prose tap hook must return false so no file-path action runs",
                        client.onTapMaybeUrl?.invoke(0.5f * fontWidth, centreY(pngRegion)) ?: true,
                    )
                    assertTrue(
                        "file-path tap hook must return true so the file viewer action runs",
                        client.onTapMaybeUrl?.invoke(centreX(pngRegion), centreY(pngRegion)) ?: false,
                    )
                }
            } finally {
                client.onTapMaybeUrl = null
            }

            // Diagnostic viewport screenshot showing the two tappable paths.
            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-500-tappable-file-paths-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun generatedImagePathsWrappedAcrossRowsRouteFullFileViewerPath() = runBlocking {
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
        val absolutePath =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255de6bac8819197d2528102528ee2.png"
        val fileUriDecodedPath =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1.png"
        val fileUri = "file://$fileUriDecodedPath"

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            state.appendRemoteOutput(
                ("absolute $absolutePath\r\nuri $fileUri\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )

            val paths = arrayOfNulls<List<FilePathRegion>>(1)
            withTimeout(5_000) {
                while (true) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        paths[0] = findVisibleFilePaths(view)
                    }
                    val found = paths[0].orEmpty()
                    val absoluteRows = found.filter { it.path == absolutePath }
                        .map { it.row }
                        .distinct()
                    val fileUriRows = found.filter { it.path == fileUriDecodedPath }
                        .map { it.row }
                        .distinct()
                    if (absoluteRows.size >= 2 && fileUriRows.size >= 2) break
                }
            }
            val found = requireNotNull(paths[0])
            val absoluteRegions = found.filter { it.path == absolutePath }
            val fileUriRegions = found.filter { it.path == fileUriDecodedPath }

            assertTrue(
                "absolute generated-image path should wrap into multiple tappable regions: $found",
                absoluteRegions.map { it.row }.distinct().size >= 2,
            )
            assertTrue(
                "file:// generated-image URI should wrap into multiple tappable regions: $found",
                fileUriRegions.map { it.row }.distinct().size >= 2,
            )
            assertTrue(
                "every absolute-path fragment must carry the full path: $absoluteRegions",
                absoluteRegions.all { it.path == absolutePath },
            )
            assertTrue(
                "every file:// fragment must carry the decoded path: $fileUriRegions",
                fileUriRegions.all { it.path == fileUriDecodedPath },
            )

            val tappedPaths = mutableListOf<String>()
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestFilePath(view, found, x, y)
                if (hit != null) {
                    tappedPaths.add(hit.path)
                    true
                } else {
                    false
                }
            }
            try {
                val absoluteContinuation = absoluteRegions.first { it.row != absoluteRegions.first().row }
                val fileUriContinuation = fileUriRegions.first { it.row != fileUriRegions.first().row }
                instrumentation.runOnMainSync {
                    assertEquals(
                        absolutePath,
                        hitTestFilePath(
                            view,
                            found,
                            centerX(absoluteContinuation, view),
                            centerY(absoluteContinuation, view),
                        )?.path,
                    )
                    assertEquals(
                        fileUriDecodedPath,
                        hitTestFilePath(
                            view,
                            found,
                            centerX(fileUriContinuation, view),
                            centerY(fileUriContinuation, view),
                        )?.path,
                    )
                    assertTrue(
                        client.onTapMaybeUrl?.invoke(
                            centerX(absoluteContinuation, view),
                            centerY(absoluteContinuation, view),
                        ) ?: false,
                    )
                    assertTrue(
                        client.onTapMaybeUrl?.invoke(
                            centerX(fileUriContinuation, view),
                            centerY(fileUriContinuation, view),
                        ) ?: false,
                    )
                }
                assertEquals(listOf(absolutePath, fileUriDecodedPath), tappedPaths)
            } finally {
                client.onTapMaybeUrl = null
            }

            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-611-generated-image-file-paths-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    private fun centerX(region: FilePathRegion, view: TerminalView): Float {
        val renderer = requireNotNull(view.mRenderer) { "renderer should be initialised" }
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * renderer.fontWidth
    }

    private fun centerY(region: FilePathRegion, view: TerminalView): Float {
        val renderer = requireNotNull(view.mRenderer) { "renderer should be initialised" }
        val rowOnScreen = region.row - view.topRow
        return renderer.fontLineSpacingAndAscent + (rowOnScreen + 0.5f) * renderer.fontLineSpacing
    }

    /**
     * Render the live [TerminalView] off-screen to a bitmap and persist it
     * under `additional_test_output/issue-500/` for reviewer inspection.
     * Non-fatal on failure — the assertions are the contract.
     */
    private fun saveViewportScreenshot(view: TerminalView, fileName: String) {
        runCatching {
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaRoot = testArtifactsRoot(ctx)
            val dir = File(mediaRoot, "additional_test_output/issue-500")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE500_VIEWPORT ${outFile.absolutePath}")
        }
    }
}
