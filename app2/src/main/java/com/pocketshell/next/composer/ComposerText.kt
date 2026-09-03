package com.pocketshell.next.composer

/**
 * The pure text rules of the composer (rewrite task P-1).
 *
 * Everything here is a function of its arguments, so the exact bytes a send
 * puts on the wire are pinned by host-JVM tests rather than inferred from a
 * screenshot.
 */
internal object ComposerText {

    /**
     * The message body: the user's draft with a block listing every staged
     * attachment's remote path.
     *
     * Byte-for-byte the old client's `appendAttachmentPaths` (a blank line, the
     * literal `Attached files:` header, then one `- <path>` per file). It is a
     * wire format an agent or a shell on the other end has been reading for a
     * year — an "improvement" here would silently change what every host-side
     * consumer sees, so the port is deliberate rather than a re-derivation.
     */
    fun compose(draft: String, attachmentPaths: List<String>): String {
        if (attachmentPaths.isEmpty()) return draft
        val block = buildString {
            append("Attached files:")
            attachmentPaths.forEach { path ->
                append('\n')
                append("- ")
                append(path)
            }
        }
        return when {
            draft.isBlank() -> block
            draft.endsWith("\n\n") -> draft + block
            draft.endsWith("\n") -> draft + "\n" + block
            else -> draft + "\n\n" + block
        }
    }

    /**
     * What actually leaves the app: the composed body plus the carriage return
     * that submits it.
     *
     * `\r`, not `\n`: this goes into a PTY, where a terminal line discipline
     * turns carriage return into "the user pressed Enter". Sending `\n` types a
     * literal newline into readline instead of submitting, which is the
     * difference between a prompt running and a prompt sitting there.
     */
    fun wireBytes(body: String): ByteArray = (body + "\r").toByteArray(Charsets.UTF_8)

    /**
     * The composer's storage key for one session, shared by the draft store and
     * the sent-message log so both answer "which session is this" identically.
     */
    fun sessionKey(hostId: Long, sessionName: String): String = "$hostId/$sessionName"

    /** The short label for a staged attachment tile: its remote file name. */
    fun attachmentDisplayName(remotePath: String): String {
        val trimmed = remotePath.trimEnd('/')
        return trimmed.substringAfterLast('/').ifBlank { remotePath }
    }

    /**
     * A one-line preview of a history entry.
     *
     * A prompt is routinely a paragraph; a history row is one line. The first
     * non-blank line, ellipsised, is what makes a list of them scannable — and
     * the full text still goes back into the draft when the row is tapped.
     */
    fun historyLabel(body: String, maxChars: Int = 80): String {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val line = firstLine.ifBlank { body.trim() }
        return if (line.length <= maxChars) line else line.take(maxChars - 1).trimEnd() + "…"
    }
}
