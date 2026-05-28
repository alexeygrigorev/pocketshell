package com.pocketshell.core.terminal.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for [TerminalQueryResponseSanitizer] (issue #248).
 *
 * The leak the maintainer saw when opening a second window was a
 * `capture-pane` replay seeding the pane with the *printable* remnant of an
 * OSC-colour + DA reply (the `ESC` bytes already dropped by tmux's grid
 * storage). These cases assert that exact shape — and the `ESC`-prefixed wire
 * form — are removed, while queries, set-colour commands, and ordinary output
 * survive untouched.
 */
class TerminalQueryResponseSanitizerTest {

    private fun sanitize(s: String): String =
        TerminalQueryResponseSanitizer
            .sanitize(s.toByteArray(Charsets.US_ASCII))
            .toString(Charsets.US_ASCII)

    @Test
    fun stripsExactReportedCaptureLeak() {
        // The literal sequence from issue #248: OSC 11 bg-colour report plus a
        // DA1 reply, in the ESC-stripped printable form `capture-pane` yields.
        val leak = "]11;rgb:0101/0404/0909\\[?64;1;2;6;9;15;18;21;22c"
        assertEquals("", sanitize(leak))
    }

    @Test
    fun stripsCaptureLeakSurroundedByRealOutput() {
        val input = "hello ]11;rgb:0101/0404/0909\\[?64;1;2;6;9;15;18;21;22c world"
        assertEquals("hello  world", sanitize(input))
    }

    @Test
    fun stripsEscPrefixedOsc11ColorReply() {
        val input = "]11;rgb:1a1a/2b2b/3c3c\\after"
        assertEquals("after", sanitize(input))
    }

    @Test
    fun stripsEscPrefixedDa1Reply() {
        val input = "before[?64;1;2;6;9;15;18;21;22cafter"
        assertEquals("beforeafter", sanitize(input))
    }

    @Test
    fun stripsDa2Reply() {
        val input = "x[>41;320;0cy"
        assertEquals("xy", sanitize(input))
    }

    @Test
    fun stripsCursorPositionReply() {
        // DSR cursor-position reply form (CSI <row>;<col> R).
        val input = "a[24;80Rb"
        assertEquals("ab", sanitize(input))
    }

    @Test
    fun stripsBelTerminatedColorReply() {
        val input = "p]10;rgb:ffff/ffff/ffffq"
        assertEquals("pq", sanitize(input))
    }

    @Test
    fun stripsHashColorSpecReply() {
        val input = "m]12;#aabbcc\\n"
        assertEquals("mn", sanitize(input))
    }

    @Test
    fun preservesPlainText() {
        val input = "just some [normal] text with ]brackets["
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesSgrColorOutput() {
        // capture-pane -e emits SGR; those must render, not be stripped.
        val input = "[31mred[0m normal"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesDaQueryForm() {
        // `CSI c` (no `?`/`>` prefix, no params) is the *query* an app sends;
        // it must reach the emulator so suppression can decide what to do.
        val input = "[c"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesOscColorQueryForm() {
        // `OSC 11 ; ?` is the query, not a reply — leave it for the emulator.
        val input = "]11;?\\"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesSetColorByNameCommand() {
        // An app legitimately setting bg colour by name is not a reply.
        val input = "]11;black\\"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesCursorMovementCsi() {
        // CSI cursor movement / clears must not be mistaken for a reply.
        val input = "[2J[H[10;5Htext"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun preservesUnterminatedColorReplyFragment() {
        // A partial reply with no terminator (chunk boundary) is left intact
        // so a follow-up chunk can complete it; we never swallow open output.
        val input = "]11;rgb:0101/0404/0909"
        assertEquals(input, sanitize(input))
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals("", sanitize(""))
    }

    @Test
    fun respectsOffsetAndCount() {
        val full = "XX]11;rgb:0101/0404/0909\\YY".toByteArray(Charsets.US_ASCII)
        val result = TerminalQueryResponseSanitizer
            .sanitize(full, offset = 2, count = full.size - 4)
            .toString(Charsets.US_ASCII)
        assertEquals("", result)
    }
}
