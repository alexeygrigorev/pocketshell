package com.pocketshell.next.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The key bar's byte tables (rewrite task U-5).
 *
 * Pure JVM — no Robolectric, no Compose. These are the bytes that reach the
 * remote PTY, so every case below is a literal from the VT/xterm control set
 * rather than a value re-derived from the implementation: a test that computed
 * `'C' - 0x40` would agree with any arithmetic bug that made the same mistake.
 *
 * The ASCII expectations are cross-checkable against `ascii(7)`, and the
 * Ctrl table is the one `com.termux.view.TerminalView.inputCodePoint` applies —
 * app2 replaces that branch when the key bar's Ctrl is armed, so the two have
 * to agree or Ctrl+key would depend on where the modifier came from.
 */
class KeyBytesTest {

    // --- the maintainer's named keys -----------------------------------------

    /**
     * The headline case: "I need Ctrl+C". SIGINT is 0x03 and nothing else.
     */
    @Test
    fun `ctrl+C is the interrupt byte`() {
        assertArrayEquals(byteArrayOf(0x03), controlBytes('C'.code))
        assertArrayEquals(byteArrayOf(0x03), controlBytes('c'.code))
    }

    /** The second named case: end-of-file. */
    @Test
    fun `ctrl+D is the end-of-file byte`() {
        assertArrayEquals(byteArrayOf(0x04), controlBytes('D'.code))
        assertArrayEquals(byteArrayOf(0x04), controlBytes('d'.code))
    }

    @Test
    fun `escape sends 0x1B`() {
        assertArrayEquals(byteArrayOf(0x1B), keyBarBytes(KEY_LABEL_ESC))
    }

    @Test
    fun `tab sends 0x09`() {
        assertArrayEquals(byteArrayOf(0x09), keyBarBytes(KEY_LABEL_TAB))
    }

    /**
     * Enter is CARRIAGE RETURN, not line feed.
     *
     * 0x0A here would look right in a log and submit nothing: a tty in
     * canonical mode expects CR from the Enter key and does the `icrnl`
     * translation itself, and readline-based REPLs (every agent CLI the
     * maintainer runs) key off CR.
     */
    @Test
    fun `enter sends carriage return and not line feed`() {
        assertArrayEquals(byteArrayOf(0x0D), keyBarBytes(KEY_LABEL_ENTER))
    }

    /** A modifier decorates the next key; by itself it puts nothing on the wire. */
    @Test
    fun `the ctrl slot sends nothing on its own`() {
        assertNull(keyBarBytes(KEY_LABEL_CTRL))
        assertNull(keyBarBytes(KEY_LABEL_CTRL, ctrlArmed = true))
    }

    /**
     * Ctrl + a named key is the named key.
     *
     * Not a special case in the encoder and deliberately not one here either:
     * Ctrl+[ IS Escape, Ctrl+I IS Tab and Ctrl+M IS Return, so an armed Ctrl
     * leaving these untouched is correct rather than a missed branch.
     */
    @Test
    fun `an armed ctrl does not change the named keys`() {
        assertArrayEquals(byteArrayOf(0x1B), keyBarBytes(KEY_LABEL_ESC, ctrlArmed = true))
        assertArrayEquals(byteArrayOf(0x09), keyBarBytes(KEY_LABEL_TAB, ctrlArmed = true))
        assertArrayEquals(byteArrayOf(0x0D), keyBarBytes(KEY_LABEL_ENTER, ctrlArmed = true))
    }

    // --- character keys ------------------------------------------------------

    @Test
    fun `a character key sends itself when ctrl is not armed`() {
        assertArrayEquals("c".toByteArray(Charsets.UTF_8), keyBarBytes("c"))
        assertArrayEquals("/".toByteArray(Charsets.UTF_8), keyBarBytes("/"))
    }

    @Test
    fun `a character key sends its control byte when ctrl is armed`() {
        assertArrayEquals(byteArrayOf(0x03), keyBarBytes("C", ctrlArmed = true))
        assertArrayEquals(byteArrayOf(0x04), keyBarBytes("d", ctrlArmed = true))
    }

    /** An unknown label is not a guess and not a crash — it sends nothing. */
    @Test
    fun `an unknown multi-character label sends nothing`() {
        assertNull(keyBarBytes("PageUp"))
        assertNull(keyBarBytes(""))
    }

    // --- the whole control table --------------------------------------------

    /**
     * Every letter, checked against the definition (Ctrl+A..Ctrl+Z = 1..26)
     * rather than a sample of three.
     */
    @Test
    fun `ctrl maps the whole alphabet to 1 through 26`() {
        ('A'..'Z').forEachIndexed { index, letter ->
            val expected = (index + 1).toByte()
            assertArrayEquals(
                "Ctrl+$letter",
                byteArrayOf(expected),
                controlBytes(letter.code),
            )
            assertArrayEquals(
                "Ctrl+${letter.lowercaseChar()}",
                byteArrayOf(expected),
                controlBytes(letter.lowercaseChar().code),
            )
        }
    }

    /**
     * The punctuation half of the C0 range, plus the digit aliases upstream
     * carries for phone keyboards that have no `[`, `\`, `]`, `^` or `_` key —
     * on such a layout `Ctrl+3` is the only way to send Escape.
     */
    @Test
    fun `ctrl maps punctuation and the digit aliases to the C0 range`() {
        val expected = mapOf(
            ' ' to 0x00, '2' to 0x00, '@' to 0x00,
            '[' to 0x1B, '3' to 0x1B,
            '\\' to 0x1C, '4' to 0x1C,
            ']' to 0x1D, '5' to 0x1D,
            '^' to 0x1E, '6' to 0x1E,
            '_' to 0x1F, '7' to 0x1F, '/' to 0x1F,
            '8' to 0x7F,
        )
        expected.forEach { (character, byte) ->
            assertArrayEquals(
                "Ctrl+$character",
                byteArrayOf(byte.toByte()),
                controlBytes(character.code),
            )
        }
    }

    /**
     * Ctrl does not swallow characters it has no meaning for.
     *
     * `null` is the signal the caller needs to send the character UNMODIFIED;
     * a zero byte or an empty array here would read as a dead keyboard on any
     * non-US layout.
     */
    @Test
    fun `ctrl leaves characters with no control meaning alone`() {
        assertNull(controlBytes('é'.code))
        assertNull(controlBytes('1'.code))
        assertNull(controlBytes('.'.code))
    }

    /**
     * A supplementary-plane code point must not be narrowed into the ASCII
     * table. U+10061 truncates to `a` under `Int.toChar()`, so without the
     * range guard an emoji-adjacent key press would send SOH.
     */
    @Test
    fun `a supplementary code point is not narrowed into a control byte`() {
        assertNull(controlBytes(0x10061))
        assertNull(controlBytes(0x1F600))
        assertNull(controlBytes(-1))
    }

    /** One byte per key press — never a two-byte escape sequence. */
    @Test
    fun `every control encoding is a single byte`() {
        listOf('a'.code, 'Z'.code, ' '.code, '8'.code, '/'.code).forEach { codePoint ->
            assertEquals(1, controlBytes(codePoint)?.size)
        }
    }
}
