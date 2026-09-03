package com.pocketshell.next.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Every string the usage panel paints, derived from a record and an injected
 * clock (rewrite task P-5, ported from the pre-rewrite `usage/UsageFormat.kt`).
 *
 * Nothing here reads the wall clock or the default zone on its own — `now` and
 * `zoneId` are parameters — so the whole file is testable on the plain JVM and
 * a rendered countdown means exactly what it says.
 *
 * What did NOT come across: the `usageProvenanceLabel` / cached-capture
 * provenance family. Those described the server-side `usage --capture` cache
 * ("last captured HH:mm · refreshing…", "couldn't refresh — showing cached
 * from HH:mm"), and the rewrite deletes that cache path entirely. What is left
 * is one honest [usageSyncLabel]: syncing, or the time of the fetch on screen.
 */
internal fun statusLabel(record: UsageProviderRecord): String =
    usageProviderStatusUi(record).label

internal const val USAGE_DATA_UNAVAILABLE: String = "Usage data unavailable"
internal const val REFRESH_USAGE_FAILED: String = "Refresh usage failed"
internal const val USAGE_AUTH_SETUP_REQUIRED: String = "Login required"
internal const val CLAUDE_USAGE_AUTH_SETUP_MESSAGE: String =
    "Claude login needed on this host. " +
        "Open Claude Code on the host and sign in, then refresh usage."
internal const val CODEX_USAGE_AUTH_SETUP_MESSAGE: String =
    "Codex login needed on this host. " +
        "Run `codex login` in the host shell, then refresh usage."
private const val PROVIDER_USAGE_AUTH_SETUP_MESSAGE: String =
    "Provider login needed on this host. " +
        "Sign in with the provider CLI on the host, then refresh usage."

/**
 * What the panel says when a host answers but has no `pocketshell` binary.
 * The pre-rewrite copy came from `PocketshellCommand.NOT_INSTALLED_HINT`; that
 * class belonged to the deleted app module and had a PATH-probing resolver
 * attached to it, which app2 deliberately does not carry (its host-CLI calls
 * are plain `pocketshell …`, same as `HostCliClient`).
 */
internal const val POCKETSHELL_NOT_INSTALLED_HINT: String =
    "Install it on the host with `uv tool install pocketshell`, then refresh usage."

internal fun usageProviderStateDescription(
    record: UsageProviderRecord,
    state: UsageThresholdState = record.thresholdState(),
): String = usageProviderStatusUi(record, state).description

internal data class UsageProviderStatusUi(
    val label: String,
    val description: String,
    val needsAuthSetup: Boolean,
)

internal fun usageProviderStatusUi(
    record: UsageProviderRecord,
    state: UsageThresholdState = record.thresholdState(),
): UsageProviderStatusUi {
    val needsAuthSetup = usageAuthSetupMessageForDisplay(record.lastError) != null
    val label = when {
        needsAuthSetup -> USAGE_AUTH_SETUP_REQUIRED
        state == UsageThresholdState.Exceeded -> "Exceeded"
        record.status == UsageStatus.Warn || record.isNearLimit -> "Warn"
        record.status == UsageStatus.Ok -> "OK"
        record.status == UsageStatus.Unsupported -> "Unsupported"
        record.status == UsageStatus.Error -> USAGE_DATA_UNAVAILABLE
        else -> record.rawStatus.replaceFirstChar { it.uppercase() }
    }
    val description = when {
        needsAuthSetup -> USAGE_AUTH_SETUP_REQUIRED
        record.status == UsageStatus.Error -> USAGE_DATA_UNAVAILABLE
        record.status == UsageStatus.Unsupported -> "Unsupported"
        state.warrantsWarning -> thresholdRowDescription(state)
        else -> "OK"
    }
    return UsageProviderStatusUi(
        label = label,
        description = description,
        needsAuthSetup = needsAuthSetup,
    )
}

internal fun usageTelemetryMessageForDisplay(message: String?): String? {
    val trimmed = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = trimmed.lowercase(Locale.US)
    return when {
        lower.startsWith(CLAUDE_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) ||
            lower.startsWith(CODEX_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) ||
            lower.startsWith(PROVIDER_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) ||
            lower.startsWith(REFRESH_USAGE_FAILED.lowercase(Locale.US)) -> trimmed

        lower.contains("codex") &&
            (
                lower.contains("no auth token") ||
                    lower.contains("no-auth-token") ||
                    lower.contains("codex login") ||
                    lower.contains("authentication") ||
                    lower.contains("login required")
                ) -> CODEX_USAGE_AUTH_SETUP_MESSAGE

        lower.contains("claude") &&
            (
                lower.contains("authentication " + "failed") ||
                    lower.contains("claude " + "/login") ||
                    lower.contains("run `claude") ||
                    lower.contains("run claude") ||
                    lower.contains("login")
                ) -> CLAUDE_USAGE_AUTH_SETUP_MESSAGE

        lower.contains("http error 401") ||
            lower.contains("unauthorized") ||
            lower == "no-credentials" ||
            lower == "no credentials" -> PROVIDER_USAGE_AUTH_SETUP_MESSAGE

        lower.startsWith(USAGE_DATA_UNAVAILABLE.lowercase(Locale.US)) -> trimmed
        else -> trimmed
    }
}

internal fun usageAuthSetupMessageForDisplay(message: String?): String? {
    val display = usageTelemetryMessageForDisplay(message) ?: return null
    val lower = display.lowercase(Locale.US)
    return when {
        lower.startsWith(CLAUDE_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) -> display
        lower.startsWith(CODEX_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) -> display
        lower.startsWith(PROVIDER_USAGE_AUTH_SETUP_MESSAGE.lowercase(Locale.US)) -> display
        else -> null
    }
}

internal fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}%" else String.format(Locale.US, "%.1f%%", value)

