package com.pocketshell.app.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.repos.RepoEntry
import com.pocketshell.app.repos.RepoPathResult
import com.pocketshell.app.repos.ReposListResult
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.SshLeaseManager
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
import javax.inject.Inject

/**
 * One row in the repos-browse list — issue #230.
 *
 * The list is a join of `pocketshell repos list --remote` (the user's
 * GitHub repositories) and `pocketshell repos list --local` (repos
 * already cloned on the host's disk). A GitHub repo that is also cloned
 * renders as a single [cloned] = true row carrying both its [fullName]
 * and the local [path]; a GitHub repo with no clone renders with
 * [cloned] = false and a null [path]. Cloned-only repos (on disk but no
 * matching GitHub remote, e.g. a private mirror) still surface so the
 * user can open them.
 *
 * @property fullName GitHub `owner/repo` slug — the stable key used for
 *   clone/open RPCs and for joining remote + local rows. Cloned-only
 *   rows that lack a remote use the derived `owner/name` (or just the
 *   folder name) so the row still has a label.
 * @property name short repository name shown as the primary label.
 * @property owner GitHub owner login when known.
 * @property cloned true when a local clone exists for this repo.
 * @property path local clone path when [cloned]; null otherwise.
 * @property defaultBranch remote default branch shown as a subtitle hint.
 * @property updatedAt remote `updated_at` ISO timestamp used for sort.
 */
data class RepoRow(
    val fullName: String,
    val name: String,
    val owner: String?,
    val cloned: Boolean,
    val path: String?,
    val defaultBranch: String?,
    val updatedAt: String?,
)

sealed interface RepoBrowserUiState {
    data object Loading : RepoBrowserUiState

    data class Ready(
        val repos: List<RepoRow>,
        /** `full_name` of the repo whose clone/open RPC is in flight. */
        val pendingFullName: String? = null,
        /** One-shot failure banner text from the last clone/open attempt. */
        val actionError: String? = null,
    ) : RepoBrowserUiState

    data class Failed(val message: String) : RepoBrowserUiState

    data object ToolUnavailable : RepoBrowserUiState
}

/**
 * Backs [RepoBrowserScreen] — issue #230 app-side slice of #205.
 *
 * Responsibilities:
 *
 *  - Open a one-shot [SshSession] from the host's credentials (same
 *    pattern as [WatchedFoldersViewModel.runDiscover]) and run
 *    `pocketshell repos list --remote` + `--local` in one connection.
 *  - Merge the two lists into [RepoRow]s so the screen can tell cloned
 *    repos apart from GitHub-only repos.
 *  - Clone-on-tap: a tap on a not-yet-cloned row runs
 *    `pocketshell repos clone`, optimistically marks the row pending,
 *    and on success resolves the clone path so the caller can open a
 *    session there. Tapping an already-cloned row runs
 *    `pocketshell repos open` to resolve the path without re-cloning.
 *
 * The clone/open RPCs are delegated to [ReposRemoteSource] (the
 * gateway landed with the daemon slice); this view model only owns the
 * connection lifecycle, the merge, and the UI state machine.
 */
