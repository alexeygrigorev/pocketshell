package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.DefaultTerminalMatcher
import com.pocketshell.core.terminal.selection.EngineCommandRegion
import com.pocketshell.core.terminal.selection.FilePathRegion
import com.pocketshell.core.terminal.selection.ShellPaneAffordanceOverlay
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.findVisibleEngineCommands
import com.pocketshell.core.terminal.selection.findVisibleFilePaths
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * Issue #1233 — REGRESSION / behavior-preservation PROOF on the REAL shell-pane
 * path: a shell / non-agent terminal pane keeps tappable URLs, file paths and
 * engine commands (behavior UNCHANGED), but its affordance detection now runs
 * from a SINGLE viewport extraction per coalesced frame, OFF the main thread —
 * not the four independent per-frame on-main scanners it used before.
 *
 * ## What #1233 changed
 *
 * A shell pane used to wire FOUR independent per-frame full-viewport scanners
 * (the URL scan + `SmartSelectionAffordanceOverlay` + `FilePathOverlay` +
 * `EngineCommandOverlay`). Each re-extracted the entire visible viewport
 * (`getSelectedText` per row) AND ran its own regex pass, all on the MAIN thread,
 * every coalesced frame — ~4× redundant per-frame extraction + regex that kept
 * the main thread busy on a high-throughput streaming shell pane (a milder cousin
 * of the #796 ANR). The fix consolidates them into ONE
 * [ShellPaneAffordanceOverlay] that extracts once per frame and runs the enabled
 * passes off-main (the same single-snapshot + off-main split #871 gave agent
 * panes).
 *
 * ## The two load-bearing checks (deterministic, no SSH / Docker)
 *
 * 1. [shellPaneUrlPathAndCommandAreTappableFromSingleSnapshot] composes the
 *    PRODUCTION [TerminalSurface] as a SHELL pane (default
 *    `affordanceScannersEnabled = true`) with the full scanner wiring, feeds a
 *    live `%output` burst carrying a URL + a file path + a `/clear` command, waits
 *    for the consolidated overlay to publish all three, taps each through the SAME
 *    production `onTapMaybeUrl` hook and asserts every tap reaches the host —
 *    proving the URL / path / command affordances are UNCHANGED for a shell pane.
 * 2. [shellPaneAffordanceScanRunsOffMainSingleSnapshotNotFourPerFrame] drives
 *    [ShellPaneAffordanceOverlay] directly with an INSTRUMENTED `scanDispatcher`
 *    and asserts (a) the scan runs OFF the main looper thread, and (b) over a tight
 *    `%output` burst the number of off-main scan dispatches stays bounded FAR below
 *    the raw render-tick count — i.e. ONE debounced scan of ONE snapshot per frame,
 *    NOT four on-main scans per tick. This is the ANR-safety / single-snapshot
 *    load-bearing assertion.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue1233-shell-pane-tappable-viewport.png` + `-visible-terminal.txt`
 *  - `issue1233-shell-pane-singlesnapshot-timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class ShellPaneAffordanceSingleSnapshotProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shellPaneUrlPathAndCommandAreTappableFromSingleSnapshot() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        val filePath = "/home/alexey/src/App.kt"
        val url = "https://example.com/shell/run-1233"
        val command = "/clear"

        val tappedPaths = mutableListOf<String>()
        val tappedUrls = mutableListOf<String>()
        val tappedCommands = mutableListOf<String>()

        try {
            compose.setContent {
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                    // The maintainer's shell pane: the DEFAULT non-agent pane
                    // (affordanceScannersEnabled = true) with the full scanner
                    // wiring the production tmux screen passes.
                    urlsEnabled = true,
                    onUrlTap = { tappedUrls.add(it) },
                    onFilePathTap = { tappedPaths.add(it) },
                    engineCommands = setOf("/clear", "/compact", "/model"),
                    onEngineCommandTap = { tappedCommands.add(it) },
                    matchListener = {},
                    affordanceScannersEnabled = true,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()
            val client = view.mClient as PocketShellTerminalViewClient

            // A shell pane printing a URL, a file path and a slash-command, each
            // prefixed with prose so we also prove the surrounding words are NOT
            // linked. Fed as live `%output` (the real shell shape).
            stdout.emit(
                "See $url\r\nEdit $filePath\r\nType $command to reset\r\n".toByteArray(Charsets.US_ASCII),
            )

            // Wait until the consolidated single-snapshot overlay has published all
            // three affordances onto the live viewport.
            val paths = AtomicReference<List<FilePathRegion>>(emptyList())
            val urls = AtomicReference<List<UrlRegion>>(emptyList())
            val commands = AtomicReference<List<EngineCommandRegion>>(emptyList())
            withTimeout(8_000) {
                while (true) {
                    delay(40)
                    instrumentation.runOnMainSync {
                        paths.set(findVisibleFilePaths(view))
                        urls.set(findVisibleUrls(view))
                        commands.set(findVisibleEngineCommands(view, setOf("/clear", "/compact", "/model")))
                    }
                    if (paths.get().any { it.path == filePath } &&
                        urls.get().any { it.url == url } &&
                        commands.get().any { it.command == command }
                    ) {
                        break
                    }
                }
            }

            val pathRegion = paths.get().firstOrNull { it.path == filePath }
            val urlRegion = urls.get().firstOrNull { it.url == url }
            val cmdRegion = commands.get().firstOrNull { it.command == command }
            assertNotNull("#1233: shell pane must surface the tappable file path ($filePath)", pathRegion)
            assertNotNull("#1233: shell pane must surface the tappable URL ($url)", urlRegion)
            assertNotNull("#1233: shell pane must surface the tappable engine command ($command)", cmdRegion)

            // Tap the centre of each region through the SAME production
            // `onTapMaybeUrl` hook [TerminalSurface] installs on the view client.
            suspend fun tapUntilRecorded(x: Float, y: Float, recorded: () -> Boolean) {
                withTimeout(5_000) {
                    while (!recorded()) {
                        compose.waitForIdle()
                        instrumentation.runOnMainSync {
                            val now = SystemClock.uptimeMillis()
                            val tap = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
                            try {
                                client.onSingleTapUp(tap)
                            } finally {
                                tap.recycle()
                            }
                        }
                        if (recorded()) break
                        delay(60)
                    }
                }
            }
            tapUntilRecorded(centreX(urlRegion!!, view), centreY(urlRegion.row, view)) { tappedUrls.contains(url) }
            tapUntilRecorded(centreX(pathRegion!!, view), centreY(pathRegion.row, view)) { tappedPaths.contains(filePath) }
            tapUntilRecorded(centreX(cmdRegion!!, view), centreY(cmdRegion.row, view)) { tappedCommands.contains(command) }

            assertTrue("#1233: tapping the URL on a shell pane must reach the host. tappedUrls=$tappedUrls", tappedUrls.contains(url))
            assertTrue("#1233: tapping the file path on a shell pane must reach the host. tappedPaths=$tappedPaths", tappedPaths.contains(filePath))
            assertTrue("#1233: tapping the /clear command on a shell pane must reach the host. tappedCommands=$tappedCommands", tappedCommands.contains(command))

            captureViewport(view, "issue1233-shell-pane-tappable")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    } }

    /**
     * Issue #1233 — the LOAD-BEARING single-snapshot / ANR-safety assertion: the
     * shell-pane affordance scan must run OFF the main thread and dispatch ONE
     * debounced scan per settled frame, NOT four on-main scans per tick.
     */
    @Test
    fun shellPaneAffordanceScanRunsOffMainSingleSnapshotNotFourPerFrame() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        val mainThread = Looper.getMainLooper().thread
        val sawMainThreadScan = AtomicBoolean(false)
        val scanThreadName = AtomicReference("")
        // Each dispatch is ONE off-main scan of ONE viewport snapshot (the single
        // `withContext(scanContext)` per frame in collectShellPaneAffordances).
        val scanDispatchCount = AtomicLong(0L)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "issue1233-scan") }
        val scanDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                runCatching {
                    executor.execute {
                        val t = Thread.currentThread()
                        scanThreadName.set(t.name)
                        if (t === mainThread) sawMainThreadScan.set(true)
                        scanDispatchCount.incrementAndGet()
                        block.run()
                    }
                }
            }
        }

        // Count the RAW render ticks the burst produces (the per-frame budget the
        // scan must NOT track). Collected off-main so it never competes for Main.
        val rawTickCount = AtomicLong(0L)
        val rawScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rawJob = rawScope.launch { state.renderRequests.collect { rawTickCount.incrementAndGet() } }

        val view = buildLaidOutView(state)

        try {
            compose.setContent {
                ShellPaneAffordanceOverlay(
                    view = view,
                    renderRequests = state.renderRequests,
                    matcher = DefaultTerminalMatcher(),
                    knownCommands = setOf("/clear", "/compact", "/model"),
                    scanUrls = true,
                    scanFilePaths = true,
                    onUrlsChanged = {},
                    onFilePathsChanged = {},
                    onMatchesChanged = {},
                    onEngineCommandsChanged = {},
                    scanDispatcher = scanDispatcher,
                )
            }
            compose.waitForIdle()

            val burstStart = SystemClock.uptimeMillis()
            var chunk = 0
            while (SystemClock.uptimeMillis() - burstStart < BURST_DURATION_MS) {
                stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                chunk += 1
            }
            SystemClock.sleep(SETTLE_MS)
            instrumentation.waitForIdleSync()

            val dispatches = scanDispatchCount.get()
            val rawTicks = rawTickCount.get()
            rawJob.cancel()
            rawScope.cancel()

            writeTimings(
                instrumentation,
                listOf(
                    "scenario=#1233 shell-pane affordance scan: single-snapshot + off-main + debounced",
                    "issue=1233",
                    "burst_chunks=$chunk",
                    "burst_duration_ms=$BURST_DURATION_MS",
                    "raw_render_request_ticks=$rawTicks",
                    "off_main_scan_dispatches=$dispatches",
                    "scan_thread_name=${scanThreadName.get()}",
                    "saw_main_thread_scan=${sawMainThreadScan.get()}",
                    "note=one dispatch = one off-main scan of ONE viewport snapshot per frame " +
                        "(all four passes share it); pre-#1233 a shell pane ran FOUR on-main scans " +
                        "per tick, each re-extracting the viewport.",
                    "expectation=scan runs on a background dispatcher (NOT main); off-main scan " +
                        "dispatches bounded FAR below raw_render_request_ticks (one debounced " +
                        "single-snapshot scan per frame, not four per tick).",
                ),
            )

            assertTrue(
                "burst must produce a real renderRequests storm; rawTicks=$rawTicks",
                rawTicks >= MIN_RAW_TICKS,
            )
            assertTrue(
                "shell-pane overlay must have dispatched at least one off-main scan; " +
                    "scanThread='${scanThreadName.get()}'",
                scanThreadName.get().isNotEmpty(),
            )

            // ---- LOAD-BEARING #1: the scan NEVER ran on the main thread.
            assertFalse(
                "#1233: the shell-pane affordance scan must run OFF the main thread. It " +
                    "executed on thread '${scanThreadName.get()}'; a main-thread scan is the " +
                    "per-frame cost this issue removed.",
                sawMainThreadScan.get(),
            )

            // ---- LOAD-BEARING #2: ONE debounced single-snapshot scan per frame,
            // bounded FAR below the raw tick count. A per-tick (let alone 4-per-tick
            // on-main) scan would dispatch ~= rawTicks.
            assertTrue(
                "#1233: the shell-pane scan must be a debounced SINGLE-snapshot scan per frame, " +
                    "NOT four on-main scans per tick. Over the burst it dispatched $dispatches " +
                    "off-main scans against $rawTicks raw render ticks; a conflated single-snapshot " +
                    "scan stays far below the raw count.",
                dispatches <= rawTicks / DEBOUNCE_FACTOR + DEBOUNCE_SLACK,
            )
        } finally {
            executor.shutdownNow()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    private fun buildChunk(i: Int): String {
        val esc = ""
        return buildString {
            append("$esc[H")
            for (row in 0 until VIEWPORT_ROWS) {
                append("$esc[K")
                append("$BURST_MARKER r$row see https://example.com/shell-$i-$row ")
                append("/clear edit /home/alexey/src/Burst$i$row.kt done\r\n")
            }
        }
    }

    private fun buildLaidOutView(state: TerminalSurfaceState): TerminalView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ref = arrayOfNulls<TerminalView>(1)
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null)
            view.applyPocketShellDefaults(PocketShellTerminalViewClient())
            view.attachSession(requireNotNull(state.session))
            val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            view.measure(w, h)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            ref[0] = view
        }
        return requireNotNull(ref[0])
    }

    private suspend fun waitForTerminalView(): TerminalView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ref = arrayOfNulls<TerminalView>(1)
        withTimeout(5_000) {
            while (ref[0] == null) {
                instrumentation.runOnMainSync {
                    ref[0] = findTerminalView(compose.activity.window.decorView)
                }
                if (ref[0] == null) delay(20)
            }
        }
        return requireNotNull(ref[0])
    }

    private fun findTerminalView(root: View): TerminalView? {
        if (root is TerminalView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findTerminalView(root.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun centreX(region: FilePathRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * r.fontWidth
    }

    private fun centreX(region: UrlRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * r.fontWidth
    }

    private fun centreX(region: EngineCommandRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * r.fontWidth
    }

    private fun centreY(row: Int, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return r.fontLineSpacingAndAscent + (row - view.topRow + 0.5f) * r.fontLineSpacing
    }

    private fun visibleTerminalText(view: TerminalView): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun captureViewport(view: TerminalView, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            if (view.width > 0 && view.height > 0) {
                val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
                bitmap = b
            }
        }
        val ctx = instrumentation.targetContext
        bitmap?.let { b ->
            val file = artifactFile(ctx, "$name-viewport.png")
            FileOutputStream(file).use { out ->
                check(b.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE1233_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile(ctx, "$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeTimings(
        instrumentation: android.app.Instrumentation,
        lines: List<String>,
    ) {
        val file = artifactFile(instrumentation.targetContext, "issue1233-shell-pane-singlesnapshot-timings.txt")
        file.writeText(lines.joinToString("\n") + "\n")
        println("ISSUE1233_TIMINGS ${file.absolutePath}")
        for (line in lines) android.util.Log.i(LOG_TAG, "TIMING $line")
    }

    private fun artifactFile(context: android.content.Context, name: String): File {
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue1233ShellPane"
        const val BURST_MARKER = "ISSUE1233-BURST"
        const val VIEWPORT_ROWS = 30
        const val BURST_DURATION_MS = 4_000L
        const val SETTLE_MS = 600L
        const val MIN_RAW_TICKS = 40L

        // A conflated single-snapshot scan services at most ~one per settled frame;
        // over a burst that fires N raw ticks it should dispatch << N.
        const val DEBOUNCE_FACTOR = 4L
        const val DEBOUNCE_SLACK = 20L
    }
}
