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
 * Epic #1353 slice R1 — the render-heal "is this pane bad?" pre-gate UNIFICATION.
 *
 * ## The bug class (#1153 desync)
 *
 * The render-heal stack used to run THREE divergent local pre-gates that did not move together —
 * a send-only 0.5 line-fraction ceiling (`visibleScreenLooksSparseForSendHeal`), a reveal/resize
 * 0.75 ceiling with a `liveLines > 3` floor (`visibleRenderMayHaveLostFrame`), and the blank/
 * partial family. For the SAME pane state a launcher could judge "suspect → heal" while another
 * judged "healthy → skip", so a fix in one layer was silently invalidated when the oracle changed
 * underneath it. #1153 broke on exactly this: a >3-line, >50%-live half-black band was healed on a
 * switch-reveal (0.75) but SKIPPED after a send (0.5) — the same pane, two answers.
 *
 * R1 collapses the divergent local cost-gates into the ONE
 * [TerminalSurfaceState.renderLooksSuspect] authority every launcher shares, defined as a strict
 * SUPERSET of all three old gates (0.75 ceiling, no `liveLines > 3` floor). So no launcher loses
 * heal coverage and, crucially, no two launchers can disagree about "consult the oracle" for a
 * given pane — a change to the oracle can never desync one launcher relative to another again.
 *
 * ## RED → GREEN (D33/G1/G10)
 *
 * [unifiedSuspectAuthorityFlagsBandTheOldSendOnlyCeilingSkipped] composes the exact #1153 pane: a
 * >3-line, ~58%-live half-black band. With the unified 0.75 authority the ONE predicate flags it
 * (GREEN). Captured RED: temporarily narrowing [RENDER_SUSPECT_MAX_LIVE_FRACTION] back to the old
 * send-only 0.5 (the pre-R1 divergence) makes `renderLooksSuspect()` return FALSE for this band and
 * this assertion fails — the desync reproduced. Widening back to 0.75 is GREEN.
 *
 * ## Class coverage (D32-G2)
 *
 * [unifiedAuthorityIsSupersetAcrossTheWholeSuspectRange] parametrizes the whole not-confidently-
 * dense spectrum (fully blank, ≤3-line partial-black, the >0.5..0.75 band the old send ceiling
 * skipped, the <0.5 mostly-empty model, up to the 0.75 boundary) and asserts the single authority
 * flags EVERY member — while a confidently-dense pane (live share above 0.75) is NOT flagged (no
 * over-flag). The geometry is the real visible render, not a proxy.
 */
@RunWith(RobolectricTestRunner::class)
class RenderHealPreGateUnificationTest {

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

    /** Paint [liveRows] non-wrapping content lines on a cleared screen — a contiguous black band above. */
    private fun paintBand(state: TerminalSurfaceState, liveRows: Int) {
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(liveRows) { append("$contentLine\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // -----------------------------------------------------------------------------------------
    // RED → GREEN: the exact #1153 pane where send (0.5) and reveal (0.75) diverged.
    // -----------------------------------------------------------------------------------------

    @Test
    fun unifiedSuspectAuthorityFlagsBandTheOldSendOnlyCeilingSkipped() = withAttachedSurface { state ->
        // 14 of 24 rows live (~42% black): live-fraction 0.58, which is > 0.5 (so the OLD send-only
        // 0.5 ceiling read it NON-suspect and skipped the capture) yet ≤ 0.75.
        paintBand(state, liveRows = 14)

        assertFalse(
            "precondition: a >3-line half-black band is NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertFalse(
            "precondition: it has MORE than 3 live lines, so it is NOT the ≤3-line partial-black",
            state.visibleScreenIsPartiallyBlank(),
        )

        // GREEN: the ONE unified authority flags the band — so the send launcher and the reveal
        // launcher now AGREE (both consult the oracle for this state). RED when the ceiling is
        // narrowed to the old send-only 0.5: 0.58 > 0.5 → returns false → this assertion fails,
        // reproducing the #1153 send/reveal desync.
        assertTrue(
            "R1 (#1153): the single renderLooksSuspect() authority must flag a >3-line, ~58%-live " +
                "half-black band — the pane the old send-only 0.5 ceiling skipped while the reveal " +
                "0.75 gate caught it (the desync). One authority: send and reveal agree.",
            state.renderLooksSuspect(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Class coverage: the single authority is a strict SUPERSET across the whole suspect range,
    // and does NOT over-flag a confidently-dense pane.
    // -----------------------------------------------------------------------------------------

    @Test
    fun unifiedAuthorityIsSupersetAcrossTheWholeSuspectRange() {
        // liveRows-of-24 → live-fraction. Every member at or below the 0.75 ceiling must be flagged
        // by the ONE authority (blank, ≤3-line partial-black, the <0.5 mostly-empty model, the
        // >0.5..0.75 dead-zone band the old send 0.5 ceiling skipped, and the exact 0.75 boundary).
        val suspectLiveRows = listOf(
            0,  // fully blank            → 0.00
            2,  // ≤3-line partial-black  → 0.08
            6,  // mostly-empty model     → 0.25
            10, // <0.5 band              → 0.42
            13, // >0.5 dead-zone band    → 0.54 (old send 0.5 ceiling skipped it)
            14, // >0.5 dead-zone band    → 0.58 (the #1153 pane)
            17, // >0.5 dead-zone band    → 0.71 (old send 0.5 ceiling skipped it)
            18, // exact 0.75 boundary    → 0.75
        )
        for (liveRows in suspectLiveRows) {
            withAttachedSurface { state ->
                paintBand(state, liveRows)
                val liveFraction = liveRows / 24.0
                assertTrue(
                    "R1 class coverage: a pane with $liveRows/24 live rows (fraction $liveFraction " +
                        "≤ 0.75) must be flagged suspect by the ONE unified authority",
                    state.renderLooksSuspect(),
                )
            }
        }

        // Over-flag guard: a confidently-dense pane (live share ABOVE 0.75) is NOT suspect — the
        // single authority does not widen into a normally-painted frame (both old gates agreed here).
        for (liveRows in listOf(20, 22, 24)) { // 0.83, 0.92, 1.00
            withAttachedSurface { state ->
                paintBand(state, liveRows)
                assertFalse(
                    "over-flag guard: a confidently-dense pane ($liveRows/24 live, > 0.75) must NOT " +
                        "be flagged suspect by the unified authority",
                    state.renderLooksSuspect(),
                )
            }
        }
    }

    @Test
    fun noEmulatorAttachedIsNotSuspect() {
        // A brand-new state with no attached producer/emulator has nothing rendered to judge — the
        // authority must be conservative (false), never spuriously suspect.
        assertFalse(TerminalSurfaceState().renderLooksSuspect())
    }
}
