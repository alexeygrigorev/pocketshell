package com.pocketshell.app.sessions

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class HostTmuxSessionListParser @Inject constructor() {
    fun parseTmuxctlList(stdout: String): List<HostTmuxSessionRow> =
        stdout.lineSequence()
            .mapNotNull(::parseTmuxctlListRow)
            .toList()

    fun parseTmuxListSessions(stdout: String): List<HostTmuxSessionRow> =
        stdout.lineSequence()
            .mapNotNull(::parseTmuxListSessionsRow)
            .toList()

    internal fun parseTmuxctlListRow(line: String): HostTmuxSessionRow? {
        if (line.isBlank()) return null
        val trimmed = line.trim()
        if (trimmed.startsWith("IDX ") || trimmed.startsWith("Join a session:") ||
            trimmed.startsWith("Create a new one:") || trimmed.startsWith("Use current folder:") ||
            trimmed.startsWith("Help:")
        ) {
            return null
        }
        // Issue #200: anchor on the deterministic trailing
        // `YYYY-MM-DD HH:MM:SS` timestamp rather than requiring 2+ spaces
        // between the session-name column and the timestamp column. On
        // hosts with long session names (e.g. `git-ai-shipping-labs-workshops-raw-guard`),
        // tmuxctl's printf layout overflows the column padding and emits
        // only a single space before the timestamp. The previous regex
        // (`\s{2,}` between name and date) silently dropped those rows,
        // showing the user a partial list instead of the full set of
        // tmux sessions present on the host.
        val timestampMatch = TRAILING_TIMESTAMP.find(line) ?: return null
        val timestampText = timestampMatch.groupValues[1]
        // Reject rows where the timestamp isn't the trailing content
        // (after optional whitespace): anything else past it would mean
        // the line isn't a session row and the "timestamp" matched
        // something embedded in a hint/help blurb.
        if (line.substring(timestampMatch.range.last + 1).isNotBlank()) return null

        val beforeTimestamp = line.substring(0, timestampMatch.range.first)
        val idxMatch = LEADING_IDX.find(beforeTimestamp) ?: return null
        val name = beforeTimestamp.substring(idxMatch.range.last + 1).trim()
        if (name.isEmpty()) return null

        val created = parseDisplayTimestamp(timestampText)
        return HostTmuxSessionRow(
            name = name,
            createdAt = created,
            lastActivity = created,
            attached = false,
        )
    }

    internal fun parseTmuxListSessionsRow(line: String): HostTmuxSessionRow? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val name = parts[0].trim()
        if (name.isEmpty()) return null
        return HostTmuxSessionRow(
            name = name,
            createdAt = parts[1].trim().toLongOrNull(),
            lastActivity = parts[2].trim().toLongOrNull(),
            attached = (parts[3].trim().toLongOrNull() ?: 0L) > 0L,
        )
    }

    private fun parseDisplayTimestamp(value: String): Long? =
        runCatching {
            LocalDateTime.parse(value, DISPLAY_TIMESTAMP)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond()
        }.getOrNull()

    private companion object {
        /**
         * Matches the trailing `YYYY-MM-DD HH:MM:SS` timestamp printed by
         * `tmuxctl list --by activity`. Used as a deterministic anchor so
         * the session-name column can absorb arbitrary intermediate
         * whitespace (including the single-space case when long names
         * overflow tmuxctl's printf padding). See issue #200.
         */
        val TRAILING_TIMESTAMP: Regex = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

        /** Matches the leading numeric IDX column on a tmuxctl row. */
        val LEADING_IDX: Regex = Regex("""^\s*\d+\s+""")

        val DISPLAY_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
