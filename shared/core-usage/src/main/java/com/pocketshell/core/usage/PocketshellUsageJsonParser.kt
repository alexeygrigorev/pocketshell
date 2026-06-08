package com.pocketshell.core.usage

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Parser for the normalized JSON produced by `pocketshell usage --json`.
 *
 * `pocketshell usage --json` emits one JSON object per line (newline-delimited
 * JSON, also known as NDJSON). Each record has a fixed shape:
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
public class PocketshellUsageJsonParser {

    @Throws(UsageParseException::class)
    public fun parse(input: String): List<UsageProviderRecord> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        val records = mutableListOf<UsageProviderRecord>()
        // `pocketshell usage --json` is newline-delimited JSON (NDJSON): one
        // record per line. We also accept records that span multiple lines by
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
        val detailWindows = obj.optJSONObject("details")?.optJSONObject("windows")

        val windows = mutableListOf<UsageWindow>()
        val codexCompatible = provider.isCodexCompatibleProvider()
        parseWindow(
            record = obj,
            jsonKey = "short_term",
            windowName = "short_term",
            provider = provider,
            detailWindow = detailWindows?.optJSONObject(
                when {
                    codexCompatible -> "primary_window"
                    provider.equals("claude", ignoreCase = true) -> "five_hour"
                    else -> ""
                },
            ),
            preferDetailPercent = codexCompatible,
            preferDetailWindowMetadata = codexCompatible,
        )?.let { windows += it }
        parseWindow(
            record = obj,
            jsonKey = "long_term",
            windowName = "long_term",
            provider = provider,
            detailWindow = detailWindows?.optJSONObject(
                when {
                    codexCompatible -> "secondary_window"
                    provider.equals("claude", ignoreCase = true) -> "seven_day"
                    else -> ""
                },
            ),
            preferDetailPercent = codexCompatible,
            preferDetailWindowMetadata = codexCompatible,
        )?.let { windows += it }

        return UsageProviderRecord(
            provider = provider,
            status = parseStatus(rawStatus),
            rawStatus = rawStatus,
            blockReason = obj.optionalString("block_reason"),
            lastError = actionableProviderError(provider, obj.optionalString("error")),
            windows = windows,
        )
    }

    /**
     * Convert a usage window object into a [UsageWindow]. The
     * `pocketshell usage --json` payload reports `percent_remaining`,
     * while the PocketShell model uses
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
        detailWindow: JSONObject? = null,
        preferDetailPercent: Boolean = false,
        preferDetailWindowMetadata: Boolean = false,
    ): UsageWindow? {
        val obj = if (!record.has(jsonKey) || record.isNull(jsonKey)) {
            null
        } else {
            record.opt(jsonKey) as? JSONObject
            ?: throw UsageParseException("'$jsonKey' for $provider is not an object")
        }
        val detailPercentRemaining = detailWindow?.percentRemainingFromUsedPercent()
        val percentRemaining = when {
            preferDetailPercent && detailPercentRemaining != null -> detailPercentRemaining
            obj != null && obj.has("percent_remaining") && !obj.isNull("percent_remaining") ->
                obj.requiredNumber("percent_remaining", provider, jsonKey)
            detailPercentRemaining != null -> detailPercentRemaining
            else -> null
        }
        if (percentRemaining == null) {
            // Per-provider window may exist but carry no value (e.g.
            // copilot short_term is hardcoded but other providers may
            // omit it). Treat as absent.
            return null
        }
        val used = (100.0 - percentRemaining).coerceIn(0.0, 100.0)
        val detailWindowLabel = detailWindow?.windowLabelFromLimitSeconds()
        val topLevelWindowLabel = obj?.optionalString("window")
        return UsageWindow(
            name = if (preferDetailWindowMetadata) {
                detailWindowLabel ?: topLevelWindowLabel ?: windowName
            } else {
                topLevelWindowLabel ?: detailWindowLabel ?: windowName
            },
            used = used,
            limit = 100.0,
            unit = "percent",
            resetAt = if (preferDetailWindowMetadata) {
                detailWindow?.optionalInstant("reset_at") ?: obj?.optionalInstant("reset_at")
            } else {
                obj?.optionalInstant("reset_at") ?: detailWindow?.optionalInstant("reset_at")
            },
        )
    }

    private fun parseStatus(raw: String): UsageStatus = when (
        raw.lowercase()
            .replace('-', '_')
            .replace(' ', '_')
    ) {
        "ok", "healthy", "available" -> UsageStatus.Ok
        "warn", "warning", "near_limit" -> UsageStatus.Warn
        "blocked",
        "limit_reached",
        "limited",
        "exhausted",
        "exceeded",
        "exhausted_quota",
        "quota_exhausted",
        "quota_exceeded",
        "usage_exhausted",
        "usage_limit_reached",
        "rate_limited" -> UsageStatus.Blocked
        "error" -> UsageStatus.Error
        "unsupported" -> UsageStatus.Unsupported
        else -> UsageStatus.Unknown
    }
}

private val CODEX_COMPATIBLE_PROVIDERS = setOf(
    "codex",
    "openai",
    "openai-codex",
    "openai_codex",
    "chatgpt",
)

private fun String.isCodexCompatibleProvider(): Boolean =
    lowercase().replace(' ', '_') in CODEX_COMPATIBLE_PROVIDERS

private fun actionableProviderError(provider: String, error: String?): String? {
    val text = error?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = text.lowercase()
    return when {
        provider.equals("claude", ignoreCase = true) &&
            (
                lower.contains("http error 401") ||
                    lower.contains("unauthorized") ||
                    lower == "no-credentials" ||
                    lower == "no credentials"
            ) ->
            "Claude Code authentication failed on this host. Run `claude /login` in the host shell, then refresh usage."
        provider.equals("codex", ignoreCase = true) &&
            (
                lower == "no auth token" ||
                    lower == "no-auth-token" ||
                    lower == "no credentials"
            ) ->
            "Codex authentication is missing on this host. Run `codex login` in the host shell, then refresh usage."
        else -> text
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
    if (!has(name) || isNull(name)) return null
    val value = opt(name)
    if (value is Number) {
        return try {
            Instant.ofEpochSecond(value.toLong())
        } catch (e: Exception) {
            throw UsageParseException("invalid '$name': $value", e)
        }
    }
    val raw = value?.toString()?.trim()?.ifBlank { return null } ?: return null
    raw.toLongOrNull()?.let { epochSeconds ->
        return try {
            Instant.ofEpochSecond(epochSeconds)
        } catch (e: Exception) {
            throw UsageParseException("invalid '$name': $raw", e)
        }
    }
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        throw UsageParseException("invalid '$name': $raw", e)
    }
}

private fun JSONObject.percentRemainingFromUsedPercent(): Double? {
    if (!has("used_percent") || isNull("used_percent")) return null
    val value = opt("used_percent")
    val used = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    } ?: return null
    return (100.0 - used).coerceIn(0.0, 100.0)
}

private fun JSONObject.windowLabelFromLimitSeconds(): String? {
    if (!has("limit_window_seconds") || isNull("limit_window_seconds")) return null
    val seconds = when (val value = opt("limit_window_seconds")) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    } ?: return null
    if (seconds <= 0L) return null
    val units = listOf(
        24L * 60L * 60L to "d",
        60L * 60L to "h",
        60L to "m",
    )
    for ((unitSeconds, suffix) in units) {
        if (seconds >= unitSeconds && seconds % unitSeconds == 0L) {
            return "${seconds / unitSeconds}$suffix"
        }
    }
    return "${seconds}s"
}
