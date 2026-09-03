package com.pocketshell.next.files

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How the viewer should render a fetched file (rewrite task P-3b).
 *
 * Three kinds, down from the old client's five: PDF and AUDIO are deliberately
 * not carried over in this slice (see [ViewerScreen] for the reasoning). An
 * unrecognised file is [BINARY] and gets a hex preview — never a crash and never
 * a blank screen.
 */
enum class FileKind {
    /** UTF-8 decodable text. Editable; rendered as Markdown when the name says so. */
    TEXT,

    /** A bitmap the platform decoder handles (PNG/JPEG/GIF/BMP/WebP). */
    IMAGE,

    /** Not previewable as text or an image — shown as a bounded hex dump. */
    BINARY,
}

/**
 * Decides a file's [FileKind] from its bytes first and its name second.
 *
 * Content beats extension because a remote path's suffix lies often enough to
 * matter: a `.log` that is actually a gzip, a screenshot saved without an
 * extension, a `.txt` holding a PNG. The order is:
 *
 *  1. A known image magic number ⇒ [FileKind.IMAGE], whatever the name says.
 *  2. A known image *extension* whose bytes are clearly not text ⇒
 *     [FileKind.IMAGE]. This covers the formats we do not sniff but the
 *     platform decoder may still open.
 *  3. Bytes that decode as UTF-8 with no NUL ⇒ [FileKind.TEXT].
 *  4. Otherwise [FileKind.BINARY].
 *
 * Ported from the old client's `FileTypeDetector` minus its PDF and audio arms.
 * Pure Kotlin, so every branch is pinned without an emulator.
 */
object FileKindDetector {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

    /** How much of a file is sniffed for text-ness. A prefix is representative. */
    private const val SNIFF_LIMIT = 64 * 1024

    fun detect(path: String, bytes: ByteArray): FileKind {
        if (looksLikeImageMagic(bytes)) return FileKind.IMAGE
        val isText = looksLikeUtf8Text(bytes)
        if (!isText && extensionOf(path) in IMAGE_EXTENSIONS) return FileKind.IMAGE
        return if (isText) FileKind.TEXT else FileKind.BINARY
    }

    /** True when [path] names a Markdown document — the render-vs-raw switch. */
    fun isMarkdown(path: String): Boolean = extensionOf(path) in MARKDOWN_EXTENSIONS

    /** Lower-cased extension of the final path segment, or "" when there is none. */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        // No dot, a leading-dot dotfile (".bashrc"), or a trailing dot: no extension.
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * Magic-number sniff for the formats Android's `BitmapFactory` reliably
     * decodes. Only has to be good enough to route the file to the image
     * renderer; the decode itself is the platform's job.
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
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        // WebP: "RIFF"…"WEBP"
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
     * A NUL is the classic binary tell — text files do not carry one. Beyond
     * that this runs a STRICT decode, which arbitrary binary almost always
     * fails. An empty file counts as text: an empty editor is more useful than
     * a hex dump of nothing.
     */
    internal fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = if (bytes.size > SNIFF_LIMIT) bytes.copyOf(SNIFF_LIMIT) else bytes
        if (sample.any { it == 0.toByte() }) return false
        return decodesAsUtf8(sample) ||
            // A multi-byte codepoint split by the sniff boundary is a false
            // negative, not a binary file: retry without the straddling tail.
            (sample.size == SNIFF_LIMIT && decodesAsUtf8(sample.copyOf(SNIFF_LIMIT - 4)))
    }

    private fun decodesAsUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i].toByte()) return false
        }
        return true
    }
}
