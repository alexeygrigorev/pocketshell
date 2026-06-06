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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Pixel-level verification for issue #172 — Option A "pin cell width to
 * regular-advance" cell rendering.
 *
 * The renderer must draw every glyph (regular or bold) inside the cell
 * allocation derived from the regular-advance width. Per-letter bold/regular
 * alternations (Claude Code's animated "Working..." spinner) used to leave
 * visible gaps because the bold paint draws wider than its precomputed regular
 * measurement; the fix in [TerminalRenderer.drawTextRun] re-measures bold runs
 * with the bold paint and squashes them back into the cell range.
 *
 * The test renders the emulator off-screen to a [Bitmap], then asserts:
 *
 *  1. Cell boundaries are at consistent x-pixels — adjacent non-space cells'
 *     painted-pixel columns line up on the regular-advance grid (no shift).
 *  2. No "visible gap" exists where a non-space cell renders as fully empty
 *     pixels — the symptom the user saw on Claude Code's spinner line.
 *
 * The test instantiates [TerminalEmulator] + [TerminalRenderer] directly (the
 * stub `libtermux.so` shipped by this module makes JNI calls inside the
 * emulator no-op, so a full [com.termux.terminal.TerminalSession] is not
 * needed to drive bytes through the parser). This keeps the test independent
 * of SSH / sessions / Compose.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRendererBoldCellPinningInstrumentedTest {

    private companion object {
        /** Generous size so bold-vs-regular metric drift is visible if present. */
        private const val TEXT_SIZE_PX = 40
        private const val COLUMNS = 32
        private const val ROWS = 4

        /** ANSI CSI sequences. `` is ESC (0x1B). */
        private const val ESC_BOLD = "[1m"
        private const val ESC_REGULAR = "[22m"

        /**
         * Distinct ARGB the renderer is highly unlikely to emit so
         * painted-pixel detection has zero false negatives even when the
         * emulator's default background / foreground are very close to the
         * canvas clear color. Magenta-with-near-blue (0xFFFF00FE).
         */
        private const val SENTINEL_BACKGROUND: Int = -0xff0102 // 0xFFFF00FE
    }

    /**
     * Per-letter bold/regular alternation. Renders a line like:
     *
     *   regular "X", bold "B", regular "X", bold "B", ...
     *
     * If bold metrics displaced neighbors, the gaps would appear in the
     * cell range that should hold either an "X" or "B".
     */
    @Test
    fun boldRegularInterleavedHasNoGapsAcrossCellBoundaries() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val emulator = createEmulator(renderer)

        // 24 cells of alternating bold/regular ASCII letters on the first row.
        // We avoid space characters so every cell has visible ink and any blank
        // cell-range is a real gap, not whitespace.
        val pattern = buildString {
            repeat(12) {
                append(ESC_BOLD).append('B')
                append(ESC_REGULAR).append('X')
            }
        }
        emulator.appendBytes(pattern.toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-172-bold-regular-after.png")
            assertCellBoundariesAreUniform(rendered, row = 0, expectedColumns = 24)
            assertNoBlankNonSpaceCells(rendered, row = 0, cellCount = 24)
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * Reproduces the spinner-style pattern Claude Code uses: a single word
     * where individual letters flip in and out of bold. Asserts cell columns
     * are aligned and no letter cell is blank.
     */
    @Test
    fun perLetterBoldToggleProducesAlignedCells() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val emulator = createEmulator(renderer)

        // "Working" with the third + fifth letter flipped to bold each
        // iteration. The shape mirrors the issue screenshot: per-glyph weight
        // toggles inside a word with no whitespace.
        val pattern = buildString {
            append('W')
            append('o')
            append(ESC_BOLD).append('r').append(ESC_REGULAR)
            append('k')
            append(ESC_BOLD).append('i').append(ESC_REGULAR)
            append('n')
            append('g')
        }
        emulator.appendBytes(pattern.toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-172-working-spinner-after.png")
            assertCellBoundariesAreUniform(rendered, row = 0, expectedColumns = 7)
            assertNoBlankNonSpaceCells(rendered, row = 0, cellCount = 7)
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * The core Option A invariant — a bold run does not paint pixels outside
     * its cell-aligned column footprint. The test renders a line with a bold
     * 'M' surrounded by SPACE characters: `  [BOLD]MM[/BOLD]  `. With the fix,
     * every painted pixel for the bold run lies within columns `[2..4)` —
     * strictly inside `[2*cellW, 4*cellW)`. Without the fix, fake-bold stroke
     * thickening bleeds into the flanking space cells, contradicting
     * Option A's promise that bold "does not displace neighbors".
     *
     * Using SPACES on either side is critical: a regular non-space neighbor
     * would always paint some ink in the slack region, making the bleed
     * indistinguishable from natural glyph rendering. Spaces guarantee that
     * any ink outside the bold cell range must have come from the bold
     * stroke bleeding past its allocated advance.
     */
    @Test
    fun boldRunDoesNotPaintOutsideItsCellRange() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val emulator = createEmulator(renderer)

        // 4 leading spaces, 12 bold 'M' chars, 4 trailing spaces.
        val pattern = buildString {
            append("    ")
            append(ESC_BOLD)
            repeat(12) { append('M') }
            append(ESC_REGULAR)
            append("    ")
        }
        emulator.appendBytes(pattern.toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-172-bold-cell-pin-after.png")

            val cellW = rendered.cellWidthPx
            val boldStartCol = 4
            val boldEndCol = 4 + 12  // exclusive
            val boldLeftPx = (boldStartCol * cellW).roundToInt()
            val boldRightPx = (boldEndCol * cellW).roundToInt()

            val rowBand = pixelRowBandForTextRow(0, rendered.fontLineSpacing, rendered.bitmap.height)

            // Left flank: cells 0..3 are spaces. Any ink there is bleed from
            // the bold run to its left.
            val leftFlankInk = bandHasInk(rendered.bitmap, rowBand, 0, boldLeftPx)
            // Right flank: cells 16..(boldEndCol+4) are spaces. The cursor
            // lands in cell 20, past our right flank scan (which ends at
            // (boldEndCol + 3) * cellW so cursor is excluded).
            val rightFlankXEnd = ((boldEndCol + 3) * cellW).roundToInt()
            val rightFlankInk = bandHasInk(rendered.bitmap, rowBand, boldRightPx, rightFlankXEnd)

            assertFalse(
                "bold run bled INK into the left flank space cells " +
                    "[0..$boldLeftPx) — Option A cell pin failed",
                leftFlankInk,
            )
            assertFalse(
                "bold run bled INK into the right flank space cells " +
                    "[$boldRightPx..$rightFlankXEnd) — Option A cell pin failed",
                rightFlankInk,
            )

            // Sanity: the bold run itself produced visible ink.
            assertTrue(
                "bold run [$boldLeftPx..$boldRightPx) has no ink — render pipeline broken",
                bandHasInk(rendered.bitmap, rowBand, boldLeftPx, boldRightPx),
            )
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * Regression guard for the all-regular path — confirms the fix did not
     * shift cell boundaries when no bold runs are involved (so unaffected
     * agent transcripts render identically).
     */
    @Test
    fun allRegularCellsRemainAligned() {
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val emulator = createEmulator(renderer)
        emulator.appendBytes("ABCDEFGHIJ".toByteArray(Charsets.US_ASCII))

        val rendered = renderEmulatorToBitmap(emulator, renderer)
        try {
            saveDiagnostic(rendered.bitmap, "issue-172-regular-only-after.png")
            assertCellBoundariesAreUniform(rendered, row = 0, expectedColumns = 10)
            assertNoBlankNonSpaceCells(rendered, row = 0, cellCount = 10)
        } finally {
            rendered.bitmap.recycle()
        }
    }

    private fun createEmulator(renderer: TerminalRenderer): TerminalEmulator {
        val cellWidthPx = renderer.fontWidth.roundToInt().coerceAtLeast(1)
        val cellHeightPx = renderer.fontLineSpacing.coerceAtLeast(1)
        return TerminalEmulator(
            SinkOutput,
            COLUMNS,
            ROWS,
            cellWidthPx,
            cellHeightPx,
            ROWS * 2,
            null,
        )
    }

    private fun TerminalEmulator.appendBytes(bytes: ByteArray) {
        append(bytes, bytes.size)
    }

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
        // Fill the bitmap with a sentinel "background" color the renderer is
        // unlikely to draw, so we can identify painted vs. non-painted pixels
        // reliably regardless of the emulator's default palette.
        canvas.drawColor(SENTINEL_BACKGROUND, PorterDuff.Mode.SRC)
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        return RenderResult(bitmap, cellWidthPx, rowSpacingPx)
    }

    /**
     * For [row] in the emulator grid, locate the band of pixel rows that
     * corresponds to that text row, then for each of the first [expectedColumns]
     * columns confirm at least one painted pixel lies within the cell range
     * `[col*cellW, (col+1)*cellW)`. This catches displacement: if bold glyph N
     * pushed glyph N+1 rightward past the cell boundary, cell N+1 would have
     * no ink inside its allocated x-range.
     */
    private fun assertCellBoundariesAreUniform(
        rendered: RenderResult,
        row: Int,
        expectedColumns: Int,
    ) {
        val rowBand = pixelRowBandForTextRow(row, rendered.fontLineSpacing, rendered.bitmap.height)
        for (col in 0 until expectedColumns) {
            val xStart = (col * rendered.cellWidthPx).roundToInt()
            val xEnd = ((col + 1) * rendered.cellWidthPx).roundToInt().coerceAtMost(rendered.bitmap.width)
            val hasInk = bandHasInk(rendered.bitmap, rowBand, xStart, xEnd)
            assertTrue(
                "cell $col [x=$xStart..${xEnd - 1}] (row $row pixel band $rowBand) has no painted pixels — " +
                    "bold metric drift displaced neighbors, leaving a visible gap",
                hasInk,
            )
        }
    }

    private fun assertNoBlankNonSpaceCells(
        rendered: RenderResult,
        row: Int,
        cellCount: Int,
    ) {
        val rowBand = pixelRowBandForTextRow(row, rendered.fontLineSpacing, rendered.bitmap.height)
        var blankCells = 0
        for (col in 0 until cellCount) {
            val xStart = (col * rendered.cellWidthPx).roundToInt()
            val xEnd = ((col + 1) * rendered.cellWidthPx).roundToInt().coerceAtMost(rendered.bitmap.width)
            if (!bandHasInk(rendered.bitmap, rowBand, xStart, xEnd)) blankCells++
        }
        assertEquals(
            "$blankCells of $cellCount non-space cells rendered blank — bold/regular boundary gap regression",
            0,
            blankCells,
        )
        // Sanity check: the bitmap as a whole has visible text, so we are not
        // asserting on an empty canvas.
        assertFalse(
            "bitmap is entirely background — render pipeline produced no output",
            bandHasInk(rendered.bitmap, rowBand, 0, rendered.bitmap.width).not(),
        )
    }

    private fun pixelRowBandForTextRow(row: Int, fontLineSpacing: Int, height: Int): IntRange {
        // TerminalRenderer draws row 0's baseline at y = fontLineSpacing, with
        // the glyph spanning roughly [fontLineSpacing - |ascent|, fontLineSpacing
        // + descent]. We sample the band [row * fontLineSpacing, (row + 1) *
        // fontLineSpacing) which fully contains the glyph for that row and is
        // exclusive of the neighboring row's ink. Y-bands have a tiny tolerance
        // of one pixel above/below clamped to the bitmap to absorb rounding.
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

    /**
     * Saves a rendered bitmap to the test app's external media dir under
     * `additional_test_output/issue-172/` so the reviewer / orchestrator can
     * pull it via `adb pull` after the connected test run. This is the same
     * convention `phone-walkthrough.sh` and friends use for visual artifacts.
     * Failures during save are non-fatal — the assertions are the test
     * contract; the image is diagnostic only.
     */
    private fun saveDiagnostic(bitmap: Bitmap, fileName: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaRoot = testArtifactsRoot(ctx)
        val dir = File(mediaRoot, "additional_test_output/issue-172")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE172_VIEWPORT ${File(dir, fileName).absolutePath}")
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
