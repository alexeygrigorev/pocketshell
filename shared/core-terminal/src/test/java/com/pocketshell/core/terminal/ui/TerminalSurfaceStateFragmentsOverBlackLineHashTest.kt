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
 * Issue #1300 — the heal oracle's divergence predicate
 * ([TerminalSurfaceState.visibleRenderLostFrameVsCapture]) must be a PER-LINE-HASH content
 * diff, not a scalar char-count. The scalar counted total non-blank chars on each side and
 * scored the pane HEALTHY whenever the counts roughly matched — the exact blind spot behind
 * the maintainer's photographed BLACK panes: a live, char-dense spinner/status block (Claude/
 * Codex progress bar, token counts, "esc to interrupt", context %) survives over an otherwise-
 * black grid, so the render's TOTAL char count matches a full tmux screen even though the two
 * grids share almost NO content lines. Count parity != content parity.
 *
 * ## The `capture-pane -e` reality (round-2 fix, #1300)
 *
 * The heal capture is issued as `capture-pane -e` (`TmuxClient.kt`), which embeds each cell's
 * ANSI SGR/colour escapes so the seed path can repaint colour. The render side
 * (`getVisibleScreenText`) is PLAIN text — the VT parser already consumed the escapes. So a
 * per-line hash diff MUST strip the capture's ANSI escapes first, or a coloured capture line
 * (`ESC[32m…ESC[0m`) never hash-matches its plain render line and EVERY coloured pane (i.e.
 * essentially every real agent/shell pane) is scored divergent → reseed-thrash on every
 * watchdog tick (the #1164/#1219 battery/heat regression). Every healthy case below therefore
 * uses a REALISTIC coloured `-e` capture (raw SGR sequences), not a plain fixture — the plain
 * fixture is exactly the happy-fixture-masks-reality trap that let the round-1 false positive
 * through (F2/G10).
 *
 * ## RED → GREEN (D33/G1/G10)
 *
 *  - [fragmentsOverBlackWithMatchingCharCountAreDivergent] — RED on the base scalar (char parity
 *    reads healthy), GREEN with the line-hash diff; the coloured sparse capture proves stripping
 *    does not mask the real divergence.
 *  - The healthy-COLOURED cases ([healthyDenseColoredPaneIsNotFlagged],
 *    [coloredBackgroundRegionIsNotFlagged], [coloredMostlyEmptyPromptIsNotFlagged]) are RED on the
 *    round-1 predicate (which only `.trim()`s — a coloured capture line never matches its plain
 *    render line → scored divergent) and GREEN once the capture side is ANSI-stripped.
 *
 * ## Class coverage (D32-G2) — the whole class, not the one reported instance
 *  - fragments-over-black with MATCHING char count → divergent (the reported blind spot).
 *  - the #1214 mostly-empty-few-scattered-lines model → divergent.
 *  - a genuinely-healthy DENSE COLOURED pane → NOT flagged (G6 false-positive guard).
 *  - a healthy COLOURED-BACKGROUND (styled-space) TUI region → NOT flagged (bg-colour symmetry).
 *  - a mostly-empty healthy COLOURED shell prompt → NOT flagged.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateFragmentsOverBlackLineHashTest {

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

    private val esc = ""

    /** Test-side ANSI stripper, mirroring the production normalization, for char-count assertions. */
    private val ansiRegex =
        Regex("(?:\\][^]*(?:|\\\\)|\\[[0-?]*[ -/]*[@-~]|[@-Z\\\\^_])")

    private fun stripAnsi(s: String): String = ansiRegex.replace(s, "")

    /**
     * Replicates the SCALAR predicate's capture-side count on the REAL (escape-stripped) content, so
     * the RED precondition — "char totals match, so the scalar scores it healthy" — is asserted on
     * the content chars a coloured `-e` capture actually carries (not inflated by escape bytes).
     */
    private fun captureVisibleNonBlank(state: TerminalSurfaceState, capture: String): Int {
        val rows = state.visibleRowCount().coerceAtLeast(1)
        return capture
            .split('\n')
            .map { stripAnsi(it) }
            .filter { it.isNotBlank() }
            .takeLast(rows)
            .sumOf { line -> line.count { !it.isWhitespace() } }
    }

    /** The plain content of agent row [row] — what the render's visible text holds after the VT parse. */
    private fun plainAgentRow(row: Int): String =
        "Codex conversation row $row : a real line of agent output text here"

    /**
     * The SAME row as a real `capture-pane -e` line carries it: bold + a truecolor foreground SGR on
     * the "Codex" token, a 256-colour SGR on the body, and SGR resets — i.e. the escape shapes a
     * coloured agent frame actually emits. Strips back to exactly [plainAgentRow].
     */
    private fun coloredAgentRow(row: Int): String =
        "$esc[1m$esc[38;2;97;175;239mCodex$esc[0m conversation row $row : " +
            "$esc[38;5;41ma real line of agent output text here$esc[0m"

    /** The full agent frame tmux authoritatively holds, PLAIN — the content the render should show. */
    private fun fullAgentFrame(): String = buildString {
        repeat(24) { row -> append(plainAgentRow(row)).append('\n') }
    }

    /** The full agent frame as a realistic coloured `capture-pane -e` snapshot. */
    private fun fullAgentFrameColored(): String = buildString {
        repeat(24) { row -> append(coloredAgentRow(row)).append('\n') }
    }

    /**
     * The SPARSE alt-screen agent frame tmux authoritatively holds while "Working", as a realistic
     * coloured `-e` capture: a header, a couple of conversation lines, a large BLANK conversation
     * area, an input box and the live status line. Sparse by design, so its content chars are spread
     * across a handful of DISTINCT content lines — and a surviving spinner block of a similar char
     * total on the render side reads as char-count parity to a scalar oracle.
     */
    private fun sparseAgentFrameColored(): String = buildString {
        append("$esc[1;36mHEADER:$esc[0m codex session on podwiki host, model gpt-5-codex\n")
        append("$esc[33mYou:$esc[0m please fix the partial-black rendering bug now thanks\n")
        append("$esc[32mCodex:$esc[0m sure, here is the plan and the change details below\n")
        repeat(10) { append("\n") } // the sparse blank conversation area while Working
        append("$esc[2minput box: type your next message here >_$esc[0m\n")
        append("$esc[7mWorking  esc to interrupt  tokens 4321  ctx 44%$esc[0m\n")
    }

    @Test
    fun fragmentsOverBlackWithMatchingCharCountAreDivergent() = withAttachedSurface { state ->
        // The maintainer's photographed black pane: only a char-DENSE live spinner/status block
        // survives over an otherwise-black grid. Against tmux's SPARSE alt-screen frame (a TUI at
        // rest) the surviving block's total char count MATCHES the frame's — exactly the parity
        // that fools a scalar char-count oracle into scoring the pane HEALTHY.
        val statusBlob = "SPINNER progress-bar working esc-to-interrupt " // ~40 non-blank chars
        val fragments = buildString {
            append("$esc[?1049h") // alternate screen
            append("$esc[2J$esc[H") // clear + home
            // A couple of long, wrapping status/spinner lines over a black grid — few content
            // LINES, many chars — tuned so the render's char count ≈ the sparse frame's.
            append("${statusBlob.repeat(2)}\r\n")
            append("${statusBlob.repeat(2)}\r\n")
            append("$esc[24;1H${statusBlob.trim()}") // the live spinner line at the bottom
        }
        state.appendRemoteOutput(fragments.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        // A realistic coloured `-e` capture — proves ANSI stripping does NOT mask the real
        // divergence (the render's spinner lines still share no content line with tmux's frame).
        val capture = sparseAgentFrameColored()
        val rendered = state.renderedNonBlankCharCount()
        val cap = captureVisibleNonBlank(state, capture)

        // RED precondition — char parity puts the pane in the SCALAR's healthy zone: the render's
        // char count sits ABOVE the scalar's `0.75 * capture` heal ceiling, so the scalar scores
        // this fragments-over-black pane HEALTHY (returns false) even though the two grids share no
        // content lines.
        assertTrue(
            "precondition: render carries real content (a char-dense spinner block), not black",
            rendered > 0,
        )
        assertTrue(
            "RED precondition (#1300): the render's char count sits ABOVE the scalar's 0.75 " +
                "healthy ceiling of the capture (rendered=$rendered cap=$cap) — the scalar scores " +
                "this fragments-over-black pane HEALTHY",
            rendered.toDouble() > cap * 0.75,
        )

        // LOAD-BEARING (GREEN): the per-line-hash diff sees that NONE of tmux's content lines are
        // reproduced by the render (the render's spinner lines differ from every tmux line) — the
        // pane is divergent and must be healed, despite the matching char totals and despite the
        // capture being coloured.
        assertTrue(
            "GREEN (#1300): the per-line-hash divergence oracle must score fragments-over-black " +
                "as DIVERGENT even when total char counts match a full tmux screen, and even when " +
                "the capture is a coloured -e snapshot (rendered=$rendered cap=$cap)",
            state.visibleRenderLostFrameVsCapture(capture),
        )
    }

    @Test
    fun mostlyEmptyFewScatteredLinesAreDivergent() = withAttachedSurface { state ->
        // Issue #1214 class: a handful of scattered live lines over a black grid, tmux holds the
        // full frame. The render reproduces only a few of tmux's content lines → divergent. The
        // capture is a coloured -e frame, proving stripping does not turn the divergent case healthy.
        val fragments = buildString {
            append("$esc[2J$esc[H")
            append("3\r\n")
            append("$esc[10;1H")
            append("24m 3 / 8 / 4 / 3 / 31\r\n")
            append("$esc[15;1H")
            append("scattered fragment line\r\n")
            append("$esc[20;1H")
            append("another surviving fragment\r\n")
        }
        state.appendRemoteOutput(fragments.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the #1214 mostly-empty model (few scattered live lines vs a full coloured tmux frame) " +
                "must score DIVERGENT under the per-line-hash diff",
            state.visibleRenderLostFrameVsCapture(fullAgentFrameColored()),
        )
    }

    @Test
    fun healthyDenseColoredPaneIsNotFlagged() = withAttachedSurface { state ->
        // G6 false-positive guard — the round-1 blocker. A fully-painted COLOURED pane whose visible
        // content matches tmux's content lines must NOT be scored divergent. The capture is a real
        // `capture-pane -e` snapshot (raw SGR escapes); the render side (visible text) is plain. On
        // the round-1 predicate (which only `.trim()`s) the coloured capture lines never hash-match
        // the plain render lines → EVERY line lost → divergent=true → this assertFalse is RED. With
        // the capture-side ANSI strip the two collapse to identical content → 0 lost → GREEN. A
        // spurious heal here would clear+repaint on every watchdog tick and burn battery
        // (#1164/#1219), on essentially every real coloured agent/shell pane.
        val renderBytes = buildString {
            append("$esc[2J$esc[H")
            repeat(24) { row -> append(coloredAgentRow(row)).append("\r\n") }
        }
        state.appendRemoteOutput(renderBytes.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "a healthy dense COLOURED pane (render reproduces tmux's content lines; capture is a " +
                "real -e snapshot) must NOT be flagged divergent — no reseed-thrash on a correctly-" +
                "painted coloured pane",
            state.visibleRenderLostFrameVsCapture(fullAgentFrameColored()),
        )
    }

    @Test
    fun coloredBackgroundRegionIsNotFlagged() = withAttachedSurface { state ->
        // A healthy TUI whose panel/gutter rows are styled with a BACKGROUND colour over spaces
        // (`ESC[44m   ESC[0m`) — the reviewer's PROBE2 asymmetry. In the coloured capture such a row
        // is non-empty after a bare `.trim()` (the escapes survive) but the render paints only
        // spaces; after ANSI stripping BOTH collapse to empty, so the row is symmetric and the real
        // content rows match → NOT divergent. On the round-1 predicate the coloured content rows +
        // the styled-space rows all diverge → divergent=true → RED; GREEN with the strip.
        val renderBytes = buildString {
            append("$esc[2J$esc[H")
            repeat(6) { row -> append(coloredAgentRow(row)).append("\r\n") }
            // a styled-background gutter/panel band (bg colour over spaces)
            repeat(4) { append("$esc[44m          $esc[0m\r\n") }
            repeat(6) { row -> append(coloredAgentRow(row + 6)).append("\r\n") }
        }
        state.appendRemoteOutput(renderBytes.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        val capture = buildString {
            repeat(6) { row -> append(coloredAgentRow(row)).append('\n') }
            repeat(4) { append("$esc[44m          $esc[0m\n") }
            repeat(6) { row -> append(coloredAgentRow(row + 6)).append('\n') }
        }
        assertFalse(
            "a healthy pane with a coloured-BACKGROUND styled-space region (bg colour over spaces) " +
                "must NOT be flagged divergent — after ANSI stripping the styled-space rows collapse " +
                "to empty on BOTH sides and the content rows match",
            state.visibleRenderLostFrameVsCapture(capture),
        )
    }

    @Test
    fun coloredMostlyEmptyPromptIsNotFlagged() = withAttachedSurface { state ->
        // A mostly-empty but healthy shell pane: a coloured bash prompt plus a few coloured `ls`
        // output lines — sparse, but the render reproduces exactly what tmux holds. It must NOT be
        // flagged (a real coloured shell prompt is the common case). Plain content on both sides is
        // identical after stripping; on the round-1 predicate the coloured capture lines never match
        // → divergent=true → RED; GREEN with the strip.
        val lines = listOf(
            "$esc[1;32muser@podwiki$esc[0m:$esc[1;34m~/proj$esc[0m$ ls -la",
            "$esc[34mdrwxr-xr-x$esc[0m  4 user user 4096 Jul  6 12:00 $esc[1;34m.$esc[0m",
            "$esc[34mdrwxr-xr-x$esc[0m 20 user user 4096 Jul  6 11:00 $esc[1;34m..$esc[0m",
            "$esc[0m-rw-r--r--$esc[0m  1 user user 2048 Jul  6 12:00 build.gradle.kts",
            "$esc[1;32muser@podwiki$esc[0m:$esc[1;34m~/proj$esc[0m$ ",
        )
        val renderBytes = buildString {
            append("$esc[2J$esc[H")
            lines.forEach { append(it).append("\r\n") }
        }
        state.appendRemoteOutput(renderBytes.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        val capture = lines.joinToString("\n", postfix = "\n")
        assertFalse(
            "a mostly-empty healthy COLOURED shell prompt (render reproduces tmux's content) must " +
                "NOT be flagged divergent",
            state.visibleRenderLostFrameVsCapture(capture),
        )
    }
}
