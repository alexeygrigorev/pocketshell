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
import com.pocketshell.core.terminal.selection.AgentPaneAffordanceOverlay
import com.pocketshell.core.terminal.selection.FilePathRegion
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.findVisibleFilePaths
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.pocketshell.core.terminal.selection.hitTestFilePath
import com.pocketshell.core.terminal.selection.hitTestUrl
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
import org.junit.Assert.assertEquals
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
 * Issue #871 — REGRESSION PROOF: on an interactive-agent (Codex/Claude) Terminal
 * pane, a file path AND a URL in the agent output must be tappable (open the file
 * viewer / browser) AGAIN — but the detection must run OFF the main thread /
 * debounced, NOT the per-frame on-main scan that caused the #803/#866/#796 ANR.
 *
 * ## The regression this reproduces
 *
 * The #796-REOPENED ANR fix gated ALL four per-frame on-main affordance scanners
 * OFF an agent pane (`affordanceScannersEnabled = false`). That killed the ANR
 * but also removed the tappable file-path + URL affordance on exactly the panes
 * where the maintainer wants it most (agents emit file paths constantly). On the
 * pre-fix code an agent pane wires NO scanner, so `visibleFilePaths` /
 * `visibleUrls` stay empty and a tap on a path/URL resolves to nothing → RED
 * ([agentPaneFilePathAndUrlAreTappable]).
 *
 * ## How the fix is proven (deterministic, no SSH / Docker)
 *
 * 1. [codexAgentPaneFilePathAndUrlAreTappable] AND
 *    [claudeAgentPaneFilePathAndUrlAreTappable] (G2 class coverage: BOTH agent
 *    kinds) compose the PRODUCTION [TerminalSurface] on a real [ComponentActivity]
 *    with a real vendored [TerminalView], marked as an AGENT pane
 *    (`affordanceScannersEnabled = false`) with the #871 link affordances enabled
 *    (`agentPaneLinkAffordancesEnabled = true`) and real `onFilePathTap` /
 *    `onUrlTap` sinks. The Codex case prints a project-relative PNG path; the
 *    Claude case prints an ABSOLUTE `.kt` path — the two path shapes the
 *    maintainer's two agents emit — each plus a URL. Each waits for the affordance
 *    snapshot to populate, then taps the centre of each region through the SAME
 *    PRODUCTION `onTapMaybeUrl` hook [TerminalSurface] installs on the view client,
 *    and asserts BOTH taps reach the host. RED on base (agent pane wires no scanner
 *    → the production `filePathTapActive`/URL gate is off → no tappable region);
 *    GREEN with the off-main overlay. The affordance path has NO `agentKind` knob
 *    (see [claudeAgentPaneFilePathAndUrlAreTappable] KDoc) — Codex vs Claude differ
 *    only in path/URL SHAPE, which is the meaningful class axis exercised here.
 *
 * 2. [agentPaneScanRunsOffMainNotPerFrame] drives [AgentPaneAffordanceOverlay]
 *    directly with an INSTRUMENTED `scanDispatcher` that records the thread every
 *    regex scan runs on, and asserts (a) the scan thread is NOT the main looper
 *    thread (the #803/#866 cost is off-main), and (b) over a tight `%output` burst
 *    the number of off-main scans stays bounded FAR below the raw render-tick
 *    count (debounced/conflated, not per-frame) — the property whose absence was
 *    the ANR. This is the load-bearing ANR-safety assertion (D32 adjacency sweep):
 *    the fix must NOT reintroduce a per-frame main-thread scan on agent panes.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue871-agent-pane-tappable-viewport.png` + `-visible-terminal.txt`
 *  - `issue871-agent-pane-offmain-timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class AgentPaneLinkAffordanceOffMainProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Issue #871 / G2 class coverage — CODEX agent pane: a project-relative PNG
     * path (`tmp/…png`) and a schemed URL the way Codex prints them. RED on base
     * (the agent pane wired no scanner), GREEN with the off-main overlay.
     */
    @Test
    fun codexAgentPaneFilePathAndUrlAreTappable() = runBlocking {
        agentPaneFilePathAndUrlAreTappable(
            kindLabel = "Codex",
            filePath = "tmp/bathtub_central_cavity_new_feedback.png",
            filePathPrefix = "Wrote image to ",
            url = "https://example.com/codex/run-42",
            urlPrefix = "See ",
            artifactName = "issue871-codex-agent-pane-tappable",
        )
    }

    /**
     * Issue #871 / G2 class coverage — CLAUDE agent pane: an ABSOLUTE file path
     * (`/home/…/Foo.kt`, Claude's usual shape) and a schemed URL the way Claude
     * Code prints them. This proves the agent-pane affordance is NOT specific to
     * Codex's relative-path shape and covers the other agent kind the maintainer
     * runs.
     *
     * Why this is sufficient class coverage (G2): the production [TerminalSurface]
     * has NO `agentKind` parameter — an agent pane is configured identically
     * regardless of kind (`affordanceScannersEnabled = false`,
     * `agentPaneLinkAffordancesEnabled = true`). The off-main
     * [AgentPaneAffordanceOverlay] scans the visible viewport text with the SAME
     * regex passes ([findVisibleFilePaths] / [findVisibleUrls]) whatever produced
     * the text, so Codex vs Claude differ only in the SHAPE of path/URL they
     * print. Exercising both shapes (relative path here vs absolute path there)
     * is the meaningful class axis; a third agent kind would re-print one of the
     * same two shapes through the identical code.
     */
    @Test
    fun claudeAgentPaneFilePathAndUrlAreTappable() = runBlocking {
        agentPaneFilePathAndUrlAreTappable(
            kindLabel = "Claude",
            filePath = "/home/alexey/git/pocketshell/src/MainActivity.kt",
            filePathPrefix = "Updated ",
            url = "https://docs.anthropic.com/claude-code/run-7",
            urlPrefix = "Reference: ",
            artifactName = "issue871-claude-agent-pane-tappable",
        )
    }

    private suspend fun agentPaneFilePathAndUrlAreTappable(
        kindLabel: String,
        filePath: String,
        filePathPrefix: String,
        url: String,
        urlPrefix: String,
        artifactName: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        val tappedPaths = mutableListOf<String>()
        val tappedUrls = mutableListOf<String>()

        try {
            compose.setContent {
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                    // The maintainer's EXACT reported pane: an AGENT pane (Codex or
                    // Claude — identical config, no `agentKind` knob).
                    // `affordanceScannersEnabled = false` is the #796 agent gate
                    // (no per-frame on-main scanners). `agentPaneLinkAffordancesEnabled
                    // = true` is the #871 fix: restore tappable file paths + URLs
                    // via the OFF-main overlay. On the PRE-FIX surface this second
                    // flag does not exist / has no effect, so the agent pane wires
                    // NO scanner and the taps below resolve to nothing → RED.
                    urlsEnabled = true,
                    onUrlTap = { tappedUrls.add(it) },
                    onFilePathTap = { tappedPaths.add(it) },
                    affordanceScannersEnabled = false,
                    agentPaneLinkAffordancesEnabled = true,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()
            val client = view.mClient as PocketShellTerminalViewClient

            // What the agent pane prints: a file path and a URL, each prefixed with
            // prose so we also prove the surrounding words are NOT linked. The
            // [kindLabel] varies the SHAPE (Codex relative png vs Claude absolute
            // kt) — the agent-pane affordance code is kind-agnostic.
            val pngPath = filePath
            val pngPrefix = filePathPrefix
            // Feed as live `%output` (the real agent pane shape) so the emulator's
            // onTextChanged drives a render request that the OFF-main overlay
            // re-scans on. (A seed via appendRemoteOutput would also reach the
            // viewport, but live output is the maintainer's actual scenario.)
            stdout.emit(
                "$pngPrefix$pngPath\r\n$urlPrefix$url\r\n".toByteArray(Charsets.US_ASCII),
            )

            // Wait until the OFF-main overlay has published BOTH a file path and a
            // URL onto the live viewport. On the pre-fix surface (agent pane wires
            // no scanner) these never populate → the timeout fires → RED.
            val paths = AtomicReference<List<FilePathRegion>>(emptyList())
            val urls = AtomicReference<List<UrlRegion>>(emptyList())
            withTimeout(8_000) {
                while (true) {
                    delay(40)
                    instrumentation.runOnMainSync {
                        paths.set(findVisibleFilePaths(view))
                        urls.set(findVisibleUrls(view))
                    }
                    if (paths.get().any { it.path == pngPath } &&
                        urls.get().any { it.url == url }
                    ) {
                        break
                    }
                }
            }

            val foundPaths = paths.get()
            val foundUrls = urls.get()
            val pngRegion = foundPaths.firstOrNull { it.path == pngPath }
            val urlRegion = foundUrls.firstOrNull { it.url == url }
            assertNotNull(
                "#871 ($kindLabel): an AGENT pane must surface the tappable file path " +
                    "($pngPath). Pre-fix the agent pane wired no scanner → empty. " +
                    "Found paths=$foundPaths",
                pngRegion,
            )
            assertNotNull(
                "#871 ($kindLabel): an AGENT pane must surface the tappable URL ($url). " +
                    "Found urls=$foundUrls",
                urlRegion,
            )

            // End-to-end: tap the centre of each region through the SAME PRODUCTION
            // `onTapMaybeUrl` hook [TerminalSurface] installed on the view client.
            // That hook is gated by the production `filePathTapActive` / URL gate —
            // which is exactly what the #871 fix turns on for an agent pane. On the
            // pre-fix surface the hook is null (no scanner) so these taps do
            // nothing. The hook reads the overlay's PUBLISHED snapshot, which lands
            // one recompose after the off-main scan completes, so poll until the
            // production hook resolves the tap (returns true) before asserting the
            // host callback fired — this is still the production gesture path, just
            // waited on to avoid a publish/recompose race.
            // Tap until the host callback records the expected target. Each tap
            // routes through the REAL production gesture path
            // (onSingleTapUp -> the surface-installed onTapMaybeUrl hook); we retry
            // only to ride out the one-recompose lag between the off-main scan
            // publishing and the hook capturing the fresh snapshot.
            suspend fun tapUntilRecorded(x: Float, y: Float, recorded: () -> Boolean) {
                withTimeout(5_000) {
                    while (!recorded()) {
                        // Let Compose flush the recomposition that installs the
                        // overlay's freshly-published snapshot into the production
                        // tap hook BEFORE we tap — otherwise the tight test loop
                        // starves the recomposer and the hook keeps its initial
                        // (empty) snapshot. On a real device recomposition runs
                        // freely between human taps; this only paces the test.
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
            tapUntilRecorded(centreX(pngRegion!!, view), centreY(pngRegion, view)) {
                tappedPaths.contains(pngPath)
            }
            tapUntilRecorded(centreX(urlRegion!!, view), centreY(urlRegion, view)) {
                tappedUrls.contains(url)
            }

            assertTrue(
                "#871 ($kindLabel): tapping the file path on an agent pane must reach the host " +
                    "(→ open in file viewer). tappedPaths=$tappedPaths",
                tappedPaths.contains(pngPath),
            )
            assertTrue(
                "#871 ($kindLabel): tapping the URL on an agent pane must reach the host " +
                    "(→ browser). tappedUrls=$tappedUrls",
                tappedUrls.contains(url),
            )

            captureViewport(view, artifactName)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    }

    /**
     * Issue #871 — the LOAD-BEARING ANR-safety assertion: the agent-pane path/URL
     * scan must run OFF the main thread and be debounced (NOT per-frame on Main).
     * If this is violated the #803/#866/#796 ANR comes straight back.
     */
    @Test
    fun agentPaneScanRunsOffMainNotPerFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        // A single-background-thread dispatcher that RECORDS the thread every
        // dispatched block (the off-main regex scan) runs on, so we can prove the
        // scan never executes on the main looper thread.
        val mainThread = Looper.getMainLooper().thread
        val sawMainThreadScan = AtomicBoolean(false)
        val scanThreadName = AtomicReference("")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "issue871-scan") }
        val scanDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                // Swallow post-teardown rejections: the overlay's LaunchedEffect may
                // dispatch one more scan as the test scope cancels. The assertions
                // are already captured; a rejected late dispatch is teardown noise.
                runCatching {
                    executor.execute {
                        val t = Thread.currentThread()
                        scanThreadName.set(t.name)
                        if (t === mainThread) sawMainThreadScan.set(true)
                        block.run()
                    }
                }
            }
        }

        // Count the off-main scan completions (each onFilePathsChanged/initial scan).
        val scanCount = AtomicLong(0L)
        // Count the RAW render ticks the burst produces (the per-frame budget the
        // scan must NOT track). Collected off-main so it never competes for Main.
        val rawTickCount = AtomicLong(0L)
        val rawScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rawJob = rawScope.launch { state.renderRequests.collect { rawTickCount.incrementAndGet() } }

        val view = buildLaidOutView(state)

        try {
            compose.setContent {
                AgentPaneAffordanceOverlay(
                    view = view,
                    renderRequests = state.renderRequests,
                    onFilePathsChanged = { scanCount.incrementAndGet() },
                    onUrlsChanged = {},
                    scanDispatcher = scanDispatcher,
                )
            }
            compose.waitForIdle()

            // Tight burst: repaint a screenful of token-packed rows with no
            // inter-chunk delay. The raw render-tick source fires hundreds of
            // times; the conflated off-main scan must service FAR fewer.
            val burstStart = SystemClock.uptimeMillis()
            var chunk = 0
            while (SystemClock.uptimeMillis() - burstStart < BURST_DURATION_MS) {
                stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                chunk += 1
            }
            SystemClock.sleep(SETTLE_MS)
            instrumentation.waitForIdleSync()

            val scans = scanCount.get()
            val rawTicks = rawTickCount.get()
            rawJob.cancel()
            rawScope.cancel()

            writeTimings(
                instrumentation,
                listOf(
                    "scenario=#871 agent-pane path/URL scan off-main + debounced",
                    "issue=871",
                    "burst_chunks=$chunk",
                    "burst_duration_ms=$BURST_DURATION_MS",
                    "raw_render_request_ticks=$rawTicks",
                    "off_main_scan_publishes=$scans",
                    "scan_thread_name=${scanThreadName.get()}",
                    "saw_main_thread_scan=${sawMainThreadScan.get()}",
                    "expectation=scan runs on a background dispatcher (NOT main); " +
                        "off-main scans bounded FAR below raw_render_request_ticks " +
                        "(conflated/debounced, not per-frame).",
                ),
            )

            // Sanity: the burst must have produced a real render storm, else the
            // bounded-count assertion is vacuous; and the overlay must have scanned.
            assertTrue(
                "burst must produce a real renderRequests storm; rawTicks=$rawTicks",
                rawTicks >= MIN_RAW_TICKS,
            )
            assertTrue(
                "agent-pane overlay must have dispatched at least one off-main scan; " +
                    "scanThread='${scanThreadName.get()}'",
                scanThreadName.get().isNotEmpty(),
            )

            // ---- LOAD-BEARING #1: the scan NEVER ran on the main thread.
            assertFalse(
                "#871/#803/#866: the agent-pane path/URL scan must run OFF the main " +
                    "thread. It executed on thread '${scanThreadName.get()}'; a main-thread " +
                    "scan reintroduces the per-frame ANR.",
                sawMainThreadScan.get(),
            )

            // ---- LOAD-BEARING #2: the number of OFF-main scan publishes is bounded
            // FAR below the raw render-tick count — i.e. debounced/conflated, not
            // per-frame. A per-frame scan would publish on every tick.
            assertTrue(
                "#871/#803/#866: the agent-pane scan must be debounced/conflated, NOT " +
                    "per-frame. Over the burst it published $scans times against $rawTicks raw " +
                    "render ticks; a conflated scan stays far below the raw count. If it tracked " +
                    "rawTicks the per-frame ANR cost would be back.",
                scans <= rawTicks / DEBOUNCE_FACTOR + DEBOUNCE_SLACK,
            )
        } finally {
            executor.shutdownNow()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    private fun buildChunk(i: Int): String {
        val esc = ""
        return buildString {
            append("$esc[H")
            for (row in 0 until VIEWPORT_ROWS) {
                append("$esc[K")
                append("$BURST_MARKER r$row see https://example.com/codex-$i-$row ")
                append("wrote src/out/burst-$i-$row.png done\r\n")
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

    private fun centreY(region: FilePathRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return r.fontLineSpacingAndAscent + (region.row - view.topRow + 0.5f) * r.fontLineSpacing
    }

    private fun centreX(region: UrlRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return (region.startCol + (region.endColExclusive - region.startCol) / 2f) * r.fontWidth
    }

    private fun centreY(region: UrlRegion, view: TerminalView): Float {
        val r = requireNotNull(view.mRenderer)
        return r.fontLineSpacingAndAscent + (region.row - view.topRow + 0.5f) * r.fontLineSpacing
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
            println("ISSUE871_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile(ctx, "$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeTimings(
        instrumentation: android.app.Instrumentation,
        lines: List<String>,
    ) {
        val file = artifactFile(instrumentation.targetContext, "issue871-agent-pane-offmain-timings.txt")
        file.writeText(lines.joinToString("\n") + "\n")
        println("ISSUE871_TIMINGS ${file.absolutePath}")
        for (line in lines) android.util.Log.i(LOG_TAG, "TIMING $line")
    }

    private fun artifactFile(context: android.content.Context, name: String): File {
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue871AgentLinks"
        const val BURST_MARKER = "ISSUE871-BURST"
        const val VIEWPORT_ROWS = 30
        const val BURST_DURATION_MS = 4_000L
        const val SETTLE_MS = 600L
        const val MIN_RAW_TICKS = 40L

        // A conflated/debounced scan services at most ~one per settled frame; over
        // a burst that fires N raw ticks it should publish << N. Require it under
        // N / DEBOUNCE_FACTOR + slack — a per-frame scan (publishes ~= N) clears
        // this only at tiny N, which the burst floor (MIN_RAW_TICKS) rules out.
        const val DEBOUNCE_FACTOR = 4L
        const val DEBOUNCE_SLACK = 20L
    }
}
