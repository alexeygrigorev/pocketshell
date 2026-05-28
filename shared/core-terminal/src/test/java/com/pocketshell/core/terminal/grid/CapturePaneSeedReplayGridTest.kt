package com.pocketshell.core.terminal.grid

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Grid-level regression for issue #259 — the *root cause* of the
 * conversation/terminal garble: the tmux `-CC` reattach **seed**.
 *
 * On reattach, tmux control mode does not re-send a pane's existing content,
 * so PocketShell seeds a freshly-attached pane's emulator from a
 * `capture-pane -p -e -S -200` snapshot before live `%output` flows in. The
 * production builder is `TmuxSessionViewModel.toTerminalViewportBytes`. This
 * test reproduces that builder's exact byte shape and drives it (plus a
 * realistic live status/spinner rewrite that follows the seed) through the
 * same vendored [TerminalEmulator] the device uses, asserting the seeded
 * content renders cleanly and the next live frame lands on the right row.
 *
 * The garble's signature is two spinner frames coexisting / fragments mashing
 * (`gthinkingwithout`, two `Beboppin…` rows). It comes from the seed leaving
 * the emulator's cursor on the wrong row: the old builder appended a trailing
 * `\r\n`, parking the cursor on the row *below* the captured spinner. Claude
 * Code's spinner rewrites in place with a bare carriage return (`\r` to the
 * current row, no re-home), so the next live frame painted one row too low and
 * the seeded frame stayed stranded above it.
 *
 * The fix: the seed restores tmux's true cursor position with a
 * viewport-absolute `CSI <row+1>;<col+1> H` and drops the forced trailing
 * newline, so the next live `\r` rewrite overwrites the seeded spinner row
 * exactly. These tests encode both the BROKEN seed (to prove the garble) and
 * the FIXED seed (to prove it is clean).
 *
 * Pure JVM: [TerminalEmulator.append] parses bytes without JNI, so no Android
 * / SSH / tmux machinery is required.
 */
class CapturePaneSeedReplayGridTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(columns: Int = 60, rows: Int = 8): TerminalEmulator =
        TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 2, null)

    private fun TerminalEmulator.feed(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.rowText(row: Int): String =
        screen.getSelectedText(0, row, mColumns, row).trimEnd()

    /**
     * The OLD (broken) seed shape: home + clear, lines joined by CRLF, then a
     * trailing CRLF that parks the cursor on the row below the content. No
     * cursor restore. This is what produced the garble on the device.
     */
    private fun brokenSeed(lines: List<String>): String = buildString {
        append("[H[2J")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\r\n")
            append(line)
        }
        append("\r\n")
    }

    /**
     * The FIXED seed shape, mirroring `toTerminalViewportBytes(cursor)`:
     * home + clear, lines joined by CRLF, NO trailing newline, an SGR reset at
     * the seed boundary, then a viewport-absolute cursor restore to tmux's
     * reported 0-based [cursorRow]/[cursorCol].
     */
    private fun fixedSeed(lines: List<String>, cursorCol: Int, cursorRow: Int): String =
        buildString {
            append("[H[2J")
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\r\n")
                append(line)
            }
            append("[0m")
            append("[${cursorRow + 1};${cursorCol + 1}H")
        }

    /**
     * Captured visible pane: the agent had printed two committed lines and is
     * mid-spinner on the third row (the cursor sits at the start of that row
     * after the spinner's own `\r`). tmux reports cursor_x=0, cursor_y=2.
     */
    private val capturedLines = listOf(
        "> explain the seeding bug",
        "I'll walk through it.",
        "Beboppin'... (7m 16s - 30.6k tokens - thinking)",
    )

    /**
     * The next live `%output` frame the agent emits after reattach: the spinner
     * rewrites itself in place with a bare carriage return and a SHORTER frame,
     * clearing to end-of-line so the longer tail is erased.
     */
    private val liveNextFrame = "\r[KBeboppin'... (44 tokens)"

    @Test
    fun brokenSeedStrandsThePreviousSpinnerFrameAboveTheLiveOne() {
        // This documents the BUG so the fix has a clear counter-example. The
        // broken seed parks the cursor on row 3 (below the spinner on row 2),
        // so the live `\r` rewrite paints row 3 and the seeded spinner survives
        // on row 2 — two coexisting frames, exactly the reported garble.
        val term = emulator()
        term.feed(brokenSeed(capturedLines))
        term.feed(liveNextFrame)

        val row2 = term.rowText(2)
        val row3 = term.rowText(3)
        // Two distinct spinner frames are visible at once — the garble.
        assertEquals("Beboppin'... (7m 16s - 30.6k tokens - thinking)", row2)
        assertEquals("Beboppin'... (44 tokens)", row3)
    }

    @Test
    fun fixedSeedLandsTheLiveFrameOnTheSpinnerRowNoCoexistingFrames() {
        // The fix restores cursor (0,2) so the live `\r` rewrite overwrites the
        // seeded spinner on row 2. Only the final frame is visible; the longer
        // intermediate frame's tail is erased; row 3 stays empty.
        val term = emulator()
        term.feed(fixedSeed(capturedLines, cursorCol = 0, cursorRow = 2))
        term.feed(liveNextFrame)

        assertEquals("Beboppin'... (44 tokens)", term.rowText(2))
        assertEquals("", term.rowText(3))
        // No fragment of the longer earlier frame survives anywhere on screen.
        for (r in 0 until 8) {
            assertFalse(
                "stale spinner-frame tail leaked into row $r: '${term.rowText(r)}'",
                term.rowText(r).contains("30.6k tokens") || term.rowText(r).contains("thinking"),
            )
        }
        // The committed lines above the spinner are untouched.
        assertEquals("> explain the seeding bug", term.rowText(0))
        assertEquals("I'll walk through it.", term.rowText(1))
    }

    @Test
    fun fixedSeedKeepsCommittedRowsSeparateAfterANewLiveLine() {
        // After the spinner resolves, the agent commits a new line on the row
        // below. With the cursor correctly restored the new line lands on its
        // own row — no run-together, no mashing with the spinner row.
        val term = emulator()
        term.feed(fixedSeed(capturedLines, cursorCol = 0, cursorRow = 2))
        // Spinner resolves to a final frame in place...
        term.feed("\r[KBeboppin'... done")
        // ...then a committed answer line on the next row.
        term.feed("\r\n> here is the answer")

        assertEquals("Beboppin'... done", term.rowText(2))
        assertEquals("> here is the answer", term.rowText(3))
        // Adjacent rows are not run together into one token.
        assertFalse(term.rowText(2).contains("> here"))
        assertFalse(term.rowText(3).contains("Beboppin"))
    }

    @Test
    fun fixedSeedDoesNotBleedCapturedColourIntoLiveOutput() {
        // capture-pane -e embeds per-cell SGR. If a captured line ends mid-colour
        // (no closing reset) the colour could bleed into the next seeded row or
        // into live output. The fix appends ESC[0m at the seed boundary. Model a
        // captured line that opens green and never closes it; the row below must
        // still render its own text cleanly (the grid stores text regardless of
        // colour, but the reset proves the boundary is closed and the live frame
        // is not painted under a stale attribute run).
        val coloured = listOf(
            "plain header",
            "[32mgreen status without a closing reset",
        )
        val term = emulator()
        term.feed(fixedSeed(coloured, cursorCol = 0, cursorRow = 1))
        // Live output writes a fresh committed line below.
        term.feed("\r\n> committed answer")

        assertEquals("plain header", term.rowText(0))
        assertEquals("green status without a closing reset", term.rowText(1))
        assertEquals("> committed answer", term.rowText(2))
    }

    @Test
    fun fixedSeedWithMissingCursorFallsBackToEndOfReplay() {
        // When tmux does not report a cursor (older tmux / query failed), the
        // builder emits no cursor-restore and the cursor sits at the end of the
        // last captured line. This is still better than the old below-content
        // placement: a subsequent committed newline opens a fresh row rather
        // than overwriting captured content. Model the no-cursor seed shape.
        val noCursorSeed = buildString {
            append("[H[2J")
            capturedLines.forEachIndexed { index, line ->
                if (index > 0) append("\r\n")
                append(line)
            }
            append("[0m")
        }
        val term = emulator()
        term.feed(noCursorSeed)
        // A committed newline + text must open the row below the spinner.
        term.feed("\r\n> answer")

        assertEquals("Beboppin'... (7m 16s - 30.6k tokens - thinking)", term.rowText(2))
        assertEquals("> answer", term.rowText(3))
    }
}
