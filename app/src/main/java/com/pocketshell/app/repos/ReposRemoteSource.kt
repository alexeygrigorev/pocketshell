package com.pocketshell.app.repos

import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

public class ReposRemoteSource @Inject constructor(
    private val parser: ReposJsonParser,
) {
    private val localRootCache = mutableMapOf<LocalRootCacheKey, LocalRootCacheEntry>()

    public suspend fun listRemote(
        session: SshSession,
        limit: Int? = null,
    ): ReposListResult {
        val command = buildString {
            append("pocketshell repos list --remote --json")
            limit?.let {
                append(" --limit ")
                append(it.coerceAtLeast(1))
            }
        }
        return runList(session, command)
    }

    /**
     * Enumerate repositories already cloned on the host's disk via
     * `pocketshell repos list --local --json`. The unified schema rows
     * carry a populated `local` block (path + head) and a null `remote`
     * block — the inverse of [listRemote], which always reports
     * `local = null`.
     *
     * The repos browse screen joins both calls by `full_name` (falling
     * back to `name`) so a GitHub repo that is already cloned renders as
     * an "Open" row instead of a "Clone" row. The daemon's `--remote`
     * path deliberately does NOT join cloned state server-side, so the
     * merge lives on the client.
     */
    public suspend fun listLocal(
        session: SshSession,
    ): ReposListResult = runList(session, "pocketshell repos list --local --json")

    /**
     * Expand one watched parent root into the cloned project folders
     * underneath it via `pocketshell repos list --local --root <path>`.
     *
     * This is the host-detail tree gateway path (#301): it is local-only,
     * server-side over SSH, and deliberately cached app-side so a poller
     * can ask for the same watched root repeatedly without adding a
     * blocking SSH exec on every tick. Missing `pocketshell` / missing
     * `repos` subcommand degrades to an empty result because watched-root
     * expansion is optional decoration; the session list should keep
     * working on older hosts.
     */
    public suspend fun listLocalRoot(
        session: SshSession,
        root: String,
        cacheNamespace: String,
        forceRefresh: Boolean = false,
    ): ReposListResult {
        val cleanRoot = root.trim()
        if (cleanRoot.isEmpty()) return ReposListResult.Success(emptyList())

        val cacheKey = LocalRootCacheKey(
            namespace = cacheNamespace,
            root = cleanRoot,
        )
        if (!forceRefresh) {
            freshLocalRootCache(cacheKey)?.let { return ReposListResult.Success(it) }
        }

        val command = "pocketshell repos list --local --json --root ${shellQuote(cleanRoot)}"
        val result = runList(session, command)
        val normalized = when (result) {
            ReposListResult.ToolMissing -> ReposListResult.Success(emptyList())
            else -> result
        }
        if (normalized is ReposListResult.Success) {
            putLocalRootCache(cacheKey, normalized.repos)
        }
        return normalized
    }

    /**
     * Issue #1876: the already-cached expansion for [root], or `null` when the
     * entry is absent or stale.
     *
     * The folder reconcile now fetches every watched root in one batched exec
     * (see [com.pocketshell.app.projects.FolderListLandingProbeOwner]). If that
     * optional batch fails as a whole, this preserves any still-fresh expansion
     * already held by the normal poller. Same TTL and same key as
     * [listLocalRoot]; this is a read of that cache, not a second one.
     */
    public fun cachedLocalRoot(root: String, cacheNamespace: String): List<RepoEntry>? {
        val cleanRoot = root.trim()
        if (cleanRoot.isEmpty()) return emptyList()
        return freshLocalRootCache(LocalRootCacheKey(cacheNamespace, cleanRoot))
    }

    /**
     * Issue #1876: parse + cache a `pocketshell repos list --local --json
     * --root <root>` payload that a CALLER already fetched, applying exactly the
     * normalisation [listLocalRoot] applies to its own exec.
     *
     * This exists so the folder reconcile can fold N per-root round trips into
     * one batched exec without losing the app-side cache the poller relies on.
     */
    public fun adoptLocalRootPayload(
        root: String,
        cacheNamespace: String,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): ReposListResult {
        val cleanRoot = root.trim()
        if (cleanRoot.isEmpty()) return ReposListResult.Success(emptyList())
        val raw = try {
            when {
                exitCode == 0 -> ReposListResult.Success(parser.parseList(stdout))
                isPocketshellReposMissing(exitCode, stdout, stderr) -> ReposListResult.ToolMissing
                else -> ReposListResult.Failed(
                    stderr.ifBlank { stdout }.ifBlank { "pocketshell repos exited $exitCode" },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The caller already completed the correctness-critical tmux
            // enumeration. A drifted/older optional repos payload must have
            // the same typed failure semantics as [runList], never escape and
            // turn that required tree into ConnectFailed (#1876).
            ReposListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
        val normalized = when (raw) {
            ReposListResult.ToolMissing -> ReposListResult.Success(emptyList())
            else -> raw
        }
        if (normalized is ReposListResult.Success) {
            putLocalRootCache(LocalRootCacheKey(cacheNamespace, cleanRoot), normalized.repos)
        }
        return normalized
    }

    private suspend fun runList(
        session: SshSession,
        command: String,
    ): ReposListResult = try {
        val result = session.exec(pathAwareCommand(command))
        when {
            result.exitCode == 0 -> ReposListResult.Success(parser.parseList(result.stdout))
            result.isPocketshellReposMissing() -> ReposListResult.ToolMissing
            else -> ReposListResult.Failed(result.failureReason("pocketshell repos exited ${result.exitCode}"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        ReposListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public suspend fun open(
        session: SshSession,
        fullName: String,
    ): RepoPathResult =
        runPath(session, "pocketshell repos open ${shellQuote(fullName)}")

    public suspend fun clone(
        session: SshSession,
        fullName: String,
        root: String = "~/git",
        protocol: String = "ssh",
    ): RepoPathResult =
        runPath(
            session,
            "pocketshell repos clone ${shellQuote(fullName)} --root ${shellQuote(root)} --protocol ${shellQuote(protocol)}",
        )

    private suspend fun runPath(
        session: SshSession,
        command: String,
    ): RepoPathResult = try {
        val result = session.exec(pathAwareCommand(command))
        when {
            result.exitCode == 0 -> {
                val path = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (path.isNullOrBlank()) {
                    RepoPathResult.Failed("pocketshell repos returned an empty path")
                } else {
                    RepoPathResult.Success(path)
                }
            }
            result.exitCode == 127 -> RepoPathResult.ToolMissing
            else -> RepoPathResult.Failed(result.failureReason("pocketshell repos exited ${result.exitCode}"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        RepoPathResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public companion object {
        public const val LOCAL_ROOT_CACHE_TTL_MILLIS: Long = 10_000L

        /**
         * Build a PATH-augmented remote command that runs under an EXPLICIT
         * POSIX shell, regardless of the user's login shell.
         *
         * Issue #633: `sshj`'s `session.exec(string)` hands the command to the
         * remote account's LOGIN shell. The previous form emitted a bare POSIX
         * one-liner (`PATH="$HOME/.local/bin:…"; <cmd>`) and assumed that login
         * shell was POSIX. On a host whose login shell is `fish`, the inline
         * `PATH=…` assignment is a hard syntax error — fish rejects it with
         * `fish: Unsupported use of '='` and exits 127 BEFORE the real command
         * ever runs. Every folder/session probe (`tmux list-sessions`,
         * `list-panes`, `printf "$HOME"`, `pocketshell sessions`, port scan,
         * watched-root expansion) therefore failed on a fish-login host, so the
         * folder list never reached a usable Ready state — exactly the
         * `fish-user-local-path` setup-detection failure this fixes.
         *
         * The fix mirrors the non-POSIX-shell handling the host bootstrapper
         * already uses ([com.pocketshell.app.bootstrap.HostBootstrapper.posixShellCommand],
         * commit a5c55f44): wrap the POSIX body in `/bin/sh -lc '<body>'` so the
         * augmentation + command are parsed by `/bin/sh` instead of the login
         * shell. `/bin/sh` is present on every host the app supports, so this is
         * a single shell-agnostic path — no per-shell branching, no login-shell
         * fallback (hard-cut, D22). The body is single-quoted with
         * [shellQuote], so any single quotes it contains (e.g. tmux `-F`
         * format strings, `printf '%s\n'`) survive intact.
         */
        public fun pathAwareCommand(command: String): String =
            posixShellCommand("PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command")

        /**
         * Wrap [command] so it runs under an explicit POSIX `/bin/sh`, immune
         * to a non-POSIX login shell (fish). See [pathAwareCommand] (#633).
         */
        private fun posixShellCommand(command: String): String =
            "/bin/sh -lc ${shellQuote(command)}"

        public fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun freshLocalRootCache(key: LocalRootCacheKey): List<RepoEntry>? =
        synchronized(localRootCache) {
            val entry = localRootCache[key] ?: return@synchronized null
            val ageMillis = (System.nanoTime() - entry.writtenAtNanos) / 1_000_000L
            if (ageMillis <= LOCAL_ROOT_CACHE_TTL_MILLIS) {
                entry.repos
            } else {
                localRootCache.remove(key)
                null
            }
        }

    private fun putLocalRootCache(key: LocalRootCacheKey, repos: List<RepoEntry>) {
        synchronized(localRootCache) {
            localRootCache[key] = LocalRootCacheEntry(
                repos = repos,
                writtenAtNanos = System.nanoTime(),
            )
        }
    }

    private data class LocalRootCacheKey(
        val namespace: String,
        val root: String,
    )

    private data class LocalRootCacheEntry(
        val repos: List<RepoEntry>,
        val writtenAtNanos: Long,
    )
}

private fun com.pocketshell.core.ssh.ExecResult.failureReason(fallback: String): String =
    stderr.ifBlank { stdout }.ifBlank { fallback }

private fun com.pocketshell.core.ssh.ExecResult.isPocketshellReposMissing(): Boolean =
    isPocketshellReposMissing(exitCode, stdout, stderr)

internal fun isPocketshellReposMissing(exitCode: Int, stdout: String, stderr: String): Boolean {
    if (exitCode == 127) return true
    val output = "$stderr\n$stdout"
    return output.contains("No such command 'repos'", ignoreCase = true) ||
        output.contains("No such command \"repos\"", ignoreCase = true)
}
