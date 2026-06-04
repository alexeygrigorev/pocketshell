package com.pocketshell.core.agents

import org.json.JSONArray
import org.json.JSONObject

internal fun String.asJsonObjectOrNull(): JSONObject? =
    runCatching { JSONObject(this) }.getOrNull()

internal fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

internal fun JSONObject.longOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

internal fun JSONObject.objectOrNull(name: String): JSONObject? =
    opt(name) as? JSONObject

internal fun JSONObject.arrayOrNull(name: String): JSONArray? =
    opt(name) as? JSONArray

internal fun JSONArray.objects(): Sequence<JSONObject> = sequence {
    for (i in 0 until length()) {
        (opt(i) as? JSONObject)?.let { yield(it) }
    }
}

internal fun JSONArray.textParts(): String =
    (0 until length()).joinToString(separator = "\n") { index ->
        when (val value = opt(index)) {
            is String -> value
            is JSONObject -> value.stringOrNull("text")
                ?: value.stringOrNull("content")
                ?: value.stringOrNull("output")
                ?: ""
            else -> ""
        }
    }.trim()

internal fun Any?.stringValue(): String = when (this) {
    null -> ""
    JSONObject.NULL -> ""
    is String -> this
    is JSONArray -> textParts().ifBlank { toString(2) }
    is JSONObject -> stringOrNull("text")
        ?: stringOrNull("content")
        ?: stringOrNull("output")
        ?: toString(2)
    else -> toString()
}

/**
 * Issue #474: extract a per-entry timestamp in epoch-millis from an agent
 * JSONL object so the Conversation view can show WHEN each message was
 * sent.
 *
 * Two encodings appear in the wild:
 *
 *  - numeric epoch-millis fields (`timestamp_ms`, `created_at_ms`,
 *    `time_ms`) — used by the deterministic test fixtures and some
 *    envelopes; read directly; and
 *  - an ISO-8601 string field (`timestamp` / `created_at` / `createdAt`)
 *    — this is what **real** Claude Code and Codex transcripts emit, e.g.
 *    `"2026-05-22T10:00:01Z"` or `"2026-05-20T15:12:00.878Z"`. Before
 *    this change these were dropped, so live Claude/Codex turns had a
 *    null `atMillis` and no timestamp showed in the app.
 *
 * The numeric forms win when present (they are already in the unit the
 * model wants). The ISO string is parsed with [parseIsoMillis], which is
 * lenient about a missing zone (treated as UTC).
 */
internal fun JSONObject.timestampMillis(): Long? =
    longOrNull("timestamp_ms")
        ?: longOrNull("created_at_ms")
        ?: longOrNull("time_ms")
        ?: stringOrNull("timestamp")?.let(::parseIsoMillis)
        ?: stringOrNull("created_at")?.let(::parseIsoMillis)
        ?: stringOrNull("createdAt")?.let(::parseIsoMillis)

/**
 * Parse an ISO-8601 timestamp string to epoch-millis, or null when the
 * value is not a recognisable instant. Accepts:
 *
 *  - an offset/zoned instant (`2026-05-22T10:00:01Z`,
 *    `2026-05-22T12:00:01+02:00`), parsed exactly; and
 *  - a zone-less local date-time (`2026-05-22T10:00:01`), interpreted as
 *    UTC so a transcript line missing its zone still sorts and renders
 *    sensibly rather than being discarded.
 *
 * Malformed values return null and are simply treated as "no timestamp"
 * by callers — agent logs are allowed to be schema-drifty without
 * dropping the surrounding conversation.
 */
internal fun parseIsoMillis(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    runCatching {
        return java.time.Instant.parse(trimmed).toEpochMilli()
    }
    runCatching {
        return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
    }
    runCatching {
        return java.time.LocalDateTime.parse(trimmed)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
    }
    return null
}
