package com.pocketshell.core.terminal.selection

/**
 * Pure, Android-free path canonicalisation shared by the terminal tap-to-view
 * path (issue #500), the in-app file viewer's [RemotePathResolver] resolution +
 * breadcrumb (issue #497/#558), and the conversation-view link detection
 * (issue #557).
 *
 * The single job here is to collapse `.` and `..` segments against an absolute
 * (or `~`-rooted) base so a tapped path like
 * `/home/alexey/git/pocketshell/../../../tmp/x.md` resolves AND displays as the
 * canonical `/tmp/x.md`, with no literal `..` left in the breadcrumb (issue
 * #558 bug 1).
 *
 * `~` expansion to `$HOME` is deliberately NOT done here: the client does not
 * know the remote `$HOME` without an extra round-trip, so a `~`-rooted path
 * keeps its `~` prefix and the remote login shell expands it at fetch time (see
 * `RealSshSession`'s shell-quoting, issue #558 bug 3). Normalisation of the
 * segments *after* the `~` still happens so `~/a/../b.txt` → `~/b.txt`.
 */
public object PathNormalizer {

    /**
     * Collapse `.`/`..` segments in [path].
     *
     * - An absolute path (`/...`) collapses against `/`; `..` segments that
     *   would escape the root are dropped (matching POSIX `cd /; cd ..` → `/`).
     * - A `~`-rooted path (`~`, `~/...`) keeps its `~` prefix and collapses the
     *   remainder; a `..` that would escape the home root is dropped.
     * - Any other (relative) path is returned unchanged — there is no base to
     *   collapse it against here; the caller resolves it first via
     *   [RemotePathResolver.resolve] and then normalises the absolute result.
     *
     * Idempotent: normalising an already-canonical path returns it unchanged.
     * A trailing slash on a directory path is preserved (so `/a/b/` stays a
     * directory breadcrumb) except for the bare root `/`.
     */
    public fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return trimmed

        val tildePrefix: String
        val body: String
        when {
            trimmed == "~" -> return "~"
            trimmed.startsWith("~/") -> {
                tildePrefix = "~"
                body = trimmed.substring(1) // keep the leading '/'
            }
            trimmed.startsWith("/") -> {
                tildePrefix = ""
                body = trimmed
            }
            // No usable root to collapse against — leave relative paths alone.
            else -> return trimmed
        }

        val hadTrailingSlash = body.length > 1 && body.endsWith('/')
        val segments = body.split('/')
        val stack = ArrayDeque<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> {} // skip empty (from leading/duplicate slashes) and '.'
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }

        val joined = stack.joinToString("/")
        val rooted = "$tildePrefix/$joined"
        // Re-attach a trailing slash for a directory path that had one, but
        // never produce `//` or a trailing slash on the bare root.
        return when {
            joined.isEmpty() -> if (tildePrefix.isEmpty()) "/" else "~"
            hadTrailingSlash -> "$rooted/"
            else -> rooted
        }
    }
}
