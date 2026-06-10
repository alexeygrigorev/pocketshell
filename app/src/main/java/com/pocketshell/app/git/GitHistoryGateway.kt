package com.pocketshell.app.git

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshSession
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads recent commit history for a remote directory over an existing SSH
 * session — issue #646 (epic #644 slice 2).
 *
 * Read-only. Mirrors the file-explorer's gateway shape: a thin wrapper that
 * runs one shell command via [SshSession.exec] and parses the structured
 * output into domain records. The parse is pulled out as a pure function so it
 * can be unit-tested without a live session.
 *
 * The git format uses ASCII control characters as delimiters — unit separator
 * (``) between fields and record separator (``) between commits — so
 * a commit subject or author containing pipes, tabs, or newlines never corrupts
 * the parse.
 */
class GitHistoryGateway(private val session: SshSession) {

    /**
     * Fetch up to [limit] most-recent commits for [dir].
     *
     * Returns a [Result] so the caller can distinguish the not-a-git-repo case
     * (a [NotAGitRepoException]) from a transport error or an empty-but-valid
     * repository (an empty list).
     */
    suspend fun recentCommits(dir: String, limit: Int = DEFAULT_LIMIT): Result<List<GitCommit>> {
        val quoted = quoteSingle(dir)
        // `rev-parse` first so a non-repo (or a path that isn't a working tree)
        // gives a precise signal instead of git's generic "fatal" on stderr.
        val probe = runCatching {
            session.exec("git -C $quoted rev-parse --is-inside-work-tree 2>/dev/null")
        }.getOrElse { return Result.failure(it) }
        if (probe.exitCode != 0 || probe.stdout.trim() != "true") {
            return Result.failure(NotAGitRepoException(dir))
        }

        val logCmd = buildString {
            append("git -C ").append(quoted)
            append(" log --no-color --max-count=").append(limit.coerceAtLeast(1))
            // %h short-hash | %an author | %ar relative-time | %s subject.
            append(" --pretty=format:'%h").append(UNIT).append("%an")
            append(UNIT).append("%ar").append(UNIT).append("%s").append(RECORD).append("'")
        }
        val result = runCatching { session.exec(logCmd) }
            .getOrElse { return Result.failure(it) }
        if (result.exitCode != 0) {
            // A valid repo with zero commits exits non-zero on `git log`
            // ("does not have any commits yet"). Treat that as an empty list,
            // not an error, so the screen shows the empty state.
            val stderr = result.stderr.lowercase()
            if (stderr.contains("does not have any commits") ||
                stderr.contains("bad default revision")
            ) {
                return Result.success(emptyList())
            }
            return Result.failure(
                GitCommandException(result.stderr.trim().ifBlank { "git log failed" }),
            )
        }
        return Result.success(parseLog(result.stdout))
    }

    /**
     * Fetch the read-only repository overview for [dir] — issue #647: branches,
     * linked worktrees, and a working-tree status summary (current branch,
     * ahead/behind vs upstream, dirty/clean, last commit).
     *
     * Returns a [NotAGitRepoException] failure when [dir] isn't a working tree,
     * mirroring [recentCommits], so the caller can render the same NotARepo state.
     * A valid-but-empty repo (no commits) succeeds with `hasNoCommits = true`.
     */
    suspend fun repoOverview(dir: String): Result<GitRepoOverview> {
        val quoted = quoteSingle(dir)
        val probe = runCatching {
            session.exec("git -C $quoted rev-parse --is-inside-work-tree 2>/dev/null")
        }.getOrElse { return Result.failure(it) }
        if (probe.exitCode != 0 || probe.stdout.trim() != "true") {
            return Result.failure(NotAGitRepoException(dir))
        }

        // status --porcelain=v2 --branch is machine-stable and reports the
        // current branch, upstream, ahead/behind, and each changed path on its
        // own line — robust against branch/file names with spaces.
        val statusOut = runCatching {
            session.exec("git -C $quoted status --porcelain=v2 --branch 2>/dev/null")
        }.getOrElse { return Result.failure(it) }
        if (statusOut.exitCode != 0) {
            return Result.failure(
                GitCommandException(statusOut.stderr.trim().ifBlank { "git status failed" }),
            )
        }

        // Last commit one-liner; non-zero on a repo with no commits yet.
        val lastOut = runCatching {
            session.exec(
                "git -C $quoted log -1 --no-color --pretty=format:'%h %s' 2>/dev/null",
            )
        }.getOrElse { return Result.failure(it) }
        val lastCommit = if (lastOut.exitCode == 0) lastOut.stdout.trim().ifBlank { null } else null

        // Branches: name, HEAD marker, upstream, and tip subject — delimited so
        // a subject with spaces/pipes survives.
        val branchCmd = buildString {
            append("git -C ").append(quoted)
            append(" branch --no-color --format='%(HEAD)").append(UNIT)
            append("%(refname:short)").append(UNIT)
            append("%(upstream:short)").append(UNIT)
            append("%(contents:subject)").append(RECORD).append("' 2>/dev/null")
        }
        val branchOut = runCatching { session.exec(branchCmd) }
            .getOrElse { return Result.failure(it) }
        val branches = if (branchOut.exitCode == 0) parseBranches(branchOut.stdout) else emptyList()

        val worktreeOut = runCatching {
            session.exec("git -C $quoted worktree list --porcelain 2>/dev/null")
        }.getOrElse { return Result.failure(it) }
        val worktrees =
            if (worktreeOut.exitCode == 0) parseWorktrees(worktreeOut.stdout) else emptyList()

        val status = parseStatus(statusOut.stdout, lastCommit)
        return Result.success(
            GitRepoOverview(status = status, branches = branches, worktrees = worktrees),
        )
    }

