package com.pocketshell.core.terminal.ui

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #866 (HIGH-severity ANR) — ON-DEVICE PROOF: attaching a Codex (alt-screen)
 * pane whose `capture-pane -e` seed snapshot is SEVERAL 64 KB chunks must NOT pin
 * the main thread long enough to ANR on the "Attaching…" overlay.
 *
 * ## The bug this reproduces
 *
 * The per-pane attach seeds the new bridge from a `capture-pane` snapshot
 * ([TerminalSurfaceState.appendRemoteOutput] → `SshTerminalBridge.seedThenOpenGate`)
 * on the MAIN thread, so the snapshot paints before live `%output` flows (#468).
 * #829 added a [SshTerminalBridge.SEED_DRAIN_MAX_MILLIS] budget to that inline
 * drain — but only honored it for the FINAL chunk of the feed. A Codex alt-screen
 * snapshot captured with full SGR colour over 200+ scrollback rows is MULTIPLE
 * 64 KB chunks, so every non-final chunk drained FULLY inline (unbudgeted) → the
 * main thread pinned for seconds → the "PocketShell isn't responding" ANR the
 * maintainer hit (green dot, spinner stuck on "Attaching…").
 *
 * ## How this proves it (deterministic, no SSH / Docker)
 *
 * 1. Compose the PRODUCTION [TerminalSurface] on a real [ComponentActivity] with a
 *    real vendored [TerminalView], wired as an agent pane (the Codex shape).
 * 2. `attachExternalProducer(awaitSeed = true)` — the real attach path: it closes
 *    the seed gate so live output buffers behind the snapshot.
 * 3. Sample main-thread responsiveness with a self-rescheduling ping while, ON THE
 *    MAIN THREAD (`runOnMainSync`, the production attach thread), feed a
 *    >MULTI-CHUNK alt-screen `capture-pane` seed via [appendRemoteOutput]. Record
 *    the synchronous duration of that on-main seed feed AND the max main-thread
 *    stall a concurrent ping observed.
 *
 * On the pre-#866 bridge the on-main seed feed runs every non-final chunk's VT
 * parse inline, so the synchronous `appendRemoteOutput` call occupies the main
 * thread for the WHOLE multi-chunk parse (well over the budget; on a real device,
 * seconds) → RED. With the #866 fix the on-main feed stops at the budget on the
 * first chunk and hands the untouched tail to the frame-yielding pump, so the
 * synchronous call returns bounded near [SshTerminalBridge.SEED_DRAIN_MAX_MILLIS]
 * and the seed still paints fully across frames → GREEN.
 *
 * The JVM sibling [com.pocketshell.core.terminal.bridge.SshTerminalBridgeTest]
 * `onMainMultiChunkSeedRespectsTimeBudgetAcrossWholeFeedAndHandsTailToPump` proves
 * the SAME property deterministically in virtual time (inline drain slice count
 * bounded to the budget, not the whole-feed slice count); this is the on-device
 * acceptance that the production attach path no longer blocks.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue866-multichunk-seed-attach-viewport.png` + `-visible-terminal.txt`
 *  - `issue866-multichunk-seed-attach-timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class CodexMultiChunkSeedAttachMainThreadProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multiChunkSeedAttachDoesNotPinTheMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // awaitSeed = true closes the seed gate up front — the real per-pane attach
        // shape that seeds from capture-pane before live %output flows.
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
            awaitSeed = true,
        )

        try {
            compose.setContent {
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                    // The Codex agent-pane shape (#679/#796): scanners gated off.
                    urlsEnabled = true,
                    onFilePathTap = {},
                    engineCommands = setOf("/clear", "/compact", "/model"),
                    onEngineCommandTap = {},
                    affordanceScannersEnabled = false,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // A multi-chunk Codex alt-screen capture-pane snapshot: a dense-SGR
            // full-screen redraw over enough rows to exceed SEVERAL 64 KB queue
            // chunks (the #866 ANR shape). Built once off the main thread.
            val seed = buildMultiChunkAltScreenSeed()
            assertTrue(
                "seed fixture must exceed several 64 KB process-to-terminal chunks " +
                    "(the #866 multi-chunk ANR shape); size=${seed.size}",
                seed.size > 64 * 1024 * 2,
            )

            // Self-rescheduling main-thread ping: records the max latency between
            // when a ping was scheduled and when the looper actually ran it. A main
            // thread pinned by the inline multi-chunk seed parse inflates that gap.
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
                    if (pingActive.get()) mainHandler.postDelayed(::schedulePing, PING_INTERVAL_MS)
                }
            }
            schedulePing()
            // Let a few pings establish the idle baseline before the seed lands.
            SystemClock.sleep(100)

            // Feed the multi-chunk seed ON THE MAIN THREAD — the production attach
            // thread (appendRemoteOutput runs on whatever calls it; on-device that is
            // the main thread). The DETERMINISTIC discriminator (issue #814: NOT a
            // wall-clock ceiling, which is fragile on a fast/slow emulator — base
            // measured only 326–434ms on this fast x86 AVD): capture, STILL ON THE
            // MAIN THREAD immediately after the synchronous appendRemoteOutput returns
            // and BEFORE the looper can run any later pump turn, HOW MUCH of the seed
            // has parsed — measured by whether a marker at the MIDDLE of the seed
            // (~chunk 15 of 30) parsed inline.
            //
            //  - Pre-#866: every chunk's VT parse runs INLINE inside this one
            //    synchronous call (the ANR), so by the time it returns ~29 of 30
            //    chunks are parsed — the MIDDLE marker is present (midParsedInline ==
            //    true) → RED. (NB: the very last chunk is the only one #829 already
            //    deferred via its final-chunk handoff, so a *last*-row probe does NOT
            //    discriminate — the middle does.)
            //  - #866 fix: the on-main feed stops at the SEED_DRAIN_MAX_MILLIS budget
            //    on the FIRST chunk and hands the whole untouched tail to the
            //    frame-yielding pump, so only ~chunk 1 parsed inline — the MIDDLE
            //    marker is NOT present (midParsedInline == false) → GREEN. The whole
            //    tail then paints across later frames (completeness asserted below).
            //
            // This is a pure control-flow fact (did the main thread parse the bulk of
            // a multi-chunk seed in ONE synchronous turn, or defer the tail?), immune
            // to how fast the emulator runs. The seed stays on the NORMAL screen (no
            // alt-screen enter) ONLY so the parsed rows accumulate in scrollback and
            // the probe is readable — the on-main multi-chunk parse cost that causes
            // the ANR is identical whether or not the alt-screen buffer is active.
            val onMainFeedDurationMs = AtomicLong(0L)
            val midParsedInline = AtomicBoolean(false)
            val lastParsedInline = AtomicBoolean(false)
            val inlineTranscriptLen = AtomicLong(0L)
            instrumentation.runOnMainSync {
                val startedAt = SystemClock.uptimeMillis()
                state.appendRemoteOutput(seed)
                onMainFeedDurationMs.set(SystemClock.uptimeMillis() - startedAt)
                // Read the parsed transcript synchronously, still on this main-thread
                // turn (no looper gap), so a deferred tail cannot have painted yet.
                val inlineText = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
                inlineTranscriptLen.set(inlineText.length.toLong())
                midParsedInline.set(inlineText.contains(SEED_MID_MARKER))
                lastParsedInline.set(inlineText.contains(SEED_LAST_MARKER))
            }

            // Let the handed-off tail drain across frames + the final frame settle.
            // Drain until the last row paints (the pump pacing is frame-bounded), so a
            // slow emulator does not falsely fail the completeness check below.
            withTimeout(TAIL_DRAIN_TIMEOUT_MS) {
                while (!visibleTerminalText(view).contains(SEED_LAST_MARKER)) {
                    delay(32)
                }
            }
            SystemClock.sleep(SETTLE_MS)
            pingActive.set(false)

            val feedMs = onMainFeedDurationMs.get()
            val stallMs = maxStallMs.get()
            val transcript = visibleTerminalText(view)
            val fullLen = transcript.length.toLong()
            val inlineLen = inlineTranscriptLen.get()
            // DETERMINISTIC discriminator (issue #814 — count-based, NOT wall-clock):
            // how much of the seed parsed INLINE on the synchronous main-thread turn,
            // measured by the transcript length captured right after appendRemoteOutput
            // returned vs the full transcript after the pump drained. Pre-#866 the
            // WHOLE multi-chunk seed parses inline (the ANR), so inlineLen ≈ fullLen.
            // The fix parses only ~the first chunk inline and pumps the rest, so
            // inlineLen is a small fraction of fullLen. Observed on this x86 AVD:
            // base inlineLen≈125947 ≈ fullLen; fix inlineLen≈1808 (~70x smaller).
            // The bounded scrollback caps fullLen the same way for both, so the RATIO
            // is machine-speed-independent — it measures WHAT parsed, not how long.
            val inlineDeferredTail = inlineLen * INLINE_DEFER_RATIO < fullLen
            // Whether the WHOLE multi-chunk seed eventually parsed through to its last
            // row (completeness). On the normal screen the parsed rows accumulate in
            // scrollback, so the LAST marker being present proves the deferred tail was
            // pumped to completion — not dropped or swallowed (the #468 no-loss
            // guarantee across the handoff).
            val seedPaintedToEnd = transcript.contains(SEED_LAST_MARKER)

            // Write the authoritative artifacts FIRST, before any assertion, so the
            // on-device timing + viewport evidence exists in the run directory even
            // when an assertion fails (process.md "Terminal Artifact Review").
            captureViewport(view, "issue866-multichunk-seed-attach")
            writeTimings(
                instrumentation,
                lines = listOf(
                    "scenario=multi-chunk Codex capture-pane seed -> on-main attach feed",
                    "issue=866",
                    "seed_bytes=${seed.size}",
                    "queue_chunk_bytes=${64 * 1024}",
                    "seed_chunks=${seed.size / (64 * 1024) + 1}",
                    "seed_drain_max_millis=${com.pocketshell.core.terminal.bridge.SshTerminalBridge.SEED_DRAIN_MAX_MILLIS}",
                    "on_main_seed_feed_duration_ms_DIAGNOSTIC=$feedMs",
                    "inline_transcript_length_LOAD_BEARING=$inlineLen",
                    "full_transcript_length=$fullLen",
                    "inline_defer_ratio_threshold=$INLINE_DEFER_RATIO",
                    "inline_deferred_tail_LOAD_BEARING=$inlineDeferredTail",
                    "mid_marker_parsed_inline_DIAGNOSTIC=${midParsedInline.get()}",
                    "last_marker_parsed_inline_DIAGNOSTIC=${lastParsedInline.get()}",
                    "max_main_thread_stall_ms_DIAGNOSTIC=$stallMs",
                    "ping_interval_ms=$PING_INTERVAL_MS",
                    "ping_count=${pingCount.get()}",
                    "seed_painted_to_last_row_after_pump=$seedPaintedToEnd",
                    "anr_window_ms=5000",
                    "pane_type=agent (Codex multi-chunk seed)",
                    "expectation=RED on pre-#866 bridge (every non-final chunk's VT parse " +
                        "runs INLINE in the one synchronous on-main feed, so the WHOLE seed is " +
                        "parsed when appendRemoteOutput returns -> inline_transcript_length ≈ " +
                        "full_transcript_length -> inline_deferred_tail=false); GREEN with the #866 " +
                        "fix (the on-main feed stops at the budget on the FIRST chunk and hands the " +
                        "whole tail to the frame-yielding pump, so only ~chunk 1 is parsed when the " +
                        "call returns -> inline_transcript_length << full_transcript_length -> " +
                        "inline_deferred_tail=true; the tail then paints across later frames). " +
                        "DETERMINISTIC count of WHAT parsed inline, not a wall-clock budget.",
                ),
            )

            Log.i(
                LOG_TAG,
                "#866 multi-chunk seed attach: inlineDeferredTail=$inlineDeferredTail " +
                    "(inlineLen=$inlineLen fullLen=$fullLen ratioThreshold=$INLINE_DEFER_RATIO) " +
                    "onMainFeed(diag)=${feedMs}ms maxStall(diag)=${stallMs}ms pings=${pingCount.get()} " +
                    "seedBytes=${seed.size} paintedToEndAfterPump=$seedPaintedToEnd",
            )

            assertTrue(
                "main-thread ping sampler must have run; pings=${pingCount.get()}",
                pingCount.get() >= MIN_PINGS,
            )

            // Completeness: the pumped tail must have drained to the LAST seed row —
            // otherwise "deferred the tail" could pass vacuously by dropping it.
            assertTrue(
                "the multi-chunk seed must paint through to its LAST row after the pump " +
                    "drains (the deferred tail was not dropped/swallowed); $SEED_LAST_MARKER " +
                    "must be in the transcript. full transcript length=$fullLen",
                seedPaintedToEnd,
            )

            // Sanity: the full seed must actually have produced a large transcript, so
            // the ratio comparison below is not vacuous on a stalled/empty feed.
            assertTrue(
                "the full seed must produce a large transcript for the ratio to be " +
                    "meaningful; full_transcript_length=$fullLen (needs >= $MIN_FULL_TRANSCRIPT)",
                fullLen >= MIN_FULL_TRANSCRIPT,
            )

            // LOAD-BEARING #866 assertion (DETERMINISTIC, issue #814): the synchronous
            // on-main seed feed must NOT parse the BULK of the multi-chunk seed inline
            // — it must DEFER the tail to the frame-yielding pump. We measure WHAT
            // parsed inline (the transcript length captured synchronously right after
            // appendRemoteOutput returned) vs the full transcript after the pump
            // drained. Pre-#866 the entire multi-chunk VT parse ran in this one
            // synchronous main-thread turn (the ANR), so inline ≈ full. The fix stops
            // at the budget on the first chunk and pumps the rest, so inline is a small
            // fraction of full. This is a pure count of parsed bytes — independent of
            // how fast the emulator runs (unlike a wall-clock ceiling, which on this
            // fast x86 AVD measured base at only 294–434ms and did NOT discriminate).
            assertTrue(
                "#866: the on-main multi-chunk seed feed must DEFER its tail off the " +
                    "synchronous main-thread turn (it must NOT parse the bulk inline). " +
                    "inline_transcript_length=$inlineLen must be < full_transcript_length=" +
                    "$fullLen / $INLINE_DEFER_RATIO (=${fullLen / INLINE_DEFER_RATIO}); " +
                    "seed=${seed.size}B over ${seed.size / (64 * 1024) + 1} chunks; " +
                    "on_main_feed(diag)=${feedMs}ms. FAILS on the pre-#866 bridge (the bulk " +
                    "parses inline so inline ≈ full); GREEN with the budget + tail pump " +
                    "(only ~chunk 1 inline, the tail deferred + painted across frames).",
                inlineDeferredTail,
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
     * A multi-chunk Codex alt-screen `capture-pane -e` snapshot: an alt-screen
     * enter + cursor-home + a screenful of dense per-cell SGR-coloured rows,
     * repeated over enough rows to exceed several 64 KB process-to-terminal chunks.
     * The first and last rows carry markers so the test can confirm the WHOLE seed
     * painted (the pumped tail was not dropped).
     */
    private fun buildMultiChunkAltScreenSeed(): ByteArray {
        val esc = ""
        val midRow = SEED_ROWS / 2
        return buildString {
            append("$esc[H")      // cursor home
            for (row in 0 until SEED_ROWS) {
                append("$esc[K")  // clear to end of line
                val marker = when (row) {
                    0 -> SEED_FIRST_MARKER
                    midRow -> SEED_MID_MARKER
                    SEED_ROWS - 1 -> SEED_LAST_MARKER
                    else -> "seed"
                }
                append("$marker r$row ")
                for (col in 0 until SEED_COLS) {
                    val color = 31 + ((row + col) % 7) // SGR 31..37
                    append("$esc[${color}m")
                    append(('a'.code + ((row + col) % 26)).toChar())
                }
                append("$esc[0m\r\n")
            }
        }.toByteArray(Charsets.US_ASCII)
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
        var bitmap: android.graphics.Bitmap? = null
        instrumentation.runOnMainSync {
            if (view.width > 0 && view.height > 0) {
                val b = android.graphics.Bitmap.createBitmap(
                    view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
                )
                view.draw(android.graphics.Canvas(b))
                bitmap = b
            }
        }
        val ctx = instrumentation.targetContext
        bitmap?.let { b ->
            val file = artifactFile(ctx, "$name-viewport.png")
            java.io.FileOutputStream(file).use { out ->
                check(b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE866_SEED_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile(ctx, "$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeTimings(instrumentation: android.app.Instrumentation, lines: List<String>) {
        val file = artifactFile(instrumentation.targetContext, "issue866-multichunk-seed-attach-timings.txt")
        file.writeText(lines.joinToString("\n") + "\n")
        println("ISSUE866_SEED_TIMINGS ${file.absolutePath}")
        for (line in lines) Log.i(LOG_TAG, "TIMING $line")
    }

    private fun artifactFile(context: android.content.Context, name: String): File {
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue866SeedAttach"
        const val SEED_FIRST_MARKER = "ISSUE866-SEED-FIRST"
        const val SEED_MID_MARKER = "ISSUE866-SEED-MID"
        const val SEED_LAST_MARKER = "ISSUE866-SEED-LAST"

        // Rows/cols sized so the dense-SGR seed (per-cell colour escapes) exceeds
        // MANY 64 KB process-to-terminal queue chunks — the multi-chunk Codex
        // alt-screen snapshot that pinned the main thread. ~2600 rows * ~120 cols *
        // ~6 B/cell (SGR + glyph) ≈ 1.9 MB ≈ 30 chunks: a large multi-chunk seed so
        // the deferred-tail control-flow discriminator is unambiguous (pre-#866
        // parses ALL ~30 chunks in the one synchronous turn; the fix defers all but
        // the first). The on-device discriminator is control-flow, not wall-clock,
        // so the exact size only needs to be comfortably multi-chunk.
        const val SEED_ROWS = 2_600
        const val SEED_COLS = 120

        const val PING_INTERVAL_MS = 16L
        const val MIN_PINGS = 5L

        // Final-frame settle after the tail has finished draining.
        const val SETTLE_MS = 500L

        // Bound on how long to wait for the frame-yielding pump to drain the whole
        // deferred tail (~30 chunks paced across frames). Generous so a slow CI
        // swiftshader emulator does not falsely fail the completeness check; far
        // under the test's own timeout. The load-bearing assertion (deferred-tail)
        // is captured synchronously BEFORE this wait, so this only gates completeness.
        const val TAIL_DRAIN_TIMEOUT_MS = 30_000L

        // The inline (synchronous) transcript must be at least this many times
        // SMALLER than the full post-pump transcript for the tail to count as
        // "deferred". Observed: fix inline≈1808 vs full≈125893 (~70x); base
        // inline≈125947 ≈ full (~1x). A 4x threshold separates them with an
        // enormous margin and is robust to machine speed (it compares two byte
        // counts from the same run, not wall-clock time).
        const val INLINE_DEFER_RATIO = 4L

        // The full post-pump transcript must be at least this large for the ratio
        // comparison to be meaningful (guards a vacuous pass on a stalled/empty
        // feed). Full is ~125 K on this fixture; require a comfortable floor.
        const val MIN_FULL_TRANSCRIPT = 40_000L
    }
}
