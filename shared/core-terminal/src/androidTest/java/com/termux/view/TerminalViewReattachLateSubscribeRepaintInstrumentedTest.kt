package com.termux.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.terminal.ui.applyPocketShellDefaults
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #879 — REAL-VIEW device-truth regression for the BEYOND-GRACE reconnect black screen.
 *
 * The reviewer (CHANGES REQUESTED) found the prior render-layer instrumented test stood a
 * `TerminalRenderer.invalidateDirtyCache()` call IN PLACE OF a real [TerminalView] — a model,
 * not the View on the real drop. This test removes that proxy: it builds and drives a **real
 * [TerminalView]** (the SAME `applyPocketShellDefaults` wiring production uses), wires the
 * **real [TerminalSurfaceState.fullRepaintRequests] collector exactly as
 * `TerminalSurface` does** (`collect { view.forceFullRepaint() }`), and renders through the
 * real `TerminalView.onDraw(...)` with the platform's dirty clip. The ONLY thing modelled is
 * the platform's own "clip onDraw to the invalidated rect" step (that is the Android view
 * framework, not our code); everything in OUR code path is the production object:
 * [TerminalSurfaceState], its `replay`ing flow, the collector shape, `view.forceFullRepaint()`,
 * `view.mRenderer`, and `view.onDraw()`.
 *
 * ## Why a real [TerminalView], not `view.draw(Canvas)` in the journey
 *
 * `View.draw(Canvas)` (what the journey's `captureViewport` uses) does a FULL software render
 * that bypasses the on-screen dirty clip — it can NEVER reproduce the surface-clip black (the
 * #721 lesson; confirmed by the reviewer's journey shell viewport that draws fine even when the
 * on-screen pane is black). So a real-View red→green for #879 must drive the real
 * `onDraw` UNDER the platform's dirty clip — which is precisely what this test does.
 *
 * ## The exact production mechanism (re-create ordering)
 *
 *  - A beyond-grace reconnect re-creates the pane: a fresh [TerminalSurfaceState] +
 *    [TerminalView]. The active pane is seeded from a full `capture-pane` snapshot, and
 *    [TerminalSurfaceState.appendRemoteOutput] fires [TerminalSurfaceState.fullRepaintRequests]
 *    so the View redraws EVERY row over the fresh/black surface.
 *  - The #640 seed-before-reveal contract means that emit fires BEFORE the fresh surface binds
 *    its `state.fullRepaintRequests.collect { view.forceFullRepaint() }` collector.
 *  - With `replay = 0` (base) the late-subscribing collector NEVER receives the emit →
 *    `forceFullRepaint()` is never called → the renderer's dirty cache is not invalidated →
 *    the next dirty-clipped `onDraw` paints only the few changed rows over black → ~95% black.
 *  - With `replay = 1` (the fix) the late subscriber receives the replayed emit → calls the
 *    real `view.forceFullRepaint()` → the next `onDraw` is full-clip → every row repaints.
 *
 * ## Red→green (this test)
 *
 *  - With `replay = 0` (revert the fix): the real View paints FEWER than all rows on the black
 *    surface → black gaps → FAIL.
 *  - With `replay = 1` (the fix): the real View's `forceFullRepaint()` ran → every row repaints
 *    over black → PASS.
 *
 * Runs on a REAL device (real font metrics so the renderer's clip-gated row-skip is active —
 * the production path where the bug lives). Built in `com.termux.view` to call the protected
 * `TerminalView.onDraw` directly through a tiny test subclass.
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewReattachLateSubscribeRepaintInstrumentedTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val rows = 14
    private val columns = 60

    private fun newEmulator(): TerminalEmulator =
        TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 4, null)

    private fun TerminalEmulator.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        append(b, b.size)
    }

    /**
     * Render the real [TerminalView]'s `onDraw` (via the public `View.draw(Canvas)`, which calls
     * the protected `onDraw` → the real `TerminalRenderer.render`) under the platform's dirty
     * clip — exactly what the Android view framework does after `invalidate(rect)`: it clips the
     * next `onDraw`'s canvas to the union of the invalidated (dirty) rows and the renderer skips
     * rows whose pixel band falls outside the clip. We compute the dirty-row band the SAME way
     * the production `TerminalView.invalidateDirtyRegion()` does (`peekDirtyRows` → per-dirty-run
     * rects), set that clip on the canvas, then call the REAL `view.draw(canvas)` — which renders
     * within the canvas's existing clip (the renderer reads `canvas.getClipBounds()` to skip
     * rows). Returns the peek result. The clip-to-invalidated-rect step is the Android view
     * framework; everything inside `view.draw()` is the real production render path.
     */
    private fun renderRealViewDirtyClipped(
        view: TerminalView,
        emulator: TerminalEmulator,
        canvas: Canvas,
        bitmap: Bitmap,
    ): Int {
        val renderer = view.mRenderer
        val dirty = BooleanArray(emulator.mRows)
        val peek = renderer.peekDirtyRows(emulator, 0, -1, -1, -1, -1, dirty)
        if (peek == TerminalRenderer.PEEK_FULL) {
            // Full invalidate → full-clip draw (no clip restriction).
            view.draw(canvas)
            return peek
        }
        if (peek == TerminalRenderer.PEEK_NONE) {
            // Nothing invalidated → the platform paints nothing → the black surface stays black.
            return peek
        }
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
        view.draw(canvas)
        canvas.restore()
        return peek
    }

    @Test
    fun lateSubscribeAfterReattachSeedRepaintsRealViewWholeBuffer() { runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext

        // Build a REAL TerminalView with the SAME production wiring (applyPocketShellDefaults
        // creates the real renderer via setTextSize + the bundled typeface and installs the real
        // PocketShellTerminalViewClient). Must run on a Looper thread — the GestureDetector +
        // the renderer-invalidation Handler need a prepared Looper; the instrumentation main
        // thread has one.
        lateinit var view: TerminalView
        instr.runOnMainSync {
            view = TerminalView(context, null)
            view.applyPocketShellDefaults(PocketShellTerminalViewClient())
        }
        val renderer = view.mRenderer
        assertTrue(
            "real-device font metrics expected (fontLineSpacing > 0) so the clip-skip path is active",
            renderer.fontLineSpacing > 0,
        )

        // The fresh pane's emulator + state (the re-create produces brand-new instances). We set
        // mEmulator directly (a real attachSession would spawn a shell process) — the journey
        // does the same. The View now renders from this real emulator buffer.
        val emulator = newEmulator()
        val state = TerminalSurfaceState()

        val widthPx = (columns * renderer.fontWidth).toInt().coerceAtLeast(1)
        val heightPx = rows * renderer.fontLineSpacing + renderer.fontLineSpacingAndAscent
        instr.runOnMainSync {
            view.mEmulator = emulator
            // Lay the detached View out so getWidth()/getHeight() are non-zero (onDraw + the
            // dirty-region path read them).
            view.layout(0, 0, widthPx, heightPx)
        }

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // STEP 1 — the reattach seed writes the full captured banner into the buffer; the real
        // View renders it once (full clip), populating the renderer's dirty cache so EVERY row
        // is considered "already painted on the surface".
        for (i in 0 until rows) emulator.feed("ISSUE879 banner row %02d filler abcdefghij\r\n".format(i))
        instr.runOnMainSync { view.draw(canvas) }
        assertEquals(
            "the seeded banner must paint fully on the first render",
            rows,
            renderer.getLastRenderedRowCountForTesting(),
        )

        // STEP 2 — the seed ALSO fires the full-repaint request through the REAL production flow
        // (the appendRemoteOutput seam), while NO collector is subscribed yet (the #640
        // seed-before-reveal ordering: the fresh surface has not bound its repaint collector).
        val emitted = state.emitFullRepaintRequestForTesting()
        assertTrue("the seed's full-repaint request must be accepted by the flow", emitted)

        // STEP 2b — the re-created/recomposed View starts on a fresh/cleared BLACK surface, but
        // the BUFFER is unchanged (so the #469 dirty cache still thinks every row is painted).
        // Without a forced full repaint the next dirty-clipped frame sees PEEK_NONE and paints
        // NOTHING → the whole screen stays black (the #721/#879 mechanism). We erase here; the
        // only thing that can repaint is the seed's full-repaint signal reaching the View.
        bitmap.eraseColor(Color.BLACK)

        // STEP 3 — the surface reveals and binds the collector EXACTLY as TerminalSurface does
        // (`state.fullRepaintRequests.collect { view.forceFullRepaint() }`), AFTER the seed
        // already emitted. This calls the REAL `view.forceFullRepaint()` (no invalidateDirtyCache
        // stand-in). With replay = 0 this late collector misses the request; with replay = 1 it
        // receives the replayed one. The collect body runs on the main thread (forceFullRepaint
        // touches the View).
        val forceLatch = CountDownLatch(1)
        val collectorScope = CoroutineScope(Dispatchers.Main)
        val collectorJob = collectorScope.launch {
            state.fullRepaintRequests.collect {
                runCatching { view.forceFullRepaint() }
                forceLatch.countDown()
            }
        }
        // Give the late collector a real chance to receive a replayed value (replay = 1) and run
        // forceFullRepaint() on the main thread. We block the (background) test thread on the
        // latch so the main looper is free to run the collector; under replay = 0 there is
        // nothing to receive, so this just times out (the bug branch) and we render the black.
        forceLatch.await(3, TimeUnit.SECONDS)
        instr.waitForIdleSync()

        // NEGATIVE/POSITIVE — render the REAL View's onDraw with the platform's dirty clip on the
        // now-BLACK surface, exactly as the next on-screen frame would after the re-create. Under
        // replay = 0 the dropped signal means peek is dirty-NONE → nothing paints → black stays
        // (the bug). Under replay = 1 the real forceFullRepaint() invalidated the cache →
        // PEEK_FULL → every row repaints over black.
        var peek = -2
        instr.runOnMainSync {
            peek = renderRealViewDirtyClipped(view, emulator, canvas, bitmap)
        }
        val paintedRows = renderer.getLastRenderedRowCountForTesting()

        dumpViewport(
            bitmap,
            if (peek == TerminalRenderer.PEEK_FULL) {
                "real-view-reattach-late-subscribe-fixed-full-repaint-viewport.png"
            } else {
                "real-view-reattach-late-subscribe-bug-black-gaps-viewport.png"
            },
        )

        collectorJob.cancel()

        // LOAD-BEARING assertion: the late subscriber must have received the seed's full-repaint
        // (replay = 1) so the REAL View's `forceFullRepaint()` ran and the WHOLE buffer repaints
        // over black. Under replay = 0 (the bug) the signal is dropped, forceFullRepaint() never
        // ran, and the dirty-clipped onDraw paints NONE of the seeded rows → fully black.
        assertEquals(
            "#879: after the reattach seed, the late-subscribing real TerminalView must run " +
                "forceFullRepaint() so the next dirty-clipped onDraw is a FULL repaint (PEEK_FULL) " +
                "— with replay = 0 the dropped signal leaves it dirty-none (peek=$peek), painting " +
                "$paintedRows of $rows rows over black.",
            TerminalRenderer.PEEK_FULL,
            peek,
        )
        assertEquals(
            "#879: the whole existing buffer must repaint on the reattach (every row), not just a " +
                "fragment — the REAL View painted $paintedRows of $rows.",
            rows,
            paintedRows,
        )
        assertNonBackgroundPixelsAcrossAllRows(bitmap, renderer)
    } }

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
                "row $i must have painted (non-black) pixels after the reattach full repaint on " +
                    "the REAL View — the existing banner content must be visible, not black",
                nonBackground > 0,
            )
        }
    }

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
}