    /**
     * Read the `origin` remote URL for [dir] over SSH — issue #648 (epic #644
     * slice 4). Best-effort: returns null when [dir] isn't a working tree, has
     * no `origin` remote, or the command otherwise fails, so the caller simply
     * doesn't offer the "Open on GitHub" action rather than erroring.
     *
     * The raw URL is returned as-is; [GitHubRemote.webUrl] turns it into a
     * canonical GitHub page (or null for non-GitHub remotes).
     */
    suspend fun originRemoteUrl(dir: String): String? {
        val quoted = quoteSingle(dir)
        val probe = runCatching {
            session.exec("git -C $quoted rev-parse --is-inside-work-tree 2>/dev/null")
        }.getOrNull() ?: return null
        if (probe.exitCode != 0 || probe.stdout.trim() != "true") return null

        val remote = runCatching {
            session.exec("git -C $quoted remote get-url origin 2>/dev/null")
        }.getOrNull() ?: return null
        if (remote.exitCode != 0) return null
        return remote.stdout.trim().ifBlank { null }
    }

    /**
     * Check whether the GitHub CLI (`gh`) is installed AND authenticated on the
     * remote — issue #649 (epic #644 slice 5), gated on slice 1 (#645).
     *
     * Runs `pocketshell github status --json` through the PATH-robust
     * [PocketshellCommand.wrap] resolver (so a `pocketshell` in `~/.local/bin`
     * that the non-interactive SSH `PATH` misses is still found) and parses the
     * `{installed, authenticated, account, hint}` envelope. Returns
     * [GhConfigStatus.Configured] only when gh is both installed and
     * authenticated; otherwise [GhConfigStatus.NotConfigured] carries the hint
     * so the screen can prompt the user to configure gh.
     *
     * When `pocketshell` itself is missing (exit 127) we still return a
     * not-configured hint pointing at the gh setup, since the issue list can't
     * be fetched either way.
     */
    suspend fun ghStatus(): GhConfigStatus {
        val result = runCatching {
            session.exec(PocketshellCommand.wrap("github status --json"))
        }.getOrElse {
            return GhConfigStatus.NotConfigured(DEFAULT_GH_HINT)
        }
        if (result.exitCode == 127) {
            return GhConfigStatus.NotConfigured(POCKETSHELL_MISSING_HINT)
        }
        return parseGhStatus(result.stdout)
    }

