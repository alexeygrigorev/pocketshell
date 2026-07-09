package com.pocketshell.app.composer

/**
 * Pure draft-string insertion rules for external composer entry points.
 */
internal fun draftWithSeededPrompt(existingDraft: String, prompt: String): String? {
    val trimmed = prompt.trim()
    if (trimmed.isEmpty()) return null
    return if (existingDraft.isBlank()) {
        trimmed
    } else {
        existingDraft.trimEnd() + "\n" + trimmed
    }
}

/**
 * Mirrors [SlashCommandAutocomplete.insertCommandText] at String level.
 */
internal fun draftWithPrefilledEngineCommand(existingDraft: String, command: String): String? {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return null
    val tokenEnd = leadingSlashTokenEnd(existingDraft)
    val trailing = existingDraft.substring(tokenEnd)
    return trimmed + trailing
}

private fun leadingSlashTokenEnd(text: String): Int {
    if (!text.startsWith("/")) return 0
    return text.indexOfFirst { it.isWhitespace() }
        .let { if (it < 0) text.length else it }
}
