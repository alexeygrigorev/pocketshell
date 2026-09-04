package com.pocketshell.next.hosts

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32

/**
 * The multi-QR envelope (`docs/ssh-qr-import.md` § Multi-QR Envelope):
 *
 * ```
 * pocketshell.qr.v1?part=<idx>/<count>&id=<short>&checksum=<crc32>&payload=<base64-chunk>
 * ```
 *
 * A single QR tops out around 2.8 KiB in practice before M-level error
 * correction starts failing on a phone camera, and an RSA-4096 key does not
 * fit. So every payload — including one that would fit — is wrapped in this
 * envelope, and a small one simply encodes as `part=1/1`. One decoder path,
 * regardless of size.
 *
 * Ported from the old client with one change: base64 goes through
 * `java.util.Base64`'s URL-safe, unpadded codec instead of `android.util.Base64`
 * with `URL_SAFE or NO_WRAP or NO_PADDING`. Byte-for-byte the same alphabet and
 * output — Python's `base64.urlsafe_b64encode(...).rstrip("=")` in
 * `tools/pocketshell/src/pocketshell/qr_share.py` is the third implementation of
 * the same thing — but it makes this file pure JVM, so the codec is tested
 * without an Android runtime.
 */
object QrChunkCodec {

    /** Envelope prefix. Bumped only on a protocol-breaking change. */
    const val ENVELOPE_PREFIX: String = "pocketshell.qr.v1?"

    /**
     * Per-chunk budget in raw bytes, before base64. 1500 raw bytes inflate to
     * ~2000 base64 chars, plus ~80 for the envelope tokens — comfortably inside
     * the practical QR limit.
     */
    const val CHUNK_SIZE: Int = 1500

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Encode [payload] into one or more envelope strings, part 1 → part N. */
    fun encode(payload: String): List<String> = encode(payload, generateId())

    /** [encode] with a caller-supplied transmission id, so a test is deterministic. */
    internal fun encode(payload: String, id: String): List<String> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        // An empty payload still has to produce one (empty) part, or the
        // receiver would see a 0-part transmission it can never complete.
        val chunks = if (bytes.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            bytes.toList().chunked(CHUNK_SIZE) { it.toByteArray() }
        }
        return chunks.mapIndexed { index, chunk ->
            buildEnvelope(part = index + 1, total = chunks.size, id = id, chunk = chunk)
        }
    }

    /**
     * Cheap prefix test. `true` does NOT mean the envelope is well-formed —
     * hand it to [decodePart] to find that out.
     */
    fun isEnvelope(text: String): Boolean = text.startsWith(ENVELOPE_PREFIX)

    /**
     * Parse one envelope. Fails on a wrong prefix, a missing token, a part
     * index outside `1..total`, undecodable base64, or a checksum mismatch —
     * each as a failed [Result], because every one of those is something a
     * misread camera frame produces routinely.
     */
    fun decodePart(text: String): Result<ChunkPart> = runCatching {
        require(isEnvelope(text)) { "Not a PocketShell QR envelope" }
        val params = parseQuery(text.substring(ENVELOPE_PREFIX.length))

        val partToken = requireNotNull(params["part"]) { "Missing part token" }
        val slash = partToken.indexOf('/')
        require(slash > 0) { "Malformed part token" }
        val part = partToken.substring(0, slash).toIntOrNull()
            ?: throw IllegalArgumentException("Malformed part token")
        val total = partToken.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Malformed part token")
        require(part in 1..total) { "Part index out of range" }

        val id = requireNotNull(params["id"]) { "Missing id token" }
        require(id.isNotBlank()) { "Empty id token" }
        val checksum = requireNotNull(params["checksum"]) { "Missing checksum token" }
        val encoded = requireNotNull(params["payload"]) { "Missing payload token" }

        val chunk = runCatching { decoder.decode(encoded) }.getOrElse {
            throw IllegalArgumentException("QR chunk payload is not valid base64", it)
        }
        val actual = crc32Hex(chunk)
        require(actual == checksum) { "Chunk checksum mismatch: expected $checksum, got $actual" }

        ChunkPart(id = id, part = part, total = total, chunk = chunk)
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            require(eq > 0) { "Malformed QR envelope segment" }
            put(segment.substring(0, eq), segment.substring(eq + 1))
        }
    }

    private fun buildEnvelope(part: Int, total: Int, id: String, chunk: ByteArray): String =
        ENVELOPE_PREFIX +
            "part=$part/$total" +
            "&id=$id" +
            "&checksum=${crc32Hex(chunk)}" +
            "&payload=${encoder.encodeToString(chunk)}"

    private fun crc32Hex(bytes: ByteArray): String =
        CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')

    /**
     * 8 hex chars from `SecureRandom`. It only has to keep two transmissions
     * that are in front of the same camera from being mixed together, but it is
     * cheap to make that unguessable rather than merely unlikely.
     */
    private fun generateId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * One decoded, checksum-verified envelope. [part] / [total] are 1-indexed.
 *
 * `equals`/`hashCode` are hand-written because [chunk] is a `ByteArray`, whose
 * generated equality would be identity — and this type is compared in tests and
 * stored in a map.
 */
