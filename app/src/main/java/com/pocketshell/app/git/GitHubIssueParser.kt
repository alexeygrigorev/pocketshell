package com.pocketshell.app.git

import org.json.JSONArray
import org.json.JSONException

/**
 * Pure parser for `gh issue list --json number,title,state,labels,updatedAt`
 * output — issue #649 (epic #644 slice 5).
 *
 * `gh issue list --json` already emits clean JSON (a top-level array of issue
 * objects), so unlike the `git log` parse there are no control delimiters: we
 * parse the JSON directly. Pure and side-effect-free so it can be unit-tested
 * without a live SSH session.
 *
 * The shape is, e.g.:
 *
 * ```json
 * [
 *   {
 *     "number": 649,
 *     "title": "view GitHub issues in-app",
 *     "state": "OPEN",
 *     "labels": [{"name": "enhancement", "color": "a2eeef"}],
 *     "updatedAt": "2026-06-09T10:11:12Z"
 *   }
 * ]
 * ```
 *
 * Robustness: malformed/empty/non-array output yields an empty list rather than
 * throwing, and an individual entry missing a usable `number` is skipped so one
 * bad row never drops the whole listing.
 */
object GitHubIssueParser {

    /** Parse the raw `gh issue list --json` stdout into [GitHubIssue] rows. */
    fun parse(raw: String): List<GitHubIssue> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = try {
            JSONArray(trimmed)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<GitHubIssue>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // `optInt` returns 0 on a missing/non-int field; gh issue numbers are
            // always >= 1, so 0 signals an unusable row we skip.
            val number = obj.optInt("number", 0)
            if (number <= 0) continue
            val title = obj.optString("title", "").trim()
            val state = GitHubIssueState.fromRaw(obj.optString("state", ""))
            val labels = parseLabels(obj.optJSONArray("labels"))
            val updatedAt = obj.optString("updatedAt", "").trim().ifBlank { null }
            out += GitHubIssue(
                number = number,
                title = title,
                state = state,
                labels = labels,
                updatedAt = updatedAt,
            )
        }
        return out
    }

    /**
     * Pull the `name` string out of each `{ "name": ..., "color": ... }` label
     * object. Blank / missing names are dropped. Accepts a null array (the
     * `labels` field absent) as an empty list.
     */
    private fun parseLabels(labels: JSONArray?): List<String> {
        if (labels == null || labels.length() == 0) return emptyList()
        val out = ArrayList<String>(labels.length())
        for (i in 0 until labels.length()) {
            val name = labels.optJSONObject(i)?.optString("name", "")?.trim()
            if (!name.isNullOrEmpty()) out += name
        }
        return out
    }
}
