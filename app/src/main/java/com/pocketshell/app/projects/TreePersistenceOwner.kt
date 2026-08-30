package com.pocketshell.app.projects

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped total-order owner for every durable tree snapshot.
 *
 * More than one [FolderListViewModel] can be alive for the same stable Room
 * host ID (Activity recreation, multi-window, or overlapping navigation). A
 * coordinator-local revision/version counter lets both owners issue revision
 * 1 / expected_version 0, and a delayed older owner can then retry over a newer
 * CAS commit. This singleton assigns one revision stream per stable host and
 * owns pending/job/remote-version state beyond any individual ViewModel.
 *
 * Writes may overlap deliberately: a slow N must not head-of-line block N+1.
 * The shared revision fence makes that safe. The local cache rejects stale
 * revisions, while remote expected-version CAS plus [isLatest] prevents a
 * conflicted N from retrying after N+1 has become the newest requested state.
 */
@Singleton
public class TreePersistenceOwner internal constructor(
    private val scope: CoroutineScope,
) {
    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    internal data class Request(
        val hostId: Long,
        val binding: TreeSyncBinding,
        val nodes: List<TreeRemoteSource.TreeNode>,
        val cached: TreeClientCache.CachedTree,
        val cache: TreeSyncCache?,
        val remote: TreeSyncRemote,
        val dispatcher: () -> CoroutineDispatcher,
        val timeoutMs: Long,
        val isCurrent: () -> Boolean,
    )

    private class HostState {
        var nextRevision: Long = 0L
        var latestRequestedRevision: Long = 0L
        var pendingRevision: Long? = null
        var remoteVersion: Long = 0L
        val jobs: MutableMap<Long, Job> = LinkedHashMap()
    }

    private val hosts = ConcurrentHashMap<Long, HostState>()

    /** Test-only suspension point before a revision attempts its local write. */
    internal var beforeLocalWriteForTest: suspend (hostId: Long, revision: Long) -> Unit = { _, _ -> }

    internal fun observeRemoteVersion(hostId: Long, version: Long) {
        val state = hosts.computeIfAbsent(hostId) { HostState() }
        synchronized(state) {
            state.remoteVersion = maxOf(state.remoteVersion, version)
        }
    }

    internal fun persist(request: Request): Long {
        val state = hosts.computeIfAbsent(request.hostId) { HostState() }
        val revision = synchronized(state) {
            val assigned = ++state.nextRevision
            state.latestRequestedRevision = assigned
            state.pendingRevision = assigned
            assigned
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            persistRevision(state, revision, request)
        }
        synchronized(state) {
            state.jobs[revision] = job
        }
        job.invokeOnCompletion {
            synchronized(state) {
                state.jobs.remove(revision)
                if (state.pendingRevision == revision) state.pendingRevision = null
            }
        }
        job.start()
        return revision
    }

    private suspend fun persistRevision(
        state: HostState,
        revision: Long,
        request: Request,
    ) {
        beforeLocalWriteForTest(request.hostId, revision)
        if (!isLatest(state, revision)) return
        request.cache?.let { target ->
            withContext(request.dispatcher()) {
                runCatching { target.write(request.hostId, revision, request.cached) }
            }
        }
        if (!isLatest(state, revision) || !request.isCurrent()) return

        var expected = synchronized(state) { state.remoteVersion }
        var outcome = withTimeoutOrNull(request.timeoutMs) {
            request.remote.upsertTree(request.binding, request.nodes, expected)
        } ?: TreeRemoteSource.UpsertResult.Unavailable

        if (outcome is TreeRemoteSource.UpsertResult.Conflict) {
            observeRemoteVersion(request.hostId, outcome.version)
            if (!isLatest(state, revision) || !request.isCurrent()) return
            val latest = withTimeoutOrNull(request.timeoutMs) {
                request.remote.getTree(request.binding)
            } ?: TreeRemoteSource.TreeResult.Unavailable
            if (latest is TreeRemoteSource.TreeResult.Available) {
                observeRemoteVersion(request.hostId, latest.version)
                expected = latest.version
                // The revision fence is checked again after the reload. N+1
                // may have been requested while N was suspended in tree.get.
                if (!isLatest(state, revision) || !request.isCurrent()) return
                outcome = withTimeoutOrNull(request.timeoutMs) {
                    request.remote.upsertTree(request.binding, request.nodes, expected)
                } ?: TreeRemoteSource.UpsertResult.Unavailable
            }
        }

        when (outcome) {
            is TreeRemoteSource.UpsertResult.Applied ->
                observeRemoteVersion(request.hostId, outcome.version)
            is TreeRemoteSource.UpsertResult.Conflict ->
                observeRemoteVersion(request.hostId, outcome.version)
            TreeRemoteSource.UpsertResult.Unavailable -> Unit
        }
    }

    private fun isLatest(state: HostState, revision: Long): Boolean =
        synchronized(state) { state.latestRequestedRevision == revision }
}
