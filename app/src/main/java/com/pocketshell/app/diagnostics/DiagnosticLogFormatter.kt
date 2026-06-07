package com.pocketshell.app.diagnostics

import java.time.Instant
import java.time.format.DateTimeFormatter

internal object DiagnosticLogFormatter {
    fun format(
        timestamp: Instant,
        category: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ): String = buildString {
        append(DateTimeFormatter.ISO_INSTANT.format(timestamp))
        append(" category=").append(sanitizeToken(category))
        append(" event=").append(sanitizeToken(event))
        fields.toSortedMap().forEach { (key, value) ->
            append(' ')
            append(sanitizeKey(key))
            append('=')
            append(sanitizeToken(value))
        }
    }

    private fun sanitizeKey(value: String): String =
        value.trim()
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }
            .joinToString("")
            .ifBlank { "field" }

    private fun sanitizeToken(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            else -> value.toString()
                .replace('\n', '_')
                .replace('\r', '_')
                .replace('\t', '_')
                .replace(' ', '_')
                .filterNot { it.isISOControl() }
                .take(MAX_VALUE_CHARS)
                .ifBlank { "empty" }
        }

    private const val MAX_VALUE_CHARS = 160
}
