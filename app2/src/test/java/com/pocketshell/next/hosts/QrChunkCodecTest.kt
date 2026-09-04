package com.pocketshell.next.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QrChunkCodec] and [QrChunkAssembler] on the plain JVM — no Robolectric, no
 * Android, because the port to `java.util.Base64` made the envelope pure Kotlin.
 *
 * What these pin is the wire format, which has three independent
 * implementations (this one, the shipping client's, and
 * `tools/pocketshell/src/pocketshell/qr_share.py`). A change here that looks
 * harmless — padding, alphabet, token order — silently stops the desktop
 * emitter's QRs from scanning, and nothing else in the build would notice.
 */
class QrChunkCodecTest {

    @Test
    fun `a small payload encodes as a single part and round-trips`() {
        val payload = """{"type":"pocketshell.ssh-import.v1","version":1}"""

        val parts = QrChunkCodec.encode(payload, id = "deadbeef")

        assertEquals(1, parts.size)
        assertTrue(parts.single().startsWith("pocketshell.qr.v1?part=1/1&id=deadbeef&checksum="))

        val decoded = QrChunkCodec.decodePart(parts.single()).getOrThrow()
        assertEquals(1, decoded.part)
        assertEquals(1, decoded.total)
        assertEquals(payload, String(decoded.chunk, Charsets.UTF_8))
    }

    @Test
    fun `a payload larger than one chunk splits and reassembles in order`() {
        // 3.5 chunks: enough to prove the split, the ordering, and that the
        // final short chunk is not padded out.
        val payload = (1..QrChunkCodec.CHUNK_SIZE * 7 / 2).joinToString("") { (it % 10).toString() }

        val parts = QrChunkCodec.encode(payload, id = "cafebabe")
        assertEquals(4, parts.size)

        val assembler = QrChunkAssembler()
        val outcomes = parts.map { assembler.accept(QrChunkCodec.decodePart(it).getOrThrow()) }

        assertEquals(
            listOf(1, 2, 3),
            outcomes.dropLast(1).map { (it as QrChunkAssembler.Outcome.Progress).state.count },
        )
        assertEquals(payload, (outcomes.last() as QrChunkAssembler.Outcome.Complete).payload)
    }

    @Test
    fun `parts scanned out of order still assemble in index order`() {
        val payload = "x".repeat(QrChunkCodec.CHUNK_SIZE * 2 + 5)
        val parts = QrChunkCodec.encode(payload, id = "0f0f0f0f")
            .map { QrChunkCodec.decodePart(it).getOrThrow() }

        val assembler = QrChunkAssembler()
        assembler.accept(parts[2])
        assembler.accept(parts[0])
        val outcome = assembler.accept(parts[1])

        assertEquals(payload, (outcome as QrChunkAssembler.Outcome.Complete).payload)
    }

    @Test
    fun `rescanning the same part is a duplicate, not corruption`() {
        val parts = QrChunkCodec.encode("y".repeat(QrChunkCodec.CHUNK_SIZE + 1), id = "11223344")
            .map { QrChunkCodec.decodePart(it).getOrThrow() }
        val assembler = QrChunkAssembler()

        assembler.accept(parts[0])
        val repeat = assembler.accept(parts[0])

        assertTrue(repeat is QrChunkAssembler.Outcome.Duplicate)
        assertEquals(1, (repeat as QrChunkAssembler.Outcome.Duplicate).state.count)
        // The transmission is still completable after the duplicate.
        assertTrue(assembler.accept(parts[1]) is QrChunkAssembler.Outcome.Complete)
    }

