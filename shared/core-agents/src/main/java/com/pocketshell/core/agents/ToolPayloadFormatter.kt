package com.pocketshell.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable formatting for the EXPANDED tool-call input/output blocks
 * (#704 requirement #2).
 *
 * The collapsed one-liner is owned by [ToolCallSummary]; this formatter owns
 * the expanded detail body that the transcript shows when a tool row is tapped
 * open. Tool I/O arrives as opaque JSON strings from three different agent
 * parsers, and the raw form is unusable when it embeds multi-KB base64 image
 * blobs (a `Read` of a screenshot dumps
 * `[{"type":"image","source":{"type":"base64","data":"iVBOR…[multi-KB]"}}]`).
 *
 * Goals:
 * - Parse the JSON and present it readably (pretty-printed, file_path shown
 *   cleanly) instead of a single raw brace-soup line.
 * - NEVER emit a multi-KB base64 / binary blob. Summarize an image result as
 *   `[image]` (with size when known) and elide any other very long opaque
 *   string value as `[… N chars elided]`.
 *
 * Lives in `core-agents` so it stays JVM-unit-testable and shared across panes.
 * Intentionally tolerant of malformed input: non-JSON payloads pass through
 * with only the blanket base64-line guard applied so the UI never crashes.
 */
public object ToolPayloadFormatter {

    /** Opaque string values longer than this are elided in formatted output. */
    private const val MaxInlineStringLength: Int = 800

    /** A standalone token that is "obviously" a long base64/opaque blob. */
    private const val Base64BlobThreshold: Int = 256

    private val Base64LikeRegex = Regex("^[A-Za-z0-9+/=\\r\\n]+$")

    /**
     * Format a tool-call INPUT payload for the expanded view. Pretty-prints
     * JSON objects/arrays; passes non-JSON through (with base64 elision).
     */
    public fun formatInput(input: String): String = formatPayload(input)

    /**
     * Format a tool-call OUTPUT payload for the expanded view.
     *
     * Claude tool results are frequently a JSON array of content blocks
     * (`[{"type":"image",...}]`, `[{"type":"text","text":"..."}]`). We render
     * those as a compact, readable summary rather than dumping the structure —
     * crucially turning an image block into `[image]` instead of its base64.
     */
    public fun formatOutput(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return ""
        // Content-block array (the common Claude tool_result shape).
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull()
            if (array != null) {
                contentBlocksSummary(array)?.let { return it }
            }
        }
        // A bare object that is itself an image/source block.
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (obj != null) {
                imageBlockSummary(obj)?.let { return it }
            }
        }
        return formatPayload(output)
    }

    // --- shared payload formatting ---------------------------------------

    private fun formatPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        when {
            trimmed.startsWith("{") -> {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull()
                if (obj != null) return sanitizeValue(obj).let { stableToString(it, indent = 2) }
            }
            trimmed.startsWith("[") -> {
                val arr = runCatching { JSONArray(trimmed) }.getOrNull()
                if (arr != null) return sanitizeValue(arr).let { stableToString(it, indent = 2) }
            }
        }
        // Not JSON — still guard against a raw base64 blob being dumped.
        return elideOpaqueString(trimmed)
    }

    /**
     * Recursively rewrite a parsed JSON tree so that base64 / binary blobs are
     * replaced with a short placeholder before it is pretty-printed.
     */
    private fun sanitizeValue(value: Any?): Any = when (value) {
        is JSONObject -> {
            val out = JSONObject()
            // An object that is itself an image source -> collapse in place.
            val collapsed = imageBlockSummary(value)
            if (collapsed != null) {
                out.put("summary", collapsed)
            } else {
                for (key in value.keys()) {
                    val child = value.opt(key)
                    out.put(key, sanitizeScalarOrRecurse(key, child))
                }
            }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) {
                out.put(sanitizeValue(value.opt(i)))
            }
            out
        }
        is String -> elideOpaqueString(value)
        null -> JSONObject.NULL
        else -> value
    }

    private fun sanitizeScalarOrRecurse(key: String, child: Any?): Any = when (child) {
        is JSONObject, is JSONArray -> sanitizeValue(child)
        is String -> {
            // The `data` field of a base64 image source is the worst offender.
            if (key.equals("data", ignoreCase = true) && looksLikeBase64Blob(child)) {
                "[base64 data · ${child.length} chars]"
            } else {
                elideOpaqueString(child)
            }
        }
        null -> JSONObject.NULL
        else -> child
    }

    /** Replace a too-long opaque/base64 string with a short placeholder. */
    private fun elideOpaqueString(value: String): String {
        if (value.length <= MaxInlineStringLength) return value
        return if (looksLikeBase64Blob(value)) {
            "[base64 data · ${value.length} chars]"
        } else {
            value.take(MaxInlineStringLength).trimEnd() + " … [${value.length - MaxInlineStringLength} chars elided]"
        }
    }

    private fun looksLikeBase64Blob(value: String): Boolean {
        if (value.length < Base64BlobThreshold) return false
        return Base64LikeRegex.matches(value)
    }

    // --- content-block summaries -----------------------------------------

    /**
     * Summarize a Claude `tool_result` content-block array into compact text.
     * Returns null when the array isn't recognizable as content blocks so the
     * caller can fall back to generic JSON formatting.
     */
    private fun contentBlocksSummary(array: JSONArray): String? {
        if (array.length() == 0) return "[empty]"
        val lines = mutableListOf<String>()
        var recognized = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: return null
            when (obj.optString("type")) {
                "image" -> {
                    recognized = true
                    lines += imageBlockSummary(obj) ?: "[image]"
                }
                "text" -> {
                    recognized = true
                    val text = obj.optString("text").trim()
                    lines += elideOpaqueString(text)
                }
                else -> return null
            }
        }
        if (!recognized) return null
        return lines.joinToString("\n").trim()
    }

    /**
     * If [obj] is (or contains) an image block, return a compact `[image]`
     * summary with the media type / size when discoverable. Otherwise null.
     */
    private fun imageBlockSummary(obj: JSONObject): String? {
        val source = when {
            obj.optString("type") == "image" -> obj.optJSONObject("source") ?: obj
            obj.has("source") && obj.optJSONObject("source")?.has("data") == true ->
                obj.optJSONObject("source")
            obj.optString("type") == "base64" && obj.has("data") -> obj
            else -> null
        } ?: return null

        val data = source.optString("data", "")
        if (data.isEmpty() && !source.has("data")) return null
        val mediaType = source.optString("media_type").ifBlank {
            source.optString("mediaType")
        }
        val typeSuffix = if (mediaType.isNotBlank()) " $mediaType" else ""
        val sizeSuffix = if (data.isNotEmpty()) " · ${data.length} chars" else ""
        return "[image$typeSuffix$sizeSuffix]"
    }

    // --- deterministic pretty-printer ------------------------------------

    /**
     * `org.json` is available on both Android and the JVM test runtime, but its
     * `toString(indent)` iteration order is not guaranteed. Re-serialize with a
     * stable key order so renders and unit assertions are deterministic.
     */
    private fun stableToString(value: Any, indent: Int): String =
        buildString { appendValue(value, indent, 0) }

    private fun StringBuilder.appendValue(value: Any?, indent: Int, depth: Int) {
        when (value) {
            is JSONObject -> appendObject(value, indent, depth)
            is JSONArray -> appendArray(value, indent, depth)
            JSONObject.NULL, null -> append("null")
            is String -> append(quote(value))
            else -> append(value.toString())
        }
    }

    private fun StringBuilder.appendObject(obj: JSONObject, indent: Int, depth: Int) {
        val keys = obj.keys().asSequence().toList()
        if (keys.isEmpty()) {
            append("{}")
            return
        }
        append("{\n")
        val pad = " ".repeat(indent * (depth + 1))
        keys.forEachIndexed { i, key ->
            append(pad)
            append(quote(key))
            append(": ")
            appendValue(obj.opt(key), indent, depth + 1)
            if (i < keys.size - 1) append(",")
            append("\n")
        }
        append(" ".repeat(indent * depth))
        append("}")
    }

    private fun StringBuilder.appendArray(arr: JSONArray, indent: Int, depth: Int) {
        if (arr.length() == 0) {
            append("[]")
            return
        }
        append("[\n")
        val pad = " ".repeat(indent * (depth + 1))
        for (i in 0 until arr.length()) {
            append(pad)
            appendValue(arr.opt(i), indent, depth + 1)
            if (i < arr.length() - 1) append(",")
            append("\n")
        }
        append(" ".repeat(indent * depth))
        append("]")
    }

    private fun quote(text: String): String = JSONObject.quote(text)
}
