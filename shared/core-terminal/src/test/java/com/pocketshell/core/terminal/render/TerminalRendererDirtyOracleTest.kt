package com.pocketshell.core.terminal.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dirty-row LOGIC oracle for the dirty-region renderer (PocketShell #469).
 *
 * Robolectric's shadow Paint reports zero font metrics, so a pixel-level viewport
 * comparison is degenerate (every glyph lands at y=0 and the renderer's pixel-band
 * skip is disabled). This oracle therefore validates the *mechanism that decides
 * what to repaint* — {@link TerminalRenderer#peekDirtyRows} — which is pure
 * generation/state comparison and fully deterministic without real metrics.
 *
 * The model mirrors the View contract exactly: a "screen capture" cell grid holds
 * what is currently painted. Each frame:
 *  - `peekDirtyRows` reports which rows the View would invalidate.
 *  - Only those rows are copied from the emulator into the capture (a clean,
 *    skipped row retains its previous cells — exactly what the platform preserves).
 *  - A `render()` is run so the renderer's internal cache advances like production.
 *  - The capture is asserted EQUAL to the emulator's full current grid.
 *
 * If `peekDirtyRows` ever fails to mark a row whose content changed (a missed dirty
 * mark — the release-blocking bug), the retained stale cells diverge from the full
 * grid and the test fails. The scenarios exercise every force-full trap from the
 * spike: scroll, in-place rewrite, reverse-video, palette change, alt-screen,
 * resize, cursor move, and selection.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalRendererDirtyOracleTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private data class Step(
        val bytes: ByteArray,
        val selY1: Int = -1,
        val selY2: Int = -1,
        val selX1: Int = -1,
        val selX2: Int = -1,
        val assertAfter: (TerminalEmulator) -> Unit = {},
    )

    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

    /** A row's painted content: the visible cells plus the cursor/selection overlay state. */
    private fun captureRow(
        terminal: TerminalEmulator,
        row: Int,
        cursorRow: Int,
        cursorCol: Int,
        cursorVisible: Boolean,
        selY1: Int, selY2: Int, selX1: Int, selX2: Int,
    ): String {
        val screen = terminal.screen
        val cols = terminal.mColumns
        val sb = StringBuilder()
        // Visible text of the row.
        sb.append(screen.getSelectedText(0, row, cols - 1, row, false))
        sb.append('|')
        // Per-cell style.
        for (c in 0 until cols) sb.append(screen.getStyleAt(row, c)).append(',')
        sb.append('|')
        // Cursor overlay painted into this row.
        if (cursorVisible && row == cursorRow) sb.append("CUR@").append(cursorCol)
        sb.append('|')
        // Selection overlay painted into this row.
        if (row in selY1..selY2) {
            val x1 = if (row == selY1) selX1 else 0
            val x2 = if (row == selY2) selX2 else cols
            sb.append("SEL").append(x1).append('-').append(x2)
        }
        return sb.toString()
    }

    private fun fullGrid(
        terminal: TerminalEmulator,
        cursorRow: Int, cursorCol: Int, cursorVisible: Boolean,
        selY1: Int, selY2: Int, selX1: Int, selX2: Int,
    ): Array<String> = Array(terminal.mRows) { row ->
        captureRow(terminal, row, cursorRow, cursorCol, cursorVisible, selY1, selY2, selX1, selX2)
    }

    private fun newScratchCanvas() = Canvas(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888))

    private fun runOracle(name: String, columns: Int, rows: Int, steps: List<Step>) {
        val terminal = TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 4, null)
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val scratchCanvas = newScratchCanvas()
        // The retained "screen capture": what is currently painted per row.
        var capture = Array(rows) { "" }
        var dirtyScratch = BooleanArray(rows)

        steps.forEachIndexed { index, step ->
            if (step.bytes.isNotEmpty()) terminal.append(step.bytes, step.bytes.size)
            step.assertAfter(terminal)

            val curRow = terminal.cursorRow
            val curCol = terminal.cursorCol
            val curVis = terminal.shouldCursorBeVisible()

            if (dirtyScratch.size != terminal.mRows) dirtyScratch = BooleanArray(terminal.mRows)
            if (capture.size != terminal.mRows) capture = Array(terminal.mRows) { "" }

            val result = renderer.peekDirtyRows(
                terminal, 0, step.selY1, step.selY2, step.selX1, step.selX2, dirtyScratch,
            )
            val paintAll = result == TerminalRenderer.PEEK_FULL
            for (r in 0 until terminal.mRows) {
                if (paintAll || dirtyScratch[r]) {
                    capture[r] = captureRow(
                        terminal, r, curRow, curCol, curVis,
                        step.selY1, step.selY2, step.selX1, step.selX2,
                    )
                }
                // else: retain the previously painted row (platform-preserved).
            }

            // Advance the renderer cache like production (render is run for real).
            renderer.render(terminal, scratchCanvas, 0, step.selY1, step.selY2, step.selX1, step.selX2)

            val expected = fullGrid(
                terminal, curRow, curCol, curVis,
                step.selY1, step.selY2, step.selX1, step.selX2,
            )
            for (r in 0 until terminal.mRows) {
                assertEquals(
                    "[$name] step $index row $r: dirty-row logic left a stale row " +
                        "(peekDirtyRows did not mark a changed row)",
                    expected[r], capture[r],
                )
            }
        }
    }

    @Test
    fun plainAppendScroll() {
        val steps = (0 until 60).map { i -> Step(b("plain line $i abcdefghijklmnop 0123456789\r\n")) }
        runOracle("plain-append-scroll", 80, 24, steps)
    }

    @Test
    fun inPlaceSpinnerRewrite() {
        val glyphs = listOf("|", "/", "-", "\\")
        val steps = buildList {
            add(Step(b("context line one\r\ncontext line two\r\n")))
            repeat(40) { i -> add(Step(b("\r${ESC}[K${glyphs[i % 4]} working step $i token-${i * 7}"))) }
            add(Step(b("\r${ESC}[Kdone")))
        }
        runOracle("spinner-rewrite", 60, 10, steps)
    }

    @Test
    fun reverseVideoToggle() {
        val steps = listOf(
            Step(b("alpha\r\nbeta\r\ngamma\r\n")),
            Step(
                b("${ESC}[?5h"),
                assertAfter = { terminal ->
                    assertTrue("DECSET ?5h must enable reverse video", terminal.isReverseVideo)
                },
            ),
            Step(b("more text after reverse\r\n")),
            Step(
                b("${ESC}[?5l"),
                assertAfter = { terminal ->
                    assertFalse("DECRST ?5l must disable reverse video", terminal.isReverseVideo)
                },
            ),
            Step(b("text after reverse off\r\n")),
        )
        runOracle("reverse-video", 50, 8, steps)
    }

    @Test
    fun paletteColorChange() {
        val steps = listOf(
            Step(b("${ESC}[31mred line${ESC}[0m\r\n")),
            Step(b("${ESC}[32mgreen line${ESC}[0m\r\n")),
            Step(
                b("${ESC}]4;1;rgb:00/00/ff${ESC}\\"),
                assertAfter = { terminal ->
                    assertEquals(
                        "OSC 4 must update palette index 1",
                        0xFF0000FF.toInt(),
                        terminal.mColors.mCurrentColors[1],
                    )
                },
            ),
            Step(b("${ESC}[33myellow line${ESC}[0m\r\n")),
        )
        runOracle("palette-change", 50, 8, steps)
    }

    @Test
    fun altScreenSwitch() {
        val steps = listOf(
            Step(b("primary one\r\nprimary two\r\nprimary three\r\n")),
            Step(
                b("${ESC}[?1049h"),
                assertAfter = { terminal ->
                    assertTrue("DECSET ?1049h must switch to the alternate buffer", terminal.isAlternateBufferActive)
                },
            ),
            Step(b("${ESC}[H${ESC}[2Jalt content row\r\n")),
            Step(b("more alt content\r\n")),
            Step(
                b("${ESC}[?1049l"),
                assertAfter = { terminal ->
                    assertFalse("DECRST ?1049l must restore the main buffer", terminal.isAlternateBufferActive)
                },
            ),
            Step(b("back on primary\r\n")),
        )
        runOracle("alt-screen", 50, 10, steps)
    }

    @Test
    fun cursorMoves() {
        val steps = listOf(
            Step(b("line one\r\nline two\r\nline three\r\n")),
            Step(
                b("${ESC}[1;1H"),
                assertAfter = { terminal ->
                    assertEquals("CSI 1;1H must move cursor to row 0", 0, terminal.cursorRow)
                    assertEquals("CSI 1;1H must move cursor to column 0", 0, terminal.cursorCol)
                },
            ),
            Step(
                b("${ESC}[2;5H"),
                assertAfter = { terminal ->
                    assertEquals("CSI 2;5H must move cursor to row 1", 1, terminal.cursorRow)
                    assertEquals("CSI 2;5H must move cursor to column 4", 4, terminal.cursorCol)
                },
            ),
            Step(
                b("${ESC}[3;1H"),
                assertAfter = { terminal ->
                    assertEquals("CSI 3;1H must move cursor to row 2", 2, terminal.cursorRow)
                    assertEquals("CSI 3;1H must move cursor to column 0", 0, terminal.cursorCol)
                },
            ),
            Step(
                b("${ESC}[?25l"),
                assertAfter = { terminal ->
                    assertFalse("DECRST ?25l must hide the cursor", terminal.shouldCursorBeVisible())
                },
            ),
            Step(
                b("${ESC}[?25h"),
                assertAfter = { terminal ->
                    assertTrue("DECSET ?25h must show the cursor", terminal.shouldCursorBeVisible())
                },
            ),
        )
        runOracle("cursor-moves", 40, 6, steps)
    }

    @Test
    fun selectionChanges() {
        val steps = listOf(
            Step(b("selectable one\r\nselectable two\r\nselectable three\r\n")),
            Step(ByteArray(0), selY1 = 0, selY2 = 0, selX1 = 2, selX2 = 8),
            Step(ByteArray(0), selY1 = 1, selY2 = 1, selX1 = 0, selX2 = 10),
            Step(ByteArray(0), selY1 = 0, selY2 = 2, selX1 = 3, selX2 = 5),
            Step(ByteArray(0)),
        )
        runOracle("selection", 40, 6, steps)
    }

    @Test
    fun resize() {
        val rows = 12
        val terminal = TerminalEmulator(SinkOutput, 60, rows, 13, 15, rows * 4, null)
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val scratchCanvas = newScratchCanvas()
        var capture = Array(rows) { "" }
        var dirtyScratch = BooleanArray(rows)

        fun feed(s: String) { val by = b(s); terminal.append(by, by.size) }

        fun compare(label: String) {
            val curRow = terminal.cursorRow
            val curCol = terminal.cursorCol
            val curVis = terminal.shouldCursorBeVisible()
            if (dirtyScratch.size < terminal.mRows) dirtyScratch = BooleanArray(terminal.mRows)
            if (capture.size != terminal.mRows) capture = Array(terminal.mRows) { "" }
            val result = renderer.peekDirtyRows(terminal, 0, -1, -1, -1, -1, dirtyScratch)
            val paintAll = result == TerminalRenderer.PEEK_FULL
            for (r in 0 until terminal.mRows) {
                if (paintAll || dirtyScratch[r]) {
                    capture[r] = captureRow(terminal, r, curRow, curCol, curVis, -1, -1, -1, -1)
                }
            }
            renderer.render(terminal, scratchCanvas, 0, -1, -1, -1, -1)
            val expected = fullGrid(terminal, curRow, curCol, curVis, -1, -1, -1, -1)
            for (r in 0 until terminal.mRows) {
                assertEquals("[resize] $label row $r stale", expected[r], capture[r])
            }
        }

        feed("row one\r\nrow two\r\nrow three\r\n"); compare("initial")
        terminal.resize(60, 6, 13, 15); compare("shrink-rows")
        feed("new after shrink\r\n"); compare("after-shrink-output")
        terminal.resize(60, 10, 13, 15); compare("grow-rows")
        feed("new after grow\r\n"); compare("after-grow-output")
        terminal.resize(40, 10, 13, 15); compare("change-columns")
        feed("new after column change wraps over the narrower screen now\r\n"); compare("after-cols-output")
    }

    @Test
    fun mixedSequence() {
        val steps = buildList {
            add(Step(b("start\r\n")))
            repeat(8) { i -> add(Step(b("flood $i ......................\r\n"))) }
            add(Step(b("\r${ESC}[K| spin a")))
            add(Step(b("\r${ESC}[K/ spin b")))
            add(Step(b("${ESC}[31mcolour${ESC}[0m\r\n")))
            add(Step(b("${ESC}[?5h")))
            add(Step(b("rev line\r\n")))
            add(Step(b("${ESC}[?5l")))
            add(Step(b("${ESC}[1;1H")))
            add(Step(ByteArray(0), selY1 = 0, selY2 = 1, selX1 = 1, selX2 = 4))
            add(Step(ByteArray(0)))
            add(Step(b("${ESC}[?1049h${ESC}[H${ESC}[2Jalt\r\n")))
            add(Step(b("${ESC}[?1049l")))
            add(Step(b("final line\r\n")))
        }
        runOracle("mixed", 50, 8, steps)
    }

    /**
     * Issue #721 — force-full-repaint on reattach/reveal/re-seed.
     *
     * The View's surface can lose the pixels the #469 dirty cache assumes are still
     * painted: a recreated/reattached View starts black, and a re-seed (capture-pane)
     * updates the buffer without changing which rows the cache considers stale. The fix
     * is [TerminalRenderer.invalidateDirtyCache] (called by
     * [com.termux.view.TerminalView.forceFullRepaint]), which must make the NEXT
     * [TerminalRenderer.peekDirtyRows] return [TerminalRenderer.PEEK_FULL] — every row
     * repaints straight from the buffer — even though no row generation changed.
     *
     * The negative control (no force) returns dirty-only / [TerminalRenderer.PEEK_NONE],
     * which is exactly today's bug: only newly-written cells paint over a black canvas.
     */
    @Test
    fun forceFullRepaintMarksEveryRowDirtyAfterReveal() {
        val columns = 40
        val rows = 8
        val terminal = TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 4, null)
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val scratchCanvas = newScratchCanvas()
        val dirty = BooleanArray(rows)

        // Fill a multi-line screen and render once so the dirty cache is populated and
        // every row is considered "already painted". Hide the cursor so the negative
        // control below is a true "nothing changed" (a visible cursor row is always
        // re-marked dirty by peekDirtyRows for blink, which is unrelated to #721).
        val seed = b("${ESC}[?25lline one\r\nline two\r\nline three\r\nline four\r\nline five\r\n")
        terminal.append(seed, seed.size)
        assertFalse("DECRST ?25l in the seed must hide the cursor", terminal.shouldCursorBeVisible())
        renderer.render(terminal, scratchCanvas, 0, -1, -1, -1, -1)

        // NEGATIVE CONTROL — a no-op reattach WITHOUT the force: the buffer content did
        // not change, so peekDirtyRows does NOT force a full repaint and does NOT mark
        // every row dirty. On a black/cleared surface that is precisely the #721 bug —
        // only newly-changed rows paint, and the existing screen is never redrawn.
        val noForce = renderer.peekDirtyRows(terminal, 0, -1, -1, -1, -1, dirty)
        assertNotEquals(
            "without forceFullRepaint a no-op reattach must NOT force a full repaint " +
                "(encodes the #721 bug: existing screen stays black)",
            TerminalRenderer.PEEK_FULL, noForce,
        )
        assertTrue(
            "without the force the existing screen is NOT fully repainted: at least " +
                "one row must be left clean (the #721 bug)",
            (0 until rows).any { !dirty[it] },
        )

        // THE FIX — forceFullRepaint() resets the dirty cache. The very next peek must be
        // PEEK_FULL with every row marked, so render() repaints the WHOLE buffer even
        // though no row's content generation changed since the last frame.
        renderer.invalidateDirtyCache()
        val forced = renderer.peekDirtyRows(terminal, 0, -1, -1, -1, -1, dirty)
        assertEquals(
            "after invalidateDirtyCache() the next peek must force a full repaint",
            TerminalRenderer.PEEK_FULL, forced,
        )
        for (r in 0 until rows) {
            assertEquals("row $r must be dirty after the force", true, dirty[r])
        }
    }

    private companion object {
        const val TEXT_SIZE_PX = 28
        const val ESC = "\u001B"
    }
}
