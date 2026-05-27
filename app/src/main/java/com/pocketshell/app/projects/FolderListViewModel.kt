package com.pocketshell.app.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One folder row in the folder list — issue #171.
 *
 * Folders are derived (auto-discovered from each session's
 * `pane_current_path` / `session_path`) and optionally enriched by the
 * user's per-host [ProjectRootEntity] overlay so a watched folder with
 * zero active sessions still appears as a row. Tap actions diverge
 * depending on whether the folder has any active sessions (see the
 * spike's locked decision in Section 3).
 *
 * @property path canonical folder path used as the grouping key
 *   (e.g. `/home/alexey/git/pocketshell`).
 * @property label user-visible label. Watched-folder rows use the
 *   [ProjectRootEntity.label] (with the order prefix stripped); pure
 *   auto-discovered rows fall back to the trailing path segment.
 * @property sessions the folder's active sessions, sorted by recency
 *   descending. Empty when the folder is a watched-folder overlay with
 *   no live sessions.
 * @property isWatched true when the folder is also a
 *   [ProjectRootEntity] for the host — drives a visual "pinned" hint
 *   so the user can tell auto-discovered folders apart from explicit
 *   pins.
 */
data class FolderRow(
    val path: String,
    val label: String,
    val sessions: List<FolderSessionEntry>,
    val isWatched: Boolean,
) {
    /** True when the folder has zero active tmux sessions today. */
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Most-recent activity across [sessions] — used for sort ordering. */
    val mostRecentActivity: Long get() = sessions.maxOfOrNull { it.lastActivity ?: 0L } ?: 0L
}

/**
 * One session inside a [FolderRow]. Carries the minimum fields the
 * folder detail screen needs to render a `SessionRow` and route the
 * tap to `AppDestination.TmuxSession`.
 */
data class FolderSessionEntry(
    val sessionName: String,
    val lastActivity: Long?,
    val attached: Boolean,
    val agentKind: SessionAgentKind,
)

sealed interface FolderListUiState {
    data object Loading : FolderListUiState

    data class Ready(
        val folders: List<FolderRow>,
        val flatSessions: List<FolderSessionEntry>,
    ) : FolderListUiState

    data class Failed(val message: String) : FolderListUiState

    data class ConnectError(val message: String, val cause: Throwable) : FolderListUiState

    data object ToolUnavailable : FolderListUiState
}

/**
 * Backs [FolderListScreen] — issue #171.
 *
 * The view model owns:
 *
 *  - One `tmux list-sessions` + `list-panes -a` probe per [bind] call.
 *    Continuous polling kicks off on bind so the badge transitions
 *    LIVE as an agent starts inside a shell session — per the
 *    refinement-comment AC "Badge transitions live as the underlying
 *    state changes (agent starts → badge updates within the existing
 *    detection latency)".
 *  - The Flow over [ProjectRootDao.getByHostId] — watched folders the
 *    user pinned in #206 overlay onto the auto-discovered set so a
 *    folder with zero active sessions still appears as a row.
 *  - Folder grouping. Sessions group by canonicalised `cwd`; sessions
 *    whose cwd is null fall under [UNTRACKED_PATH].
 *
 * No DB schema change. The view model is a Hilt-managed read-only
 * orchestrator over existing DAOs + a fresh ssh-exec probe.
 */
@HiltViewModel
class FolderListViewModel @Inject constructor(
    private val gateway: FolderListGateway,
    private val hostDao: HostDao,
    private val projectRootDao: ProjectRootDao,
) : ViewModel() {

    private val _state: MutableStateFlow<FolderListUiState> =
        MutableStateFlow(FolderListUiState.Loading)
    val state: StateFlow<FolderListUiState> = _state.asStateFlow()

    private var bound: BoundParams? = null
    private var watchedFoldersJob: Job? = null
    private var pollingJob: Job? = null
    private var lastSessions: List<FolderSessionEntry> = emptyList()
    private var lastSessionFolderPaths: Map<String, String> = emptyMap()
    private var lastWatchedFolders: List<ProjectRootEntity> = emptyList()

    /**
     * Bind to a host and kick a one-shot probe. Re-calling with the same
     * id is a no-op so a recomposition doesn't blow away the visible
     * state.
     *
     * The SSH credentials are required because the gateway opens a
     * fresh `SshConnection` for the probe — the folder screen sits
     * upstream of the per-host `tmux -CC` client in the navigation
     * graph, so we can't reuse an already-attached `TmuxClient`.
     */
    fun bind(
        hostId: Long,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        passphrase: CharArray?,
    ) {
        val params = BoundParams(
            hostId = hostId,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )
        if (bound == params) return
        bound = params

        watchedFoldersJob?.cancel()
        watchedFoldersJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { rows ->
                lastWatchedFolders = rows
                // Re-emit so a watched-folder write that lands after
                // the initial probe surfaces immediately.
                emitReady()
            }
        }
        startPolling()
    }

    /**
     * Re-run the gateway probe NOW. Wired to the screen's pull-to-refresh
     * affordance, the retry button on the error panel, and the
     * post-create-session refresh after the
     * [SessionTypePickerSheet] returns.
     */
    fun refresh() {
        // Cancel the in-flight poll cycle and restart so the new probe
        // runs immediately rather than waiting for the next tick.
        startPolling()
    }

    /**
     * Create a new tmux session in [cwd] and optionally auto-launch
     * [startCommand] inside it via `send-keys` — invoked from the
     * [SessionTypePickerSheet] confirm path.
     *
     * On success [onResolved] fires with the resolved tmux session name
     * so the caller can route to `AppDestination.TmuxSession`. On
     * failure the screen falls back to the Failed state with the error
     * message (so the user sees what went wrong instead of a silent
     * "nothing happened").
     */
    fun createSession(
        sessionName: String,
        cwd: String,
        startCommand: String?,
        onResolved: (sessionName: String) -> Unit,
    ) {
        val params = bound ?: return
        viewModelScope.launch {
            val host = withContext(Dispatchers.IO) { hostDao.getById(params.hostId) } ?: run {
                _state.value = FolderListUiState.Failed("Host not found.")
                return@launch
            }
            val result = gateway.createSession(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                sessionName = sessionName,
                cwd = cwd,
                startCommand = startCommand,
            )
            result.fold(
                onSuccess = { resolvedName ->
                    onResolved(resolvedName)
                    refresh()
                },
                onFailure = { error ->
                    _state.value = FolderListUiState.Failed(
                        "Couldn't create session: ${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun startPolling() {
        val params = bound ?: return
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // Surface loading state on the very first cycle when we have
            // nothing to show yet; subsequent cycles keep the previous
            // snapshot visible (so the badge update is a single Compose
            // recomposition, not a loading flash).
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = FolderListUiState.Loading
            }
            while (isActive) {
                runProbe(params)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the background poll cycle — wired to the screen's
     * `DisposableEffect.onDispose` so navigating away (e.g. tapping a
     * session row → TmuxSessionScreen) frees the SSH probe channel
     * for the next destination immediately rather than waiting for
     * Hilt's ViewModelStore cleanup.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun runProbe(params: BoundParams) {
        val host = withContext(Dispatchers.IO) { hostDao.getById(params.hostId) } ?: run {
            _state.value = FolderListUiState.Failed("Host not found.")
            return
        }
        val result = gateway.listSessionsWithFolder(
            host = host,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
        )
        when (result) {
            is FolderListResult.Sessions -> {
                lastSessions = result.rows.map { row ->
                    FolderSessionEntry(
                        sessionName = row.sessionName,
                        lastActivity = row.lastActivity,
                        attached = row.attached,
                        agentKind = row.agentKind,
                    )
                }
                lastSessionFolderPaths = result.rows.associate { row ->
                    row.sessionName to (row.cwd?.let(::canonicalisePath) ?: UNTRACKED_PATH)
                }
                emitReady()
            }
            is FolderListResult.Failed -> {
                _state.value = FolderListUiState.Failed(result.message)
            }
            is FolderListResult.ConnectFailed -> {
                _state.value = FolderListUiState.ConnectError(
                    message = result.cause.message ?: "Connection failed",
                    cause = result.cause,
                )
            }
            FolderListResult.ToolUnavailable -> {
                _state.value = FolderListUiState.ToolUnavailable
            }
        }
    }

    /**
     * Rebuild [FolderListUiState.Ready] from the most recent gateway
     * snapshot + watched-folder overlay. Idempotent: every change in
     * either input re-runs the grouping.
     */
    private fun emitReady() {
        if (bound == null) return
        val folders = groupSessionsIntoFolders(
            sessions = lastSessions,
            sessionFolderPaths = lastSessionFolderPaths,
            watchedFolders = lastWatchedFolders,
        )
        _state.value = FolderListUiState.Ready(
            folders = folders,
            flatSessions = lastSessions.sortedWith(
                compareByDescending<FolderSessionEntry> { it.lastActivity ?: 0L }
                    .thenBy { it.sessionName },
            ),
        )
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        watchedFoldersJob?.cancel()
    }

    private data class BoundParams(
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BoundParams) return false
            if (hostId != other.hostId) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
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
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /**
         * Synthetic group used for sessions whose `pane_current_path` /
         * `session_path` are both unknown. Surfaced visually as an
         * "Untracked" row at the bottom of the folder list so the
         * sessions are still reachable. Distinct sentinel string so
         * the grouping logic treats it as a real key without colliding
         * with any real filesystem path.
         */
        const val UNTRACKED_PATH: String = "::untracked::"
        const val UNTRACKED_LABEL: String = "Untracked"

        /**
         * Polling cadence for the gateway probe. The folder list is a
         * leaf surface the user dwells on for at most a few seconds
         * before drilling further; 5 s is short enough that an agent
         * promotion (shell → claude) registers before the user moves
         * on, and long enough to not hammer the SSH server. Matches
         * the spike Section 6 detection-latency note ("~500 ms SSH
         * exec; user sees the badge update shortly after agent
         * startup").
         */
        const val POLL_INTERVAL_MS: Long = 5_000L

        /**
         * Maximum time the polling coroutine spends inside `delay()`
         * before yielding so the Compose test idling registry can
         * settle. The poll cadence is split into 100 ms ticks so a
         * `compose.waitUntil` doesn't block on a 5-second `delay`.
         */
        private const val POLL_TICK_MS: Long = 100L

        /**
         * Canonicalise a `pane_current_path` / `session_path` value
         * into a stable grouping key.
         *
         *  - Trailing slashes are removed (so `/home/foo/` and
         *    `/home/foo` collapse to one folder).
         *  - A blank value collapses to [UNTRACKED_PATH].
         *  - Otherwise the value is returned verbatim — we deliberately
         *    do NOT expand `~` to the user's home, because both tmux's
         *    `session_path` and `pane_current_path` already report
         *    absolute paths after process resolution.
         */
        fun canonicalisePath(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return UNTRACKED_PATH
            val stripped = trimmed.trimEnd('/')
            return stripped.ifEmpty { "/" }
        }

        /**
         * Derive a user-visible label from a canonicalised path: the
         * trailing path component (`/home/alexey/git/pocketshell` →
         * `pocketshell`). Falls back to the whole path for short / root
         * paths that don't have a meaningful trailing segment.
         */
        fun defaultLabelForPath(path: String): String {
            if (path == UNTRACKED_PATH) return UNTRACKED_LABEL
            val tail = path.substringAfterLast('/')
            return tail.ifBlank { path }
        }

        /**
         * Pure folder-grouping function — visible-for-test so the unit
         * suite can drive the grouping without spinning up the
         * gateway or DAO.
         *
         * Inputs:
         *  - [sessions]: every active session reported by the gateway
         *    probe (already classified per agent kind by the gateway).
         *  - [sessionFolderPaths]: map from session name to the
         *    canonicalised folder path (`pane_current_path`-primary,
         *    `session_path`-fallback). Sessions absent from this map
         *    are routed to [UNTRACKED_PATH].
         *  - [watchedFolders]: the host's
         *    [ProjectRootEntity] overlay. A watched folder with zero
         *    matching sessions still appears as an [FolderRow.isEmpty]
         *    row so the user sees their pin.
         *
         * Output: folder rows sorted by activity recency descending,
         * with watched-but-empty rows after the active set and the
         * [UNTRACKED_PATH] row (if any) last.
         */
        fun groupSessionsIntoFolders(
            sessions: List<FolderSessionEntry>,
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
        ): List<FolderRow> {
            val watchedByPath = watchedFolders
                .associate { canonicalisePath(it.path) to it }
            val groupedSessions: Map<String, List<FolderSessionEntry>> = sessions
                .groupBy { sessionFolderPaths[it.sessionName] ?: UNTRACKED_PATH }
                .mapValues { (_, list) ->
                    list.sortedWith(
                        compareByDescending<FolderSessionEntry> { it.lastActivity ?: 0L }
                            .thenBy { it.sessionName },
                    )
                }

            val allPaths = groupedSessions.keys + watchedByPath.keys
            val rows = allPaths.map { path ->
                val matching = groupedSessions[path].orEmpty()
                val watched = watchedByPath[path]
                val label = when {
                    watched != null ->
                        com.pocketshell.app.projects.WatchedFoldersViewModel
                            .stripOrderPrefix(watched.label)
                            .ifBlank { defaultLabelForPath(path) }
                    else -> defaultLabelForPath(path)
                }
                FolderRow(
                    path = path,
                    label = label,
                    sessions = matching,
                    isWatched = watched != null,
                )
            }

            // Partition into active / empty-watched / untracked so we
            // can apply distinct sort rules per bucket.
            val active = rows.filter { it.sessions.isNotEmpty() && it.path != UNTRACKED_PATH }
                .sortedWith(
                    compareByDescending<FolderRow> { it.mostRecentActivity }
                        .thenBy { it.label.lowercase() },
                )
            val watchedEmpty = rows.filter { it.sessions.isEmpty() && it.path != UNTRACKED_PATH }
                .sortedBy { it.label.lowercase() }
            val untracked = rows.filter { it.path == UNTRACKED_PATH }
            return active + watchedEmpty + untracked
        }
    }
}
