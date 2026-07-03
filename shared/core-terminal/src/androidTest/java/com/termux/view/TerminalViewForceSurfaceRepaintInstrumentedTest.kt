package com.termux.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient
import com.pocketshell.core.terminal.ui.applyPocketShellDefaults
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1203 — REAL-VIEW device-truth regression for the SURFACE-only-black recovery.
 *
 * ## The class this recovers (the #1192 sixth `black_frame_observed` class)
 *
 * `surface_black_model_intact`: the MODEL grid (the session/bridge emulator) still holds
 * the frame — so tmux's model-vs-model heal oracle calls the pane HEALTHY and a model
 * reseed (manual Redraw / stale-render heal) restores NOTHING — but the on-screen SURFACE
 * is black. The concrete mechanism: the [TerminalView]'s own emulator binding
 * ({@link TerminalView#mEmulator}) is `null` even though {@link TerminalView#mTermSession}
 * still holds a live emulator, so every {@link TerminalView#onDraw} takes the BLACK
 * fallback (`mEmulator == null` → `canvas.drawColor(black)`).
 *
 * ## Why forceFullRepaint (#721) cannot recover it — and forceSurfaceRepaint (#1203) can
 *
 * [TerminalView.forceFullRepaint] (what the model reseed's `appendRemoteOutput` fires)
 * only resets the renderer's dirty cache and calls `invalidate()`. With `mEmulator == null`
 * the next `onDraw` STILL takes the black fallback → the surface stays black. That is the
 * maintainer's "Redraw doesn't work". [TerminalView.forceSurfaceRepaint] re-binds
 * `mEmulator` from the live session BEFORE invalidating, so the next `onDraw` takes the
 * CONTENT path and the surface recovers.
 *
 * ## Red → green (this test, on the REAL View + REAL onDraw)
 *
 *  - RED (base / the surface-black state): with `mEmulator == null`, the real `onDraw`
 *    paints the black fallback and the [TerminalView.FramePaintObserver] reports
 *    `paintedEmulatorContent = false` — the confirmed surface-black.
 *  - `forceSurfaceRepaint()` re-binds the emulator from the session.
 *  - GREEN: the next real `onDraw` paints the emulator content and the observer reports
 *    `paintedEmulatorContent = true` — the surface recovered.
 *
 * If this test is run against a `forceSurfaceRepaint` that does NOT re-bind the emulator
 * (i.e. behaves like `forceFullRepaint`), `mEmulator` stays null, the second `onDraw`
 * paints black again, and the GREEN assertion fails — proving the re-bind is the
 * load-bearing recovery, not merely another `invalidate()`.
 *
 * Runs on a REAL device (real font metrics + the real `TerminalRenderer.render`); built in
 * `com.termux.view` to call the protected `onDraw` via `View.draw(Canvas)` and to touch the
 * package-visible `mEmulator` / `mTermSession` fields.
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewForceSurfaceRepaintInstrumentedTest {

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

    /** A bare session with a pre-installed emulator (no shell subprocess) via reflection. */
    private fun sessionHolding(emulator: TerminalEmulator): TerminalSession {
        val session = TerminalSession("/bin/sh", "/", arrayOf("sh"), arrayOf(), rows * 4, null)
        val field = TerminalSession::class.java.getDeclaredField("mEmulator")
        field.isAccessible = true
        field.set(session, emulator)
        return session
    }

    @Test
    fun forceSurfaceRepaintRebindsEmulatorAndRecoversContentPaint() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext

        lateinit var view: TerminalView
        instr.runOnMainSync {
            view = TerminalView(context, null)
            view.applyPocketShellDefaults(PocketShellTerminalViewClient())
        }
        val renderer = view.mRenderer
        assertTrue(
            "real-device font metrics expected (fontLineSpacing > 0) so the content render path is active",
            renderer.fontLineSpacing > 0,
        )

        // The MODEL is intact: a session whose emulator holds a full dense frame.
        val emulator = newEmulator()
        for (i in 0 until rows) emulator.feed("ISSUE1203 recovered row %02d filler abcdefghij\r\n".format(i))
        val session = sessionHolding(emulator)

        val widthPx = (columns * renderer.fontWidth).toInt().coerceAtLeast(1)
        val heightPx = rows * renderer.fontLineSpacing + renderer.fontLineSpacingAndAscent

        // The SURFACE-only-black state: the View has the session but LOST its emulator
        // binding (mEmulator == null → the onDraw black fallback), while the session's
        // emulator still holds the frame. This is the exact surface_black_model_intact
        // class the model-vs-tmux oracle cannot see.
        //
        // ORDER MATTERS (reviewer #1203): the View must be laid out out at real size
        // BEFORE we null mEmulator. `view.layout(...)` drives onSizeChanged → updateSize(),
        // and updateSize() re-binds `mEmulator = mTermSession.getEmulator()` whenever
        // `mEmulator == null` (TerminalView.java:1396-1398). If we nulled mEmulator BEFORE
        // layout, that size pass would immediately re-bind it and the RED surface-black
        // precondition would never actually be entered — a false green. So: lay out first
        // (which binds the emulator from the live session), THEN drop the binding to enter
        // the surface_black_model_intact state that the next onDraw must render black.
        instr.runOnMainSync {
            view.mTermSession = session
            view.layout(0, 0, widthPx, heightPx)
            view.mEmulator = null
        }
        // Hard guard: the surface-black precondition MUST actually hold before the RED
        // onDraw, or the whole red→green is vacuous (G3). Fail loudly if a later size pass
        // ever re-binds the emulator again.
        instr.runOnMainSync {
            assertNull(
                "precondition: the View must be in the surface_black_model_intact state " +
                    "(mEmulator == null) BEFORE the RED onDraw — otherwise the black-fallback " +
                    "render never happens and the test passes vacuously",
                view.mEmulator,
            )
        }

        // Wire the REAL paint-confirmation observer exactly as TerminalSurface does.
        val lastPaintedContent = AtomicBoolean(false)
        val paintCount = AtomicInteger(0)
        instr.runOnMainSync {
            view.setFramePaintObserver { paintedContent, _ ->
                lastPaintedContent.set(paintedContent)
                paintCount.incrementAndGet()
            }
        }

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // RED — the real onDraw with mEmulator == null paints the BLACK fallback; the
        // observer confirms the surface is black (paintedEmulatorContent = false).
        instr.runOnMainSync { view.draw(canvas) }
        instr.waitForIdleSync()
        assertTrue("a frame must have painted", paintCount.get() >= 1)
        assertFalse(
            "RED: with mEmulator == null the real onDraw paints the BLACK fallback — the surface " +
                "is confirmed black (paintedEmulatorContent = false), exactly what forceFullRepaint " +
                "alone cannot recover",
            lastPaintedContent.get(),
        )
        instr.runOnMainSync { assertNull("precondition: the View lost its emulator binding", view.mEmulator) }

        // THE FIX — forceSurfaceRepaint re-binds the emulator from the live session.
        instr.runOnMainSync { view.forceSurfaceRepaint() }
        instr.runOnMainSync {
            assertSame(
                "#1203: forceSurfaceRepaint must re-bind mEmulator from the live session (the " +
                    "load-bearing recovery — a plain forceFullRepaint would leave it null and the " +
                    "surface black)",
                emulator,
                view.mEmulator,
            )
        }

        // GREEN — the next real onDraw now takes the CONTENT path; the observer confirms the
        // surface recovered (paintedEmulatorContent = true).
        val countBeforeGreen = paintCount.get()
        instr.runOnMainSync { view.draw(canvas) }
        instr.waitForIdleSync()
        assertTrue("a second frame must have painted", paintCount.get() > countBeforeGreen)
        assertTrue(
            "GREEN: after forceSurfaceRepaint re-bound the emulator, the real onDraw paints the " +
                "emulator CONTENT (paintedEmulatorContent = true) — the surface-only-black recovered",
            lastPaintedContent.get(),
        )
    }
}
