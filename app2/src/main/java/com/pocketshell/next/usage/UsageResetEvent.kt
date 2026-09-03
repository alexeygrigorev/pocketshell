package com.pocketshell.next.usage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * One reset event surfaced by the host's server-side detection.
 *
 * The host's capture compares each usage reading to the previous one and, when
 * a provider's limits reset (the meter jumped back toward baseline, or the
 * stated deadline elapsed and the window rolled), appends an event to
 * `usage-reset-events.jsonl`. `pocketshell usage --reset-events` prints those
 * as one `{"reset_events": [...]}` document.
 *
 * [resetKey] is the server-side de-dup identity: the same reset keeps the same
 * key across later captures, so the banner shows once per actual reset rather
 * than once per read.
 */
data class UsageResetEvent(
    val provider: String,
    val window: String,
    /** When the capture that detected the reset ran. */
    val detectedAt: Instant?,
    /** The provider's previously-stated reset deadline, if any. */
    val statedResetAt: Instant?,
    /** The new window's reset deadline, if the provider reported one. */
    val newResetAt: Instant?,
    /** "early" when detected before the stated deadline, else "on_or_after_stated". */
    val timing: String?,
    /** Minutes earlier than stated the reset was detected (when [timing] is "early"). */
    val minutesEarly: Int?,
    /** Stable de-dup identity (provider|window|new-reset-at). */
    val resetKey: String,
) {
    /** True when this reset was detected EARLIER than the provider's stated time. */
    val isEarly: Boolean
        get() = timing == "early"
}

/**
 * Parses the `{"reset_events": [...]}` document.
 *
 * Deliberately permissive, unlike the strict usage-record parser: this feeds a
 * decorative "limits just reset" banner, not the numbers the panel is about. An
 * empty/malformed document, a missing array, a row with no `reset_key`, or a
 * host CLI that does not know `--reset-events` at all collapses to an empty
 * list, so a bad reading can never wedge the panel it sits on top of. Events
 * come back in the log's natural order (oldest first).
 */
object UsageResetEventsParser {

    fun parse(stdout: String): List<UsageResetEvent> {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return emptyList()
        }
        val array: JSONArray = root.optJSONArray("reset_events") ?: return emptyList()
        val out = mutableListOf<UsageResetEvent>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            parseEvent(obj)?.let { out += it }
        }
        return out
    }

    private fun parseEvent(obj: JSONObject): UsageResetEvent? {
        val provider = obj.optString("provider").trim().ifBlank { null } ?: return null
        val resetKey = obj.optString("reset_key").trim().ifBlank { null } ?: return null
        return UsageResetEvent(
            provider = provider,
            window = obj.optString("window").trim(),
            detectedAt = parseInstant(obj.optString("detected_at")),
            statedResetAt = parseInstant(obj.optString("stated_reset_at")),
            newResetAt = parseInstant(obj.optString("new_reset_at")),
            timing = obj.optString("timing").trim().ifBlank { null },
            minutesEarly = if (obj.has("minutes_early") && !obj.isNull("minutes_early")) {
                obj.optInt("minutes_early")
            } else {
                null
            },
            resetKey = resetKey,
        )
    }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "null") return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }
}