internal fun formatPercentUsed(value: Double): String = "${formatPercent(value)} used"

internal fun formatWindowFoot(
    window: UsageWindow,
    now: Instant,
    blockReason: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val reset = "resets ${formatResetRelative(now, window.resetAt, zoneId)}"
    return listOfNotNull(reset, blockReason?.let(::quotaMessageForDisplay)).joinToString(" · ")
}

internal fun blockReasonForWindow(record: UsageProviderRecord, window: UsageWindow): String? {
    val reason = record.blockReason?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (record.windows.size <= 1) return reason
    return when (quotaReasonScope(reason)) {
        QuotaReasonScope.ShortTerm -> reason.takeIf { window.isShortTermUsageWindow() }
        QuotaReasonScope.LongTerm -> reason.takeIf { window.isLongTermUsageWindow() }
        QuotaReasonScope.Unknown -> reason.takeIf { window == record.mostConstrainedWindow }
    }
}

internal fun quotaMessageForDisplay(reason: String): String {
    val trimmed = reason.trim()
    if (trimmed.isEmpty()) return trimmed
    val lower = trimmed.lowercase(Locale.US)
    return when {
        quotaReasonScope(trimmed) == QuotaReasonScope.LongTerm &&
            (lower.contains("codex") || lower.contains("quota")) -> "Weekly quota exceeded"

        lower.contains("quota") &&
            (
                lower.contains("exhausted") ||
                    lower.contains("exceeded") ||
                    lower.contains("reached")
                ) -> "Quota exceeded"

        else -> trimmed
    }
}

internal fun exceededUsageDescription(): String = "Quota exceeded"

private enum class QuotaReasonScope {
    ShortTerm,
    LongTerm,
    Unknown,
}

private fun quotaReasonScope(reason: String): QuotaReasonScope {
    val lower = reason.lowercase(Locale.US)
    return when {
        LONG_TERM_TOKENS.any { it in lower } -> QuotaReasonScope.LongTerm
        SHORT_TERM_TOKENS.any { it in lower } -> QuotaReasonScope.ShortTerm
        else -> QuotaReasonScope.Unknown
    }
}

private val LONG_TERM_TOKENS = listOf(
    "weekly", "week", "long_term", "long term", "7d", "seven_day", "seven day", "secondary",
)

private val SHORT_TERM_TOKENS = listOf(
    "short_term", "short term", "5h", "five_hour", "five hour", "primary",
)

private fun UsageWindow.isLongTermUsageWindow(): Boolean {
    val lower = name.lowercase(Locale.US)
    return LONG_TERM_TOKENS.any { it in lower }
}

private fun UsageWindow.isShortTermUsageWindow(): Boolean {
    val lower = name.lowercase(Locale.US)
    return SHORT_TERM_TOKENS.any { it in lower }
}

/**
 * Em dash placeholder shown when a window/provider has no `reset_at` (some
 * providers/windows never report one).
 */
internal const val RESET_PLACEHOLDER: String = "—"

