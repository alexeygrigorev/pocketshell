package com.pocketshell.next.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pocketshell.next.settings.LocalAppSettings
import kotlin.math.ceil

/**
 * How many character cells fit in a pixel viewport (rewrite task U-5).
 *
 * ## What this is for
 *
 * The terminal's size in cells is what the remote is told, so it decides where
 * the shell wraps and what `stty size` reports. Once a [com.termux.view.TerminalView]
 * exists it owns that number — it holds the renderer, so it holds the font
 * metrics — and reports it through `onEmulatorSet`. But the PTY is opened
 * BEFORE any view has been laid out, and until U-5 it was opened at the
 * historical 80x24 default, so every attach made the remote paint once at
 * 80x24 and then reflow when the first frame reported the phone's real
 * geometry.
 *
 * [terminalCells] closes that gap: the session screen measures its terminal
 * slot, converts pixels to cells with the same arithmetic the vendored view
 * uses, and reports it through `onResized` while the dial is still in flight —
 * which [SessionViewModel.onResized] explicitly supports ("Before the bridge
 * exists the size is only remembered, so a resize that lands during the dial
 * still opens the PTY at the right size instead of being lost").
 *
 * ## One owner, still
 *
 * The estimate is used ONLY while the screen is not live. The moment the
 * terminal view exists it is the single source of the size, exactly as U-4
 * designed — this never competes with it, it just stops the remote from
 * starting at the wrong size.
 *
 * ## Mirroring obligation
 *
 * [terminalCells] and [measureTerminalCellMetrics] mirror
 * `TerminalView.updateSize()` and `TerminalRenderer`'s constructor, which live
 * in `shared/core-terminal` and are pinned byte-identical to upstream (see its
 * `VENDORED.md`). They cannot call into them: both the metric fields and
 * `LINE_SPACING_MULTIPLIER` are package-private to `com.termux.view`. If a
 * Termux refresh changes either formula, the worst case here is an estimate
 * that is off by a row or two and gets corrected by the first frame — the same
 * correction that happened on every attach before U-5.
 */

/**
 * Cell metrics in raw device pixels, as a renderer computes them from a
 * typeface and a text size.
 *
 * @param cellWidthPx advance width of one monospace glyph. Fractional, because
 *   the vendored renderer divides by it as a float before truncating.
 * @param lineHeightPx per-cell row pitch.
 * @param lineSpacingAndAscentPx the vendored `mFontLineSpacingAndAscent` —
 *   row pitch plus the (negative) ascent, i.e. the vertical slack the renderer
 *   reserves above the first row's baseline. Subtracted from the viewport
 *   height before dividing, so a viewport exactly N rows tall reports N rows
 *   rather than N + a sliver.
 * @param textSizePx raw-pixel text size these metrics were measured at. Kept
 *   so a host-JVM test can tell a 40 px measurement from a 28 px one even
 *   when Robolectric's `Paint` reports the same 1 px glyph for both.
 */
data class TerminalCellMetrics(
    val cellWidthPx: Float,
    val lineHeightPx: Int,
    val lineSpacingAndAscentPx: Int,
    /**
     * Raw-pixel text size these metrics were measured at.
     *
     * Recorded (not re-derived from [cellWidthPx]) because Robolectric's
     * `Paint` reports a 1 px glyph at every size, so the only way a host-JVM
     * test can tell a 40 px measurement from a 28 px one is to keep the input.
     * Production still measures at this size; the field is how the wiring test
     * proves [rememberTerminalCellMetrics] used the Settings value and not the
     * [TERMINAL_TEXT_SIZE_RAW_PX] literal. Defaults to the shipped size so
     * fixtures that stand in for "28 px on a device" stay as they were.
     */
    val textSizePx: Int = TERMINAL_TEXT_SIZE_RAW_PX,
)

/** A terminal size in character cells. */
data class TerminalCells(val cols: Int, val rows: Int)

/**
 * The vendored view's floor. `TerminalView.updateSize()` clamps both axes to
 * 4, so a viewport briefly measured at a few pixels (mid-IME-animation, or the
 * frame before a rotation settles) can never ask the remote for a 1x1 terminal.
 */
