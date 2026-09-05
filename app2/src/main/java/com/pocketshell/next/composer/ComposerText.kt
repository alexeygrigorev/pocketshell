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
     * The composed body as UTF-8, with no trailing Enter.
     *
     * Send writes this first, waits [com.pocketshell.next.settings.AppSettings.agentSubmitEnterDelayMs],
     * then writes [enterBytes] as a second PTY write. Concatenating body+CR
     * here is the race agents treat as a newline instead of submit (#2526).
     */
    fun bodyBytes(body: String): ByteArray = body.toByteArray(Charsets.UTF_8)

    /**
     * Carriage return — a PTY line-discipline "the user pressed Enter".
     *
     * `\r`, not `\n`: sending `\n` types a literal newline into readline
     * instead of submitting. A fresh array each call so a caller cannot
     * mutate a shared buffer.
     */
    fun enterBytes(): ByteArray = byteArrayOf(0x0D)

    /**
     * The composer's storage key for one session, shared by the draft store and
     * the sent-message log so both answer "which session is this" identically.
     */
    fun sessionKey(hostId: Long, sessionName: String): String = "$hostId/$sessionName"

    /**
     * Joins a draft and a dictated transcript (task P-2).
     *
     * A space between them, not a newline: dictation continues a sentence far
     * more often than it starts a paragraph, and the user can always type the
     * newline themselves. Shared by live dictation
     * ([AndroidSpeechRecognitionDelegate]) and offline-queued delivery
     * ([ComposerViewModel.onForegroundResume]) so both arms merge a transcript
     * into the draft the same way.
     */
    fun appendDictated(base: String, transcript: String): String = when {
        transcript.isBlank() -> base
        base.isBlank() -> transcript
        base.endsWith(" ") || base.endsWith("\n") -> base + transcript
        else -> "$base $transcript"
    }

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
