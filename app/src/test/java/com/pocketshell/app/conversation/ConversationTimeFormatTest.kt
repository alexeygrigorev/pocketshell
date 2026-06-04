package com.pocketshell.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Issue #474: deterministic per-message timestamp formatting. The clock
 * ("today") and zone are injected so these assertions never depend on the
 * machine's wall clock.
 */
class ConversationTimeFormatTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun nullTimestampFormatsToNull() {
        assertNull(ConversationTimeFormat.format(atMillis = null, zone = utc, today = LocalDate.of(2026, 6, 4)))
    }

    @Test
    fun todayShowsTimeOnly() {
        val result = ConversationTimeFormat.format(
            atMillis = millis("2026-06-04T14:32:00Z"),
            zone = utc,
            today = LocalDate.of(2026, 6, 4),
        )
        assertEquals("14:32", result)
    }

    @Test
    fun earlierDaySameYearPrefixesMonthAndDay() {
        val result = ConversationTimeFormat.format(
            atMillis = millis("2026-06-02T09:05:00Z"),
            zone = utc,
            today = LocalDate.of(2026, 6, 4),
        )
        assertEquals("Jun 2, 09:05", result)
    }

    @Test
    fun earlierYearIncludesTheYear() {
        val result = ConversationTimeFormat.format(
            atMillis = millis("2025-12-31T23:10:00Z"),
            zone = utc,
            today = LocalDate.of(2026, 6, 4),
        )
        assertEquals("Dec 31 2025, 23:10", result)
    }

    @Test
    fun zoneShiftsTheLocalTimeAndDayBucket() {
        // 2026-06-04T01:30Z is still "yesterday" at UTC-2; the date prefix
        // must appear because the local day differs from `today`.
        val zoneMinus2 = ZoneOffset.ofHours(-2)
        val result = ConversationTimeFormat.format(
            atMillis = millis("2026-06-04T01:30:00Z"),
            zone = zoneMinus2,
            today = LocalDate.of(2026, 6, 4),
        )
        assertEquals("Jun 3, 23:30", result)
    }
}
