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
 *
 * Issue #748 — the agent's actual working directory can be a *subdirectory* of
 * the session cwd (it `cd`'d into `matchbox-mattel-atv-6x6/` before generating
 * `renders/foo.png`). A clean relative path then canonicalises against the
 * wrong base — the session cwd — and points at a path that doesn't exist. The
 * app cannot reliably know the agent's exact cwd, so when the primary resolved
 * path is missing the viewer falls back to a bounded basename search under the
 * session cwd tree ([searchPlan]); the resolver supplies the pure search plan
 * (root, basename, relative-suffix for ranking) and the viewer runs the `find`.
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

    /**
     * A bounded search the viewer can run when the primary [resolve]d path is
     * missing (issue #748): the agent worked in a subdirectory, so a clean
     * relative path resolved against the wrong base. The viewer searches the
     * session-cwd tree for the file's [basename] and ranks matches so the one
     * whose tail matches the original relative path ([relativeSuffix]) wins.
     *
     * @property searchRoot Absolute directory to recurse under. Null when no
     *   usable rooted base is available (a relative search would be meaningless).
     * @property basename The file's final segment — what `find -name` matches.
     * @property relativeSuffix The original clean relative path
     *   (`renders/foo.png`), used to prefer a match whose path ends with it over
     *   an unrelated same-named file elsewhere in the tree.
     */
    data class SearchPlan(
        val searchRoot: String,
        val basename: String,
        val relativeSuffix: String,
    )

    /**
     * Build the basename-search fallback plan for a tapped [input] (issue #748),
     * or null when a search makes no sense:
     *  - the input is absolute, a `~`-path, or a `file://` URI (it was rooted —
     *    a missing rooted path is genuinely missing, not a wrong-base problem);
     *  - there is no usable rooted [cwd] to recurse under;
     *  - the input has no usable basename.
     *
     * Pure / JVM-testable. The actual `find` execution lives in the viewer
     * (it needs an SSH session); this just decides whether and where to search.
     */
    fun searchPlan(input: String, cwd: String?, remoteHome: String? = null): SearchPlan? {
        val path = input.trim()
        if (path.isEmpty()) return null
        // Rooted inputs (absolute, `~`, `file://`) were resolved exactly; a miss
        // there is a real miss, not a wrong-base subdirectory problem.
        if (decodeLocalFileUriPath(path) != null) return null
        if (isAlreadyRooted(path)) return null

        val base = cwd?.trim().orEmpty()
        if (base.isEmpty() || !isAlreadyRooted(base)) return null
        val searchRoot = (expandHomeShortcut(base, remoteHome)?.trimEnd('/') ?: base.trimEnd('/'))
            .takeIf { it.startsWith("/") }
            ?: return null

        // Derive the relative tail from the canonically-resolved path (so any
        // `./` and `../` segments collapse the same way the primary read did),
        // then strip the search root back off. A `../` that climbs above the
        // root leaves a path outside it — there is no in-tree suffix to match,
        // so the suffix degrades to the basename alone.
        val resolved = resolve(path, cwd, remoteHome).takeIf { it.startsWith("/") } ?: return null
        val relativeSuffix = if (resolved.startsWith("$searchRoot/")) {
            resolved.removePrefix("$searchRoot/").trimStart('/').ifEmpty { return null }
        } else {
            // The `../` climbed above the search root — no in-tree suffix to
            // match on; fall back to the bare basename for the search.
            resolved.substringAfterLast('/').ifBlank { return null }
        }
        val basename = relativeSuffix.substringAfterLast('/').ifBlank { return null }
        // A bare `..`/`.` target has no real basename to search for.
        if (basename == "." || basename == "..") return null

        return SearchPlan(
            searchRoot = searchRoot,
            basename = basename,
            relativeSuffix = relativeSuffix,
        )
    }

    /**
     * Pick the best of the candidate absolute paths a basename `find` returned
     * (issue #748). Preference order:
     *  1. a candidate whose path ends with the full [SearchPlan.relativeSuffix]
     *     (`.../matchbox-.../renders/foo.png` for `renders/foo.png`) — the agent
     *     just worked in a subdirectory, so the relative tail is intact;
     *  2. otherwise, when there is exactly one candidate, that one;
     *  3. otherwise null — ambiguous; the viewer offers the candidate list.
     *
     * Among multiple suffix matches the shallowest (fewest path segments) wins —
     * the closest to the session root is the most likely intended file. Pure.
     */
    fun chooseSearchMatch(plan: SearchPlan, candidates: List<String>): String? {
        val cleaned = candidates.map { it.trim() }.filter { it.startsWith("/") }.distinct()
        if (cleaned.isEmpty()) return null

        val suffix = "/" + plan.relativeSuffix
        val suffixMatches = cleaned.filter { it == plan.relativeSuffix || it.endsWith(suffix) }
        if (suffixMatches.isNotEmpty()) {
            return suffixMatches.minByOrNull { it.count { ch -> ch == '/' } }
        }
        return cleaned.singleOrNull()
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
