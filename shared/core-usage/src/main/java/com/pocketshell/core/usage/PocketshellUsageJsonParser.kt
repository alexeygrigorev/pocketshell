package com.pocketshell.core.usage

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Parser for the per-provider NDJSON produced by `pocketshell usage --json`.
 *
 * `pocketshell usage` flattens quse's provider-keyed `--json` document into
 * newline-delimited JSON — ONE object per line per provider. quse v0.0.9 is
 * the single source of truth for the unified schema (issue #1318, D22
 * hard-cut): the app reads quse's exact fields and does NOT re-derive windows
 * / resets / percentages. Each record has this fixed shape:
 *
 * ```json
 * {
 *   "provider": "claude",
 *   "status": "ok",
 *   "short_term": {"percent_remaining": 91.0, "reset_at": "2026-07-07T23:19:59Z", "window": "5h"},
 *   "long_term":  {"percent_remaining": 30.0, "reset_at": "2026-07-09T14:59:59Z", "window": "7d"},
 *   "block_reason": null,
 *   "error": null,
 *   "details": { ... IGNORED by the app ... }
 * }
 * ```
 *
 * Either `short_term` or `long_term` (or both) may be present / null. The
 * window label comes straight from `short_term.window` / `long_term.window`
 * (e.g. `5h`, `7d`, `weekly`, `monthly`, or `null` → the generic key name).
 * `status` values include `ok`, `unsupported`, `error`, and `limited` /
 * `blocked`. When `status == "error"` the `error` field carries a free-form
 * string. The app IGNORES `details` entirely — windows come from the unified
 * top-level fields.
 *
 * STRICT / fail-loud (issue #1318): the parser expects quse's exact schema.
 * Any malformed record — non-JSON line, missing `provider`, a `short_term` /
 * `long_term` that is not an object — throws [UsageParseException], which the
 * caller surfaces as a whole-panel error. There is no per-record
 * skip-resilience, no `details.windows` alias fallback, and no re-derivation:
 * a schema mismatch fails visibly instead of silently rendering a broken
 * panel. Genuine RUNTIME states (SSH failure, quse-missing / exit != 0
 * provider-error, empty `--cached`) are handled by the caller, not here.
 *
 * Parsing stays app-credential-free: the app only consumes JSON already
 * fetched by a server-side command.
 */
public class PocketshellUsageJsonParser {

    @Throws(UsageParseException::class)
    public fun parse(input: String): List<UsageProviderRecord> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        // `pocketshell usage --json` is newline-delimited JSON: exactly one
        // provider record per line. We parse each non-blank line strictly and
        // THROW on the first malformed line (fail-loud, issue #1318). No
        // per-record skip-resilience, no multi-line accumulation — pocketshell
        // emits compact single-line records, and any drift is a hard error.
        val records = mutableListOf<UsageProviderRecord>()
        for ((index, rawLine) in trimmed.lines().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val obj = try {
                JSONObject(line)
            } catch (e: JSONException) {
                throw UsageParseException(
                    "invalid usage JSON near line ${index + 1}: ${e.message}",
                    e,
                )
            }
            records += parseRecord(obj)
        }
        return records
    }

    private fun parseRecord(obj: JSONObject): UsageProviderRecord {
        val provider = obj.requiredString("provider")
        val rawStatus = obj.optString("status", "unknown").ifBlank { "unknown" }

        val windows = mutableListOf<UsageWindow>()
        parseWindow(record = obj, jsonKey = "short_term", provider = provider)?.let { windows += it }
        parseWindow(record = obj, jsonKey = "long_term", provider = provider)?.let { windows += it }

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
     * Convert a usage window object into a [UsageWindow]. quse reports
     * `percent_remaining`; the PocketShell model uses `used` / `limit` in
     * `percent` units, so `percent_remaining = R` maps to
     * `used = 100 - R, limit = 100, unit = "percent"`.
     *
     * The window NAME comes straight from quse's `window` field (`5h`, `7d`,
     * `weekly`, `monthly`, …); when quse carries no span (`window: null`) the
     * generic key name (`short_term` / `long_term`) is used so the UI's
     * `windowLabel` humanizes it. The reset time comes straight from the
     * `reset_at` field (canonical ISO-8601 UTC).
     *
     * Returns `null` when the field is absent / null on the record, or when
     * the window carries no `percent_remaining` (some providers expose only
     * one of short/long term). THROWS when the field is present but is not an
     * object, or when `percent_remaining` / `reset_at` are malformed
     * (fail-loud, issue #1318).
     */
    private fun parseWindow(
        record: JSONObject,
        jsonKey: String,
        provider: String,
    ): UsageWindow? {
        if (!record.has(jsonKey) || record.isNull(jsonKey)) return null
        val obj = record.opt(jsonKey) as? JSONObject
            ?: throw UsageParseException("'$jsonKey' for $provider is not an object")

        if (!obj.has("percent_remaining") || obj.isNull("percent_remaining")) {
            // The window object exists but carries no value (e.g. a provider
            // that only populates one range, or an error record with null
            // percent). Treat as absent — no renderable window.
            return null
        }
        val percentRemaining = obj.requiredNumber("percent_remaining", provider, jsonKey)
        val used = (100.0 - percentRemaining).coerceIn(0.0, 100.0)
        return UsageWindow(
            name = obj.optionalString("window") ?: jsonKey,
            used = used,
            limit = 100.0,
            unit = "percent",
            resetAt = obj.optionalResetInstant(),
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

private const val CLAUDE_USAGE_AUTH_SETUP_MESSAGE =
    "Claude login needed on this host. " +
        "Open Claude Code on the host and sign in, then refresh usage."

/**
 * Rewrite a provider `error` string into an actionable, human message. This is
 * genuine error-message UX (a legit runtime state, kept per issue #1318), NOT
 * schema re-derivation: it translates a couple of known auth failures into
 * "here is what to do" text so the panel shows "sign in on the host" instead
 * of a bare "HTTP Error 401". Idempotent — the rewritten messages do not
 * re-match these patterns.
 */
private fun actionableProviderError(provider: String, error: String?): String? {
    val text = error?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = text.lowercase()
    return when {
        provider.equals("claude", ignoreCase = true) &&
            (
                lower.contains("claude " + "/login") ||
                    lower.contains("run `claude") ||
                    lower.contains("run claude") ||
                    lower.contains("authentication " + "failed")
            ) ->
            CLAUDE_USAGE_AUTH_SETUP_MESSAGE
        provider.equals("claude", ignoreCase = true) &&
            (
                lower.contains("http error 401") ||
                    lower.contains("unauthorized") ||
                    lower == "no-credentials" ||
                    lower == "no credentials"
            ) ->
            CLAUDE_USAGE_AUTH_SETUP_MESSAGE
        provider.equals("codex", ignoreCase = true) &&
            (
                lower == "no auth token" ||
                    lower == "no-auth-token" ||
                    lower == "no credentials"
            ) ->
            "Codex login needed on this host. Run `codex login` in the host shell, then refresh usage."
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

/**
 * Read quse's canonical `reset_at` (ISO-8601 UTC, e.g. `2026-07-07T23:19:59Z`).
 * quse owns the timestamp format (issue #1318): the app parses that one field
 * directly — no `resets_at` / `next_reset_at` / `reset_after_seconds` alias
 * fallbacks. A malformed value THROWS (fail-loud). A numeric epoch-seconds
 * value is still accepted as a convenience, but a bare non-ISO string is a
 * hard error.
 */
private fun JSONObject.optionalResetInstant(): Instant? {
    if (!has("reset_at") || isNull("reset_at")) return null
    val value = opt("reset_at")
    if (value is Number) {
        return try {
            Instant.ofEpochSecond(value.toLong())
        } catch (e: Exception) {
            throw UsageParseException("invalid 'reset_at': $value", e)
        }
    }
    val raw = value?.toString()?.trim()?.ifBlank { return null } ?: return null
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        throw UsageParseException("invalid 'reset_at': $raw", e)
    }
}
