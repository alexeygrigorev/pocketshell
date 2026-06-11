package com.pocketshell.app.usage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * One reset event surfaced by the server-side detection (issue #690, built on
 * the merged #690 server slice).
 *
 * The server's hourly capture (`pocketshell usage --capture`) compares each
 * reading to the previous one and, when a provider's usage limits reset (the
 * meter jumped back toward baseline, or the stated reset deadline elapsed and
 * the window rolled), appends a reset event to `usage-reset-events.jsonl`. The
 * app reads those events with `pocketshell usage --reset-events`, which emits a
 * single `{"reset_events": [...]}` document.
 *
 * Each event is the parsed JSON shape produced by `usage_reset._reset_event`:
 *
 * ```json
 * {"type":"reset","provider":"codex","window":"short_term",
 *  "detected_at":"2026-06-11T12:00:00Z","detected_reset_at":"...",
 *  "stated_reset_at":"...","new_reset_at":"...","timing":"early",
 *  "minutes_early":15,"previous_percent_remaining":6.0,
 *  "current_percent_remaining":100.0,"signals":["recovery","window_rolled"],
 *  "reset_key":"codex|short_term|<new_reset_at>"}
 * ```
 *
 * The [resetKey] is the server-side de-dup identity (#619 don't-renotify): the
 * same reset keeps the same key across later hourly runs, so the app shows one
 * banner / one push per actual reset, not one per capture.
 */
public data class UsageResetEvent(
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
    /** How many minutes earlier than stated the reset was detected (when [timing] == "early"). */
    val minutesEarly: Int?,
    /** Stable de-dup identity (provider|window|new-reset-at). */
    val resetKey: String,
) {
    /** True when this reset was detected EARLIER than the provider's stated time. */
    public val isEarly: Boolean
        get() = timing == "early"
}

/**
 * Parses the `{"reset_events": [...]}` document emitted by
 * `pocketshell usage --reset-events`.
 *
 * Deliberately permissive: an empty / malformed document, a missing
 * `reset_events` array, or a row without a `reset_key` collapses to an empty
 * list (or that row is dropped) so a bad reading can never wedge the banner.
 * Events are returned in the log's natural order (oldest first); callers pick
 * the most recent for the banner.
 */
public object UsageResetEventsParser {

    public fun parse(stdout: String): List<UsageResetEvent> {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return emptyList()
        }
        val array: JSONArray = root.optJSONArray("reset_events") ?: return emptyList()
        val out = mutableListOf<UsageResetEvent>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseEvent(obj)?.let { out += it }
        }
        return out
    }

    private fun parseEvent(obj: JSONObject): UsageResetEvent? {
        val provider = obj.optString("provider").trim().ifBlank { null } ?: return null
        val resetKey = obj.optString("reset_key").trim().ifBlank { null } ?: return null
        return UsageResetEvent(
            provider = provider,
            window = obj.optString("window").trim().ifBlank { "" },
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
