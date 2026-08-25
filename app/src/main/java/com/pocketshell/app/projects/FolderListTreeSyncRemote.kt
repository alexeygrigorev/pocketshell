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
    private var boundParams: BoundParams? = null

    override fun events(params: BoundParams): Flow<TreeSyncEvent> {
        val registry = activeTmuxClients ?: return emptyFlow()
        return channelFlow {
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

    override suspend fun ensureWarmConnected(params: BoundParams) {
        if (boundParams != params) {
            invalidateWarmStateForHostSwitch()
            boundParams = params
        }
        if (warmLease?.session?.isConnected == true) return
        val liveClient = activeTmuxClients?.clients?.value?.get(params.hostId)
            ?.takeIf { it.matches(params) }
            ?.takeUnless { it.client.disconnected.value }
        if (liveClient != null) return

        val existing = warmJob
        if (existing != null && existing.isActive) {
            existing.join()
        } else if (warmLease == null && (existing == null || existing.isCancelled)) {
            val job = scope.launch { replaceWarmLease(params) }
            warmJob = job
            job.join()
        }
    }

    override suspend fun fullReconcile(
        params: BoundParams,
        watchedFolders: List<com.pocketshell.core.storage.entity.ProjectRootEntity>,
    ): TreeSyncRemote.FullResult {
        val host = withContext(dispatcher()) { hostDao.getById(params.hostId) }
            ?: return TreeSyncRemote.FullResult.HostNotFound
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

    override suspend fun getTree(params: BoundParams): TreeRemoteSource.TreeResult {
        val source = treeRemoteSource ?: return TreeRemoteSource.TreeResult.Empty
        val session = awaitWarmSession(params) ?: return TreeRemoteSource.TreeResult.Empty
        return source.getTree(session, params.hostName)
    }

    override suspend fun reconcileTree(params: BoundParams): TreeRemoteSource.ReconcileDelta? {
        val source = treeRemoteSource ?: return null
        val session = awaitWarmSession(params) ?: return null
        return source.reconcileTree(session, params.hostName)
    }

    override suspend fun upsertTree(
        params: BoundParams,
        nodes: List<TreeRemoteSource.TreeNode>,
    ): Boolean {
        val source = treeRemoteSource ?: return false
        val session = awaitWarmSession(params) ?: return false
        return source.upsertTree(session, params.hostName, nodes)
    }

    override suspend fun acquireSessionForUpgrade(params: BoundParams): SshSession? {
        ensureWarmConnected(params)
        warmLease?.session?.takeIf { it.isConnected }?.let { return it }
        warmJob?.takeIf { it.isActive }?.join()
        warmLease?.session?.takeIf { it.isConnected }?.let { return it }
        replaceWarmLease(params)
        return warmLease?.session?.takeIf { it.isConnected }
    }

    override suspend fun releaseWarm() {
        val lease = warmLease ?: return
        warmLease = null
        warmSessionReady.value = false
        withContext(NonCancellable + dispatcher()) {
            withTimeoutOrNull(WARM_LEASE_RELEASE_TIMEOUT_MS) { lease.release() }
        }
    }

    private suspend fun awaitWarmSession(params: BoundParams): SshSession? {
        ensureWarmConnected(params)
        warmLease?.session?.let { return it }
        val ready = withTimeoutOrNull(warmSessionAwaitMs()) {
            warmSessionReady.first { it }
        }
        return if (ready == true && boundParams == params) warmLease?.session else null
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
        releaseWarm()
    }

    private suspend fun replaceWarmLease(params: BoundParams) {
        releaseWarm()
        var acquiredLease: SshLease? = null
        try {
            val lease = sshLeaseManager.acquire(params.toSshLeaseTarget()).getOrNull() ?: return
            acquiredLease = lease
            onWarmSessionAcquired()
            withContext(NonCancellable) {
                if (boundParams != params) return@withContext
                warmLease = lease
                acquiredLease = null
                boundParams = params
                warmSessionReady.value = true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            acquiredLease?.release()
        }
    }

    private fun ActiveTmuxClients.Entry.matches(params: BoundParams): Boolean =
        hostname == params.hostname &&
            port == params.port &&
            username == params.username &&
            keyPath == params.keyPath

    private companion object {
        const val WARM_LEASE_RELEASE_TIMEOUT_MS: Long = 3_000L
    }
}