    /**
     * List the current repo's GitHub issues via `gh issue list --json …` over
     * SSH — issue #649 (epic #644 slice 5).
     *
     * Runs `gh issue list` inside [dir] (gh resolves the repo from the working
     * directory) requesting the `number,title,state,labels,updatedAt` fields,
     * capped at [limit]. The caller is expected to have already gated on
     * [ghStatus]; this method assumes gh is usable.
     *
     * Returns a [Result] so the caller can distinguish a transport error from a
     * gh failure (e.g. not a GitHub repo, no network) — a [GitCommandException]
     * carries gh's stderr. A clean empty listing (`[]`) succeeds with an empty
     * list so the screen can show its empty state.
     */
    suspend fun listIssues(dir: String, limit: Int = DEFAULT_ISSUE_LIMIT): Result<List<GitHubIssue>> {
        val quoted = quoteSingle(dir)
        val safeLimit = limit.coerceIn(1, MAX_ISSUE_LIMIT)
        // `cd` into the repo so gh resolves the GitHub repo from the directory's
        // origin remote; `--json` emits a top-level array we parse directly.
        val cmd = "cd $quoted && gh issue list " +
            "--json number,title,state,labels,updatedAt " +
            "--state all --limit $safeLimit"
        val result = runCatching { session.exec(cmd) }
            .getOrElse { return Result.failure(it) }
        if (result.exitCode != 0) {
            return Result.failure(
                GitCommandException(result.stderr.trim().ifBlank { "gh issue list failed" }),
            )
        }
        return Result.success(GitHubIssueParser.parse(result.stdout))
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100

        /** Default issue listing cap — keeps a giant project's list manageable. */
        const val DEFAULT_ISSUE_LIMIT: Int = 50

        /** Hard ceiling on the issue listing limit passed to `gh issue list`. */
        const val MAX_ISSUE_LIMIT: Int = 200

        /** Fallback hint when the gh-status probe can't run at all. */
        internal const val DEFAULT_GH_HINT: String =
            "install gh (https://cli.github.com) and run `gh auth login`"

        /** Hint when `pocketshell` (the status helper) itself isn't installed. */
        internal const val POCKETSHELL_MISSING_HINT: String =
            "install pocketshell + gh on the server, then run `gh auth login`"

        // ASCII control delimiters — never appear in real commit metadata.
        private const val UNIT = ""
        private const val RECORD = ""

        /**
         * Parse the delimited `git log` output into [GitCommit] records. Pure —
         * unit-tested. Blank trailing fragments (from the final record
         * separator) are dropped, and a record with too few fields is skipped
         * rather than throwing.
         */
        internal fun parseLog(raw: String): List<GitCommit> {
            if (raw.isBlank()) return emptyList()
            return raw.split(RECORD)
                .asSequence()
                .map { it.trim('\n', '\r') }
                .filter { it.isNotEmpty() }
                .mapNotNull { record ->
                    val fields = record.split(UNIT)
                    if (fields.size < 4) return@mapNotNull null
                    val hash = fields[0].trim()
                    if (hash.isEmpty()) return@mapNotNull null
                    GitCommit(
                        shortHash = hash,
                        author = fields[1].trim(),
                        relativeTime = fields[2].trim(),
                        // Subject may legitimately contain the unit separator
                        // never, but re-join defensively in case a field count
                        // grows; index 3 onward is the subject.
                        subject = fields.drop(3).joinToString(UNIT).trim(),
                    )
                }
                .toList()
        }

        /**
         * Parse `git branch --format='%(HEAD)␟%(refname:short)␟%(upstream:short)␟%(contents:subject)␞'`.
         * Pure — unit-tested. The current branch (`%(HEAD)` == `*`) is moved to
         * the front so the UI lists it first. Records with no name are skipped.
         */
        internal fun parseBranches(raw: String): List<GitBranch> {
            if (raw.isBlank()) return emptyList()
            val parsed = raw.split(RECORD)
                .asSequence()
                .map { it.trim('\n', '\r') }
                .filter { it.isNotEmpty() }
                .mapNotNull { record ->
                    val fields = record.split(UNIT)
                    if (fields.size < 2) return@mapNotNull null
                    val name = fields[1].trim()
                    if (name.isEmpty()) return@mapNotNull null
                    GitBranch(
                        name = name,
                        current = fields[0].trim() == "*",
                        upstream = fields.getOrNull(2)?.trim()?.ifBlank { null },
                        subject = fields.drop(3).joinToString(UNIT).trim().ifBlank { null },
                    )
                }
                .toList()
            // Current branch first, then the rest in git's (alphabetical) order.
            return parsed.sortedByDescending { it.current }
        }

        /**
         * Parse `git worktree list --porcelain`. Pure — unit-tested. Records are
         * separated by a blank line; each starts with `worktree <path>` and may
         * carry `HEAD <sha>`, `branch refs/heads/<name>`, `bare`, or `detached`.
         */
        internal fun parseWorktrees(raw: String): List<GitWorktree> {
            if (raw.isBlank()) return emptyList()
            val result = mutableListOf<GitWorktree>()
            var path: String? = null
            var head: String? = null
            var branch: String? = null
            var bare = false
            var detached = false

            fun flush() {
                val p = path ?: return
                result += GitWorktree(
                    path = p,
                    branch = branch,
                    head = head,
                    bare = bare,
                    detached = detached,
                )
                path = null; head = null; branch = null; bare = false; detached = false
            }

            for (line in raw.split('\n')) {
                val trimmed = line.trim('\r')
                when {
                    trimmed.isBlank() -> flush()
                    trimmed.startsWith("worktree ") -> {
                        flush()
                        path = trimmed.removePrefix("worktree ").trim()
                    }
                    trimmed.startsWith("HEAD ") ->
                        head = trimmed.removePrefix("HEAD ").trim().take(7).ifBlank { null }
                    trimmed.startsWith("branch ") ->
                        branch = trimmed.removePrefix("branch ").trim()
                            .removePrefix("refs/heads/").ifBlank { null }
                    trimmed == "bare" -> bare = true
                    trimmed == "detached" -> detached = true
                }
            }
            flush()
            return result
        }

        /**
         * Parse `git status --porcelain=v2 --branch` plus a pre-fetched
         * [lastCommit] one-liner. Pure — unit-tested. Header lines start with
         * `# branch.*`; every other non-blank line is a changed/untracked path.
         */
        internal fun parseStatus(raw: String, lastCommit: String?): GitRepoStatus {
            var currentBranch: String? = null
            var upstream: String? = null
            var ahead = 0
            var behind = 0
            var hasNoCommits = false
            var changed = 0

            for (line in raw.split('\n')) {
                val trimmed = line.trim('\r')
                if (trimmed.isBlank()) continue
                if (trimmed.startsWith("# branch.head ")) {
                    val head = trimmed.removePrefix("# branch.head ").trim()
                    currentBranch = if (head == "(detached)") null else head
                } else if (trimmed.startsWith("# branch.upstream ")) {
                    upstream = trimmed.removePrefix("# branch.upstream ").trim().ifBlank { null }
                } else if (trimmed.startsWith("# branch.ab ")) {
                    // Format: "# branch.ab +A -B"
                    val parts = trimmed.removePrefix("# branch.ab ").trim().split(" ")
                    ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                    behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                } else if (trimmed.startsWith("# branch.oid ")) {
                    hasNoCommits = trimmed.removePrefix("# branch.oid ").trim() == "(initial)"
                } else if (!trimmed.startsWith("#")) {
                    // 1/2 (changed/renamed), u (unmerged), ? (untracked), ! (ignored).
                    if (trimmed.firstOrNull() != '!') changed++
                }
            }
            return GitRepoStatus(
                currentBranch = currentBranch,
                upstream = upstream,
                ahead = ahead,
                behind = behind,
                dirty = changed > 0,
                changedFiles = changed,
                lastCommit = if (hasNoCommits) null else lastCommit,
                hasNoCommits = hasNoCommits,
            )
        }

        /** Single-quote a path for safe interpolation into a remote shell command. */
        internal fun quoteSingle(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        /**
         * Parse the `pocketshell github status --json` envelope
         * (`{installed, authenticated, account, hint}`) into a [GhConfigStatus].
         * Pure — unit-tested. Malformed / empty output is treated as
         * not-configured with the default hint so the screen degrades to the
         * configure-gh prompt rather than erroring.
         */
        internal fun parseGhStatus(raw: String): GhConfigStatus {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return GhConfigStatus.NotConfigured(DEFAULT_GH_HINT)
            val obj = try {
                JSONObject(trimmed)
            } catch (_: JSONException) {
                return GhConfigStatus.NotConfigured(DEFAULT_GH_HINT)
            }
            val installed = obj.optBoolean("installed", false)
            val authenticated = obj.optBoolean("authenticated", false)
            if (installed && authenticated) {
                val account = obj.optString("account", "").trim().ifBlank { null }
                return GhConfigStatus.Configured(account)
            }
            val hint = obj.optString("hint", "").trim().ifBlank { DEFAULT_GH_HINT }
            return GhConfigStatus.NotConfigured(hint)
        }
    }
}

/**
 * Whether the GitHub CLI is ready to use on the remote — issue #649. Gates the
 * in-app Issues view: [Configured] surfaces the issue list, [NotConfigured]
 * surfaces a "configure gh" hint instead.
 */
sealed interface GhConfigStatus {
    /** gh is installed and authenticated. [account] is the logged-in user, if known. */
    data class Configured(val account: String?) : GhConfigStatus

    /** gh is missing or unauthenticated. [hint] tells the user how to fix it. */
    data class NotConfigured(val hint: String) : GhConfigStatus
}

/** The target directory is not inside a git working tree. */
class NotAGitRepoException(val dir: String) :
    Exception("Not a git repository: $dir")

/** `git log` (or the probe) failed for a reason other than not-a-repo. */
class GitCommandException(message: String) : Exception(message)
