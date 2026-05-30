package com.pocketshell.app.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.assistant.AppAssistantActions
import com.pocketshell.app.assistant.AssistantActions
import com.pocketshell.app.assistant.AssistantInstallId
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.ExecutorTraceSink
import com.pocketshell.app.assistant.RealAssistantSshExecutor
import com.pocketshell.app.assistant.SessionActionBridge
import com.pocketshell.app.assistant.SessionAssistantController
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.portfwd.ForwardingHostSnapshot
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.WarmSshConnections
import com.pocketshell.app.sessions.WarmSshTarget
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * One watched parent root in the host-detail tree. Each root owns the
 * project folders discovered under it by `pocketshell repos list --local
 * --root <path>` plus any live session folders that fall under that root.
 */
data class FolderTreeRoot(
    val path: String,
    val label: String,
    val folders: List<FolderRow>,
    val isWatched: Boolean,
    val addSheetProjects: List<RootProjectCandidate> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty()
    val mostRecentActivity: Long get() = folders.maxOfOrNull { it.mostRecentActivity } ?: 0L
    val displayPath: String? get() = path.takeUnless { it == FolderListViewModel.OTHER_ROOT_PATH }
}

data class RootProjectCandidate(
    val path: String,
    val label: String,
    val source: RootProjectSource,
    val activeSessionCount: Int,
) {
    val isActive: Boolean get() = activeSessionCount > 0
}

enum class RootProjectSource { Active, History, Scanned }

data class HostDiscoveredPort(
    val remotePort: Int,
    val process: String,
    val status: HostPortForwardingPortStatus = HostPortForwardingPortStatus.DISCOVERED,
    val discovered: Boolean = true,
)

enum class HostPortForwardingPortStatus {
    DISCOVERED,
    FORWARDING,
}

data class HostPortForwardingSummary(
    val discoveredPorts: List<HostDiscoveredPort> = emptyList(),
    val active: Boolean = false,
    val activeTunnelCount: Int = 0,
) {
    val discoveredCount: Int
        get() = discoveredPorts.count { it.discovered }
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
        val treeRoots: List<FolderTreeRoot>,
        val flatSessions: List<FolderSessionEntry>,
        val expandedProjectPaths: Set<String>,
        val portForwarding: HostPortForwardingSummary = HostPortForwardingSummary(),
    ) : FolderListUiState

    data class Failed(val message: String) : FolderListUiState

    data class ConnectError(val message: String, val cause: Throwable) : FolderListUiState

    data object ToolUnavailable : FolderListUiState
}

sealed interface FolderActionStatus {
    data object Idle : FolderActionStatus
    data class Running(val label: String) : FolderActionStatus
    data class Succeeded(val message: String) : FolderActionStatus
    data class Failed(val message: String) : FolderActionStatus
}

