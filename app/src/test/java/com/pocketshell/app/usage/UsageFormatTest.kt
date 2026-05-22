package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
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
    fun formatResetTime_usesDateForLaterReset() {
        assertEquals(
            "May 28",
            formatResetTime(
                now = Instant.parse("2026-05-21T11:49:00Z"),
                resetAt = Instant.parse("2026-05-28T09:00:00Z"),
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
    fun statusLabel_promotesNearLimitToWarning() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("5h", 90.0, 100.0, "percent", null)),
        )

        assertEquals("Warn", statusLabel(record))
    }
}
