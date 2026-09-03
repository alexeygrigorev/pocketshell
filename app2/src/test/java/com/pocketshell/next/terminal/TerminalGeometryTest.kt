package com.pocketshell.next.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pixels to character cells (rewrite task U-5).
 *
 * This arithmetic decides what the remote is told its terminal is, so it
 * decides where the shell wraps and what `stty size` prints. It is a pure
 * function precisely so it can be pinned here without a View, a Robolectric
 * shadow or an emulator — the device half (that a rotation and an opening
 * keyboard really do reach the remote) is J03's, on a real host.
 *
 * The expectations below are computed by hand from the vendored
 * `TerminalView.updateSize()` formula, not from the implementation:
 *
 *   cols = max(4, (int)(viewWidth / fontWidth))
 *   rows = max(4, (viewHeight - fontLineSpacingAndAscent) / fontLineSpacing)
 */
class TerminalGeometryTest {

    /**
     * A Pixel-class portrait viewport with the metrics the bundled
     * JetBrainsMono produces at the shipped 28 px text size.
     *
     * 1080 / 16.8 = 64.28 -> 64 columns. (2000 - 4) / 31 = 64.38 -> 64 rows.
     */
    @Test
    fun `a portrait phone viewport`() {
        val cells = terminalCells(viewWidthPx = 1080, viewHeightPx = 2000, metrics = METRICS)

        assertEquals(TerminalCells(cols = 64, rows = 64), cells)
    }

    /**
     * The same phone with the keyboard up: 800 px of viewport left.
     *
     * (800 - 4) / 31 = 25.6 -> 25 rows, and the column count does not move
     * because the keyboard takes height, not width. This is the shape of the
     * resize the IME must push to the remote.
     */
    @Test
    fun `an open keyboard costs rows and no columns`() {
        val full = terminalCells(1080, 2000, METRICS)!!
        val withKeyboard = terminalCells(1080, 800, METRICS)!!

        assertEquals(25, withKeyboard.rows)
        assertEquals(full.cols, withKeyboard.cols)
        assertEquals(64, withKeyboard.cols)
    }

    /**
     * Landscape: the axes swap, so the column count roughly doubles and the
     * row count collapses. 2000 / 16.8 = 119.04 -> 119 columns;
     * (1080 - 4) / 31 = 34.7 -> 34 rows.
     */
    @Test
    fun `rotating to landscape swaps the geometry`() {
        val cells = terminalCells(viewWidthPx = 2000, viewHeightPx = 1080, metrics = METRICS)

        assertEquals(TerminalCells(cols = 119, rows = 34), cells)
    }

    /**
     * Truncation, not rounding.
     *
     * A viewport 1.9 cells wider than 63 columns is 64 columns, not 65: the
     * remote must never be told about a column it cannot paint. Rounding up
     * here would put the last character of every wrapped line off-screen.
     */
    @Test
    fun `a partial cell is not counted`() {
        // 63 whole columns plus 90% of one.
        val width = (63 * 16.8f + 15f).toInt()

        assertEquals(63, terminalCells(width, 2000, METRICS)!!.cols)
    }

    /**
     * The vendored floor. A viewport measured at a few pixels — mid-IME
     * animation, or the frame before a rotation settles — must not ask the
     * remote for a 1x1 terminal, which tmux and readline both handle badly.
     */
    @Test
    fun `a tiny viewport clamps to the four-cell floor`() {
        val cells = terminalCells(viewWidthPx = 10, viewHeightPx = 10, metrics = METRICS)

        assertEquals(TerminalCells(cols = MIN_TERMINAL_CELLS, rows = MIN_TERMINAL_CELLS), cells)
    }

    /**
     * "Not measured yet" is not "80x24".
     *
     * The screen calls this from `onSizeChanged`, which Compose also runs with
     * a zero size before layout has bounds; a fallback size here would push a
     * wrong geometry to the remote on every attach.
     */
    @Test
    fun `an unmeasured viewport reports nothing rather than a default`() {
        assertNull(terminalCells(0, 2000, METRICS))
        assertNull(terminalCells(1080, 0, METRICS))
        assertNull(terminalCells(-5, -5, METRICS))
    }

    /** Metrics a renderer has not produced yet are equally unusable. */
    @Test
    fun `unmeasured font metrics report nothing`() {
        assertNull(terminalCells(1080, 2000, METRICS.copy(cellWidthPx = 0f)))
        assertNull(terminalCells(1080, 2000, METRICS.copy(lineHeightPx = 0)))
    }

    /**
     * The ascent slack is really subtracted.
     *
     * Dropping it is the easy mistake — the numbers stay plausible — and it
     * costs one row on roughly half of all viewport heights, which shows up as
     * the remote scrolling one line further than the phone can display.
     */
    @Test
    fun `the line-spacing-and-ascent slack is subtracted before dividing`() {
        val exactlyTenRows = 10 * METRICS.lineHeightPx + METRICS.lineSpacingAndAscentPx
        assertEquals(10, terminalCells(1080, exactlyTenRows, METRICS)!!.rows)
        assertEquals(9, terminalCells(1080, exactlyTenRows - 1, METRICS)!!.rows)
    }

    private companion object {
        /**
         * JetBrainsMono Regular at the shipped 28 raw px, as
         * `measureTerminalCellMetrics` produces it on a device. Written as
         * literals so the expectations above stay hand-checkable.
         */
        val METRICS = TerminalCellMetrics(
            cellWidthPx = 16.8f,
            lineHeightPx = 31,
            lineSpacingAndAscentPx = 4,
        )
    }
}
