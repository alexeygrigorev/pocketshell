package com.pocketshell.core.terminal.grid

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Grid-level regression for issue #259 — "conversation/terminal rendering
 * garbles lines — rows run together / text mixed".
 *
 * Claude Code (and most agent CLIs) draw an animated status/spinner line that
 * rewrites itself *in place* between frames using carriage-return
 * (`\r`), cursor-column-absolute (`CSI G`), erase-line (`CSI K`), and
 * cursor-position (`CSI H`) escape sequences rather than appending fresh
 * lines. The maintainer's device showed the spinner garbled — fragments of
 * the previous frame interleaved with the new one (e.g. `gthinkingwithout`
 * where `g` + `thinking` + `without` were mashed together with the inter-word
 * spaces eaten).
 *
 * These tests drive realistic in-place rewrite sequences through the vendored
 * [TerminalEmulator] and assert the resulting cell grid holds exactly the
 * final frame — no leftover characters from an earlier (longer) frame, no
 * mashing, correct inter-word spacing. The grid is the source of truth the
 * renderer paints from; proving it is clean isolates any visible garble to the
 * render path (covered by the instrumented
 * `TerminalRendererSpinnerRewriteInstrumentedTest`) rather than to grid state.
 *
 * Pure JVM: [TerminalEmulator.append] parses bytes without touching JNI, so
 * no Android / SSH / session machinery is required.
 */
class StatusSpinnerRewriteGridTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(columns: Int = 60, rows: Int = 10): TerminalEmulator =
        TerminalEmulator(SinkOutput, columns, rows, 13, 15, rows * 2, null)

    private fun TerminalEmulator.feed(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }

    /** Visible text of a single screen row, trailing blanks trimmed. */
    private fun TerminalEmulator.rowText(row: Int): String {
        val line = screen.getSelectedText(0, row, mColumns, row)
        // getSelectedText for a single row never injects a newline; just trim
        // the trailing padding the grid stores as spaces.
        return line.trimEnd()
    }

    @Test
    fun carriageReturnRewriteOverwritesPreviousFrameInPlace() {
        val term = emulator()
        // Frame 1: a long spinner line.
        term.feed("Beboppin'... (250 tokens - thinking)")
        // Frame 2: carriage return to column 0, then a SHORTER line. The CLI
        // clears to end-of-line first (CSI K) so the longer tail of frame 1 is
        // erased instead of leaking through.
        term.feed("\r[KBeboppin'... (260 tokens)")

        assertEquals("Beboppin'... (260 tokens)", term.rowText(0))
        // The tail of the longer first frame ("- thinking)") must be gone.
        assertFalse(
            "leftover from a longer previous frame leaked into the grid",
            term.rowText(0).contains("thinking"),
        )
    }

    @Test
    fun carriageReturnRewriteWithoutEraseStillOverwritesCharByChar() {
        // Some CLIs do not emit CSI K and instead rely on overwriting every
        // column with a space. Frame 1 is longer; frame 2 explicitly paints
        // trailing spaces over the stale tail.
        val term = emulator()
        term.feed("Working on a really long task name here")
        // Carriage return, then a shorter message padded with spaces to cover
        // the previous frame's full width.
        val frame2 = "Done".padEnd("Working on a really long task name here".length, ' ')
        term.feed("\r$frame2")

        assertEquals("Done", term.rowText(0))
    }

    @Test
    fun cursorColumnAbsoluteRewriteKeepsWordSpacingClean() {
        // Reproduces the precise garble shape from the issue: a status line
        // assembled with cursor-column-absolute (CSI G) jumps between fields.
        // The earlier frame had "g thinking without" with single spaces; a
        // broken in-place update could collapse those into "gthinkingwithout".
        val term = emulator()
        // Frame 1.
        term.feed("[Hready")
        // Frame 2: home cursor, erase line, write the assembled status using
        // CSI G column jumps the way a TUI status bar lays out fields.
        term.feed("[H[K")
        term.feed("g")                 // field 1 at col 0
        term.feed("[3G")        // jump to column 3 (1-based)
        term.feed("thinking")          // field 2
        term.feed("[12G")       // jump to column 12 (1-based)
        term.feed("without")           // field 3

        val text = term.rowText(0)
        // Each field must be at its own column with blanks between — never
        // mashed into a single token.
        assertFalse(
            "fields were mashed together (the #259 garble): '$text'",
            text.contains("gthinkingwithout") || text.contains("thinkingwithout"),
        )
        // Concrete expected layout: 'g' at col0, "thinking" starting col2,
        // "without" starting col11 (0-based).
        assertEquals('g', text[0])
        assertEquals("thinking", text.substring(2, 10))
        assertEquals("without", text.substring(11, 18))
    }

    @Test
    fun repeatedSpinnerFramesNeverAccumulateStaleCharacters() {
        // Animate a spinner across several frames the way Claude Code does:
        // each frame is the SAME column-0 rewrite with a different glyph and a
        // shrinking/growing token count. After the last frame only the last
        // frame's content may be present.
        val term = emulator()
        val frames = listOf(
            "|  Beboppin'... (10 tokens - thinking)",
            "/  Beboppin'... (120 tokens - thinking)",
            "-  Beboppin'... (1280 tokens - thinking)",
            "\\  Beboppin'... (9 tokens)",
        )
        for (frame in frames) {
            term.feed("\r[K$frame")
        }
        assertEquals("\\  Beboppin'... (9 tokens)", term.rowText(0))
        // The longest intermediate frame had "1280 tokens - thinking"; none of
        // its distinctive tail may survive.
        assertFalse(term.rowText(0).contains("1280"))
        assertFalse(term.rowText(0).contains("thinking"))
    }

    @Test
    fun newlineAfterInPlaceSpinnerDoesNotGarbleSurroundingRows() {
        // The issue notes it "also glitches when sending a new line/message".
        // Model: a spinner rewrites row 0 in place, then the agent prints a
        // committed message on the next row. The committed row must be exactly
        // its own text and the spinner row must be exactly its final frame.
        val term = emulator()
        term.feed("\r[KBeboppin'... (12 tokens - thinking)")
        term.feed("\r[KBeboppin'... (44 tokens)")
        // New committed line.
        term.feed("\r\n")
        term.feed("> here is the answer to your question")

        assertEquals("Beboppin'... (44 tokens)", term.rowText(0))
        assertEquals("> here is the answer to your question", term.rowText(1))
    }

    @Test
    fun transcriptTextHasOneLinePerRowAfterInPlaceRewrite() {
        // The conversation/terminal view reads visible text from the grid.
        // After an in-place rewrite the transcript must not run rows together:
        // each logical row is separated by exactly one '\n' and carries only
        // its final-frame content.
        val term = emulator(columns = 40, rows = 4)
        term.feed("[H[2J") // home + clear screen
        term.feed("first line\r\n")
        term.feed("\r[Kspinner frame A (longer text here)")
        term.feed("\r[Kspinner B")
        val transcript = term.screen.transcriptText
        // No row should contain the eaten-space mash, and the long frame A
        // tail must be erased.
        assertFalse("transcript leaked stale frame: '$transcript'", transcript.contains("longer text here"))
        assertEquals("first line\nspinner B", transcript)
    }
}
