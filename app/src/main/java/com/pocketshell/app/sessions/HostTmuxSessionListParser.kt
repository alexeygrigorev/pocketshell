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
        val match = TMUXCTL_ROW.matchEntire(line) ?: return null
        val name = match.groupValues[2].trim()
        if (name.isEmpty()) return null
        val created = parseDisplayTimestamp(match.groupValues[3].trim())
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
        val TMUXCTL_ROW: Regex = Regex("""^\s*(\d+)\s+(.+?)\s{2,}(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*$""")
        val DISPLAY_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
