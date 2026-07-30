package com.pocketshell.app.session

import com.pocketshell.app.fileviewer.RemotePathResolver
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import com.pocketshell.core.terminal.selection.decodeLocalFileUriPath

internal sealed interface ConversationTapTarget {
    data class Url(val value: String) : ConversationTapTarget
    data class RemotePath(val value: String) : ConversationTapTarget
}

/**
 * Preserve URL routing while treating every syntactic path kind as only a
 * remote-path candidate. FILE vs DIRECTORY is deliberately absent: issue #1890
 * makes the remote probe the sole authority for that distinction.
 */
internal fun conversationTapTarget(link: ConversationLink): ConversationTapTarget =
    if (link.kind == ConversationLinkKind.URL) {
        ConversationTapTarget.Url(link.text)
    } else {
        ConversationTapTarget.RemotePath(link.text)
    }

/**
 * Terminal and conversation file-link detectors surface both cwd-relative
 * targets (`out/report.png`) and server-rooted targets (`/...`, `~/...`,
 * `file:///...`). Only the cwd-relative shape should carry the pane cwd into
 * FileViewer resolution. Passing cwd for rooted attachment paths is harmless
 * with today's resolver, but dropping it here keeps those links exact at the
 * routing boundary and prevents regressions like issue #609.
 *
 * Conversation path taps no longer call this helper: issue #1890 hard-cut the
 * parser's FILE/DIRECTORY guess from that routing boundary. Conversation paths
 * now resolve once and route from a bounded remote type probe; this helper
 * remains solely for terminal file-path taps, which still open FileViewer.
 */
internal fun cwdForDetectedFilePath(path: String, cwd: String?): String? {
    val usableCwd = cwd?.takeIf { it.isNotBlank() } ?: return null
    val trimmedPath = path.trim()
    if (trimmedPath.isEmpty()) return usableCwd
    if (RemotePathResolver.isAlreadyRooted(trimmedPath)) return null
    if (decodeLocalFileUriPath(trimmedPath) != null) return null
    return usableCwd
}
