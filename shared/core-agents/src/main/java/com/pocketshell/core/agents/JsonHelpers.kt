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

internal fun JSONObject.timestampMillis(): Long? =
    longOrNull("timestamp_ms")
        ?: longOrNull("created_at_ms")
        ?: longOrNull("time_ms")
