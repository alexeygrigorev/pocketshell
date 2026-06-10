package com.pocketshell.app.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * UI state for the Git history screen — issue #646 (epic #644 slice 2).
 *
 * Every state carries the [dir] so the header renders the project path even
 * mid-load and on error.
 */
sealed interface GitHistoryUiState {

    val dir: String

    /** Fetching the log for [dir]. */
    data class Loading(override val dir: String) : GitHistoryUiState

    /** Commits + repo overview were read. [commits] are newest-first (git log order). */
    data class Ready(
        override val dir: String,
        val commits: List<GitCommit>,
        /** True when the listing hit the count cap. */
        val truncated: Boolean,
        /**
         * Branches / worktrees / status overview (issue #647), or null when the
         * overview probe failed even though history loaded.
         */
        val overview: GitRepoOverview? = null,
        /**
         * Canonical `https://github.com/<owner>/<repo>` web URL when `origin`
         * points at GitHub (issue #648), else null — null hides the
         * "Open on GitHub" action.
         */
        val gitHubUrl: String? = null,
        /**
         * GitHub issues for the repo (issue #649), newest-updated first as gh
         * returns them. Null when gh isn't configured (then [ghHint] is set), or
         * the listing failed. An empty list is a real "no open issues" state.
         */
        val issues: List<GitHubIssue>? = null,
        /**
         * When gh is NOT installed/authenticated on the remote, the actionable
         * "configure gh" hint to show on the Issues tab instead of a list. Null
         * when gh is configured.
         */
        val ghHint: String? = null,
    ) : GitHistoryUiState

    /** [dir] is not inside a git working tree. */
    data class NotARepo(override val dir: String) : GitHistoryUiState

    /** The log couldn't be read (transport drop, git error). [canRetry] gates Retry. */
    data class Failed(
        override val dir: String,
        val message: String,
        val canRetry: Boolean = true,
    ) : GitHistoryUiState
}

/**
 * Backs [GitHistoryScreen] — issues #646 + #647.
 *
 * Opens one persistent [SshSession] for the host (mirroring the credentials the
 * live session already holds, passed via [start]) and reads recent commit
 * history plus a read-only repository overview (branches, worktrees, status) for
 * a project directory through [GitHistoryGateway]. Read-only: no write/checkout
 * operations here.
 */
@HiltViewModel
class GitHistoryViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<GitHistoryUiState>(
        GitHistoryUiState.Loading(dir = ""),
    )
    val state: StateFlow<GitHistoryUiState> = _state.asStateFlow()

    private var request: Request? = null
    private var session: SshSession? = null
    private var loadJob: Job? = null

    /**
     * Bind host credentials + the project directory and load it. Idempotent for
     * the same [Request] so a recomposition doesn't re-fetch.
     */
    fun start(request: Request) {
        if (this.request == request) return
        this.request = request
        load()
    }

    /** Re-read the log (wired to Retry on the failed panel). */
    fun retry() = load()

    private fun load() {
        val req = request ?: return
        loadJob?.cancel()
        _state.value = GitHistoryUiState.Loading(dir = req.dir)
        loadJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { fetch(req) }
        }
    }

    private suspend fun fetch(req: Request): GitHistoryUiState {
        val live = ensureSession(req)
            ?: return GitHistoryUiState.Failed(
                dir = req.dir,
                message = "Couldn't reach ${req.username}@${req.hostname}.",
            )
        return try {
            val gateway = GitHistoryGateway(live)
            val result = gateway.recentCommits(req.dir, limit = COMMIT_LIMIT)
            result.fold(
                onSuccess = { commits ->
                    // Overview is best-effort: a failure here (e.g. a transient
                    // git error) must not hide the history that already loaded.
                    val overview = gateway.repoOverview(req.dir).getOrNull()
                    // Best-effort GitHub detection (#648): a failure here must
                    // not hide the history/overview that already loaded.
                    val gitHubUrl = runCatching {
                        GitHubRemote.webUrl(gateway.originRemoteUrl(req.dir))
                    }.getOrNull()
                    // GitHub issues (#649), gated on gh being configured (#645).
                    // Best-effort: a probe/listing failure must not hide the
                    // history that already loaded — the Issues tab degrades to
                    // a hint or an empty state.
                    val (issues, ghHint) = fetchIssues(gateway, req.dir)
                    GitHistoryUiState.Ready(
                        dir = req.dir,
                        commits = commits,
                        truncated = commits.size >= COMMIT_LIMIT,
                        overview = overview,
                        gitHubUrl = gitHubUrl,
                        issues = issues,
                        ghHint = ghHint,
                    )
                },
                onFailure = { error ->
                    when (error) {
                        is NotAGitRepoException -> GitHistoryUiState.NotARepo(req.dir)
                        is CancellationException -> throw error
                        else -> GitHistoryUiState.Failed(
                            dir = req.dir,
                            message = "Couldn't read git history: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            GitHistoryUiState.Failed(
                dir = req.dir,
                message = "Couldn't read git history: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    /**
     * Fetch GitHub issues for [dir], gated on gh being configured (#645/#649).
     *
     * Returns `(issues, ghHint)`:
     *  - gh configured → `(list, null)` (the list may be empty for "no issues").
     *  - gh NOT configured → `(null, hint)` so the Issues tab shows the prompt.
     *  - listing failed despite gh being configured → `(null, null)` so the tab
     *    shows a neutral "couldn't load" state rather than a misleading hint.
     *
     * All failures are swallowed (best-effort) so they never hide the history /
     * overview that already loaded.
     */
    private suspend fun fetchIssues(
        gateway: GitHistoryGateway,
        dir: String,
    ): Pair<List<GitHubIssue>?, String?> {
        val status = runCatching { gateway.ghStatus() }.getOrElse {
            return null to GitHistoryGateway.DEFAULT_GH_HINT
        }
        return when (status) {
            is GhConfigStatus.NotConfigured -> null to status.hint
            is GhConfigStatus.Configured -> {
                val issues = runCatching {
                    gateway.listIssues(dir, limit = ISSUE_LIMIT).getOrNull()
                }.getOrNull()
                issues to null
            }
        }
    }

    private suspend fun ensureSession(req: Request): SshSession? {
        session?.let { if (it.isConnected) return it }
        val opened = SshConnection.connect(
            host = req.hostname,
            port = req.port,
            user = req.username,
            key = SshKey.Path(File(req.keyPath)),
            passphrase = req.passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrNull()
        session = opened
        return opened
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        runCatching { session?.close() }
        session = null
    }

    /**
     * Host credentials + the project directory to inspect. Mirrors the
     * credential shape used by sibling per-host screens.
     */
    data class Request(
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val dir: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
            if (dir != other.dir) return false
            if (passphrase != null) {
                if (other.passphrase == null) return false
                if (!passphrase.contentEquals(other.passphrase)) return false
            } else if (other.passphrase != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + dir.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /** Cap the history so a giant repo doesn't blow up the listing. */
        const val COMMIT_LIMIT: Int = GitHistoryGateway.DEFAULT_LIMIT

        /** Cap the in-app GitHub issue listing (#649). */
        const val ISSUE_LIMIT: Int = GitHistoryGateway.DEFAULT_ISSUE_LIMIT
    }
}
