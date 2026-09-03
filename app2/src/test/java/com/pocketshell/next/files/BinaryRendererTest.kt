package com.pocketshell.next.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary fallback's hex dump (task P-3b). Pure JVM.
 *
 * The bound is the point: a 12 MiB blob must render a fixed number of rows, not
 * 786,000 of them. A dump that grows with the file would hang the composition,
 * which is the same failure as a crash from the user's side.
 */
class BinaryRendererTest {

    @Test
    fun `a dump row carries offset, hex and printable ascii`() {
        val dump = hexDump("PocketShell".toByteArray(Charsets.US_ASCII), HEX_DUMP_BYTES)

        assertEquals(1, dump.lines().size)
        assertTrue("expected an offset column, got: $dump", dump.startsWith("00000000  "))
        assertTrue("expected hex bytes, got: $dump", dump.contains("50 6f 63 6b 65 74"))
        assertTrue("expected the ascii column, got: $dump", dump.contains("|PocketShell|"))
    }

    @Test
    fun `non-printable bytes render as dots so the columns stay aligned`() {
        val dump = hexDump(byteArrayOf(0x00, 0x41, 0x1F, 0x42, 0x7F), HEX_DUMP_BYTES)

        assertTrue("expected dotted non-printables, got: $dump", dump.contains("|.A.B.|"))
    }

    @Test
    fun `the dump is bounded no matter how large the file is`() {
        val dump = hexDump(ByteArray(5 * 1024 * 1024), HEX_DUMP_BYTES)

        assertEquals(HEX_DUMP_BYTES / 16, dump.lines().size)
    }

    @Test
    fun `the note tells the user the dump is a prefix, and how big the file really is`() {
        assertEquals(
            "Not text or an image — showing all 512 B as hex.",
            binaryNote(sizeBytes = 512, shown = HEX_DUMP_BYTES),
        )
        assertEquals(
            "Not text or an image — showing the first 4.0 KB of 5.0 MB as hex.",
            binaryNote(sizeBytes = 5L * 1024 * 1024, shown = HEX_DUMP_BYTES),
        )
    }

    @Test
    fun `an empty file dumps to nothing rather than throwing`() {
        assertEquals("", hexDump(ByteArray(0), HEX_DUMP_BYTES))
    }
}
