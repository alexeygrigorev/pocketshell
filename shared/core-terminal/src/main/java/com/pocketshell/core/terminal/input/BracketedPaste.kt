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

    @JvmStatic
    fun hexChunks(
        bytes: ByteArray,
        bodyChunkBytes: Int = BODY_CHUNK_BYTES,
    ): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val chunkSize = bodyChunkBytes.coerceAtLeast(1)
        val normalised = normaliseLineEndings(bytes)
        val chunks = ArrayList<String>((normalised.size + chunkSize - 1) / chunkSize + 2)
        chunks += hex(pasteStart)
        var offset = 0
        while (offset < normalised.size) {
            val length = minOf(chunkSize, normalised.size - offset)
            chunks += hex(normalised, offset, length)
            offset += length
        }
        chunks += hex(pasteEnd)
        return chunks
    }

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
