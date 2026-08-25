package com.pocketshell.app.projects

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production transport adapter for [TreeSyncCoordinator]. It keeps SSH/daemon
 * mechanics out of the coordinator while preserving the existing one-warm-lease
 * and one-live-client contracts.
 */
internal class FolderListTreeSyncRemote(
    private val gateway: FolderListGateway,
    private val hostDao: HostDao,
    private val treeRemoteSource: TreeRemoteSource?,
    private val sshLeaseManager: SshLeaseManager,
    private val activeTmuxClients: ActiveTmuxClients?,
    private val scope: CoroutineScope,
    private val dispatcher: () -> CoroutineDispatcher,
    private val warmSessionAwaitMs: () -> Long,
    private val onWarmSessionAcquired: () -> Unit = {},
) : TreeSyncRemote {
    override val hasDurableTree: Boolean get() = treeRemoteSource != null

    private var warmJob: Job? = null
    private var warmLease: SshLease? = null
    private val warmSessionReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var currentBinding: TreeSyncBinding? = null
    private var warmBinding: TreeSyncBinding? = null
    private val warmMutex = Mutex()

    override fun bind(binding: TreeSyncBinding) {
        val previous = currentBinding
        if (previous != null && previous.generation > binding.generation) return
        if (previous == binding) return
        currentBinding = binding
        warmBinding = null
        warmSessionReady.value = false
        // The coordinator cancels only its wrapper job. Cancel the adapter's
        // sibling warm job immediately; the next ensure joins it before
        // releasing the old lease and dialing the new host.
        warmJob?.cancel()
    }

    override fun events(binding: TreeSyncBinding): Flow<TreeSyncEvent> {
        val registry = activeTmuxClients ?: return emptyFlow()
        return channelFlow {
            if (!isCurrent(binding)) return@channelFlow
            val params = binding.params
            launch {
                registry.clients
                    .map { snapshot -> snapshot[params.hostId]?.takeIf { it.matches(params) }?.client }
                    .distinctUntilChanged()
                    .collectLatest { client ->
                        if (client == null) return@collectLatest
                        client.events
                            .filter { it is ControlEvent.SessionsChanged }
                            .collect { send(TreeSyncEvent.SessionsChanged) }
                    }
            }
            launch {
                registry.clients
                    .map { snapshot -> snapshot[params.hostId]?.takeIf { it.matches(params) }?.client }
                    .distinctUntilChanged()
                    .collectLatest { client ->
                        if (client == null) return@collectLatest
                        client.events
                            .filter { it is ControlEvent.WindowClose }
                            .collect { event ->
                                val window = event as ControlEvent.WindowClose
                                send(TreeSyncEvent.WindowClosed(window.windowId))
                            }
                    }
            }
            awaitClose { }
        }
    }

    override suspend fun ensureWarmConnected(binding: TreeSyncBinding) = warmMutex.withLock {
        ensureWarmConnectedLocked(binding)
    }

    override suspend fun fullReconcile(
        binding: TreeSyncBinding,
        watchedFolders: List<com.pocketshell.core.storage.entity.ProjectRootEntity>,
    ): TreeSyncRemote.FullResult {
        if (!isCurrent(binding)) return staleFullResult()
        val params = binding.params
        val host = withContext(dispatcher()) { hostDao.getById(params.hostId) }
            ?: return TreeSyncRemote.FullResult.HostNotFound
        if (!isCurrent(binding)) return staleFullResult()
        return when (
            val result = gateway.listSessionsWithFolder(
                host = host,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                watchedRoots = watchedFolders,
            )
        ) {
            is FolderListResult.Sessions -> TreeSyncRemote.FullResult.Sessions(result)
            FolderListResult.ToolUnavailable -> TreeSyncRemote.FullResult.ToolUnavailable
            is FolderListResult.Failed -> TreeSyncRemote.FullResult.Failed(result.message)
            is FolderListResult.ConnectFailed -> TreeSyncRemote.FullResult.ConnectFailed(result.cause)
        }
    }

    override suspend fun getTree(binding: TreeSyncBinding): TreeRemoteSource.TreeResult {
        if (!isCurrent(binding)) return TreeRemoteSource.TreeResult.Empty
        val params = binding.params
        val source = treeRemoteSource ?: return TreeRemoteSource.TreeResult.Empty
        val session = awaitWarmSession(binding) ?: return TreeRemoteSource.TreeResult.Empty
        if (!isCurrent(binding)) return TreeRemoteSource.TreeResult.Empty
        return source.getTree(session, params.hostName)
    }

    override suspend fun reconcileTree(binding: TreeSyncBinding): TreeRemoteSource.ReconcileDelta? {
        if (!isCurrent(binding)) return null
        val params = binding.params
        val source = treeRemoteSource ?: return null
        val session = awaitWarmSession(binding) ?: return null
        if (!isCurrent(binding)) return null
        return source.reconcileTree(session, params.hostName)
    }

    override suspend fun upsertTree(
        binding: TreeSyncBinding,
        nodes: List<TreeRemoteSource.TreeNode>,
    ): Boolean {
        if (!isCurrent(binding)) return false
        val params = binding.params
        val source = treeRemoteSource ?: return false
        val session = awaitWarmSession(binding) ?: return false
        if (!isCurrent(binding)) return false
        return source.upsertTree(session, params.hostName, nodes)
    }

    override suspend fun acquireSessionForUpgrade(binding: TreeSyncBinding): SshSession? =
        warmMutex.withLock {
            if (!isCurrent(binding)) return@withLock null
            ensureWarmConnectedLocked(binding)
            if (!isCurrent(binding)) return@withLock null
            liveWarmSessionFor(binding)?.let { return@withLock it }
            warmJob?.takeIf { it.isActive }?.join()
            if (!isCurrent(binding)) return@withLock null
            liveWarmSessionFor(binding)?.let { return@withLock it }
            replaceWarmLease(binding)
            if (!isCurrent(binding)) return@withLock null
            liveWarmSessionFor(binding)
        }

    override suspend fun releaseWarm() = warmMutex.withLock {
        releaseWarmLocked()
    }

    private suspend fun awaitWarmSession(binding: TreeSyncBinding): SshSession? {
        ensureWarmConnected(binding)
        if (!isCurrent(binding)) return null
        warmSessionFor(binding)?.let { return it }
        val ready = withTimeoutOrNull(warmSessionAwaitMs()) {
            warmSessionReady.first { it }
        }
        return if (ready == true && isCurrent(binding)) warmSessionFor(binding) else null
    }

    private suspend fun ensureWarmConnectedLocked(binding: TreeSyncBinding) {
        if (!isCurrent(binding)) return
        if (warmBinding != binding) {
            invalidateWarmStateForHostSwitch()
            if (!isCurrent(binding)) return
            warmBinding = binding
            warmSessionReady.value = false
        }
        if (!isCurrent(binding)) return
        if (warmSessionFor(binding)?.isConnected == true) return
        val params = binding.params
        val liveClient = activeTmuxClients?.clients?.value?.get(params.hostId)
            ?.takeIf { it.matches(params) }
            ?.takeUnless { it.client.disconnected.value }
        if (!isCurrent(binding) || liveClient != null) return

        val existing = warmJob
        if (existing != null && existing.isActive) {
            existing.join()
        } else if (warmLease == null && (existing == null || existing.isCancelled)) {
            val job = scope.launch { replaceWarmLease(binding) }
            warmJob = job
            job.join()
        }
    }

    private suspend fun releaseWarmLocked() {
        val lease = warmLease
        warmLease = null
        warmBinding = null
        warmSessionReady.value = false
        if (lease != null) {
            withContext(NonCancellable + dispatcher()) {
                withTimeoutOrNull(WARM_LEASE_RELEASE_TIMEOUT_MS) { lease.release() }
            }
        }
    }

    /**
     * A coordinator host switch cancels only its wrapper job. The remote's
     * bind-time warm job is a sibling in [scope], and its lease must therefore
     * be invalidated here before the new host can reuse the adapter state.
     */
    private suspend fun invalidateWarmStateForHostSwitch() {
        val previousWarmJob = warmJob
        warmJob = null
        previousWarmJob?.cancelAndJoin()
        releaseWarmLocked()
    }

    private suspend fun replaceWarmLease(binding: TreeSyncBinding) {
        if (!isCurrent(binding)) return
        releaseWarmFor(binding)
        if (!isCurrent(binding)) return
        var acquiredLease: SshLease? = null
        try {
            val lease = sshLeaseManager.acquire(binding.params.toSshLeaseTarget()).getOrNull() ?: return
            acquiredLease = lease
            onWarmSessionAcquired()
            withContext(NonCancellable) {
                if (!isCurrent(binding)) return@withContext
                warmLease = lease
                acquiredLease = null
                warmBinding = binding
                warmSessionReady.value = true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            acquiredLease?.release()
        }
    }

    private fun isCurrent(binding: TreeSyncBinding): Boolean = currentBinding == binding

    private fun warmSessionFor(binding: TreeSyncBinding): SshSession? =
        warmLease?.session?.takeIf { warmBinding == binding }

    private fun liveWarmSessionFor(binding: TreeSyncBinding): SshSession? =
        warmSessionFor(binding)?.takeIf { it.isConnected }

    private suspend fun releaseWarmFor(binding: TreeSyncBinding) {
        if (warmBinding != binding) return
        releaseWarmLocked()
        // Replacing a lease for the same binding must not look like a failed
        // host switch.  Keep the completed warm attempt associated with this
        // generation so the reconcile can surface its failure through the
        // gateway instead of immediately dialing a third time.
        if (isCurrent(binding)) warmBinding = binding
    }

    private fun staleFullResult(): TreeSyncRemote.FullResult =
        TreeSyncRemote.FullResult.ConnectFailed(CancellationException("stale tree binding"))

    private fun ActiveTmuxClients.Entry.matches(params: BoundParams): Boolean =
        hostname == params.hostname &&
            port == params.port &&
            username == params.username &&
            keyPath == params.keyPath

    private companion object {
        const val WARM_LEASE_RELEASE_TIMEOUT_MS: Long = 3_000L
    }
}
