package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A host-tree event emitted by the live remote session registry. */
internal sealed interface TreeSyncEvent {
    data object SessionsChanged : TreeSyncEvent
    data class WindowClosed(val windowId: String) : TreeSyncEvent
}

/**
 * Remote operations needed by [TreeSyncCoordinator].
 *
 * The coordinator owns *when* synchronization happens; this seam owns the
 * transport details (host lookup, warm lease reuse, tree RPCs, and live-client
 * events). Tests can therefore exercise scheduling and failure policy without
 * opening SSH channels or depending on command strings.
 */
internal interface TreeSyncRemote {
    val hasDurableTree: Boolean

    fun events(params: BoundParams): Flow<TreeSyncEvent>

    suspend fun ensureWarmConnected(params: BoundParams)

    suspend fun fullReconcile(
        params: BoundParams,
        watchedFolders: List<ProjectRootEntity>,
    ): FullResult

    suspend fun getTree(params: BoundParams): TreeRemoteSource.TreeResult

    suspend fun reconcileTree(params: BoundParams): TreeRemoteSource.ReconcileDelta?

    suspend fun upsertTree(
        params: BoundParams,
        nodes: List<TreeRemoteSource.TreeNode>,
    ): Boolean

    suspend fun acquireSessionForUpgrade(params: BoundParams): com.pocketshell.core.ssh.SshSession?

    suspend fun releaseWarm()

    sealed interface FullResult {
        data class Sessions(val result: FolderListResult.Sessions) : FullResult
        data object ToolUnavailable : FullResult
        data class Failed(val message: String) : FullResult
        data class ConnectFailed(val cause: Throwable) : FullResult
        data object HostNotFound : FullResult
    }
}

/** Injectable timing and retry policy for tree synchronization. */
internal data class TreeSyncPolicy(
    var hydrateTimeoutMs: Long = 10_000L,
    var reconcileTimeoutMs: Long = 12_000L,
    var staleAfterMs: Long = HostTreeModel.RECONCILE_STALENESS_MS,
    var resumeFreshenMs: Long = 10_000L,
    var periodicReconcileMs: Long = 5 * 60 * 1000L,
    var sessionsChangedDebounceMs: Long = 400L,
    var warmReleaseDelayMs: Long = 10_000L,
    var warmReleaseTimeoutMs: Long = 3_000L,
    var transientRetryLimit: Int = 2,
    var periodicEnabled: Boolean = false,
)

/** A user-visible synchronization failure, kept separate from UI projection. */
internal sealed interface TreeSyncFailure {
    data object Timeout : TreeSyncFailure
    data object HostNotFound : TreeSyncFailure
    data class ToolUnavailable(val result: FolderListResult = FolderListResult.ToolUnavailable) : TreeSyncFailure
    data class Failed(val message: String) : TreeSyncFailure
    data class ConnectFailed(val cause: Throwable) : TreeSyncFailure
    data class Unexpected(val cause: Throwable) : TreeSyncFailure
}

/**
 * Owns the maintained tree's synchronization lifecycle.
 *
 * [FolderListViewModel] remains the UI projection and action router. This class
 * owns the synchronization state machine: cache/durable hydration, delta versus
 * authoritative full reconcile, bounded retries, foreground gating, live-event
 * subscriptions, periodic scheduling, persistence handoff, and cancellation.
 */
