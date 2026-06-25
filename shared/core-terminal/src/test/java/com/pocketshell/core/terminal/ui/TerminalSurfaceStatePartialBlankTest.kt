package com.pocketshell.core.terminal.ui

import android.os.Looper
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

/**
 * Issue #553 (epic #687 Phase 3, J2) — [TerminalSurfaceState.visibleScreenIsPartiallyBlank]
 * is the diagnostic predicate for the maintainer's "only a timer, rest blank" symptom: a
 * within-grace reattach where a reflow wiped the static viewport but ONE live line (a
 * per-second status/timer) keeps repainting. Because that one line makes the screen NOT
 * fully blank, the fully-blank heal ([visibleScreenIsBlank]) skips the pane — which is
 * exactly why the P3 within-grace reseed restores the FULL viewport unconditionally
 * instead of trusting the fully-blank gate.
 *
 * These tests pin the partial-blank classifier against the states that distinguish "one
 * live line on an otherwise empty grid" (partial blank) from "fully blank" and "normally
 * populated":
 *
 *  - No producer attached -> NOT partial (nothing to classify yet).
 *  - A fully-blank grid -> NOT partial (that is [visibleScreenIsBlank]'s job).
 *  - A clear + ONE live line -> partial blank (the timer-only symptom).
 *  - A clear + a normally-populated viewport -> NOT partial.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStatePartialBlankTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun withAttachedSurface(block: (TerminalSurfaceState) -> Unit) = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            block(state)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun unattachedSurfaceIsNotPartiallyBlank() {
        val state = TerminalSurfaceState()
        assertFalse(
            "an unattached surface must not be reported partially blank",
            state.visibleScreenIsPartiallyBlank(),
        )
    }

    @Test
    fun fullyBlankSurfaceIsNotPartiallyBlank() = withAttachedSurface { state ->
        // A clear-only frame is FULLY blank — visibleScreenIsBlank handles it; the
        // partial classifier must NOT also claim it (no live line to preserve).
        state.appendRemoteOutput("[2J[H".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "precondition: a clear-only frame is fully blank",
            state.visibleScreenIsBlank(),
        )
        assertFalse(
            "a fully-blank surface must NOT be reported partially blank",
            state.visibleScreenIsPartiallyBlank(),
        )
    }

    @Test
    fun clearPlusOneLiveLineIsPartiallyBlank() = withAttachedSurface { state ->
        // The maintainer's symptom: the static viewport is wiped, ONE live timer line
        // repaints. Not fully blank (the timer is present) but the vast majority of the
        // grid is empty -> partial blank.
        state.appendRemoteOutput("[2J[HISSUE553-TIMER 42\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "precondition: a live timer line makes the screen NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertTrue(
            "a clear + one live line on an otherwise empty grid must be partially blank",
            state.visibleScreenIsPartiallyBlank(),
        )
    }

    @Test
    fun normallyPopulatedViewportIsNotPartiallyBlank() = withAttachedSurface { state ->
        // A normally-populated pane: many live rows. NOT partial blank.
        val frame = buildString {
            append("[2J[H")
            repeat(20) { append("line $it has real content here\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "a normally-populated viewport must NOT be reported partially blank",
            state.visibleScreenIsPartiallyBlank(),
        )
    }

    // ----------------------------------------------------------------- Issue #941
    // The combined oracle [visibleScreenIsBlankOrPartiallyBlank] is the single
    // "needs heal" predicate the switch-reveal gate, the connected blank watchdog,
    // and the send-overpaint heal now share, so a PARTIAL-black pane (the "sent a
    // message -> black" symptom) is healed on every reveal/watchdog path, not only
    // on reattach. These pin its union semantics.

    @Test
    fun blankOrPartiallyBlankIsTrueForAFullyBlankPane() = withAttachedSurface { state ->
        state.appendRemoteOutput("[2J[H".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "precondition: a clear-only frame is fully blank",
            state.visibleScreenIsBlank(),
        )
        assertTrue(
            "the combined oracle must be true for a fully-blank pane",
            state.visibleScreenIsBlankOrPartiallyBlank(),
        )
    }

    @Test
    fun blankOrPartiallyBlankIsTrueForAPartialBlackPane() = withAttachedSurface { state ->
        // The #941 symptom: a send/switch overpaint wiped the static viewport and
        // left ONE live line. Fully-blank is false (the line is present), but the
        // combined oracle must catch it so the heal fires.
        state.appendRemoteOutput("[2J[HISSUE941-OVERPAINT 7\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "precondition: a partial-black pane is NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertTrue(
            "precondition: a partial-black pane IS partially blank",
            state.visibleScreenIsPartiallyBlank(),
        )
        assertTrue(
            "the combined oracle must be true for a partial-black pane (the heal " +
                "gates the switch-reveal / watchdog / send-overpaint paths read on it)",
            state.visibleScreenIsBlankOrPartiallyBlank(),
        )
    }

    @Test
    fun blankOrPartiallyBlankIsFalseForANormallyPaintedPane() = withAttachedSurface { state ->
        val frame = buildString {
            append("[2J[H")
            repeat(20) { append("line $it has real content here\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "the combined oracle must be FALSE for a normally-painted pane so the " +
                "heal correctly no-ops (no reseed-thrash on a healthy pane)",
            state.visibleScreenIsBlankOrPartiallyBlank(),
        )
    }
}
