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
 * Issue #842: extract a [ConversationImage] from a single `image` content
 * block (or the older Anthropic `source`-wrapped form). Returns null when the
 * object is not an image block or carries no usable reference.
 *
 * Shapes handled (the common ones across Claude Code / Codex / OpenCode):
 *  - `{"type":"image","path":"/abs/host/path.png"}` — a saved-on-host image;
 *  - `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}}`
 *    — Anthropic-style inline base64;
 *  - `{"type":"image","source":{"type":"path","path":"/abs/…"}}` /
 *    `{"type":"image_url","image_url":{"url":"https://…"}}` — a path/URL ref;
 *  - `{"type":"image","image_url":"https://…"}` / `{"type":"image","url":"…"}`.
 *
 * A `data:` URI in any url/path field is decomposed into its media-type + base64
 * payload so it renders inline rather than as an unfetchable "URL".
 */
internal fun JSONObject.imageBlockOrNull(): ConversationImage? {
    val type = stringOrNull("type")?.lowercase()
    val mime = stringOrNull("media_type")
        ?: stringOrNull("mimeType")
        ?: stringOrNull("mime")
        ?: objectOrNull("source")?.stringOrNull("media_type")
    val mimeIsImage = mime?.startsWith("image/", ignoreCase = true) == true
    val isImageType = type == "image" || type == "image_url" ||
        type == "input_image" || type == "output_image" ||
        // A generic `file`/`attachment` part counts as an image only when its
        // declared mime says so (OpenCode emits attachments as `file` parts).
        ((type == "file" || type == "attachment") && mimeIsImage)
    // Some agents wrap the image under a `source` object without a top-level
    // type; treat a `source.type == image/base64` the same way.
    val source = objectOrNull("source")
    val sourceType = source?.stringOrNull("type")?.lowercase()
    if (!isImageType && sourceType != "base64" && sourceType != "image" && !has("image_url") && !mimeIsImage) {
        return null
    }

    // Direct path field.
    val directPath = stringOrNull("path")
        ?: source?.stringOrNull("path")
        ?: stringOrNull("file_path")
        ?: stringOrNull("filename")
    // URL field (top-level, or under image_url which may itself be an object).
    val directUrl = stringOrNull("url")
        ?: (opt("image_url") as? String)
        ?: (opt("image_url") as? JSONObject)?.stringOrNull("url")
        ?: source?.stringOrNull("url")
    // Inline base64 (Anthropic `source.data`) + its media type.
    val base64 = source?.stringOrNull("data")
        ?: stringOrNull("data")
    val mediaType = mime

    // A `data:` URI hiding in path/url → split into media type + payload.
    val dataUri = (directUrl ?: directPath)?.takeIf { it.startsWith("data:", ignoreCase = true) }
    if (dataUri != null) {
        val (mt, payload) = parseDataUri(dataUri)
        if (!payload.isNullOrBlank()) {
            return ConversationImage(base64Data = payload, mediaType = mt ?: mediaType)
        }
    }

    val image = ConversationImage(
        path = directPath?.takeUnless { it.startsWith("data:", ignoreCase = true) }?.takeIf { it.isNotBlank() },
        url = directUrl?.takeUnless { it.startsWith("data:", ignoreCase = true) }?.takeIf { it.isNotBlank() },
        base64Data = base64?.takeIf { it.isNotBlank() },
        mediaType = mediaType,
    )
    return image.takeIf { !it.path.isNullOrBlank() || !it.url.isNullOrBlank() || !it.base64Data.isNullOrBlank() }
}

/** Split a `data:image/png;base64,AAAA` URI into (mediaType, base64Payload). */
private fun parseDataUri(uri: String): Pair<String?, String?> {
    val comma = uri.indexOf(',')
    if (comma < 0) return null to null
    val header = uri.substring(5, comma) // after "data:"
    val payload = uri.substring(comma + 1).takeIf { it.isNotBlank() }
    val mediaType = header.substringBefore(';').takeIf { it.isNotBlank() && it.contains('/') }
    val isBase64 = header.contains("base64", ignoreCase = true)
    return mediaType to (if (isBase64) payload else null)
}

/**
 * Issue #842: collect every [ConversationImage] from a content value — a single
 * image object, or an array mixing text and image blocks. Used by the parsers to
 * pull images out of message content and tool-result content alike.
 */
internal fun Any?.imageBlocks(): List<ConversationImage> = when (this) {
    is JSONObject -> listOfNotNull(imageBlockOrNull())
    is JSONArray -> objects().mapNotNull { it.imageBlockOrNull() }.toList()
    else -> emptyList()
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
