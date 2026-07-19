package com.pocketshell.core.terminal.input

import java.io.ByteArrayOutputStream

/**
 * Shared bracketed-paste byte helpers for app-level tmux injection and the
 * live TerminalView IME path.
 */
object BracketedPaste {
    const val BODY_CHUNK_BYTES: Int = 1024

    private val pasteStart: ByteArray = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)
    private val pasteEnd: ByteArray = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E)
    private val hexDigits: CharArray = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun containsLineBreak(text: CharSequence?): Boolean {
        if (text == null) return false
        for (index in 0 until text.length) {
            if (text[index] == '\n') return true
        }
        return false
    }

    @JvmStatic
    fun containsLineBreak(bytes: ByteArray): Boolean {
        for (b in bytes) if (b == 0x0A.toByte()) return true
        return false
    }

    @JvmStatic
    fun frame(text: CharSequence): ByteArray =
        frame(text.toString().toByteArray(Charsets.UTF_8))

    @JvmStatic
    fun frame(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        val normalised = normaliseLineEndings(bytes)
        val out = ByteArrayOutputStream(pasteStart.size + normalised.size + pasteEnd.size)
        out.write(pasteStart)
        out.write(normalised)
        out.write(pasteEnd)
        return out.toByteArray()
    }

    @JvmStatic
    fun hexPayload(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return hex(frame(bytes))
    }

    /**
     * Issue #1636: the [frame]d paste block split into bounded, UTF-8-SAFE text
     * chunks, for the atomic `set-buffer`(-fill) + `paste-buffer`(-commit) route
     * that replaced the old `send-keys -H` chunk chain.
     *
     * Contract — the load-bearing property the #1636 regression suite pins:
     * `chunks.joinToString("").toByteArray(UTF_8)` is BYTE-IDENTICAL to
     * `frame(bytes)`. Two invariants make that hold:
     *
     *  - a chunk boundary NEVER falls inside a UTF-8 multi-byte sequence (a
     *    boundary is only taken where the next byte is not a `10xxxxxx`
     *    continuation byte), so every chunk is a whole run of complete code
     *    points and `String(bytes, UTF_8)` round-trips it exactly. A naive
     *    fixed-byte split would replace the halves of a split character with
     *    U+FFFD and silently corrupt the payload.
     *  - when [bodyChunkBytes] is smaller than a single code point the chunk
     *    grows forward to that code point's end rather than splitting it, so
     *    the invariant holds at any chunk size.
     *
     * Each chunk is a shell/tmux command ARGUMENT (single-quoted by the caller),
     * so — unlike the hex form — it carries the payload's raw bytes, including
     * the `\n` line breaks that are the whole point of a bracketed paste.
     */
    @JvmStatic
    fun frameTextChunks(
        bytes: ByteArray,
        bodyChunkBytes: Int = BODY_CHUNK_BYTES,
    ): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val framed = frame(bytes)
        val chunkSize = bodyChunkBytes.coerceAtLeast(1)
        val chunks = ArrayList<String>((framed.size + chunkSize - 1) / chunkSize + 1)
        var offset = 0
        while (offset < framed.size) {
            var end = minOf(offset + chunkSize, framed.size)
            // Back off to the nearest code-point boundary at/below the budget.
            while (end > offset && end < framed.size && isUtf8Continuation(framed[end])) end -= 1
            if (end <= offset) {
                // The budget is smaller than this single code point: take the whole
                // code point instead of splitting it (over-budget by <4 bytes).
                end = offset + 1
                while (end < framed.size && isUtf8Continuation(framed[end])) end += 1
            }
            chunks += String(framed, offset, end - offset, Charsets.UTF_8)
            offset = end
        }
        return chunks
    }

    private fun isUtf8Continuation(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

    @JvmStatic
    fun hex(bytes: ByteArray): String =
        hex(bytes, 0, bytes.size)

    @JvmStatic
    fun hex(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        val builder = StringBuilder(3 * length)
        appendHex(builder, bytes, offset, length)
        return builder.toString()
    }

    private fun normaliseLineEndings(bytes: ByteArray): ByteArray {
        var sawCr = false
        for (b in bytes) {
            if (b == 0x0D.toByte()) {
                sawCr = true
                break
            }
        }
        if (!sawCr) return bytes
        val out = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val b = bytes[index]
            if (b == 0x0D.toByte() && index + 1 < bytes.size && bytes[index + 1] == 0x0A.toByte()) {
                out.write(0x0A)
                index += 2
            } else {
                out.write(b.toInt() and 0xFF)
                index += 1
            }
        }
        return out.toByteArray()
    }

    private fun appendHex(
        builder: StringBuilder,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val end = minOf(bytes.size, offset + length)
        for (index in offset until end) {
            val b = bytes[index]
            if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
            val v = b.toInt() and 0xFF
            builder.append(hexDigits[(v ushr 4) and 0xF])
            builder.append(hexDigits[v and 0xF])
        }
    }
}