/**
 * Backs [FolderListScreen] — issue #171.
 *
 * The view model owns:
 *
 *  - One `tmux list-sessions` + `list-panes -a` probe per [bind] call.
 *    Continuous polling kicks off on bind so the agent classifier chip
 *    transitions LIVE as an agent starts inside a shell session.
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
    private val warmSshConnections: WarmSshConnections = WarmSshConnections(),
    @ApplicationContext private val applicationContext: Context? = null,
    private val assistantClientFactory: AssistantLlmClientFactory? = null,
    private val reposRemoteSource: ReposRemoteSource? = null,
    private val forwardingController: ForwardingController,
) : ViewModel() {

    private val _state: MutableStateFlow<FolderListUiState> =
        MutableStateFlow(FolderListUiState.Loading)
    val state: StateFlow<FolderListUiState> = _state.asStateFlow()

    private val _actionStatus: MutableStateFlow<FolderActionStatus> =
        MutableStateFlow(FolderActionStatus.Idle)
    val actionStatus: StateFlow<FolderActionStatus> = _actionStatus.asStateFlow()

    private val assistant: SessionAssistantController =
        SessionAssistantController(scope = viewModelScope, sessionFactory = ::buildAssistantDeps)
    internal val assistantState: StateFlow<AssistantUiState> = assistant.state

    private val _assistantNavRequests: MutableSharedFlow<AppDestination> = MutableSharedFlow(extraBufferCapacity = 1)
    val assistantNavRequests: SharedFlow<AppDestination> = _assistantNavRequests.asSharedFlow()

    private var assistantSshExecutor: AssistantSshExecutor = RealAssistantSshExecutor()

    private var bound: BoundParams? = null
    private var warmJob: Job? = null
    private var warmReleaseJob: Job? = null
    private var watchedFoldersJob: Job? = null
    private var pollingJob: Job? = null
    private var lastSessions: List<FolderSessionEntry> = emptyList()
    private var lastSessionFolderPaths: Map<String, String> = emptyMap()
    private var lastWatchedFolders: List<ProjectRootEntity> = emptyList()
    private var lastScannedProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var lastHistoryProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var lastResolvedWatchedRootPaths: Map<String, String> = emptyMap()
    private var lastCreatedFolders: Map<String, String> = emptyMap()
    private var expandedProjectPaths: Set<String> = emptySet()
    private var lastDiscoveredPorts: List<HostDiscoveredPort> = emptyList()
    private var forwardingSnapshots: Map<Long, ForwardingHostSnapshot> = emptyMap()

    init {
        viewModelScope.launch {
            forwardingController.flowOfHostSnapshots().collectLatest { snapshots ->
                forwardingSnapshots = snapshots
                emitReady()
            }
        }
    }

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
        hostName: String,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        passphrase: CharArray?,
    ) {
        val params = BoundParams(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        if (bound == params) {
            if (pollingJob == null) startPolling()
            return
        }
        bound = params

        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            warmSshConnections.warm(
                target = params.toWarmSshTarget(),
                passphrase = params.passphrase,
            )
        }
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

    fun createEmptyProject(parentPath: String, folderName: String) {
        val params = bound ?: return
        viewModelScope.launch {
            _actionStatus.value = FolderActionStatus.Running("Creating $folderName")
            val host = withContext(Dispatchers.IO) { hostDao.getById(params.hostId) } ?: run {
                _actionStatus.value = FolderActionStatus.Failed("Host not found.")
                return@launch
            }
            val result = gateway.createEmptyProject(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                parentPath = parentPath,
                folderName = folderName,
            )
            result.fold(
                onSuccess = { path ->
                    lastCreatedFolders = lastCreatedFolders + (canonicalisePath(path) to defaultLabelForPath(path))
                    emitReady()
                    _actionStatus.value = FolderActionStatus.Succeeded("Created $path")
                    refresh()
                },
                onFailure = { error ->
                    _actionStatus.value = FolderActionStatus.Failed(
                        "Couldn't create project: ${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    fun importFileIntoFolder(folderPath: String, payload: FolderImportPayload) {
        val params = bound ?: return
        viewModelScope.launch {
            _actionStatus.value = FolderActionStatus.Running("Importing ${payload.remoteName}")
            val host = withContext(Dispatchers.IO) { hostDao.getById(params.hostId) } ?: run {
                _actionStatus.value = FolderActionStatus.Failed("Host not found.")
                return@launch
            }
            val result = gateway.importFile(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                folderPath = folderPath,
                payload = payload,
            )
            result.fold(
                onSuccess = { remotePath ->
                    lastCreatedFolders = lastCreatedFolders +
                        (canonicalisePath(folderPath) to defaultLabelForPath(folderPath))
                    emitReady()
                    _actionStatus.value = FolderActionStatus.Succeeded("Imported $remotePath")
                },
                onFailure = { error ->
                    _actionStatus.value = FolderActionStatus.Failed(
                        "Couldn't import file: ${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    fun clearActionStatus() {
        _actionStatus.value = FolderActionStatus.Idle
    }

    fun toggleProjectExpanded(projectPath: String) {
        expandedProjectPaths = toggleProjectExpansion(expandedProjectPaths, canonicalisePath(projectPath))
        emitReady()
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAssistantSshExecutor(executor: AssistantSshExecutor) {
        assistantSshExecutor = executor
    }

    fun startAssistant(prompt: String) = assistant.start(prompt)

    fun confirmAssistantAction() = assistant.confirm()

    fun correctAssistantAction(correction: String) = assistant.correct(correction)

    fun cancelAssistantAction() = assistant.cancel()

    fun dismissAssistant() = assistant.dismiss()

    private fun buildAssistantDeps(): SessionAssistantController.AssistantRunDeps? {
        val context = applicationContext ?: return null
        val client = assistantClientFactory?.create() ?: return null
        val repos = reposRemoteSource ?: return null
        val params = activeAssistantParams() ?: return null

        val bridge = object : SessionActionBridge {
            override fun activeHostName(): String? = params.hostName
            override fun activeCwd(): String? = null
            override fun activeSessionName(): String? = null
            override fun currentScreenLabel(): String = "host detail for ${params.hostName}"
            override fun sendCommand(command: String) = Unit
            override fun navigate(destination: AppDestination) {
                _assistantNavRequests.tryEmit(destination)
            }
        }

        val actions: AssistantActions = AppAssistantActions(
            bridge = bridge,
            hostDao = hostDao,
            folderListGateway = gateway,
            reposRemoteSource = repos,
            sshExecutor = assistantSshExecutor,
            resolveParams = { name ->
                params.takeIf {
                    name.isBlank() ||
                        name == it.hostName ||
                        name == it.hostname
                }
            },
            activeParams = ::activeAssistantParams,
            extraContext = ::hostDetailAssistantContext,
            onProjectCreated = ::recordAssistantCreatedProject,
        )

        return SessionAssistantController.AssistantRunDeps(
            client = client,
            actions = actions,
            traceSink = ExecutorTraceSink(assistantSshExecutor, ::activeAssistantParams),
            installId = AssistantInstallId.get(context),
            sessionId = null,
        )
    }

    private fun activeAssistantParams(): AssistantSshParams? {
        val params = bound ?: return null
        return AssistantSshParams(
            hostId = params.hostId,
            hostName = params.hostName,
            hostname = params.hostname,
            port = params.port,
            username = params.username,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
        )
    }

    private fun hostDetailAssistantContext(): String = buildString {
        appendLine("workspace_roots:")
        val roots = (state.value as? FolderListUiState.Ready)?.treeRoots.orEmpty()
        if (roots.isEmpty()) {
            lastWatchedFolders.forEach { appendLine("- ${it.label}: ${it.path}") }
        } else {
            roots.forEach { root ->
                append("- ${root.label}")
                root.displayPath?.let { append(": $it") }
                appendLine()
                root.folders.take(8).forEach { folder ->
                    appendLine("  - ${folder.label}: ${folder.path} (${folder.sessions.size} sessions)")
                }
            }
        }
        appendLine("known_sessions:")
        lastSessions.take(12).forEach { appendLine("- ${it.sessionName}") }
    }.trim()

    private fun recordAssistantCreatedProject(path: String) {
        val canonical = canonicalisePath(path)
        lastCreatedFolders = lastCreatedFolders + (canonical to defaultLabelForPath(canonical))
        emitReady()
        refresh()
    }

    private fun startPolling() {
        val params = bound ?: return
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // Surface loading state on the very first cycle when we have
            // nothing to show yet; subsequent cycles keep the previous
            // snapshot visible (so classifier-chip updates are a single
            // Compose recomposition, not a loading flash).
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
        scheduleWarmRelease()
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
            watchedRoots = lastWatchedFolders,
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
                lastScannedProjectFoldersByRoot = result.projectFoldersByRoot
                lastHistoryProjectFoldersByRoot = result.historyProjectFoldersByRoot
                lastResolvedWatchedRootPaths = result.resolvedWatchedRootPaths
                lastDiscoveredPorts = result.discoveredPorts.map { port ->
                    HostDiscoveredPort(
                        remotePort = port.port,
                        process = port.processName,
                    )
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
            extraFolders = lastCreatedFolders,
        )
        val treeRoots = buildFolderTree(
            sessions = lastSessions,
            sessionFolderPaths = lastSessionFolderPaths,
            watchedFolders = lastWatchedFolders,
            scannedProjectFoldersByRoot = lastScannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = lastHistoryProjectFoldersByRoot,
            resolvedWatchedRootPaths = lastResolvedWatchedRootPaths,
            extraFolders = lastCreatedFolders,
        )
        val visibleProjectPaths = treeRoots
            .flatMap { root -> root.folders }
            .map { folder -> folder.path }
            .toSet()
        expandedProjectPaths = expandedProjectPaths.intersect(visibleProjectPaths)
        _state.value = FolderListUiState.Ready(
            folders = folders,
            treeRoots = treeRoots,
            flatSessions = lastSessions.sortedWith(
                compareByDescending<FolderSessionEntry> { it.lastActivity ?: 0L }
                    .thenBy { it.sessionName },
            ),
            expandedProjectPaths = expandedProjectPaths,
            portForwarding = forwardingSummary(),
        )
    }

    private fun forwardingSummary(): HostPortForwardingSummary {
        val hostId = bound?.hostId ?: return HostPortForwardingSummary(discoveredPorts = lastDiscoveredPorts)
        val snapshot = forwardingSnapshots[hostId]
        return HostPortForwardingSummary(
            discoveredPorts = mergeForwardingPortRows(
                discoveredPorts = lastDiscoveredPorts,
                activeRemotePorts = snapshot?.activeRemotePorts.orEmpty(),
            ),
            active = snapshot?.active == true,
            activeTunnelCount = snapshot?.tunnelCount ?: 0,
        )
    }

    override fun onCleared() {
        pollingJob?.cancel()
        warmJob?.cancel()
        warmReleaseJob?.cancel()
        bound?.let { params ->
            warmSshConnections.closeIfIdle(params.toWarmSshTarget())
        }
        watchedFoldersJob?.cancel()
        super.onCleared()
    }

    private data class BoundParams(
        val hostId: Long,
        val hostName: String,
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
            if (hostName != other.hostName) return false
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
            result = 31 * result + hostName.hashCode()
            result = 31 * result + hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }

        fun toWarmSshTarget(): WarmSshTarget =
            WarmSshTarget(
                hostId = hostId,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
            )
    }

    private fun scheduleWarmRelease() {
        val params = bound ?: return
        warmReleaseJob?.cancel()
        warmReleaseJob = viewModelScope.launch {
            delay(WARM_RELEASE_DELAY_MS)
            warmSshConnections.close(params.toWarmSshTarget())
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
        const val OTHER_ROOT_PATH: String = "::other-folders::"
        const val OTHER_ROOT_LABEL: String = "Other folders"

        /**
         * Polling cadence for the gateway probe. The folder list is a
         * leaf surface the user dwells on for at most a few seconds
         * before drilling further; 5 s is short enough that an agent
         * promotion (shell → claude) registers before the user moves
         * on, and long enough to not hammer the SSH server. Matches
         * the spike Section 6 detection-latency note ("~500 ms SSH
         * exec; user sees the classifier chip update shortly after agent
         * startup").
         */
        const val POLL_INTERVAL_MS: Long = 5_000L
        const val WARM_RELEASE_DELAY_MS: Long = 10_000L

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
            extraFolders: Map<String, String> = emptyMap(),
        ): List<FolderRow> {
            val watchedByPath = watchedFolders
                .associate { canonicalisePath(it.path) to it }
            val extraByPath = extraFolders
                .mapKeys { (path, _) -> canonicalisePath(path) }
            val groupedSessions: Map<String, List<FolderSessionEntry>> = sessions
                .groupBy { sessionFolderPaths[it.sessionName] ?: UNTRACKED_PATH }
                .mapValues { (_, list) ->
                    list.sortedWith(
                        compareByDescending<FolderSessionEntry> { it.lastActivity ?: 0L }
                            .thenBy { it.sessionName },
                    )
                }

            val allPaths = groupedSessions.keys + watchedByPath.keys + extraByPath.keys
            val rows = allPaths.map { path ->
                val matching = groupedSessions[path].orEmpty()
                val watched = watchedByPath[path]
                val label = when {
                    watched != null ->
                        com.pocketshell.app.projects.WatchedFoldersViewModel
                            .stripOrderPrefix(watched.label)
                            .ifBlank { defaultLabelForPath(path) }
                    extraByPath[path] != null -> extraByPath.getValue(path)
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

        fun buildFolderTree(
            sessions: List<FolderSessionEntry>,
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
            scannedProjectFoldersByRoot: Map<String, List<String>>,
            historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
            resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
            extraFolders: Map<String, String> = emptyMap(),
        ): List<FolderTreeRoot> {
            val resolvedByWatchedPath = resolvedWatchedRootPaths
                .mapKeys { (path, _) -> canonicalisePath(path) }
                .mapValues { (_, path) -> canonicalisePath(path) }
            val watchedRoots = watchedFolders
                .map { root ->
                    val path = canonicalisePath(root.path)
                    val matchPath = resolvedByWatchedPath[path]
                        ?.takeIf { it != UNTRACKED_PATH }
                        ?: path
                    val label = WatchedFoldersViewModel
                        .stripOrderPrefix(root.label)
                        .ifBlank { defaultLabelForPath(path) }
                    WatchedRoot(path = path, matchPath = matchPath, label = label)
                }
                .distinctBy { it.path }

            val sessionProjectPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
            val otherSessionPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
            for (session in sessions) {
                val cwd = sessionFolderPaths[session.sessionName] ?: UNTRACKED_PATH
                val root = bestRootForPath(cwd, watchedRoots)
                val projectPath = root?.let { projectPathUnderRoot(cwd, it.matchPath) }
                val target = if (projectPath != null) sessionProjectPaths else otherSessionPaths
                val key = projectPath ?: cwd
                target.getOrPut(key) { mutableListOf() }.add(session)
            }

            val extraByPath = extraFolders
                .mapKeys { (path, _) -> canonicalisePath(path) }
            val treeRoots = watchedRoots.map { root ->
                val scanned = scannedProjectFoldersByRoot[root.path].orEmpty() +
                    scannedProjectFoldersByRoot[root.matchPath].orEmpty() +
                    scannedProjectFoldersByRoot.entries
                        .firstOrNull {
                            val key = canonicalisePath(it.key)
                            key == root.path || key == root.matchPath
                        }
                        ?.value
                        .orEmpty()
                val scannedPaths = scanned
                    .map(::canonicalisePath)
                    .filter { pathWithinRoot(it, root.matchPath) }
                val extraPaths = extraByPath.keys.filter { pathWithinRoot(it, root.matchPath) }
                val sessionPaths = sessionProjectPaths.keys.filter { pathWithinRoot(it, root.matchPath) }
                val historyPaths = historyProjectFoldersByRoot[root.path].orEmpty() +
                    historyProjectFoldersByRoot[root.matchPath].orEmpty() +
                    historyProjectFoldersByRoot.entries
                        .firstOrNull {
                            val key = canonicalisePath(it.key)
                            key == root.path || key == root.matchPath
                        }
                        ?.value
                        .orEmpty()
                val historyProjectPaths = historyPaths
                    .map(::canonicalisePath)
                    .filter { pathWithinRoot(it, root.matchPath) }
                val visibleProjectPaths = (extraPaths + sessionPaths)
                    .distinct()
                    .filter { it != UNTRACKED_PATH }
                val sheetProjectPaths = (sessionPaths + historyProjectPaths + scannedPaths + extraPaths)
                    .distinct()
                    .filter { it != UNTRACKED_PATH }

                FolderTreeRoot(
                    path = root.path,
                    label = root.label,
                    isWatched = true,
                    folders = visibleProjectPaths
                        .map { path ->
                            folderRowForTreePath(
                                path = path,
                                sessions = sessionProjectPaths[path].orEmpty(),
                                watchedFolders = watchedFolders,
                                extraByPath = extraByPath,
                            )
                        }
                        .sortedForTree(),
                    addSheetProjects = buildRootProjectCandidates(
                        projectPaths = sheetProjectPaths,
                        activeSessionsByProjectPath = sessionProjectPaths,
                        historyProjectPaths = historyProjectPaths,
                        scannedProjectPaths = scannedPaths,
                        extraByPath = extraByPath,
                    ),
                )
            }

            val otherRows = otherSessionPaths
                .map { (path, entries) ->
                    FolderRow(
                        path = path,
                        label = defaultLabelForPath(path),
                        sessions = entries.sortedWith(sessionEntrySort()),
                        isWatched = false,
                    )
                }
                .sortedForTree()
            val flatFallbackRows = if (watchedRoots.isEmpty()) {
                groupSessionsIntoFolders(
                    sessions = sessions,
                    sessionFolderPaths = sessionFolderPaths,
                    watchedFolders = watchedFolders,
                    extraFolders = extraFolders,
                )
            } else {
                emptyList()
            }
            val otherRoot = if (otherRows.isNotEmpty() || flatFallbackRows.isNotEmpty()) {
                listOf(
                    FolderTreeRoot(
                        path = OTHER_ROOT_PATH,
                        label = OTHER_ROOT_LABEL,
                        folders = flatFallbackRows.ifEmpty { otherRows },
                        isWatched = false,
                    ),
                )
            } else {
                emptyList()
            }

            return treeRoots + otherRoot
        }

        private data class WatchedRoot(
            val path: String,
            val matchPath: String,
            val label: String,
        )

        private fun folderRowForTreePath(
            path: String,
            sessions: List<FolderSessionEntry>,
            watchedFolders: List<ProjectRootEntity>,
            extraByPath: Map<String, String>,
        ): FolderRow {
            val watched = watchedFolders.firstOrNull { canonicalisePath(it.path) == path }
            val label = when {
                watched != null -> WatchedFoldersViewModel
                    .stripOrderPrefix(watched.label)
                    .ifBlank { defaultLabelForPath(path) }
                extraByPath[path] != null -> extraByPath.getValue(path)
                else -> defaultLabelForPath(path)
            }
            return FolderRow(
                path = path,
                label = label,
                sessions = sessions.sortedWith(sessionEntrySort()),
                isWatched = watched != null,
            )
        }

        private fun bestRootForPath(path: String, roots: List<WatchedRoot>): WatchedRoot? {
            if (path == UNTRACKED_PATH) return null
            return roots
                .filter { pathWithinRoot(path, it.matchPath) }
                .maxByOrNull { it.matchPath.length }
        }

        private fun pathWithinRoot(path: String, root: String): Boolean =
            path == root || path.startsWith(root.trimEnd('/') + "/")

        private fun projectPathUnderRoot(path: String, root: String): String {
            if (path == root) return root
            val prefix = root.trimEnd('/') + "/"
            val child = path.removePrefix(prefix).substringBefore('/').ifBlank { return root }
            return prefix + child
        }

        internal fun buildRootProjectCandidates(
            projectPaths: List<String>,
            activeSessionsByProjectPath: Map<String, List<FolderSessionEntry>>,
            historyProjectPaths: List<String>,
            scannedProjectPaths: List<String>,
            extraByPath: Map<String, String> = emptyMap(),
        ): List<RootProjectCandidate> {
            val historyRank = historyProjectPaths
                .map(::canonicalisePath)
                .distinct()
                .withIndex()
                .associate { it.value to it.index }
            val activeRank = activeSessionsByProjectPath.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<FolderSessionEntry>>> {
                        it.value.maxOfOrNull { session -> session.lastActivity ?: 0L } ?: 0L
                    }.thenBy { defaultLabelForPath(it.key).lowercase() },
                )
                .mapIndexed { index, entry -> canonicalisePath(entry.key) to index }
                .toMap()
            val scannedSet = scannedProjectPaths.map(::canonicalisePath).toSet()
            return projectPaths
                .map(::canonicalisePath)
                .distinct()
                .filter { it != UNTRACKED_PATH }
                .map { path ->
                    val active = activeSessionsByProjectPath[path].orEmpty()
                    val source = when {
                        active.isNotEmpty() -> RootProjectSource.Active
                        path in historyRank -> RootProjectSource.History
                        else -> RootProjectSource.Scanned
                    }
                    RootProjectCandidate(
                        path = path,
                        label = extraByPath[path] ?: defaultLabelForPath(path),
                        source = source,
                        activeSessionCount = active.size,
                    )
                }
                .filter { it.source != RootProjectSource.Scanned || it.path in scannedSet || it.path in extraByPath }
                .sortedWith(rootProjectCandidateSort(activeRank, historyRank))
        }

        internal fun filterRootProjectCandidates(
            candidates: List<RootProjectCandidate>,
            query: String,
        ): List<RootProjectCandidate> {
            val clean = query.trim()
            if (clean.isEmpty()) return candidates
            return candidates.filter { candidate ->
                candidate.label.contains(clean, ignoreCase = true) ||
                candidate.path.contains(clean, ignoreCase = true)
            }
        }

        fun toggleProjectExpansion(expandedPaths: Set<String>, projectPath: String): Set<String> {
            val canonical = canonicalisePath(projectPath)
            return if (canonical in expandedPaths) expandedPaths - canonical else expandedPaths + canonical
        }

        internal fun mergeForwardingPortRows(
            discoveredPorts: List<HostDiscoveredPort>,
            activeRemotePorts: Set<Int>,
        ): List<HostDiscoveredPort> {
            if (activeRemotePorts.isEmpty()) {
                return discoveredPorts
                    .map { it.copy(status = HostPortForwardingPortStatus.DISCOVERED) }
                    .sortedBy { it.remotePort }
            }
            val active = activeRemotePorts.toSet()
            val discoveredByPort = discoveredPorts.associateBy { it.remotePort }
            return (discoveredByPort.keys + active).sorted().map { remotePort ->
                val discovered = discoveredByPort[remotePort]
                HostDiscoveredPort(
                    remotePort = remotePort,
                    process = discovered?.process.orEmpty(),
                    status = if (remotePort in active) {
                        HostPortForwardingPortStatus.FORWARDING
                    } else {
                        HostPortForwardingPortStatus.DISCOVERED
                    },
                    discovered = discovered != null,
                )
            }
        }

        private fun rootProjectCandidateSort(
            activeRank: Map<String, Int>,
            historyRank: Map<String, Int>,
        ): Comparator<RootProjectCandidate> =
            compareBy<RootProjectCandidate> {
                when (it.source) {
                    RootProjectSource.Active -> 0
                    RootProjectSource.History -> 1
                    RootProjectSource.Scanned -> 2
                }
            }.thenBy {
                if (it.source == RootProjectSource.Active) activeRank[it.path] ?: Int.MAX_VALUE else Int.MAX_VALUE
            }.thenBy {
                if (it.source == RootProjectSource.History) historyRank[it.path] ?: Int.MAX_VALUE else Int.MAX_VALUE
            }.thenBy { it.label.lowercase() }
                .thenBy { it.path.lowercase() }

        private fun sessionEntrySort(): Comparator<FolderSessionEntry> =
            compareByDescending<FolderSessionEntry> { it.agentKind.isAgentSession() }
                .thenByDescending { it.lastActivity ?: 0L }
                .thenBy { it.sessionName }

        private fun List<FolderRow>.sortedForTree(): List<FolderRow> {
            val active = filter { it.sessions.isNotEmpty() && it.path != UNTRACKED_PATH }
                .sortedWith(
                    compareByDescending<FolderRow> { it.mostRecentActivity }
                        .thenBy { it.label.lowercase() },
                )
            val empty = filter { it.sessions.isEmpty() && it.path != UNTRACKED_PATH }
                .sortedBy { it.label.lowercase() }
            val untracked = filter { it.path == UNTRACKED_PATH }
            return active + empty + untracked
        }

        private fun SessionAgentKind.isAgentSession(): Boolean = when (this) {
            SessionAgentKind.Claude,
            SessionAgentKind.Codex,
            SessionAgentKind.OpenCode,
            SessionAgentKind.Probing,
            SessionAgentKind.Exited,
            -> true
            SessionAgentKind.Shell -> false
        }
    }
}
