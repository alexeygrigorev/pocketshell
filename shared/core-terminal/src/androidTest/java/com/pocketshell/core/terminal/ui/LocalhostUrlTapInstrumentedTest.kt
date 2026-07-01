package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.classifyLocalhostUrl
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #488 — connected coverage for routing a tapped server-local (loopback)
 * URL into the port-forward flow.
 *
 * Renders `http://localhost:3000`, a bare `localhost:5173` dev-server
 * reference, and a real-host URL the way an agent would print them into the
 * visible viewport, runs [findVisibleUrls] to recover grid coordinates,
 * hit-tests the centre of each URL's bounding box with [hitTestUrl], and
 * exercises the same `onTapMaybeUrl` tap hook the production [TerminalSurface]
 * installs. The tapped URL string is then run through [classifyLocalhostUrl] —
 * exactly the decision the tmux screen's `onUrlTap` makes — asserting:
 *
 * - the loopback URL is recognised as server-side (non-null classification with
 *   the right remote port), so a tap routes to the forward flow; and
 * - the real-host URL classifies to null, so a tap keeps the normal browser
 *   open behaviour.
 *
 * Mirrors [FilePathTapInstrumentedTest] (#500) so the gesture-routing math is
 * proven without pulling Compose pointer machinery into this module. Captures a
 * viewport screenshot under `additional_test_output/issue-488/`.
 */
@RunWith(AndroidJUnit4::class)
class LocalhostUrlTapInstrumentedTest {

    @Test
    fun localhostReferencesTapClassifiesAsServerLocalWhileRealHostStaysBrowser() { runBlocking {
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

        // A loopback dev-server URL (the motivating case: a Vite/Next server
        // printed its localhost URL), a bare loopback reference, and a
        // real-host URL on the next row.
        val localUrl = "http://localhost:3000"
        val bareLocal = "localhost:5173"
        val realUrl = "https://example.com/docs"

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

            val localPrefix = "Local:   "
            val barePrefix = "Vite:    "
            val realPrefix = "Docs:    "
            val output = "$localPrefix$localUrl\r\n$barePrefix$bareLocal\r\n$realPrefix$realUrl\r\n"
            state.appendRemoteOutput(output.toByteArray(Charsets.US_ASCII))

            val urls = arrayOfNulls<List<UrlRegion>>(1)
            withTimeout(3_000) {
                while ((urls[0]?.size ?: 0) < 3) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        urls[0] = findVisibleUrls(view)
                    }
                }
            }
            val found = requireNotNull(urls[0])
            assertEquals(
                "scanner should surface exactly the three emitted URL targets: $found",
                3,
                found.size,
            )
            val localRegion = found.firstOrNull { it.url == localUrl }
            val bareRegion = found.firstOrNull { it.url == bareLocal }
            val realRegion = found.firstOrNull { it.url == realUrl }
            assertNotNull("localhost URL should be detected", localRegion)
            assertNotNull("bare localhost reference should be detected", bareRegion)
            assertNotNull("real-host URL should be detected", realRegion)

            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()

            fun centreX(region: UrlRegion): Float =
                (region.startCol + (region.endColExclusive - region.startCol) / 2f) * fontWidth

            fun centreY(region: UrlRegion): Float =
                rowOffset + (region.row - view.topRow + 0.5f) * lineSpacing

            // Install the production tap hook (URL routing mirrors how
            // TerminalSurface wires onTapMaybeUrl) and record what each tap
            // delivers + how the tmux screen's onUrlTap would classify it.
            val tappedUrls = mutableListOf<String>()
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestUrl(view, found, x, y)
                if (hit != null) {
                    tappedUrls.add(hit.url)
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
                synthTap(centreX(localRegion!!), centreY(localRegion))
                synthTap(centreX(bareRegion!!), centreY(bareRegion))
                synthTap(centreX(realRegion!!), centreY(realRegion))

                assertEquals(
                    "all URL/reference taps should reach the host onUrlTap callback",
                    listOf(localUrl, bareLocal, realUrl),
                    tappedUrls,
                )
            } finally {
                client.onTapMaybeUrl = null
            }

            // The decision the tmux screen's onUrlTap makes on each delivered URL:
            // localhost → server-local (route to forward flow), real → browser.
            val localClass = classifyLocalhostUrl(tappedUrls[0])
            assertNotNull("tapped localhost URL must classify as server-local", localClass)
            assertEquals("remote port to forward", 3000, localClass!!.remotePort)
            assertEquals("http://127.0.0.1:4000", localClass.toLocalUrl(4000))

            val bareClass = classifyLocalhostUrl(tappedUrls[1])
            assertNotNull("tapped bare localhost reference must classify as server-local", bareClass)
            assertEquals("remote port to forward", 5173, bareClass!!.remotePort)
            assertEquals("http://127.0.0.1:45173", bareClass.toLocalUrl(45173))

            assertNull(
                "tapped real-host URL must NOT classify as server-local (browser route)",
                classifyLocalhostUrl(tappedUrls[2]),
            )

            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-488-localhost-url-tap-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        assertTrue(true)
    } }

    /**
     * Render the live [TerminalView] off-screen to a bitmap and persist it
     * under `additional_test_output/issue-488/` for reviewer inspection.
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
            val dir = File(mediaRoot, "additional_test_output/issue-488")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE488_VIEWPORT ${outFile.absolutePath}")
        }
    }
}
