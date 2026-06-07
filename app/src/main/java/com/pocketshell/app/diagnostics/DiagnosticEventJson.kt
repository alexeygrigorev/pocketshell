package com.pocketshell.app.diagnostics

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

data class DiagnosticsEvent(
    val sequence: Long,
    val wallClockTime: Instant,
    val monotonicTimestampNanos: Long,
    val category: String,
    val name: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

internal object DiagnosticEventJson {
    fun encode(event: DiagnosticsEvent): String {
        val root = JSONObject()
            .put("sequence", event.sequence)
            .put("wallClockTime", DateTimeFormatter.ISO_INSTANT.format(event.wallClockTime))
            .put("monotonicTimestampNanos", event.monotonicTimestampNanos)
            .put("category", sanitizeToken(event.category))
            .put("name", sanitizeToken(event.name))
        val metadata = JSONObject()
        event.metadata.toSortedMap().forEach { (key, value) ->
            metadata.put(sanitizeKey(key), JSONObject.wrap(value))
        }
        root.put("metadata", metadata)
        return root.toString()
    }

    fun decode(line: String): DiagnosticsEvent? = runCatching {
        val root = JSONObject(line)
        val metadataJson = root.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            metadataJson.keys().forEach { key ->
                put(key, metadataJson.opt(key).takeUnless { it == JSONObject.NULL })
            }
        }
        DiagnosticsEvent(
            sequence = root.getLong("sequence"),
            wallClockTime = Instant.parse(root.getString("wallClockTime")),
            monotonicTimestampNanos = root.getLong("monotonicTimestampNanos"),
            category = root.getString("category"),
            name = root.getString("name"),
            metadata = metadata,
        )
    }.getOrNull()

    private fun sanitizeKey(value: String): String =
        value.trim()
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }
            .joinToString("")
            .ifBlank { "field" }
            .take(MAX_KEY_CHARS)

    private fun sanitizeToken(value: String): String =
        value.trim()
            .replace('\n', '_')
            .replace('\r', '_')
            .replace('\t', '_')
            .filterNot { it.isISOControl() }
            .ifBlank { "unknown" }
            .take(MAX_TOKEN_CHARS)

    private const val MAX_KEY_CHARS = 64
    private const val MAX_TOKEN_CHARS = 80
}

internal object DiagnosticRedactor {
    fun redact(fields: Map<String, Any?>): Map<String, Any?> =
        fields.mapKeys { (key, _) -> sanitizeMetadataKey(key) }
            .mapValues { (key, value) -> redactValue(key, value) }

    private fun redactValue(key: String, value: Any?): Any? {
        if (value == null) return null
        if (isSensitiveKey(key) || looksSensitive(value)) return REDACTED
        return when (value) {
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
            is CharArray -> mapOf("chars" to value.size)
            is ByteArray -> mapOf("bytes" to value.size)
            is Collection<*> -> mapOf("count" to value.size)
            is Map<*, *> -> mapOf("count" to value.size)
            is Throwable -> value.javaClass.simpleName
            else -> value.toString()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .filterNot { it.isISOControl() }
                .take(MAX_VALUE_CHARS)
        }
    }

    private fun sanitizeMetadataKey(key: String): String =
        key.trim()
            .ifBlank { "field" }
            .take(MAX_KEY_CHARS)

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        if (lower.endsWith("bytes") || lower.endsWith("count") || lower.endsWith("ms")) return false
        return SENSITIVE_KEY_MARKERS.any { marker -> lower.contains(marker) }
    }

    private fun looksSensitive(value: Any?): Boolean {
        val text = value?.toString() ?: return false
        if (text.length < 8) return false
        val lower = text.lowercase()
        return SENSITIVE_VALUE_MARKERS.any { marker -> lower.contains(marker) }
    }

    private const val MAX_KEY_CHARS = 64
    private const val MAX_VALUE_CHARS = 160
    private const val REDACTED = "[redacted]"

    private val SENSITIVE_KEY_MARKERS = listOf(
        "apikey",
        "api_key",
        "auth",
        "body",
        "command",
        "content",
        "cookie",
        "credential",
        "keypath",
        "message",
        "passphrase",
        "password",
        "privatekey",
        "prompt",
        "secret",
        "token",
        "uri",
    )

    private val SENSITIVE_VALUE_MARKERS = listOf(
        "authorization:",
        "bearer ",
        "github_pat_",
        "password=",
        "sk-",
        "-----begin ",
    )
}
