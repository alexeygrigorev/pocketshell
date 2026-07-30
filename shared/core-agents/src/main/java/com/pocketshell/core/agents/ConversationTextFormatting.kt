package com.pocketshell.core.agents

/**
 * Display-time cleaners for conversation transcript text (#704).
 *
 * Agent JSONL feeds wrap internal-protocol metadata in XML-style tags such as
 * `<task-id>a1887b43e9b725929</task-id>`. When such a wrapper leaks into a
 * `Message` body or a `SystemNote` preview the transcript renders a raw,
 * unstyled XML block that "doesn't look like the rest" of the chat — the
 * maintainer's #704 complaint #1.
 *
 * These helpers strip the noise wrappers (a hash id with zero user value)
 * while preserving the inner text of human-readable wrappers, so the renderer
 * never shows raw internal XML. The logic lives in `core-agents` (not `:app`)
 * so it stays unit-testable on the JVM and both the tmux-CC and raw-SSH panes
 * share one source of truth.
 */
public object ConversationTextFormatting {

    public data class BoundedNoiseStrippedText(
        val text: String,
        val consumedAllInput: Boolean,
    )

    /**
     * Internal-protocol wrapper tags that carry no user-facing value (opaque
     * ids / routing metadata). Both the tags AND their inner content are
     * removed from displayed text.
     */
    private val NoiseTags: List<String> = listOf(
        "task-id",
        "tool-use-id",
        "request-id",
        "session-id",
    )

    private val NoiseTagRegexes: List<Regex> = NoiseTags.map { tag ->
        Regex(
            pattern = "(?s)<" + Regex.escape(tag) + "(?:\\s[^>]*)?>.*?</" + Regex.escape(tag) + ">",
            option = RegexOption.IGNORE_CASE,
        )
    }

    /**
     * Strip internal-protocol noise wrappers (and their content) from a piece
     * of transcript text, collapsing the whitespace they leave behind. Returns
     * the cleaned text; may be blank if the whole payload was noise.
     */
    public fun stripInternalProtocolNoise(text: String): String {
        var result = text
        for (regex in NoiseTagRegexes) {
            result = regex.replace(result, "")
        }
        // Collapse the blank lines / leading-trailing space the removal leaves.
        return result
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Extract presentable text without scanning an arbitrarily large message.
     *
     * Recognized noise wrappers are skipped as ranges, so their opaque payload
     * is never copied into a partial preview. If a wrapper does not close
     * within [maxScanChars], extraction stops before the opening tag instead
     * of leaking an unmatchable raw fragment.
     */
    public fun stripInternalProtocolNoiseBounded(
        text: String,
        maxVisibleChars: Int,
        maxScanChars: Int,
    ): BoundedNoiseStrippedText {
        require(maxVisibleChars > 0)
        require(maxScanChars >= maxVisibleChars)

        val scanEndExclusive = minOf(text.length, maxScanChars)
        val visible = StringBuilder(minOf(maxVisibleChars, text.length))
        var cursor = 0
        while (cursor < scanEndExclusive && visible.length < maxVisibleChars) {
            val wrapperEnd = if (text[cursor] == '<') {
                recognizedNoiseWrapperEnd(
                    text = text,
                    start = cursor,
                    scanEndExclusive = scanEndExclusive,
                )
            } else {
                null
            }
            when {
                wrapperEnd == INCOMPLETE_NOISE_WRAPPER -> {
                    cursor = scanEndExclusive
                }
                wrapperEnd != null -> {
                    cursor = wrapperEnd
                }
                else -> {
                    visible.append(text[cursor])
                    cursor += 1
                }
            }
        }

        return BoundedNoiseStrippedText(
            text = stripInternalProtocolNoise(visible.toString()),
            consumedAllInput = cursor == text.length,
        )
    }

    /**
     * True when [text], once internal-protocol noise is stripped, has no
     * presentable content left. The renderer drops such entries entirely
     * instead of showing a styled-but-empty row.
     */
    public fun isOnlyInternalProtocolNoise(text: String): Boolean {
        val firstContentIndex = text.indexOfFirst { !it.isWhitespace() }
        if (firstContentIndex < 0 || text[firstContentIndex] != '<') return false
        return stripInternalProtocolNoise(text).isBlank()
    }

    private fun recognizedNoiseWrapperEnd(
        text: String,
        start: Int,
        scanEndExclusive: Int,
    ): Int? {
        for (tag in NoiseTags) {
            val openingPrefix = "<$tag"
            if (!text.regionMatches(start, openingPrefix, 0, openingPrefix.length, ignoreCase = true)) {
                continue
            }
            val boundaryIndex = start + openingPrefix.length
            if (boundaryIndex >= scanEndExclusive) return INCOMPLETE_NOISE_WRAPPER
            val boundary = text[boundaryIndex]
            if (boundary != '>' && !boundary.isWhitespace()) continue

            val openingEnd = text.indexOfBounded(
                needle = ">",
                startIndex = boundaryIndex,
                endExclusive = scanEndExclusive,
                ignoreCase = false,
            )
            if (openingEnd < 0) return INCOMPLETE_NOISE_WRAPPER

            val closingTag = "</$tag>"
            val closingStart = text.indexOfBounded(
                needle = closingTag,
                startIndex = openingEnd + 1,
                endExclusive = scanEndExclusive,
                ignoreCase = true,
            )
            if (closingStart < 0) return INCOMPLETE_NOISE_WRAPPER
            return closingStart + closingTag.length
        }
        return null
    }

    private fun String.indexOfBounded(
        needle: String,
        startIndex: Int,
        endExclusive: Int,
        ignoreCase: Boolean,
    ): Int {
        val lastStart = endExclusive - needle.length
        var candidate = startIndex
        while (candidate <= lastStart) {
            if (regionMatches(candidate, needle, 0, needle.length, ignoreCase = ignoreCase)) {
                return candidate
            }
            candidate += 1
        }
        return -1
    }

    private const val INCOMPLETE_NOISE_WRAPPER: Int = -1
}
