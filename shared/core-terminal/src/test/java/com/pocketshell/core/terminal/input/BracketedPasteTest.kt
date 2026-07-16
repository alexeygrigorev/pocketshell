package com.pocketshell.core.terminal.input

import org.junit.Assert.assertArrayEquals
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

    // ------------------------------------------------------------------
    // Issue #1636 — frameTextChunks byte-exactness
    // ------------------------------------------------------------------

    private fun assertChunksRebuildFrameExactly(source: String, chunkBytes: Int) {
        val bytes = source.toByteArray(Charsets.UTF_8)
        val chunks = BracketedPaste.frameTextChunks(bytes, chunkBytes)
        assertArrayEquals(
            "chunks (size=$chunkBytes) must re-assemble into the framed bytes EXACTLY",
            BracketedPaste.frame(bytes),
            chunks.joinToString(separator = "").toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * THE contract the #1636 atomic paste rides on: the chunks the tmux paste
     * buffer is filled with must re-assemble into the framed payload byte for
     * byte. Swept across chunk sizes so a boundary lands inside every UTF-8
     * sequence length in the corpus.
     */
    @Test
    fun frameTextChunksRebuildTheFrameByteExactlyAtEveryChunkSize() {
        val source = "Проверка 🎧 ünïcode\nsecond line\n\n" + "x".repeat(300) + "\ntail ✅"
        for (chunkBytes in 1..64) {
            assertChunksRebuildFrameExactly(source, chunkBytes)
        }
        for (chunkBytes in listOf(127, 128, 1023, 1024, 4096)) {
            assertChunksRebuildFrameExactly(source, chunkBytes)
        }
    }

    /**
     * A boundary must never fall inside a multi-byte sequence: a naive fixed-byte
     * split replaces the halves with U+FFFD and silently corrupts the payload.
     */
    @Test
    fun frameTextChunksNeverSplitAMultiByteCharacter() {
        // The frame prefix is 6 bytes, so a 7-byte budget puts the boundary exactly
        // one byte into the first (4-byte) emoji.
        val chunks = BracketedPaste.frameTextChunks("🎧🎧🎧".toByteArray(Charsets.UTF_8), 7)

        assertFalse(
            "no chunk may contain the U+FFFD a split multi-byte sequence decodes to: $chunks",
            chunks.any { it.contains('�') },
        )
        assertEquals("\u001B[200~🎧🎧🎧\u001B[201~", chunks.joinToString(separator = ""))
    }

    @Test
    fun frameTextChunksAreBoundedByTheChunkBudget() {
        val chunks = BracketedPaste.frameTextChunks("y".repeat(5000).toByteArray(Charsets.UTF_8), 1024)

        assertTrue("expected a multi-chunk fill, got ${chunks.size}", chunks.size > 4)
        assertTrue(
            "every chunk must stay within the budget (+ at most one code point)",
            chunks.all { it.toByteArray(Charsets.UTF_8).size <= 1024 + 3 },
        )
    }

    @Test
    fun frameTextChunksNormaliseCrLfLikeFrameAndDropEmptyInput() {
        assertEquals(
            "\u001B[200~a\nb\rc\u001B[201~",
            BracketedPaste.frameTextChunks("a\r\nb\rc".toByteArray(Charsets.UTF_8)).joinToString(""),
        )
        assertEquals(emptyList<String>(), BracketedPaste.frameTextChunks(ByteArray(0)))
    }

    @Test
    fun containsLineBreakOnlyTreatsLfAsPasteSeparator() {
        assertTrue(BracketedPaste.containsLineBreak("a\nb"))
        assertTrue(BracketedPaste.containsLineBreak("a\r\nb"))
        assertFalse(BracketedPaste.containsLineBreak("a\rb"))
        assertFalse(BracketedPaste.containsLineBreak(""))
    }
}
