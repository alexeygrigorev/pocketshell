package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Pixel-level render-correctness guard for issue #259 — terminal rendering
 * garbles lines / rows run together / text mixed.
 *
 * Rendered with the REAL bundled terminal typeface (the face the production
 * app uses) — `Typeface.MONOSPACE` is not used because it would hide any
 * face-specific shaping (ligatures / contextual alternates) the production
 * font could apply. Issue #259 hardens the renderer against that class of
 * defect by disabling `liga`/`clig`/`dlig`/`calt` on the text paint
 * ([TerminalRenderer]'s constructor), so a programming font that visually
 * ligates adjacent glyphs (merging two cells' glyphs into one, the
 * "words mashed together" symptom — e.g. `gthinkingwithout`) cannot reshape
 * the monospace cell grid the emulator positioned.
 *
 * These tests assert the rendered cell grid agrees with the emulator grid:
 *
 *  1. Words separated by single spaces keep their space cells blank — no
 *     neighbouring glyph drifted / ligated into the gap.
 *  2. Ligature-prone sequences (`->`, `==`, `!=`, repeated letters) still land
 *     one glyph per cell with the inter-token spacing preserved.
 *  3. An in-place CR/erase spinner rewrite renders only its final frame with
 *     correct word spacing and no stale tail from an earlier longer frame.
 *
 * NOTE on scope: the emulator/grid itself handles in-place CR / cursor / erase
 * rewrites correctly regardless of the font — proven independently by
 * `StatusSpinnerRewriteGridTest` (pure JVM). These instrumented cases cover
 * the canvas render path, the only PocketShell-modified file in the terminal
 * draw stack.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRendererSpinnerRewriteInstrumentedTest {

    private companion object {
        private const val TEXT_SIZE_PX = 40
        private const val COLUMNS = 48
        private const val ROWS = 4

        /** Distinct ARGB the renderer is highly unlikely to draw. */
        private const val SENTINEL_BACKGROUND: Int = -0xff0102 // 0xFFFF00FE
    }

    private fun terminalTypeface(): Typeface {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return runCatching {
            Typeface.createFromAsset(ctx.applicationContext.assets, TERMINAL_FONT_ASSET_PATH)
        }.getOrNull() ?: Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    /**
     * Words separated by single spaces must keep their space cells blank — a
     * glyph drifting out of its cell (ligature/contextual reflow off the grid)
     * would paint ink into the gap, the "words run together" symptom.
     */
    @Test
    fun adjacentWordsKeepInterWordSpaceCellsBlank() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, terminalTypeface())
        val emulator = createEmulator(renderer)

        // Mirrors the issue's garble shape: three tokens, single spaces.
        val text = "g thinking without"
        emulator.appendBytes(text.toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-259-words-after.png")
            // Space columns in "g thinking without": index 1 and index 10.
            val spaceColumns = text.indices.filter { text[it] == ' ' }
            val rowBand = pixelRowBandForTextRow(0, rendered.fontLineSpacing, rendered.bitmap.height)
            for (col in spaceColumns) {
                // Sample the inner 60% of the cell so antialiasing fringe from
                // a correctly-placed neighbour glyph at the cell edge does not
                // cause a false positive; a drifted glyph paints the centre.
                val cellW = rendered.cellWidthPx
                val xStart = ((col + 0.2f) * cellW).roundToInt()
                val xEnd = ((col + 0.8f) * cellW).roundToInt()
                assertFalse(
                    "space cell $col has ink — a neighbouring glyph drifted out " +
                        "of its column (the #259 'words mashed together' garble)",
                    bandHasInk(rendered.bitmap, rowBand, xStart, xEnd),
                )
            }
            // Sanity: the line did render something.
            assertTrue(
                "no ink rendered at all — pipeline broken",
                bandHasInk(rendered.bitmap, rowBand, 0, rendered.bitmap.width),
            )
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * Ligature-forming sequences must still occupy one cell per code point with
     * the spacing intact. Each non-space cell has ink in its own footprint and
     * each space cell stays blank.
     */
    @Test
    fun ligatureProneSequencesStayOnCellGrid() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, terminalTypeface())
        val emulator = createEmulator(renderer)

        // `->` `==` `!=` `>>` and a doubled letter are classic ligature
        // triggers in programming fonts; keep them separated by single spaces.
        val text = "a -> b == c != d >> ee"
        emulator.appendBytes(text.toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-259-ligatures-after.png")
            val rowBand = pixelRowBandForTextRow(0, rendered.fontLineSpacing, rendered.bitmap.height)
            val cellW = rendered.cellWidthPx
            for (col in text.indices) {
                val xStart = ((col + 0.2f) * cellW).roundToInt()
                val xEnd = ((col + 0.8f) * cellW).roundToInt()
                val centreHasInk = bandHasInk(rendered.bitmap, rowBand, xStart, xEnd)
                if (text[col] == ' ') {
                    assertFalse(
                        "space cell $col has ink — ligature/contextual reflow " +
                            "drifted a glyph off the cell grid",
                        centreHasInk,
                    )
                } else {
                    assertTrue(
                        "non-space cell $col ('${text[col]}') rendered blank in its " +
                            "footprint — a ligature consumed/displaced it",
                        centreHasInk,
                    )
                }
            }
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * Drives the full in-place spinner rewrite (CR + erase-line) through the
     * emulator and renders the final frame. Only the final frame's tokens may
     * appear, each on its own cell with spaces preserved.
     */
    @Test
    fun inPlaceSpinnerRewriteRendersFinalFrameCleanly() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, terminalTypeface())
        val emulator = createEmulator(renderer)

        // ESC is 0x1B. Frame 1 is long; frame 2 is the shorter final frame,
        // each preceded by carriage-return + erase-to-end-of-line.
        emulator.appendBytes("\r[KBeboppin work (250 tok thinking)".toByteArray(Charsets.US_ASCII))
        emulator.appendBytes("\r[KBeboppin work (44 tok)".toByteArray(Charsets.US_ASCII))

        val finalFrame = "Beboppin work (44 tok)"
        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-259-spinner-after.png")
            val rowBand = pixelRowBandForTextRow(0, rendered.fontLineSpacing, rendered.bitmap.height)
            val cellW = rendered.cellWidthPx
            for (col in finalFrame.indices) {
                val xStart = ((col + 0.2f) * cellW).roundToInt()
                val xEnd = ((col + 0.8f) * cellW).roundToInt()
                val centreHasInk = bandHasInk(rendered.bitmap, rowBand, xStart, xEnd)
                if (finalFrame[col] == ' ') {
                    assertFalse(
                        "space cell $col has ink in the final spinner frame — " +
                            "garbled in-place rewrite",
                        centreHasInk,
                    )
                } else {
                    assertTrue(
                        "non-space cell $col ('${finalFrame[col]}') is blank — " +
                            "final spinner frame did not render cleanly",
                        centreHasInk,
                    )
                }
            }
            // The longer first frame's tail ("thinking") extended past the
            // final frame; those cells must now be blank (erased + not painted).
            val tailStart = ((finalFrame.length + 1) * cellW).roundToInt()
            val tailEnd = ((finalFrame.length + 6) * cellW).roundToInt()
            assertFalse(
                "stale tail from the longer earlier spinner frame leaked through",
                bandHasInk(rendered.bitmap, rowBand, tailStart, tailEnd),
            )
        } finally {
            rendered.bitmap.recycle()
        }
    }

    private fun createEmulator(renderer: TerminalRenderer): TerminalEmulator {
        val cellWidthPx = renderer.fontWidth.roundToInt().coerceAtLeast(1)
        val cellHeightPx = renderer.fontLineSpacing.coerceAtLeast(1)
        return TerminalEmulator(SinkOutput, COLUMNS, ROWS, cellWidthPx, cellHeightPx, ROWS * 2, null)
    }

    private fun TerminalEmulator.appendBytes(bytes: ByteArray) = append(bytes, bytes.size)

    private fun renderEmulatorToBitmap(
        emulator: TerminalEmulator,
        renderer: TerminalRenderer,
    ): RenderResult {
        val cellWidthPx = renderer.fontWidth
        val rowSpacingPx = renderer.fontLineSpacing
        val widthPx = (cellWidthPx * COLUMNS).roundToInt().coerceAtLeast(1)
        val heightPx = (rowSpacingPx * ROWS + rowSpacingPx).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(SENTINEL_BACKGROUND, PorterDuff.Mode.SRC)
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        return RenderResult(bitmap, cellWidthPx, rowSpacingPx)
    }

    private fun pixelRowBandForTextRow(row: Int, fontLineSpacing: Int, height: Int): IntRange {
        val rowTop = (row * fontLineSpacing - 1).coerceAtLeast(0)
        val rowBottom = ((row + 1) * fontLineSpacing + 1).coerceAtMost(height)
        return rowTop until rowBottom
    }

    private fun bandHasInk(bitmap: Bitmap, rows: IntRange, xStart: Int, xEnd: Int): Boolean {
        val width = bitmap.width
        val clampedXStart = xStart.coerceIn(0, width)
        val clampedXEnd = xEnd.coerceIn(clampedXStart, width)
        if (clampedXEnd <= clampedXStart) return false
        for (y in rows) {
            for (x in clampedXStart until clampedXEnd) {
                if (bitmap.getPixel(x, y) != SENTINEL_BACKGROUND) return true
            }
        }
        return false
    }

    private fun saveDiagnostic(bitmap: Bitmap, fileName: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaRoot = ctx.externalMediaDirs.firstOrNull { it != null }
            ?: ctx.getExternalFilesDir(null)
            ?: ctx.cacheDir
        val dir = File(mediaRoot, "additional_test_output/issue-259")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE259_VIEWPORT ${File(dir, fileName).absolutePath}")
        }
    }

    private data class RenderResult(
        val bitmap: Bitmap,
        val cellWidthPx: Float,
        val fontLineSpacing: Int,
    )

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
}
