package com.pocketshell.core.usage

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Parser for the normalized JSON produced by `quse --json`.
 *
 * `quse` emits one JSON object per line (newline-delimited JSON, also known
 * as NDJSON). Each record has a fixed shape:
 *
 * ```json
 * {
 *   "provider": "codex",
 *   "status": "ok",
 *   "short_term": {"percent_remaining": 77.0, "reset_at": "...", "window": null},
 *   "long_term":  {"percent_remaining": 88.0, "reset_at": "...", "window": null},
 *   "block_reason": null,
 *   "error": null,
 *   "details": { ... }
 * }
 * ```
 *
 * Either `short_term` or `long_term` (or both) may be present. `status`
 * values include `ok`, `unsupported`, `error`, and `limited` / `blocked`
 * for providers that have hit a quota wall. When `status == "error"` the
 * `error` field carries a free-form string.
 *
 * Parsing stays app-credential-free: the app only consumes JSON already
 * fetched by a server-side command.
 */
public class QuseUsageJsonParser {

    @Throws(UsageParseException::class)
    public fun parse(input: String): List<UsageProviderRecord> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        val records = mutableListOf<UsageProviderRecord>()
        // `quse --json` is newline-delimited JSON (NDJSON): one record per
        // line. We also accept records that span multiple lines by
        // accumulating until braces balance — this keeps the parser
        // tolerant of pretty-printed output from custom wrapper scripts
        // (per-host `usageCommandOverride`) without losing the
        // per-record error reporting the line index gives us.
        val buffer = StringBuilder()
        var depth = 0
        var inString = false
        var escape = false
        for ((index, rawLine) in trimmed.lines().withIndex()) {
            val line = rawLine
            if (buffer.isEmpty() && line.isBlank()) continue
            for (ch in line) {
                buffer.append(ch)
                if (escape) {
                    escape = false
                    continue
                }
                when (ch) {
                    '\\' -> if (inString) escape = true
                    '"' -> inString = !inString
                    '{' -> if (!inString) depth += 1
                    '}' -> if (!inString) depth -= 1
                }
            }
            if (buffer.isNotEmpty() && depth == 0 && !inString) {
                val record = buffer.toString().trim()
                buffer.setLength(0)
                if (record.isEmpty()) continue
                val obj = try {
                    JSONObject(record)
                } catch (e: JSONException) {
                    throw UsageParseException("invalid usage JSON near line ${index + 1}: ${e.message}", e)
                }
                records += parseRecord(obj)
            } else if (buffer.isNotEmpty()) {
                // Multi-line record: keep the newline so the next iteration
                // separates tokens correctly.
                buffer.append('\n')
            }
        }
        if (depth != 0 || inString) {
            throw UsageParseException("invalid usage JSON: unterminated record")
        }
        return records
    }

    private fun parseRecord(obj: JSONObject): UsageProviderRecord {
        val provider = obj.requiredString("provider")
        val rawStatus = obj.optString("status", "unknown").ifBlank { "unknown" }

        val windows = mutableListOf<UsageWindow>()
        parseWindow(obj, "short_term", "short_term", provider)?.let { windows += it }
        parseWindow(obj, "long_term", "long_term", provider)?.let { windows += it }

        return UsageProviderRecord(
            provider = provider,
            status = parseStatus(rawStatus),
            rawStatus = rawStatus,
            blockReason = obj.optionalString("block_reason"),
            lastError = obj.optionalString("error"),
            windows = windows,
        )
    }

    /**
     * Convert a quse window object into a [UsageWindow]. The quse JSON
     * reports `percent_remaining`, while the PocketShell model uses
     * `used` / `limit` in `percent` units; convert `percent_remaining = R`
     * to `used = 100 - R, limit = 100, unit = "percent"`.
     *
     * Returns `null` when the field is missing or null on the record
     * (some providers only expose one of short/long term).
     */
    private fun parseWindow(
        record: JSONObject,
        jsonKey: String,
        windowName: String,
        provider: String,
    ): UsageWindow? {
        if (!record.has(jsonKey) || record.isNull(jsonKey)) return null
        val obj = record.opt(jsonKey) as? JSONObject
            ?: throw UsageParseException("'$jsonKey' for $provider is not an object")
        if (!obj.has("percent_remaining") || obj.isNull("percent_remaining")) {
            // Per-provider window may exist but carry no value (e.g.
            // copilot short_term is hardcoded but other providers may
            // omit it). Treat as absent.
            return null
        }
        val percentRemaining = obj.requiredNumber("percent_remaining", provider, jsonKey)
        val used = (100.0 - percentRemaining).coerceIn(0.0, 100.0)
        return UsageWindow(
            name = windowName,
            used = used,
            limit = 100.0,
            unit = "percent",
            resetAt = obj.optionalInstant("reset_at"),
        )
    }

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

private fun JSONObject.requiredNumber(name: String, provider: String, windowKey: String): Double {
    if (!has(name) || isNull(name)) {
        throw UsageParseException("missing '$name' for $provider $windowKey")
    }
    val value = opt(name)
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    } ?: throw UsageParseException("invalid '$name' for $provider $windowKey")
}

private fun JSONObject.optionalInstant(name: String): Instant? {
    val raw = optionalString(name) ?: return null
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        throw UsageParseException("invalid '$name': $raw", e)
    }
}
