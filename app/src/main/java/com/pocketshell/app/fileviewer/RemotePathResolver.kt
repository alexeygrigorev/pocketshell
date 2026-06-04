package com.pocketshell.app.fileviewer

/**
 * Resolve a user-/agent-supplied remote path against the session's working
 * directory (issue #497).
 *
 * The agent says "look, there's a file at `out/report.txt`" — that's relative
 * to wherever the agent is running, which is the active pane's cwd. The viewer
 * must turn it into something the remote shell can open.
 *
 * Rules (deliberately conservative — the heavy lifting of `~`/`$VAR` expansion
 * is left to the remote login shell, which `RealSshSession.downloadFile`
 * resolves through):
 *  - Absolute paths (`/...`) pass through unchanged.
 *  - `~`-relative paths (`~`, `~/...`) pass through unchanged — the remote
 *    shell expands them.
 *  - Everything else is joined onto [cwd] when [cwd] is a usable absolute or
 *    tilde path; the join collapses a trailing slash and a leading `./`.
 *  - When [cwd] is blank/unusable, a relative path passes through unchanged so
 *    the remote shell resolves it against the SSH session's own default cwd
 *    (the user's home). This is a best-effort fallback, not a guarantee.
 */
object RemotePathResolver {

    fun resolve(input: String, cwd: String?): String {
        val path = input.trim()
        if (path.isEmpty()) return path
        if (isAlreadyRooted(path)) return path

        val base = cwd?.trim().orEmpty()
        if (base.isEmpty() || !isAlreadyRooted(base)) {
            // No usable base — let the remote shell resolve against $HOME.
            return path
        }

        val cleanedBase = base.trimEnd('/')
        val cleanedInput = path.removePrefix("./")
        return "$cleanedBase/$cleanedInput"
    }

    /** True for absolute (`/...`) or `~`-relative paths the remote shell expands. */
    internal fun isAlreadyRooted(path: String): Boolean =
        path.startsWith("/") || path == "~" || path.startsWith("~/")
}
