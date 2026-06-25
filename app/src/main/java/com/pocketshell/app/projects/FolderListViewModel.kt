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
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Issue #858: the human label of the non-default profile this session was
     * launched with (e.g. `"Claude (Z.AI)"`), read back from the host-side
     * `@ps_agent_profile` tmux user option. `null` for a default / non-profiled
     * / legacy session — so the tree distinguishes a z.ai Claude from a default
     * Claude, and shows no spurious chip for a default session.
     */
    val recordedProfile: String? = null,
    /**
     * Issue #899: tmux `#{session_id}` (`$N`) carried from discovery into
     * navigation so runtime/reveal/memory keys can avoid stale name reuse.
     */
    val tmuxSessionId: String? = null,
    /**
     * Issue #899: tmux `#{session_created}` epoch seconds carried with
     * [tmuxSessionId] to build the durable session key.
     */
    val sessionCreated: Long? = null,
)

data class FolderSessionWindowEntry(
    val index: Int?,
    val name: String?,
    val active: Boolean,
    val command: String?,
    val agentKind: SessionAgentKind,
    /**
     * Issue #653: the stable tmux window id (`@N`) — the same id the live `-CC`
     * stream reports in `%window-close @<id>`. Carried into the maintained tree
     * so a single window close prunes exactly that window node by id (the window
     * index renumbers across closes and cannot key the prune). `null` for a
     * window the probe path could not tag with an id (e.g. an older cached row).
     */
    val windowId: String? = null,
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

/**
 * Host-detail action feedback surface (#656).
 *
 * For a routine **success** there is deliberately no status state at all — the
 * list updating (a session disappearing on Stop, appearing on Create) IS the
 * feedback, so a success never produces a banner that would push the list down.
 * Only a [Failed] action carries a message (the user must know it did not
 * work), and the screen surfaces that as a NON-displacing overlay rather than a
 * top-of-list row. In-progress feedback for the manual refresh rides the
 * non-displacing refresh progress bar ([FolderListUiState.Ready.isRefreshing],
 * #639), not a status banner.
 */
sealed interface FolderActionStatus {
    data object Idle : FolderActionStatus

    /**
     * A failure banner. [isRefreshFailure] marks the calm "couldn't refresh the
     * project tree" band (issue #711) so its #656 auto-clear can recognise it by
     * TYPE rather than by matching the user-facing message text. Action failures
     * (kill / rename / create / import / host-not-found) leave it `false` — those
     * are explicit, user-dismissed errors that must NOT be silently cleared when
     * a later reconcile succeeds.
     */
    data class Failed(
        val message: String,
        val isRefreshFailure: Boolean = false,
    ) : FolderActionStatus
}

/**
 * Issue #702: surfaced as a retryable [FolderListUiState.ConnectError] when a
 * whole [FolderListViewModel] reconcile out-waits its outer bound. The gateway
 * already self-bounds its live `-CC` enumeration (#702) and its SSH-lease exec
 * reads (#470); this is the last-line defence so a future unbounded gateway
 * path can never pin the session picker in `Loading`. The user gets a Retry
 * panel instead of an indefinite spinner.
 */
class FolderReconcileTimeoutException(
    timeoutMs: Long,
) : RuntimeException(
    "Session list didn't load within ${timeoutMs}ms. Tap to retry.",
)

/**
 * Active/idle split of the flat host-detail session list (#489).
 *
 * The flat view groups every session into an **Active** and an **Idle**
 * section instead of by folder. A session reads *active* purely when it is
 * running an agent (Claude / Codex / OpenCode / probing / exited-agent) — the
 * same green-dot condition the row [com.pocketshell.uikit.components.StatusDot]
 * paints, so the section a row lands in always agrees with its dot colour.
 * Everything else (plain shells, attached or not) is *idle* (amber).
 *
 * The split deliberately does NOT key on
 * [FolderSessionEntry.attached] (#663): `attached` flips true the instant the
 * user opens a session, which would jump a plain shell from Idle to Active and
 * reorder the list under the user's finger, causing mis-taps. Agent activity is
 * the only thing that moves a row between sections; opening/attaching a plain
 * shell leaves its row in exactly the same section and index.
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
         * Partition [sessions] into the Active / Idle sections (#489, #663). A
         * session is active when its [FolderSessionEntry.agentKind] is an agent
         * kind — agent activity only, NOT [FolderSessionEntry.attached], so
         * opening/attaching a plain shell never moves its row between sections.
         * Relative order within each section is the input order (already sorted
         * upstream).
         */
        fun from(sessions: List<FolderSessionEntry>): FlatSessionGroups {
            val (active, idle) = sessions.partition { it.agentKind.isFlatActiveAgent() }
            return FlatSessionGroups(active = active, idle = idle)
        }

        private fun SessionAgentKind.isFlatActiveAgent(): Boolean = when (this) {
            SessionAgentKind.Claude,
            SessionAgentKind.Codex,
            SessionAgentKind.OpenCode,
            SessionAgentKind.Probing,
            SessionAgentKind.Exited,
            -> true
            // Unknown (foreign, not-yet-classified) groups with shells (#821).
            SessionAgentKind.Shell,
            SessionAgentKind.Unknown,
            -> false
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
    private val assistantClientFactory: AssistantLlmClientFactory? = null,
    private val reposRemoteSource: ReposRemoteSource? = null,
    private val forwardingController: ForwardingController,
    // Issue #464: cross-view-model fan-out so a confirmed Kill session on
    // the per-session screen drops the dead row from this tree promptly.
    private val sessionLifecycleSignals: com.pocketshell.app.tmux.SessionLifecycleSignals? = null,
    // Issue #706: the app-scoped registry of live `tmux -CC` control clients.
    // When the bound host has a live client we subscribe to its
    // `%sessions-changed` (ControlEvent.SessionsChanged) event and treat it as a
    // DEBOUNCED, foreground-only reconcile trigger so an OUT-OF-BAND session
    // create/kill (another terminal, an agent spawning one) appears in the
    // picker within seconds — not the 15-min staleness gate. Null in unit tests
    // that don't exercise the live-event trigger.
    private val activeTmuxClients: ActiveTmuxClients? = null,
    // Issue #718: fetch the host's agent profiles (discovered server-side)
    // over the SAME warm SSH lease the gateway uses, replacing the old
    // client-stored per-host JSON. Null in unit tests that don't exercise the
    // picker profile fetch (the picker then falls back to the default-only
    // profile set).
    private val profilesGateway: ProfilesGateway? = null,
    // Epic #821 slice C (issue #837): the durable per-host tree registry seam
    // (`pocketshell tree get|upsert|reconcile` over the warm SSH session). On a
    // cold start it HYDRATES the held tree so the order + expand/collapse render
    // instantly; after a mutation it fire-and-forget UPSERTS the tree; on resume
    // it RECONCILEs gone/added as deltas. Null in unit tests that don't exercise
    // durability (the tree then behaves exactly as before — empty-until-probe).
    private val treeRemoteSource: TreeRemoteSource? = null,
    // Issue #867 (stale-while-revalidate): the per-host CLIENT-SIDE cold cache
    // of the last-rendered tree. On a fresh connect / cold app start the held
    // tree is empty (process death wiped [HostTreeModel]) and the durable #837
    // registry is HOST-side (reading it needs the warm SSH session — the very
    // round-trip whose gap produces the empty rebuild flash). This local cache
    // is read the instant [bind] runs (no SSH) and hydrated into the held tree
    // so the last-known tree paints INSTANTLY; the silent reconcile then stays
    // authoritative (advisory cache, D22). Null in unit tests that don't
    // exercise the instant cold render (the tree then shows the brief Loading
    // until the first reconcile, exactly as before).
    private val treeClientCache: TreeClientCache? = null,
    // Issue #885: the `pocketshell` version this app build expects on the host,
    // for the passive payload-version mismatch check. Defaults to reading the
    // installed app `versionName` (app + CLI ship in lockstep on every release
    // tag — `tools/pocketshell/pyproject.toml`). Injectable so unit tests can
    // pin it deterministically without a real PackageManager.
    private val expectedPocketshellVersionProvider: () -> String = expectedPocketshellVersionProvider@{
        val context = applicationContext ?: return@expectedPocketshellVersionProvider ""
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
    },
    // Issue #430: when true the view model attaches a
    // [ProcessLifecycleOwner] observer in [init] so the session-discovery
    // poll loop is gated on the whole-process foreground signal. Always
    // true on the production Hilt path (constructed on the main thread).
    // Connected tests that construct the VM off the main thread and drive
    // the gate via [setProcessStartedForTest] pass `false` so they never
    // touch the main-thread-affine lifecycle registry.
    attachLifecycle: Boolean = true,
    // Issue #783: when true the bound-host periodic (~5 min) reconcile heartbeat
    // ([startPeriodicReconcile]) runs while the tree screen is composed. Always
    // true on the production Hilt path. Defaults FALSE for the bare internal
    // constructor so the great majority of unit tests — which construct the VM
    // directly, call `bind`, but never `stopPolling` — are not left with an
    // infinite `delay`-loop coroutine that `runTest`'s end-of-body
    // `advanceUntilIdle` would chase forever. Tests that specifically exercise the
    // heartbeat flip it on via [setPeriodicReconcileEnabledForTest].
    periodicReconcileEnabled: Boolean = false,
) : ViewModel() {

    /**
     * Issue #783: gate for the bound-host periodic reconcile heartbeat — see the
     * `periodicReconcileEnabled` constructor param. Mutable so a focused test can
     * enable it on a directly-constructed VM via
     * [setPeriodicReconcileEnabledForTest].
     */
    private var periodicReconcileEnabled: Boolean = periodicReconcileEnabled

    /**
     * Issue #783 test seam: enable the periodic reconcile heartbeat on a
     * directly-constructed VM. Production gets it via the `@Inject` constructor.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setPeriodicReconcileEnabledForTest(enabled: Boolean) {
        periodicReconcileEnabled = enabled
    }

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
        assistantClientFactory: AssistantLlmClientFactory?,
        reposRemoteSource: ReposRemoteSource?,
        forwardingController: ForwardingController,
        sessionLifecycleSignals: com.pocketshell.app.tmux.SessionLifecycleSignals,
        // Issue #706: inject the SAME app-scoped singleton registry the gateway
        // and the dashboard use, so the live-`-CC`-client `%sessions-changed`
        // subscription rides the already-open control channel.
        activeTmuxClients: ActiveTmuxClients,
        // Issue #718: the host-discovered agent-profile fetch gateway.
        profilesGateway: ProfilesGateway,
        // Epic #821 slice C (issue #837): the durable tree registry seam.
        treeRemoteSource: TreeRemoteSource,
        // Issue #867: the per-host client-side cold cache for instant cold render.
        treeClientCache: TreeClientCache,
    ) : this(
        gateway = gateway,
        hostDao = hostDao,
        projectRootDao = projectRootDao,
        sshLeaseManager = sshLeaseManager,
        applicationContext = applicationContext,
        assistantClientFactory = assistantClientFactory,
        reposRemoteSource = reposRemoteSource,
        forwardingController = forwardingController,
        sessionLifecycleSignals = sessionLifecycleSignals,
        activeTmuxClients = activeTmuxClients,
        profilesGateway = profilesGateway,
        treeRemoteSource = treeRemoteSource,
        treeClientCache = treeClientCache,
        attachLifecycle = true,
        periodicReconcileEnabled = true,
    )

    private val _state: MutableStateFlow<FolderListUiState> =
        MutableStateFlow(FolderListUiState.Loading())
    val state: StateFlow<FolderListUiState> = _state.asStateFlow()

    private val _actionStatus: MutableStateFlow<FolderActionStatus> =
        MutableStateFlow(FolderActionStatus.Idle)
    val actionStatus: StateFlow<FolderActionStatus> = _actionStatus.asStateFlow()

    /**
     * Issue #885: passive host-CLI-version mismatch, detected from the
     * `pocketshell tree` payload's `cli_version` on every host open (warm/direct
     * included) — NOT a separate slow blocking `--version` exec. Non-null when
     * the host's `pocketshell` is OLDER than this app build expects; the
     * FolderList surfaces it as a small dismissible update prompt. Stays `null`
     * when the versions match, when the payload carries no version (old CLI), or
     * when the host is NEWER than the app (that is the app-behind case, not a
     * host-update prompt — see [PayloadVersionCheck.Verdict.AppOutdated]).
     */
    private val _cliVersionMismatch: MutableStateFlow<PayloadVersionCheck.Verdict.HostOutdated?> =
        MutableStateFlow(null)
    val cliVersionMismatch: StateFlow<PayloadVersionCheck.Verdict.HostOutdated?> =
        _cliVersionMismatch.asStateFlow()

    /** Dismiss the passive CLI-version update prompt (issue #885). */
    fun dismissCliVersionMismatch() {
        _cliVersionMismatch.value = null
    }

    /**
     * Issue #885: feed a payload-carried [hostCliVersion] (from `tree get` /
     * `tree reconcile`) through [PayloadVersionCheck] and raise the passive
     * update prompt when the host CLI is older than this app build. Visible for
     * test. A `null`/blank/unparseable version is "no signal" and never raises a
     * false prompt; a NEWER host CLI does not raise the prompt either.
     */
    internal fun observePayloadCliVersion(hostCliVersion: String?) {
        if (hostCliVersion.isNullOrBlank()) return
        when (val verdict = PayloadVersionCheck.evaluate(hostCliVersion, expectedPocketshellVersion())) {
            is PayloadVersionCheck.Verdict.HostOutdated -> _cliVersionMismatch.value = verdict
            else -> {
                // Match / AppOutdated: clear any stale prompt (e.g. the host was
                // updated since the last open).
                _cliVersionMismatch.value = null
            }
        }
    }

    /**
     * The `pocketshell` version this app build expects on the host. Resolved via
     * [expectedPocketshellVersionProvider] (the installed app `versionName` on
     * the production path; injectable in tests). Blank when the read fails, which
     * [PayloadVersionCheck.evaluate] treats as "no signal".
     */
    private fun expectedPocketshellVersion(): String = expectedPocketshellVersionProvider()

    /**
     * Issue #718: Claude Code profiles for this host, DISCOVERED on the host
     * and fetched via [ProfilesGateway] during [bind] (was the #627
     * client-stored JSON, hard-cut per D22). Empty when the host has only the
     * default profile, when the CLI is missing, or when the fetch fails (the
     * picker then shows no profile selector).
     */
    private val _claudeProfiles: MutableStateFlow<List<ClaudeProfile>> =
        MutableStateFlow(emptyList())
    val claudeProfiles: StateFlow<List<ClaudeProfile>> = _claudeProfiles.asStateFlow()

    /**
     * Issue #718: Codex profiles for this host, discovered on the host and
     * fetched via [ProfilesGateway] during [bind] (was the #631 client-stored
     * JSON, hard-cut per D22). Empty for the default-only / unavailable cases.
     */
    private val _codexProfiles: MutableStateFlow<List<CodexProfile>> =
        MutableStateFlow(emptyList())
    val codexProfiles: StateFlow<List<CodexProfile>> = _codexProfiles.asStateFlow()

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

    /**
     * Epic #821 slice C (issue #837): the warm SSH session becomes available
     * (`true`) once [replaceWarmLease] sets [warmLease]. The cold-start
     * tree-hydrate coroutine awaits this so it execs `tree.get` over the SAME
     * warm session the gateway uses (D21 — no new connection). Reset to `false`
     * on a host change / release.
     */
    private val warmSessionReady = MutableStateFlow(false)

    /** Epic #821 slice C: the cold-start tree-hydrate coroutine for this open. */
    private var hydrateTreeJob: Job? = null
    @androidx.annotation.VisibleForTesting
    internal var warmLeaseAcquiredForTest: (() -> Unit)? = null
    @androidx.annotation.VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Issue #702: outer bound on a single [runReconcile] gateway call so NO
     * gateway path (now or in future) can pin the picker in `Loading` forever.
     * The gateway already bounds its live `-CC` enumeration (#702) and its
     * SSH-lease exec reads (#470), but this is the last-line defence at the view
     * model: if the whole reconcile somehow out-waits those inner bounds, the
     * picker surfaces a retryable `ConnectError` panel instead of an indefinite
     * spinner. Generous relative to the sum of the inner bounds (live-enum +
     * lease enum + agent detection + watched-root expansion) so it only fires on
     * a true wedge, never on a slow-but-progressing reconcile. Test-overridable.
     */
    @androidx.annotation.VisibleForTesting
    internal var reconcileTimeoutMs: Long = RECONCILE_TIMEOUT_MS
    private var watchedFoldersJob: Job? = null
    private var probeJob: Job? = null

    /**
     * Issue #706 / #783: subscription to the bound host's live `tmux -CC`
     * client's `%sessions-changed` (ControlEvent.SessionsChanged) event. A burst
     * of `%sessions-changed` is debounced into a single reconcile trigger.
     *
     * Issue #783 lifecycle: this subscription is tied to the BOUND-HOST WARM-LEASE
     * lifetime, NOT `FolderListScreen` composition. It is started in [bind] (and
     * re-started on a host CHANGE), survives [stopPolling] (screen dispose), and
     * is torn down only on a host change or [onCleared]. This way an out-of-band
     * session change that lands while the user is on the session screen still
     * reconciles the tree the instant the event arrives — the prune no longer
     * lands on a dead collector. It rides the same already-open `-CC` event bus
     * via [ActiveTmuxClients] (the warm lease, D21) and is foreground-gated inside
     * [launchReconcile], so no second SSH/`-CC` connection is opened and nothing
     * runs while backgrounded.
     */
    private var sessionsChangedJob: Job? = null

    /**
     * Issue #653 / #783: subscription to the bound host's live `tmux -CC` client's
     * `%window-close @<id>` event ([ControlEvent.WindowClose]). When a single
     * tmux WINDOW closes (on the host, another terminal, an agent) while its
     * session stays alive, this prunes exactly that window node from the
     * maintained tree by window id — the window-level analogue of the
     * [sessionsChangedJob] whole-session path.
     *
     * Issue #783 lifecycle: like [sessionsChangedJob], this is tied to the
     * BOUND-HOST WARM-LEASE lifetime, NOT `FolderListScreen` composition. It is
     * started in [bind] (re-started on a host CHANGE), SURVIVES [stopPolling]
     * (screen dispose), and is torn down only on a host change or [onCleared].
     * This is the core #783 fix: a `%window-close` that arrives while the user is
     * NOT on the tree screen (e.g. they navigated into the session screen to
     * close a window on the host) used to land on a collector that `stopPolling`
     * had already cancelled, so the stale `[wN]` node lingered up to ~15 min.
     * Keeping the subscription alive for the bound host prunes the node the
     * instant the event arrives. It reuses the warm `-CC` client (D21 — no second
     * connection) and is foreground-gated by [processStarted].
     */
    private var windowCloseJob: Job? = null

    /**
     * Issue #783: the host id the event subscriptions ([sessionsChangedJob] /
     * [windowCloseJob] / [periodicReconcileJob]) are currently bound to. Lets
     * [bind] keep the live subscriptions running across a same-host re-bind (so
     * `stopPolling` → return does not restart them and miss an event in the gap),
     * while a host CHANGE tears them down and re-subscribes for the new host.
     * `null` while unbound.
     */
    private var subscriptionsHost: Long? = null

    /**
     * Issue #783: periodic (~5 min) reconcile while the tree screen is composed.
     * Out-of-band host changes that DON'T emit a control event on the open `-CC`
     * channel (so neither `%sessions-changed` nor `%window-close` fires) are
     * caught by this slow safety-net tick. Per the maintainer's tree spec this is
     * a freshness net for the SHOWN tree, so — unlike the event subscriptions
     * (which survive screen dispose, see [windowCloseJob]) — it follows the SCREEN
     * lifecycle: started in [bind], cancelled in [stopPolling]. That matches the
     * old discovery-probe lifecycle (it reuses the screen's own warm lease, not a
     * second connection) and avoids re-acquiring the warm lease for an undisplayed
     * screen (the probe lease is released on `stopPolling`).
     *
     * D21-clean: each tick's reconcile is gated on the foreground signal inside
     * [launchReconcile] (`processStarted.first { it }`), so while backgrounded the
     * loop parks at the gate and never runs background SSH work — a foreground
     * heartbeat, not a `Timer`/`WorkManager`/`AlarmManager`.
     */
    private var periodicReconcileJob: Job? = null

    /**
     * EPIC #679 — the maintained in-memory project tree. Held across opens of
     * the same host; only a host CHANGE resets it ([HostTreeModel.bindHost]).
     * Order, expansion, and bucket placement are intrinsic node state, and
     * app-initiated changes mutate it directly by id (#653/#678). A probe
     * becomes a reconcile (diff add/remove/update), never a from-scratch
     * rebuild — replacing the legacy `lastXxx` snapshot fields +
     * `stableSessionOrder` + `expandedProjectPaths` recompute that this view
     * model used to carry.
     */
    private val tree = HostTreeModel()
    private var lastWatchedFolders: List<ProjectRootEntity> = emptyList()
    private var rootSnapshotLoaded: Boolean = false
    private var lastDiscoveredPorts: List<HostDiscoveredPort> = emptyList()
    private var forwardingSnapshots: Map<Long, ForwardingHostSnapshot> = emptyMap()
    private var sessionRefreshInFlight: Boolean = false
    private var refreshSessionsRequested: Boolean = false

    /**
     * Issue #711: count of consecutive QUIET retries the current refresh has
     * already spent healing a transient transport drop (EOF / broken transport /
     * channel closed). Bounded by [TRANSIENT_REFRESH_RETRY_LIMIT] so a genuinely
     * unrecoverable host eventually surfaces the calm message instead of looping
     * forever. Reset to 0 on any successful reconcile and on a fresh [bind].
     */
    private var transientRefreshRetries: Int = 0
    /** Foreground generation observed by the last scheduled resume reconcile. */
    private var lastResumeGenerationHandled: Long = -1L

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
            // Issue #883: a confirmed single-window close (the session survived).
            // Drop ONLY that window row from this tree — siblings + the session
            // stay — then reconcile if the tree screen is still composed.
            viewModelScope.launch {
                signals.closedWindows.collect { closed ->
                    onWindowClosed(closed)
                }
            }
        }
        // EPIC #679 requirement #2: reconcile INFREQUENTLY, not on a constant
        // poll. The tree is held across opens, so a foreground/resume only
        // triggers a reconcile when the held tree is stale (or never
        // reconciled). D21-clean: this rides the existing [ProcessLifecycleOwner]
        // foreground signal, never a Timer/WorkManager/AlarmManager.
        viewModelScope.launch {
            processStarted.collect { started ->
                if (started) maybeReconcileOnResume()
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
        // EPIC #679: drop the dead row from the maintained tree directly by id.
        // The reconcile is a confirmation, not the trigger.
        if (tree.removeSession(killed.sessionName)) {
            emitReady()
        }
        // Only kick an authoritative reconcile when the tree screen is still
        // composed (a reconcile is already in flight / the screen is live). If
        // the user navigated onward (stopPolling cleared probeJob), the
        // optimistic drop stands and the reconcile rides the next bind/resume —
        // re-probing now would re-acquire the warm lease and race the session
        // screen's own attach, and a gateway that still reports the just-killed
        // session would resurrect the dropped row.
        if (probeJob != null) refresh()
    }

    /**
     * Issue #883: handle a confirmed single-window close broadcast from the
     * per-session screen (the parent session survived). Drops ONLY the closed
     * window's row from the maintained tree by its stable tmux id
     * ([HostTreeModel.removeWindow]) so sibling window rows + the session node
     * keep their slots, then — like [onSessionKilled] — reconciles only when
     * the tree screen is still composed (otherwise the optimistic drop stands
     * and the next bind/resume re-probe confirms it). Ignores other hosts.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onWindowClosed(closed: com.pocketshell.app.tmux.ClosedWindow) {
        val params = bound ?: return
        if (params.hostId != closed.hostId) return
        if (tree.removeWindow(closed.windowId)) {
            emitReady()
        }
        if (probeJob != null) refresh()
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

    /**
     * Epic #821 slice C (issue #837) test seam: mark the held tree stale so the
     * next foreground/resume runs a reconcile, letting the resume delta-refresh
     * path be exercised deterministically (the staleness gate uses the wall
     * clock, which a unit test cannot advance).
     */
    @androidx.annotation.VisibleForTesting
    internal fun forceTreeStaleForTest() {
        tree.markReconcileDueForTest()
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
        // Issue #783: keep the live `-CC` event subscriptions
        // (`%sessions-changed` + `%window-close`) and the periodic reconcile tied
        // to the BOUND-HOST lifetime, not screen composition. On a same-host
        // re-bind they keep running (no restart gap that could miss an event);
        // only a host CHANGE tears them down and re-subscribes. This is the
        // #783 core fix — a `%window-close` that lands while the user is on the
        // session screen still prunes the tree, because `stopPolling` no longer
        // cancels these jobs. They reuse the warm `-CC` client (D21, no second
        // connection) and are foreground-gated.
        ensureBoundHostSubscriptions(params)
        // Issue #783: (re)start the periodic ~5-min reconcile heartbeat for the
        // SHOWN tree. Unlike the event subscriptions it follows the screen
        // lifecycle (cancelled in `stopPolling`), so it is restarted on every
        // tree open. It reuses the screen's own warm lease (no second
        // connection) and is foreground-gated.
        startPeriodicReconcile(params)
        // EPIC #679 requirement #1: opening the host detail renders the HELD
        // tree INSTANTLY. Re-binding the SAME host reuses the maintained tree
        // (no probe-on-open, no loading flash) and only kicks a reconcile if one
        // is genuinely due (staleness gate). A host CHANGE resets the tree.
        if (bound == params && !tree.bindHost(hostId)) {
            // Same host: keep showing the held tree; re-project so a re-entry
            // reflects any by-id mutation that landed while away, then reconcile
            // only if stale.
            if (rootSnapshotLoaded) emitReady()
            maybeReconcileOnOpen(params)
            return
        }
        probeJob?.cancel()
        probeJob = null
        hydrateTreeJob?.cancel()
        hydrateTreeJob = null
        warmSessionReady.value = false
        bound = params
        tree.bindHost(hostId)
        rootSnapshotLoaded = false
        lastWatchedFolders = emptyList()
        sessionRefreshInFlight = false
        transientRefreshRetries = 0
        // Issue #867 (stale-while-revalidate): paint the last-known tree
        // INSTANTLY from the per-host CLIENT cache before any SSH, so a fresh
        // connect / cold app start no longer flashes the empty rebuild ("No
        // folders yet / 0 projects", everything in "Other folders", a spinner)
        // during the daemon round-trip + first probe. This is a LOCAL read (no
        // network) keyed off the host store, so it is available the instant
        // bind() runs. The cache is ADVISORY: the silent reconcile below stays
        // authoritative and overwrites the seeded placeholders in place (keyed
        // diff, no rebuild), and [HostTreeModel.hydrate] skips clobbering an
        // already-populated tree — so a stale cache entry can never survive past
        // the first refresh (#679 stale-type guard, D22).
        val seeded = hydrateFromClientCache(params)
        if (!seeded) {
            // No client cache yet (a genuinely new host / first-ever open): the
            // brief Loading until the first authoritative reconcile seeds it,
            // exactly as before — then the cache write below means the NEXT cold
            // start renders instantly.
            _state.value = loadingState()
        }
        // The maintained in-memory tree is held across opens of the SAME host
        // (so a re-open renders instantly), the daemon registry (#837) makes the
        // presentation state durable host-side, and this client cache makes the
        // FIRST cold render instant. `project_roots` (Room, D22-protected) still
        // supplies the watched-root overlay.

        // Issue #718: fetch the host-DISCOVERED agent profiles over the warm
        // SSH lease (was the #627/#631 client-stored JSON, hard-cut per D22).
        // The default-only / CLI-missing / fetch-failure cases all collapse to
        // an empty list, so the picker simply shows no profile selector.
        fetchProfiles(params)

        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            replaceWarmLease(params)
        }
        watchedFoldersJob?.cancel()
        watchedFoldersJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { rows ->
                lastWatchedFolders = rows
                tree.setWatchedFolders(rows)
                val firstSnapshot = !rootSnapshotLoaded
                rootSnapshotLoaded = true
                // EPIC #679: a reconcile fires only when one is due. On the very
                // first watched-folder snapshot the held tree has never been
                // reconciled, so this kicks the initial probe; afterwards a
                // watched-folder edit just re-projects (the bucket overlay
                // changed, the session set did not).
                if (firstSnapshot) {
                    // Epic #821 slice C (issue #837): on a cold start of a
                    // previously-opened host, HYDRATE the held tree from the
                    // durable registry BEFORE the freshening reconcile so the
                    // held order + expand/collapse render INSTANTLY (no Loading
                    // flash, no order shuffle). [hydrateTreeOnColdStart] seeds the
                    // tree, emits the held shape, and ONLY THEN runs the
                    // freshening reconcile — so the reconcile never races ahead of
                    // (and no-ops) the hydrate. Without a durable source it falls
                    // straight through to the reconcile, exactly as before.
                    hydrateTreeOnColdStart(params)
                } else {
                    // Re-emit so a watched-folder write surfaces immediately,
                    // mapping sessions into the new root overlay without a probe.
                    emitReady()
                }
            }
        }
    }

    /**
     * Issue #718: fetch the host-discovered agent profiles via
     * [ProfilesGateway] and split them by engine into the picker's
     * [claudeProfiles] / [codexProfiles] flows. Foreground, on bind. Any
     * non-success result (CLI missing, connect/parse failure, no gateway in
     * tests) leaves the flows empty so the picker shows no profile selector —
     * the safe default-only behaviour.
     */
    private fun fetchProfiles(params: BoundParams) {
        val gw = profilesGateway ?: run {
            _claudeProfiles.value = emptyList()
            _codexProfiles.value = emptyList()
            return
        }
        viewModelScope.launch {
            val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: return@launch
            val result = withContext(ioDispatcher) {
                gw.listProfiles(
                    host = host,
                    keyPath = params.keyPath,
                    passphrase = params.passphrase,
                )
            }
            // Ignore a stale result if the host changed while we were fetching.
            if (bound?.hostId != params.hostId) return@launch
            when (result) {
                is ProfilesResult.Profiles -> applyProfiles(result.profiles)
                else -> {
                    _claudeProfiles.value = emptyList()
                    _codexProfiles.value = emptyList()
                }
            }
        }
    }

    /**
     * Issue #718: project the host-discovered [RemoteProfile] rows onto the
     * picker's per-engine flows. The default profile is included so the picker
     * shows the full "Claude" / "Claude (Z.AI)" choice and pre-selects the
     * default; the launch command omits `--profile` for the default.
     */
    private fun applyProfiles(profiles: List<RemoteProfile>) {
        _claudeProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CLAUDE }
            .map { ClaudeProfile(name = it.name, default = it.default) }
        _codexProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CODEX }
            .map { CodexProfile(name = it.name, default = it.default) }
    }

    /**
     * Force a reconcile NOW. Wired to the screen's pull-to-refresh swipe
     * gesture (EPIC #679 requirement #4), the retry button on the error panel,
     * and the post-create reconcile after an app-initiated change. Unlike the
     * legacy 5 s poll this is an EXPLICIT, infrequent trigger — there is no
     * background loop; the held tree is otherwise reconciled only on a stale
     * foreground/resume.
     */
    fun refresh() {
        runReconcileNow()
    }

    /**
     * Manual host-detail pull-to-refresh (EPIC #679 requirement #4 — the swipe
     * gesture, NOT a button). Reuses the same reconcile path as [refresh], but
     * keeps the current Ready snapshot visible if the remote reconcile fails and
     * reports that failure via the non-displacing failure affordance. In-progress
     * feedback rides the non-displacing refresh progress bar
     * ([FolderListUiState.Ready.isRefreshing], #639), so no displacing
     * "Refreshing sessions" banner is emitted (#656).
     */
    fun refreshSessions() {
        if (_state.value is FolderListUiState.Ready) {
            refreshSessionsRequested = true
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
        chosenKind: SessionAgentKind? = null,
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
                    // EPIC #679 (#678 create side): the app KNOWS it just created
                    // this session, so insert it into the maintained tree by id
                    // immediately — optimistically — instead of waiting for the
                    // next reconcile to discover it ("created session/window
                    // slow-to-appear"). The node carries an optimistic grace so
                    // the reconcile that follows does not prune it before the
                    // probe has observed it; that same reconcile then confirms it
                    // and clears the grace.
                    //
                    // EPIC #821 Workstream A: the app already KNOWS the kind it
                    // just launched (the picker chose it, and the wrapper has
                    // recorded it host-side as `@ps_agent_kind`). Stamp that
                    // chosen kind onto the optimistic node instead of `Probing`
                    // so the tree shows the real kind from the moment of
                    // creation — no detection round-trip, no flicker through
                    // Probing. The sticky `mergeAgentKind` guard keeps this
                    // recorded kind across the reconcile that follows (which
                    // also re-reads it from the host option). A shell session
                    // (`chosenKind == null`) keeps the optimistic `Probing`
                    // placeholder until the reconcile confirms it as `Shell`.
                    tree.insertSession(
                        entry = FolderSessionEntry(
                            sessionName = resolvedName,
                            lastActivity = System.currentTimeMillis(),
                            attached = false,
                            agentKind = chosenKind ?: SessionAgentKind.Probing,
                        ),
                        folderPath = cwd,
                    )
                    emitReady()
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
                    // Issue #656: a successful stop emits no banner — the row
                    // dropping from the list below is the feedback. Reuse the
                    // existing kill reconcile path (issue #464):
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
                    // Issue #656: a successful rename emits no banner — the
                    // renamed row in the list is the feedback.
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
        // EPIC #679: rename by id on the maintained tree, preserving the slot.
        if (tree.renameSession(oldTarget, newTarget)) emitReady()
    }

    fun createEmptyProject(
        parentPath: String,
        folderName: String,
        onCreated: (String) -> Unit = {},
    ) {
        val params = bound ?: return
        viewModelScope.launch {
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
                    // Issue #656 / EPIC #679: a successful create emits no banner.
                    // Insert the known folder by id so it appears immediately
                    // (the reconcile confirms it later).
                    tree.insertOptimisticFolder(path, defaultLabelForPath(path))
                    emitReady()
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
                onSuccess = { _ ->
                    // Issue #656 / EPIC #679: a successful import emits no banner.
                    // Insert the known folder by id so it shows immediately.
                    tree.insertOptimisticFolder(folderPath, defaultLabelForPath(folderPath))
                    emitReady()
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
        // EPIC #679: expansion is intrinsic node state on the maintained tree
        // (#471 collapse-stickiness lives there now). Collapsing a folder pins
        // it collapsed across reconciles; expanding it restores auto-expand.
        tree.toggleProjectExpanded(projectPath)
        emitReady()
        // Epic #821 slice C (issue #837): persist the new collapse state so a
        // folder the user collapsed stays collapsed across an app kill +
        // relaunch. Fire-and-forget over the warm session.
        bound?.let { persistTree(it) }
        // Issue #867: mirror the collapse into the CLIENT cache so the next cold
        // start renders the collapsed folder collapsed without waiting on SSH.
        bound?.let { persistClientCache(it) }
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
        tree.sessionEntries().take(12).forEach { appendLine("- ${it.sessionName}") }
    }.trim()

    private fun recordAssistantCreatedProject(path: String) {
        val canonical = canonicalisePath(path)
        tree.insertOptimisticFolder(canonical, defaultLabelForPath(canonical))
        emitReady()
        refresh()
    }

    /**
     * EPIC #679 reconcile entry point for an OPEN (bind / first watched-folder
     * snapshot). Renders the held tree instantly and only kicks a reconcile when
     * one is genuinely due (the tree was never reconciled, or it is stale past
     * [HostTreeModel.RECONCILE_STALENESS_MS]). This is requirement #1: no
     * probe-on-open unless due.
     */
    private fun maybeReconcileOnOpen(params: BoundParams) {
        if (!rootSnapshotLoaded) {
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = loadingState()
            }
            return
        }
        lastResumeGenerationHandled = foregroundGeneration
        if (tree.reconcileDue(now = System.currentTimeMillis(), staleAfterMs = HostTreeModel.RECONCILE_STALENESS_MS)) {
            launchReconcile(params)
        } else {
            // Fresh held tree — render it immediately, no spinner.
            emitReady()
        }
    }

    /**
     * EPIC #679 requirement #2 + issue #706: a foreground/resume reconciles the
     * held tree when it is even mildly stale (older than [RESUME_FRESHEN_MS]),
     * NOT only past the 15-min held-tree gate. This closes the "created in
     * another terminal / by an agent while I was away, switch back" case: a real
     * foreground return freshens the picker within seconds. A rapid in-place
     * background→foreground bounce (held tree younger than [RESUME_FRESHEN_MS])
     * still does NOT re-probe, preserving #679's "no constant poll" intent.
     * D21-clean: rides the [ProcessLifecycleOwner] signal, no background timer.
     */
    private fun maybeReconcileOnResume() {
        val params = bound ?: return
        if (!rootSnapshotLoaded) return
        // Only react to a genuine new foreground generation.
        if (foregroundGeneration == lastResumeGenerationHandled) return
        lastResumeGenerationHandled = foregroundGeneration
        if (!tree.reconcileDue(now = System.currentTimeMillis(), staleAfterMs = RESUME_FRESHEN_MS)) {
            return
        }
        // Epic #821 slice C (issue #837): on a resume-when-stale, prefer the
        // CHEAP daemon delta reconcile (gone/added by name) over a full gateway
        // re-probe when the durable seam is available — "refresh to see which
        // sessions are gone, without reloading everything". A clean delta (no
        // additions) prunes gone sessions in place and avoids the heavier probe;
        // any addition (or an unavailable delta) falls back to the full
        // reconcile so the new session's content is fetched.
        if (treeRemoteSource != null && tree.hasSnapshot) {
            reconcileTreeDeltasOnResume(params)
        } else {
            launchReconcile(params)
        }
    }

    /**
     * Epic #821 slice C (issue #837): the delta-refresh path. Execs the daemon
     * `tree.reconcile` over the warm session, prunes the held tree's GONE
     * sessions in place (deltas only — no full reload), and only escalates to a
     * full [launchReconcile] when the delta reports ADDED sessions (whose content
     * a probe must fetch) or when the delta is unavailable. Persists the pruned
     * tree so the registry stays clean.
     */
    private fun reconcileTreeDeltasOnResume(params: BoundParams) {
        val source = treeRemoteSource ?: run { launchReconcile(params); return }
        viewModelScope.launch {
            processStarted.first { it }
            val session = warmLease?.session ?: awaitWarmSession()
            if (session == null) {
                if (bound == params) launchReconcile(params)
                return@launch
            }
            if (bound != params) return@launch
            val delta = withTimeoutOrNull(reconcileTimeoutMs + 1_000L) {
                source.reconcileTree(session, params.hostName)
            }
            if (bound != params) return@launch
            // Issue #885: the reconcile payload also carries the host CLI
            // version (it fires on every open + resume), so the passive check
            // stays live even when `tree get` returned an empty seed.
            observePayloadCliVersion(delta?.cliVersion)
            if (delta == null || delta.added.isNotEmpty()) {
                // Unavailable or new sessions appeared → full probe.
                launchReconcile(params)
                return@launch
            }
            // Gone-only delta: prune in place, re-emit, persist. No full reload.
            if (tree.applyReconcileGoneDelta(delta.gone)) {
                emitReady()
                persistTree(params)
                // Issue #867: keep the client cache in step with the pruned tree.
                persistClientCache(params)
            }
        }
    }

    /**
     * Epic #821 slice C (issue #837): seed the held tree from the durable
     * host-side registry on a cold start, BEFORE the freshening reconcile, so
     * the held order + expand/collapse render INSTANTLY (no Loading flash, no
     * order shuffle). Awaits the warm session (bounded), execs `tree.get` over
     * it (D21 — no new connection), hydrates the [HostTreeModel], and emits the
     * seeded tree immediately. A no-op when there is no [treeRemoteSource]
     * (unit tests) or no persisted nodes (a genuinely new host stays
     * empty-until-probe, exactly as before). [HostTreeModel.hydrate] itself
     * skips clobbering an already-populated tree, so a reconcile that beat the
     * hydrate is never overwritten.
     *
     * ## Fail-safe: the hydrate NEVER gates connect (issue #847)
     *
     * The hydrate is a pure PRESENTATION optimisation (instant held order +
     * collapse). The freshening reconcile is what actually POPULATES the tree
     * and clears "loading tree". v0.4.10 shipped a release-breaking regression
     * (#847): on a host whose `pocketshell` CLI is OLDER than the client (no
     * `tree` subcommand), the hydrate's `tree get` errored, and because the
     * freshening reconcile was CHAINED AFTER the hydrate with NO bound on the
     * `tree get`, a slow/failed/wedged `tree get` left the reconcile un-run and
     * the screen pinned on "loading tree" forever — the app would not connect.
     *
     * The fix keeps the hydrate-BEFORE-reconcile ordering (so a successful
     * `tree get` still seeds the held order + collapse memory before the
     * reconcile lands — the #837 durability contract, see
     * [FolderListViewModelTreeDurabilityTest]) but makes it FAIL-SAFE:
     *  - the hydrate body (warm-session await + `tree get` + parse) is BOUNDED
     *    by [HYDRATE_TIMEOUT_MS], so a wedged/never-returning old-CLI `tree get`
     *    can no longer suspend the coroutine forever; and
     *  - the freshening reconcile runs in a `finally`, so it ALWAYS fires —
     *    whether the hydrate seeded, returned nothing, errored, or timed out —
     *    and connect can never hang behind `tree get`.
     * `getTree` is itself exit-code-guarded + catch-all, so a missing/old/
     * erroring CLI yields an empty seed promptly and the `finally` reconcile
     * runs without delay; the timeout is the last-line defence for a true wedge.
     *
     * NO POLLING: this runs ONCE per cold-start open.
     */
    private fun hydrateTreeOnColdStart(params: BoundParams) {
        val source = treeRemoteSource
        if (source == null) {
            // No durable source (unit tests / never-installed daemon): behave
            // exactly as before — straight to the freshening reconcile.
            maybeReconcileOnOpen(params)
            return
        }
        hydrateTreeJob?.cancel()
        hydrateTreeJob = viewModelScope.launch {
            // Foreground-gated, mirroring the reconcile path (D21-clean).
            processStarted.first { it }
            var hostChanged = false
            try {
                // Bound the best-effort seed so a wedged/old host CLI can't keep
                // this coroutine alive: a missing `tree` command, a parse failure,
                // or a never-returning exec all collapse to "no seed", and the
                // `finally` reconcile below is the authoritative tree.
                withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
                    val session = awaitWarmSession() ?: return@withTimeoutOrNull
                    if (bound != params) {
                        hostChanged = true
                        return@withTimeoutOrNull
                    }
                    // `getTree` returns an empty list for a missing/old/erroring
                    // CLI and never throws except on cancellation. Run it in a
                    // CHILD coroutine on [ioDispatcher] so a TRUE wedge (the #470
                    // blocking stdout read inside `exec`, which a plain
                    // `withTimeout` can't interrupt because the read sits in a
                    // non-cancellable `Dispatchers.IO` block) is still bounded:
                    // when [HYDRATE_TIMEOUT_MS] expires the parent is cancelled at
                    // `await()` — a real suspension point — and the orphaned read
                    // is reaped with the warm lease. In unit tests [ioDispatcher]
                    // is the virtual test dispatcher, so a fast `getTree` resolves
                    // synchronously and the hydrate-before-reconcile ordering is
                    // preserved (the #837 durability contract).
                    val treeResult = withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
                        async(ioDispatcher) { source.getTree(session, params.hostName) }.await()
                    } ?: return@withTimeoutOrNull
                    if (bound != params) {
                        hostChanged = true
                        return@withTimeoutOrNull
                    }
                    // Issue #885: passive host-CLI-version check from the SAME
                    // payload the client already round-trips on every open — no
                    // separate blocking `--version` exec, and consistent because
                    // this fires on warm/direct opens too (not only home→open).
                    observePayloadCliVersion(treeResult.cliVersion)
                    val nodes = treeResult.nodes
                    if (nodes.isNotEmpty()) {
                        tree.hydrate(nodes.map { it.toHydratedNode() })
                        // Render the seeded order/collapse immediately — instant
                        // held shape, no Loading flash, BEFORE the reconcile.
                        if (rootSnapshotLoaded) emitReady()
                    }
                }
            } finally {
                // Issue #847: the freshening reconcile is the load-bearing step —
                // it populates the tree and clears "loading tree", so it MUST fire
                // whether the hydrate seeded, no-op'd, errored, or timed out. Skip
                // only when the host actually changed (a new bind drives its own
                // hydrate/reconcile) or this job was cancelled.
                if (!hostChanged && bound == params) maybeReconcileOnOpen(params)
            }
        }
    }

    /**
     * Issue #867 (stale-while-revalidate): seed the held tree from the per-host
     * CLIENT cache the instant [bind] runs — a LOCAL read (no SSH), so the
     * last-known tree paints INSTANTLY and the empty rebuild flash never shows.
     *
     * Runs in [bind]'s cold-start path BEFORE the Loading state is set and
     * before the daemon hydrate / first reconcile. [HostTreeModel.hydrate] is a
     * no-op once the tree already has a snapshot, so a probe that somehow beat
     * this never gets clobbered by the (older) cache seed. Returns `true` when
     * the cache seeded ≥1 node (so the caller skips the Loading flash and emits
     * the held shape immediately), `false` when there is no cache yet.
     *
     * The seed is ADVISORY: the silent reconcile stays authoritative and
     * overwrites these placeholders in place (keyed diff). A cache entry the
     * probe no longer reports is pruned by the first reconcile, so a stale entry
     * never survives past the first refresh (#679 stale-type guard, D22).
     */
    private fun hydrateFromClientCache(params: BoundParams): Boolean {
        val cache = treeClientCache ?: return false
        val cached = runCatching { cache.read(params.hostName) }
            .getOrDefault(TreeClientCache.CachedTree(nodes = emptyList()))
        if (cached.isEmpty) return false
        // Issue #867 (REOPEN): seed the watched-root overlay + the structural
        // maps the grouping needs BEFORE the per-session hydrate's emit, so the
        // FIRST cold-start frame already shows the SETTLED tree — sessions
        // bucketed under their watched root, the project subfolders + counts
        // visible — not "0 projects" with everything dumped into "Other
        // folders". The authoritative Room `project_roots` Flow overwrites the
        // seeded watched folders the moment it emits (advisory), and the first
        // reconcile overwrites the structural maps wholesale.
        if (cached.watchedFolders.isNotEmpty()) {
            tree.setWatchedFolders(cached.watchedFolders)
        }
        // Hydrate the per-session nodes FIRST (populates `sessionFolderPaths`),
        // then the structure — so [HostTreeModel.hydrateStructure]'s sticky-bucket
        // seed sees the sessions and can place them under their root by id.
        tree.hydrate(cached.nodes.map { it.toHydratedNode() })
        tree.hydrateStructure(
            resolvedWatchedRootPaths = cached.resolvedWatchedRootPaths,
            scannedProjectFoldersByRoot = cached.scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = cached.historyProjectFoldersByRoot,
        )
        if (!tree.hasSnapshot) return false
        // Render the seeded order / placement / collapse + grouping immediately —
        // instant held shape, no Loading flash, no empty-rebuild grouping. The
        // confirmed kinds arrive with the first reconcile and update in place.
        emitReady()
        return true
    }

    /**
     * Issue #867: persist the current tree state to the per-host CLIENT cache so
     * the NEXT cold start renders instantly. Fire-and-forget on a background
     * dispatcher; mirrors [persistTree]'s host-side upsert but to the local
     * advisory cache. A no-op without a [treeClientCache]; an empty tree deletes
     * the cache file.
     */
    private fun persistClientCache(params: BoundParams) {
        val cache = treeClientCache ?: return
        // Issue #867 (REOPEN): snapshot the FULL presentation shape — the session
        // nodes PLUS the watched-root overlay + the structural maps — so the next
        // cold start can reproduce the SETTLED grouping instantly (sessions under
        // their root, project counts), not just the flat session list.
        val structure = tree.exportStructure()
        val cached = TreeClientCache.CachedTree(
            nodes = tree.exportNodes().map { it.toTreeNode() },
            watchedFolders = lastWatchedFolders,
            resolvedWatchedRootPaths = structure.resolvedWatchedRootPaths,
            scannedProjectFoldersByRoot = structure.scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = structure.historyProjectFoldersByRoot,
        )
        viewModelScope.launch(ioDispatcher) {
            runCatching { cache.write(params.hostName, cached) }
        }
    }

    /**
     * Epic #821 slice C (issue #837): fire-and-forget persist of the current
     * tree state (order + folder paths + collapse memory + foreign-guess) to the
     * durable registry after a mutation (toggle-expanded, reconcile). Execs
     * `tree.upsert` over the warm session. A no-op without a [treeRemoteSource];
     * any failure is swallowed (the tree is still correct in memory; the next
     * mutation re-persists).
     */
    private fun persistTree(params: BoundParams) {
        val source = treeRemoteSource ?: return
        val nodes = tree.exportNodes()
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            val session = warmLease?.session ?: awaitWarmSession() ?: return@launch
            if (bound != params) return@launch
            withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
                source.upsertTree(
                    session = session,
                    host = params.hostName,
                    nodes = nodes.map { it.toTreeNode() },
                )
            }
        }
    }

    /**
     * Await the warm SSH session for a bounded window. Returns the live session
     * once [replaceWarmLease] has acquired it, or `null` if it does not appear
     * in time (so a hydrate/persist that loses the connect race simply skips —
     * the tree stays correct, the next probe/mutation re-seeds/re-persists).
     */
    private suspend fun awaitWarmSession(): SshSession? {
        warmLease?.session?.let { return it }
        val ready = withTimeoutOrNull(WARM_SESSION_AWAIT_MS) {
            warmSessionReady.first { it }
        }
        return if (ready == true) warmLease?.session else null
    }

    /**
     * Issue #847: establish the warm SSH connection BEFORE the bounded
     * enumeration in [runReconcile], so the cold sshj dial (≤ the lease
     * manager's ~35s connect timeout) is NOT charged against the 12s
     * enumeration budget. This is the structural fix for the v0.4.10/v0.4.11
     * "Session list didn't load within 12000ms" connect break: when the
     * bootstrap "Host setup needed" sheet is dismissed via Skip its warm
     * `warm-host-connect` lease is released, so the first reconcile would need a
     * FRESH cold dial — and that dial, wrapped by the 12s enumeration bound,
     * blew the budget on a real/slow network and surfaced the spurious error
     * even though the host was connectable.
     *
     * Fast paths (no behaviour change for the healthy / warm-reuse case):
     * - a still-connected warm lease is reused immediately;
     * - a live `-CC` control client makes the enumeration leaseless, so we do
     *   not block on a lease the gateway won't open.
     *
     * Otherwise we drive [replaceWarmLease] (re-using the in-flight bind-time
     * [warmJob] when present) and await its completion. The acquire's own
     * connect timeout bounds it — a slow-but-valid connect succeeds and the
     * gateway acquire inside the enumeration window is then a fast REUSE of the
     * live transport. A genuine connect failure completes [warmJob] promptly
     * (no warm lease), and the bounded enumeration below opens its own lease and
     * surfaces an honest retryable connect error — never a spurious 12s timeout
     * on a connectable host.
     */
    private suspend fun ensureWarmConnectForReconcile(params: BoundParams) {
        if (warmLease?.session?.isConnected == true) return
        // A live `-CC` client serves the enumeration without a lease (the
        // gateway's [listSessionRowsFromLiveClient] fast path), so there is
        // nothing to pre-connect — don't block the leaseless path.
        val liveClient = activeTmuxClients?.clients?.value?.get(params.hostId)
            ?.takeIf { it.matches(params) }
            ?.takeUnless { it.client.disconnected.value }
        if (liveClient != null) return
        val existing = warmJob
        if (existing != null && existing.isActive) {
            // Re-use the bind-time connect already in flight (the dominant case
            // on a fresh open / after a bootstrap-Skip re-bind, which launches a
            // fresh [warmJob] before this reconcile runs). Its acquire is bounded
            // by the lease manager's OWN connect timeout (~35s), NOT the 12s
            // enumeration budget — so a slow-but-valid cold dial completes here,
            // OUTSIDE the window, and the gateway acquire below is then a fast
            // REUSE. A connect failure also completes the job promptly (no warm
            // lease), and the bounded enumeration surfaces the honest error.
            existing.join()
        } else if (warmLease == null && (existing == null || existing.isCancelled)) {
            // No warm lease, and the bind-time connect is absent or was CANCELLED
            // (not merely failed): drive ONE fresh acquire OUTSIDE the
            // enumeration window so a slow cold dial isn't charged to the 12s
            // budget. A connect that already RAN and failed is NOT retried here —
            // the bounded enumeration's own gateway acquire surfaces the honest
            // retryable connect error, and re-dialing would just waste a handshake
            // and (worse) consume virtual time before the window opens.
            val job = viewModelScope.launch { replaceWarmLease(params) }
            warmJob = job
            job.join()
        }
    }

    private fun TreeRemoteSource.TreeNode.toHydratedNode(): HostTreeModel.HydratedNode =
        HostTreeModel.HydratedNode(
            sessionName = session,
            order = order,
            folderPath = folderPath,
            collapsed = collapsed,
            foreignGuess = foreignKind.toSessionAgentKindOrNull(),
        )

    private fun HostTreeModel.HydratedNode.toTreeNode(): TreeRemoteSource.TreeNode =
        TreeRemoteSource.TreeNode(
            session = sessionName,
            order = order,
            folderPath = folderPath,
            collapsed = collapsed,
            foreignKind = foreignGuess?.toRegistryKindString(),
        )

    private fun String?.toSessionAgentKindOrNull(): SessionAgentKind? = when (this) {
        "claude" -> SessionAgentKind.Claude
        "codex" -> SessionAgentKind.Codex
        "opencode" -> SessionAgentKind.OpenCode
        else -> null
    }

    private fun SessionAgentKind.toRegistryKindString(): String? = when (this) {
        SessionAgentKind.Claude -> "claude"
        SessionAgentKind.Codex -> "codex"
        SessionAgentKind.OpenCode -> "opencode"
        else -> null
    }

    /**
     * Issue #706: subscribe the held tree to the bound host's live `tmux -CC`
     * client's `%sessions-changed` (ControlEvent.SessionsChanged) event and treat
     * it as a DEBOUNCED, foreground-only reconcile trigger. This is the core fix
     * for the out-of-band-session gap: a session created/killed outside the app
     * (another terminal, an agent spawning one) emits `%sessions-changed` on the
     * already-open control channel, which here schedules a debounced reconcile so
     * the new/dead row appears within seconds — instead of staying invisible
     * until the 15-min staleness gate or a manual pull-to-refresh.
     *
     * D21-clean: NO poll, NO Timer/AlarmManager/WorkManager. It rides the
     * existing hot `-CC` event bus (the SAME `%sessions-changed`
     * [TmuxSessionViewModel]/[SessionsDashboardViewModel] already consume) and
     * the reconcile itself is foreground-gated inside [launchReconcile]
     * (`processStarted.first { it }`), so nothing runs while backgrounded.
     *
     * Tracks [ActiveTmuxClients.clients] with [collectLatest] so the
     * subscription always follows the live client for [params]'s host: if the
     * client (re)registers (a reconnect), the event collector re-attaches to the
     * fresh client; if it deregisters, the inner collector is cancelled and we
     * idle until one reappears. [debounce] coalesces a `%sessions-changed` burst
     * into one reconcile.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startSessionsChangedSubscription(params: BoundParams) {
        val registry = activeTmuxClients ?: return
        sessionsChangedJob?.cancel()
        sessionsChangedJob = viewModelScope.launch {
            registry.clients
                .map { snapshot -> snapshot[params.hostId]?.takeIf { it.matches(params) }?.client }
                .distinctUntilChanged()
                .collectLatest { client ->
                    if (client == null) return@collectLatest
                    client.events
                        .filter { it is ControlEvent.SessionsChanged }
                        .debounce(SESSIONS_CHANGED_DEBOUNCE_MS)
                        .collect {
                            // Only react while this host is still the bound one and
                            // the tree is initialised. The reconcile is itself
                            // foreground-gated inside launchReconcile.
                            if (bound == params && rootSnapshotLoaded) {
                                launchReconcile(params)
                            }
                        }
                }
        }
    }

    /**
     * Issue #706: does this live-client registry entry describe the SAME host
     * connection as [params]? Mirrors the gateway's
     * `ActiveTmuxClients.Entry.matches` (host/port/user/keyPath) so the
     * `%sessions-changed` subscription only fires for the host actually shown,
     * never a same-id-but-different-credential entry.
     */
    private fun ActiveTmuxClients.Entry.matches(params: BoundParams): Boolean =
        hostname == params.hostname &&
            port == params.port &&
            username == params.username &&
            keyPath == params.keyPath

    /**
     * Issue #653: subscribe the held tree to the bound host's live `tmux -CC`
     * client's `%window-close @<id>` event ([ControlEvent.WindowClose]) and treat
     * it as a DIRECT, by-id window prune. When a single tmux window is closed
     * remotely (another terminal, an agent, a `kill-window`) while its session
     * stays alive, tmux emits `%window-close @<id>` on the already-open control
     * channel; this drops exactly that window node from the maintained tree
     * ([HostTreeModel.removeWindow]) — sibling windows and the parent session
     * keep their slots — and re-projects, INCREMENTALLY, with no full reconcile.
     *
     * The whole-session analogue is [startSessionsChangedSubscription]; a window
     * close that takes the session's last window also surfaces as
     * `%sessions-changed`, which that path prunes as a whole session. There is NO
     * debounce here: each `%window-close` carries a distinct id, so coalescing a
     * burst would risk dropping one of several simultaneous closes — each prune
     * is a cheap in-place mutation, so they are applied one-for-one.
     *
     * D21-clean: NO poll, NO Timer/AlarmManager/WorkManager. It rides the same
     * hot `-CC` event bus as `%sessions-changed`, follows the live client with
     * [collectLatest] across reconnects, and only mutates while the app is
     * foregrounded ([processStarted]) and this host is still bound — so nothing
     * runs while backgrounded.
     */
    private fun startWindowCloseSubscription(params: BoundParams) {
        val registry = activeTmuxClients ?: return
        windowCloseJob?.cancel()
        windowCloseJob = viewModelScope.launch {
            registry.clients
                .map { snapshot -> snapshot[params.hostId]?.takeIf { it.matches(params) }?.client }
                .distinctUntilChanged()
                .collectLatest { client ->
                    if (client == null) return@collectLatest
                    client.events
                        .filterIsInstance<ControlEvent.WindowClose>()
                        .collect { event ->
                            // Only mutate while this host is still bound, the tree
                            // is initialised, and the app is foregrounded (D21).
                            if (bound == params && rootSnapshotLoaded && processStarted.value) {
                                if (tree.removeWindow(event.windowId)) {
                                    emitReady()
                                }
                            }
                        }
                }
        }
    }

    /**
     * Issue #783: (re)bind the bound-host EVENT subscriptions
     * ([startSessionsChangedSubscription] / [startWindowCloseSubscription]) for
     * [params]'s host.
     *
     * Idempotent per host: a same-host re-bind (returning to the tree screen)
     * keeps the already-running subscriptions, so there is NO restart gap in
     * which a `%window-close` / `%sessions-changed` could fall on a cancelled
     * collector. A host CHANGE tears the old subscriptions down and starts fresh
     * ones for the new host. Tied to the bound-host warm-lease lifetime — NOT
     * `FolderListScreen` composition — so the prune survives `stopPolling`.
     *
     * NOTE: the periodic reconcile heartbeat ([startPeriodicReconcile]) is NOT
     * part of this — it follows the SCREEN lifecycle ([bind]/[stopPolling]), see
     * its docs.
     */
    private fun ensureBoundHostSubscriptions(params: BoundParams) {
        if (subscriptionsHost == params.hostId &&
            sessionsChangedJob?.isActive == true &&
            windowCloseJob?.isActive == true
        ) {
            // Same host, both subscriptions live — keep them so a return to the
            // tree screen doesn't reopen the dead-collector window.
            return
        }
        startSessionsChangedSubscription(params)
        startWindowCloseSubscription(params)
        subscriptionsHost = params.hostId
    }

    /**
     * Issue #783: tear down the bound-host EVENT subscriptions. Called on a host
     * change (before re-subscribing) and from [onCleared]. NOT called from
     * [stopPolling] — that is the whole point: the subscriptions outlive screen
     * composition so a `%window-close` while the user is on the session screen
     * still prunes the tree. (The periodic heartbeat is cancelled separately by
     * [stopPolling]/[onCleared].)
     */
    private fun tearDownBoundHostSubscriptions() {
        sessionsChangedJob?.cancel()
        sessionsChangedJob = null
        windowCloseJob?.cancel()
        windowCloseJob = null
        subscriptionsHost = null
    }

    /**
     * Issue #783: start the periodic (~5 min) reconcile heartbeat for the SHOWN
     * tree screen. Started in [bind] and cancelled in [stopPolling] (screen
     * dispose) / [onCleared] — it follows the screen, not the bound-host event
     * subscriptions, because it is a freshness net for the tree the user is
     * looking at and reuses the screen's own warm lease (no second connection).
     *
     * D21-clean: each tick's reconcile is gated on the foreground signal inside
     * [launchReconcile] (`processStarted.first { it }`), so while backgrounded the
     * loop parks at the gate and performs no SSH work — a foreground heartbeat,
     * not a `Timer`/`WorkManager`/`AlarmManager`.
     */
    private fun startPeriodicReconcile(params: BoundParams) {
        periodicReconcileJob?.cancel()
        periodicReconcileJob = null
        // Issue #783: disabled for the bare internal constructor (most unit
        // tests) so a directly-constructed VM that never calls `stopPolling`
        // does not leave an infinite `delay`-loop for `runTest` to chase.
        if (!periodicReconcileEnabled) return
        periodicReconcileJob = viewModelScope.launch {
            while (true) {
                delay(PERIODIC_RECONCILE_MS)
                if (bound != params || !rootSnapshotLoaded) continue
                // launchReconcile is itself foreground-gated, so a tick that
                // fires while backgrounded suspends at the gate instead of
                // probing a dead lease.
                launchReconcile(params)
            }
        }
    }

    /**
     * Force a reconcile NOW regardless of staleness — the explicit
     * pull-to-refresh swipe (requirement #4), error-panel retry, and the
     * post-app-action confirmation. Gated only on the foreground signal (D21).
     */
    private fun runReconcileNow() {
        val params = bound ?: return
        if (!rootSnapshotLoaded) {
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = loadingState()
            }
            return
        }
        launchReconcile(params)
    }

    private fun launchReconcile(params: BoundParams) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = loadingState()
            }
            // D21 / #430: gate the reconcile on the whole-process foreground
            // signal. While backgrounded this suspends (no background SSH work);
            // it releases on the next ON_START.
            processStarted.first { it }
            setSessionRefreshInFlight(true)
            // Issue #939 (audit #935 S5-1): [runReconcile] sets
            // `sessionRefreshInFlight = true` then runs an unguarded suspend body
            // (`tree.reconcile`, `persistTree`/`persistClientCache`, the
            // `hostDao.getById` Room read, the gateway enumeration). Before this
            // guard, ANY throw between the flag set and one of `runReconcile`'s
            // explicit `setSessionRefreshInFlight(false)` clears escaped without
            // ever releasing the flag — the cold-launch session-picker refresh
            // bar then spun FOREVER with no error band, and the in-flight guard at
            // [setSessionRefreshInFlight] made a re-tap a no-op (the user could
            // not retry without leaving + re-binding the host). The `finally`
            // guarantees the flag is released on EVERY exit (success, handled
            // failure, OR an unexpected throw); the `catch` surfaces a retryable
            // error so the picker is escapable instead of silently wedged.
            // CancellationException (stopPolling / host change / onCleared) is
            // rethrown so structured cancellation is preserved.
            try {
                runReconcile(params)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                surfaceUnexpectedReconcileFailure(params, t)
            } finally {
                // Idempotent: the `when` arms in [runReconcile] already clear the
                // flag on every normal exit, and [setSessionRefreshInFlight]
                // no-ops when it is already false. This `finally` is the backstop
                // for the throwing paths only.
                setSessionRefreshInFlight(false)
            }
        }
    }

    /**
     * Issue #939 (audit #935 S5-1): an UNEXPECTED throw inside [runReconcile]
     * (e.g. an `SQLiteException` from the host read, a `persistTree` IO error, or
     * a `tree.reconcile` projection fault) must NOT leave the picker silently
     * stuck on the refresh spinner. Surface the same calm, RETRYABLE error the
     * `ConnectFailed` arm uses: keep the last-known tree visible with a
     * dismissable refresh-failure banner when one is already painted, otherwise
     * show the retryable [FolderListUiState.ConnectError] panel. Either way the
     * user has an escape (Retry / the banner clears on the next success) instead
     * of a frozen spinner.
     */
    private fun surfaceUnexpectedReconcileFailure(params: BoundParams, cause: Throwable) {
        // A reconcile for a host the user has since navigated away from must not
        // clobber the now-current binding's state.
        if (bound != params) return
        if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
        _state.value = FolderListUiState.ConnectError(
            message = connectErrorCopy(cause),
            cause = cause,
        )
    }

    /**
     * Stop any in-flight reconcile — wired to the screen's
     * `DisposableEffect.onDispose` so navigating away (e.g. tapping a session
     * row → TmuxSessionScreen) frees the SSH probe channel for the next
     * destination immediately. The MAINTAINED TREE survives (held across opens),
     * so returning renders it instantly without a fresh probe (#679 req #1).
     */
    fun stopPolling() {
        probeJob?.cancel()
        probeJob = null
        // Issue #783: the `%sessions-changed` / `%window-close` EVENT
        // subscriptions are DELIBERATELY NOT torn down here. They are tied to the
        // bound-host warm-lease lifetime (see [ensureBoundHostSubscriptions]), not
        // screen composition, so a window/session closed on the host (or in
        // another terminal) while the user is on the session screen still prunes
        // the maintained tree the instant the event arrives — no dead-collector
        // drop, no ~15-min staleness wait. They reuse the registry's live `-CC`
        // client (D21, no second connection) and are foreground-gated, and are
        // torn down only on a host change or [onCleared].
        //
        // The periodic ~5-min reconcile heartbeat DOES stop here — it is a
        // freshness net for the SHOWN tree, so it follows screen composition like
        // the old discovery probe and must not re-acquire the warm lease (just
        // scheduled for release below) for an undisplayed screen.
        periodicReconcileJob?.cancel()
        periodicReconcileJob = null
        scheduleWarmRelease()
    }

    private suspend fun runReconcile(params: BoundParams) {
        val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
            setSessionRefreshInFlight(false)
            _state.value = FolderListUiState.Failed("Host not found.")
            return
        }
        // Issue #847: establish the SSH connection OUTSIDE the enumeration window
        // below. The cold sshj dial is bounded by the lease manager's connect
        // timeout (~35s), which is LONGER than the 12s reconcile bound — wrapping
        // the dial in the 12s window (as before) meant a slow-but-valid connect
        // (e.g. the FRESH cold dial needed after the bootstrap "Host setup
        // needed" sheet is dismissed via Skip, which releases the warm
        // `warm-host-connect` lease) blew the budget and surfaced the spurious
        // "Session list didn't load within 12000ms" error even though the host
        // was connectable. Pre-establishing the warm lease here (its OWN connect
        // bound, decoupled from the enumeration timer) makes the gateway acquire
        // inside the bounded enumeration a fast REUSE of the live transport, and
        // the picker shows Loading ("Connecting…") while the connect is in
        // flight — never a hard 12s error on a connectable host.
        ensureWarmConnectForReconcile(params)
        if (bound != params) return
        // Issue #702: bound the ENUMERATION (not the connect). The gateway already
        // self-bounds its live `-CC` enumeration and SSH-lease reads, but a
        // `withTimeout` here guarantees that NO gateway path can ever pin the
        // picker in `Loading` indefinitely — on expiry we surface a retryable
        // error panel instead of an endless spinner. With the warm lease already
        // established above, this window now covers only the enumeration round
        // trip(s), never a cold dial (issue #847).
        val result = withTimeoutOrNull(reconcileTimeoutMs) {
            gateway.listSessionsWithFolder(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                watchedRoots = lastWatchedFolders,
            )
        } ?: FolderListResult.ConnectFailed(
            FolderReconcileTimeoutException(reconcileTimeoutMs),
        )
        if (bound != params) return
        when (result) {
            is FolderListResult.Sessions -> {
                val sessionEntries = result.rows.map { it.toSessionEntry() }
                val folderPaths = result.rows.associate { row ->
                    row.sessionName to (row.cwd?.let(::canonicalisePath) ?: UNTRACKED_PATH)
                }
                // EPIC #679: reconcile the held tree (diff add/remove/update by
                // id) instead of overwriting snapshot fields + a from-scratch
                // rebuild.
                tree.reconcile(
                    HostTreeModel.ProbeSnapshot(
                        sessions = sessionEntries,
                        folderPaths = folderPaths,
                        scannedProjectFoldersByRoot = result.projectFoldersByRoot,
                        historyProjectFoldersByRoot = result.historyProjectFoldersByRoot,
                        resolvedWatchedRootPaths = result.resolvedWatchedRootPaths,
                    ),
                )
                // Issue #456/#602: filter discovery to interesting ports.
                lastDiscoveredPorts = InterestingPortFilter.filter(result.discoveredPorts).map { port ->
                    HostDiscoveredPort(
                        remotePort = port.port,
                        process = port.processName,
                    )
                }
                // Issue #711: a successful reconcile clears the quiet-retry
                // budget so a later, unrelated transient drop gets its own full
                // retry allowance.
                transientRefreshRetries = 0
                setSessionRefreshInFlight(false)
                emitReady()
                // Epic #821 slice C (issue #837): persist the freshened tree so
                // the next cold start hydrates the just-reconciled order /
                // expand-collapse / foreign-guess. Fire-and-forget.
                persistTree(params)
                // Issue #867: mirror the freshened tree into the CLIENT cache so
                // the NEXT cold start paints THIS (settled) tree instantly — the
                // local-first half of stale-while-revalidate. Fire-and-forget.
                persistClientCache(params)
                if (refreshSessionsRequested) {
                    completeManualRefresh()
                } else {
                    clearRefreshFailure()
                }
            }
            is FolderListResult.Failed -> {
                if (handleTransientRefreshDrop(params, RuntimeException(result.message))) return
                setSessionRefreshInFlight(false)
                if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
                _state.value = FolderListUiState.Failed(REFRESH_FAILED_MESSAGE)
            }
            is FolderListResult.ConnectFailed -> {
                if (handleTransientRefreshDrop(params, result.cause)) return
                setSessionRefreshInFlight(false)
                if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
                // Issue #711: the user-facing connect message is the calm copy,
                // never the raw transport exception text (which embedded the
                // entire enumeration command). The raw [cause] is still carried
                // for Retry/diagnostics but never rendered.
                _state.value = FolderListUiState.ConnectError(
                    message = connectErrorCopy(result.cause),
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

    /**
     * Issue #711: a transient transport drop mid-refresh (EOF / broken transport
     * / channel closed / SSH not connected) is NOT a user-facing error — the
     * gateway's own evict-and-retry heal (#680) usually recovers it, and the
     * tree self-refreshes on the next reconcile. When such a transient error
     * still escapes the gateway (e.g. the fresh lease also blipped), we retry
     * QUIETLY here — up to [TRANSIENT_REFRESH_RETRY_LIMIT] times — instead of
     * flashing a scary band carrying the raw enumeration command. We keep the
     * last-known tree visible (no displacing error, no spinner-only screen) and
     * only fall through to a COMPACT calm message if the retries are exhausted.
     *
     * Returns true when the drop was classified transient and a quiet retry was
     * scheduled (the caller must `return` without surfacing any error state).
     */
    private fun handleTransientRefreshDrop(params: BoundParams, cause: Throwable): Boolean {
        if (!isTransientTransportDrop(cause)) return false
        if (transientRefreshRetries >= TRANSIENT_REFRESH_RETRY_LIMIT) {
            // Retries exhausted: this is no longer "transient" from the user's
            // point of view. Fall through to the calm-message path below.
            transientRefreshRetries = 0
            return false
        }
        transientRefreshRetries += 1
        // Keep the held tree visible and quietly re-run the reconcile on a fresh
        // lease. We do NOT clear isRefreshing here so the non-displacing progress
        // bar (#639) keeps spinning across the silent retry — no error flash.
        if (bound != params) {
            transientRefreshRetries = 0
            return true
        }
        launchReconcile(params)
        return true
    }

    /**
     * Issue #711: classify a refresh error as the TRANSIENT transport-EOF family
     * the dogfood report surfaced — a broken transport, encountered EOF, broken
     * pipe, a `Failed to open exec channel` wrapping that EOF, or a channel
     * closed under the exec. This is the drop that self-recovered on the very
     * next reconcile (the maintainer got the tree), so it must heal QUIETLY, not
     * flash a scary band carrying the raw enumeration command.
     *
     * Deliberately NARROWER than the gateway's [isStaleChannelSymptom]: the
     * "open failed" / "SSH session is not connected" stale-channel cases already
     * have an explicit, established Retry-panel UX (#465/#680) — they surface a
     * calm, retryable panel (now with calm copy, see [connectErrorCopy]) rather
     * than auto-looping. Only the EOF-drop family auto-retries quietly here.
     * Matched on message text (walking the cause chain) so it stays in lockstep
     * with the gateway classifier without an exception-type dependency.
     */
    private fun isTransientTransportDrop(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                (
                    message.contains("encountered EOF", ignoreCase = true) ||
                        message.contains("Broken transport", ignoreCase = true) ||
                        message.contains("broken pipe", ignoreCase = true) ||
                        message.contains("Failed to open exec channel", ignoreCase = true) ||
                        message.contains("channel closed", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #711: the user-facing copy for an unrecoverable folder-tree connect
     * error. NEVER the raw transport exception (which embedded the whole
     * `PATH=…; tmux list-sessions …` enumeration command) — a compact, calm
     * one-liner with a Retry affordance on the panel. The reconcile-timeout case
     * already carries its own short, calm message, so it is passed through.
     */
    private fun connectErrorCopy(cause: Throwable): String =
        if (cause is FolderReconcileTimeoutException) {
            cause.message ?: REFRESH_FAILED_MESSAGE
        } else {
            REFRESH_FAILED_MESSAGE
        }

    private fun FolderSessionRow.toSessionEntry(): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = sessionName,
            lastActivity = lastActivity,
            attached = attached,
            agentKind = agentKind,
            recordedProfile = recordedProfile,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
            windows = windows.map { window ->
                FolderSessionWindowEntry(
                    index = window.index,
                    name = window.name,
                    active = window.active,
                    command = window.command,
                    agentKind = window.agentKind,
                    windowId = window.windowId,
                )
            },
        )

    private fun preserveReadyOnRefresh(message: String): Boolean {
        if (_state.value !is FolderListUiState.Ready) return false
        refreshSessionsRequested = false
        _actionStatus.value = FolderActionStatus.Failed(message, isRefreshFailure = true)
        emitReady()
        return true
    }

    private fun completeManualRefresh() {
        if (!refreshSessionsRequested) return
        refreshSessionsRequested = false
        // Issue #656: a successful manual refresh emits no banner — the
        // refreshed list (and the non-displacing progress bar clearing) is the
        // feedback. Clear any stale refresh-failure message so a prior failure
        // does not linger after a subsequent success.
        clearRefreshFailure()
    }

    private fun clearRefreshFailure() {
        // Issue #711 / #656: auto-clear the calm refresh-failure band when a later
        // reconcile succeeds, recognising it by TYPE flag — NOT by matching the
        // user-facing message text. The prior prefix-match (`startsWith("Couldn't
        // refresh sessions:")`) silently stopped clearing the moment the copy
        // changed to [REFRESH_FAILED_MESSAGE], leaving a stale band on a healthy
        // tree. An action failure (kill / rename / create / import) is NOT a
        // refresh failure, so it is never auto-cleared here.
        val status = _actionStatus.value as? FolderActionStatus.Failed ?: return
        if (status.isRefreshFailure) {
            _actionStatus.value = FolderActionStatus.Idle
        }
    }

    private fun setSessionRefreshInFlight(refreshing: Boolean) {
        if (sessionRefreshInFlight == refreshing) return
        sessionRefreshInFlight = refreshing
        if (tree.hasSnapshot) emitReady()
    }

    /**
     * Rebuild [FolderListUiState.Ready] from the most recent gateway
     * snapshot + watched-folder overlay. Idempotent: every change in
     * either input re-runs the grouping.
     */
    /**
     * EPIC #679: project the maintained [HostTreeModel] into
     * [FolderListUiState.Ready]. The visuals stay byte-identical because the
     * projection feeds the SAME pure builders (`groupSessionsIntoFolders` /
     * `buildFolderTree`) and the same `resolveExpandedProjectPaths` auto-expand
     * the legacy rebuild used — but order, expansion, and node identity are now
     * intrinsic to the held tree (no per-emit re-derivation, no flash).
     */
    private fun emitReady() {
        if (bound == null) return
        if (!tree.hasSnapshot) return
        val projection = tree.project()
        _state.value = FolderListUiState.Ready(
            folders = projection.folders,
            treeRoots = projection.treeRoots,
            flatSessions = projection.flatSessions,
            expandedProjectPaths = projection.expandedProjectPaths,
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
            discoveryLoading = !tree.hasSnapshot,
        )
    }

    override fun onCleared() {
        probeJob?.cancel()
        // Issue #783: the event subscriptions are torn down here (and on a host
        // change), not on screen dispose. The periodic heartbeat is cancelled
        // here too (it normally stops on `stopPolling`, but a VM destroyed while
        // the tree screen is still composed must not leak the loop).
        tearDownBoundHostSubscriptions()
        periodicReconcileJob?.cancel()
        periodicReconcileJob = null
        hydrateTreeJob?.cancel()
        hydrateTreeJob = null
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
            warmSessionReady.value = false
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
                warmSessionReady.value = true
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

        // EPIC #679 (D22 hard-cut): the constant 5 s discovery poll
        // (`POLL_INTERVAL_MS` / `POLL_TICK_MS`) is deleted. The maintained
        // [HostTreeModel] is held across opens and reconciled INFREQUENTLY —
        // on a stale foreground/resume ([HostTreeModel.RECONCILE_STALENESS_MS])
        // and on the explicit pull-to-refresh swipe — never on a tight loop.
        const val WARM_RELEASE_DELAY_MS: Long = 10_000L

        /**
         * Epic #821 slice C (issue #837): bound on how long the cold-start
         * tree-hydrate / fire-and-forget persist waits for the warm SSH session
         * to be acquired before giving up. Sized above a normal connect so the
         * hydrate usually wins the race and seeds instantly; if it loses, it
         * simply skips (the probe re-seeds, the next mutation re-persists) rather
         * than blocking the screen.
         */
        const val WARM_SESSION_AWAIT_MS: Long = 8_000L

        /**
         * Issue #847: outer bound on the cold-start tree-HYDRATE best-effort seed
         * coroutine (warm-session await + `tree get` + parse). The freshening
         * reconcile is launched independently and is NOT gated by this — this only
         * caps how long the (cosmetic) order/collapse seed coroutine may live so a
         * wedged / old / mismatched host CLI can never keep it alive indefinitely.
         * Sized above [WARM_SESSION_AWAIT_MS] (the dominant term on a healthy cold
         * start) plus a normal sub-second `tree get`.
         */
        const val HYDRATE_TIMEOUT_MS: Long = 10_000L

        /**
         * Issue #702: outer bound on a single [runReconcile] gateway call. Sized
         * above the sum of the gateway's inner bounds — the live `-CC`
         * enumeration ([SshFolderListGateway.LIVE_ENUM_TIMEOUT_MS], 3.5s) plus,
         * when it falls through, the SSH-lease enumeration exec
         * ([SshFolderListGateway.EXEC_READ_TIMEOUT_MS], 3.5s) plus per-session
         * agent detection and watched-root expansion — so a slow-but-progressing
         * reconcile is never tripped. Kept comfortably BELOW the session-picker
         * readiness bound (#470, 20s) so a truly wedged reconcile surfaces the
         * retryable `ConnectError` panel with time to spare for the user (or the
         * picker-readiness Retry) to re-probe, rather than racing the picker's
         * own deadline. The remaining unbounded wait this defends against — the
         * SSH lease ACQUIRE/coalesce itself — is structurally bounded in #687;
         * until then this view-model bound guarantees the picker never pins in
         * an indefinite `Loading`.
         */
        const val RECONCILE_TIMEOUT_MS: Long = 12_000L

        /**
         * Issue #711: the COMPACT, calm, human one-liner shown when the folder
         * tree genuinely can't refresh (after the gateway heal + the bounded
         * quiet retries). It deliberately carries NO raw shell command and NO
         * raw transport-exception text — just a calm sentence and a Retry
         * affordance (the panel/banner already renders a Retry/Dismiss button).
         * Replaces the old `"Couldn't refresh sessions: <raw exception>"` band
         * that dumped the whole `PATH=…; tmux list-sessions …` enumeration.
         */
        const val REFRESH_FAILED_MESSAGE: String =
            "Couldn't refresh the project tree — tap to retry."

        /**
         * Issue #711: how many times a single refresh quietly retries a
         * transient transport drop (EOF / broken transport / channel closed)
         * before falling through to the calm [REFRESH_FAILED_MESSAGE]. Small —
         * the gateway already heals most transient drops with its own
         * evict-and-retry-once (#680); this is the view-model's defence so a
         * transient error that still escapes the gateway never flashes a band
         * for a drop that recovers on the very next reconcile.
         */
        const val TRANSIENT_REFRESH_RETRY_LIMIT: Int = 2

        /**
         * Issue #706: foreground-resume "freshen" window — much shorter than the
         * 15-min held-tree staleness gate ([HostTreeModel.RECONCILE_STALENESS_MS]).
         * When the user returns to PocketShell after even a brief background bounce,
         * an out-of-band session created while away (another terminal, an agent)
         * should appear without a 15-min wait. A resume re-probes when the held
         * tree is older than this window, so a real foreground return freshens the
         * picker promptly while a rapid in-place bounce (held tree younger than
         * this) still does NOT re-probe — preserving EPIC #679's "no constant poll"
         * intent. D21-clean: evaluated on the [ProcessLifecycleOwner] resume
         * signal, never a Timer/AlarmManager/WorkManager.
         */
        const val RESUME_FRESHEN_MS: Long = 10_000L

        /**
         * Issue #783: period of the bound-host foreground reconcile heartbeat
         * ([startPeriodicReconcile]). ~5 minutes per the maintainer's tree spec —
         * a slow safety net for out-of-band host changes that emit NO control
         * event on the open `-CC` channel (so neither `%sessions-changed` nor
         * `%window-close` fires) and that the foreground-resume freshen
         * ([RESUME_FRESHEN_MS]) / pull-to-refresh don't otherwise catch. Long
         * enough to honour EPIC #679's "reconcile infrequently, no constant poll"
         * intent. D21-clean: each tick's reconcile is foreground-gated, so the
         * loop parks while backgrounded (no background SSH work).
         */
        const val PERIODIC_RECONCILE_MS: Long = 5 * 60 * 1000L

        /**
         * Issue #706: debounce window for the live-`-CC`-client
         * `%sessions-changed` reconcile trigger. tmux can emit a burst of
         * `%sessions-changed` (e.g. a create immediately followed by a
         * window-add), so we coalesce the burst into a single reconcile rather
         * than firing one per event — keeping the trigger cheap and honouring
         * #679's "infrequent reconcile" intent. Small enough that an out-of-band
         * create still surfaces within a couple of seconds.
         */
        const val SESSIONS_CHANGED_DEBOUNCE_MS: Long = 400L

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
                    list.sortedWith(sessionEntrySort())
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
                .sortedWith(folderRowSort())
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
            // Issue #729 (#679 Slice 2): sticky bucket placement. Maps a
            // session name to the resolved root *match* path it was last placed
            // under. When the current probe momentarily degrades (an empty or
            // incomplete [resolvedWatchedRootPaths] so `bestRootForPath` no
            // longer matches), a session whose cwd is STILL within its sticky
            // root is held under that root instead of flashing into "Other
            // folders". `bestRootForPath` is therefore no longer the sole
            // per-projection authority for an already-placed node. Stickiness
            // never pins a session whose cwd genuinely left the root — that is
            // an authoritative move and re-buckets normally.
            stickyBuckets: Map<String, String> = emptyMap(),
        ): List<FolderTreeRoot> {
            val watchedRoots = watchedRootsOf(watchedFolders, resolvedWatchedRootPaths)

            val stickyByName = stickyBuckets
                .mapValues { (_, matchPath) -> canonicalisePath(matchPath) }

            val sessionProjectPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
            val otherSessionPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
            // #729: project paths placed by STICKINESS (not by the current probe)
            // map to the watched-root node id that must host them. The render
            // loop below filters session project paths by `root.matchPath`, which
            // a degraded probe no longer matches, so these explicit assignments
            // are what keep a sticky-held session under its node.
            val stickyProjectRootNode = mutableMapOf<String, String>()
            for (session in sessions) {
                val cwd = sessionFolderPaths[session.sessionName] ?: UNTRACKED_PATH
                // The current probe's authority first. Stickiness only engages
                // when the live probe fails to place the node — i.e. the
                // watched-roots resolution transiently degraded — and never
                // overrides a live, authoritative placement.
                val liveRoot = bestRootForPath(cwd, watchedRoots)
                val root = liveRoot
                    ?: stickyRootForSession(
                        cwd = cwd,
                        stickyMatchPath = stickyByName[session.sessionName],
                        watchedRoots = watchedRoots,
                    )
                val projectPath = root?.let { projectPathUnderRoot(cwd, it.matchPath) }
                val target = if (projectPath != null) sessionProjectPaths else otherSessionPaths
                val key = projectPath ?: cwd
                target.getOrPut(key) { mutableListOf() }.add(session)
                // Record the hosting node only when stickiness (not the live
                // probe) placed it, so the render loop can include it under the
                // correct watched root despite the degraded match-path. A
                // non-null [projectPath] guarantees a non-null hosting [root].
                if (projectPath != null && liveRoot == null) {
                    stickyProjectRootNode[projectPath] = root!!.path
                }
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
                val sessionPaths = sessionProjectPaths.keys.filter {
                    // #729: include a project path under this root when the live
                    // match-path contains it OR when stickiness explicitly
                    // assigned it to this watched-root node (degraded probe).
                    pathWithinRoot(it, root.matchPath) ||
                        stickyProjectRootNode[it] == root.path
                }
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

        /**
         * #729 sticky-bucket bookkeeping. Given the CURRENT (assumed healthy)
         * probe inputs, returns the resolved root *match* path for each session
         * the live probe places under a watched root. Sessions the probe does
         * not place (untracked cwd, or cwd outside every root) are absent.
         *
         * [HostTreeModel] folds the result into its sticky memory after each
         * reconcile: a present session refreshes/sets its sticky root, while a
         * session that AUTHORITATIVELY moved out of every root (placed by the
         * live probe but now matching nothing) is dropped. Held sticky entries
         * are the held-by-id placements [buildFolderTree] honours when a later
         * probe degrades. This reuses the exact `watchedRoots`/`bestRootForPath`
         * resolution `buildFolderTree` uses, so the sticky path is always the
         * same match-path the healthy projection bucketed under.
         */
        fun resolveStickyPlacements(
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
            resolvedWatchedRootPaths: Map<String, String>,
        ): Map<String, String> {
            val watchedRoots = watchedRootsOf(watchedFolders, resolvedWatchedRootPaths)
            if (watchedRoots.isEmpty()) return emptyMap()
            val placements = LinkedHashMap<String, String>()
            for ((sessionName, rawCwd) in sessionFolderPaths) {
                val cwd = canonicalisePath(rawCwd)
                val root = bestRootForPath(cwd, watchedRoots) ?: continue
                placements[sessionName] = root.matchPath
            }
            return placements
        }

        /**
         * The canonicalised watched-root overlay used by both [buildFolderTree]
         * and [resolveStickyPlacements] so the two agree on every match-path.
         */
        private fun watchedRootsOf(
            watchedFolders: List<ProjectRootEntity>,
            resolvedWatchedRootPaths: Map<String, String>,
        ): List<WatchedRoot> {
            val resolvedByWatchedPath = resolvedWatchedRootPaths
                .mapKeys { (path, _) -> canonicalisePath(path) }
                .mapValues { (_, path) -> canonicalisePath(path) }
            return watchedFolders
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
        }

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

        /**
         * #729 sticky placement. Called only when the current probe's
         * [bestRootForPath] returned null (the watched-roots resolution
         * degraded). Holds a session under its previously-assigned root iff:
         *
         *  1. the session has a sticky match-path ([stickyMatchPath]),
         *  2. its current cwd is STILL within that sticky match-path (so the
         *     session has not authoritatively moved out of the root), and
         *  3. a watched root still owns that match-path — identified by node id
         *     via the canonical root *path*, not by re-running `bestRootForPath`
         *     against the degraded `matchPath`.
         *
         * Returns a [WatchedRoot] whose `matchPath` is overridden to the held
         * sticky path so [projectPathUnderRoot] computes the same project key as
         * the healthy probe did, and whose `path` (node id) is the stable
         * watched-root the healthy probe placed the session under so the project
         * still renders beneath that node. Returns null when the cwd genuinely
         * left the root (an authoritative move) or no watched root owns the
         * sticky path.
         */
        private fun stickyRootForSession(
            cwd: String,
            stickyMatchPath: String?,
            watchedRoots: List<WatchedRoot>,
        ): WatchedRoot? {
            if (cwd == UNTRACKED_PATH) return null
            if (watchedRoots.isEmpty()) return null
            val sticky = stickyMatchPath ?: return null
            // Authoritative-move guard: the cwd must still sit under the sticky
            // root, otherwise the session genuinely left it and re-buckets.
            if (!pathWithinRoot(cwd, sticky)) return null
            // Resolve the sticky match-path back to its stable watched-root node
            // (held by id). The watched root's *current* match-path may be the
            // degraded raw path, so identify the owner by node relationship, not
            // by re-running `bestRootForPath` against the degraded roots:
            //  1. an exact id/match-path equality (healthy or already-resolved),
            //  2. else the longest watched root whose raw/match path is a prefix
            //     of the sticky path or vice versa (alias/symlink resolution),
            //  3. else, when there is a single watched root, that root.
            val owner = watchedRoots.firstOrNull { root ->
                root.matchPath == sticky || root.path == sticky
            } ?: watchedRoots
                .filter { root ->
                    pathWithinRoot(sticky, root.path) ||
                        pathWithinRoot(sticky, root.matchPath) ||
                        pathWithinRoot(root.path, sticky) ||
                        pathWithinRoot(root.matchPath, sticky)
                }
                .maxByOrNull { maxOf(it.path.length, it.matchPath.length) }
                ?: watchedRoots.singleOrNull()
                ?: return null
            // Override the match-path to the held sticky path so the project key
            // is identical to the healthy projection's.
            return owner.copy(matchPath = sticky)
        }

        internal fun pathWithinRoot(path: String, root: String): Boolean =
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

        /**
         * Within-folder session order: agents first, then most-recent activity,
         * then name. Session order is intrinsic to the maintained session list
         * [HostTreeModel] feeds in already-stable slots (#679/#733), so this is
         * the single ordering rule — no frozen display-rank is threaded.
         */
        private val recencySessionSort: Comparator<FolderSessionEntry> =
            compareByDescending<FolderSessionEntry> { it.agentKind.isAgentSession() }
                .thenByDescending { it.lastActivity ?: 0L }
                .thenBy { it.sessionName }

        private fun sessionEntrySort(): Comparator<FolderSessionEntry> = recencySessionSort

        /**
         * Order of active folder rows within a group: most-recent activity first,
         * ties broken on label so two folders never swap arbitrarily.
         */
        private fun folderRowSort(): Comparator<FolderRow> =
            compareByDescending<FolderRow> { it.mostRecentActivity }
                .thenBy { it.label.lowercase() }

        private fun List<FolderRow>.sortedForTree(): List<FolderRow> {
            val active = filter { it.sessions.isNotEmpty() && it.path != UNTRACKED_PATH }
                .sortedWith(folderRowSort())
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
            // Unknown (foreign, not-yet-classified) groups with shells (#821).
            SessionAgentKind.Shell,
            SessionAgentKind.Unknown,
            -> false
        }
    }
}
