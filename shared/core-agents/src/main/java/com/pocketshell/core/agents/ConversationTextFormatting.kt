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
     * True when [text], once internal-protocol noise is stripped, has no
     * presentable content left. The renderer drops such entries entirely
     * instead of showing a styled-but-empty row.
     */
    public fun isOnlyInternalProtocolNoise(text: String): Boolean =
        text.isNotBlank() && stripInternalProtocolNoise(text).isBlank()
}
