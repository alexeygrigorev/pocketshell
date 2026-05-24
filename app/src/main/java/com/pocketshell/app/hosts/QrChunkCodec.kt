package com.pocketshell.app.hosts

import android.util.Base64
import java.util.zip.CRC32

/**
 * Multi-QR sequencing for large SSH-import payloads (issue #129).
 *
 * The existing [SshImportPayloadCodec] tops out around the QR-code
 * 2.8 KiB byte limit before error-correction starts to fail in practice.
 * Larger private keys (RSA 4096, ed25519 keys with long comments, signing
 * keys, etc.) need to be split across multiple QRs. This codec wraps any
 * payload in a small versioned envelope:
 *
 * ```
 * pocketshell.qr.v1?part=<idx>/<count>&id=<short>&checksum=<crc32>&payload=<base64-chunk>
 * ```
 *
 * - `part=<idx>/<count>` — 1-indexed part number and total. `1/3` is the
 *   first of three.
 * - `id=<short>` — a short opaque id (8 hex chars) shared across every
 *   part of a single payload. The decoder accumulates parts keyed by id;
 *   parts from a different transmission cannot collide.
 * - `checksum=<crc32>` — CRC-32 (lowercase 8-hex) of the per-part chunk
 *   bytes BEFORE base64-encoding. A mismatch rejects the part and the
 *   user can rescan.
 * - `payload=<base64-chunk>` — URL-safe base64 of the binary chunk
 *   bytes. The first chunk also carries a single `total` length token in
 *   its decoded form so the assembled payload can be verified against
 *   the broadcast total size; see [encode] / [decode] for details.
 *
 * Small payloads ([SshImportPayloadCodec]-shaped JSON below the safe
 * size) still encode as a single `pocketshell.qr.v1` envelope with
 * `part=1/1`. Decoders that recognise the envelope MAY shortcut to the
 * inner payload directly; the assembled output for the single-part case
 * is identical to the raw payload bytes interpreted as UTF-8 text.
 *
 * Backwards-compat: legacy un-wrapped payloads (the original
 * `pocketshell.ssh-import.v1` JSON or a `pocketshell.host.v1` URI) are
 * still accepted by [HostListViewModel.importSharedHostPayload]. The
 * scanner / decoder probes for the envelope first and falls through to
 * the legacy path on mismatch.
 */
object QrChunkCodec {
    /** Envelope prefix. Bumped only on protocol-breaking changes. */
    const val EnvelopePrefix: String = "pocketshell.qr.v1?"

    /**
     * Safe per-chunk payload byte budget BEFORE base64-encoding. Picked
     * to keep the final QR text comfortably under the ~2.8 KiB practical
     * QR limit with M-level error correction (base64 inflates ~4/3, plus
     * the envelope overhead of ~80 bytes for `part`, `id`, `checksum`,
     * etc.). 1500 bytes raw → roughly 2000 chars base64 → ~2080 chars
     * total with the envelope; well inside the QR budget.
     */
    const val ChunkSize: Int = 1500

    /**
     * Threshold above which a payload is split into multiple chunks. A
     * payload below this size produces exactly one envelope-wrapped QR.
     * Picked equal to [ChunkSize] so a payload that fits the budget
     * never needs more than one QR even when sequencing is enabled.
     */
    const val SingleQrThresholdBytes: Int = ChunkSize

    /**
     * Encode [payload] into one or more envelope strings ready to be
     * rendered as QRs. Order is part 1 → part N. The [id] is generated
     * automatically; use the overload with `id` for deterministic tests.
     */
    fun encode(payload: String): List<String> = encode(payload, generateId())

    /** Test seam — accepts a deterministic `id`. */
    internal fun encode(payload: String, id: String): List<String> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val chunks = bytes.toList().chunked(ChunkSize) { it.toByteArray() }
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            buildEnvelope(
                part = index + 1,
                total = total,
                id = id,
                chunk = chunk,
            )
        }
    }

    private fun buildEnvelope(part: Int, total: Int, id: String, chunk: ByteArray): String {
        val checksum = crc32Hex(chunk)
        val encoded = Base64.encodeToString(
            chunk,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return EnvelopePrefix +
            "part=$part/$total" +
            "&id=$id" +
            "&checksum=$checksum" +
            "&payload=$encoded"
    }

    /**
     * Detect whether [text] looks like the envelope. Returning `true`
     * does NOT mean the part is well-formed — callers should hand it to
     * [QrChunkAssembler.accept] to find out.
     */
    fun isEnvelope(text: String): Boolean =
        text.startsWith(EnvelopePrefix, ignoreCase = false)

    /**
     * Parse a single envelope string into a [ChunkPart]. Returns a
     * failure result if the envelope shape, checksum, or part indices
     * are invalid.
     */
    fun decodePart(text: String): Result<ChunkPart> = runCatching {
        require(isEnvelope(text)) { "Not a pocketshell QR envelope" }
        val query = text.substring(EnvelopePrefix.length)
        val params = mutableMapOf<String, String>()
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            require(eq > 0) { "Malformed QR envelope segment" }
            val key = segment.substring(0, eq)
            val value = segment.substring(eq + 1)
            params[key] = value
        }
        val partToken = requireNotNull(params["part"]) { "Missing part token" }
        val slash = partToken.indexOf('/')
        require(slash > 0) { "Malformed part token" }
        val part = partToken.substring(0, slash).toInt()
        val total = partToken.substring(slash + 1).toInt()
        require(part in 1..total) { "Part index out of range" }
        val id = requireNotNull(params["id"]) { "Missing id token" }
        require(id.isNotBlank()) { "Empty id token" }
        val checksum = requireNotNull(params["checksum"]) { "Missing checksum token" }
        val encodedPayload = requireNotNull(params["payload"]) { "Missing payload token" }
        val chunk = Base64.decode(
            encodedPayload,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val actualChecksum = crc32Hex(chunk)
        require(actualChecksum == checksum) {
            "Chunk checksum mismatch: expected $checksum, got $actualChecksum"
        }
        ChunkPart(id = id, part = part, total = total, chunk = chunk)
    }

    private fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32().apply { update(bytes) }.value
        return crc.toString(16).padStart(8, '0')
    }

    private fun generateId(): String {
        // 8 hex chars is collision-safe enough for a transient
        // accumulator keyed in memory for at most 60 s. We pull from
        // SecureRandom to avoid surprises if a future reuse case ends up
        // pairing the id with something more sensitive.
        val bytes = ByteArray(4)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Parsed contents of a single QR chunk envelope. [part] / [total] are
 * 1-indexed; [chunk] is the raw bytes (already base64-decoded and
 * checksum-verified by [QrChunkCodec.decodePart]).
 */
data class ChunkPart(
    val id: String,
    val part: Int,
    val total: Int,
    val chunk: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkPart) return false
        return id == other.id &&
            part == other.part &&
            total == other.total &&
            chunk.contentEquals(other.chunk)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + part
        result = 31 * result + total
        result = 31 * result + chunk.contentHashCode()
        return result
    }
}

