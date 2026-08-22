package com.pocketshell.core.usage

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Parser for the per-provider NDJSON produced by `pocketshell usage --json`.
 *
 * `pocketshell usage` flattens quse's provider-keyed `--json` document into
 * newline-delimited JSON — ONE object per line per provider. The pinned PyPI
 * quse 0.0.14 wheel is the five-provider legacy `short_term` /
 * `long_term` producer; the host boundary translates it and this parser
 * consumes only the resulting canonical PocketShell wire shape. A separate
 * canonical producer contract may add providers such as OpenCode Go. The
 * parser does NOT re-derive windows / resets / percentages. Each record has
 * this shape:
 *
 * ```json
 * {
 *   "provider": "claude",
 *   "status": "ok",
 *   "windows": {
 *     "5h":      {"percent_remaining": 99.0, "reset_at": "2026-08-22T09:49:59Z", "rolling": false},
 *     "7d":      {"percent_remaining": 96.0, "reset_at": "2026-08-27T14:59:59Z"},
 *   },
 *   "block_reason": null,
 *   "error": null,
 *   "details": { ... ignored except documented Codex reset-credit fields ... }
 * }
 * ```
 *
 * The top-level `windows` map carries one entry per span; the KEY IS the
 * producer's window label (`5h`, `7d`, `weekly`, `monthly`, or another
 * provider-owned key) and is passed through verbatim. A span that does not
 * apply to the provider is omitted from rendering. Only the `5h` entry may carry a
 * `rolling: bool`, which has no counterpart in the PocketShell window model
 * and is deliberately ignored. `status` values include `ok`, `unsupported`,
 * `error`, and `limited` / `blocked`. When `status == "error"` the `error`
 * field carries a free-form string. The app ignores `details` except for
 * Codex `reset_credits_available`, `reset_credits`, and
 * `reset_credits_error`. Windows still come only from quse's own fields.
 *
 * STRICT / fail-loud (issue #1318): the parser expects the producer's exact
 * canonical schema.
 * Any malformed record — non-JSON line, missing `provider`, a `windows` that
 * is not an object, a null/non-object window entry, or a window with a
 * missing/non-numeric percentage — throws
 * [UsageParseException], which the caller surfaces as a whole-panel error.
 * There is no per-record skip-resilience, no old-schema alias fallback, and no
 * re-derivation: a schema mismatch fails visibly instead of silently rendering
 * a broken panel. Genuine RUNTIME states (SSH
 * failure, quse-missing / exit != 0 provider-error, empty `--cached`) are
 * handled by the caller, not here.
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

        return UsageProviderRecord(
            provider = provider,
            status = parseStatus(rawStatus),
            rawStatus = rawStatus,
            blockReason = obj.optionalString("block_reason"),
            lastError = actionableProviderError(provider, obj.optionalString("error")),
            windows = parseWindows(record = obj, provider = provider),
            resetCredits = parseResetCredits(record = obj, provider = provider),
        )
    }

    /**
     * Parse the one narrow issue #1789 exception to #1318's details-ignore
     * rule. This method never reads `details.windows` and never creates a
     * [UsageWindow], so credit expiry cannot become quota reset state.
     */
    private fun parseResetCredits(
        record: JSONObject,
        provider: String,
    ): UsageResetCredits? {
        if (!provider.equals("codex", ignoreCase = true)) return null
        if (!record.has("details") || record.isNull("details")) return null
        val details = record.opt("details") as? JSONObject
            ?: throw UsageParseException("'details' for codex is not an object")

        val hasCount = details.has("reset_credits_available")
        val hasCredits = details.has("reset_credits")
        val hasError = details.has("reset_credits_error")
        if (!hasCount && !hasCredits && !hasError) return null

        if (hasError && !details.isNull("reset_credits_error")) {
            val errorValue = details.opt("reset_credits_error")
            if (errorValue !is String || errorValue.isBlank()) {
                throw UsageParseException("invalid 'reset_credits_error' for codex")
            }
            return UsageResetCredits(
                availableCount = null,
                credits = emptyList(),
                unavailable = true,
            )
        }

        if (!hasCount || details.isNull("reset_credits_available") ||
            !hasCredits || details.isNull("reset_credits")
        ) {
            throw UsageParseException(
                "codex reset_credits_available and reset_credits must be present together",
            )
        }

        val availableCount = details.requiredNonNegativeInt("reset_credits_available")
        val rawCredits = details.opt("reset_credits") as? org.json.JSONArray
            ?: throw UsageParseException("'reset_credits' for codex is not an array")
        val availableCredits = buildList {
            for (index in 0 until rawCredits.length()) {
                val rawCredit = rawCredits.opt(index) as? JSONObject
                    ?: throw UsageParseException("codex reset_credits[$index] is not an object")
                val status = rawCredit.requiredExactString("status", "reset_credits[$index]")
                if (status != "available") continue
                add(
                    UsageResetCredit(
                        title = rawCredit.resetCreditTitle(),
                        expiresAt = rawCredit.optionalExpiryInstant(index),
                    ),
                )
            }
        }
        if (availableCount != availableCredits.size) {
            throw UsageParseException(
                "codex reset_credits_available=$availableCount does not match " +
                    "${availableCredits.size} exact-available reset_credits rows",
            )
        }
        return UsageResetCredits(
            availableCount = availableCount,
            credits = availableCredits,
            unavailable = false,
        )
    }

    /**
     * Parse the producer's canonical top-level `windows` map (issue #2274,
     * D22 hard-cut). Each KEY is the published window label (`5h`, `7d`,
     * `weekly`, `monthly`, …) and is passed through verbatim. Each value is
     * `{percent_remaining, reset_at[, rolling]}`; quse reports
     * `percent_remaining`, and the PocketShell model uses `used` / `limit` in
     * percent units, so `percent_remaining = R` maps to
     * `used = 100 - R, limit = 100, unit = "percent"`. The reset time comes
     * straight from the per-window `reset_at` (canonical ISO-8601 UTC).
     *
     * The per-window `rolling: bool` (only the `5h` entry carries it) has no
     * counterpart in the [UsageWindow] model and is deliberately ignored.
     *
     * The host producer requires either canonical `windows` or at least one
     * legacy `short_term` / `long_term` field, so a producer record missing
     * both never reaches this parser. For defensive compatibility, this parser
     * returns an empty list when the canonical map is absent / null. THROWS
     * fail-loud (issue #1318) when `windows` is present but not an object,
     * when an entry is present but not an object, or when `percent_remaining`
     * A non-null `reset_at` must be canonical ISO-8601; an absent or null
     * `reset_at` means that no reset time is available. A present window
     * object with a null `percent_remaining` is a valid non-applicable span
     * in a canonical producer record and is omitted from rendering; a present
     * non-object entry remains a schema error.
     * There is NO `short_term` / `long_term` alias fallback here: the host
     * producer owns that compatibility translation, so reading those fields
     * in the app would silently mask a producer/schema drift.
     */
    private fun parseWindows(
        record: JSONObject,
        provider: String,
    ): List<UsageWindow> {
        if (!record.has("windows") || record.isNull("windows")) return emptyList()
        val windowsObj = record.opt("windows") as? JSONObject
            ?: throw UsageParseException("'windows' for $provider is not an object")

        val windows = mutableListOf<UsageWindow>()
        for (key in windowsObj.keys()) {
            if (windowsObj.isNull(key)) {
                throw UsageParseException("'windows.$key' for $provider is not an object")
            }
            val obj = windowsObj.opt(key) as? JSONObject
                ?: throw UsageParseException("'windows.$key' for $provider is not an object")
            if (!obj.has("percent_remaining")) {
                throw UsageParseException(
                    "'windows.$key.percent_remaining' for $provider is missing",
                )
            }
            if (obj.isNull("percent_remaining")) continue
            val percentRemaining = obj.requiredNumber("percent_remaining", provider, key)
            val used = (100.0 - percentRemaining).coerceIn(0.0, 100.0)
            windows += UsageWindow(
                name = key,
                used = used,
                limit = 100.0,
                unit = "percent",
                resetAt = obj.optionalResetInstant(),
            )
        }
        return windows
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

private const val GROK_USAGE_AUTH_SETUP_MESSAGE =
    "Grok login needed on this host. " +
        "Sign in with `grok` on the host, then refresh usage."

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
        (
            provider.equals("grok", ignoreCase = true) ||
                provider.equals("grok-build", ignoreCase = true)
            ) &&
            (
                lower == "no-credentials" ||
                    lower == "no credentials" ||
                    lower == "no-auth-token" ||
                    lower == "no auth token" ||
                    lower.contains("auth.json")
            ) ->
            GROK_USAGE_AUTH_SETUP_MESSAGE
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

private fun JSONObject.requiredNonNegativeInt(name: String): Int {
    val value = opt(name)
    val number = value as? Number
        ?: throw UsageParseException("invalid '$name' for codex")
    val asDouble = number.toDouble()
    val asLong = number.toLong()
    if (!asDouble.isFinite() || asDouble != asLong.toDouble() || asLong !in 0..Int.MAX_VALUE) {
        throw UsageParseException("invalid '$name' for codex")
    }
    return asLong.toInt()
}

private fun JSONObject.requiredExactString(name: String, context: String): String {
    val value = opt(name)
    return value as? String
        ?: throw UsageParseException("invalid '$name' for codex $context")
}

private fun JSONObject.resetCreditTitle(): String {
    if (!has("title") || isNull("title")) return RESET_CREDIT_TITLE_FALLBACK
    val value = opt("title") as? String
        ?: throw UsageParseException("invalid reset-credit 'title' for codex")
    return value.trim()
        .ifBlank { RESET_CREDIT_TITLE_FALLBACK }
        .take(RESET_CREDIT_TITLE_MAX_CHARS)
}

private fun JSONObject.optionalExpiryInstant(index: Int): Instant? {
    if (!has("expires_at") || isNull("expires_at")) return null
    val raw = opt("expires_at") as? String
        ?: throw UsageParseException("invalid 'expires_at' for codex reset_credits[$index]")
    return try {
        Instant.parse(raw.trim())
    } catch (e: Exception) {
        throw UsageParseException(
            "invalid 'expires_at' for codex reset_credits[$index]: $raw",
            e,
        )
    }
}

private const val RESET_CREDIT_TITLE_FALLBACK = "Reset credit"
private const val RESET_CREDIT_TITLE_MAX_CHARS = 160

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