internal class TreeSyncCoordinator(
    private val scope: CoroutineScope,
    private val remote: TreeSyncRemote,
    private val cache: TreeSyncCache?,
    private val processStarted: StateFlow<Boolean>,
    private val dispatcher: () -> CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    val policy: TreeSyncPolicy = TreeSyncPolicy(),
    private val awaitBeforeFullReconcile: suspend (BoundParams) -> Unit = {},
    private val listener: Listener,
) {
    interface Listener {
        fun onLoadingRequested()
        fun onRefreshingChanged(refreshing: Boolean)
        fun onTreeChanged(synchronous: Boolean = false)
        fun onReconcileSuccess(result: FolderListResult.Sessions)
        fun onReconcileFailure(failure: TreeSyncFailure)
        fun onUnexpectedFailure(cause: Throwable)
        fun onPayloadCliVersion(version: String)
    }

    val tree: HostTreeModel = HostTreeModel()

    val hasSnapshot: Boolean get() = tree.hasSnapshot
    val isPolling: Boolean get() = reconcileJob != null
    val isRefreshing: Boolean get() = refreshing
    val rootSnapshotLoaded: Boolean get() = rootSnapshotLoadedState
    val watchedFolders: List<ProjectRootEntity> get() = lastWatchedFolders

    private var bound: BoundParams? = null
    private var watchedFoldersJob: Job? = null
    private var cacheSeedJob: Job? = null
    private var hydrateJob: Job? = null
    private var reconcileJob: Job? = null
    private var eventJob: Job? = null
    private var debounceJob: Job? = null
    private var periodicJob: Job? = null
    private var warmConnectJob: Job? = null
    private var warmReleaseJob: Job? = null

    private var lastWatchedFolders: List<ProjectRootEntity> = emptyList()
    private var rootSnapshotLoadedState: Boolean = false
    private var refreshing: Boolean = false
    private var identityReconcileRequested: Boolean = false
    private var transientRetries: Int = 0
    private var foregroundGeneration: Long = 0L
    private var lastResumeGenerationHandled: Long = -1L
    private var reconcileGeneration: Long = 0L
    private var closed: Boolean = false

    /**
     * Foreground transitions are consumed here, so the ViewModel does not own a
     * second lifecycle subscription for tree synchronization.
     */
    private val lifecycleJob: Job = scope.launch {
        processStarted.collect { started ->
            if (!started) return@collect
            foregroundGeneration += 1L
            maybeReconcileOnResume()
        }
    }

    /** Bind the synchronization owner to a host and its watched-root stream. */
    fun bind(params: BoundParams, watchedFolders: Flow<List<ProjectRootEntity>>) {
        if (closed) return
        val sameHost = bound == params && !tree.bindHost(params.hostId)
        warmReleaseJob?.cancel()
        warmReleaseJob = null

        if (!sameHost) {
            reconcileJob?.cancel()
            reconcileJob = null
            hydrateJob?.cancel()
            hydrateJob = null
            cacheSeedJob?.cancel()
            cacheSeedJob = null
            eventJob?.cancel()
            eventJob = null
            debounceJob?.cancel()
            debounceJob = null
            rootSnapshotLoadedState = false
            lastWatchedFolders = emptyList()
            refreshing = false
            transientRetries = 0
            identityReconcileRequested = false
            bound = params
            tree.bindHost(params.hostId)
            warmConnectJob?.cancel()
            warmConnectJob = scope.launch { remote.ensureWarmConnected(params) }
            if (!hydrateFromClientCache(params)) {
                listener.onLoadingRequested()
                warmFromClientCacheOffMain(params)
            }
        } else {
            bound = params
            if (tree.hasSnapshot) listener.onTreeChanged()
            maybeReconcileOnOpen(params)
        }

        bindWatchedFolders(params, watchedFolders)
        ensureEventSubscription(params)
        startPeriodic(params)
    }

    /** Explicit pull-to-refresh, error-panel retry, or action confirmation. */
    fun requestReconcile() {
        val params = bound ?: return
        if (!rootSnapshotLoadedState) {
            listener.onLoadingRequested()
            return
        }
        scheduleReconcile(params, ReconcileMode.Full)
    }

    /** Request a generation-safe probe after a name-only lifecycle hint. */
    fun requestIdentityReconcile() {
        identityReconcileRequested = true
        if (isPolling) requestReconcile()
    }

    /** Mark the held tree stale for deterministic unit coverage. */
    fun forceTreeStaleForTest() {
        tree.markReconcileDueForTest()
    }

    fun setPeriodicEnabledForTest(enabled: Boolean) {
        policy.periodicEnabled = enabled
        bound?.let(::startPeriodic)
    }

    suspend fun acquireSessionForUpgrade(params: BoundParams) =
        remote.acquireSessionForUpgrade(params)

    suspend fun getTreeForUpgrade(params: BoundParams) = remote.getTree(params)

    /** Toggle + persist is a tree mutation, not a UI-only operation. */
    fun toggleProjectExpanded(projectPath: String) {
        tree.toggleProjectExpanded(projectPath)
        listener.onTreeChanged()
        bound?.let(::persist)
    }

    /** Persist after an app action that has already mutated [tree]. */
    fun persistCurrentTree() {
        bound?.let(::persist)
    }

    fun setProcessStartedForTest(started: Boolean) {
        // The owner of the flow is the ViewModel; this method exists only for
        // coordinator-focused tests that pass a MutableStateFlow as the seam.
        (processStarted as? kotlinx.coroutines.flow.MutableStateFlow<Boolean>)?.value = started
    }

    /** Stop screen-scoped work while retaining host-bound event subscriptions. */
    fun stopPolling() {
        reconcileJob?.cancel()
        reconcileJob = null
        periodicJob?.cancel()
        periodicJob = null
        scheduleWarmRelease()
    }

    /** Cancel all coordinator work and release the warm transport best-effort. */
    fun close() {
        if (closed) return
        closed = true
        lifecycleJob.cancel()
        reconcileJob?.cancel()
        watchedFoldersJob?.cancel()
        cacheSeedJob?.cancel()
        hydrateJob?.cancel()
        eventJob?.cancel()
        debounceJob?.cancel()
        periodicJob?.cancel()
        warmConnectJob?.cancel()
        warmReleaseJob?.cancel()
        CoroutineScope(dispatcher()).launch {
            withContext(NonCancellable) {
                withTimeoutOrNull(policy.warmReleaseTimeoutMs) { remote.releaseWarm() }
            }
        }
    }

    private fun bindWatchedFolders(
        params: BoundParams,
        watchedFolders: Flow<List<ProjectRootEntity>>,
    ) {
        watchedFoldersJob?.cancel()
        watchedFoldersJob = scope.launch {
            watchedFolders.collectLatest { rows ->
                if (closed || bound != params) return@collectLatest
                lastWatchedFolders = rows
                tree.setWatchedFolders(rows)
                val firstSnapshot = !rootSnapshotLoadedState
                rootSnapshotLoadedState = true
                if (firstSnapshot) {
                    hydrateTreeOnColdStart(params)
                } else {
                    listener.onTreeChanged()
                }
            }
        }
    }

    private fun hydrateFromClientCache(params: BoundParams): Boolean {
        val cached = cache?.peek(params.hostName) ?: return false
        if (cached.isEmpty) return false
        if (!applyCachedTree(cached)) return false
        listener.onTreeChanged(synchronous = true)
        return true
    }

    private fun warmFromClientCacheOffMain(params: BoundParams) {
        val cache = cache ?: return
        cacheSeedJob?.cancel()
        cacheSeedJob = scope.launch(dispatcher()) {
            val cached = runCatching { cache.read(params.hostName) }
                .getOrDefault(TreeClientCache.CachedTree(nodes = emptyList()))
            // Resume on the coordinator's owning scope after the injected
            // dispatcher finishes the blocking cache read.  Hard-coding
            // Dispatchers.Main here makes the coordinator impossible to run
            // under a focused JVM harness and gives cache I/O a second,
            // non-injectable lifecycle context.
            if (closed || bound != params || cached.isEmpty) return@launch
            if (!applyCachedTree(cached)) return@launch
            listener.onTreeChanged()
        }
    }

    private fun applyCachedTree(cached: TreeClientCache.CachedTree): Boolean {
        if (cached.watchedFolders.isNotEmpty()) tree.setWatchedFolders(cached.watchedFolders)
        tree.hydrate(cached.nodes.map { it.toHydratedNode() })
        tree.hydrateStructure(
            resolvedWatchedRootPaths = cached.resolvedWatchedRootPaths,
            scannedProjectFoldersByRoot = cached.scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = cached.historyProjectFoldersByRoot,
        )
        return tree.hasSnapshot
    }

    private fun hydrateTreeOnColdStart(params: BoundParams) {
        hydrateJob?.cancel()
        hydrateJob = scope.launch {
            cacheSeedJob?.join()
            if (closed || bound != params) return@launch
            processStarted.first { it }
            if (!remote.hasDurableTree) {
                if (isCurrent(params)) maybeReconcileOnOpen(params)
                return@launch
            }
            var cancelled = false
            try {
                val result = withTimeoutOrNull(policy.hydrateTimeoutMs) {
                    async(dispatcher()) { remote.getTree(params) }.await()
                } ?: TreeRemoteSource.TreeResult.Empty
                if (!isCurrent(params)) return@launch
                result.cliVersion?.let(listener::onPayloadCliVersion)
                if (result.nodes.isNotEmpty()) {
                    tree.hydrate(result.nodes.map { it.toHydratedNode() })
                    listener.onTreeChanged()
                }
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            } catch (_: Throwable) {
                // Hydrate is advisory. The authoritative full reconcile still
                // runs below when the host is current.
            } finally {
                if (!cancelled && isCurrent(params)) maybeReconcileOnOpen(params)
            }
        }
    }

    private fun maybeReconcileOnOpen(params: BoundParams) {
        if (!isCurrent(params)) return
        if (!rootSnapshotLoadedState) {
            listener.onLoadingRequested()
            return
        }
        lastResumeGenerationHandled = foregroundGeneration
        if (identityReconcileRequested ||
            tree.reconcileDue(now = clock(), staleAfterMs = policy.staleAfterMs)
        ) {
            scheduleReconcile(params, ReconcileMode.Full)
        } else {
            listener.onTreeChanged()
        }
    }

    private fun maybeReconcileOnResume() {
        val params = bound ?: return
        if (closed || !rootSnapshotLoadedState) return
        if (foregroundGeneration == lastResumeGenerationHandled) return
        lastResumeGenerationHandled = foregroundGeneration
        if (identityReconcileRequested ||
            !tree.reconcileDue(now = clock(), staleAfterMs = policy.resumeFreshenMs)
        ) return
        scheduleReconcile(
            params,
            if (remote.hasDurableTree && tree.hasSnapshot) ReconcileMode.DeltaThenFull
            else ReconcileMode.Full,
        )
    }

    private fun scheduleReconcile(params: BoundParams, mode: ReconcileMode) {
        reconcileJob?.cancel()
        val generation = ++reconcileGeneration
        reconcileJob = scope.launch {
            if (!isCurrent(params)) return@launch
            listener.onLoadingRequestedIfNeeded()
            processStarted.first { it }
            if (!isCurrent(params)) return@launch
            setRefreshing(true)
            try {
                when (mode) {
                    ReconcileMode.Full -> runFullReconcile(params, generation)
                    ReconcileMode.DeltaThenFull -> runDeltaThenFull(params, generation)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                if (isCurrent(params)) listener.onUnexpectedFailure(unexpected)
            } finally {
                if (generation == reconcileGeneration) setRefreshing(false)
            }
        }
    }

    private suspend fun runDeltaThenFull(params: BoundParams, generation: Long) {
        val delta = withTimeoutOrNull(policy.reconcileTimeoutMs + 1_000L) {
            remote.reconcileTree(params)
        }
        if (!isCurrent(params) || generation != reconcileGeneration) return
        delta?.cliVersion?.let(listener::onPayloadCliVersion)
        if (delta == null || delta.added.isNotEmpty() || delta.gone.isNotEmpty()) {
            runFullReconcile(params, generation)
        }
    }

    private suspend fun runFullReconcile(params: BoundParams, generation: Long) {
        while (isCurrent(params) && generation == reconcileGeneration) {
            // The warm connect has its own bound. It is intentionally outside
            // the enumeration timeout so a slow-but-valid cold dial is not
            // misreported as a 12-second list timeout.
            remote.ensureWarmConnected(params)
            if (!isCurrent(params) || generation != reconcileGeneration) return
            // The pre-extraction ViewModel awaited the bind-time engine
            // registry read before asking the folder gateway to enumerate
            // sessions. Keep that ordering at the coordinator boundary so a
            // cold bind cannot classify rows against an empty registry.
            awaitBeforeFullReconcile(params)
            if (!isCurrent(params) || generation != reconcileGeneration) return
            val result = withTimeoutOrNull(policy.reconcileTimeoutMs) {
                remote.fullReconcile(params, lastWatchedFolders)
            } ?: TreeSyncRemote.FullResult.ConnectFailed(
                FolderReconcileTimeoutException(policy.reconcileTimeoutMs),
            )
            if (!isCurrent(params) || generation != reconcileGeneration) return
            when (result) {
                is TreeSyncRemote.FullResult.Sessions -> {
                    applySuccessfulReconcile(params, result.result)
                    return
                }
                TreeSyncRemote.FullResult.HostNotFound -> {
                    listener.onReconcileFailure(TreeSyncFailure.HostNotFound)
                    return
                }
                TreeSyncRemote.FullResult.ToolUnavailable -> {
                    listener.onReconcileFailure(TreeSyncFailure.ToolUnavailable())
                    return
                }
                is TreeSyncRemote.FullResult.Failed -> {
                    if (retryTransient(params, RuntimeException(result.message))) continue
                    listener.onReconcileFailure(TreeSyncFailure.Failed(result.message))
                    return
                }
                is TreeSyncRemote.FullResult.ConnectFailed -> {
                    if (result.cause is FolderReconcileTimeoutException) {
                        listener.onReconcileFailure(TreeSyncFailure.Timeout)
                        return
                    }
                    if (retryTransient(params, result.cause)) continue
                    listener.onReconcileFailure(TreeSyncFailure.ConnectFailed(result.cause))
                    return
                }
            }
        }
    }

    private suspend fun retryTransient(params: BoundParams, cause: Throwable): Boolean {
        if (!isTransientFolderRefreshDrop(cause)) return false
        if (transientRetries >= policy.transientRetryLimit) {
            transientRetries = 0
            return false
        }
        transientRetries += 1
        return isCurrent(params)
    }

    private fun applySuccessfulReconcile(
        params: BoundParams,
        result: FolderListResult.Sessions,
    ) {
        val entries = result.rows.map { it.toSessionEntry() }
        val folderPaths = result.rows.associate { row ->
            row.sessionName to (row.cwd?.let(FolderListViewModel::canonicalisePath)
                ?: FolderListViewModel.UNTRACKED_PATH)
        }
        tree.reconcile(
            HostTreeModel.ProbeSnapshot(
                sessions = entries,
                folderPaths = folderPaths,
                scannedProjectFoldersByRoot = result.projectFoldersByRoot,
                historyProjectFoldersByRoot = result.historyProjectFoldersByRoot,
                resolvedWatchedRootPaths = result.resolvedWatchedRootPaths,
            ),
            now = clock(),
        )
        transientRetries = 0
        identityReconcileRequested = false
        listener.onReconcileSuccess(result)
        listener.onTreeChanged()
        persist(params)
    }

    private fun ensureEventSubscription(params: BoundParams) {
        if (eventJob?.isActive == true && bound == params) return
        eventJob?.cancel()
        eventJob = scope.launch {
            remote.events(params).collect { event ->
                if (!isCurrent(params) || !rootSnapshotLoadedState) return@collect
                when (event) {
                    TreeSyncEvent.SessionsChanged -> {
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(policy.sessionsChangedDebounceMs)
                            if (isCurrent(params) && rootSnapshotLoadedState) {
                                scheduleReconcile(params, ReconcileMode.Full)
                            }
                        }
                    }
                    is TreeSyncEvent.WindowClosed -> {
                        if (processStarted.value && tree.removeWindow(event.windowId)) {
                            listener.onTreeChanged()
                        }
                    }
                }
            }
        }
    }

    private fun startPeriodic(params: BoundParams) {
        periodicJob?.cancel()
        periodicJob = null
        if (!policy.periodicEnabled) return
        periodicJob = scope.launch {
            while (isCurrent(params)) {
                delay(policy.periodicReconcileMs)
                if (isCurrent(params) && rootSnapshotLoadedState) {
                    scheduleReconcile(params, ReconcileMode.Full)
                }
            }
        }
    }

    private fun persist(params: BoundParams) {
        val nodes = tree.exportNodes().map { it.toTreeNode() }
        if (nodes.isNotEmpty()) {
            scope.launch {
                withTimeoutOrNull(policy.hydrateTimeoutMs) {
                    runCatching { remote.upsertTree(params, nodes) }
                }
            }
        }
        val cache = cache ?: return
        val structure = tree.exportStructure()
        val cached = TreeClientCache.CachedTree(
            nodes = nodes,
            watchedFolders = lastWatchedFolders,
            resolvedWatchedRootPaths = structure.resolvedWatchedRootPaths,
            scannedProjectFoldersByRoot = structure.scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = structure.historyProjectFoldersByRoot,
        )
        scope.launch(dispatcher()) {
            runCatching { cache.write(params.hostName, cached) }
        }
    }

    private fun scheduleWarmRelease() {
        warmReleaseJob?.cancel()
        warmReleaseJob = scope.launch {
            delay(policy.warmReleaseDelayMs)
            withContext(NonCancellable + dispatcher()) {
                withTimeoutOrNull(policy.warmReleaseTimeoutMs) { remote.releaseWarm() }
            }
        }
    }

    private fun setRefreshing(value: Boolean) {
        if (refreshing == value) return
        refreshing = value
        listener.onRefreshingChanged(value)
    }

    private fun isCurrent(params: BoundParams): Boolean = !closed && bound == params

    private fun Listener.onLoadingRequestedIfNeeded() {
        // The ViewModel decides whether a Loading state would displace a Ready
        // snapshot; this callback is intentionally named separately to keep
        // that UI policy out of the synchronization owner.
        onLoadingRequested()
    }

    private enum class ReconcileMode { Full, DeltaThenFull }

    companion object {
        const val DEFAULT_WARM_SESSION_AWAIT_MS: Long = 8_000L
    }
}