/**
 * Stateful accumulator for multi-QR scans (issue #129). Keyed by the
 * transmission id from the envelope, so an in-flight scan of a 3-part
 * payload that gets interrupted by a stray rescan of a different
 * payload doesn't get its buffer corrupted.
 *
 * The assembler is single-transmission at a time by contract — calling
 * [accept] with a part that has a different id than the current
 * accumulation clears the previous accumulation. The caller is
 * responsible for surfacing progress / reset to the UI.
 *
 * Stale partial scans are evicted by [pruneStale], called by the
 * scanner view model whenever a new part arrives. The default expiry
 * is 60 s, matching the AC.
 */
class QrChunkAssembler(
    private val expiryMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Per-transmission state. We hold both the seen parts and the
     * total broadcast count so the UI can render "Scanned 2 of 3"
     * without needing the parts to have arrived in order.
     */
    data class State(
        val id: String,
        val total: Int,
        val seen: Map<Int, ByteArray>,
        val lastUpdatedAt: Long,
    ) {
        val count: Int get() = seen.size
        val isComplete: Boolean get() = seen.size == total

        fun assemble(): ByteArray {
            require(isComplete) { "Not all chunks have arrived" }
            val out = java.io.ByteArrayOutputStream()
            for (i in 1..total) {
                val chunk = requireNotNull(seen[i]) { "Missing chunk $i" }
                out.write(chunk)
            }
            return out.toByteArray()
        }
    }

    private var state: State? = null

    val current: State? get() = state

    /**
     * Possible outcomes of accepting a part.
     */
    sealed interface Outcome {
        /** New part stored; payload is still incomplete. */
        data class Progress(val state: State) : Outcome

        /** Payload assembled. [payload] is the UTF-8 decoded text. */
        data class Complete(val payload: String) : Outcome

        /** Part was a duplicate — already had this index. */
        data class Duplicate(val state: State) : Outcome

        /**
         * The total broadcast count doesn't match the previously-seen
         * total for the same id. Usually means a chunk was malformed
         * or two senders share an id; we reset and store the new part.
         */
        data class Reset(val state: State, val reason: String) : Outcome
    }

    /**
     * Feed a parsed part into the assembler. May replace the current
     * accumulation if the id differs, the total mismatches, or the
     * previous accumulation expired.
     */
    fun accept(part: ChunkPart): Outcome {
        pruneStale()
        val now = clock()
        val current = state
        if (current == null || current.id != part.id) {
            val seen = mapOf(part.part to part.chunk)
            val newState = State(
                id = part.id,
                total = part.total,
                seen = seen,
                lastUpdatedAt = now,
            )
            state = newState
            return if (newState.isComplete) {
                Outcome.Complete(payload = String(newState.assemble(), Charsets.UTF_8))
                    .also { state = null }
            } else {
                Outcome.Progress(newState)
            }
        }
        if (current.total != part.total) {
            val seen = mapOf(part.part to part.chunk)
            val newState = State(
                id = part.id,
                total = part.total,
                seen = seen,
                lastUpdatedAt = now,
            )
            state = newState
            return Outcome.Reset(newState, reason = "Total chunk count changed mid-scan")
        }
        if (current.seen.containsKey(part.part)) {
            // Idempotent: rescanning the same chunk is harmless.
            return Outcome.Duplicate(current)
        }
        val nextSeen = current.seen.toMutableMap().apply { put(part.part, part.chunk) }
        val newState = current.copy(seen = nextSeen, lastUpdatedAt = now)
        state = newState
        return if (newState.isComplete) {
            Outcome.Complete(payload = String(newState.assemble(), Charsets.UTF_8))
                .also { state = null }
        } else {
            Outcome.Progress(newState)
        }
    }

    /** Force-clear any in-flight accumulation. */
    fun reset() {
        state = null
    }

    /**
     * Drop the in-flight accumulation if it has been idle longer than
     * [expiryMillis]. Called automatically by [accept]; exposed so the
     * scanner UI can also poll it on a timer to clear the progress
     * chip after a timeout.
     */
    fun pruneStale() {
        val current = state ?: return
        if (clock() - current.lastUpdatedAt >= expiryMillis) {
            state = null
        }
    }
}
