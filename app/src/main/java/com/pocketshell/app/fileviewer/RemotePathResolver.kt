package com.pocketshell.app.fileviewer

import com.pocketshell.core.terminal.selection.PathNormalizer
import com.pocketshell.core.terminal.selection.decodeLocalFileUriPath

/**
 * Resolve a user-/agent-supplied remote path against the session's working
 * directory (issue #497).
 *
 * The agent says "look, there's a file at `out/report.txt`" — that's relative
 * to wherever the agent is running, which is the active pane's cwd. The viewer
 * must turn it into something the remote shell can open.
 *
 * Rules:
 *  - Local `file://` URIs (`file:///home/me/out.png`) decode to their absolute
 *    path before the normal rooted-path handling. Non-local authorities are not
 *    rewritten.
 *  - Absolute paths (`/...`) pass through, with `.`/`..` segments collapsed
 *    ([PathNormalizer]) so a tapped `…/git/pocketshell/../../../tmp/x.md`
 *    resolves AND displays as the canonical `/tmp/x.md` (issue #558 bug 1).
 *  - `~`-relative paths (`~`, `~/...`) expand to [remoteHome] when it is
 *    known, then collapse `.`/`..` segments so the viewer breadcrumb and
 *    missing-file errors show the canonical absolute remote path. Without a
 *    known home, the path keeps its `~` prefix and the remote login shell
 *    expands it at fetch time.
 *  - Everything else is joined onto [cwd] when [cwd] is a usable absolute or
 *    tilde path; the joined result is then normalised so a relative `../`
 *    target also collapses for resolution and the breadcrumb display.
 *  - When [cwd] is blank/unusable, a relative path passes through unchanged so
 *    the remote shell resolves it against the SSH session's own default cwd
 *    (the user's home). This is a best-effort fallback, not a guarantee.
 */
object RemotePathResolver {

    fun resolve(input: String, cwd: String?, remoteHome: String? = null): String {
        val path = input.trim()
        if (path.isEmpty()) return path
        decodeLocalFileUriPath(path)?.let { return PathNormalizer.normalize(it) }
        expandHomeShortcut(path, remoteHome)?.let { return PathNormalizer.normalize(it) }
        if (isAlreadyRooted(path)) return PathNormalizer.normalize(path)

        val base = cwd?.trim().orEmpty()
        if (base.isEmpty() || !isAlreadyRooted(base)) {
            // No usable base — let the remote shell resolve against $HOME.
            return path
        }

        val cleanedBase = expandHomeShortcut(base, remoteHome)?.trimEnd('/') ?: base.trimEnd('/')
        val cleanedInput = path.removePrefix("./")
        return PathNormalizer.normalize("$cleanedBase/$cleanedInput")
    }

    /** True for absolute (`/...`) or `~`-relative paths the remote shell expands. */
    internal fun isAlreadyRooted(path: String): Boolean =
        path.startsWith("/") || path == "~" || path.startsWith("~/")

    private fun expandHomeShortcut(path: String, remoteHome: String?): String? {
        val home = remoteHome
            ?.trim()
            ?.let { it.trimEnd('/').ifEmpty { "/" } }
            ?.takeIf { it.startsWith("/") }
            ?: return null
        return when {
            path == "~" -> home
            path.startsWith("~/") -> "$home/${path.removePrefix("~/")}"
            else -> null
        }
    }
}
