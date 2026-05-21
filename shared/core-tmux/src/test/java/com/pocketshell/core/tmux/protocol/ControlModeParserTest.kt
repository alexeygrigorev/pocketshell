package com.pocketshell.core.tmux.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ControlModeParser]. Pure-JVM, no Android, no Docker.
 *
 * Fixtures hand-crafted from `man tmux` (CONTROL MODE) and tmux's own
 * `control-notify.c`; cross-checked against iTerm2's tmux integration test
 * vectors where they exist. See issue #43 for the canonical list of events
 * and edge cases this suite must cover.
 */
class ControlModeParserTest {

    private val parser = ControlModeParser()

    // --- happy-path: one test per ControlEvent subtype ----------------------

    @Test
    fun `parses output event with plain ASCII data`() {
        val event = parser.parse("%output %0 hello") as ControlEvent.Output
        assertEquals("%0", event.paneId)
        assertArrayEquals("hello".toByteArray(Charsets.US_ASCII), event.data)
    }

    @Test
    fun `parses output event with multi-digit pane id`() {
        val event = parser.parse("%output %123 x") as ControlEvent.Output
        assertEquals("%123", event.paneId)
        assertArrayEquals(byteArrayOf('x'.code.toByte()), event.data)
    }

    @Test
    fun `parses output with octal-escaped ESC sequences (red text)`() {
        // \033 is octal ESC (0x1b), the standard CSI introducer.
        val event = parser.parse("%output %1 \\033[31mred\\033[0m") as ControlEvent.Output
        assertEquals("%1", event.paneId)
        val expected = byteArrayOf(
            0x1b, '['.code.toByte(), '3'.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(),
            'r'.code.toByte(), 'e'.code.toByte(), 'd'.code.toByte(),
            0x1b, '['.code.toByte(), '0'.code.toByte(), 'm'.code.toByte(),
        )
        assertArrayEquals(expected, event.data)
    }

