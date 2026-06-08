package com.pocketshell.app.session

import com.pocketshell.app.fileviewer.RemotePathResolver
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import com.pocketshell.core.terminal.selection.decodeLocalFileUriPath

internal sealed interface ConversationLinkAction {
    data class OpenFile(val path: String, val cwd: String?) : ConversationLinkAction
    data class BrowseDirectory(val startDir: String) : ConversationLinkAction
    data class OpenUrl(val url: String) : ConversationLinkAction
}

/**
 * Issue #583: map a detected conversation URL/path to the same destination
 * shape used by terminal/file-preview taps. File paths keep their literal text
 * plus cwd so FileViewer can apply [RemotePathResolver]; directories are
 * resolved before opening the browser because it expects a concrete start dir.
 */
internal fun conversationLinkAction(
    link: ConversationLink,
    cwd: String?,
): ConversationLinkAction = when (link.kind) {
    ConversationLinkKind.FILE -> ConversationLinkAction.OpenFile(
        path = link.text,
        cwd = cwdForDetectedFilePath(link.text, cwd),
    )
    ConversationLinkKind.DIRECTORY -> ConversationLinkAction.BrowseDirectory(
        startDir = RemotePathResolver.resolve(link.text, cwd),
    )
    ConversationLinkKind.URL -> ConversationLinkAction.OpenUrl(link.text)
}

/**
 * Terminal and conversation file-link detectors surface both cwd-relative
 * targets (`out/report.png`) and server-rooted targets (`/...`, `~/...`,
 * `file:///...`). Only the cwd-relative shape should carry the pane cwd into
 * FileViewer resolution. Passing cwd for rooted attachment paths is harmless
 * with today's resolver, but dropping it here keeps those links exact at the
 * routing boundary and prevents regressions like issue #609.
 */
internal fun cwdForDetectedFilePath(path: String, cwd: String?): String? {
    val usableCwd = cwd?.takeIf { it.isNotBlank() } ?: return null
    val trimmedPath = path.trim()
    if (trimmedPath.isEmpty()) return usableCwd
    if (RemotePathResolver.isAlreadyRooted(trimmedPath)) return null
    if (decodeLocalFileUriPath(trimmedPath) != null) return null
    return usableCwd
}
