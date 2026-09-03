package com.pocketshell.next.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact bytes a send puts on the wire (rewrite task P-1).
 *
 * This is a WIRE FORMAT, not a presentation detail: a host-side agent reading
 * `Attached files:` / `- ~/...` out of a prompt has been parsing this shape
 * since the old client, so an "improvement" here silently changes what every
 * consumer on the other end sees. That is why the port is pinned character for
 * character rather than re-derived.
 */
class ComposerTextTest {

    @Test
    fun `a plain draft is sent verbatim`() {
        assertEquals("run the tests", ComposerText.compose("run the tests", emptyList()))
    }

    @Test
    fun `attachments are appended as a blank-line separated block`() {
        assertEquals(
            "look at this\n\nAttached files:\n- ~/a.png\n- ~/b.png",
            ComposerText.compose("look at this", listOf("~/a.png", "~/b.png")),
        )
    }

    @Test
    fun `an attachment with no draft is just the block`() {
        assertEquals(
            "Attached files:\n- ~/a.png",
            ComposerText.compose("", listOf("~/a.png")),
        )
    }

    /**
     * Exactly one blank line between the text and the block whatever the draft
     * already ended with — a draft ending in a newline must not produce three.
     */
    @Test
    fun `trailing newlines in the draft are not doubled`() {
        assertEquals(
            "text\n\nAttached files:\n- ~/a",
            ComposerText.compose("text\n", listOf("~/a")),
        )
        assertEquals(
            "text\n\nAttached files:\n- ~/a",
            ComposerText.compose("text\n\n", listOf("~/a")),
        )
    }

    /**
     * Carriage return, not newline. A PTY's line discipline turns `\r` into
     * "the user pressed Enter"; `\n` types a literal newline into readline and
     * the prompt just sits there — the difference between a command running and
     * a command not running.
     */
    @Test
    fun `the wire bytes end in a carriage return`() {
        assertEquals("hello\r", ComposerText.wireBytes("hello").toString(Charsets.UTF_8))
    }

    @Test
    fun `the wire bytes are UTF-8`() {
        assertEquals(
            "привет\r".toByteArray(Charsets.UTF_8).toList(),
            ComposerText.wireBytes("привет").toList(),
        )
    }

    @Test
    fun `the session key pairs the host id with the session name`() {
        assertEquals("7/my project", ComposerText.sessionKey(7, "my project"))
    }

    @Test
    fun `an attachment label is the remote file name`() {
        assertEquals("shot.png", ComposerText.attachmentDisplayName("~/.pocketshell/a/shot.png"))
        assertEquals("bare", ComposerText.attachmentDisplayName("bare"))
    }

    @Test
    fun `a history label is the first non-blank line, ellipsised`() {
        assertEquals("second line", ComposerText.historyLabel("\n\nsecond line\nthird"))
        assertEquals("abc…", ComposerText.historyLabel("abcdefgh", maxChars = 4))
        assertEquals("short", ComposerText.historyLabel("short", maxChars = 40))
    }
}
