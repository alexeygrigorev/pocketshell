package com.pocketshell.core.terminal.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketedPasteTest {
    @Test
    fun frameWrapsUtf8BytesAndPreservesLf() {
        val framed = BracketedPaste.frame("a\nb").toString(Charsets.UTF_8)

        assertEquals("\u001B[200~a\nb\u001B[201~", framed)
    }

    @Test
    fun frameNormalisesCrLfToLfWithoutChangingLoneCr() {
        val framed = BracketedPaste.frame("a\r\nb\rc").toString(Charsets.UTF_8)

        assertEquals("\u001B[200~a\nb\rc\u001B[201~", framed)
    }

    @Test
    fun hexPayloadMatchesTmuxSendKeysShape() {
        val hex = BracketedPaste.hexPayload("a\nb".toByteArray(Charsets.UTF_8))

        assertEquals(
            "1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e",
            hex,
        )
    }

    @Test
    fun containsLineBreakOnlyTreatsLfAsPasteSeparator() {
        assertTrue(BracketedPaste.containsLineBreak("a\nb"))
        assertTrue(BracketedPaste.containsLineBreak("a\r\nb"))
        assertFalse(BracketedPaste.containsLineBreak("a\rb"))
        assertFalse(BracketedPaste.containsLineBreak(""))
    }
}
