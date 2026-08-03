package com.pocketshell.core.terminal.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatchRegion
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.extractVisibleViewportRows
import com.pocketshell.core.terminal.selection.findVisibleFilePaths
import com.pocketshell.core.terminal.selection.findVisibleTerminalMatches
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.pocketshell.core.terminal.selection.hitTestUrl
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
import org.junit.Rule
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
 * Also verifies [findVisibleTerminalMatches], which feeds underline decoration,
 * emits one URL match region per wrapped visual row with the same full URL.
 *
 * Captures a viewport screenshot under `additional_test_output/issue-558/`.
 */
@RunWith(AndroidJUnit4::class)
class WrappedUrlReassemblyInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun issue1955HardWrappedExactGithubUrlBothRowsDispatchCompleteUri() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()
        val url =
            "https://github.com/DataTalksClub/playbooks/blob/main/" +
                "campaigns/ml-zoomcamp-2026/copy-bank/events/pre-course-live-qa.md"
        val splitAt = url.indexOf("campaigns") + "campaig".length
        val firstFragment = url.take(splitAt)
        val continuationFragment = url.drop(splitAt)
        assertEquals("the reported Pixel-7 terminal grid is 60 columns", 60, splitAt)

        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(android.app.Instrumentation.ActivityResult(0, null))

            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))

                // Initialise renderer metrics, then size the REAL TerminalView
                // to the 60-column Pixel-7 viewport captured in the report.
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val fontWidth = requireNotNull(view.mRenderer).fontWidth
                val sixtyColumnWidth = (fontWidth * 60.5f).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(sixtyColumnWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertEquals(60, requireNotNull(view.mEmulator).mColumns)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            // Claude Code emits long response URLs as OSC 8 hyperlinks. Disable
            // VT auto-wrap and position the cursor explicitly for row 2 to paint
            // the exact screenshot shape while leaving BOTH line-wrap bits false.
            // The OSC 8 remains active across the cursor move, which gives the
            // terminal a structural continuation signal without guessing from
            // the visible `campaig` / `ns/...` text.
            val hardWrappedPaint = buildString {
                append("\u001b[?7l")
                append("\u001b]8;;$url\u001b\\")
                append(firstFragment)
                append("\u001b[2;1H")
                append(continuationFragment)
                append("\u001b]8;;\u001b\\")
                append("\u001b[?7h")
            }
            state.appendRemoteOutput(hardWrappedPaint.toByteArray(Charsets.US_ASCII))

            val snapshotRef = arrayOfNulls<com.pocketshell.core.terminal.selection.ViewportRowsSnapshot>(1)
            withTimeout(5_000) {
                while (true) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        snapshotRef[0] = extractVisibleViewportRows(view)
                    }
                    val snapshot = snapshotRef[0] ?: continue
                    val first = snapshot.rows.firstOrNull { it.row == 0 }?.text?.take(60)
                    val second = snapshot.rows.firstOrNull { it.row == 1 }?.text
                    if (first == firstFragment && second?.startsWith(continuationFragment) == true) break
                }
            }
            val snapshot = requireNotNull(snapshotRef[0])
            val sourceRows = snapshot.rows.filter { it.row == 0 || it.row == 1 }
            assertEquals(listOf(false, false), sourceRows.map { it.wrapsToNext })
            assertTrue(
                "first row's last cell must retain OSC 8 provenance",
                sourceRows[0].endsWithOsc8Hyperlink,
            )
            assertTrue(
                "continuation row's first cell must retain OSC 8 provenance",
                sourceRows[1].startsWithOsc8Hyperlink,
            )
            assertFalse(
                "continuation must not be a newly opened independent OSC 8 link",
                sourceRows[1].startsNewOsc8Hyperlink,
            )

            val urlsRef = arrayOfNulls<List<UrlRegion>>(1)
            val pathsRef = arrayOfNulls<List<com.pocketshell.core.terminal.selection.FilePathRegion>>(1)
            val matchesRef = arrayOfNulls<List<TerminalMatchRegion>>(1)
            instrumentation.runOnMainSync {
                urlsRef[0] = findVisibleUrls(view)
                pathsRef[0] = findVisibleFilePaths(view)
                matchesRef[0] = findVisibleTerminalMatches(view)
            }
            val urls = requireNotNull(urlsRef[0]).filter { it.url == url }.sortedBy { it.row }
            assertEquals("both visual fragments must share one complete target", listOf(0, 1), urls.map { it.row })
            assertTrue(urls.all { it.url == url })
            assertFalse(
                "the continuation must not be exposed to the local file viewer",
                requireNotNull(pathsRef[0]).any { it.row == 1 },
            )
            val decorations = requireNotNull(matchesRef[0])
                .filter { it.match is TerminalMatch.Url && it.match.value == url }
                .sortedBy { it.row }
            assertEquals("both URL fragments must be decorated", listOf(0, 1), decorations.map { it.row })

            // Use the same TerminalViewClient single-tap hook shape as the
            // production TerminalSurface, including the real ACTION_VIEW bridge.
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestUrl(view, urls, x, y)
                if (hit == null) {
                    false
                } else {
                    openUrlWithFallback(context, hit.url)
                    true
                }
            }
            try {
                for (region in urls) {
                    val before = Intents.getIntents().count {
                        it.action == Intent.ACTION_VIEW && it.data == Uri.parse(url)
                    }
                    instrumentation.runOnMainSync {
                        val now = SystemClock.uptimeMillis()
                        val event = MotionEvent.obtain(
                            now,
                            now,
                            MotionEvent.ACTION_UP,
                            centerX(region, view),
                            centerY(region, view),
                            0,
                        )
                        try {
                            client.onSingleTapUp(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    val after = Intents.getIntents().count {
                        it.action == Intent.ACTION_VIEW && it.data == Uri.parse(url)
                    }
                    assertEquals(
                        "tap on row ${region.row} must dispatch the exact complete URI once",
                        before + 1,
                        after,
                    )
                }
            } finally {
                client.onTapMaybeUrl = null
            }

            val exactIntentCount = Intents.getIntents().count {
                it.action == Intent.ACTION_VIEW && it.data == Uri.parse(url)
            }
            val artifactSummary = buildString {
                appendLine("url=$url")
                appendLine("columns=${snapshot.columns}")
                appendLine("source_wrap_flags=${sourceRows.map { it.wrapsToNext }}")
                appendLine(
                    "source_osc8_boundary=" +
                        "[${sourceRows[0].endsWithOsc8Hyperlink}, " +
                        "${sourceRows[1].startsWithOsc8Hyperlink}]",
                )
                appendLine("continuation_starts_new_osc8=${sourceRows[1].startsNewOsc8Hyperlink}")
                appendLine("url_region_rows=${urls.map { it.row }}")
                appendLine("url_region_targets=${urls.map { it.url }}")
                appendLine("file_path_regions=${pathsRef[0]}")
                appendLine("action_view_exact_uri_count=$exactIntentCount")
            }
            instrumentation.runOnMainSync {
                saveIssue1955Artifacts(
                    view = view,
                    visibleText = sourceRows.joinToString("\n") { it.text.trimEnd() },
                    summary = artifactSummary,
                )
            }
        } finally {
            Intents.release()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun urlWrappedAcrossRowsIsReassembledIntoOneFullTarget() { runBlocking {
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
            val continuation = found.first { it.row != found.first().row }
            val continuationHit = hitTestUrl(
                view,
                found,
                centerX(continuation, view),
                centerY(continuation, view),
            )
            assertTrue(
                "tap on continuation row should resolve the full URL, got $continuationHit",
                continuationHit?.url == longUrl,
            )

            val matchRegions = arrayOfNulls<List<TerminalMatchRegion>>(1)
            instrumentation.runOnMainSync {
                matchRegions[0] = findVisibleTerminalMatches(view)
            }
            val urlMatchRegions = requireNotNull(matchRegions[0])
                .filter { it.match is TerminalMatch.Url }
                .filter { it.match.value == longUrl }
            assertTrue(
                "underline decoration should include every wrapped URL fragment: $urlMatchRegions",
                urlMatchRegions.size >= 2,
            )
            assertTrue(
                "underline fragments should occupy distinct visual rows: $urlMatchRegions",
                urlMatchRegions.map { it.row }.distinct().size >= 2,
            )

            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-558-wrapped-url-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    private fun centerX(region: UrlRegion, view: TerminalView): Float {
        val renderer = requireNotNull(view.mRenderer) { "renderer should be initialised" }
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * renderer.fontWidth
    }

    private fun centerY(region: UrlRegion, view: TerminalView): Float {
        val renderer = requireNotNull(view.mRenderer) { "renderer should be initialised" }
        val rowOnScreen = region.row - view.topRow
        return renderer.fontLineSpacingAndAscent + (rowOnScreen + 0.5f) * renderer.fontLineSpacing
    }

    private fun saveViewportScreenshot(view: TerminalView, fileName: String) {
        runCatching {
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaRoot = testArtifactsRoot(ctx)
            val dir = File(mediaRoot, "additional_test_output/issue-558")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE558_VIEWPORT ${outFile.absolutePath}")
        }
    }

    private fun saveIssue1955Artifacts(
        view: TerminalView,
        visibleText: String,
        summary: String,
    ) {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(testArtifactsRoot(ctx), "additional_test_output/issue-1955")
        check(dir.exists() || dir.mkdirs()) { "could not create issue-1955 artifact directory: $dir" }
        val viewport = File(dir, "issue1955-hard-wrapped-url-viewport.png")
        FileOutputStream(viewport).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "could not encode #1955 viewport artifact"
            }
        }
        File(dir, "issue1955-visible-terminal.txt").writeText(visibleText)
        File(dir, "issue1955-summary.txt").writeText(summary)
        println("ISSUE1955_ARTIFACTS ${dir.absolutePath}")
    }
}
