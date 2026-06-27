package com.pocketshell.app.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * State of the "New issue" create form (#650), separate from the screen's main
 * load state so opening/submitting the sheet doesn't disturb the list/overview.
 */
sealed interface CreateIssueUiState {
    /** No submission in flight; the form is editable. */
    data object Idle : CreateIssueUiState

    /** `gh issue create` is running. */
    data object Submitting : CreateIssueUiState

    /** The issue was created; [url] is the new issue's GitHub URL. */
    data class Success(val url: String) : CreateIssueUiState

    /** Creation failed; [message] is the gh/transport error to show. */
    data class Failure(val message: String) : CreateIssueUiState
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
class GitHistoryViewModel @Inject constructor(
    private val sshLeaseManager: SshLeaseManager,
) : ViewModel() {

    internal constructor(
        sshLeaseManager: SshLeaseManager,
        ioDispatcher: CoroutineDispatcher,
        bestEffortProbeTimeoutMs: Long = BEST_EFFORT_PROBE_TIMEOUT_MS,
        createIssueTimeoutMs: Long = CREATE_ISSUE_TIMEOUT_MS,
    ) : this(sshLeaseManager) {
        this.ioDispatcher = ioDispatcher
        this.bestEffortProbeTimeoutMs = bestEffortProbeTimeoutMs
        this.createIssueTimeoutMs = createIssueTimeoutMs
    }

    private val _state = MutableStateFlow<GitHistoryUiState>(
        GitHistoryUiState.Loading(dir = ""),
    )
    val state: StateFlow<GitHistoryUiState> = _state.asStateFlow()

    /** Create-issue form state (#650), independent of the main screen state. */
    private val _createState = MutableStateFlow<CreateIssueUiState>(CreateIssueUiState.Idle)
    val createState: StateFlow<CreateIssueUiState> = _createState.asStateFlow()

    private var request: Request? = null
    // Issue #699: the screen borrows ONE warm lease from the app-wide
    // @Singleton SshLeaseManager (keyed identically to the live session
    // screens, so it shares the same transport per host) and reuses
    // lease.session for every git read/write. Released on onCleared().
    private var lease: SshLease? = null
    private var loadJob: Job? = null
    private var createJob: Job? = null
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var bestEffortProbeTimeoutMs: Long = BEST_EFFORT_PROBE_TIMEOUT_MS
    private var createIssueTimeoutMs: Long = CREATE_ISSUE_TIMEOUT_MS

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

    /**
     * Submit a new GitHub issue (#650) for the bound repo dir over the existing
     * SSH session, then — on success — refresh the issues list (#649) so the new
     * issue appears. No-op while a previous submission is still in flight.
     *
     * The result is surfaced on [createState]: [CreateIssueUiState.Submitting]
     * while gh runs, then [CreateIssueUiState.Success] with the new issue URL or
     * [CreateIssueUiState.Failure] with the error message. The shell-safety of
     * [title] / [body] is handled by [GitHistoryGateway.createIssue].
     */
    fun createIssue(title: String, body: String) {
        val req = request ?: return
        if (_createState.value is CreateIssueUiState.Submitting) return
        createJob?.cancel()
        _createState.value = CreateIssueUiState.Submitting
        createJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                val live = ensureSession(req)
                    ?: return@withContext CreateIssueUiState.Failure(
                        "Couldn't reach ${req.username}@${req.hostname}.",
                    )
                val gateway = GitHistoryGateway(live)
                val result = boundedGatewayResult(
                    timeoutMs = createIssueTimeoutMs,
                    timeoutMessage = "gh issue create timed out",
                ) {
                    gateway.createIssue(req.dir, title, body)
                }
                result.fold(
                    onSuccess = { url -> CreateIssueUiState.Success(url) },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        CreateIssueUiState.Failure(
                            error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
            }
            _createState.value = outcome
            // Reflect the new issue in the list immediately on success.
            if (outcome is CreateIssueUiState.Success) load()
        }
    }

    /** Reset the create-issue form back to idle (sheet dismissed / re-opened). */
    fun dismissCreateIssue() {
        createJob?.cancel()
        _createState.value = CreateIssueUiState.Idle
    }

    private fun load() {
        val req = request ?: return
        loadJob?.cancel()
        _state.value = GitHistoryUiState.Loading(dir = req.dir)
        loadJob = viewModelScope.launch {
            _state.value = withContext(ioDispatcher) { fetch(req) }
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
                    // git error or hung status/worktree probe) must not hide the
                    // history that already loaded.
                    val overview = bestEffortProbe {
                        gateway.repoOverview(req.dir).getOrNull()
                    }
                    // Best-effort GitHub detection (#648): a failure here must
                    // not hide the history/overview that already loaded.
                    val gitHubUrl = bestEffortProbe {
                        GitHubRemote.webUrl(gateway.originRemoteUrl(req.dir))
                    }
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
        val status = bestEffortProbe { gateway.ghStatus() }
            ?: return null to GitHistoryGateway.DEFAULT_GH_HINT
        return when (status) {
            is GhConfigStatus.NotConfigured -> null to status.hint
            is GhConfigStatus.Configured -> {
                val issues = bestEffortProbe {
                    gateway.listIssues(dir, limit = ISSUE_LIMIT).getOrNull()
                }
                issues to null
            }
        }
    }

    private suspend fun <T> bestEffortProbe(block: suspend () -> T?): T? =
        try {
            withTimeoutOrNull(bestEffortProbeTimeoutMs) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    private suspend fun <T> boundedGatewayResult(
        timeoutMs: Long,
        timeoutMessage: String,
        block: suspend () -> Result<T>,
    ): Result<T> =
        try {
            withTimeoutOrNull(timeoutMs) { block() }
                ?: Result.failure(GitCommandException(timeoutMessage))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /**
     * Issue #699: borrow the warm transport from the app-wide
     * [SshLeaseManager] instead of dialing a fresh SSH connection per screen.
     * The lease is acquired once (keyed identically to the live session
     * screens via [Request.toLeaseTarget], so it shares the same per-host
     * transport — no extra handshake when a session is already warm) and its
     * [SshSession] is reused for every subsequent git read/write. Released in
     * [onCleared].
     */
    private suspend fun ensureSession(req: Request): SshSession? {
        lease?.let { if (it.session.isConnected) return it.session }
        // A stale lease (transport dropped) is released before re-acquiring so
        // the next acquire opens or reuses a healthy transport.
        lease?.let { stale ->
            withContext(NonCancellable) { runCatching { stale.release() } }
            lease = null
        }
        val acquired = sshLeaseManager.acquire(req.toLeaseTarget()).getOrNull()
        lease = acquired
        return acquired?.session
    }

    override fun onCleared() {
        loadJob?.cancel()
        createJob?.cancel()
        // Issue #699: refcount-- the warm transport SYNCHRONOUSLY before the VM
        // dies. A viewModelScope.launch here would race the framework's
        // post-onCleared scope cancellation and could leak the refcount, so we
        // release on a bounded IO hop — mirroring TmuxSessionViewModel's
        // teardown. Releasing only decrements the pool refcount; the warm
        // transport itself stays pooled (idle-TTL) for the next surface.
        val toRelease = lease
        lease = null
        if (toRelease != null) {
            runCatching {
                runBlocking(Dispatchers.IO + NonCancellable) {
                    withTimeoutOrNull(LEASE_RELEASE_TIMEOUT_MS) { toRelease.release() }
                }
            }
        }
        super.onCleared()
    }

    /**
     * Host credentials + the project directory to inspect. Mirrors the
     * credential shape used by sibling per-host screens.
     */
    data class Request(
        /**
         * Persistent host identifier — issue #699. Keys the warm SSH lease
         * identically to the live session screens
         * ([com.pocketshell.app.tmux.TmuxSessionViewModel]'s
         * `credentialId = "$hostId:$keyPath"`) so git history shares the one
         * warm transport per host instead of dialing a second connection.
         */
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val dir: String,
    ) {
        /**
         * Issue #699: build the lease key the SAME way the live session screen
         * does — `credentialId = "$hostId:$keyPath"`, `knownHostsId =
         * "accept-all"` — so an already-warm host transport is reused rather
         * than a fresh handshake dialed.
         */
        fun toLeaseTarget(): SshLeaseTarget =
            SshLeaseTarget(
                leaseKey = SshLeaseKey(
                    host = hostname,
                    port = port,
                    user = username,
                    credentialId = "$hostId:$keyPath",
                    knownHostsId = "accept-all",
                ),
                key = SshKey.Path(File(keyPath)),
                passphrase = passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            if (hostId != other.hostId) return false
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
            var result = hostId.hashCode()
            result = 31 * result + hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + dir.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /** Bound the synchronous lease release at teardown (#699). */
        private const val LEASE_RELEASE_TIMEOUT_MS: Long = 2_000L

        /** Bound optional probes so loaded commits are not hidden behind them. */
        private const val BEST_EFFORT_PROBE_TIMEOUT_MS: Long = 3_500L

        /** Bound the user-triggered gh create call independently of the screen load. */
        private const val CREATE_ISSUE_TIMEOUT_MS: Long = 15_000L

        /** Cap the history so a giant repo doesn't blow up the listing. */
        const val COMMIT_LIMIT: Int = GitHistoryGateway.DEFAULT_LIMIT

        /** Cap the in-app GitHub issue listing (#649). */
        const val ISSUE_LIMIT: Int = GitHistoryGateway.DEFAULT_ISSUE_LIMIT
    }
}
