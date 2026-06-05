package com.pocketshell.core.terminal.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalParserRenderBenchmarkTest {

    private data class Fixture(
        val name: String,
        val bytes: ByteArray,
        val columns: Int = 80,
        val rows: Int = 24,
        val finalMarker: String = "PS_BENCH_FINAL",
        val validator: (TerminalEmulator) -> Unit = {},
    )

    private data class BenchmarkResult(
        val name: String,
        val bytes: Int,
        val batches: Int,
        val throughputMbPerSec: Double,
        val p95BatchMs: Double,
        val maxBatchMs: Double,
        val finalTranscriptTail: String,
    )

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    @Test
    fun parserRenderFixturesEmitBaselineArtifactsAndPreserveFinalMarkers() {
        val results = fixtures().map { fixture -> runFixture(fixture) }
        val report = writeReport(results)

        assertTrue(
            "terminal parser/render benchmark report should be emitted: ${report.absolutePath}",
            report.isFile,
        )

        if (System.getProperty(ENFORCE_BUDGETS_PROPERTY).toBoolean()) {
            enforceBudgets(results)
        }
    }

    /**
     * Dirty-region win (PocketShell #469). Mirrors the live per-frame cadence: a small
     * chunk is appended then rendered, repeatedly. Compares the production dirty path
     * ({@link TerminalRenderer#peekDirtyRows} — the rows TerminalView invalidates and
     * repaints) against a forced full-repaint baseline.
     *
     * <p>The gate is the DETERMINISTIC repainted-row work summed over all frames — a
     * timing-free, metric-independent proxy for the redraw cost the issue targets
     * (Robolectric's shadow Canvas does not model rasterization, so wall-clock is only
     * recorded, not gated).
     *
     * <p>Two regimes, both asserted:
     *  - **in-place rewrite** (agent spinner / status line — the realistic heavy
     *    interactive-output hot path of #172/#259/#457): only the rewritten row changes,
     *    so the dirty path repaints ~1 row/frame vs the full grid — an order-of-magnitude
     *    reduction. Gated hard (>= 8x).
     *  - **pure append scroll** (`yes`/`cat biglog`): every visible row's CONTENT shifts
     *    up one row each frame, so by logical-row identity every row legitimately changed.
     *    This renderer does not blit the canvas, so a scroll correctly repaints all rows;
     *    we assert NO REGRESSION (dirty work <= full) and rely on the oracle + device
     *    workbench to prove no row is blanked.
     */
    @Test
    fun dirtyRegionRenderingBeatsFullRepaintOnHeavyOutput() {
        val scenarios = listOf(
            "append_flood" to renderCadenceLines(2000) { i ->
                "build log line $i: compiling module-$i ... ok in ${i % 97}ms\r\n"
            },
            "spinner_rewrite" to renderCadenceLines(2000) { i ->
                val g = spinnerGlyph(i)
                "\r[K$g task progress ${i % 100}% token-${i * 13}"
            },
        )

        val report = StringBuilder("dirty-region render micro-benchmark (#469)\n")
        val results = HashMap<String, Pair<RenderCadenceResult, RenderCadenceResult>>()
        scenarios.forEach { (name, frames) ->
            val full = measureRenderCadence(name, frames, forceFull = true)
            val dirty = measureRenderCadence(name, frames, forceFull = false)
            results[name] = full to dirty
            val rowReduction = full.paintedRows.toDouble() / dirty.paintedRows.coerceAtLeast(1)
            report.appendLine(
                "$name: full repainted-rows=${full.paintedRows} dirty repainted-rows=${dirty.paintedRows} " +
                    "reduction=${format(rowReduction)}x | " +
                    "full p95=${format(full.p95RenderMs)}ms dirty p95=${format(dirty.p95RenderMs)}ms",
            )
        }

        // In-place rewrite (the realistic heavy interactive agent-output hot path): the
        // dirty path must repaint at least ~8x fewer rows than the full grid repaint.
        // Deterministic, so stable on Robolectric's zero-metric shadow Canvas.
        val (spinFull, spinDirty) = results.getValue("spinner_rewrite")
        val spinReduction = spinFull.paintedRows.toDouble() / spinDirty.paintedRows.coerceAtLeast(1)
        assertTrue(
            "spinner_rewrite repainted-row reduction ${format(spinReduction)}x must be >= 8x: $report",
            spinReduction >= 8.0,
        )
        // Append scroll legitimately changes every row, so assert no regression only.
        val (appFull, appDirty) = results.getValue("append_flood")
        assertTrue(
            "append_flood dirty repainted-rows ${appDirty.paintedRows} must not exceed full ${appFull.paintedRows}: $report",
            appDirty.paintedRows <= appFull.paintedRows,
        )

        val outputDir = if (File("shared/core-terminal").isDirectory) {
            File("shared/core-terminal/build/reports/terminal-benchmarks")
        } else {
            File("build/reports/terminal-benchmarks")
        }
        outputDir.mkdirs()
        File(outputDir, "dirty-region-render.txt").writeText(report.toString())
    }

    private data class RenderCadenceResult(
        val p95RenderMs: Double,
        val maxRenderMs: Double,
        val paintedRows: Long,
    )

    private fun renderCadenceLines(count: Int, line: (Int) -> String): List<ByteArray> =
        (0 until count).map { line(it).toByteArray(Charsets.UTF_8) }

    private fun measureRenderCadence(
        name: String,
        frames: List<ByteArray>,
        forceFull: Boolean,
    ): RenderCadenceResult {
        val terminal = TerminalEmulator(
            SinkOutput, 80, 24, CELL_WIDTH_PX, CELL_HEIGHT_PX,
            TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX, null,
        )
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rows = 24
        val dirtyScratch = BooleanArray(rows)

        // Warm up.
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1)

        var invalidatedRows = 0L
        val timingsNs = ArrayList<Long>(frames.size)
        frames.forEach { chunk ->
            terminal.append(chunk, chunk.size)
            val started = System.nanoTime()
            if (forceFull) {
                // Baseline: a full (unclipped) repaint of the whole grid each frame —
                // the platform redraws all rows, so the per-frame redraw cost is `rows`.
                renderer.invalidateDirtyCache()
                renderer.render(terminal, canvas, 0, -1, -1, -1, -1)
                invalidatedRows += rows
            } else {
                // Dirty path: mirror TerminalView — only the rows peekDirtyRows reports
                // are invalidated and repainted (PEEK_FULL = all rows). This row count
                // is the timing-free measure of redraw work and is metric-independent,
                // so it is stable on Robolectric's zero-metric shadow Canvas.
                val result = renderer.peekDirtyRows(terminal, 0, -1, -1, -1, -1, dirtyScratch)
                val dirtyCount = when (result) {
                    TerminalRenderer.PEEK_FULL -> rows
                    TerminalRenderer.PEEK_NONE -> 0
                    else -> result
                }
                invalidatedRows += dirtyCount
                // Still render so the renderer's cache advances exactly like production.
                renderer.render(terminal, canvas, 0, -1, -1, -1, -1)
            }
            timingsNs += System.nanoTime() - started
        }
        val sorted = timingsNs.sorted()
        val p95Index = ceil(sorted.size * 0.95).toInt().coerceAtLeast(1) - 1
        return RenderCadenceResult(
            p95RenderMs = sorted[p95Index].toDouble() / NANOS_PER_MILLI,
            maxRenderMs = sorted.last().toDouble() / NANOS_PER_MILLI,
            paintedRows = invalidatedRows,
        )
    }

    private fun runFixture(fixture: Fixture): BenchmarkResult {
        val terminal = TerminalEmulator(
            SinkOutput,
            fixture.columns,
            fixture.rows,
            CELL_WIDTH_PX,
            CELL_HEIGHT_PX,
            TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX,
            null,
        )
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val timingsNs = mutableListOf<Long>()

        // Warm up Robolectric's graphics shadows and renderer path before timing fixture bytes.
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1)

        var offset = 0
        while (offset < fixture.bytes.size) {
            val count = minOf(BATCH_SIZE_BYTES, fixture.bytes.size - offset)
            val batch = fixture.bytes.copyOfRange(offset, offset + count)
            val started = System.nanoTime()
            terminal.append(batch, batch.size)
            renderer.render(terminal, canvas, 0, -1, -1, -1, -1)
            timingsNs += System.nanoTime() - started
            offset += count
        }

        val transcript = terminal.screen.transcriptText
        assertTrue(
            "${fixture.name} transcript must end with final marker '${fixture.finalMarker}', tail='${transcript.takeLast(200)}'",
            transcript.endsWith(fixture.finalMarker),
        )
        fixture.validator(terminal)

        val sortedTimings = timingsNs.sorted()
        val p95Index = ceil(sortedTimings.size * 0.95).toInt().coerceAtLeast(1) - 1
        val totalSeconds = timingsNs.sum().toDouble() / NANOS_PER_SECOND
        val throughput = fixture.bytes.size.toDouble() / BYTES_PER_MIB / totalSeconds
        return BenchmarkResult(
            name = fixture.name,
            bytes = fixture.bytes.size,
            batches = timingsNs.size,
            throughputMbPerSec = throughput,
            p95BatchMs = sortedTimings[p95Index].toDouble() / NANOS_PER_MILLI,
            maxBatchMs = sortedTimings.last().toDouble() / NANOS_PER_MILLI,
            finalTranscriptTail = transcript.takeLast(160),
        )
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture(
            name = "plain_10mb_stream",
            bytes = plain10MbStream(),
            finalMarker = "PS_BENCH_FINAL_plain_10mb_stream",
        ),
        Fixture(
            name = "ansi_spinner_rewrite_heavy",
            bytes = ansiSpinnerRewriteHeavy(),
            finalMarker = "PS_BENCH_FINAL_ansi_spinner_rewrite_heavy",
            validator = { terminal ->
                val transcript = terminal.screen.transcriptText
                assertFalse("stale spinner text leaked into transcript", transcript.contains("very long stale suffix"))
            },
        ),
        Fixture(
            name = "wrapped_10k_lines",
            bytes = wrapped10kLines(),
            finalMarker = "PS_BENCH_FINAL_wrapped_10k_lines",
            validator = { terminal ->
                assertTrue(
                    "wrapped-line fixture lost late content",
                    terminal.screen.transcriptText.contains("wrapped-9999"),
                )
            },
        ),
        Fixture(
            name = "unicode_wide_characters",
            bytes = unicodeWideCharacters(),
            finalMarker = "PS_BENCH_FINAL_unicode_wide_characters",
            validator = { terminal ->
                val transcript = terminal.screen.transcriptText
                assertTrue("unicode fixture lost CJK wide text", transcript.contains("界面"))
                assertTrue("unicode fixture lost emoji text", transcript.contains("🙂"))
            },
        ),
        Fixture(
            name = "alt_screen_redraw",
            bytes = altScreenRedraw(),
            finalMarker = "PS_BENCH_FINAL_alt_screen_redraw",
            validator = { terminal ->
                assertTrue("alt-screen redraw fixture should finish on the alternate buffer", terminal.isAlternateBufferActive)
            },
        ),
    )

    private fun plain10MbStream(): ByteArray {
        val marker = "PS_BENCH_FINAL_plain_10mb_stream"
        val out = ByteArrayOutputStream(PLAIN_STREAM_BYTES + 1024)
        var i = 0
        while (out.size() < PLAIN_STREAM_BYTES - marker.length - 1) {
            out.writeAscii("plain line ${i++} abcdefghijklmnopqrstuvwxyz 0123456789\n")
        }
        out.writeAscii(marker)
        return out.toByteArray()
    }

    private fun ansiSpinnerRewriteHeavy(): ByteArray {
        val marker = "PS_BENCH_FINAL_ansi_spinner_rewrite_heavy"
        val out = ByteArrayOutputStream()
        repeat(20_000) { i ->
            out.writeUtf8("\r\u001B[K${spinnerGlyph(i)} task-$i very long stale suffix with changing tokens ${i * 17}")
            out.writeUtf8("\r\u001B[2Gstep-${i % 100}")
            out.writeUtf8("\r\u001B[K${spinnerGlyph(i + 1)} ok ${i % 1000}")
        }
        out.writeUtf8("\r\u001B[K$marker")
        return out.toByteArray()
    }

    private fun wrapped10kLines(): ByteArray {
        val marker = "PS_BENCH_FINAL_wrapped_10k_lines"
        val out = ByteArrayOutputStream()
        repeat(10_000) { i ->
            out.writeAscii(
                "wrapped-$i " +
                    "0123456789abcdefghijklmnopqrstuvwxyz".repeat(4) +
                    "\n",
            )
        }
        out.writeAscii(marker)
        return out.toByteArray()
    }

    private fun unicodeWideCharacters(): ByteArray {
        val marker = "PS_BENCH_FINAL_unicode_wide_characters"
        val out = ByteArrayOutputStream()
        repeat(5_000) { i ->
            out.writeUtf8("unicode-$i 界面🙂 コンソール e\u0301 한글 wide\n")
        }
        out.writeUtf8(marker)
        return out.toByteArray()
    }

    private fun altScreenRedraw(): ByteArray {
        val marker = "PS_BENCH_FINAL_alt_screen_redraw"
        val out = ByteArrayOutputStream()
        out.writeUtf8("\u001B[?1049h")
        repeat(2_000) { frame ->
            out.writeUtf8("\u001B[H\u001B[2J")
            repeat(24) { row ->
                out.writeUtf8("frame=$frame row=$row ${"#".repeat((row + frame) % 40)}\r\n")
            }
        }
        out.writeUtf8("\u001B[H\u001B[2J$marker")
        return out.toByteArray()
    }

    private fun writeReport(results: List<BenchmarkResult>): File {
        val outputDir = if (File("shared/core-terminal").isDirectory) {
            File("shared/core-terminal/build/reports/terminal-benchmarks")
        } else {
            File("build/reports/terminal-benchmarks")
        }
        outputDir.mkdirs()
        val report = File(outputDir, "parser-render-baseline.json")
        report.writeText(buildJsonReport(results))
        return report
    }

    private fun buildJsonReport(results: List<BenchmarkResult>): String = buildString {
        appendLine("{")
        appendLine("  \"benchmark\": \"terminal-parser-render\",")
        appendLine("  \"batch_bytes\": $BATCH_SIZE_BYTES,")
        appendLine("  \"threshold_mode\": \"recorded_by_default_with_adjusted_opt_in_local_enforcement\",")
        appendLine("  \"local_enforcement_property\": \"$ENFORCE_BUDGETS_PROPERTY\",")
        appendLine("  \"ci_ramp_up_plan\": \"Keep CI recording-only until issue #339 lands production throughput changes; then collect five CI baselines, set enforced budgets to the slower of the current suggested budgets and 75% of observed p50 throughput with p95/max batch budgets at 150% of observed p95/max, and enable $ENFORCE_BUDGETS_PROPERTY in CI once two consecutive main runs pass.\",")
        appendLine("  \"issue_suggested_budgets\": {")
        appendLine("    \"plain_10mb_stream_min_throughput_mb_per_sec\": 25.0,")
        appendLine("    \"ansi_spinner_rewrite_heavy_min_throughput_mb_per_sec\": 8.0,")
        appendLine("    \"p95_batch_max_ms\": 16.0,")
        appendLine("    \"max_batch_max_ms\": 50.0")
        appendLine("  },")
        appendLine("  \"adjusted_local_budgets\": {")
        appendLine("    \"plain_10mb_stream_min_throughput_mb_per_sec\": $PLAIN_MIN_MB_PER_SEC,")
        appendLine("    \"ansi_spinner_rewrite_heavy_min_throughput_mb_per_sec\": $ANSI_MIN_MB_PER_SEC,")
        appendLine("    \"p95_batch_max_ms\": $P95_BATCH_BUDGET_MS,")
        appendLine("    \"max_batch_max_ms\": $MAX_BATCH_BUDGET_MS")
        appendLine("  },")
        appendLine("  \"results\": [")
        results.forEachIndexed { index, result ->
            appendLine("    {")
            appendLine("      \"name\": \"${result.name}\",")
            appendLine("      \"bytes\": ${result.bytes},")
            appendLine("      \"batches\": ${result.batches},")
            appendLine("      \"throughput_mb_per_sec\": ${format(result.throughputMbPerSec)},")
            appendLine("      \"p95_batch_ms\": ${format(result.p95BatchMs)},")
            appendLine("      \"max_batch_ms\": ${format(result.maxBatchMs)},")
            appendLine("      \"final_transcript_tail\": \"${jsonEscape(result.finalTranscriptTail)}\"")
            append("    }")
            if (index != results.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun enforceBudgets(results: List<BenchmarkResult>) {
        val byName = results.associateBy { it.name }
        assertTrue(
            "plain 10MB throughput ${byName.getValue("plain_10mb_stream").throughputMbPerSec} MB/s < $PLAIN_MIN_MB_PER_SEC MB/s",
            byName.getValue("plain_10mb_stream").throughputMbPerSec >= PLAIN_MIN_MB_PER_SEC,
        )
        assertTrue(
            "ANSI-heavy throughput ${byName.getValue("ansi_spinner_rewrite_heavy").throughputMbPerSec} MB/s < $ANSI_MIN_MB_PER_SEC MB/s",
            byName.getValue("ansi_spinner_rewrite_heavy").throughputMbPerSec >= ANSI_MIN_MB_PER_SEC,
        )
        results.forEach { result ->
            assertTrue(
                "${result.name} p95 batch ${result.p95BatchMs} ms > $P95_BATCH_BUDGET_MS ms",
                result.p95BatchMs < P95_BATCH_BUDGET_MS,
            )
            assertTrue(
                "${result.name} max batch ${result.maxBatchMs} ms > $MAX_BATCH_BUDGET_MS ms",
                result.maxBatchMs < MAX_BATCH_BUDGET_MS,
            )
        }
    }

    private fun spinnerGlyph(index: Int): String = when (index % 4) {
        0 -> "|"
        1 -> "/"
        2 -> "-"
        else -> "\\"
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private companion object {
        const val CELL_WIDTH_PX = 13
        const val CELL_HEIGHT_PX = 15
        const val TEXT_SIZE_PX = 28
        const val BATCH_SIZE_BYTES = 64 * 1024
        const val PLAIN_STREAM_BYTES = 10 * 1024 * 1024
        const val BYTES_PER_MIB = 1024.0 * 1024.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000.0
        const val PLAIN_MIN_MB_PER_SEC = 20.0
        const val ANSI_MIN_MB_PER_SEC = 8.0
        const val P95_BATCH_BUDGET_MS = 75.0
        const val MAX_BATCH_BUDGET_MS = 100.0
        const val ENFORCE_BUDGETS_PROPERTY = "pocketshell.terminalBenchmark.enforceBudgets"
    }
}
