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
 * Issue #1138 — [TerminalSurfaceState.visibleRenderLostFrameVsCapture] is the steady-state
 * watchdog's "the live render LOST the frame" predicate for a live-streaming AGENT pane.
 *
 * ## The maintainer's report (v0.4.19 dogfood, 2026-07-01)
 *
 * A connected Claude Code AND a connected Codex session (host `pocketshell` / `podwiki`) each
 * showed a SEMI/PARTIAL-black terminal: only the agent's live status line (`∘ Working 7`) +
 * cursor near the bottom, the upper ALT-SCREEN rows black. The agent redraws with cursor-
 * addressed writes, so only its live status line repaints locally while the upper rows the
 * pane lost stay black. The pane is on the ALTERNATE screen (Codex/Claude run a full-screen
 * TUI), and an alt-screen frame is SPARSE: a header + a large blank conversation area + an
 * input/status line. So its non-blank content is small, and the surviving status line is a
 * LARGE fraction of it — ABOVE the #966 25% divergence ceiling. The v0.4.18 steady-state
 * stale-render watchdog's ONLY heal predicate was [visibleScreenDivergesFromCapture], so it
 * read the pane "healthy" and never fired — the pane sat mostly-black on a LIVE transport.
 *
 * ## RED → GREEN (D33/G1/G10)
 *
 * Each test drives a REAL emulator onto the alternate screen into the exact partial-black
 * state, then pins:
 *  - RED gap: the OLD predicate [visibleScreenDivergesFromCapture] returns FALSE against the
 *    agent's authoritative full alt-screen capture (so the watchdog would SKIP the heal).
 *  - GREEN fix: the union predicate [visibleRenderLostFrameVsCapture] returns TRUE (heal fires).
 *
 * ## Class coverage (D32-G2) — the whole class, not the one reported instance
 *  - alt-screen partial-black (status-line-only) against a full agent frame → heals (the bug).
 *  - the #966 scattered-fragment case still heals (union preserves divergence).
 *  - anti-thrash: a legitimately-short prompt (tmux ≈ render) → does NOT heal.
 *  - the #807 by-design alt-screen void (tmux ALSO near-empty) → does NOT heal.
 *  - a healthy fully-painted pane → does NOT heal.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateAgentPartialBlackTest {

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

    /** ESC, so the test feeds real control sequences. */
    private val esc = ""

    /**
     * The full alt-screen agent frame tmux's grid holds while "Working": a header, a couple
     * of conversation lines, a large BLANK conversation area (padding — the sparse part), an
     * input box and the live status line. Sparse: ~120 non-blank chars, so the surviving
     * status line (~40 chars) is ~33% of it — above the 25% divergence ceiling.
     */
    private fun fullAgentAltCapture(): String = buildString {
        append("HEADER: codex session on podwiki\n")
        append("You: fix the partial-black bug\n")
        append("Codex: sure, here is the plan and the details of the change\n")
        repeat(10) { append("\n") } // blank conversation area while Working (the sparse part)
        append("input box: >_\n")
        append(" Working 7  esc to interrupt\n")
    }

    /**
     * Issue #1153 — the full alt-screen agent frame tmux's grid holds after a multi-line send:
     * a header + a real (non-sparse) conversation body filling ~20 rows. Its non-blank content
     * is LARGE, so the surviving half-black band (~8 lines) is well under the 25% divergence
     * ceiling of it — the RED gap the #966 oracle leaves.
     */
    private fun fullAgentFrame(): String = buildString {
        append("HEADER: codex full conversation frame on podwiki host\n")
        repeat(19) { row -> append("Codex conversation row $row : a real line of agent output with content\n") }
    }

    /**
     * Issue #1153 — drive the emulator onto the alt screen, clear, and paint 8 HARD-newline
     * content lines, leaving the remaining ~2/3 of the 24-row viewport BLACK. MORE than 3 live
     * lines (so NOT the ≤3-line partial-black) yet only ~1/3 of the visible rows live — the
     * half-black band. (Hard `\r\n` lines, NOT cursor-addressed: contiguous cursor-positioned
     * short lines are joined into ONE logical line by `getSelectedText`, which would read as a
     * single live line.)
     */
    private fun paintHalfBlackBand(state: TerminalSurfaceState) {
        val frame = buildString {
            append("$esc[?1049h$esc[2J$esc[H")
            for (row in 0 until 8) {
                append("ISSUE1153 half-black conversation/status line $row filler content here\r\n")
            }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Drive the emulator onto the alt screen and paint ONLY the live status line. */
    private fun paintAltScreenPartialBlack(state: TerminalSurfaceState) {
        val frame = buildString {
            append("$esc[?1049h")   // enter alternate screen buffer
            append("$esc[2J$esc[H") // clear + home
            append("$esc[24;1H Working 7  esc to interrupt")
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun altScreenPartialBlackIsMissedByDivergenceButHealedByUnion() = withAttachedSurface { state ->
        paintAltScreenPartialBlack(state)

        // Preconditions: this IS the maintainer's partial-black, NOT fully blank.
        assertFalse(
            "precondition: a live status line makes the pane NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertTrue(
            "precondition: the alt-screen agent pane IS partial-black (#1138 symptom)",
            state.visibleScreenIsPartiallyBlank(),
        )

        // RED: the old divergence oracle reads the pane "healthy" — the surviving status
        // line exceeds 25% of the sparse alt-screen frame, so the v0.4.18 watchdog SKIPS it.
        assertFalse(
            "RED (#1138): the #966 divergence oracle MISSES the alt-screen partial-black — " +
                "its surviving band exceeds the 25% ceiling of the sparse agent frame",
            state.visibleScreenDivergesFromCapture(fullAgentAltCapture()),
        )

        // GREEN: the union predicate heals it — tmux holds materially more (the full frame).
        assertTrue(
            "GREEN (#1138): the union predicate must detect the alt-screen partial-black " +
                "against the agent's authoritative full capture and heal it",
            state.visibleRenderLostFrameVsCapture(fullAgentAltCapture()),
        )
    }

    /**
     * Issue #1153 — the >3-live-line HALF-BLACK render the pre-#1153 union oracle MISSED. A
     * composer Send with an attachment is always multi-line → bracketed-paste + submit → the
     * alt-screen agent clear+redraws its WHOLE viewport, and the overpaint leaves a large black
     * BAND above a surviving input box + a few conversation lines + status. That band has MORE
     * than the ≤3 live lines the partial-black heuristic caps at, so BOTH the #966 divergence
     * oracle (surviving band > 25% of the frame) AND the pre-#1153 case (b) (requires
     * partial-black) MISS it — the "partly redrew but still too black" state.
     */
    @Test
    fun halfBlackOverThreeLinesIsMissedByDivergenceButHealedByUnion() = withAttachedSurface { state ->
        paintHalfBlackBand(state)

        assertFalse(
            "precondition: a half-black band is NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        assertFalse(
            "precondition (#1153): the half-black band has MORE than 3 live lines, so it is NOT " +
                "the ≤3-line partial-black the pre-#1153 case (b) required",
            state.visibleScreenIsPartiallyBlank(),
        )
        // RED gap: the #966 divergence oracle reads the >3-line band "healthy" (its surviving
        // content exceeds the 25% ceiling of the full frame), so the pre-#1153 union missed it.
        assertFalse(
            "RED (#1153): the #966 divergence oracle MISSES the >3-line half-black band",
            state.visibleScreenDivergesFromCapture(fullAgentFrame()),
        )
        // GREEN: the widened union predicate heals it — the render lost most of the frame tmux
        // still holds, and its live share of the visible rows is below the ceiling.
        assertTrue(
            "GREEN (#1153): the widened union predicate must heal a >3-line half-black band " +
                "against tmux's authoritative full frame",
            state.visibleRenderLostFrameVsCapture(fullAgentFrame()),
        )
    }

    /**
     * Issue #1153 over-heal guard: a DENSE render (well over half the visible rows live) must
     * NOT heal even when tmux's capture carries somewhat MORE — the fraction ceiling keeps a
     * normally-painted, slightly-lagging pane from re-seeding on every send/watchdog tick.
     */
    @Test
    fun denseRenderDoesNotHealEvenWhenCaptureHasSomewhatMore() = withAttachedSurface { state ->
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(20) { row -> append("dense row $row : lots of real painted agent content here padding text\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        // tmux holds a few MORE rows than the render (a slight stream lag), but the render is
        // DENSE (20 of 24 rows live) — above the half-black ceiling — so it must NOT heal.
        val fullerCapture = buildString {
            repeat(24) { row -> append("dense row $row : lots of real painted agent content here padding text\n") }
        }
        assertFalse(
            "a dense render (live rows above the ceiling) must NOT heal even when tmux holds a " +
                "few more rows — no reseed-thrash on a slightly-lagging healthy pane",
            state.visibleRenderLostFrameVsCapture(fullerCapture),
        )
    }

    @Test
    fun scatteredFragmentStillHealsThroughUnion() = withAttachedSurface { state ->
        // The #966 case: the union must still fire (divergence branch preserved).
        val fragments = buildString {
            append("$esc[2J$esc[H")
            append("3\r\n")
            append("$esc[10;1H")
            append("24m 3 / 8 / 4 / 3 / 31\r\n")
            append("$esc[15;40H")
            append("x\r\n")
            append("$esc[20;5H")
            append("y z\r\n")
        }
        state.appendRemoteOutput(fragments.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        val fullTui = buildString {
            repeat(24) { row -> append("status row $row : a lot of real agent TUI content here padding\n") }
        }
        assertTrue(
            "the #966 scattered-fragment case must still heal through the union predicate",
            state.visibleRenderLostFrameVsCapture(fullTui),
        )
    }

    @Test
    fun shortPromptDoesNotThrashHeal() = withAttachedSurface { state ->
        // A legitimately-short prompt: render matches a short capture — partial-black by the
        // heuristic, but tmux ALSO has only those few lines, so the union must NOT heal
        // (no reseed-thrash on every 4s watchdog tick).
        val sparse = "user@host:~$ ls\r\n"
        state.appendRemoteOutput("$esc[2J$esc[H$sparse".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "anti-thrash: a short prompt whose tmux capture matches the render must NOT heal",
            state.visibleRenderLostFrameVsCapture("user@host:~$ ls\n"),
        )
    }

    @Test
    fun byDesignAltScreenVoidDoesNotHeal() = withAttachedSurface { state ->
        // The #807 by-design void: the agent's OWN alt-screen is genuinely near-empty (just
        // the status line). tmux's capture ALSO carries only the status line → nothing to
        // restore → the union must NOT heal (distinguish the render bug from a real clear).
        paintAltScreenPartialBlack(state)
        val voidCapture = " Working 7  esc to interrupt\n"
        assertFalse(
            "#807: when tmux's alt-screen is ALSO near-empty (agent's own clear) the union " +
                "must NOT heal — there is no lost frame to restore",
            state.visibleRenderLostFrameVsCapture(voidCapture),
        )
    }

    @Test
    fun healthyFullyPaintedPaneDoesNotHeal() = withAttachedSurface { state ->
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(24) { row -> append("status row $row : a lot of real agent TUI content here padding\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        val fullTui = buildString {
            repeat(24) { row -> append("status row $row : a lot of real agent TUI content here padding\n") }
        }
        assertFalse(
            "a healthy fully-painted pane (render matches tmux) must NOT heal",
            state.visibleRenderLostFrameVsCapture(fullTui),
        )
    }
}