/**
 * Human, relative "time until reset" for a single window.
 *
 * Buckets so providers compare at a glance:
 *  - under a minute  → "in <1m"
 *  - under a day     → "in 2h 15m" / "in 5m"
 *  - a day or more   → "in 1 day" / "in 3 days"
 *  - already past    → "now"
 *  - null `resetAt`  → [RESET_PLACEHOLDER]
 *
 * Past 24h the difference is counted in LOCAL CALENDAR DAYS, not raw seconds:
 * a reset ~28h out that lands on tomorrow's date must read "in 1 day" and not
 * overshoot the absolute date rendered beneath it (`ceil` on raw seconds was
 * the pre-rewrite bug — 1.16 days rounded up to 2).
 */
internal fun formatResetRelative(now: Instant, resetAt: Instant?, zoneId: ZoneId): String {
    if (resetAt == null) return RESET_PLACEHOLDER
    val seconds = resetAt.epochSecond - now.epochSecond
    if (seconds <= 0L) return "now"
    if (seconds < 60L) return "in <1m"
    if (seconds < SECONDS_PER_DAY) return "in " + compactDuration(seconds)
    val nowDate = now.atZone(zoneId).toLocalDate()
    val resetDate = resetAt.atZone(zoneId).toLocalDate()
    val days = max(1L, ChronoUnit.DAYS.between(nowDate, resetDate))
    return if (days == 1L) "in 1 day" else "in $days days"
}

/**
 * Absolute local date + time for the reset, the secondary line under the
 * relative string. Null `resetAt` → null (the caller omits the line).
 */
internal fun formatResetAbsolute(resetAt: Instant?, zoneId: ZoneId): String? {
    if (resetAt == null) return null
    return absoluteFormatter(zoneId).format(resetAt)
}

/**
 * Render-only reset-credit expiry copy.
 *
 * Deliberately does not call either reset formatter: credit expiry is not an
 * automatic quota reset and must never inherit reset vocabulary by convenience.
 */
internal data class CreditExpiryText(
    val primary: String,
    val absolute: String?,
)

internal fun formatCreditExpiry(
    now: Instant,
    expiresAt: Instant?,
    zoneId: ZoneId,
): CreditExpiryText {
    if (expiresAt == null) {
        return CreditExpiryText(primary = "Expiry unavailable", absolute = null)
    }
    val absolute = absoluteFormatter(zoneId).format(expiresAt)
    if (!expiresAt.isAfter(now)) {
        return CreditExpiryText(primary = "expired", absolute = absolute)
    }
    val seconds = expiresAt.epochSecond - now.epochSecond
    val relative = if (seconds < SECONDS_PER_DAY) {
        "in " + compactDuration(max(1L, seconds))
    } else {
        val days = max(
            1L,
            ChronoUnit.DAYS.between(
                now.atZone(zoneId).toLocalDate(),
                expiresAt.atZone(zoneId).toLocalDate(),
            ),
        )
        if (days == 1L) "in 1 day" else "in $days days"
    }
    return CreditExpiryText(primary = "expires $relative", absolute = absolute)
}

/**
 * The soonest (smallest non-null `resetAt`) across a provider's windows, for
 * the summary strip. Null when the provider reports no reset times at all.
 */
internal fun soonestReset(record: UsageProviderRecord): Instant? =
    record.windows.mapNotNull { it.resetAt }.minOrNull()

/** Local "HH:mm" clock for a fetch timestamp; [RESET_PLACEHOLDER] when unknown. */
internal fun formatClock(at: Instant?, zoneId: ZoneId): String {
    if (at == null) return RESET_PLACEHOLDER
    return DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zoneId).format(at)
}

/**
 * The panel's one provenance line.
 *
 * There is no cached tier any more, so this has exactly three things to say:
 * a fetch is running, a fetch has landed (and when), or nothing has been read
 * yet.
 */
internal fun usageSyncLabel(
    state: UsageScreenState,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (state.isRefreshing) return "Syncing…"
    val latest = state.hosts.mapNotNull { it.lastSyncedAt }.maxOrNull()
        ?: return "Not synced yet"
    return "Last sync ${formatClock(latest, zoneId)}"
}

private const val SECONDS_PER_DAY = 86_400L

private fun absoluteFormatter(zoneId: ZoneId): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.US).withZone(zoneId)

/** "2h 15m" / "3h" / "45m", rounding minutes UP so a countdown never shows 0. */
private fun compactDuration(seconds: Long): String {
    val totalMinutes = max(1L, ceil(seconds / 60.0).toLong())
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
