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
    val reset = window.resetAt?.let { "resets ${formatResetTime(now, it, zoneId)}" }
    return listOfNotNull(reset, blockReason).joinToString(" · ")
}

internal fun formatResetTime(now: Instant, resetAt: Instant, zoneId: ZoneId): String {
    val seconds = resetAt.epochSecond - now.epochSecond
    if (seconds in 0 until 86_400L) {
        val totalMinutes = max(1L, ceil(seconds / 60.0).toLong())
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0 && minutes > 0 -> "in ${hours}h ${minutes}m"
            hours > 0 -> "in ${hours}h"
            else -> "in ${minutes}m"
        }
    }
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(zoneId)
    return formatter.format(resetAt)
}
