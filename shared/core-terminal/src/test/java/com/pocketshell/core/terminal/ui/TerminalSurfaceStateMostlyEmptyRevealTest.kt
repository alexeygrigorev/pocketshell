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
 * Issue #1214 — the reveal/resize/switch LOCAL pre-check
 * [TerminalSurfaceState.renderLooksSuspect] was BLIND to a mostly-empty model.
 *
 * The authoritative steady-watchdog oracle [TerminalSurfaceState.visibleRenderLostFrameVsCapture]
 * is a char-count-fraction test that HEALS a mostly-empty-model-vs-full-tmux pane. But the cheap
 * LOCAL pre-check that gates the reveal gate
 * ([com.pocketshell.app.tmux.TmuxSessionViewModel.awaitActivePaneSeededOrLoading]) and the no-op
 * resize heal ([com.pocketshell.app.tmux.TmuxSessionViewModel.maybeHealActivePaneOnNoOpResize])
 * only flagged a live-fraction in `(0.5, 0.75]` or a ≤3-line partial-blank. So a mostly-empty
 * pane with MORE than 3 scattered live lines but a live-fraction BELOW 0.5 read "healthy" at the
 * reveal gate, revealed UNHEALED (fragments-over-black), and only the ≤16s-later steady watchdog
 * could catch it. This is the reveal-time leg of the photographed fragments-over-black (#1208).
 *
 * ## RED → GREEN (D33/G1/G10)
 *
 * [mostlyEmptyModelIsFlaggedSoTheRevealGatePaysTheAuthoritativeCapture] composes a >3-live-line
 * pane whose live-fraction is BELOW 0.5 (the mostly-empty model), against a full-tmux capture the
 * authoritative oracle WOULD heal. On base [renderLooksSuspect] returns FALSE (reads
 * "healthy" — RED: the reveal gate skips the capture and reveals unhealed). With the fix it
 * returns TRUE (GREEN: the reveal gate pays ONE authoritative diff and heals AT reveal). The test
 * also asserts the authoritative oracle itself judges the pane LOST — proving the pre-check was
 * the ONLY thing blocking the reveal-time heal.
 *
 * ## Class coverage (D32-G2)
 *
 * [mostlyEmptyModelIsFlaggedAcrossTheSparseSpectrum] parametrizes 4..11 live lines of 24 (all
 * below the 0.5 floor, all above the 3-line partial-blank cap) so the whole mostly-empty class is
 * flagged, not the single reported instance. [sparseButCorrectPaneNoOpsViaGate2] and
 * [nearEmptyAltScreenVoidNoOpsViaGate1] pin the two self-guards: a genuinely-sparse-but-correct
 * short prompt (capture ≈ render, Gate 2) and the #807 near-empty alt-screen void (capture < 40
 * chars, Gate 1) may now be pre-flagged (one wasted capture) but the authoritative oracle STILL
 * refuses to heal — so a false pre-flag never causes a wrong heal or reseed-thrash.
 * [confidentlyDensePaneStillSkipsTheCapture] confirms a dense pane still short-circuits the
 * capture (no per-reveal cost on a healthy pane, the #1166 battery guard for the local gate).
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateMostlyEmptyRevealTest {

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

    /** ESC (U+001B), so the test feeds real control sequences (a literal `[2J` is just text). */
    private val esc = ""

    /** A single non-wrapping content line: 20 non-blank chars, well under the 80-col grid. */
    private val contentLine = "abcdefghij klmnopqrst"

    /**
     * tmux's authoritative capture: a full 24-row viewport of [contentLine] (the emulator's
     * visible-row count is 24). A render that painted only a few of those rows lost the frame tmux
     * still holds — the mostly-empty-model-vs-full-tmux case #1214 is about.
     */
    private fun fullCapture(): String = buildString { repeat(24) { append("$contentLine\n") } }

    /** Paint [liveRows] non-wrapping content lines on a cleared screen — a mostly-empty model. */
    private fun paintLines(state: TerminalSurfaceState, liveRows: Int) {
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(liveRows) { append("$contentLine\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // -----------------------------------------------------------------------------------------
    // RED → GREEN: the mostly-empty model the pre-check judged "healthy" at reveal.
    // -----------------------------------------------------------------------------------------

    @Test
    fun mostlyEmptyModelIsFlaggedSoTheRevealGatePaysTheAuthoritativeCapture() =
        withAttachedSurface { state ->
            // 6 of 24 rows live: live-fraction 0.25 (BELOW the 0.5 floor) AND more than 3 live
            // lines (so it escapes the ≤3-line partial-black net). This is the mostly-empty model.
            paintLines(state, liveRows = 6)
            val capture = fullCapture()

            assertFalse(
                "precondition: a 6-live-line model is NOT fully blank",
                state.visibleScreenIsBlank(),
            )
            assertFalse(
                "precondition: it has MORE than 3 live lines, so it is NOT the ≤3-line partial-black " +
                    "(the ONLY sub-0.5 case the base pre-check flagged)",
                state.visibleScreenIsPartiallyBlank(),
            )

            // The authoritative oracle WOULD heal this pane (tmux holds a full frame, the render
            // reproduces only ~25% of it) — so the ONLY thing standing between the reveal and a
            // healed pane is the cheap local pre-check.
            assertTrue(
                "premise: the authoritative count-diff oracle judges this mostly-empty model LOST — " +
                    "the reveal-time heal is available IF the pre-check opens the capture",
                state.visibleRenderLostFrameVsCapture(capture),
            )

            // GREEN (#1214): the pre-check must flag the mostly-empty model so the reveal gate and
            // the no-op-resize heal pay ONE authoritative diff instead of reading "healthy". On
            // base this returns FALSE (reads healthy, reveals unhealed) — the red→green
            // discriminator.
            assertTrue(
                "GREEN (#1214): renderLooksSuspect() must flag a >3-live-line pane with " +
                    "live-fraction < 0.5 so the reveal/resize gate pays the authoritative capture",
                state.renderLooksSuspect(),
            )
        }

    // -----------------------------------------------------------------------------------------
    // Class coverage (G2): the WHOLE mostly-empty spectrum (>3 lines, < 0.5 fraction) is flagged.
    // -----------------------------------------------------------------------------------------

    @Test
    fun mostlyEmptyModelIsFlaggedAcrossTheSparseSpectrum() {
        val capture = fullCapture()
        // 4..11 live rows of 24: all ABOVE the 3-line partial-black cap and all BELOW the 0.5
        // live-fraction floor (11/24 = 0.458). The whole mostly-empty class must be flagged.
        for (liveRows in 4..11) {
            withAttachedSurface { state ->
                paintLines(state, liveRows)
                val liveFraction = liveRows / 24.0
                assertTrue(
                    "precondition: $liveRows live rows is below the 0.5 floor (fraction $liveFraction)",
                    liveFraction < 0.5,
                )
                assertFalse(
                    "precondition: $liveRows live rows escapes the ≤3-line partial-black net",
                    state.visibleScreenIsPartiallyBlank(),
                )
                assertTrue(
                    "#1214 class coverage: a $liveRows-of-24 mostly-empty model must be flagged so " +
                        "the reveal/resize gate pays the authoritative capture",
                    state.renderLooksSuspect(),
                )
                // And the authoritative oracle confirms the heal — the reveal is not paying for
                // nothing across the class.
                assertTrue(
                    "#1214 class coverage: the authoritative oracle heals the $liveRows-of-24 pane",
                    state.visibleRenderLostFrameVsCapture(capture),
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Self-guards (G2): a false pre-flag costs ONE wasted capture, never a wrong heal / thrash.
    // -----------------------------------------------------------------------------------------

    @Test
    fun sparseButCorrectPaneNoOpsViaGate2() = withAttachedSurface { state ->
        // A genuinely-sparse-but-correct SHORT prompt: 5 live lines, and tmux's capture is the
        // SAME 5 lines (render ≈ capture). The pre-check may now flag it (one wasted capture),
        // but the authoritative oracle's Gate 2 (capture must carry materially MORE than the
        // render) refuses the heal → NO reseed-thrash.
        paintLines(state, liveRows = 5)
        val sparseCorrectCapture = buildString { repeat(5) { append("$contentLine\n") } }

        assertTrue(
            "the pre-check may pre-flag the sparse pane (one wasted capture is acceptable)",
            state.renderLooksSuspect(),
        )
        assertFalse(
            "Gate 2 self-guard: a sparse-but-correct prompt where capture ≈ render must NOT heal " +
                "(no reseed-thrash on a legitimately-short pane)",
            state.visibleRenderLostFrameVsCapture(sparseCorrectCapture),
        )
    }

    @Test
    fun nearEmptyAltScreenVoidNoOpsViaGate1() = withAttachedSurface { state ->
        // The #807 by-design near-empty alt-screen void: a few live render lines but tmux's
        // capture carries < 40 non-blank chars (Gate 1: no real frame to restore). The pre-check
        // may pre-flag the render, but the oracle's Gate 1 refuses to heal a genuinely-empty pane
        // to itself.
        paintLines(state, liveRows = 5)
        val nearEmptyCapture = "ab\n" // < STALE_RENDER_MIN_CAPTURE_CHARS (40) non-blank chars

        assertFalse(
            "#807 Gate 1 self-guard: a near-empty tmux capture (< 40 chars) must NOT heal — the " +
                "genuinely-empty alt-screen void is left alone",
            state.visibleRenderLostFrameVsCapture(nearEmptyCapture),
        )
    }

    // -----------------------------------------------------------------------------------------
    // #1166 battery guard for the LOCAL gate: a confidently-dense pane still skips the capture.
    // -----------------------------------------------------------------------------------------

    @Test
    fun confidentlyDensePaneStillSkipsTheCapture() = withAttachedSurface { state ->
        // 22 of 24 rows live (~8% black): live-fraction 0.92, well ABOVE the 0.75 ceiling. A
        // confidently-full pane must NOT pay for a capture on every reveal/resize.
        paintLines(state, liveRows = 22)
        assertFalse(
            "battery guard: a confidently-dense pane (live-fraction 0.92 > 0.75) must NOT be " +
                "flagged — no per-reveal capture cost on a healthy pane",
            state.renderLooksSuspect(),
        )
    }
}
