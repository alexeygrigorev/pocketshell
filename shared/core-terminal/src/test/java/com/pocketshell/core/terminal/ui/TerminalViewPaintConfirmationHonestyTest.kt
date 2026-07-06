package com.pocketshell.core.terminal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1296 — the paint-confirmation seam ([TerminalView.onDraw] →
 * [TerminalSurfaceState.onSurfaceFramePainted]) must record a CONTENT paint ONLY
 * when the frame actually rendered non-black glyph coverage, NOT merely because
 * `mEmulator != null`.
 *
 * ## The bug this pins (adversarial audit finding 3 on #1208)
 *
 * After [TerminalView.forceSurfaceRepaint] (#1203) re-binds `mEmulator` from the
 * live session, the NEXT `onDraw` on a surface that is still black (0-size /
 * offscreen / stale hardware buffer / a genuinely blank grid) used to set
 * `paintedEmulatorContent = true` purely because `mEmulator != null`. That bogus
 * "content paint" flips [TerminalSurfaceState.surfaceIsBlackWhileModelHasContent]
 * FALSE — silencing BOTH the heal oracle's surface-black detector AND the
 * `%output` suspect-wake path — while the pane sits visually black. The system
 * records a heal that never happened.
 *
 * ## Why these run on the REAL [TerminalView.onDraw] (not a proxy)
 *
 * The tests build a real [TerminalView] and drive its real `onDraw` via
 * `view.draw(canvas)` — the exact production render path — and read back the
 * outcome through the real [TerminalView.FramePaintObserver] wired exactly as
 * [TerminalSurface] wires it into [TerminalSurfaceState.onSurfaceFramePainted].
 * Robolectric's shadow `Paint` reports zero font metrics, but the #1296 decision
 * is a MODEL-level check (nonzero view size AND non-blank visible rows), which is
 * independent of glyph metrics — so the JVM Robolectric path exercises the exact
 * load-bearing logic without an emulator. That is why this is the fastest
 * reliable proof for this seam; the device-truth sibling is
 * `TerminalViewForceSurfaceRepaintInstrumentedTest` (#1203).
 */
@RunWith(RobolectricTestRunner::class)
class TerminalViewPaintConfirmationHonestyTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private val rows = 14
    private val columns = 60

    private fun newView(): TerminalView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        view.applyPocketShellDefaults(PocketShellTerminalViewClient())
        return view
    }

    private fun newEmulator(): TerminalEmulator =
        TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 4, null)

    private fun TerminalEmulator.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        append(b, b.size)
    }

    /**
     * Criterion 1 + 4 — a non-null emulator bound to a 0-SIZE [TerminalView] must
     * NOT record a content paint, and [TerminalSurfaceState.surfaceIsBlackWhileModelHasContent]
     * must STAY true so the watchdog/wake path keeps retrying.
     *
     * RED on base: `onDraw` sets `paintedEmulatorContent = true` because
     * `mEmulator != null`, so the observer feeds a CONTENT paint and
     * `surfaceIsBlackWhileModelHasContent()` flips false — the assertion fails.
     * GREEN with the fix: `getWidth() == 0` ⇒ BLANK paint ⇒ detector stays armed.
     */
    @Test
    fun zeroSizeBoundEmulatorRecordsBlankPaintAndKeepsDetectorArmed() = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            // The MODEL is intact: the state's emulator holds a full frame, so the
            // model-vs-tmux heal oracle would call this pane HEALTHY.
            state.appendRemoteOutput(
                "[2J[HISSUE1296-MODEL-CONTENT-VISIBLE\r\n".toByteArray(Charsets.US_ASCII),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "precondition: the MODEL grid must hold content (renderedNonBlankCharCount > 0) — " +
                    "otherwise surfaceIsBlackWhileModelHasContent() is vacuously false and the test proves nothing",
                state.renderedNonBlankCharCount() > 0,
            )

            // The SURFACE-only-black state: the View is bound to the SAME live
            // emulator the model holds, but the View has ZERO size (0-size /
            // offscreen — the surface is still black). This is the exact
            // post-forceSurfaceRepaint window the bug records as "healed".
            val view = newView()
            view.mEmulator = state.session?.emulator
            assertTrue("precondition: the View must be bound to a non-null emulator", view.mEmulator != null)
            assertTrue("precondition: the View must be 0-size to reproduce the still-black surface", view.width == 0)

            // Wire the REAL paint-confirmation observer exactly as TerminalSurface does.
            val lastPaintedContent = AtomicBoolean(true)
            val paintCount = AtomicInteger(0)
            view.setFramePaintObserver { paintedContent, atMs ->
                lastPaintedContent.set(paintedContent)
                paintCount.incrementAndGet()
                state.onSurfaceFramePainted(paintedContent, atMs)
            }

            // Drive the REAL onDraw on a 1x1 canvas (canvas size is irrelevant; the
            // View reports getWidth()/getHeight() == 0 because it was never laid out).
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            assertTrue("a frame must have painted", paintCount.get() >= 1)
            assertFalse(
                "#1296: a 0-size bound emulator must record a BLANK paint, NOT a content paint — " +
                    "mEmulator != null alone is not proof the surface rendered anything",
                lastPaintedContent.get(),
            )
            assertTrue(
                "#1296: after the 0-size draw the surface-black detector must STAY armed (true) so the " +
                    "watchdog/%output-wake path keeps retrying — the bug silences it by recording a bogus content paint",
                state.surfaceIsBlackWhileModelHasContent(),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    /**
     * Criterion 2 — an emulator bound to a SIZED view whose screen has genuinely
     * blank rows must record a BLANK paint.
     *
     * RED on base: `onDraw` records a content paint (mEmulator != null). GREEN with
     * the fix: all-blank visible rows ⇒ [com.termux.terminal.TerminalBuffer.hasNonBlankVisibleRow]
     * is false ⇒ BLANK paint.
     */
    @Test
    fun sizedViewBlankEmulatorRecordsBlankPaint() {
        val view = newView()
        // Lay the view out at a real (nonzero) size first. mTermSession is null, so
        // updateSize() early-returns and does NOT touch mEmulator.
        view.layout(0, 0, 300, 300)
        assertTrue("precondition: the view must be sized", view.width > 0 && view.height > 0)

        // A freshly constructed emulator: all cells are spaces — a genuinely blank grid.
        val blankEmulator = newEmulator()
        assertFalse(
            "precondition: the emulator screen must have NO non-blank visible row",
            blankEmulator.screen.hasNonBlankVisibleRow(),
        )
        view.mEmulator = blankEmulator

        val lastPaintedContent = AtomicBoolean(true)
        val paintCount = AtomicInteger(0)
        view.setFramePaintObserver { paintedContent, _ ->
            lastPaintedContent.set(paintedContent)
            paintCount.incrementAndGet()
        }

        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        assertTrue("a frame must have painted", paintCount.get() >= 1)
        assertFalse(
            "#1296: a sized view whose bound emulator has only blank visible rows must record a BLANK paint",
            lastPaintedContent.get(),
        )
    }

    /**
     * Criterion 3 (G6 — no false positives) — an emulator bound to a SIZED view
     * whose screen carries real glyphs must record a CONTENT paint, so a HEALTHY
     * pane never starts triggering heals. Green on BOTH base and fix by design:
     * this is the false-positive guard, not a red→green.
     */
    @Test
    fun sizedViewEmulatorWithGlyphsRecordsContentPaint() {
        val view = newView()
        view.layout(0, 0, 300, 300)
        assertTrue("precondition: the view must be sized", view.width > 0 && view.height > 0)

        val emulator = newEmulator()
        for (i in 0 until rows) emulator.feed("ISSUE1296 healthy row %02d filler abcdefghij\r\n".format(i))
        assertTrue(
            "precondition: the emulator screen must carry real glyphs (a non-blank visible row)",
            emulator.screen.hasNonBlankVisibleRow(),
        )
        view.mEmulator = emulator

        val lastPaintedContent = AtomicBoolean(false)
        val paintCount = AtomicInteger(0)
        view.setFramePaintObserver { paintedContent, _ ->
            lastPaintedContent.set(paintedContent)
            paintCount.incrementAndGet()
        }

        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        assertTrue("a frame must have painted", paintCount.get() >= 1)
        assertTrue(
            "#1296 (G6): a sized view rendering real glyphs must record a CONTENT paint — the fix must NOT " +
                "regress healthy panes into false surface-black heals",
            lastPaintedContent.get(),
        )
    }
}
