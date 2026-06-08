package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFormatTest {

    @Test
    fun formatResetTime_usesCountdownForSameDayReset() {
        assertEquals(
            "in 2h 34m",
            formatResetTime(
                now = Instant.parse("2026-05-21T11:49:00Z"),
                resetAt = Instant.parse("2026-05-21T14:23:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetTime_normalizesRoundedMinutes() {
        assertEquals(
            "in 2h",
            formatResetTime(
                now = Instant.parse("2026-05-21T00:00:00Z"),
                resetAt = Instant.parse("2026-05-21T01:59:59Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatPercentUsed_makesUsageExplicit() {
        assertEquals("65% used", formatPercentUsed(65.0))
        assertEquals("65.5% used", formatPercentUsed(65.5))
    }

    // --- Issue #501: relative "time until reset" across all buckets ---

    @Test
    fun formatResetRelative_underAMinuteReadsLessThanOneMinute() {
        assertEquals(
            "in <1m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T12:00:30Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_minutesOnly() {
        assertEquals(
            "in 15m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T12:15:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_hoursAndMinutes() {
        assertEquals(
            "in 2h 15m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T11:00:00Z"),
                resetAt = Instant.parse("2026-06-04T13:15:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_oneDaySingular() {
        // Exactly 24h away rounds up to a single day bucket.
        assertEquals(
            "in 1 day",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-05T12:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_multipleDaysPlural() {
        assertEquals(
            "in 7 days",
            formatResetRelative(
                now = Instant.parse("2026-05-21T11:49:00Z"),
                resetAt = Instant.parse("2026-05-28T09:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_pastResetReadsNow() {
        assertEquals(
            "now",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T11:59:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_nullReadsPlaceholder() {
        assertEquals(
            "—",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = null,
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetAbsolute_rendersLocalDateAndTime() {
        // 13:10 UTC is 15:10 in Berlin (CEST, +02:00) — proves zone-aware.
        assertEquals(
            "Jun 4, 15:10",
            formatResetAbsolute(
                resetAt = Instant.parse("2026-06-04T13:10:00Z"),
                zoneId = ZoneId.of("Europe/Berlin"),
            ),
        )
    }

    @Test
    fun formatAbsolute_nullReturnsNull() {
        assertEquals(
            null,
            formatResetAbsolute(resetAt = null, zoneId = ZoneId.of("UTC")),
        )
    }

    @Test
    fun soonestReset_picksEarliestNonNullWindow() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(
                UsageWindow("5h", 10.0, 100.0, "percent", Instant.parse("2026-06-04T15:00:00Z")),
                UsageWindow("7d", 20.0, 100.0, "percent", Instant.parse("2026-06-04T13:10:00Z")),
            ),
        )
        assertEquals(Instant.parse("2026-06-04T13:10:00Z"), soonestReset(record))
    }

    @Test
    fun soonestReset_nullWhenNoWindowReportsReset() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("weekly", 10.0, 100.0, "percent", null)),
        )
        assertEquals(null, soonestReset(record))
    }

    @Test
    fun formatWindowFoot_nullResetStillShowsPlaceholder() {
        val window = UsageWindow("5h", 10.0, 100.0, "percent", null)
        assertEquals(
            "resets —",
            formatWindowFoot(
                window = window,
                now = Instant.parse("2026-06-04T12:00:00Z"),
                blockReason = null,
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatWindowFoot_sanitizesCodexWeeklyQuotaMessage() {
        val window = UsageWindow("7d", 100.0, 100.0, "percent", null)

        assertEquals(
            "resets — · Weekly quota exceeded",
            formatWindowFoot(
                window = window,
                now = Instant.parse("2026-06-04T12:00:00Z"),
                blockReason = "codex quota exhausted (weekly window at 80%)",
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun blockReasonForWindow_scopesWeeklyReasonToLongTermWindowOnly() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "codex quota exhausted (weekly window at 80%)",
            windows = listOf(
                UsageWindow("5h", 12.0, 100.0, "percent", null),
                UsageWindow("7d", 100.0, 100.0, "percent", Instant.parse("2026-06-15T10:00:00Z")),
            ),
        )

        assertEquals(null, blockReasonForWindow(record, record.windows[0]))
        assertEquals(
            "codex quota exhausted (weekly window at 80%)",
            blockReasonForWindow(record, record.windows[1]),
        )
    }

    @Test
    fun statusLabel_promotesNearLimitToWarning() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("5h", 90.0, 100.0, "percent", null)),
        )

        assertEquals("Warn", statusLabel(record))
    }

    @Test
    fun exhaustedCodex_hasClearExceededLabels() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "Codex quota exhausted",
            windows = emptyList(),
        )

        assertEquals(UsageThresholdState.Exceeded, record.thresholdState())
        assertEquals("Exceeded", statusLabel(record))
        assertEquals("EXCEEDED", thresholdBadgeLabel(record.thresholdState()))
        assertEquals("Quota exceeded", thresholdRowDescription(record.thresholdState()))
        assertEquals(
            false,
            thresholdRowDescription(record.thresholdState()).contains("provider " + "blocked", ignoreCase = true),
        )
    }

    @Test
    fun dashboardRows_keepsBlockedCodexWithoutWindows() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "Codex quota exhausted",
            windows = emptyList(),
        )
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1L,
                    hostName = "agents",
                    records = listOf(record),
                    lastSyncedAt = Instant.parse("2026-06-07T12:00:00Z"),
                ),
            ),
        )

        val row = state.dashboardRows().single()

        assertEquals("Codex", row.provider)
        assertEquals(UsageStatus.Blocked, row.status)
        assertEquals(100.0, row.percent, 0.001)
        assertEquals(UsageThresholdState.Exceeded, row.thresholdState)
    }
}
