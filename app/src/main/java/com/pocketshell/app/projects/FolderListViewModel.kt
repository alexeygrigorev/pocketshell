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
import com.pocketshell.app.bootstrap.expectedHostCliVersion
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.portfwd.ForwardingHostSnapshot
import com.pocketshell.app.portfwd.InterestingPortFilter
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.requireMainThread
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

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
    // Issue #2320: host engine-registry read-through for the session picker.
    // Null in direct unit-test paths that do not exercise engine discovery.
    private val enginesGateway: EnginesGateway? = null,
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
    // Issue #2381: it is the RELEASE CORE of `versionName`, not the raw
    // string. Since #2356 `versionName` is git-derived and can carry a
    // describe qualifier and/or build metadata (`0.4.45-4-g9b1d784e`,
    // `0.0.0-dev+525c87a`) — shapes no host CLI reports and no index can
    // resolve for the `pocketshell==<version>` upgrade pin. See
    // [expectedHostCliVersion].
    private val expectedPocketshellVersionProvider: () -> String = expectedPocketshellVersionProvider@{
        val context = applicationContext ?: return@expectedPocketshellVersionProvider ""
        try {
            expectedHostCliVersion(
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName,
            )
        } catch (_: Throwable) {
            ""
        }
    },
    // Issue #1509: notifications step of the single session-tree setup — see
    // [defaultNotificationPermissionNeeded]. Injectable test seam.
    private val notificationPermissionNeeded: () -> Boolean = {
        defaultNotificationPermissionNeeded(applicationContext)
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
        treeSync.setPeriodicEnabledForTest(enabled)
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
        // Issue #2320: the host engine-registry read-through gateway.
        enginesGateway: EnginesGateway,
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
        enginesGateway = enginesGateway,
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
     * `pocketshell tree` payload's `cli_version` — NOT a slow blocking `--version`
     * exec. Non-null only when the host `pocketshell` is OLDER than this app build
     * expects; the FolderList surfaces it as a dismissible update prompt. Stays
     * `null` on match, on a versionless payload (old CLI), or when the host is
     * NEWER (the app-behind case — see [PayloadVersionCheck.Verdict.AppOutdated]).
     */
    private val cliVersionBanner = CliVersionBannerCoordinator(
        expectedVersion = { expectedPocketshellVersion() },
        hostId = { bound?.hostId },
        dismissStore = CliVersionBannerDismissStore.from(applicationContext),
    )
    val cliVersionMismatch: StateFlow<PayloadVersionCheck.Verdict.HostOutdated?> =
        cliVersionBanner.mismatch

    @androidx.annotation.VisibleForTesting
    internal fun setCliVersionBannerDismissStoreForTest(store: CliVersionBannerDismissStore) =
        cliVersionBanner.setDismissStoreForTest(store)

    /** Dismiss the passive CLI-version update prompt (issue #885 / #2033). */
    fun dismissCliVersionMismatch() = cliVersionBanner.dismiss()

    /** Issue #1509: THE single session-tree setup coordinator (relocation + dedup, D22). */
    private val sessionTreeSetup = SessionTreeSetupCoordinator(notificationPermissionNeeded)

    val notificationPermissionRequest: StateFlow<Boolean> =
        sessionTreeSetup.notificationPermissionRequest

    fun onNotificationPermissionRequestConsumed() =
        sessionTreeSetup.onNotificationPermissionRequestConsumed()

    /**
     * Issue #947: progress of the banner's one-tap **Update** action (host-side
     * `pocketshell` upgrade over the warm SSH session). [Idle] shows the
     * Update/Dismiss buttons; [Running] a spinner; [Failure] the installer error
     * with Retry/Dismiss — never a stuck spinner (the exec is bounded, #944/#939).
     * SUCCESS has no state: the upgrade re-checks the version and, on a match,
     * clears [cliVersionMismatch] so the whole banner disappears.
     */
    public sealed interface CliVersionUpdateState {
        public data object Idle : CliVersionUpdateState
        public data object Running : CliVersionUpdateState
        public data class Failure(
            val message: String,
            val kind: Kind = Kind.Failed,
            val offerRetry: Boolean = true,
        ) : CliVersionUpdateState {
            public enum class Kind { Unpublished, Capped, Failed }
        }
    }

    val cliVersionUpdateState: StateFlow<CliVersionUpdateState> =
        cliVersionBanner.updateState

    /**
     * Issue #947: the host-upgrade seam. Runs `pocketshell`'s installer upgrade
     * (uv / pipx / pip, auto-detected) over the warm session, bounded. Injectable
     * for tests; the production default is a real [HostPocketshellUpgrade].
     */
    private var hostPocketshellUpgrade: HostPocketshellUpgrade = HostPocketshellUpgrade()

    @androidx.annotation.VisibleForTesting
    internal fun setHostPocketshellUpgradeForTest(upgrade: HostPocketshellUpgrade) {
        hostPocketshellUpgrade = upgrade
    }

    private var hostUpgradeJob: Job? = null

    /**
     * Issue #947: run the host-side `pocketshell` upgrade for the
     * [cliVersionMismatch] banner's one-tap **Update** button. Over the EXISTING
     * warm SSH session (D21) it flips to [CliVersionUpdateState.Running], execs
     * the bounded installer upgrade (#944/#939), then on success RE-CHECKS the
     * version via a fresh `tree get` through [observePayloadCliVersion] (a match
     * clears the banner); on failure / still-outdated it surfaces a
     * [CliVersionUpdateState.Failure] — never a stuck spinner. Idempotent against
     * a double tap (a second call while one is in flight is ignored).
     */
    fun runHostPocketshellUpgrade() {
        if (cliVersionBanner.updateState.value is CliVersionUpdateState.Running) return
        val params = bound
        if (params == null) {
            // Issue #1157: with NO bound host there is genuinely no "the host" to
            // upgrade over — the mismatch banner is only ever raised for a bound
            // host (see [observePayloadCliVersion]), so this is a defensive edge.
            // Do NOT claim a false connection failure ("Not connected — reconnect"),
            // which contradicts a connected tray/tree; say what is actually true.
            cliVersionBanner.setUpdateState(
                CliVersionUpdateState.Failure("No host is selected — reopen the host and try again."),
            )
            return
        }
        hostUpgradeJob?.cancel()
        cliVersionBanner.setUpdateState(CliVersionUpdateState.Running)
        hostUpgradeJob = viewModelScope.launch {
            // Issue #1157: robustly (re)acquire a live session on demand rather than
            // dead-ending on a possibly-absent/expired warm lease. [acquireUpgradeSession]
            // reuses a live warm session as-is and, when absent, re-acquires from the
            // SHARED pool — REUSING the live pooled transport (refcount, no new connect
            // — D21) and dialing fresh only when there is genuinely no live transport,
            // so it avoids the old `awaitWarmSession() == null` FALSE "Not connected"
            // that contradicted a connected tree + tray. Null ONLY when unreachable.
            val session = treeSync.acquireSessionForUpgrade(params)
            if (session == null || bound != params) {
                cliVersionBanner.setUpdateState(
                    CliVersionUpdateState.Failure(
                        "Couldn't reach the host to run the update — reconnect and try again.",
                    ),
                )
                return@launch
            }
            when (val result = hostPocketshellUpgrade.run(session, expectedPocketshellVersion())) {
                is HostPocketshellUpgrade.Result.Success -> {
                    // Re-check the host version from a fresh payload. A SUCCESSFUL
                    // upgrade should now report the matching version, clearing the
                    // banner; a no-op upgrade (already-newest / unpublished) is
                    // classified from requested vs resolved (#2033), not guessed
                    // as a cap.
                    recheckHostVersionAfterUpgrade(params, result.output)
                }
                is HostPocketshellUpgrade.Result.Failure -> {
                    cliVersionBanner.classifyAndApply(
                        requestedVersion = expectedPocketshellVersion(),
                        resolvedVersion = cliVersionBanner.mismatch.value?.hostVersion,
                        exitCode = result.exitCode ?: 1,
                        output = result.rawOutput.ifBlank { result.message },
                    )
                }
            }
        }
    }

    /**
     * Issue #947: after a successful upgrade exec, re-read the host CLI version
     * from a fresh `tree get` and re-evaluate. A now-matching (or newer) version
     * clears [cliVersionMismatch] and drops back to [CliVersionUpdateState.Idle];
     * a still-outdated read (a silent no-op upgrade) surfaces a failure so the
     * spinner never sticks and the user can retry/dismiss.
     */
    private suspend fun recheckHostVersionAfterUpgrade(
        params: BoundParams,
        installerOutput: String,
    ) {
        if (treeRemoteSource == null) {
            // No durable source (some unit paths): trust the exit-0 success and
            // clear the banner so the spinner doesn't stick.
            cliVersionBanner.clearAfterTrustedSuccess()
            return
        }
        // `getTree` is itself bounded (TreeRemoteSource.execTreeRpcBounded), but
        // wrap the re-check in an OUTER bound too so a future unbounded `getTree`
        // can never leave the banner's spinner stuck (#947 / #944 / #939).
        val treeResult = withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
            runCatching { treeSync.getTreeForUpgrade(params) }
                .getOrDefault(TreeRemoteSource.TreeResult.Empty)
        } ?: TreeRemoteSource.TreeResult.Empty
        if (treeResult.cliVersion.isNullOrBlank()) {
            // The re-read carried NO version (timed out / old-CLI omits it /
            // empty payload). The upgrade exec itself exited 0, so trust that
            // success and clear the banner rather than raising a false
            // "still outdated" — and never leave the spinner stuck.
            cliVersionBanner.clearAfterTrustedSuccess()
            return
        }
        cliVersionBanner.applyRecheck(treeResult.cliVersion, installerOutput)
    }

    /** Issue #885: raise the passive update prompt from a payload-carried version. */
    internal fun observePayloadCliVersion(hostCliVersion: String?) =
        cliVersionBanner.observePayloadCliVersion(hostCliVersion)

    /**
     * The `pocketshell` version this app build expects on the host, via
     * [expectedPocketshellVersionProvider] (installed app `versionName`; injectable
     * in tests). Blank when the read fails — [PayloadVersionCheck] treats it as
     * "no signal".
     */
    private fun expectedPocketshellVersion(): String = expectedPocketshellVersionProvider()

    private val assistant: SessionAssistantController =
        SessionAssistantController(scope = viewModelScope, sessionFactory = ::buildAssistantDeps)
    internal val assistantState: StateFlow<AssistantUiState> = assistant.state

    private val _assistantNavRequests: MutableSharedFlow<AppDestination> = MutableSharedFlow(extraBufferCapacity = 1)
    val assistantNavRequests: SharedFlow<AppDestination> = _assistantNavRequests.asSharedFlow()

    private var assistantSshExecutor: AssistantSshExecutor = RealAssistantSshExecutor()

    private var bound: BoundParams? = null

    /**
     * Issue #718/#1875: host profile discovery and latest-request state live in
     * one focused owner rather than adding another responsibility to this
     * already-large tree coordinator.
     */
    private val profileDiscovery = FolderListProfileDiscovery(
        profilesGateway = profilesGateway,
        hostDao = hostDao,
        scope = viewModelScope,
        ioDispatcher = { ioDispatcher },
        isCurrentHost = { hostId -> bound?.hostId == hostId },
    )

    val claudeProfiles: StateFlow<List<ClaudeProfile>> = profileDiscovery.claudeProfiles
    val codexProfiles: StateFlow<List<CodexProfile>> = profileDiscovery.codexProfiles

    private val engineDiscovery: FolderListEngineDiscovery = FolderListEngineDiscovery(
        enginesGateway = enginesGateway,
        hostDao = hostDao,
        scope = viewModelScope,
        ioDispatcher = { ioDispatcher },
        isCurrentHost = { hostId -> bound?.hostId == hostId },
        onRefreshApplied = { hostId ->
            bound?.takeIf { it.hostId == hostId }?.let {
                if (rootSnapshotLoaded) treeSync.requestReconcile()
            }
        },
    )

    val engines: StateFlow<List<RemoteEngine>> = engineDiscovery.engines

    @androidx.annotation.VisibleForTesting
    internal var warmLeaseAcquiredForTest: (() -> Unit)? = null
    @androidx.annotation.VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Issue #965 (ANR off-Main): the worker dispatcher the EXPENSIVE tree
     * derivation runs on so it never blocks Main. [emitReady] takes a cheap
     * immutable snapshot of the held tree on the caller's thread and runs the
     * O(roots × projects) `buildFolderTree`/`groupSessionsIntoFolders`
     * projection here (the 71-project ANR cost), assigning the result back on
     * Main. Single-threaded so concurrent projections cannot interleave;
     * test-overridable (unit tests set it to the virtual-time test dispatcher so
     * the emit stays deterministic).
     */
    @androidx.annotation.VisibleForTesting
    internal var treeDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    /**
     * Issue #965: the most-recent [emitReady] projection coroutine + a monotone
     * generation token. Because the heavy projection now runs off-Main and
     * resumes asynchronously, an older projection that finishes after a newer
     * one must NOT overwrite the fresher Ready state — the resume checks its
     * captured generation against [emitGeneration] and drops itself if stale.
     */
    private var emitJob: Job? = null
    private var emitGeneration: Long = 0L

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

    /**
     * Issue #783: the host id the event subscriptions ([sessionsChangedJob] /
     * [windowCloseJob] / [periodicReconcileJob]) are currently bound to. Lets
     * [bind] keep the live subscriptions running across a same-host re-bind (so
     * `stopPolling` → return does not restart them and miss an event in the gap),
     * while a host CHANGE tears them down and re-subscribes for the new host.
     * `null` while unbound.
     */

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
    private var lastDiscoveredPorts: List<HostDiscoveredPort> = emptyList()
    private var forwardingSnapshots: Map<Long, ForwardingHostSnapshot> = emptyMap()
    private var sessionRefreshInFlight: Boolean = false
    private var createSessionInFlight: Boolean = false
    private var refreshSessionsRequested: Boolean = false
    /**
     * A lifecycle signal without a complete generation is only a hint.  Keep
     * it until an authoritative session probe succeeds; never let the hint
     * authorize a name-based removal.
     */


    /**
     * Issue #711: count of consecutive QUIET retries the current refresh has
     * already spent healing a transient transport drop (EOF / broken transport /
     * channel closed). Bounded by [TRANSIENT_REFRESH_RETRY_LIMIT] so a genuinely
     * unrecoverable host eventually surfaces the calm message instead of looping
     * forever. Reset to 0 on any successful reconcile and on a fresh [bind].
     */

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
    private var lifecycleObserver: LifecycleEventObserver? = null

    private val treeSyncRemote = FolderListTreeSyncRemote(
        gateway = gateway,
        hostDao = hostDao,
        treeRemoteSource = treeRemoteSource,
        sshLeaseManager = sshLeaseManager,
        activeTmuxClients = activeTmuxClients,
        scope = viewModelScope,
        dispatcher = { ioDispatcher },
        warmSessionAwaitMs = { TreeSyncCoordinator.DEFAULT_WARM_SESSION_AWAIT_MS },
        onWarmSessionAcquired = { warmLeaseAcquiredForTest?.invoke() },
    )

    private val treeSyncListener = object : TreeSyncCoordinator.Listener {
        override fun onLoadingRequested() {
            if (_state.value !is FolderListUiState.Ready) {
                _state.value = folderListLoadingState(bound?.hostId, forwardingSnapshots)
            }
        }

        override fun onRefreshingChanged(refreshing: Boolean) {
            setSessionRefreshInFlight(refreshing)
        }

        override fun onTreeChanged(synchronous: Boolean) {
            emitReady(synchronous = synchronous)
        }

        override fun onReconcileSuccess(result: FolderListResult.Sessions) {
            lastDiscoveredPorts = InterestingPortFilter.filter(result.discoveredPorts).map { port ->
                HostDiscoveredPort(remotePort = port.port, process = port.processName)
            }
            setSessionRefreshInFlight(false)
            if (refreshSessionsRequested) completeManualRefresh() else clearRefreshFailure()
        }

        override fun onReconcileFailure(failure: TreeSyncFailure) {
            setSessionRefreshInFlight(false)
            when (failure) {
                TreeSyncFailure.HostNotFound -> {
                    _state.value = FolderListUiState.Failed("Host not found.")
                }
                is TreeSyncFailure.Failed -> {
                    if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
                    _state.value = FolderListUiState.Failed(REFRESH_FAILED_MESSAGE)
                }
                TreeSyncFailure.Timeout -> {
                    val cause = FolderReconcileTimeoutException(reconcileTimeoutMs)
                    if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
                    _state.value = FolderListUiState.ConnectError(
                        message = folderListConnectErrorMessage(cause, REFRESH_FAILED_MESSAGE),
                        cause = cause,
                    )
                }
                is TreeSyncFailure.ConnectFailed -> {
                    if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
                    _state.value = FolderListUiState.ConnectError(
                        message = folderListConnectErrorMessage(failure.cause, REFRESH_FAILED_MESSAGE),
                        cause = failure.cause,
                    )
                }
                is TreeSyncFailure.ToolUnavailable -> {
                    if (preserveReadyOnRefresh("Couldn't refresh sessions: tmux is not installed.")) return
                    _state.value = FolderListUiState.ToolUnavailable
                }
                is TreeSyncFailure.Unexpected -> onUnexpectedFailure(failure.cause)
            }
        }

        override fun onUnexpectedFailure(cause: Throwable) {
            if (bound == null) return
            if (preserveReadyOnRefresh(REFRESH_FAILED_MESSAGE)) return
            _state.value = FolderListUiState.ConnectError(
                message = folderListConnectErrorMessage(cause, REFRESH_FAILED_MESSAGE),
                cause = cause,
            )
        }

        override fun onPayloadCliVersion(version: String) {
            sessionTreeSetup.maybeRunVersionCheck(version, ::observePayloadCliVersion)
        }
    }

    private val treeSync: TreeSyncCoordinator = TreeSyncCoordinator(
        scope = viewModelScope,
        remote = treeSyncRemote,
        cache = treeClientCache,
        processStarted = processStarted,
        dispatcher = { ioDispatcher },
        policy = TreeSyncPolicy(periodicEnabled = periodicReconcileEnabled),
        awaitBeforeFullReconcile = { params: BoundParams ->
            engineDiscovery.awaitBindRefresh(params.hostId)
        },
        listener = treeSyncListener,
    )

    private val tree: HostTreeModel get() = treeSync.tree
    private val rootSnapshotLoaded: Boolean get() = treeSync.rootSnapshotLoaded
    private val lastWatchedFolders: List<ProjectRootEntity> get() = treeSync.watchedFolders

    private val sessionLifecycleActions = FolderListSessionLifecycleActions(
        boundHostId = { bound?.hostId },
        tree = tree,
        emitReady = ::emitReady,
        isPolling = { treeSync.isPolling },
        refresh = ::refresh,
        requestIdentityReconcile = treeSync::requestIdentityReconcile,
    )

    @androidx.annotation.VisibleForTesting
    internal var reconcileTimeoutMs: Long
        get() = treeSync.policy.reconcileTimeoutMs
        set(value) { treeSync.policy.reconcileTimeoutMs = value }

    init {
        viewModelScope.launch {
            forwardingController.flowOfHostSnapshots().collectLatest { snapshots ->
                forwardingSnapshots = snapshots
                emitReady()
            }
        }
        sessionLifecycleSignals?.let { signals ->
            bindFolderListSessionLifecycleSignals(
                scope = viewModelScope,
                signals = signals,
                onKilled = ::onSessionKilled,
                onWindowClosed = sessionLifecycleActions::onWindowClosed,
                onStale = sessionLifecycleActions::onStale,
                onIdentityUncertain = sessionLifecycleActions::onIdentityUncertain,
            )
        }
        // Issue #1155 (Part A): the client-cache PARSED snapshots are warmed into
        // memory ONCE at process startup by [com.pocketshell.app.App.onCreate]
        // (`TreeClientCache.warmAll`, off Main, MANY frames ahead of any
        // navigation) — the SINGLE warm path. The old per-VM warm launched here at
        // construction lost the race with `bind` on the maintainer's
        // deep-link-into-a-session-then-back path (VM built + bound in the same
        // instant), which is exactly the recurring #867/#1109 Loading flash. It was
        // ALSO the flaky-test culprit: launched on the default `Dispatchers.IO`
        // (captured before a test can rebind `ioDispatcher`), it raced the
        // synchronous `bind` peek on a real background thread. Removed here (D22
        // hard-cut — one warm path): the startup warm makes `peek` hit for every
        // persisted host, and a genuine cold MISS still falls back to the brief
        // Loading + the OFF-Main read in [bind] (never a Main-thread read).
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
        if (tree.removeSession(killed.generation)) {
            emitReady()
        }
        if (treeSync.isPolling) refresh()
    }

    /** Name-only signals request a probe. */
    @androidx.annotation.VisibleForTesting
    internal fun onSessionIdentityUncertain(
        uncertain: com.pocketshell.app.tmux.SessionIdentityUncertain,
    ) = sessionLifecycleActions.onIdentityUncertain(uncertain)

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
    internal fun onWindowClosed(closed: com.pocketshell.app.tmux.ClosedWindow) =
        sessionLifecycleActions.onWindowClosed(closed)

    /**
     * Issue #1155: handle a persisted-but-GENUINELY-GONE session broadcast from
     * the per-session screen ([SessionLifecycleSignals.staleSessions]). The attach
     * confirmed the tmux session is absent (`TmuxSessionNotFoundException`) — NOT a
     * transient reconnect (those never emit this) — so drop the dead row from the
     * held tree (it is confirmed gone), matching [onSessionKilled], keeping the
     * list accurate. Ignores other hosts.
     *
     * The user-facing "This session no longer exists — create in this folder, or
     * go home?" recovery PROMPT is no longer raised here: it is owned app-level by
     * `MainActivity` ([com.pocketshell.app.tmux.StaleSessionPromptController]) so
     * it also surfaces on the cold-restore path where this view model never
     * exists (the folder tree was never opened). This collector only keeps the
     * tree it IS bound to accurate.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onStaleSession(stale: com.pocketshell.app.tmux.StaleSession) =
        sessionLifecycleActions.onStale(stale)

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
        treeSync.forceTreeStaleForTest()
    }

    private fun updateProcessStarted(started: Boolean) {
        processStarted.value = started
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
        requireMainThread("FolderListViewModel.bind")
        val params = BoundParams(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )
        // Issue #1509: the single setup pass — folds in the notifications request
        // (the MainActivity.onCreate app-open trigger is deleted, D22).
        sessionTreeSetup.runSetup()
        // Issue #783: keep the live `-CC` event subscriptions
        // (`%sessions-changed` + `%window-close`) and the periodic reconcile tied
        // to the BOUND-HOST lifetime, not screen composition. On a same-host
        // re-bind they keep running (no restart gap that could miss an event);
        // only a host CHANGE tears them down and re-subscribes. This is the
        // #783 core fix — a `%window-close` that lands while the user is on the
        // session screen still prunes the tree, because `stopPolling` no longer
        // cancels these jobs. They reuse the warm `-CC` client (D21, no second
        // connection) and are foreground-gated.
        // Issue #783: (re)start the periodic ~5-min reconcile heartbeat for the
        // SHOWN tree. Unlike the event subscriptions it follows the screen
        // lifecycle (cancelled in `stopPolling`), so it is restarted on every
        // tree open. It reuses the screen's own warm lease (no second
        // connection) and is foreground-gated.
        // EPIC #679 requirement #1: opening the host detail renders the HELD
        // tree INSTANTLY. Re-binding the SAME host reuses the maintained tree
        // (no probe-on-open, no loading flash) and only kicks a reconcile if one
        // is genuinely due (staleness gate). A host CHANGE resets the tree.
        val hostChanged = bound != params
        bound = params
        // Issue #1509: host CHANGE re-arms the one-shot version-mismatch check
        // (a same-host re-entry, handled above, keeps a dismissed banner gone).
        if (hostChanged) sessionTreeSetup.onHostChanged()
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
        // Issue #1109 (regression of the #867 instant-render promise) + #965 (ANR
        // off-Main): hydrate the per-host CLIENT cache + emit Ready SYNCHRONOUSLY on
        // the calling (Main) thread so the FIRST painted frame on a cold connect is
        // already the cached tree — NO visible Loading rebuild flash. #965 had moved
        // the cache read OFF Main, which (with the StateFlow starting at `Loading()`)
        // meant the screen always composed the empty Loading frame first and only
        // flipped to Ready a couple of async hops later — the exact flash the
        // maintainer re-reported. The fix DECOUPLES the parse from the hydrate: the
        // expensive file read + JSON parse run OFF Main (warmed into [TreeClientCache]'s
        // in-memory snapshot by the init pre-warm + each reconcile's `write`), so the
        // synchronous seed below reads the ALREADY-PARSED snapshot via [peek] with NO
        // Main-thread `disk_read` (the #965 ANR cause stays gone — see
        // [FolderListScaleAnrStrictModeDockerTest]). On a genuine cold MISS (the
        // snapshot was not warmed in time) the seed returns false and we fall back to
        // the brief Loading + an OFF-Main read, never a Main-thread file read. The
        // cache stays ADVISORY: the silent reconcile overwrites the seeded
        // placeholders in place.
        // Start the registry read before binding the coordinator. Its initial
        // reconcile may start synchronously on an unconfined/test dispatcher,
        // so the bind-read job must already exist when the ordering barrier is
        // reached.
        engineDiscovery.bind(params)
        treeSync.bind(params, projectRootDao.getByHostId(hostId))
        // The maintained in-memory tree is held across opens of the SAME host
        // (so a re-open renders instantly), the daemon registry (#837) makes the
        // presentation state durable host-side, and this client cache makes the
        // FIRST cold render instant. `project_roots` (Room, D22-protected) still
        // supplies the watched-root overlay.

        // Issue #718: fetch the host-DISCOVERED agent profiles over the warm
        // SSH lease (was the #627/#631 client-stored JSON, hard-cut per D22).
        // The default-only / CLI-missing / fetch-failure cases all collapse to
        // an empty list, so the picker simply shows no profile selector.
        profileDiscovery.refresh(params)

    }

    /**
     * Issue #1875: retry host profile discovery at the moment the user opens a
     * new-session picker. A transient bind-time failure must not permanently
     * hide non-default profiles for the lifetime of the host screen.
     */
    fun refreshProfilesForPicker() {
        bound?.let(profileDiscovery::refresh)
    }

    fun refreshEnginesForPicker() {
        bound?.let(engineDiscovery::refresh)
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
        treeSync.requestReconcile()
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
     * On full success [onResolved] fires with the resolved tmux session name
     * so the caller can route to `AppDestination.TmuxSession`. On failure —
     * and on issue #1928's PARTIAL success, where the session exists but the
     * agent did not start — the screen keeps the list visible and reports the
     * reason through [actionStatus], not a silent "nothing happened".
     */
    fun createSession(
        sessionName: String,
        cwd: String,
        startCommand: String?,
        chosenKind: SessionAgentKind? = null,
        onResolved: (sessionName: String) -> Unit,
        onFinished: () -> Unit = {},
    ) {
        val params = bound ?: run {
            _actionStatus.value = FolderActionStatus.Failed(
                "Session list isn't ready yet. Try again.",
            )
            onFinished()
            return
        }
        if (createSessionInFlight) return
        setCreateSessionInFlight(true)
        viewModelScope.launch {
            try {
                // #1036: non-displacing in-progress feedback so closing the
                // picker is followed by an explicit "still working" signal
                // rather than an apparently frozen/no-op host screen.
                _actionStatus.value = FolderActionStatus.Running("Creating session…")
                val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
                    _actionStatus.value = FolderActionStatus.Failed("Host not found.")
                    return@launch
                }
                val result = gateway.createSession(
                    host = host,
                    keyPath = params.keyPath,
                    passphrase = params.passphrase,
                    sessionName = sessionName,
                    cwd = cwd,
                    startCommand = startCommand,
                    namePolicy = SessionNamePolicy.UniqueOnHost,
                )
                result.fold(
                    onSuccess = { outcome ->
                        val resolvedName = outcome.sessionName
                        // Issue #1928: a LaunchFailed session is a plain shell —
                        // stamping the CHOSEN agent kind on it would put a Claude
                        // badge on a session with no Claude in it. Fall back to
                        // Probing so the reconcile settles the truth.
                        val launched = outcome is SessionCreateOutcome.Created
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
                                agentKind = chosenKind?.takeIf { launched }
                                    ?: SessionAgentKind.Probing,
                            ),
                            folderPath = cwd,
                        )
                        emitReady()
                        // Issue #1928: the created session is inserted above in
                        // BOTH states — it exists and stays. Only a full success
                        // routes the user into it; a launch failure keeps them
                        // here with the new row visible plus the reason, instead
                        // of dropping them into a shell they asked to be an agent.
                        outcome.fold(
                            onCreated = {
                                _actionStatus.value = FolderActionStatus.Idle
                                onResolved(resolvedName)
                            },
                            onLaunchFailed = { name, detail ->
                                _actionStatus.value = FolderActionStatus.Failed(
                                    sessionLaunchFailedMessage(name, detail),
                                )
                            },
                        )
                        refresh()
                    },
                    onFailure = { error ->
                        // #1036: surface the failure through the non-displacing
                        // [actionStatus] overlay (matching killSession/renameSession)
                        // so the current session list stays visible instead of being
                        // replaced by a displacing Failed state.
                        _actionStatus.value = FolderActionStatus.Failed(
                            "Couldn't create session: ${error.message ?: error.javaClass.simpleName}",
                        )
                    },
                )
            } finally {
                setCreateSessionInFlight(false)
                onFinished()
            }
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
        // Authorize the destructive action against the exact row the user
        // tapped, before the gateway suspension can observe a same-name
        // recreation. Never resolve this generation again from [target] after
        // the async kill: that lookup could authorize the successor instead.
        val requestedGeneration = tree.generationForSession(target)
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
                    val generation = requestedGeneration
                    if (generation != null) {
                        onSessionKilled(
                            com.pocketshell.app.tmux.KilledSession(
                                hostId = params.hostId,
                                generation = generation,
                                lastKnownName = target,
                            ),
                        )
                    } else {
                        onSessionIdentityUncertain(
                            com.pocketshell.app.tmux.SessionIdentityUncertain(
                                hostId = params.hostId,
                                lastKnownName = target,
                                folderPath = null,
                                action = com.pocketshell.app.tmux.SessionLifecycleAction.Kill,
                            ),
                        )
                    }
                    sessionLifecycleSignals?.emitKilled(
                        hostId = params.hostId,
                        generation = generation,
                        lastKnownName = target,
                    )
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
        launchFolderListRename(
            scope = viewModelScope,
            gateway = gateway,
            hostDao = hostDao,
            ioDispatcher = ioDispatcher,
            tree = tree,
            params = params,
            oldTarget = oldTarget,
            newTarget = newTarget,
            refresh = ::refresh,
            emitReady = ::emitReady,
            onMissingGeneration = {
                treeSync.requestIdentityReconcile()
            },
            onFailure = { message ->
                _actionStatus.value = FolderActionStatus.Failed(message)
            },
        )
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
        treeSync.toggleProjectExpanded(projectPath)
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
     * Stop screen-scoped synchronization while retaining the maintained tree.
     * The coordinator owns reconcile, heartbeat, subscriptions, and warm release.
     */
    fun stopPolling() {
        treeSync.stopPolling()
    }

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
     * EPIC #679: project the maintained [HostTreeModel] into
     * [FolderListUiState.Ready]. The visuals stay byte-identical because the
     * projection feeds the SAME pure builders (`groupSessionsIntoFolders` /
     * `buildFolderTree`) and the same `resolveExpandedProjectPaths` auto-expand
     * the legacy rebuild used — but order, expansion, and node identity are now
     * intrinsic to the held tree (no per-emit re-derivation, no flash).
     */
    private fun emitReady(synchronous: Boolean = false) {
        // #1829: the Main-confined check-then-act seam over emitGeneration.
        requireMainThread("FolderListViewModel.emitReady")
        if (bound == null) return
        if (!tree.hasSnapshot) return
        // Issue #965 (ANR off-Main): take a CHEAP immutable snapshot of the held
        // tree on the caller's thread (Main), then run the EXPENSIVE projection
        // (O(roots × projects) `buildFolderTree` — the 71-project ANR cost) on
        // the worker [treeDispatcher]. Nothing mutable crosses the boundary
        // (snapshot is a copy) so other mutations can land on the model while the
        // build runs. The result + expansion write-back are applied back on Main.
        // The latest emit wins: a stale [emitGeneration] result is dropped.
        val snapshot = tree.snapshotForProjection()
        val generation = ++emitGeneration
        val refreshing = sessionRefreshInFlight
        if (synchronous) {
            // Issue #1109: the cold-start client-cache seed projects + emits Ready
            // INLINE on the calling (Main) thread so the FIRST painted frame is the
            // cached tree (no Loading flash). One-time per connect and bounded by
            // the cached tree size; with the folder-list row virtualization (#965)
            // in place this cannot reintroduce the ANR. The repeated reconcile-
            // driven emits below still run the projection OFF Main.
            val result = HostTreeModel.buildProjection(snapshot)
            if (generation != emitGeneration || bound == null) return
            applyReadyProjection(result, refreshing)
            return
        }
        emitJob = viewModelScope.launch {
            val result = withContext(treeDispatcher) {
                HostTreeModel.buildProjection(snapshot)
            }
            // A newer emit superseded this one (or the host changed) — drop it so
            // an out-of-order older projection can't clobber fresher state.
            if (generation != emitGeneration || bound == null) return@launch
            applyReadyProjection(result, refreshing)
        }
    }

    /**
     * Apply a freshly-built [HostTreeModel.ProjectionResult] to the held tree and
     * publish the [FolderListUiState.Ready] state. Shared by the synchronous
     * cold-start seed ([hydrateFromClientCache]) and the off-Main reconcile-driven
     * emit ([emitReady]). Must run on Main (it writes [_state]) — #1829.
     */
    private fun applyReadyProjection(
        result: HostTreeModel.ProjectionResult,
        refreshing: Boolean,
    ) {
        requireMainThread("FolderListViewModel.applyReadyProjection")
        tree.applyProjection(result)
        _state.value = folderListReadyState(
            projection = result.projection,
            refreshing = refreshing,
            creatingSession = createSessionInFlight,
            portForwarding = folderListForwardingSummary(
                hostId = bound?.hostId,
                forwardingSnapshots = forwardingSnapshots,
                discoveredPorts = lastDiscoveredPorts,
                treeHasSnapshot = tree.hasSnapshot,
            ),
        )
    }

    private fun setCreateSessionInFlight(inFlight: Boolean) {
        createSessionInFlight = inFlight
        val ready = _state.value as? FolderListUiState.Ready ?: return
        _state.value = ready.copy(isCreatingSession = inFlight)
    }

    override fun onCleared() {
        treeSync.close()
        emitJob?.cancel()
        emitJob = null
        lifecycleObserver?.let { observer ->
            // `removeObserver` is main-thread-affine; `onCleared` runs on
            // the main thread so this is safe. Only set when lifecycle
            // attachment was enabled (production path).
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
        lifecycleObserver = null
        super.onCleared()
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
        const val WARM_LEASE_RELEASE_TIMEOUT_MS: Long = 3_000L

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

        // Kept on the ViewModel companion for existing production/test call sites;
        // implementations live in FolderTreeProjection.
        fun canonicalisePath(value: String): String {
            return FolderTreeProjection.canonicalisePath(value)
        }

        fun defaultLabelForPath(path: String): String {
            return FolderTreeProjection.defaultLabelForPath(path)
        }

        fun groupSessionsIntoFolders(
            sessions: List<FolderSessionEntry>,
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
            extraFolders: Map<String, String> = emptyMap(),
        ): List<FolderRow> {
            return FolderTreeProjection.groupSessionsIntoFolders(
                sessions = sessions,
                sessionFolderPaths = sessionFolderPaths,
                watchedFolders = watchedFolders,
                extraFolders = extraFolders,
            )
        }

        fun buildFolderTree(
            sessions: List<FolderSessionEntry>,
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
            scannedProjectFoldersByRoot: Map<String, List<String>>,
            historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
            resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
            extraFolders: Map<String, String> = emptyMap(),
            stickyBuckets: Map<String, String> = emptyMap(),
        ): List<FolderTreeRoot> {
            return FolderTreeProjection.buildFolderTree(
                sessions = sessions,
                sessionFolderPaths = sessionFolderPaths,
                watchedFolders = watchedFolders,
                scannedProjectFoldersByRoot = scannedProjectFoldersByRoot,
                historyProjectFoldersByRoot = historyProjectFoldersByRoot,
                resolvedWatchedRootPaths = resolvedWatchedRootPaths,
                extraFolders = extraFolders,
                stickyBuckets = stickyBuckets,
            )
        }

        fun resolveStickyPlacements(
            sessionFolderPaths: Map<String, String>,
            watchedFolders: List<ProjectRootEntity>,
            resolvedWatchedRootPaths: Map<String, String>,
        ): Map<String, String> {
            return FolderTreeProjection.resolveStickyPlacements(
                sessionFolderPaths = sessionFolderPaths,
                watchedFolders = watchedFolders,
                resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            )
        }

        internal fun pathWithinRoot(path: String, root: String): Boolean =
            FolderTreeProjection.pathWithinRoot(path, root)

        internal fun buildRootProjectCandidates(
            projectPaths: List<String>,
            activeSessionsByProjectPath: Map<String, List<FolderSessionEntry>>,
            historyProjectPaths: List<String>,
            scannedProjectPaths: List<String>,
            extraByPath: Map<String, String> = emptyMap(),
        ): List<RootProjectCandidate> {
            return FolderTreeProjection.buildRootProjectCandidates(
                projectPaths = projectPaths,
                activeSessionsByProjectPath = activeSessionsByProjectPath,
                historyProjectPaths = historyProjectPaths,
                scannedProjectPaths = scannedProjectPaths,
                extraByPath = extraByPath,
            )
        }

        internal fun filterRootProjectCandidates(
            candidates: List<RootProjectCandidate>,
            query: String,
        ): List<RootProjectCandidate> {
            return FolderTreeProjection.filterRootProjectCandidates(candidates, query)
        }

        fun toggleProjectExpansion(expandedPaths: Set<String>, projectPath: String): Set<String> {
            return FolderTreeProjection.toggleProjectExpansion(expandedPaths, projectPath)
        }

        fun resolveExpandedProjectPaths(
            previousExpanded: Set<String>,
            visibleProjectPaths: Set<String>,
            activeProjectPaths: Set<String>,
            userCollapsedProjectPaths: Set<String>,
        ): Set<String> {
            return FolderTreeProjection.resolveExpandedProjectPaths(
                previousExpanded = previousExpanded,
                visibleProjectPaths = visibleProjectPaths,
                activeProjectPaths = activeProjectPaths,
                userCollapsedProjectPaths = userCollapsedProjectPaths,
            )
        }

        internal fun mergeForwardingPortRows(
            discoveredPorts: List<HostDiscoveredPort>,
            activeRemotePorts: Set<Int>,
        ): List<HostDiscoveredPort> {
            return FolderTreeProjection.mergeForwardingPortRows(
                discoveredPorts = discoveredPorts,
                activeRemotePorts = activeRemotePorts,
            )
        }
    }
}
