package com.pocketshell.core.usage

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Parser for the normalized JSON produced by `heru usage --json`.
 *
 * PocketShell accepts the documented array shape and a single-record object
 * because `heru` may emit either. Parsing stays app-credential-free: the app
 * only consumes JSON already fetched by a server-side command.
 */
public class HeruUsageJsonParser {

    @Throws(UsageParseException::class)
    public fun parse(input: String): List<UsageProviderRecord> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            when (trimmed.first()) {
                '[' -> parseArray(JSONArray(trimmed))
                '{' -> listOf(parseRecord(JSONObject(trimmed)))
                else -> throw UsageParseException("usage JSON must start with '[' or '{'")
            }
        } catch (e: UsageParseException) {
            throw e
        } catch (e: JSONException) {
            throw UsageParseException("invalid usage JSON: ${e.message}", e)
        }
    }

    private fun parseArray(array: JSONArray): List<UsageProviderRecord> {
        val records = mutableListOf<UsageProviderRecord>()
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            val obj = value as? JSONObject
                ?: throw UsageParseException("provider record at index $i is not an object")
            records += parseRecord(obj)
        }
        return records
    }

    private fun parseRecord(obj: JSONObject): UsageProviderRecord {
        val provider = obj.requiredString("provider")
        val rawStatus = obj.optString("status", "unknown").ifBlank { "unknown" }
        val windowsJson = if (obj.has("windows") && !obj.isNull("windows")) {
            obj.optJSONArray("windows")
                ?: throw UsageParseException("'windows' for $provider is not an array")
        } else {
            JSONArray()
        }
        val windows = mutableListOf<UsageWindow>()
        for (i in 0 until windowsJson.length()) {
            val windowObj = windowsJson.opt(i) as? JSONObject
                ?: throw UsageParseException("window at index $i for $provider is not an object")
            windows += parseWindow(provider = provider, obj = windowObj, index = i)
        }

        return UsageProviderRecord(
            provider = provider,
            status = parseStatus(rawStatus),
            rawStatus = rawStatus,
            blockReason = obj.optionalString("block_reason"),
            lastError = obj.optionalString("last_error") ?: obj.optionalString("error"),
            windows = windows,
        )
    }

    private fun parseWindow(provider: String, obj: JSONObject, index: Int): UsageWindow =
        UsageWindow(
            name = obj.requiredString("name"),
            used = obj.requiredNumber("used", provider, index),
            limit = obj.requiredNumber("limit", provider, index),
            unit = obj.optString("unit", "count").ifBlank { "count" },
            resetAt = obj.optionalInstant("reset_at"),
        )

    private fun parseStatus(raw: String): UsageStatus = when (raw.lowercase()) {
        "ok", "healthy", "available" -> UsageStatus.Ok
        "warn", "warning", "near_limit" -> UsageStatus.Warn
        "blocked", "limit_reached", "limited" -> UsageStatus.Blocked
        "error" -> UsageStatus.Error
        "unsupported" -> UsageStatus.Unsupported
        else -> UsageStatus.Unknown
    }
}

public class UsageParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun JSONObject.requiredString(name: String): String {
    val value = optionalString(name)
    if (value.isNullOrBlank()) throw UsageParseException("missing required string '$name'")
    return value
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().ifBlank { null }
}

private fun JSONObject.requiredNumber(name: String, provider: String, windowIndex: Int): Double {
    if (!has(name) || isNull(name)) {
        throw UsageParseException("missing '$name' for $provider window $windowIndex")
    }
    val value = opt(name)
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    } ?: throw UsageParseException("invalid '$name' for $provider window $windowIndex")
}

private fun JSONObject.optionalInstant(name: String): Instant? {
    val raw = optionalString(name) ?: return null
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        throw UsageParseException("invalid '$name': $raw", e)
    }
}