    @Test
    fun `parses output with hex-escaped byte`() {
        // \x1b is the documented (iTerm2 protocol notes) legacy hex form.
        // tmux itself emits octal, but the brief calls out hex support too.
        val event = parser.parse("%output %0 \\x1b[H") as ControlEvent.Output
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte()),
            event.data,
        )
    }

    @Test
    fun `parses output with literal backslash escape`() {
        // tmux doubles real backslashes in the wire format. `\\` -> single `\`.
        val event = parser.parse("%output %0 a\\\\b") as ControlEvent.Output
        assertArrayEquals(
            byteArrayOf('a'.code.toByte(), '\\'.code.toByte(), 'b'.code.toByte()),
            event.data,
        )
    }

    @Test
    fun `parses output with empty data`() {
        val event = parser.parse("%output %0 ") as ControlEvent.Output
        assertEquals("%0", event.paneId)
        assertEquals(0, event.data.size)
    }

    @Test
    fun `parses session-changed event`() {
        val event = parser.parse("%session-changed \$0 main") as ControlEvent.SessionChanged
        assertEquals("\$0", event.sessionId)
        assertEquals("main", event.name)
    }

    @Test
    fun `parses session-changed with spaces in name`() {
        // tmux does not (currently) emit spaces in session names without
        // quoting, but the protocol doesn't forbid it — the rest of the line
        // after the first space is the name.
        val event = parser.parse("%session-changed \$2 my session") as ControlEvent.SessionChanged
        assertEquals("\$2", event.sessionId)
        assertEquals("my session", event.name)
    }

    @Test
    fun `parses sessions-changed event`() {
        assertEquals(ControlEvent.SessionsChanged, parser.parse("%sessions-changed"))
    }

    @Test
    fun `parses window-add event`() {
        val event = parser.parse("%window-add @0") as ControlEvent.WindowAdd
        assertEquals("@0", event.windowId)
        // tmux does not include sessionId or name on this notification.
        assertEquals("", event.sessionId)
        assertEquals("", event.name)
    }

    @Test
    fun `parses window-close event`() {
        val event = parser.parse("%window-close @5") as ControlEvent.WindowClose
        assertEquals("@5", event.windowId)
    }

    @Test
    fun `parses window-renamed event`() {
        val event = parser.parse("%window-renamed @3 build") as ControlEvent.WindowRenamed
        assertEquals("@3", event.windowId)
        assertEquals("build", event.name)
    }

    @Test
    fun `parses layout-change event with single layout token`() {
        val event = parser.parse("%layout-change @0 b25d,80x24,0,0,0") as ControlEvent.LayoutChange
        assertEquals("@0", event.windowId)
        assertEquals("b25d,80x24,0,0,0", event.layout)
    }

    @Test
    fun `parses layout-change event with extra tmux-2_2+ fields`() {
        // Newer tmux: "@<id> <layout> <visible-layout> <window-flags>".
        // We keep just the first layout token.
        val event = parser.parse("%layout-change @0 b25d,80x24,0,0,0 b25d,80x24,0,0,0 *") as ControlEvent.LayoutChange
        assertEquals("@0", event.windowId)
        assertEquals("b25d,80x24,0,0,0", event.layout)
    }

    @Test
    fun `parses pane-mode-changed event`() {
        val event = parser.parse("%pane-mode-changed %7") as ControlEvent.PaneModeChanged
        assertEquals("%7", event.paneId)
    }

    @Test
    fun `parses begin event`() {
        val event = parser.parse("%begin 1234567890 1 0") as ControlEvent.Begin
        assertEquals(1234567890L, event.time)
        assertEquals(1L, event.number)
        assertEquals(0, event.flags)
    }

    @Test
    fun `parses end event`() {
        val event = parser.parse("%end 1234567890 1 0") as ControlEvent.End
        assertEquals(1234567890L, event.time)
        assertEquals(1L, event.number)
        assertEquals(0, event.flags)
    }

    @Test
    fun `parses error event`() {
        val event = parser.parse("%error 1234567890 2 0") as ControlEvent.Error
        assertEquals(1234567890L, event.time)
        assertEquals(2L, event.number)
        assertEquals(0, event.flags)
    }

    @Test
    fun `parses client-detached event`() {
        assertEquals(ControlEvent.ClientDetached, parser.parse("%client-detached"))
    }

    @Test
    fun `parses client-detached with client name argument`() {
        // tmux >= 3.2 includes the client name; we deliberately discard it.
        assertEquals(ControlEvent.ClientDetached, parser.parse("%client-detached /dev/pts/3"))
    }

    @Test
    fun `parses exit event without reason`() {
        val event = parser.parse("%exit") as ControlEvent.Exit
        assertNull(event.reason)
    }

    @Test
    fun `parses exit event with trailing space and no reason as null reason`() {
        // "%exit " with a stray trailing space — opcode-only dispatch sees
        // empty args and yields null reason.
        val event = parser.parse("%exit ") as ControlEvent.Exit
        assertNull(event.reason)
    }

    @Test
    fun `parses exit event with reason`() {
        val event = parser.parse("%exit server exited") as ControlEvent.Exit
        assertEquals("server exited", event.reason)
    }

    // --- edge cases ---------------------------------------------------------

    @Test
    fun `returns null for empty line`() {
        assertNull(parser.parse(""))
    }

    @Test
    fun `returns null for line without percent prefix`() {
        // Non-`%`-prefixed lines outside a response block are protocol
        // violations and should be ignored.
        assertNull(parser.parse("just some text"))
    }

    @Test
    fun `returns null for unknown event opcode`() {
        // Future tmux event we don't know about — silent skip.
        assertNull(parser.parse("%future-thing some args"))
    }

    @Test
    fun `returns null for malformed begin (missing fields)`() {
        assertNull(parser.parse("%begin 12345"))
    }

    @Test
    fun `returns null for malformed begin (non-numeric)`() {
        assertNull(parser.parse("%begin notanumber 1 0"))
    }

    @Test
    fun `returns null for malformed output (missing paneId)`() {
        assertNull(parser.parse("%output "))
    }

    @Test
    fun `returns null for malformed output (paneId without percent)`() {
        assertNull(parser.parse("%output 1 data"))
    }

    @Test
    fun `returns null for malformed session-changed (missing name)`() {
        assertNull(parser.parse("%session-changed \$0"))
    }

    @Test
    fun `returns null for malformed window-add (no windowId)`() {
        assertNull(parser.parse("%window-add"))
    }

    @Test
    fun `returns null for malformed window-add (bad prefix)`() {
        assertNull(parser.parse("%window-add notawindow"))
    }

    @Test
    fun `output decoder preserves bytes through non-recognised escape`() {
        // `\q` is not a known escape — we pass the backslash through
        // verbatim rather than dropping data. Then the `q` is plain.
        val decoded = decodeOutputData("a\\qb")
        assertArrayEquals(
            byteArrayOf('a'.code.toByte(), '\\'.code.toByte(), 'q'.code.toByte(), 'b'.code.toByte()),
            decoded,
        )
    }

    @Test
    fun `output decoder handles trailing lone backslash`() {
        // A bare `\` at the end of the data — no escape sequence to apply,
        // pass through literally without crashing.
        val decoded = decodeOutputData("a\\")
        assertArrayEquals(
            byteArrayOf('a'.code.toByte(), '\\'.code.toByte()),
            decoded,
        )
    }

    @Test
    fun `output decoder handles octal newline and tab`() {
        // tmux's primary form for non-printables: 3-digit octal.
        // \012 = '\n' (LF), \011 = '\t' (TAB)
        val decoded = decodeOutputData("hi\\012world\\011!")
        assertArrayEquals(
            byteArrayOf(
                'h'.code.toByte(), 'i'.code.toByte(), '\n'.code.toByte(),
                'w'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
                'l'.code.toByte(), 'd'.code.toByte(), '\t'.code.toByte(),
                '!'.code.toByte(),
            ),
            decoded,
        )
    }

    @Test
    fun `output decoder handles letter newline escape`() {
        // Synthetic / non-tmux source: `\n` letter form. Tolerated for
        // tests and replay tools per the brief example "hello\\nworld".
        val decoded = decodeOutputData("hello\\nworld")
        assertArrayEquals("hello\nworld".toByteArray(Charsets.US_ASCII), decoded)
    }

    @Test
    fun `output decoder accepts mixed octal hex and letter escapes`() {
        // Same byte (ESC, 0x1b) expressed three different ways in one line.
        val decoded = decodeOutputData("\\033\\x1b\\033")
        assertArrayEquals(byteArrayOf(0x1b, 0x1b, 0x1b), decoded)
    }

    @Test
    fun `output decoder handles uppercase hex digits`() {
        val decoded = decodeOutputData("\\xFF\\x0A")
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x0a.toByte()), decoded)
    }

    @Test
    fun `output decoder handles empty input`() {
        assertEquals(0, decodeOutputData("").size)
    }

    @Test
    fun `output event with empty paneId is rejected`() {
        // `%output  data` (two spaces) — paneId would be "" which isn't a
        // valid pane reference.
        assertNull(parser.parse("%output  data"))
    }

    @Test
    fun `parser is reusable across calls`() {
        // Statelessness sanity check — the parser has no per-line memory,
        // so two unrelated parses must not interfere.
        assertTrue(parser.parse("%begin 1 1 0") is ControlEvent.Begin)
        assertTrue(parser.parse("%end 2 2 0") is ControlEvent.End)
        assertTrue(parser.parse("%output %0 x") is ControlEvent.Output)
    }
}
