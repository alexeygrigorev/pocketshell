package com.pocketshell.app.projects

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.assistant.AppAssistantActions
import com.pocketshell.app.assistant.AssistantActions
import com.pocketshell.app.assistant.AssistantInstallId
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.ExecutorTraceSink
import com.pocketshell.app.assistant.FolderCandidate
import com.pocketshell.app.assistant.RealAssistantSshExecutor
import com.pocketshell.app.assistant.SessionActionBridge
import com.pocketshell.app.assistant.SessionAssistantController
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.portfwd.ForwardingHostSnapshot
import com.pocketshell.app.portfwd.InterestingPortFilter
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
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
    val activeProjectCount: Int get() = folders.count { it.sessions.isNotEmpty() }
    val sessionCount: Int get() = folders.sumOf { it.sessions.size }
    val inactiveProjectCount: Int get() = addSheetProjects.size
}

data class RootProjectCandidate(
    val path: String,
    val label: String,
    val source: RootProjectSource,
)

enum class RootProjectSource { History, Scanned }

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
    val entryAvailable: Boolean = false,
    val discoveryLoading: Boolean = false,
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
    val windows: List<FolderSessionWindowEntry> = emptyList(),
)

data class FolderSessionWindowEntry(
    val index: Int?,
    val name: String?,
    val active: Boolean,
    val command: String?,
    val agentKind: SessionAgentKind,
)

sealed interface FolderListUiState {
    data class Loading(
        val portForwarding: HostPortForwardingSummary = HostPortForwardingSummary(
            entryAvailable = true,
            discoveryLoading = true,
        ),
    ) : FolderListUiState

