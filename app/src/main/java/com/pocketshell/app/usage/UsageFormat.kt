package com.pocketshell.app.usage

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
 * the wall clock. The sub-24h path is elapsed time. Past 24h we count
 * the difference in *local calendar days* (zone-aware, matching the #467
 * day-formatting pattern) so the relative string never overshoots how a
 * human reads the absolute date below it (issue #802): a reset ~28h out
 * that lands on tomorrow's date reads "in 1 day", not "in 2 days".
 * `ceil` on the raw seconds was the bug — 1.16 days rounded up to 2.
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
    // >= 24h: count whole local calendar days so the relative string
    // matches the absolute date shown beneath it. The seconds >= 86_400
    // branch guarantees the dates differ by at least one calendar day, so
    // this is always >= 1.
    val nowDate = now.atZone(zoneId).toLocalDate()
    val resetDate = resetAt.atZone(zoneId).toLocalDate()
    val days = max(1L, ChronoUnit.DAYS.between(nowDate, resetDate))
    return if (days == 1L) "in 1 day" else "in $days days"
}

/**
 * Absolute local date + time for the reset, shown as the secondary line
 * under the relative string. Null `resetAt` → null (caller omits the
 * line). Zone-aware via the injected [zoneId].
 *
 * Issue #1565: a short day-of-week prefix makes the reset date readable at
 * a glance alongside the relative "in N days" line.
 *
 * Format examples (en-US): "Thu Jun 4, 13:10", "Wed May 28, 09:00".
 */
internal fun formatResetAbsolute(resetAt: Instant?, zoneId: ZoneId): String? {
    if (resetAt == null) return null
    val formatter = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.US).withZone(zoneId)
    return formatter.format(resetAt)
}

/**
 * The soonest (smallest non-null `resetAt`) across a provider's windows,
 * for the host-list summary strip. Null when the provider reports no
 * reset times at all.
 */
internal fun soonestReset(record: UsageProviderRecord): Instant? =
    record.windows.mapNotNull { it.resetAt }.minOrNull()

/**
 * Issue #689: local "HH:mm" clock time for a capture timestamp, used in the
 * "last captured at <time>" / "showing cached from <time>" provenance labels.
 * Falls back to "—" when the capture time is unknown.
 */
internal fun formatCapturedClock(capturedAt: Instant?, zoneId: ZoneId): String {
    if (capturedAt == null) return RESET_PLACEHOLDER
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zoneId)
    return formatter.format(capturedAt)
}

/**
 * Issue #689: the screen-level provenance line for the usage meta row.
 *
 * - live refresh in progress over a cached value → "Last captured HH:mm · refreshing…"
 * - live refresh failed, cached value kept       → "Couldn't refresh — showing cached from HH:mm"
 * - fresh live data                              → "Last sync: host data"
 * - live refresh in progress, no cache yet       → "Syncing..."
 */
internal fun usageProvenanceLabel(
    state: UsageScreenState,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val capturedClock = formatCapturedClock(state.cachedAt, zoneId)
    return when {
        state.refreshFailedShowingCached ->
            "Couldn't refresh — showing cached from $capturedClock"
        state.showingCached && state.isRefreshing ->
            "Last captured $capturedClock · refreshing…"
        state.isRefreshing -> "Syncing..."
        else -> "Last sync: host data"
    }
}
