package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #803 — REGRESSION PROOF (the maintainer's heavy-colored-diff freeze): a
 * dense Codex colored-diff `%output` burst — walls of truecolor SGR spans, NO
 * composer, NO IME, pure append — must NOT pin the main thread long enough to
 * ANR. The terminal must still render the FINAL diff state correctly (every byte
 * in order, no loss/garble).
 *
 * ## The bug this reproduces (research spike on #803)
 *
 * `85835356` (#796) frame-gated the *render* signal (≤1 repaint/scan per frame),
 * but the **VT parse / append itself** still ran on the main looper UNBOUNDED:
 * each `%output` chunk posted one `MSG_NEW_INPUT`, and the vendored drain handler
 * re-posted `MSG_NEW_INPUT` back-to-back while the 64 KB queue stayed full — a
 * contiguous run of 16 KB `TerminalEmulator.append` (per-byte `processByte`
 * VT/SGR state machine) calls on the main thread with NO frame yield. A dense
 * red/green diff carries 3–10× more VT bytes than visible characters, so the
 * parse cost dominates: the main thread is pinned for multiple seconds →
 * input-dispatch ANR. The render frame-gate from #796 cannot help — the parse is
 * UPSTREAM of the render signal and is O(total bytes) on main.
 *
 * ## How this proves it (deterministic, no SSH / Docker / composer / IME)
 *
 * 1. Compose the PRODUCTION [TerminalSurface] on a real [ComponentActivity] with
 *    a real vendored [TerminalView]. NO composer, NO synthetic IME — this isolates
 *    the APPEND/parse cost (the residual #796 H3 reviewer isolated as #803), not
 *    the render-scan or keyboard-up amplifier.
 * 2. Drive a dense colored-diff burst through the real `attachExternalProducer`
 *    bridge: many `%output`-sized chunks, each a screenful of diff rows where
 *    EVERY visible char is wrapped in a truecolor SGR run
 *    (`ESC[38;2;r;g;bm … ESC[0m`) — thousands of SGR spans per burst, the exact
 *    heavy-colored-diff shape. Fed off-main (the production `%output` path), so the
 *    emulator append runs on the UI thread via the Termux main-looper handler.
 * 3. WHILE the burst runs, schedule periodic "ping" Runnables on the MAIN looper
 *    and measure how long each waits before it executes. A main thread blocked by
 *    the unbounded back-to-back VT parse inflates that latency; the #803
 *    frame-budgeted [com.pocketshell.core.terminal.bridge.MainThreadDrainScheduler]
 *    keeps it bounded (≤ one per-frame parse budget per turn, then a frame yield).
 *    The load-bearing assertion is that the MAX observed main-thread stall stays
 *    under [MAX_MAIN_THREAD_STALL_MS] (well under the 5 s ANR window). NO
 *    `assumeTrue` / `assumeFalse(isRunningOnCi())` on it (process.md F3).
 * 4. Assert the FINAL rendered transcript is correct — the last diff marker is
 *    present and every emitted row landed in order — proving the frame-budgeted
 *    drain preserved byte ORDER and the FINAL byte (no #651/#658 garble regression).
 *
 * On the pre-fix bridge (unbounded re-post) the same burst pins the main thread
 * for seconds and the max stall blows past the budget → RED. With the
 * frame-budgeted drain the stall stays bounded AND the final state is correct →
 * GREEN. The [com.pocketshell.core.terminal.bridge.MainThreadDrainSchedulerTest]
 * + [com.pocketshell.core.terminal.bridge.MainThreadDrainBudgetTest] JVM unit
 * tests are the fast siblings that prove the batching contract in milliseconds;
 * this is the on-device acceptance.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue803-append-burst-viewport.png` + `-visible-terminal.txt`
 *  - `issue803-append-burst-timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class CodexAppendBurstMainThreadProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun denseColoredDiffAppendBurstKeepsMainThreadResponsiveAndRendersFinalState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        try {
            compose.setContent {
                // No composer, no IME — isolate the append/parse cost. URL/scan
                // overlays are deliberately OFF so the only main-thread cost the
                // burst drives is the VT parse + the (already frame-gated) repaint.
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // ---- Drive the dense colored-diff burst + measure main-thread stall.
            val mainHandler = Handler(Looper.getMainLooper())
            val maxStallMs = AtomicLong(0L)
            val pingCount = AtomicLong(0L)
            val pingActive = AtomicBoolean(true)
            fun schedulePing() {
                val scheduledAt = SystemClock.uptimeMillis()
                mainHandler.post {
                    val latency = SystemClock.uptimeMillis() - scheduledAt
                    if (latency > maxStallMs.get()) maxStallMs.set(latency)
                    pingCount.incrementAndGet()
                    if (pingActive.get()) {
                        // Reschedule with NO delay so each ping competes HEAD-TO-HEAD
                        // with the bridge's drain continuations for the main looper —
                        // the input-dispatch shape. On the pre-fix path the drain
                        // re-posts MSG_NEW_INPUT with zero delay too, so a sustained
                        // flood lets the drain monopolize and the ping latency tracks
                        // the contiguous append run; the #803 scheduler's postDelayed
                        // frame yield guarantees the ping a gap each frame.
                        mainHandler.post(::schedulePing)
                    }
                }
            }
            schedulePing()

            // A TIGHT (no inter-chunk delay) run of dense colored-diff repaints.
            // Each chunk re-homes the cursor and repaints a screenful where every
            // visible char is wrapped in a truecolor SGR run, so the emulator must
            // parse thousands of SGR spans on the main thread. Bound by wall-clock
            // (not chunk count) so the measurement is emulator-speed-independent.
            var sgrSpansEmitted = 0L
            val burstStartedAt = SystemClock.uptimeMillis()
            var chunk = 0
            var lastChunk = 0
            while (SystemClock.uptimeMillis() - burstStartedAt < BURST_DURATION_MS) {
                val (text, spans) = buildColoredDiffChunk(chunk)
                stdout.emit(text.toByteArray(Charsets.US_ASCII))
                sgrSpansEmitted += spans
                lastChunk = chunk
                chunk += 1
            }
            // Emit a UNIQUE final marker line so we can prove the FINAL byte landed
            // (final-state correctness / no-loss).
            stdout.emit(finalMarkerLine(lastChunk).toByteArray(Charsets.US_ASCII))

            // Let the tail of the burst drain across its budgeted frames and the
            // settled final frame paint.
            SystemClock.sleep(SETTLE_MS)
            val burstDurationMs = SystemClock.uptimeMillis() - burstStartedAt
            pingActive.set(false)

            captureViewport(view, "issue803-append-burst")
            val transcript = visibleTerminalText(view)

            writeTimings(
                instrumentation,
                lines = listOf(
                    "scenario=dense-colored-diff-%output-append-burst (NO composer, NO IME) -> main-thread responsiveness",
                    "issue=803",
                    "burst_chunks_emitted=$chunk",
                    "sgr_spans_emitted=$sgrSpansEmitted",
                    "burst_duration_target_ms=$BURST_DURATION_MS",
                    "viewport_rows_per_chunk=$VIEWPORT_ROWS",
                    "burst_duration_ms=$burstDurationMs",
                    "ping_interval_ms=$PING_INTERVAL_MS",
                    "ping_count=${pingCount.get()}",
                    "max_main_thread_stall_ms=${maxStallMs.get()}",
                    "max_main_thread_stall_budget_ms=$MAX_MAIN_THREAD_STALL_MS",
                    "anr_window_ms=5000",
                    "final_marker=${finalMarker(lastChunk)}",
                    "final_marker_present=${transcript.contains(finalMarker(lastChunk))}",
                    "expectation=RED on pre-fix unbounded MSG_NEW_INPUT re-post (append pins main " +
                        "for seconds, max stall blows past budget); GREEN with the #803 " +
                        "frame-budgeted MainThreadDrainScheduler (stall bounded, final state correct)",
                ),
            )

            Log.i(
                LOG_TAG,
                "#803 dense-colored-diff append burst: chunks=$chunk sgrSpans=$sgrSpansEmitted " +
                    "maxStall=${maxStallMs.get()}ms pings=${pingCount.get()} " +
                    "burstDuration=${burstDurationMs}ms finalMarkerPresent=" +
                    "${transcript.contains(finalMarker(lastChunk))}",
            )

            // Sanity: the burst must have emitted a real storm of SGR spans,
            // otherwise the responsiveness measurement is vacuous.
            assertTrue(
                "burst must emit a real multi-thousand-SGR-span storm; sgrSpans=$sgrSpansEmitted " +
                    "(needs >= $MIN_SGR_SPANS to be a meaningful #803 test)",
                sgrSpansEmitted >= MIN_SGR_SPANS,
            )
            // Sanity: we must have actually sampled the main thread during the burst.
            assertTrue(
                "main-thread ping sampler must have run during the burst; pings=${pingCount.get()}",
                pingCount.get() >= MIN_PINGS,
            )

            // ---- CORRECTNESS (no #651/#658 ordering/garble regression): the FINAL
            // byte of the burst must have landed — the unique final marker is the
            // last thing emitted, so its presence proves the frame-budgeted drain
            // preserved byte order and parsed the whole queue to the end (no loss,
            // no reorder). If the screen were blank or truncated the stall number
            // would be meaningless.
            assertTrue(
                "#803: the FINAL diff state must render — the frame-budgeted drain must " +
                    "parse every byte in order to the end (no lost/garbled/reordered output). " +
                    "Final marker '${finalMarker(lastChunk)}' missing from transcript " +
                    "(length=${transcript.length}).",
                transcript.contains(finalMarker(lastChunk)),
            )

            // ---- LOAD-BEARING assertion: the dense colored-diff append burst must
            // NOT stall the main thread past the budget. On the pre-fix unbounded
            // MSG_NEW_INPUT re-post the per-byte SGR parse runs back-to-back on main
            // for seconds → max stall blows past the budget → RED. With the #803
            // frame-budgeted scheduler each main-thread turn parses at most one
            // per-frame byte budget then yields, so the looper stays responsive
            // between turns → GREEN. NO assumeTrue / CI-skip on this assertion (F3).
            assertTrue(
                "#803: a dense colored-diff %output append burst must NOT stall the main " +
                    "thread past ${MAX_MAIN_THREAD_STALL_MS}ms (5s ANR window). Observed max " +
                    "stall=${maxStallMs.get()}ms over ${pingCount.get()} pings across a " +
                    "${burstDurationMs}ms burst of $sgrSpansEmitted SGR spans. FAILS on the " +
                    "pre-fix unbounded VT-append drain; GREEN with the frame-budgeted scheduler.",
                maxStallMs.get() <= MAX_MAIN_THREAD_STALL_MS,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * A dense colored-diff alt-screen repaint: home the cursor, then write a
     * screenful of diff rows where every visible char is wrapped in a truecolor
     * SGR run (red for `-` rows, green for `+` rows). Returns the wire text and
     * the number of SGR spans it contains — the heavy per-byte VT parse cost the
     * append burst multiplies. ESC is the VT control byte, written as the Kotlin escape so the source stays editable.
     */
    private fun buildColoredDiffChunk(i: Int): Pair<String, Int> {
        val esc = "\u001B"
        var spans = 0
        val text = buildString {
            append("$esc[H") // cursor home (alt-screen redraw shape)
            for (row in 0 until VIEWPORT_ROWS) {
                append("$esc[K") // clear to end of line
                val removed = (row % 2 == 0)
                // truecolor red (removed) / green (added) — the heavy SGR shape.
                val color = if (removed) "$esc[38;2;220;50;47m" else "$esc[38;2;80;200;120m"
                val sign = if (removed) "-" else "+"
                append("$BURST_MARKER ")
                // Wrap EACH token in its own SGR run + reset so a single row holds
                // many SGR spans (the dense diff shape), not one span for the line.
                for (tok in 0 until TOKENS_PER_ROW) {
                    append(color)
                    append("$sign tok-$i-$row-$tok ")
                    append("$esc[0m")
                    spans += 2 // the set + the reset
                }
                append("\r\n")
            }
        }
        return text to spans.toLong().toInt()
    }

    private fun finalMarker(lastChunk: Int): String = "$FINAL_MARKER_PREFIX$lastChunk-DONE"

    private fun finalMarkerLine(lastChunk: Int): String {
        val esc = "\u001B"
        // A plain (uncolored) unique final line on its own row — unambiguous to
        // assert against the transcript.
        return "${esc}[K${finalMarker(lastChunk)}\r\n"
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
            println("ISSUE803_BURST_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile(ctx, "$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeTimings(
        instrumentation: android.app.Instrumentation,
        lines: List<String>,
    ) {
        val file = artifactFile(instrumentation.targetContext, "issue803-append-burst-timings.txt")
        file.writeText(lines.joinToString("\n") + "\n")
        println("ISSUE803_BURST_TIMINGS ${file.absolutePath}")
        for (line in lines) Log.i(LOG_TAG, "TIMING $line")
    }

    private fun artifactFile(context: android.content.Context, name: String): File {
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue803AppendBurst"
        const val BURST_MARKER = "ISSUE803-BURST"
        const val FINAL_MARKER_PREFIX = "ISSUE803-FINAL-"

        // Rows per chunk and tokens (each its own SGR span pair) per row. A full
        // phone viewport is ~30 rows; 8 tokens/row × 30 rows × 2 spans = 480 SGR
        // spans per chunk, so a multi-second burst emits many thousands.
        const val VIEWPORT_ROWS = 30
        const val TOKENS_PER_ROW = 8

        // The burst: a TIGHT (no inter-chunk delay) dense-colored-diff repaint
        // storm held for this wall-clock duration. ~3.5s is long enough that the
        // pre-fix unbounded VT-append drain pins the main looper and the max ping
        // stall blows past the budget, while the frame-budgeted scheduler services
        // at most one per-frame parse budget per turn and stays bounded.
        const val BURST_DURATION_MS = 3_500L

        // Drain tail (across budgeted frames) + final-frame settle. Generous so
        // the whole queue + the final marker drain even under the paced budget.
        const val SETTLE_MS = 1_500L

        const val PING_INTERVAL_MS = 16L

        // The responsiveness budget — well under the 5s ANR window so a regression
        // is caught long before a real ANR. The frame-budgeted drain keeps each
        // main-thread turn bounded and yields between turns, so a sustained
        // multi-hundred-ms stall already indicates the unbounded back-to-back parse
        // is back.
        const val MAX_MAIN_THREAD_STALL_MS = 1_000L

        const val MIN_PINGS = 20L

        // The burst must emit a real multi-thousand-SGR-span storm for the test to
        // be meaningful (the heavy-colored-diff shape). A genuine ~3.5s burst at
        // 480 spans/chunk fires many thousands; require a healthy floor.
        const val MIN_SGR_SPANS = 5_000L
    }
}