    data class Ready(
        val folders: List<FolderRow>,
        val treeRoots: List<FolderTreeRoot>,
        val flatSessions: List<FolderSessionEntry>,
        val expandedProjectPaths: Set<String>,
        val isRefreshing: Boolean = false,
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
 * Active/idle split of the flat host-detail session list (#489).
 *
 * The flat view groups every session into an **Active** and an **Idle**
 * section instead of by folder. A session reads *active* when it is attached or
 * is running an agent (Claude / Codex / OpenCode / probing / exited-agent) — the
 * same green-dot condition the row [com.pocketshell.uikit.components.StatusDot]
 * paints, so the section a row lands in always agrees with its dot colour.
 * Everything else (plain idle shells) is *idle* (amber).
 *
 * Order inside each section is preserved from the already-sorted `flatSessions`
 * input (agents first, then most-recent activity, then name — see
 * `sessionEntrySort`), so the split is purely a partition and never re-sorts.
 */
data class FlatSessionGroups(
    val active: List<FolderSessionEntry>,
    val idle: List<FolderSessionEntry>,
) {
    val activeCount: Int get() = active.size
    val idleCount: Int get() = idle.size
    val totalCount: Int get() = active.size + idle.size

    companion object {
        /**
         * Partition [sessions] into the Active / Idle sections (#489). A session
         * is active when [FolderSessionEntry.attached] is set or its
         * [FolderSessionEntry.agentKind] is an agent kind. Relative order within
         * each section is the input order (already sorted upstream).
         */
        fun from(sessions: List<FolderSessionEntry>): FlatSessionGroups {
            val (active, idle) = sessions.partition { it.attached || it.agentKind.isFlatActiveAgent() }
            return FlatSessionGroups(active = active, idle = idle)
        }

        private fun SessionAgentKind.isFlatActiveAgent(): Boolean = when (this) {
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
class FolderListViewModel internal constructor(
    private val gateway: FolderListGateway,
    private val hostDao: HostDao,
    private val projectRootDao: ProjectRootDao,
    private val sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
        },
    ),
    @ApplicationContext private val applicationContext: Context? = null,
    private val hostSessionListCache: HostSessionListCache? =
        applicationContext?.let(::HostSessionListCache),
    private val assistantClientFactory: AssistantLlmClientFactory? = null,
    private val reposRemoteSource: ReposRemoteSource? = null,
    private val forwardingController: ForwardingController,
    // Issue #464: cross-view-model fan-out so a confirmed Kill session on
    // the per-session screen drops the dead row from this tree promptly.
    private val sessionLifecycleSignals: com.pocketshell.app.tmux.SessionLifecycleSignals? = null,
    // Issue #430: when true the view model attaches a
    // [ProcessLifecycleOwner] observer in [init] so the session-discovery
    // poll loop is gated on the whole-process foreground signal. Always
    // true on the production Hilt path (constructed on the main thread).
    // Connected tests that construct the VM off the main thread and drive
    // the gate via [setProcessStartedForTest] pass `false` so they never
    // touch the main-thread-affine lifecycle registry.
    attachLifecycle: Boolean = true,
) : ViewModel() {

    /**
     * Production Hilt entry point. Delegates to the internal constructor
     * with lifecycle attachment enabled. Hilt constructs view models on
     * the main thread, so the [ProcessLifecycleOwner] touch in [init] is
     * safe here.
     */
    @Inject
    constructor(
        gateway: FolderListGateway,
        hostDao: HostDao,
        projectRootDao: ProjectRootDao,
        // Issue #470: inject the app-scoped singleton lease manager (the
        // SAME one [SshFolderListGateway] uses for its `tmux list-sessions`
        // probe) so the warm host lease and the probe share ONE pooled SSH
        // connection. Previously this view model used a throwaway
        // `SshLeaseManager()` for the warm lease while the gateway used the
        // Hilt singleton, so opening a host detail screen fired TWO
        // concurrent SSH connects to the same host. On a cold/loaded
        // emulator the redundant second connect congests the `10.0.2.2` NAT
        // path and the folder-list enumeration stalls in `Loading` past the
        // connect bound. Sharing the pool means the probe reuses the warm
        // connection (reference-counted) instead of racing a second one.
        sshLeaseManager: SshLeaseManager,
        @ApplicationContext applicationContext: Context,
        hostSessionListCache: HostSessionListCache,
        assistantClientFactory: AssistantLlmClientFactory?,
        reposRemoteSource: ReposRemoteSource?,
        forwardingController: ForwardingController,
        sessionLifecycleSignals: com.pocketshell.app.tmux.SessionLifecycleSignals,
    ) : this(
        gateway = gateway,
        hostDao = hostDao,
        projectRootDao = projectRootDao,
        sshLeaseManager = sshLeaseManager,
        applicationContext = applicationContext,
        hostSessionListCache = hostSessionListCache,
        assistantClientFactory = assistantClientFactory,
        reposRemoteSource = reposRemoteSource,
        forwardingController = forwardingController,
        sessionLifecycleSignals = sessionLifecycleSignals,
        attachLifecycle = true,
    )

    private val _state: MutableStateFlow<FolderListUiState> =
        MutableStateFlow(FolderListUiState.Loading())
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
    private var warmLease: SshLease? = null
    @androidx.annotation.VisibleForTesting
    internal var warmLeaseAcquiredForTest: (() -> Unit)? = null
    @androidx.annotation.VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var watchedFoldersJob: Job? = null
    private var pollingJob: Job? = null
    private var lastSessions: List<FolderSessionEntry> = emptyList()
    private var lastSessionFolderPaths: Map<String, String> = emptyMap()
    private var lastWatchedFolders: List<ProjectRootEntity> = emptyList()
    private var rootSnapshotLoaded: Boolean = false
    private var hasSessionProbeSnapshot: Boolean = false
    private var lastScannedProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var lastHistoryProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var lastResolvedWatchedRootPaths: Map<String, String> = emptyMap()
    private var lastCreatedFolders: Map<String, String> = emptyMap()
    private var expandedProjectPaths: Set<String> = emptySet()
    private var sessionRefreshInFlight: Boolean = false

    /**
     * Issue #471: folder paths the user has *explicitly collapsed*. This is
     * the memory that makes auto-expand respect manual collapse.
     *
     * Auto-expand ([emitReady]) opens any folder with ≥1 active session on
     * first ready and whenever a freshly-active folder appears — but it must
     * NOT fight the user. Once the user collapses a session-bearing folder it
     * lands here, and every subsequent 5 s discovery poll / re-emission skips
     * re-expanding it. The path leaves this set only when the user expands the
     * folder again ([toggleProjectExpanded]), restoring auto-expand for it.
     */
    private var userCollapsedProjectPaths: Set<String> = emptySet()
    private var lastDiscoveredPorts: List<HostDiscoveredPort> = emptyList()
    private var forwardingSnapshots: Map<Long, ForwardingHostSnapshot> = emptyMap()
    private var refreshSessionsRequested: Boolean = false

    /**
     * Issue #430: whole-process foreground signal driven by
     * [ProcessLifecycleOwner]. `true` while an Activity is visible
     * (`STARTED`). The session-discovery poll loop ([startPolling]) is
     * gated on this flag, so:
     *
     *  - while backgrounded the loop parks instead of polling a dead SSH
     *    lease (honours the no-background-work principle, D21 / #161); and
     *  - on every `false -> true` transition (app foreground / resume)
     *    the loop wakes and runs an **immediate** probe, re-acquiring a
     *    fresh connection so a known host's live tmux sessions reappear
     *    on the folder tree without the user manually re-attaching.
     *
     * Seeded synchronously in [attachProcessLifecycle] so a view model
     * created while the app is already foregrounded does not block at a
     * stale `false`.
     */
    private val processStarted = MutableStateFlow(false)
    private var foregroundGeneration: Long = 0L
    private var lifecycleObserver: LifecycleEventObserver? = null

    init {
        viewModelScope.launch {
            forwardingController.flowOfHostSnapshots().collectLatest { snapshots ->
                forwardingSnapshots = snapshots
                emitReady()
            }
        }
        // Issue #464: when the per-session screen confirms a Kill session,
        // drop the matching row from this tree immediately (optimistic),
        // then re-probe so a failed kill that somehow still broadcast — or
        // a same-name session recreated since — is reconciled against the
        // authoritative `tmux list-sessions` result.
        sessionLifecycleSignals?.let { signals ->
            viewModelScope.launch {
                signals.killedSessions.collect { killed ->
                    onSessionKilled(killed)
                }
            }
        }
        if (attachLifecycle) attachProcessLifecycle()
    }

    /**
     * Issue #464: handle a confirmed session kill broadcast from the
     * per-session screen. Ignores kills for other hosts. Optimistically
     * removes the dead session from the current snapshot so the tree
     * updates instantly.
     *
     * Reconcile against the authoritative `tmux list-sessions` result is
     * deliberately deferred to the screen's normal probe cadence rather
     * than forced here: the kill is emitted only on a *confirmed* tmux
     * teardown, so the optimistic drop is always correct, and the user is
     * still on the per-session screen when this fires. Starting a competing
     * probe now would re-acquire the warm SSH lease and race the session
     * screen's own attach (the exact reason `stopPolling()` exists). If the
     * tree's poll loop is still live (kill triggered while the tree screen
     * remained composed), we kick an immediate refresh; otherwise the
     * reconcile rides the re-probe that `bind()` runs when the user returns.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onSessionKilled(killed: com.pocketshell.app.tmux.KilledSession) {
        val params = bound ?: return
        if (params.hostId != killed.hostId) return
        if (hasSessionProbeSnapshot) {
            val before = lastSessions.size
            lastSessions = lastSessions.filterNot { it.sessionName == killed.sessionName }
            if (lastSessions.size != before) {
                lastSessionFolderPaths = lastSessionFolderPaths - killed.sessionName
                emitReady()
            }
        }
        if (pollingJob != null) refresh()
    }

    /**
     * Attach a [ProcessLifecycleOwner] observer so [processStarted]
     * tracks the whole-process `STARTED` / `STOPPED` lifecycle. The
     * current state is seeded synchronously so a poll loop started while
     * the app is already foregrounded sweeps immediately rather than
     * waiting for the next `ON_START`.
     */
    @androidx.annotation.VisibleForTesting
    internal fun attachProcessLifecycle(
        owner: LifecycleOwner = ProcessLifecycleOwner.get(),
    ) {
        if (lifecycleObserver != null) return
        val observer = LifecycleEventObserver { _: LifecycleOwner, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updateProcessStarted(true)
                Lifecycle.Event.ON_STOP -> updateProcessStarted(false)
                else -> Unit
            }
        }
        lifecycleObserver = observer
        // Seed synchronously so a poll loop started while the app is
        // already foregrounded sweeps immediately. `getCurrentState` /
        // `addObserver` are main-thread-affine; production Hilt always
        // constructs this view model on the main thread, so the touch is
        // inline there. Connected tests that construct the VM off the
        // main thread drive the gate via [setProcessStartedForTest] and
        // pass `attachLifecycle = false` so we never reach the registry.
        updateProcessStarted(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        owner.lifecycle.addObserver(observer)
    }

    /**
     * Issue #430 test seam: flip the process-foreground gate without a
     * real [ProcessLifecycleOwner]. Lets unit tests park / release the
     * poll loop deterministically under `runTest`'s virtual clock.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setProcessStartedForTest(started: Boolean) {
        updateProcessStarted(started)
    }

    private fun updateProcessStarted(started: Boolean) {
        val wasStarted = processStarted.value
        processStarted.value = started
        if (!wasStarted && started) {
            foregroundGeneration += 1
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
        pollingJob?.cancel()
        pollingJob = null
        bound = params
        rootSnapshotLoaded = false
        hasSessionProbeSnapshot = false
        lastSessions = emptyList()
        lastSessionFolderPaths = emptyMap()
        lastWatchedFolders = emptyList()
        lastScannedProjectFoldersByRoot = emptyMap()
        lastHistoryProjectFoldersByRoot = emptyMap()
        lastResolvedWatchedRootPaths = emptyMap()
        sessionRefreshInFlight = false
        // Issue #471: a new host starts with no expansion memory so auto-expand
        // applies fresh and a prior host's collapse choices don't leak across.
        expandedProjectPaths = emptySet()
        userCollapsedProjectPaths = emptySet()
        _state.value = loadingState()
        restoreCachedSessions(hostId)

        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            replaceWarmLease(params)
        }
        watchedFoldersJob?.cancel()
        watchedFoldersJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { rows ->
                lastWatchedFolders = rows
                rootSnapshotLoaded = true
                if (pollingJob == null) {
                    startPolling()
                }
                // Re-emit so a watched-folder write that lands after the
                // initial probe surfaces immediately. Before the first
                // probe, keep the explicit loading state instead of
                // rendering roots-only scaffolding that will be replaced
                // moments later by session/scan data.
                emitReady()
            }
        }
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
     * Manual host-detail overflow action for issue #607. Reuses the same
     * session/folder discovery path as [refresh], but keeps the current Ready
     * snapshot visible if the remote refresh fails and reports that failure in
     * the existing lightweight action banner.
     */
    fun refreshSessions() {
        if (_state.value is FolderListUiState.Ready) {
            refreshSessionsRequested = true
            _actionStatus.value = FolderActionStatus.Running("Refreshing sessions")
        }
        refresh()
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
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
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

    /**
     * Stop (kill) the tmux session named [sessionName] directly from the
     * host-detail tree — issue #518.
     *
     * The folder/session tree never holds an attached `tmux -CC` control
     * client, so the kill runs over the gateway's SSH-exec path
     * ([FolderListGateway.killSession]) rather than the in-session control
     * channel that [com.pocketshell.app.tmux.TmuxSessionViewModel.killCurrentSession]
     * uses. On a CONFIRMED kill (the gateway verified the session is gone)
     * we reuse the EXACT same reconcile path as the in-session kill: the
     * optimistic [onSessionKilled] row-drop plus the [SessionLifecycleSignals]
     * broadcast, so any other view model (a flat sessions dashboard, a
     * re-bound tree) drops the dead row too. A failed kill never drops the
     * row and surfaces an error banner so the user knows nothing happened.
     */
    fun killSession(sessionName: String) {
        val params = bound ?: return
        val target = sessionName.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            _actionStatus.value = FolderActionStatus.Running("Stopping $target")
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
                _actionStatus.value = FolderActionStatus.Failed("Host not found.")
                return@launch
            }
            val result = gateway.killSession(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                sessionName = target,
            )
            result.fold(
                onSuccess = {
                    _actionStatus.value = FolderActionStatus.Succeeded("Stopped $target")
                    // Reuse the existing kill reconcile path (issue #464):
                    // optimistic local drop + the shared lifecycle broadcast
                    // so every view model converges on the dead session being
                    // gone. onSessionKilled also kicks an authoritative
                    // re-probe when the tree poll is still live.
                    onSessionKilled(
                        com.pocketshell.app.tmux.KilledSession(
                            hostId = params.hostId,
                            sessionName = target,
                        ),
                    )
                    sessionLifecycleSignals?.emitKilled(params.hostId, target)
                },
                onFailure = { error ->
                    _actionStatus.value = FolderActionStatus.Failed(
                        "Couldn't stop $target: ${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    fun renameSession(oldName: String, newName: String) {
        val params = bound ?: return
        val oldTarget = oldName.trim()
        val newTarget = newName.trim()
        if (oldTarget.isEmpty() || newTarget.isEmpty() || oldTarget == newTarget) return
        viewModelScope.launch {
            _actionStatus.value = FolderActionStatus.Running("Renaming $oldTarget")
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
                _actionStatus.value = FolderActionStatus.Failed("Host not found.")
                return@launch
            }
            val result = gateway.renameSession(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                oldName = oldTarget,
                newName = newTarget,
            )
            result.fold(
                onSuccess = {
                    _actionStatus.value = FolderActionStatus.Succeeded("Renamed $oldTarget to $newTarget")
                    renameSessionSnapshot(oldTarget = oldTarget, newTarget = newTarget)
                    refresh()
                },
                onFailure = { error ->
                    _actionStatus.value = FolderActionStatus.Failed(
                        "Couldn't rename $oldTarget: ${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun renameSessionSnapshot(oldTarget: String, newTarget: String) {
        if (!hasSessionProbeSnapshot) return
        var changed = false
        lastSessions = lastSessions.map { session ->
            if (session.sessionName == oldTarget) {
                changed = true
                session.copy(sessionName = newTarget)
            } else {
                session
            }
        }
        val folderPath = lastSessionFolderPaths[oldTarget]
        if (folderPath != null) {
            lastSessionFolderPaths = lastSessionFolderPaths - oldTarget + (newTarget to folderPath)
            changed = true
        }
        if (changed) emitReady()
    }

    fun createEmptyProject(
        parentPath: String,
        folderName: String,
        onCreated: (String) -> Unit = {},
    ) {
        val params = bound ?: return
        viewModelScope.launch {
            _actionStatus.value = FolderActionStatus.Running("Creating $folderName")
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
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
                    onCreated(path)
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
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
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
        val canonical = canonicalisePath(projectPath)
        val wasExpanded = canonical in expandedProjectPaths
        expandedProjectPaths = toggleProjectExpansion(expandedProjectPaths, canonical)
        // Issue #471: record/clear the explicit manual-collapse so auto-expand
        // respects the user's choice. Collapsing a folder pins it collapsed
        // across polls; expanding it again restores auto-expand for it.
        userCollapsedProjectPaths = if (wasExpanded) {
            userCollapsedProjectPaths + canonical
        } else {
            userCollapsedProjectPaths - canonical
        }
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

    internal fun chooseAssistantFolder(candidate: FolderCandidate) = assistant.choose(candidate)

    fun cancelAssistantChoice() = assistant.cancelChoice()

    fun retryAssistantAction() = assistant.retry()

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
            override suspend fun sendCommand(command: String): Result<Unit> =
                Result.failure(IllegalStateException("No active terminal pane on the host detail screen."))
            override suspend fun sendPromptToSession(sessionName: String, prompt: String): Result<Unit> =
                Result.failure(IllegalStateException("No active agent pane on the host detail screen."))
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
        if (!rootSnapshotLoaded) {
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = loadingState()
            }
            return
        }
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // Surface loading state on the very first cycle when we have
            // nothing to show yet; subsequent cycles keep the previous
            // snapshot visible (so classifier-chip updates are a single
            // Compose recomposition, not a loading flash).
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = loadingState()
            }
            while (isActive) {
                // Issue #430: gate every probe on the whole-process
                // foreground signal. While the app is backgrounded this
                // suspends (no background SSH work, D21 / #161); the
                // suspension releases on the next `ON_START`, so a
                // `false -> true` foreground/resume transition runs an
                // immediate probe and a host's live tmux sessions
                // reappear on the folder tree without manual re-attach.
                processStarted.first { it }
                setSessionRefreshInFlight(true)
                runProbe(params)
                waitForNextPollOrForegroundReturn(foregroundGeneration)
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
        val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
            setSessionRefreshInFlight(false)
            _state.value = FolderListUiState.Failed("Host not found.")
            return
        }
        val result = gateway.listSessionsWithFolder(
            host = host,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
            watchedRoots = lastWatchedFolders,
        )
        if (bound != params) return
        when (result) {
            is FolderListResult.Sessions -> {
                hasSessionProbeSnapshot = true
                hostSessionListCache?.save(params.hostId, result.rows)
                lastSessions = result.rows.map { row ->
                    FolderSessionEntry(
                        sessionName = row.sessionName,
                        lastActivity = row.lastActivity,
                        attached = row.attached,
                        agentKind = row.agentKind,
                        windows = row.windows.map { window ->
                            FolderSessionWindowEntry(
                                index = window.index,
                                name = window.name,
                                active = window.active,
                                command = window.command,
                                agentKind = window.agentKind,
                            )
                        },
                    )
                }
                lastSessionFolderPaths = result.rows.associate { row ->
                    row.sessionName to (row.cwd?.let(::canonicalisePath) ?: UNTRACKED_PATH)
                }
                lastScannedProjectFoldersByRoot = result.projectFoldersByRoot
                lastHistoryProjectFoldersByRoot = result.historyProjectFoldersByRoot
                lastResolvedWatchedRootPaths = result.resolvedWatchedRootPaths
                // Issue #456/#602: filter discovery to interesting ports
                // (de-dupe and show the user-useful 1000..10000 range by
                // default) so the host card's "N ports" count and the panel
                // agree and the card never reflects an ~80-port dump.
                lastDiscoveredPorts = InterestingPortFilter.filter(result.discoveredPorts).map { port ->
                    HostDiscoveredPort(
                        remotePort = port.port,
                        process = port.processName,
                    )
                }
                setSessionRefreshInFlight(false)
                emitReady()
                if (refreshSessionsRequested) {
                    completeManualRefresh()
                } else {
                    clearRefreshFailure()
                }
            }
            is FolderListResult.Failed -> {
                setSessionRefreshInFlight(false)
                if (preserveReadyOnRefresh("Couldn't refresh sessions: ${result.message}")) return
                _state.value = FolderListUiState.Failed(result.message)
            }
            is FolderListResult.ConnectFailed -> {
                val message = result.cause.message ?: "Connection failed"
                setSessionRefreshInFlight(false)
                if (preserveReadyOnRefresh("Couldn't refresh sessions: $message")) return
                _state.value = FolderListUiState.ConnectError(
                    message = message,
                    cause = result.cause,
                )
            }
            FolderListResult.ToolUnavailable -> {
                setSessionRefreshInFlight(false)
                if (preserveReadyOnRefresh("Couldn't refresh sessions: tmux is not installed.")) return
                _state.value = FolderListUiState.ToolUnavailable
            }
        }
    }

    private suspend fun waitForNextPollOrForegroundReturn(observedForegroundGeneration: Long) {
        var waitedMs = 0L
        while (currentCoroutineContext().isActive && waitedMs < POLL_INTERVAL_MS) {
            delay(POLL_TICK_MS)
            if (foregroundGeneration != observedForegroundGeneration) return
            if (!processStarted.value) {
                processStarted.first { it }
                return
            }
            waitedMs += POLL_TICK_MS
        }
    }

    private fun preserveReadyOnRefresh(message: String): Boolean {
        if (_state.value !is FolderListUiState.Ready) return false
        refreshSessionsRequested = false
        _actionStatus.value = FolderActionStatus.Failed(message)
        emitReady()
        return true
    }

    private fun completeManualRefresh() {
        if (!refreshSessionsRequested) return
        refreshSessionsRequested = false
        _actionStatus.value = FolderActionStatus.Succeeded("Sessions refreshed")
    }

    private fun clearRefreshFailure() {
        val status = _actionStatus.value as? FolderActionStatus.Failed ?: return
        if (status.message.startsWith("Couldn't refresh sessions:")) {
            _actionStatus.value = FolderActionStatus.Idle
        }
    }

    private fun restoreCachedSessions(hostId: Long) {
        val snapshot = hostSessionListCache?.read(hostId) ?: return
        hasSessionProbeSnapshot = true
        lastSessions = snapshot.rows.map { row ->
            FolderSessionEntry(
                sessionName = row.sessionName,
                lastActivity = row.lastActivity,
                attached = row.attached,
                agentKind = row.agentKind,
                windows = row.windows.map { window ->
                    FolderSessionWindowEntry(
                        index = window.index,
                        name = window.name,
                        active = window.active,
                        command = window.command,
                        agentKind = window.agentKind,
                    )
                },
            )
        }
        lastSessionFolderPaths = snapshot.rows.associate { row ->
            row.sessionName to (row.cwd?.let(::canonicalisePath) ?: UNTRACKED_PATH)
        }
        emitReady()
    }

    private fun setSessionRefreshInFlight(refreshing: Boolean) {
        if (sessionRefreshInFlight == refreshing) return
        sessionRefreshInFlight = refreshing
        if (hasSessionProbeSnapshot) emitReady()
    }

    /**
     * Rebuild [FolderListUiState.Ready] from the most recent gateway
     * snapshot + watched-folder overlay. Idempotent: every change in
     * either input re-runs the grouping.
     */
    private fun emitReady() {
        if (bound == null) return
        if (!hasSessionProbeSnapshot) return
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
        val visibleFolders = treeRoots.flatMap { root -> root.folders }
        val visibleProjectPaths = visibleFolders
            .map { folder -> folder.path }
            .toSet()
        // Issue #471: auto-expand folders with ≥1 active session by default so
        // running sessions are visible at a glance, while RESPECTING manual
        // collapse. A folder the user collapsed stays in
        // [userCollapsedProjectPaths] and is skipped here, so a 5 s discovery
        // poll / re-emission never springs it back open. Empty folders are
        // never auto-expanded (keeps the #455 compact-tree direction).
        val activeProjectPaths = visibleFolders
            .filter { it.sessions.isNotEmpty() }
            .map { it.path }
            .toSet()
        expandedProjectPaths = resolveExpandedProjectPaths(
            previousExpanded = expandedProjectPaths,
            visibleProjectPaths = visibleProjectPaths,
            activeProjectPaths = activeProjectPaths,
            userCollapsedProjectPaths = userCollapsedProjectPaths,
        )
        // Drop collapse memory for folders that no longer exist so it can't
        // leak across rebinds or accumulate stale paths.
        userCollapsedProjectPaths = userCollapsedProjectPaths.intersect(visibleProjectPaths)
        _state.value = FolderListUiState.Ready(
            folders = folders,
            treeRoots = treeRoots,
            flatSessions = lastSessions.sortedWith(
                compareByDescending<FolderSessionEntry> { it.lastActivity ?: 0L }
                    .thenBy { it.sessionName },
            ),
            expandedProjectPaths = expandedProjectPaths,
            isRefreshing = sessionRefreshInFlight,
            portForwarding = forwardingSummary(),
        )
    }

    private fun loadingState(): FolderListUiState.Loading {
        val hostId = bound?.hostId
        val snapshot = hostId?.let { forwardingSnapshots[it] }
        return FolderListUiState.Loading(
            portForwarding = HostPortForwardingSummary(
                active = snapshot?.active == true,
                activeTunnelCount = snapshot?.tunnelCount ?: 0,
                entryAvailable = hostId != null,
                discoveryLoading = true,
            ),
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
            entryAvailable = true,
            discoveryLoading = !hasSessionProbeSnapshot,
        )
    }

    override fun onCleared() {
        pollingJob?.cancel()
        warmJob?.cancel()
        warmReleaseJob?.cancel()
        runBlocking {
            releaseWarmLease()
        }
        watchedFoldersJob?.cancel()
        lifecycleObserver?.let { observer ->
            // `removeObserver` is main-thread-affine; `onCleared` runs on
            // the main thread so this is safe. Only set when lifecycle
            // attachment was enabled (production path).
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
        lifecycleObserver = null
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

        fun toSshLeaseTarget(): SshLeaseTarget =
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
    }

    private suspend fun releaseWarmLease() {
        withContext(NonCancellable) {
            val lease = warmLease ?: return@withContext
            warmLease = null
            lease.release()
        }
    }

    private suspend fun replaceWarmLease(params: BoundParams) {
        releaseWarmLease()
        var acquiredLease: SshLease? = null
        try {
            val lease = sshLeaseManager.acquire(params.toSshLeaseTarget()).getOrNull() ?: return
            acquiredLease = lease
            warmLeaseAcquiredForTest?.invoke()
            withContext(NonCancellable) {
                warmLease = lease
                acquiredLease = null
            }
        } finally {
            acquiredLease?.release()
        }
    }

    private fun scheduleWarmRelease() {
        warmReleaseJob?.cancel()
        warmReleaseJob = viewModelScope.launch {
            delay(WARM_RELEASE_DELAY_MS)
            releaseWarmLease()
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
         * Human-meaningful labels for the two degenerate-but-real cwd
         * cases that otherwise render as a nameless folder (#438): a
         * session sitting at filesystem root, and one at the literal
         * home marker.
         */
        const val ROOT_LABEL: String = "/ (root)"
        const val HOME_LABEL: String = "~ (home)"

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
         * `pocketshell`). This is a guaranteed-non-blank fallback chain —
         * it never returns an empty string, a lone `"/"`, or any other
         * degenerate label that would read as a nameless folder in the
         * project tree (#438):
         *
         *  - the [UNTRACKED_PATH] sentinel → [UNTRACKED_LABEL].
         *  - a blank path → [UNTRACKED_LABEL] (never blank).
         *  - filesystem root (`/`, `//`, ...) → `"/ (root)"`.
         *  - a literal home marker (`~` / `$HOME`) → `"~ (home)"`.
         *  - otherwise the trailing path segment, or the full path when
         *    there is no meaningful trailing segment.
         */
        fun defaultLabelForPath(path: String): String {
            if (path == UNTRACKED_PATH) return UNTRACKED_LABEL
            val clean = path.trim()
            if (clean.isEmpty()) return UNTRACKED_LABEL
            val stripped = clean.trimEnd('/')
            if (stripped.isEmpty()) return ROOT_LABEL
            if (stripped == "~" || stripped == "\$HOME") return HOME_LABEL
            val tail = stripped.substringAfterLast('/')
            return tail.ifBlank { stripped }
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
                    extraByPath[path] != null ->
                        extraByPath.getValue(path).ifBlank { defaultLabelForPath(path) }
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
                val visibleProjectPaths = sessionPaths
                    .distinct()
                    .filter { it != UNTRACKED_PATH }
                val sheetProjectPaths = (historyProjectPaths + scannedPaths + extraPaths)
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
                extraByPath[path] != null ->
                    extraByPath.getValue(path).ifBlank { defaultLabelForPath(path) }
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
            val activeProjectPaths = activeSessionsByProjectPath.keys
                .map(::canonicalisePath)
                .toSet()
            val scannedSet = scannedProjectPaths.map(::canonicalisePath).toSet()
            return projectPaths
                .map(::canonicalisePath)
                .distinct()
                .filter { it != UNTRACKED_PATH && it !in activeProjectPaths }
                .map { path ->
                    val source = when {
                        path in historyRank -> RootProjectSource.History
                        else -> RootProjectSource.Scanned
                    }
                    RootProjectCandidate(
                        path = path,
                        label = (extraByPath[path] ?: defaultLabelForPath(path))
                            .ifBlank { defaultLabelForPath(path) },
                        source = source,
                    )
                }
                .filter { it.source != RootProjectSource.Scanned || it.path in scannedSet || it.path in extraByPath }
                .sortedWith(rootProjectCandidateSort(historyRank))
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

        /**
         * Issue #471: compute the next set of expanded folder paths for a
         * fresh emission, auto-expanding folders with active sessions while
         * respecting manual collapse. Pure + visible-for-test so the
         * collapse-stickiness invariant can be exercised without a view model.
         *
         * Rules:
         *  - Start from [previousExpanded] (so a folder the user manually
         *    expanded stays open), pruned to [visibleProjectPaths] so paths
         *    for folders that disappeared don't linger.
         *  - Auto-expand every path in [activeProjectPaths] (folders with ≥1
         *    active session) EXCEPT those the user explicitly collapsed
         *    ([userCollapsedProjectPaths]). This is what keeps a poll / re-emit
         *    from re-opening a folder the user collapsed.
         *  - Empty folders are never in [activeProjectPaths], so they are never
         *    auto-expanded (they only open via an explicit user tap, which
         *    lands in [previousExpanded]).
         */
        fun resolveExpandedProjectPaths(
            previousExpanded: Set<String>,
            visibleProjectPaths: Set<String>,
            activeProjectPaths: Set<String>,
            userCollapsedProjectPaths: Set<String>,
        ): Set<String> {
            val carriedOver = previousExpanded.intersect(visibleProjectPaths)
            val autoExpand = activeProjectPaths - userCollapsedProjectPaths
            return carriedOver + autoExpand
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
            historyRank: Map<String, Int>,
        ): Comparator<RootProjectCandidate> =
            compareBy<RootProjectCandidate> {
                when (it.source) {
                    RootProjectSource.History -> 0
                    RootProjectSource.Scanned -> 1
                }
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
