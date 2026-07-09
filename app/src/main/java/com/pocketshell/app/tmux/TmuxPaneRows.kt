package com.pocketshell.app.tmux

/**
 * Parse one row from `list-panes -F ...` output into a
 * [TmuxSessionViewModel.ParsedPane]. Returns null if the row is malformed — we
 * tolerate a trailing blank line or a tmux version that surfaces fewer fields
 * than the format string requested.
 */
internal fun parsePaneRow(line: String): TmuxSessionViewModel.ParsedPane? {
    val parts = if (LIST_PANES_FIELD_SEPARATOR in line) {
        line.split(LIST_PANES_FIELD_SEPARATOR)
    } else {
        line.split('\t')
    }
    if (parts.size < 5) return null
    val paneId = parts[0].takeIf { it.startsWith("%") } ?: return null
    val windowId = parts[1].takeIf { it.startsWith("@") } ?: return null
    val hasWindowIndex = parts.getOrNull(2)?.startsWith("$") == false
    val windowIndex = if (hasWindowIndex) parts[2].trim().toIntOrNull() else null
    val sessionIdIndex = if (hasWindowIndex) 3 else 2
    val sessionId = parts.getOrNull(sessionIdIndex)?.takeIf { it.startsWith("$") } ?: return null
    val hasSessionName = parts.size >= sessionIdIndex + 4
    val sessionNameIndex = sessionIdIndex + 1
    val titleIndex = if (hasSessionName) sessionIdIndex + 2 else sessionIdIndex + 1
    val paneIndexIndex = if (hasSessionName) sessionIdIndex + 3 else sessionIdIndex + 2
    val sessionName = if (hasSessionName) parts[sessionNameIndex] else ""
    val title = parts.getOrNull(titleIndex).orEmpty()
    val paneIndex = parts.getOrNull(paneIndexIndex)?.trim()?.toIntOrNull() ?: 0
    return TmuxSessionViewModel.ParsedPane(
        paneId = paneId,
        windowId = windowId,
        windowIndex = windowIndex,
        sessionId = sessionId,
        title = title,
        paneIndex = paneIndex,
        cwd = parts.getOrNull(paneIndexIndex + 1).orEmpty(),
        currentCommand = parts.getOrNull(paneIndexIndex + 2).orEmpty(),
        // Issue #186: `#{pane_tty}` scopes the recorded-source process
        // scan to this pane. Older tmux versions that omit the field
        // simply return empty, in which case per-pane source resolution
        // skips this pane rather than fall back to a host-wide scan.
        paneTty = parts.getOrNull(paneIndexIndex + 3).orEmpty(),
        inCopyMode = parseTmuxBoolean(parts.getOrNull(paneIndexIndex + 4)),
        // Epic #821 slice A2: `#{pane_pid}` is the LAST field. Older tmux
        // (or unit tests on the legacy format) omit it -> 0, and the
        // foreign-session kind guess simply skips the pane.
        panePid = parts.getOrNull(paneIndexIndex + 5)?.trim()?.toLongOrNull() ?: 0L,
        sessionName = sessionName,
    )
}

private fun parseTmuxBoolean(raw: String?): Boolean =
    raw == "1" || raw.equals("true", ignoreCase = true)
