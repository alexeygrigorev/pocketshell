package com.pocketshell.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for [QrChunkCodec] + [QrChunkAssembler] (issue #129).
 *
 * Robolectric runner is needed because `android.util.Base64` is the
 * implementation backing the URL-safe encoding in [QrChunkCodec].
 * Picking the Android-stdlib API rather than `java.util.Base64` keeps
 * the code minSdk-friendly without dragging in a third-party base64
 * dependency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QrChunkCodecTest {

    @Test
    fun encode_smallPayload_yieldsOnePart() {
        val parts = QrChunkCodec.encode("hello world", id = "deadbeef")
        assertEquals(1, parts.size)
        assertTrue(parts[0].startsWith("pocketshell.qr.v1?"))
        assertTrue("part=1/1 should be present", parts[0].contains("part=1/1"))
        assertTrue("id should be present", parts[0].contains("id=deadbeef"))
    }

    @Test
    fun encode_largePayload_yieldsMultipleParts() {
        val payload = "a".repeat(QrChunkCodec.ChunkSize * 3 + 17)
        val parts = QrChunkCodec.encode(payload, id = "deadbeef")
        assertEquals(4, parts.size)
        parts.forEachIndexed { idx, env ->
            assertTrue(
                "expected part=${idx + 1}/4 in envelope $env",
                env.contains("part=${idx + 1}/4"),
            )
        }
    }

    @Test
    fun roundTrip_singlePart_inOrder() {
        val payload = """{"type":"pocketshell.ssh-import.v1","name":"prod"}"""
        val parts = QrChunkCodec.encode(payload, id = "abcdef01")
        val assembler = QrChunkAssembler()
        val decoded = QrChunkCodec.decodePart(parts[0]).getOrThrow()
        val outcome = assembler.accept(decoded)
        assertTrue(outcome is QrChunkAssembler.Outcome.Complete)
        assertEquals(payload, (outcome as QrChunkAssembler.Outcome.Complete).payload)
    }

    @Test
    fun roundTrip_multiPart_inOrder() {
        val payload = "PRIVATE_KEY_LINE\n".repeat(400)
        val parts = QrChunkCodec.encode(payload, id = "11112222")
        assertTrue("expected multiple parts", parts.size > 1)
        val assembler = QrChunkAssembler()
        var lastOutcome: QrChunkAssembler.Outcome? = null
        for (env in parts) {
            val part = QrChunkCodec.decodePart(env).getOrThrow()
            lastOutcome = assembler.accept(part)
        }
        assertTrue(
            "expected final outcome to be Complete, got $lastOutcome",
            lastOutcome is QrChunkAssembler.Outcome.Complete,
        )
        assertEquals(
            payload,
            (lastOutcome as QrChunkAssembler.Outcome.Complete).payload,
        )
    }

    @Test
    fun roundTrip_multiPart_outOfOrder() {
        val payload = "X".repeat(QrChunkCodec.ChunkSize * 3 - 5)
        val parts = QrChunkCodec.encode(payload, id = "99887766")
        assertEquals(3, parts.size)
        val assembler = QrChunkAssembler()
        // Feed in order 3, 1, 2
        val order = listOf(2, 0, 1)
        var lastOutcome: QrChunkAssembler.Outcome? = null
        for (i in order) {
            val part = QrChunkCodec.decodePart(parts[i]).getOrThrow()
            lastOutcome = assembler.accept(part)
        }
        assertTrue(lastOutcome is QrChunkAssembler.Outcome.Complete)
        assertEquals(
            payload,
            (lastOutcome as QrChunkAssembler.Outcome.Complete).payload,
        )
    }

    @Test
    fun duplicatePart_isIgnored() {
        val payload = "Q".repeat(QrChunkCodec.ChunkSize * 2 + 1)
        val parts = QrChunkCodec.encode(payload, id = "feedface")
        assertTrue(parts.size >= 2)
        val assembler = QrChunkAssembler()
        val first = QrChunkCodec.decodePart(parts[0]).getOrThrow()
        assembler.accept(first)
        // Rescanning the same chunk should not advance progress.
        val again = assembler.accept(first)
        assertTrue(
            "expected duplicate outcome, got $again",
            again is QrChunkAssembler.Outcome.Duplicate,
        )
        val state = (again as QrChunkAssembler.Outcome.Duplicate).state
        assertEquals(1, state.count)
    }

    @Test
    fun partialThenTimeout_isDropped() {
        val payload = "Z".repeat(QrChunkCodec.ChunkSize * 2 + 1)
        val parts = QrChunkCodec.encode(payload, id = "1a2b3c4d")
        var fakeNow = 1_000L
        val assembler = QrChunkAssembler(expiryMillis = 60_000L) { fakeNow }
        val first = QrChunkCodec.decodePart(parts[0]).getOrThrow()
        assembler.accept(first)
        assertNotNull(assembler.current)
        // Advance the clock past the timeout.
        fakeNow += 60_001L
        assembler.pruneStale()
        assertNull(
            "expected pruning to drop the partial accumulation",
            assembler.current,
        )
    }

    @Test
    fun partialThenTimeoutThenRestart_acceptsFreshTransmission() {
        val payload = "A".repeat(QrChunkCodec.ChunkSize * 2 + 1)
        val parts = QrChunkCodec.encode(payload, id = "deadc0de")
        var fakeNow = 0L
        val assembler = QrChunkAssembler(expiryMillis = 60_000L) { fakeNow }
        val first = QrChunkCodec.decodePart(parts[0]).getOrThrow()
        assembler.accept(first)
        // Stale-out the partial.
        fakeNow += 60_500L
        assembler.pruneStale()
        assertNull(assembler.current)
        // Re-scan in order with the same id (user retried the desktop tool).
        var outcome: QrChunkAssembler.Outcome? = null
        for (env in parts) {
            outcome = assembler.accept(QrChunkCodec.decodePart(env).getOrThrow())
        }
        assertTrue(outcome is QrChunkAssembler.Outcome.Complete)
        assertEquals(payload, (outcome as QrChunkAssembler.Outcome.Complete).payload)
    }

    @Test
    fun differentIds_resetAccumulation() {
        val payload1 = "ONE".repeat(QrChunkCodec.ChunkSize)
        val payload2 = "TWO".repeat(QrChunkCodec.ChunkSize)
        val firstParts = QrChunkCodec.encode(payload1, id = "aaaaaaaa")
        val secondParts = QrChunkCodec.encode(payload2, id = "bbbbbbbb")
        val assembler = QrChunkAssembler()
        // Begin accumulating the first transmission.
        assembler.accept(QrChunkCodec.decodePart(firstParts[0]).getOrThrow())
        assertEquals("aaaaaaaa", assembler.current?.id)
        // A part from a different id should reset the accumulation.
        assembler.accept(QrChunkCodec.decodePart(secondParts[0]).getOrThrow())
        assertEquals("bbbbbbbb", assembler.current?.id)
        assertEquals(1, assembler.current?.count)
    }

    @Test
    fun decodePart_rejectsTamperedChecksum() {
        val parts = QrChunkCodec.encode("hello", id = "deadbeef")
        val tampered = parts[0].replace("checksum=", "checksum=ff")
        val result = QrChunkCodec.decodePart(tampered)
        assertTrue("expected failure for tampered checksum", result.isFailure)
    }

    @Test
    fun isEnvelope_recognisesPrefix() {
        val parts = QrChunkCodec.encode("hi", id = "12345678")
        assertTrue(QrChunkCodec.isEnvelope(parts[0]))
        assertFalse(QrChunkCodec.isEnvelope("""{"type":"pocketshell.ssh-import.v1"}"""))
    }

    @Test
    fun decodePart_rejectsNonEnvelope() {
        val result = QrChunkCodec.decodePart("totally not a pocketshell qr")
        assertTrue(result.isFailure)
    }

    @Test
    fun encode_isStableForSamePayloadAndId() {
        val payload = "REPEATABLE".repeat(40)
        val first = QrChunkCodec.encode(payload, id = "deadbeef")
        val second = QrChunkCodec.encode(payload, id = "deadbeef")
        assertEquals(first, second)
    }

    @Test
    fun decodePart_rejectsMalformedPartToken() {
        val parts = QrChunkCodec.encode("hello", id = "deadbeef")
        val broken = parts[0].replace("part=1/1", "part=bogus")
        assertTrue(QrChunkCodec.decodePart(broken).isFailure)
    }

    @Test
    fun complete_clearsAccumulationForNextTransmission() {
        val payload = "DONE"
        val parts = QrChunkCodec.encode(payload, id = "deadbeef")
        val assembler = QrChunkAssembler()
        val outcome = assembler.accept(QrChunkCodec.decodePart(parts[0]).getOrThrow())
        if (outcome !is QrChunkAssembler.Outcome.Complete) fail("expected Complete, got $outcome")
        assertNull(
            "assembler should clear once a transmission completes",
            assembler.current,
        )
    }
}
