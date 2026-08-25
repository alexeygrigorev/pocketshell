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

/** Immutable host binding carried by every asynchronous tree operation. */
internal data class TreeSyncBinding(
    val params: BoundParams,
    val generation: Long,
)

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

    fun bind(binding: TreeSyncBinding) {}

    fun events(binding: TreeSyncBinding): Flow<TreeSyncEvent>

    suspend fun ensureWarmConnected(binding: TreeSyncBinding)

    suspend fun fullReconcile(
        binding: TreeSyncBinding,
        watchedFolders: List<ProjectRootEntity>,
    ): FullResult

    suspend fun getTree(binding: TreeSyncBinding): TreeRemoteSource.TreeResult

    suspend fun reconcileTree(binding: TreeSyncBinding): TreeRemoteSource.ReconcileDelta?

    suspend fun upsertTree(
        binding: TreeSyncBinding,
        nodes: List<TreeRemoteSource.TreeNode>,
    ): Boolean

    suspend fun acquireSessionForUpgrade(binding: TreeSyncBinding): com.pocketshell.core.ssh.SshSession?

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
    private var binding: TreeSyncBinding? = null
    private var nextBindingGeneration: Long = 0L
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
        val nextBinding = if (sameHost) {
            checkNotNull(binding)
        } else {
            TreeSyncBinding(params = params, generation = ++nextBindingGeneration)
        }
        warmReleaseJob?.cancel()
        warmReleaseJob = null
        binding = nextBinding
        remote.bind(nextBinding)

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
            warmConnectJob = scope.launch { remote.ensureWarmConnected(nextBinding) }
            if (!hydrateFromClientCache(nextBinding)) {
                listener.onLoadingRequested()
                warmFromClientCacheOffMain(nextBinding)
            }
        } else {
            bound = params
            if (tree.hasSnapshot) listener.onTreeChanged()
            maybeReconcileOnOpen(nextBinding)
        }

        bindWatchedFolders(nextBinding, watchedFolders)
        ensureEventSubscription(nextBinding)
        startPeriodic(nextBinding)
    }

    /** Explicit pull-to-refresh, error-panel retry, or action confirmation. */
    fun requestReconcile() {
        val currentBinding = binding ?: return
        if (!rootSnapshotLoadedState) {
            listener.onLoadingRequested()
            return
        }
        scheduleReconcile(currentBinding, ReconcileMode.Full)
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
        binding?.let(::startPeriodic)
    }

    suspend fun acquireSessionForUpgrade(params: BoundParams) =
        binding?.takeIf { it.params == params }?.let { current ->
            remote.acquireSessionForUpgrade(current)
        }

    suspend fun getTreeForUpgrade(params: BoundParams) =
        binding?.takeIf { it.params == params }?.let { current ->
            remote.getTree(current)
        }
            ?: TreeRemoteSource.TreeResult.Empty

    /** Toggle + persist is a tree mutation, not a UI-only operation. */
    fun toggleProjectExpanded(projectPath: String) {
        tree.toggleProjectExpanded(projectPath)
        listener.onTreeChanged()
        binding?.let(::persist)
    }

    /** Persist after an app action that has already mutated [tree]. */
    fun persistCurrentTree() {
        binding?.let(::persist)
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
        binding: TreeSyncBinding,
        watchedFolders: Flow<List<ProjectRootEntity>>,
    ) {
        watchedFoldersJob?.cancel()
        watchedFoldersJob = scope.launch {
            watchedFolders.collectLatest { rows ->
                if (!isCurrent(binding)) return@collectLatest
                lastWatchedFolders = rows
                tree.setWatchedFolders(rows)
                val firstSnapshot = !rootSnapshotLoadedState
                rootSnapshotLoadedState = true
                if (firstSnapshot) {
                    hydrateTreeOnColdStart(binding)
                } else {
                    listener.onTreeChanged()
                }
            }
        }
    }

    private fun hydrateFromClientCache(binding: TreeSyncBinding): Boolean {
        val params = binding.params
        val cached = cache?.peek(params.hostName) ?: return false
        if (cached.isEmpty) return false
        if (!applyCachedTree(cached)) return false
        listener.onTreeChanged(synchronous = true)
        return true
    }

    private fun warmFromClientCacheOffMain(binding: TreeSyncBinding) {
        val params = binding.params
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
            if (!isCurrent(binding) || cached.isEmpty) return@launch
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

    private fun hydrateTreeOnColdStart(binding: TreeSyncBinding) {
        val params = binding.params
        hydrateJob?.cancel()
        hydrateJob = scope.launch {
            cacheSeedJob?.join()
            if (!isCurrent(binding)) return@launch
            processStarted.first { it }
            if (!remote.hasDurableTree) {
                if (isCurrent(binding)) maybeReconcileOnOpen(binding)
                return@launch
            }
            var cancelled = false
            try {
                val result = withTimeoutOrNull(policy.hydrateTimeoutMs) {
                    async(dispatcher()) { remote.getTree(binding) }.await()
                } ?: TreeRemoteSource.TreeResult.Empty
                if (!isCurrent(binding)) return@launch
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
                if (!cancelled && isCurrent(binding)) maybeReconcileOnOpen(binding)
            }
        }
    }

    private fun maybeReconcileOnOpen(binding: TreeSyncBinding) {
        if (!isCurrent(binding)) return
        val params = binding.params
        if (!rootSnapshotLoadedState) {
            listener.onLoadingRequested()
            return
        }
        lastResumeGenerationHandled = foregroundGeneration
        if (identityReconcileRequested ||
            tree.reconcileDue(now = clock(), staleAfterMs = policy.staleAfterMs)
        ) {
            scheduleReconcile(binding, ReconcileMode.Full)
        } else {
            listener.onTreeChanged()
        }
    }

    private fun maybeReconcileOnResume() {
        val binding = binding ?: return
        val params = binding.params
        if (!isCurrent(binding) || !rootSnapshotLoadedState) return
        if (foregroundGeneration == lastResumeGenerationHandled) return
        lastResumeGenerationHandled = foregroundGeneration
        if (identityReconcileRequested ||
            !tree.reconcileDue(now = clock(), staleAfterMs = policy.resumeFreshenMs)
        ) return
        scheduleReconcile(
            binding,
            if (remote.hasDurableTree && tree.hasSnapshot) ReconcileMode.DeltaThenFull
            else ReconcileMode.Full,
        )
    }

    private fun scheduleReconcile(binding: TreeSyncBinding, mode: ReconcileMode) {
        reconcileJob?.cancel()
        val generation = ++reconcileGeneration
        reconcileJob = scope.launch {
            if (!isCurrent(binding)) return@launch
            listener.onLoadingRequestedIfNeeded()
            processStarted.first { it }
            if (!isCurrent(binding)) return@launch
            setRefreshing(true)
            try {
                when (mode) {
                    ReconcileMode.Full -> runFullReconcile(binding, generation)
                    ReconcileMode.DeltaThenFull -> runDeltaThenFull(binding, generation)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                if (isCurrent(binding)) listener.onUnexpectedFailure(unexpected)
            } finally {
                if (generation == reconcileGeneration) setRefreshing(false)
            }
        }
    }

    private suspend fun runDeltaThenFull(binding: TreeSyncBinding, generation: Long) {
        val delta = withTimeoutOrNull(policy.reconcileTimeoutMs + 1_000L) {
            remote.reconcileTree(binding)
        }
        if (!isCurrent(binding) || generation != reconcileGeneration) return
        delta?.cliVersion?.let(listener::onPayloadCliVersion)
        if (delta == null || delta.added.isNotEmpty() || delta.gone.isNotEmpty()) {
            runFullReconcile(binding, generation)
        }
    }

    private suspend fun runFullReconcile(binding: TreeSyncBinding, generation: Long) {
        val params = binding.params
        while (isCurrent(binding) && generation == reconcileGeneration) {
            // The warm connect has its own bound. It is intentionally outside
            // the enumeration timeout so a slow-but-valid cold dial is not
            // misreported as a 12-second list timeout.
            remote.ensureWarmConnected(binding)
            if (!isCurrent(binding) || generation != reconcileGeneration) return
            // The pre-extraction ViewModel awaited the bind-time engine
            // registry read before asking the folder gateway to enumerate
            // sessions. Keep that ordering at the coordinator boundary so a
            // cold bind cannot classify rows against an empty registry.
            awaitBeforeFullReconcile(params)
            if (!isCurrent(binding) || generation != reconcileGeneration) return
            val result = withTimeoutOrNull(policy.reconcileTimeoutMs) {
                remote.fullReconcile(binding, lastWatchedFolders)
            } ?: TreeSyncRemote.FullResult.ConnectFailed(
                FolderReconcileTimeoutException(policy.reconcileTimeoutMs),
            )
            if (!isCurrent(binding) || generation != reconcileGeneration) return
            when (result) {
                is TreeSyncRemote.FullResult.Sessions -> {
                    applySuccessfulReconcile(binding, result.result)
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
                    if (retryTransient(binding, RuntimeException(result.message))) continue
                    listener.onReconcileFailure(TreeSyncFailure.Failed(result.message))
                    return
                }
                is TreeSyncRemote.FullResult.ConnectFailed -> {
                    if (result.cause is FolderReconcileTimeoutException) {
                        listener.onReconcileFailure(TreeSyncFailure.Timeout)
                        return
                    }
                    if (retryTransient(binding, result.cause)) continue
                    listener.onReconcileFailure(TreeSyncFailure.ConnectFailed(result.cause))
                    return
                }
            }
        }
    }

    private suspend fun retryTransient(binding: TreeSyncBinding, cause: Throwable): Boolean {
        if (!isTransientFolderRefreshDrop(cause)) return false
        if (transientRetries >= policy.transientRetryLimit) {
            transientRetries = 0
            return false
        }
        transientRetries += 1
        return isCurrent(binding)
    }

    private fun applySuccessfulReconcile(
        binding: TreeSyncBinding,
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
        persist(binding)
    }

    private fun ensureEventSubscription(binding: TreeSyncBinding) {
        val params = binding.params
        if (eventJob?.isActive == true && this.binding == binding) return
        eventJob?.cancel()
        eventJob = scope.launch {
            remote.events(binding).collect { event ->
                if (!isCurrent(binding) || !rootSnapshotLoadedState) return@collect
                when (event) {
                    TreeSyncEvent.SessionsChanged -> {
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(policy.sessionsChangedDebounceMs)
                            if (isCurrent(binding) && rootSnapshotLoadedState) {
                                scheduleReconcile(binding, ReconcileMode.Full)
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

    private fun startPeriodic(binding: TreeSyncBinding) {
        periodicJob?.cancel()
        periodicJob = null
        if (!policy.periodicEnabled) return
        periodicJob = scope.launch {
            while (isCurrent(binding)) {
                delay(policy.periodicReconcileMs)
                if (isCurrent(binding) && rootSnapshotLoadedState) {
                    scheduleReconcile(binding, ReconcileMode.Full)
                }
            }
        }
    }

    private fun persist(binding: TreeSyncBinding) {
        val params = binding.params
        val nodes = tree.exportNodes().map { it.toTreeNode() }
        if (nodes.isNotEmpty()) {
            scope.launch {
                if (!isCurrent(binding)) return@launch
                withTimeoutOrNull(policy.hydrateTimeoutMs) {
                    runCatching { remote.upsertTree(binding, nodes) }
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
            if (isCurrent(binding)) {
                runCatching { cache.write(params.hostName, cached) }
            }
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

    private fun isCurrent(binding: TreeSyncBinding): Boolean = !closed && this.binding == binding

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
