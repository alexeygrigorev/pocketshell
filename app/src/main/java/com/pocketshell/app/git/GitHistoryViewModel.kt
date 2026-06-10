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

    /** Commits were read. [commits] are newest-first (git log order). */
    data class Ready(
        override val dir: String,
        val commits: List<GitCommit>,
        /** True when the listing hit the count cap. */
        val truncated: Boolean,
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
 * Backs [GitHistoryScreen] — issue #646.
 *
 * Opens one persistent [SshSession] for the host (mirroring the credentials the
 * live session already holds, passed via [start]) and reads recent commit
 * history for a project directory through [GitHistoryGateway]. Read-only: no
 * write/checkout operations here (branches/worktrees/stats are slice #647).
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
                    GitHistoryUiState.Ready(
                        dir = req.dir,
                        commits = commits,
                        truncated = commits.size >= COMMIT_LIMIT,
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
    }
}
