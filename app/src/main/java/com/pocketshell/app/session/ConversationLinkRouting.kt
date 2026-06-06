package com.pocketshell.app.session

import com.pocketshell.app.fileviewer.RemotePathResolver
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind

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
        cwd = cwd?.takeIf { it.isNotBlank() },
    )
    ConversationLinkKind.DIRECTORY -> ConversationLinkAction.BrowseDirectory(
        startDir = RemotePathResolver.resolve(link.text, cwd),
    )
    ConversationLinkKind.URL -> ConversationLinkAction.OpenUrl(link.text)
}