class ChunkPart(
    val id: String,
    val part: Int,
    val total: Int,
    val chunk: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkPart) return false
        return id == other.id && part == other.part && total == other.total &&
            chunk.contentEquals(other.chunk)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + part
        result = 31 * result + total
        result = 31 * result + chunk.contentHashCode()
        return result
    }

    override fun toString(): String = "ChunkPart(id=$id, part=$part/$total, ${chunk.size}B)"
}

/**
 * Accumulates the parts of one multi-QR transmission, keyed by its id.
 *
 * Single-transmission by contract: a part with a different id replaces the
 * accumulation rather than merging into it, so a stray rescan of an unrelated
 * QR cannot corrupt a scan that is halfway done. A partial scan is dropped
 * after [expiryMillis] of idle so the progress chip cannot get stuck on a
 * transmission the user walked away from.
 */
class QrChunkAssembler(
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** In-flight accumulation. [total] is the broadcast part count. */
    data class State(
        val id: String,
        val total: Int,
        val seen: Map<Int, ByteArray>,
        val lastUpdatedAt: Long,
    ) {
        val count: Int get() = seen.size
        val isComplete: Boolean get() = seen.size == total

        fun assemble(): ByteArray {
            check(isComplete) { "Not all chunks have arrived" }
            val out = ByteArrayOutputStream()
            for (index in 1..total) {
                out.write(requireNotNull(seen[index]) { "Missing chunk $index" })
            }
            return out.toByteArray()
        }
    }

    /** What [accept] did with a part. */
    sealed interface Outcome {
        /** Stored; the payload is still incomplete. */
        data class Progress(val state: State) : Outcome

        /** Every part has arrived; [payload] is the assembled UTF-8 text. */
        data class Complete(val payload: String) : Outcome

        /** This index was already held — rescanning one QR is harmless. */
        data class Duplicate(val state: State) : Outcome
    }

    private var state: State? = null

    val current: State? get() = state

    fun accept(part: ChunkPart): Outcome {
        pruneStale()
        val now = clock()
        val existing = state

        // A different transmission id, or the same id claiming a different part
        // count (one of the two reads is wrong and there is no way to tell
        // which), restarts the accumulation on the newer claim rather than
        // merging two payloads into one corrupt buffer.
        if (existing == null || existing.id != part.id || existing.total != part.total) {
            return settle(startFreshState(part, now))
        }
        if (existing.seen.containsKey(part.part)) return Outcome.Duplicate(existing)

        val next = existing.copy(
            seen = existing.seen + (part.part to part.chunk),
            lastUpdatedAt = now,
        )
        return settle(next)
    }

    /** Drop any in-flight accumulation. */
    fun reset() {
        state = null
    }

    /**
     * Discard the accumulation if it has been idle past [expiryMillis]. Called
     * from [accept]; exposed so a UI can also clear its progress chip on a timer.
     */
    fun pruneStale() {
        val existing = state ?: return
        if (clock() - existing.lastUpdatedAt >= expiryMillis) state = null
    }

    private fun startFreshState(part: ChunkPart, now: Long): State = State(
        id = part.id,
        total = part.total,
        seen = mapOf(part.part to part.chunk),
        lastUpdatedAt = now,
    )

    private fun settle(next: State): Outcome {
        state = next
        return if (next.isComplete) {
            val payload = String(next.assemble(), Charsets.UTF_8)
            // A completed transmission is handed over exactly once; holding it
            // would make the next scan look like a duplicate.
            state = null
            Outcome.Complete(payload)
        } else {
            Outcome.Progress(next)
        }
    }

    private companion object {
        const val DEFAULT_EXPIRY_MILLIS = 60_000L
    }
}
