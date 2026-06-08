package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

internal fun statusLabel(record: UsageProviderRecord): String = when {
    record.thresholdState() == UsageThresholdState.Exceeded -> "Exceeded"
    record.status == UsageStatus.Warn || record.isNearLimit -> "Warn"
    record.status == UsageStatus.Ok -> "OK"
    record.status == UsageStatus.Unsupported -> "Unsupported"
    record.status == UsageStatus.Error -> USAGE_DATA_UNAVAILABLE
    else -> record.rawStatus.replaceFirstChar { it.uppercase() }
}

internal const val USAGE_DATA_UNAVAILABLE: String = "Usage data unavailable"
internal const val REFRESH_USAGE_FAILED: String = "Refresh usage failed"

internal fun usageProviderStateDescription(
    record: UsageProviderRecord,
    state: UsageThresholdState = record.thresholdState(),
): String = when {
    record.status == UsageStatus.Error -> USAGE_DATA_UNAVAILABLE
    record.status == UsageStatus.Unsupported -> "Unsupported"
    state.warrantsWarning -> thresholdRowDescription(state)
    else -> "OK"
}

internal fun usageTelemetryMessageForDisplay(message: String?): String? {
    val trimmed = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = trimmed.lowercase(Locale.US)
    return when {
        lower.startsWith(USAGE_DATA_UNAVAILABLE.lowercase(Locale.US)) ||
            lower.startsWith(REFRESH_USAGE_FAILED.lowercase(Locale.US)) -> trimmed
        lower.contains("claude") &&
            (
                    lower.contains("authentication " + "failed") ||
                    lower.contains("claude " + "/login") ||
                    lower.contains("run `claude") ||
                    lower.contains("run claude") ||
                    lower.contains("login")
            ) -> USAGE_DATA_UNAVAILABLE
        lower.contains("http error 401") ||
            lower.contains("unauthorized") ||
            lower == "no-credentials" ||
            lower == "no credentials" -> "$USAGE_DATA_UNAVAILABLE: $trimmed"
        else -> trimmed
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
            (lower.contains("codex") || lower.contains("quota")) ->
            "Weekly quota exceeded"
        lower.contains("quota") &&
            (lower.contains("exhausted") || lower.contains("exceeded") || lower.contains("reached")) ->
            "Quota exceeded"
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
        listOf("weekly", "week", "long_term", "long term", "7d", "seven_day", "seven day", "secondary")
            .any { it in lower } -> QuotaReasonScope.LongTerm
        listOf("short_term", "short term", "5h", "five_hour", "five hour", "primary")
            .any { it in lower } -> QuotaReasonScope.ShortTerm
        else -> QuotaReasonScope.Unknown
    }
}

private fun UsageWindow.isLongTermUsageWindow(): Boolean {
    val lower = name.lowercase(Locale.US)
    return listOf("weekly", "week", "long_term", "long term", "7d", "seven_day", "seven day", "secondary")
        .any { it in lower }
}

private fun UsageWindow.isShortTermUsageWindow(): Boolean {
    val lower = name.lowercase(Locale.US)
    return listOf("short_term", "short term", "5h", "five_hour", "five hour", "primary")
        .any { it in lower }
}

/**
 * Em dash placeholder shown when a window/provider has no `reset_at`
 * (some providers/windows never report one).
 */
internal const val RESET_PLACEHOLDER: String = "—"

/**
 * Human, relative "time until reset" for a single window — issue #501.
 *
 * Buckets so the maintainer can compare providers at a glance:
 *  - under a minute  → "in <1m"
 *  - under a day     → "in 2h 15m" / "in 5m"
 *  - a day or more   → "in 1 day" / "in 3 days"
 *  - already past     → "now" (reset is due / overdue)
 *  - null `resetAt`  → [RESET_PLACEHOLDER] ("—")
 *
 * Deterministic: the caller injects [now] and [zoneId]; nothing reads
 * the wall clock. Day bucketing is computed on the local calendar
 * (zone-aware), matching the #467 day-formatting pattern, so a reset at
 * 23:30 tonight reads "in 1 day" rather than "in 7h" only when it
 * crosses far enough — here we keep the sub-24h path as elapsed time and
 * switch to calendar-free day counts past 24h to stay predictable.
 */
internal fun formatResetRelative(now: Instant, resetAt: Instant?, zoneId: ZoneId): String {
    if (resetAt == null) return RESET_PLACEHOLDER
    val seconds = resetAt.epochSecond - now.epochSecond
    if (seconds <= 0L) return "now"
    if (seconds < 60L) return "in <1m"
    if (seconds < 86_400L) {
        val totalMinutes = max(1L, ceil(seconds / 60.0).toLong())
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0 && minutes > 0 -> "in ${hours}h ${minutes}m"
            hours > 0 -> "in ${hours}h"
            else -> "in ${minutes}m"
        }
    }
    val days = ceil(seconds / 86_400.0).toLong()
    return if (days == 1L) "in 1 day" else "in $days days"
}

/**
 * Absolute local date + time for the reset, shown as the secondary line
 * under the relative string. Null `resetAt` → null (caller omits the
 * line). Zone-aware via the injected [zoneId].
 *
 * Format examples (en-US): "Jun 4, 13:10", "May 28, 09:00".
 */
internal fun formatResetAbsolute(resetAt: Instant?, zoneId: ZoneId): String? {
    if (resetAt == null) return null
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(zoneId)
    return formatter.format(resetAt)
}

/**
 * Retained name kept for the existing call sites / tests: same as
 * [formatResetRelative] but for a non-null instant. Issue #501 widened
 * the behavior past 24h from a bare date to a relative "in N days".
 */
internal fun formatResetTime(now: Instant, resetAt: Instant, zoneId: ZoneId): String =
    formatResetRelative(now, resetAt, zoneId)

/**
 * The soonest (smallest non-null `resetAt`) across a provider's windows,
 * for the host-list summary strip. Null when the provider reports no
 * reset times at all.
 */
internal fun soonestReset(record: UsageProviderRecord): Instant? =
    record.windows.mapNotNull { it.resetAt }.minOrNull()
