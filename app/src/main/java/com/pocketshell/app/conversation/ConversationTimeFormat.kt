package com.pocketshell.app.conversation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Issue #474: compact, deterministic per-message timestamp rendering for
 * the Conversation view. The maintainer wants to know WHEN a message was
 * sent (e.g. when the agent reported it finished) without reading terminal
 * logs.
 *
 * Rendering follows the #467 day-grouping style:
 *
 *  - a message sent **today** shows just the local time, `HH:mm`
 *    (e.g. `14:32`) — the day is "now", so a date would be noise;
 *  - a message sent on an **earlier day** prefixes the date so the time is
 *    never ambiguous: `MMM d, HH:mm` within the current year (`Jun 2, 14:32`)
 *    and `MMM d yyyy, HH:mm` across a year boundary (`Dec 31 2025, 14:32`).
 *
 * Determinism: both [zone] and [today] are injected, so unit tests pin them
 * to a fixed clock/zone instead of calling `Instant.now()` /
 * `LocalDate.now()`. The single-arg production defaults resolve the system
 * zone and current date.
 */
internal object ConversationTimeFormat {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DATE_SAME_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val DATE_OTHER_YEAR = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)

    /**
     * Format [atMillis] (epoch millis from the parsed transcript entry) as
     * a compact label, or null when there is no timestamp to show. The
     * caller renders null as "no timestamp" rather than an empty chip.
     */
    fun format(
        atMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String? {
        if (atMillis == null) return null
        val instant = Instant.ofEpochMilli(atMillis)
        val zoned = instant.atZone(zone)
        val date = zoned.toLocalDate()
        val time = TIME.format(zoned)
        if (date == today) return time
        val datePart = if (date.year == today.year) {
            DATE_SAME_YEAR.format(zoned)
        } else {
            DATE_OTHER_YEAR.format(zoned)
        }
        return "$datePart, $time"
    }
}
