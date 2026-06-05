package com.pocketshell.app.sessions

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class HostTmuxSessionListParser @Inject constructor() {
    fun parsePocketshellSessionsList(stdout: String): List<HostTmuxSessionRow> =
        stdout.lineSequence()
            .mapNotNull(::parsePocketshellSessionsListRow)
            .toList()

    fun parseTmuxListSessions(stdout: String): List<HostTmuxSessionRow> =
        stdout.lineSequence()
            .mapNotNull(::parseTmuxListSessionsRow)
            .toList()

    internal fun parsePocketshellSessionsListRow(line: String): HostTmuxSessionRow? {
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
        // the underlying `tmuxctl list` printf layout (which
        // `pocketshell sessions list` proxies byte-for-byte) overflows the
        // column padding and emits only a single space before the
        // timestamp. The previous regex
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
        val fields = parseStructuredTmuxListSessionsFields(line)
            ?: parseFallbackTmuxListSessionsFields(line)
            ?: return null
        val name = fields.name.trim()
        if (name.isEmpty()) return null
        return HostTmuxSessionRow(
            name = name,
            createdAt = fields.createdAt?.trim()?.toLongOrNull(),
            lastActivity = fields.lastActivity?.trim()?.toLongOrNull(),
            attached = (fields.attached?.trim()?.toLongOrNull() ?: fields.fallbackAttachedCount) > 0L,
            // Issue #463: only the warm live-client query carries
            // `#{session_path}`; treat a blank path as "unknown" (null) so
            // the project switcher does not group every path-less session
            // under an empty-string bucket.
            path = fields.path?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseStructuredTmuxListSessionsFields(line: String): TmuxListSessionsFields? {
        for (separator in STRUCTURED_SEPARATORS) {
            // Issue #463: the warm live-client query appends
            // `#{session_path}` as a 5th column. Try the 5-field shape
            // first (so the session path is captured), then fall back to
            // the original 4-field shape that other producers still emit.
            parseStructuredFiveFields(line, separator)?.let { return it }
            val fields = line.splitFromRight(separator, expectedTailFields = 3) ?: continue
            if (fields[1].trim().toLongOrNull() == null || fields[2].trim().toLongOrNull() == null) {
                continue
            }
            return TmuxListSessionsFields(
                name = fields[0],
                createdAt = fields[1],
                lastActivity = fields[2],
                attached = fields[3],
            )
        }
        return null
    }

    private fun parseStructuredFiveFields(line: String, separator: String): TmuxListSessionsFields? {
        val fields = line.splitFromRight(separator, expectedTailFields = 4) ?: return null
        // created + activity must be numeric epoch seconds; attached is a
        // 0/1 count. The trailing field is the (possibly empty) session
        // path. If created/activity aren't numeric this isn't the 5-field
        // shape — let the 4-field path handle it.
        if (fields[1].trim().toLongOrNull() == null || fields[2].trim().toLongOrNull() == null) {
            return null
        }
        if (fields[3].trim().toLongOrNull() == null) {
            return null
        }
        return TmuxListSessionsFields(
            name = fields[0],
            createdAt = fields[1],
            lastActivity = fields[2],
            attached = fields[3],
            path = fields[4],
        )
    }

    private fun parseFallbackTmuxListSessionsFields(line: String): TmuxListSessionsFields? {
        val match = FALLBACK_TMUX_LIST_SESSIONS.matchEntire(line.trim()) ?: return null
        val name = match.groupValues[1]
        val attached = if (line.contains("(attached)", ignoreCase = true)) 1L else 0L
        return TmuxListSessionsFields(
            name = name,
            createdAt = null,
            lastActivity = null,
            attached = null,
            fallbackAttachedCount = attached,
        )
    }

    private fun String.splitFromRight(separator: String, expectedTailFields: Int): List<String>? {
        val parts = ArrayDeque<String>()
        var endExclusive = length
        repeat(expectedTailFields) {
            val separatorIndex = lastIndexOf(separator, startIndex = endExclusive - 1)
            if (separatorIndex < 0) return null
            parts.addFirst(substring(separatorIndex + separator.length, endExclusive))
            endExclusive = separatorIndex
        }
        parts.addFirst(substring(0, endExclusive))
        return parts.toList()
    }

    private fun parseDisplayTimestamp(value: String): Long? =
        runCatching {
            LocalDateTime.parse(value, DISPLAY_TIMESTAMP)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond()
        }.getOrNull()

    private data class TmuxListSessionsFields(
        val name: String,
        val createdAt: String?,
        val lastActivity: String?,
        val attached: String?,
        val fallbackAttachedCount: Long = 0L,
        // Issue #463: session working directory from `#{session_path}`,
        // only present in the warm live-client 5-field shape.
        val path: String? = null,
    )

    private companion object {
        /**
         * Matches the trailing `YYYY-MM-DD HH:MM:SS` timestamp printed by
         * `pocketshell sessions list --by activity` (which proxies
         * `tmuxctl list` byte-for-byte). Used as a deterministic anchor so
         * the session-name column can absorb arbitrary intermediate
         * whitespace (including the single-space case when long names
         * overflow the underlying printf padding). See issue #200.
         */
        val TRAILING_TIMESTAMP: Regex = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

        /**
         * Matches the leading numeric IDX column on a
         * `pocketshell sessions list` row.
         */
        val LEADING_IDX: Regex = Regex("""^\s*\d+\s+""")

        val DISPLAY_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val STRUCTURED_SEPARATORS: List<String> = listOf("\t", """\t""", "::")

        val FALLBACK_TMUX_LIST_SESSIONS: Regex = Regex("""^([^:]+):\s+.*$""")
    }
}
