package com.pocketshell.app.git

import com.pocketshell.core.ssh.SshSession

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

    companion object {
        const val DEFAULT_LIMIT: Int = 100

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

        /** Single-quote a path for safe interpolation into a remote shell command. */
        internal fun quoteSingle(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}

/** The target directory is not inside a git working tree. */
class NotAGitRepoException(val dir: String) :
    Exception("Not a git repository: $dir")

/** `git log` (or the probe) failed for a reason other than not-a-repo. */
class GitCommandException(message: String) : Exception(message)
