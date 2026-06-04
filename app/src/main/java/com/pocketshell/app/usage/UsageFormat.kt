package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

internal fun statusLabel(record: UsageProviderRecord): String = when {
    record.isBlocked -> "Blocked"
    record.status == UsageStatus.Warn || record.isNearLimit -> "Warn"
    record.status == UsageStatus.Ok -> "OK"
    record.status == UsageStatus.Unsupported -> "Unsupported"
    record.status == UsageStatus.Error -> "Error"
    else -> record.rawStatus.replaceFirstChar { it.uppercase() }
}

internal fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}%" else String.format(Locale.US, "%.1f%%", value)

internal fun formatWindowFoot(
    window: UsageWindow,
    now: Instant,
    blockReason: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val reset = "resets ${formatResetRelative(now, window.resetAt, zoneId)}"
    return listOfNotNull(reset, blockReason).joinToString(" · ")
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