@HiltViewModel
class RepoBrowserViewModel @Inject constructor(
    private val reposRemoteSource: ReposRemoteSource,
    // Issue #699: borrow the host's WARM transport from the app-wide
    // @Singleton SshLeaseManager (the SAME instance the live session screens /
    // folder discovery use) instead of dialing a fresh ~3-4s SSH handshake per
    // repo list / clone / open. The lease key is byte-identical to those
    // surfaces (`credentialId = "$hostId:$keyPath"`), so a repo action reuses
    // the pooled connection a warm session already holds.
    private val sshLeaseManager: SshLeaseManager,
) : ViewModel() {

    private val _state: MutableStateFlow<RepoBrowserUiState> =
        MutableStateFlow(RepoBrowserUiState.Loading)
    val state: StateFlow<RepoBrowserUiState> = _state.asStateFlow()

    private var credentials: SshCredentials? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    /**
     * Bind to a host and kick the initial list. Re-binding with the same
     * credentials is a no-op so a recomposition does not blow away the
     * visible list or an in-flight clone.
     */
    fun bind(credentials: SshCredentials) {
        if (this.credentials == credentials) return
        this.credentials = credentials
        refresh()
    }

    /** Re-run the remote + local enumeration. Wired to pull-to-retry. */
    fun refresh() {
        val creds = credentials ?: return
        loadJob?.cancel()
        // Keep the existing list visible on a refresh so the screen does
        // not flash a spinner over a usable list; only show Loading when
        // we have nothing yet.
        if (_state.value !is RepoBrowserUiState.Ready) {
            _state.value = RepoBrowserUiState.Loading
        }
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runLoad(creds) }
            _state.value = result
        }
    }

    /**
     * Tap handler. For a not-yet-cloned repo this clones it; for an
     * already-cloned repo it resolves the existing path. On success
     * [onResolved] fires with the local clone path so the caller can
     * open a session there.
     */
    fun onRepoTapped(row: RepoRow, onResolved: (path: String) -> Unit) {
        val creds = credentials ?: return
        // Ignore taps while an action is already running so a double tap
        // does not fire two clones.
        val ready = _state.value as? RepoBrowserUiState.Ready
        if (ready?.pendingFullName != null) return
        _state.value = (ready ?: RepoBrowserUiState.Ready(emptyList()))
            .copy(pendingFullName = row.fullName, actionError = null)
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runAction(creds, row) }
            when (result) {
                is RepoPathResult.Success -> {
                    onResolved(result.path)
                    // Re-list so a freshly cloned repo flips to an
                    // "Open" row when the user comes back.
                    if (!row.cloned) {
                        refresh()
                    } else {
                        clearPending()
                    }
                }
                RepoPathResult.ToolMissing -> setActionError(
                    "pocketshell is not installed on this host.",
                )
                is RepoPathResult.Failed -> setActionError(
                    (if (row.cloned) "Couldn't open ${row.name}: " else "Couldn't clone ${row.name}: ") +
                        result.reason,
                )
            }
        }
    }

    /** Dismiss the one-shot clone/open failure banner. */
    fun clearActionError() {
        val ready = _state.value as? RepoBrowserUiState.Ready ?: return
        _state.value = ready.copy(actionError = null)
    }

    private fun clearPending() {
        val ready = _state.value as? RepoBrowserUiState.Ready ?: return
        _state.value = ready.copy(pendingFullName = null)
    }

    private fun setActionError(message: String) {
        val ready = _state.value as? RepoBrowserUiState.Ready ?: return
        _state.value = ready.copy(pendingFullName = null, actionError = message)
    }

    private suspend fun runLoad(creds: SshCredentials): RepoBrowserUiState =
        withSession(
            creds,
            // A connect failure surfaces the same "Couldn't reach" banner the
            // raw path showed; an in-block exec error keeps its "ClassName: msg"
            // shape.
            onConnectFail = { RepoBrowserUiState.Failed("Couldn't reach ${creds.hostname}.") },
            onExecFail = { t ->
                RepoBrowserUiState.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
            },
        ) { session ->
            // The remote + local enumeration runs over ONE borrowed lease
            // session, so both `repos list` calls reuse the warm transport.
            val remote = reposRemoteSource.listRemote(session)
            when {
                remote is ReposListResult.ToolMissing -> RepoBrowserUiState.ToolUnavailable
                remote is ReposListResult.Failed -> RepoBrowserUiState.Failed(remote.reason)
                else -> {
                    val local = reposRemoteSource.listLocal(session)
                    if (local is ReposListResult.ToolMissing) {
                        RepoBrowserUiState.ToolUnavailable
                    } else {
                        // A local-scan failure degrades gracefully — we still
                        // show the remote list, just without cloned-state for
                        // any repo whose clone the scan would have found.
                        val remoteRepos = (remote as ReposListResult.Success).repos
                        val localRepos = (local as? ReposListResult.Success)?.repos.orEmpty()
                        RepoBrowserUiState.Ready(repos = mergeRepos(remoteRepos, localRepos))
                    }
                }
            }
        }

    private suspend fun runAction(creds: SshCredentials, row: RepoRow): RepoPathResult =
        withSession(
            creds,
            onConnectFail = { RepoPathResult.Failed("Couldn't reach ${creds.hostname}.") },
            onExecFail = { t ->
                RepoPathResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
            },
        ) { session ->
            if (row.cloned) {
                reposRemoteSource.open(session, row.fullName)
            } else {
                reposRemoteSource.clone(session, row.fullName, root = creds.cloneRoot)
            }
        }

    /**
     * Issue #699: borrow the host's WARM transport from the app-wide
     * [SshLeaseManager] (reference-counted, released — never closed — when
     * [block] returns) instead of dialing a fresh [com.pocketshell.core.ssh.SshConnection]
     * per repo action. The lease key is byte-identical to the session screens'
     * / folder discovery's, so a repo list / clone / open reuses the same
     * pooled connection the user's session holds.
     *
     * [block] runs WITHOUT a swallowing try/catch so a stale-channel symptom
     * propagates into [LeaseSessionExec], which evicts the poisoned transport
     * and retries ONCE on a fresh lease. The final failure is then split:
     * [onConnectFail] for a lease-acquire (connect) failure, [onExecFail] for
     * an in-block exec error — preserving the raw path's distinct messages.
     */
    private suspend fun <T> withSession(
        creds: SshCredentials,
        onConnectFail: () -> T,
        onExecFail: (Throwable) -> T,
        block: suspend (SshSession) -> T,
    ): T {
        val target = LeaseSessionTarget(
            hostId = creds.hostId,
            hostname = creds.hostname,
            port = creds.port,
            username = creds.username,
            keyPath = creds.keyPath,
            passphrase = creds.passphrase,
        )
        var blockEntered = false
        val result = LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = target,
        ) { session ->
            blockEntered = true
            block(session)
        }
        return result.getOrElse { t ->
            // A failure after the block ran is an exec error; a failure before
            // it ran is a lease-acquire (connect) failure.
            if (blockEntered) onExecFail(t) else onConnectFail()
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        actionJob?.cancel()
    }

    data class SshCredentials(
        // Issue #699: the host id is required to build the lease key
        // (`credentialId = "$hostId:$keyPath"`) byte-identically to the live
        // session screens / folder discovery so a repo action reuses the same
        // warm transport.
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val cloneRoot: String = "~/git",
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SshCredentials) return false
            if (hostId != other.hostId) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
            if (cloneRoot != other.cloneRoot) return false
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
            result = 31 * result + cloneRoot.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /**
         * Stable join key for a repo entry: prefer the `full_name`
         * (`owner/repo`); fall back to `owner/name`, then bare `name`.
         * Both the remote and local scans emit `name`, so the bare-name
         * fallback guarantees every row keys to something even when the
         * unified schema's `full_name` is absent (older local scans).
         */
        internal fun joinKey(entry: RepoEntry): String {
            entry.fullName?.takeIf { it.isNotBlank() }?.let { return it }
            val owner = entry.owner?.takeIf { it.isNotBlank() }
            return if (owner != null) "$owner/${entry.name}" else entry.name
        }

        /**
         * Pure merge of the remote (GitHub) and local (cloned) repo
         * lists into display rows — visible-for-test so the join can be
         * driven without an SSH session.
         *
         * Rules:
         *
         *  - Every GitHub repo becomes a row. If a local clone shares
         *    its join key, the row is marked cloned with the clone path.
         *  - A local clone with no matching GitHub repo (private mirror,
         *    repo cloned outside the owner's account) still surfaces as a
         *    cloned-only row so the user can open it.
         *  - Rows sort cloned-first (cloned repos at the top, the user's
         *    active workspaces), then by remote `updated_at` descending,
         *    then by name for a stable order.
         */
        internal fun mergeRepos(
            remote: List<RepoEntry>,
            local: List<RepoEntry>,
        ): List<RepoRow> {
            val localByKey = local.associateBy(::joinKey)
            val seen = mutableSetOf<String>()
            val rows = mutableListOf<RepoRow>()

            for (entry in remote) {
                val key = joinKey(entry)
                seen += key
                val localMatch = localByKey[key]
                rows += RepoRow(
                    fullName = key,
                    name = entry.name,
                    owner = entry.owner,
                    cloned = localMatch?.local != null,
                    path = localMatch?.local?.path,
                    defaultBranch = entry.remote?.defaultBranch,
                    updatedAt = entry.remote?.updatedAt,
                )
            }

            // Cloned-only repos with no GitHub remote in the list.
            for (entry in local) {
                val key = joinKey(entry)
                if (key in seen) continue
                if (entry.local == null) continue
                seen += key
                rows += RepoRow(
                    fullName = key,
                    name = entry.name,
                    owner = entry.owner,
                    cloned = true,
                    path = entry.local.path,
                    defaultBranch = entry.remote?.defaultBranch,
                    updatedAt = entry.remote?.updatedAt,
                )
            }

            return rows.sortedWith(
                compareByDescending<RepoRow> { it.cloned }
                    .thenByDescending { it.updatedAt ?: "" }
                    .thenBy { it.name.lowercase() },
            )
        }
    }
}
