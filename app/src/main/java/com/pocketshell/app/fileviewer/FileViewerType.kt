package com.pocketshell.app.fileviewer

/**
 * How the in-app file viewer (issue #497) should render a fetched file.
 *
 * The viewer supports two preview modes NOW — images and text. Anything
 * else (binary-non-image, or a file that exceeded the size cap) renders a
 * friendly "can't preview" message instead of crashing.
 */
enum class FileViewerType {
    /** Renders in the zoom/pan image view (PNG/JPG/WebP/GIF/BMP). */
    IMAGE,

    /** Renders in the scrollable monospace read-only text view. */
    TEXT,

    /** Not previewable (binary, unknown, or undecodable). */
    BINARY,
}

/**
 * Pure type-decision for the file viewer — visible-for-test so the
 * image-vs-text-vs-binary call is pinned without an SSH session or Android
 * graphics.
 *
 * Decision order:
 *  1. If the bytes carry a known **image** magic-number header, it's an
 *     [FileViewerType.IMAGE] regardless of extension (the agent often
 *     references a path whose extension lies, and a content sniff is more
 *     reliable than the suffix).
 *  2. Else if the extension is a known image extension AND the bytes look
 *     image-ish enough not to be UTF-8 text, treat as image. (Covers exotic
 *     image formats we don't sniff but the OS decoder may still handle.)
 *  3. Else if the bytes are UTF-8-decodable text (no NUL bytes, valid
 *     UTF-8), it's [FileViewerType.TEXT].
 *  4. Else [FileViewerType.BINARY].
 */
object FileTypeDetector {

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp",
    )

    fun detect(remotePath: String, bytes: ByteArray): FileViewerType {
        if (looksLikeImageMagic(bytes)) return FileViewerType.IMAGE

        val ext = extensionOf(remotePath)
        val isUtf8Text = looksLikeUtf8Text(bytes)

        if (ext in IMAGE_EXTENSIONS && !isUtf8Text) return FileViewerType.IMAGE

        return if (isUtf8Text) FileViewerType.TEXT else FileViewerType.BINARY
    }

    /** Lower-cased extension after the last `.` of the final path segment, or "" . */
    internal fun extensionOf(remotePath: String): String {
        val name = remotePath.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * Magic-number sniff for the image formats the Android [android.graphics]
     * decoder reliably handles. Content sniffing beats the extension because
     * agent-referenced paths can have a misleading or absent suffix.
     */
    internal fun looksLikeImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG: 89 50 4E 47
        if (bytes.startsWith(0x89, 0x50, 0x4E, 0x47)) return true
        // JPEG: FF D8 FF
        if (bytes.startsWith(0xFF, 0xD8, 0xFF)) return true
        // GIF: "GIF8"
        if (bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code)) return true
        // BMP: "BM"
        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return true
        }
        // WebP: "RIFF"...."WEBP"
        if (bytes.size >= 12 &&
            bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return true
        }
        return false
    }

    /**
     * True when [bytes] decode cleanly as UTF-8 and contain no NUL byte.
     *
     * A NUL byte is the classic "this is binary" tell — text files don't
     * contain it. Beyond that we run a strict UTF-8 decode: invalid byte
     * sequences (which arbitrary binary almost always produces) reject the
     * file as non-text. An empty file is treated as text (an empty preview
     * is harmless and more useful than "binary").
     */
    internal fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.any { it == 0.toByte() }) return false
        // Only sniff a prefix — a huge file's first chunk is representative
        // and a full strict decode of a capped (<=20MB) file is still cheap,
        // but bounding the sniff keeps this fast and predictable.
        val sample = if (bytes.size > SNIFF_LIMIT) bytes.copyOf(SNIFF_LIMIT) else bytes
        return decodesAsUtf8(sample)
    }

    private fun decodesAsUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder().apply {
            onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        }
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            // If we truncated mid-multibyte-sequence at the sniff boundary,
            // a tail malformed-input is a false negative. Retry dropping the
            // last few bytes so a split codepoint at the edge doesn't reject
            // an otherwise-valid text file.
            if (bytes.size > SNIFF_LIMIT - 4) {
                val trimmed = bytes.copyOf(maxOf(0, bytes.size - 4))
                runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(trimmed))
                }.isSuccess
            } else {
                false
            }
        }
    }

    private const val SNIFF_LIMIT = 64 * 1024

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i].toByte()) return false
        }
        return true
    }
}