const val MIN_TERMINAL_CELLS: Int = 4

/**
 * Cells for a viewport, or `null` when the viewport or the metrics are not
 * measurable yet.
 *
 * `null` rather than a fallback size: "we do not know yet" and "the terminal
 * is 80x24" are different claims, and reporting the second would push a wrong
 * size to the remote every time a layout pass ran before the view had bounds.
 *
 * Pure — this is the whole point of the file. It takes ints and floats and
 * returns a data class, so the arithmetic that decides what the remote is told
 * is unit-testable without a View, a Robolectric shadow or an emulator.
 */
fun terminalCells(
    viewWidthPx: Int,
    viewHeightPx: Int,
    metrics: TerminalCellMetrics,
): TerminalCells? {
    if (viewWidthPx <= 0 || viewHeightPx <= 0) return null
    if (metrics.cellWidthPx <= 0f || metrics.lineHeightPx <= 0) return null
    // Float divide then truncate for columns, integer divide for rows: not a
    // style choice, it is what `TerminalView.updateSize()` does, and a
    // "cleaner" rounding here would disagree with the view by a column on some
    // widths and produce a resize on every single frame.
    val cols = maxOf(MIN_TERMINAL_CELLS, (viewWidthPx / metrics.cellWidthPx).toInt())
    val rows = maxOf(
        MIN_TERMINAL_CELLS,
        (viewHeightPx - metrics.lineSpacingAndAscentPx) / metrics.lineHeightPx,
    )
    return TerminalCells(cols = cols, rows = rows)
}

/**
 * The metrics of the face the terminal actually renders with, measured once
 * per context and text size.
 *
 * A `@Composable` rather than a plain function so the session screen can take
 * it as a defaulted parameter — the same seam shape `AppNavHost` uses for its
 * screens. That matters for more than tidiness: Robolectric's `Paint` reports
 * a 1 px glyph with zero ascent and descent, so a host-JVM test that could not
 * substitute real metrics could never see the size-reporting path run at all.
 *
 * The size is [LocalAppSettings]'s `terminalTextSizePx`, not the
 * [TERMINAL_TEXT_SIZE_RAW_PX] literal: that constant is only the fresh-install
 * default, and measuring at a different size than [TerminalHostView] paints
 * would make the geometry estimate disagree with the glyphs (issue #2512).
 */
@Composable
fun rememberTerminalCellMetrics(): TerminalCellMetrics {
    val context = LocalContext.current
    val textSizePx = LocalAppSettings.current.terminalTextSizePx
    return remember(context, textSizePx) {
        measureTerminalCellMetrics(
            typeface = terminalTypeface(context),
            textSizePx = textSizePx,
        )
    }
}

/**
 * Measures [typeface] at [textSizePx] the way the vendored `TerminalRenderer`
 * does.
 *
 * The multiplier and the ascent/descent basis are PocketShell's own change to
 * the vendored renderer (issue #241: upstream used `Paint.getFontSpacing()`,
 * whose built-in leading made the terminal read as vertically stretched on a
 * phone), so they are reproduced here rather than guessed.
 */
fun measureTerminalCellMetrics(typeface: Typeface, textSizePx: Int): TerminalCellMetrics {
    val paint = Paint().apply {
        setTypeface(typeface)
        isAntiAlias = true
        isSubpixelText = true
        isLinearText = true
        textSize = textSizePx.toFloat()
    }
    val ascent = ceil(paint.ascent().toDouble()).toInt()
    val lineHeight = ceil((-paint.ascent() + paint.descent()) * LINE_SPACING_MULTIPLIER.toDouble())
        .toInt()
    return TerminalCellMetrics(
        cellWidthPx = paint.measureText("X"),
        lineHeightPx = lineHeight,
        lineSpacingAndAscentPx = lineHeight + ascent,
        textSizePx = textSizePx,
    )
}

/**
 * Must equal `TerminalRenderer.LINE_SPACING_MULTIPLIER` (private, hence the
 * copy). See that constant's javadoc for why it is 0.85 and not 1.0.
 */
private const val LINE_SPACING_MULTIPLIER: Float = 0.85f
