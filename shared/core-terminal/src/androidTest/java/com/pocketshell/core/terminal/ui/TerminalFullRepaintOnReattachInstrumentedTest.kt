package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #721 — "terminal stays black after reattach; only NEW output paints".
 *
 * The maintainer's #1 long-standing pain. The emulator buffer is correct after a
 * reattach/switch/resume (the capture-pane re-seed lands), but the [TerminalView]'s
 * surface no longer holds the pixels the #469 dirty-region cache assumes are painted:
 * a recreated/reattached View starts on a black/cleared canvas, and a re-seed updates
 * the buffer without changing which rows the cache considers stale. So
 * [TerminalRenderer.peekDirtyRows] reports dirty-only and the platform clips the next
 * `onDraw` to just those rows — only freshly-written cells paint over black, and the
 * existing screen content is never redrawn.
 *
 * This runs on a REAL device (unlike the Robolectric
 * [com.pocketshell.core.terminal.render.TerminalRendererDirtyOracleTest], whose shadow
 * Paint reports zero font metrics and therefore disables the renderer's pixel-band
 * clip-skip). Real metrics mean `mFontLineSpacing > 0`, so the renderer's clip-gated
 * row-skip is ACTIVE — exactly the production path where the bug lives. The test:
 *
 *  1. Seeds a multi-line screen, renders once (cache populated → "already painted").
 *  2. **Negative control (the bug):** a no-op reattach renders with the platform clip
 *     restricted to the dirty rows the [TerminalRenderer.peekDirtyRows] reports — and
 *     the renderer paints only those few rows, leaving the rest of the (black) surface
 *     untouched. Asserts the painted-row count is NOT the full screen.
 *  3. **The fix:** [TerminalView.forceFullRepaint] resets the dirty cache via
 *     [TerminalRenderer.invalidateDirtyCache]; the next [TerminalRenderer.peekDirtyRows]
 *     returns [TerminalRenderer.PEEK_FULL] and a full-clip render paints EVERY row from
 *     the buffer — proving the whole existing screen repaints.
 *  4. **The #469 steady-state opt is preserved:** after the full repaint, an ordinary
 *     append paints only the changed rows again (NOT a full repaint), so streaming keeps
 *     its zero-steady-state-cost dirty-region behaviour.
 */
