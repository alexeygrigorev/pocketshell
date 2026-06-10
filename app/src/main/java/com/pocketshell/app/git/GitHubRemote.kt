package com.pocketshell.app.git

/**
 * Pure parser for a git remote URL → a canonical GitHub web page URL —
 * issue #648 (epic #644 slice 4).
 *
 * Converts the `origin` remote that `git remote get-url origin` prints into the
 * `https://github.com/<owner>/<repo>` page a user can open in a browser. Pure
 * and side-effect-free so it can be unit-tested without a live session.
 *
 * Handles the common remote forms:
 *  - `git@github.com:owner/repo.git`        (scp-like SSH)
 *  - `ssh://git@github.com/owner/repo.git`  (ssh:// SSH)
 *  - `https://github.com/owner/repo.git`    (HTTPS)
 *  - `https://github.com/owner/repo`        (HTTPS, no .git)
 *  - with or without a trailing `.git` and/or trailing slash
 *  - the host matched case-insensitively (`GitHub.com`)
 *
 * Returns null for any non-GitHub remote (GitLab, Bitbucket, a plain SSH host,
 * a blank string) so the caller only surfaces "Open on GitHub" for real GitHub
 * repos.
 */
object GitHubRemote {

    private const val GITHUB_HOST = "github.com"

    /**
     * Derive the canonical `https://github.com/<owner>/<repo>` web URL from a
     * raw [remoteUrl], or null when it isn't a GitHub remote.
     */
    fun webUrl(remoteUrl: String?): String? {
        val trimmed = remoteUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val (host, path) = splitHostAndPath(trimmed) ?: return null
        if (!host.equals(GITHUB_HOST, ignoreCase = true)) return null

        val (owner, repo) = ownerRepo(path) ?: return null
        return "https://$GITHUB_HOST/$owner/$repo"
    }

    /**
     * Split a remote URL into its host and the owner/repo path part, normalising
     * the three transport shapes. Returns null when the shape isn't recognised.
     */
    private fun splitHostAndPath(url: String): Pair<String, String>? {
        // scp-like SSH: git@github.com:owner/repo.git  (note the ':' separator,
        // and there is no "//" after a scheme). Detect it before scheme parsing
        // so the colon isn't mistaken for a port.
        if ("://" !in url && url.contains(':')) {
            val afterUser = url.substringAfter('@', url)
            val host = afterUser.substringBefore(':')
            val path = afterUser.substringAfter(':')
            if (host.isBlank() || path.isBlank()) return null
            return host to path
        }

        // scheme://[user@]host[:port]/owner/repo  (https, ssh, http, git).
        if ("://" in url) {
            val afterScheme = url.substringAfter("://")
            val authority = afterScheme.substringBefore('/')
            val path = afterScheme.substringAfter('/', "")
            if (path.isBlank()) return null
            // strip an optional user@ and :port from the authority.
            val hostWithPort = authority.substringAfter('@', authority)
            val host = hostWithPort.substringBefore(':')
            if (host.isBlank()) return null
            return host to path
        }

        return null
    }

    /**
     * Reduce a path like `owner/repo.git/` to the `owner` / `repo` pair, dropping
     * a trailing slash and a `.git` suffix. Returns null when either part is
     * missing or the path has extra segments (not an `owner/repo` shape).
     */
    private fun ownerRepo(rawPath: String): Pair<String, String>? {
        val path = rawPath.trim().trim('/')
        if (path.isEmpty()) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size != 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (owner.isEmpty() || repo.isEmpty()) return null
        return owner to repo
    }
}