    @Test
    fun `a part from a different transmission restarts the accumulation`() {
        val first = QrChunkCodec.encode("a".repeat(QrChunkCodec.CHUNK_SIZE + 1), id = "aaaaaaaa")
            .map { QrChunkCodec.decodePart(it).getOrThrow() }
        val second = QrChunkCodec.encode("b".repeat(QrChunkCodec.CHUNK_SIZE + 1), id = "bbbbbbbb")
            .map { QrChunkCodec.decodePart(it).getOrThrow() }
        val assembler = QrChunkAssembler()

        assembler.accept(first[0])
        val switched = assembler.accept(second[0])

        // Mixing the two payloads' bytes together would be a silently corrupt
        // import, so the newer id wins outright.
        assertEquals("bbbbbbbb", (switched as QrChunkAssembler.Outcome.Progress).state.id)
        assertEquals(1, switched.state.count)
    }

    @Test
    fun `a stale partial scan is dropped after the expiry`() {
        var now = 0L
        val assembler = QrChunkAssembler(expiryMillis = 60_000L, clock = { now })
        val parts = QrChunkCodec.encode("c".repeat(QrChunkCodec.CHUNK_SIZE + 1), id = "cccccccc")
            .map { QrChunkCodec.decodePart(it).getOrThrow() }

        assembler.accept(parts[0])
        assertNotNull(assembler.current)

        now += 60_000L
        assembler.pruneStale()

        assertNull(assembler.current)
    }

    @Test
    fun `a foreign QR is rejected, not parsed`() {
        assertFalse(QrChunkCodec.isEnvelope("https://example.com"))
        assertTrue(QrChunkCodec.decodePart("https://example.com").isFailure)
    }

    @Test
    fun `a corrupted chunk fails its checksum instead of importing garbage`() {
        val envelope = QrChunkCodec.encode("hello", id = "deadbeef").single()

        // Flip the payload without touching the checksum — a misread camera
        // frame, which must not reach the import path.
        val tampered = envelope.substringBeforeLast("&payload=") + "&payload=" +
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hellp".toByteArray(Charsets.UTF_8))

        val failure = QrChunkCodec.decodePart(tampered).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("checksum mismatch"))
    }

    @Test
    fun `a malformed envelope fails cleanly rather than throwing`() {
        val malformed = listOf(
            "pocketshell.qr.v1?",
            "pocketshell.qr.v1?part=1",
            "pocketshell.qr.v1?part=0/1&id=x&checksum=00000000&payload=",
            "pocketshell.qr.v1?part=2/1&id=x&checksum=00000000&payload=",
            "pocketshell.qr.v1?part=one/two&id=x&checksum=00000000&payload=",
            "pocketshell.qr.v1?part=1/1&checksum=00000000&payload=",
            "pocketshell.qr.v1?part=1/1&id=x&payload=",
            "pocketshell.qr.v1?part=1/1&id=x&checksum=00000000&payload=!!!not-base64!!!",
        )

        malformed.forEach { text ->
            assertTrue("expected '$text' to fail", QrChunkCodec.decodePart(text).isFailure)
        }
    }

    /**
     * The base64 alphabet, pinned against the Python emitter's
     * `urlsafe_b64encode(...).rstrip("=")`.
     *
     * `U+FFFF` is chosen because its UTF-8 bytes (`EF BF BF`) hit exactly the
     * two alphabet slots that differ between standard and URL-safe base64
     * (62 → `+` vs `-`, 63 → `/` vs `_`), and the payload length forces padding
     * that must be stripped. Getting any of the three wrong makes every QR the
     * desktop `pocketshell qr-share` emits unscannable, with nothing else in
     * the build failing.
     */
    @Test
    fun `chunk payloads use url-safe unpadded base64`() {
        val payload = "\uFFFF"

        val encoded = QrChunkCodec.encode(payload, id = "deadbeef").single()
        val body = encoded.substringAfter("&payload=")

        assertFalse("padding must be stripped", body.contains("="))
        assertFalse("must not use the standard alphabet", body.contains("+") || body.contains("/"))
        assertTrue("expected the url-safe alphabet in '$body'", body.contains("-") && body.contains("_"))
        assertEquals(
            payload,
            String(QrChunkCodec.decodePart(encoded).getOrThrow().chunk, Charsets.UTF_8),
        )
    }
}