@RunWith(AndroidJUnit4::class)
class TerminalFullRepaintOnReattachInstrumentedTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val rows = 12
    private val columns = 60

    private fun newEmulator(): TerminalEmulator =
        TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 4, null)

    private fun TerminalEmulator.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        append(b, b.size)
    }

    /** Count rows the renderer actually painted on the most recent [TerminalRenderer.render]. */
    private fun TerminalRenderer.paintedRows() = getLastRenderedRowCountForTesting()

    /**
     * Render with a platform clip restricted to the union of the dirty rows the renderer
     * reports — mimicking exactly what [TerminalView] does after `invalidate(rect)` on a
     * dirty-region frame. The renderer skips rows whose pixel band falls outside the clip.
     */
    private fun renderDirtyClipped(
        renderer: TerminalRenderer,
        emulator: TerminalEmulator,
        canvas: Canvas,
        bitmap: Bitmap,
    ): Int {
        val dirty = BooleanArray(emulator.mRows)
        val peek = renderer.peekDirtyRows(emulator, 0, -1, -1, -1, -1, dirty)
        if (peek == TerminalRenderer.PEEK_FULL) {
            renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
            return peek
        }
        if (peek == TerminalRenderer.PEEK_NONE) {
            return peek
        }
        // Union the dirty rows into a single clip rect band, the same way the platform's
        // clip would bound the next onDraw to the invalidated rows.
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (i in 0 until emulator.mRows) {
            if (dirty[i]) {
                top = minOf(top, maxOf(0, renderer.rowTopPx(i)))
                bottom = maxOf(bottom, renderer.rowBottomPx(i))
            }
        }
        canvas.save()
        canvas.clipRect(Rect(0, top, bitmap.width, bottom))
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        canvas.restore()
        return peek
    }

    @Test
    fun reattachOnBlackCanvasRepaintsWholeBufferOnlyAfterForceFullRepaint() {
        val emulator = newEmulator()
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)

        // Real device metrics: the clip-skip is only meaningful with a positive row pitch.
        assertTrue(
            "real-device font metrics expected (mFontLineSpacing > 0) so the clip-skip path is active",
            renderer.fontLineSpacing > 0,
        )

        val bitmap = Bitmap.createBitmap(
            (columns * renderer.fontWidth).toInt().coerceAtLeast(1),
            rows * renderer.fontLineSpacing + renderer.fontLineSpacingAndAscent,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)

        // 1. Seed a full screen of content and render once (full clip) so the dirty cache
        //    is populated — every row is now considered "already painted on the surface".
        for (i in 0 until rows) emulator.feed("existing row $i with content abcdefghij 0123456789\r\n")
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        assertEquals(
            "first full render must paint every visible row",
            rows,
            renderer.paintedRows(),
        )

        // 2. NEGATIVE CONTROL — the #721 bug. Simulate a reattach/recreate: the View's
        //    surface is black/cleared, but the renderer's cache still thinks the rows are
        //    painted. A dirty-region frame on this wiped canvas paints only the rows
        //    peekDirtyRows reports — which is NOT the whole screen, so the existing
        //    content stays black.
        bitmap.eraseColor(Color.BLACK)
        val bugPeek = renderDirtyClipped(renderer, emulator, canvas, bitmap)
        assertTrue(
            "without the force, a no-op reattach must NOT force a full repaint (the #721 bug)",
            bugPeek != TerminalRenderer.PEEK_FULL,
        )
        assertTrue(
            "without the force, the existing screen is NOT fully repainted: painted rows " +
                "(${renderer.paintedRows()}) must be fewer than all $rows rows",
            renderer.paintedRows() < rows,
        )
        // Authoritative viewport artifact of the BUG: black except scattered cells.
        dumpViewport(bitmap, "reattach-bug-black-except-new-viewport.png")

        // 3. THE FIX — forceFullRepaint() resets the dirty cache. The next peek must be
        //    PEEK_FULL and the render must paint EVERY row from the buffer, so the whole
        //    existing screen repaints on the black canvas.
        bitmap.eraseColor(Color.BLACK)
        renderer.invalidateDirtyCache() // what TerminalView.forceFullRepaint() calls
        val fixDirty = BooleanArray(rows)
        val fixPeek = renderer.peekDirtyRows(emulator, 0, -1, -1, -1, -1, fixDirty)
        assertEquals(
            "after invalidateDirtyCache() the next peek must force a full repaint",
            TerminalRenderer.PEEK_FULL,
            fixPeek,
        )
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        assertEquals(
            "the fix repaints the WHOLE existing buffer (every row) on reattach",
            rows,
            renderer.paintedRows(),
        )
        assertNonBackgroundPixelsAcrossAllRows(bitmap, renderer)
        // Authoritative viewport artifact of the FIX: the whole existing screen repaints.
        dumpViewport(bitmap, "reattach-fixed-full-repaint-viewport.png")

        // 4. #469 STEADY-STATE OPT PRESERVED — an in-place rewrite of a single row after
        //    the full repaint must paint only that changed row, not the whole screen
        //    (a scroll legitimately dirties every band, so we rewrite one row in place
        //    via cursor addressing without scrolling). This proves the force is scoped to
        //    the reveal boundary and does not regress steady-state streaming cost.
        val esc = ""
        emulator.feed("$esc[3;1H${esc}[K| spinner rewrite on row 3 in place")
        val steadyPeek = renderDirtyClipped(renderer, emulator, canvas, bitmap)
        assertTrue(
            "steady-state streaming must stay dirty-only (not PEEK_FULL) so #469 is preserved",
            steadyPeek != TerminalRenderer.PEEK_FULL,
        )
        assertTrue(
            "steady-state append must paint fewer rows than a full repaint (#469 preserved): " +
                "painted ${renderer.paintedRows()} of $rows",
            renderer.paintedRows() < rows,
        )
    }

    /** Each visible row band must contain at least one non-background pixel after a full repaint. */
    private fun assertNonBackgroundPixelsAcrossAllRows(bitmap: Bitmap, renderer: TerminalRenderer) {
        for (i in 0 until rows) {
            val top = maxOf(0, renderer.rowTopPx(i))
            val bottom = minOf(bitmap.height, renderer.rowBottomPx(i))
            var nonBackground = 0
            var y = top
            while (y < bottom) {
                var x = 0
                while (x < bitmap.width) {
                    if (bitmap.getPixel(x, y) != Color.BLACK) nonBackground++
                    x += 2
                }
                y += 2
            }
            assertTrue(
                "row $i must have painted (non-black) pixels after the full repaint — " +
                    "the existing screen content must be visible, not black",
                nonBackground > 0,
            )
        }
    }

    /**
     * Write the rendered viewport bitmap as an authoritative `*-viewport.png` artifact so
     * it can be pulled off the device for #638 review evidence (bug state vs full-repaint
     * state). Prefers AGP's `additionalTestOutputDir` instrumentation argument — which the
     * connected-test task pulls into `build/outputs/connected_android_test_additional_output/`
     * — and falls back to the instrumentation context's external files dir.
     */
    private fun dumpViewport(bitmap: Bitmap, name: String) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val argDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (!argDir.isNullOrBlank()) {
            File(argDir).apply { mkdirs() }
        } else {
            File(instr.context.getExternalFilesDir(null), "terminal-lab").apply { mkdirs() }
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileOutputStream(File(dir, name)).use { out -> out.write(png.toByteArray()) }
    }

    private companion object {
        const val TEXT_SIZE_PX = 28
    }
}
