package com.pocketshell.app.projects

import com.pocketshell.core.storage.dao.HostDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the host engine-registry read-through used by the picker.
 *
 * There is no ticker. Bind and an explicit picker-open retry are the only
 * refresh triggers. Failed reads preserve the last valid rows, including
 * disabled/unavailable rows needed to render existing sessions.
 */
internal class FolderListEngineDiscovery(
    private val enginesGateway: EnginesGateway?,
    private val hostDao: HostDao,
    private val scope: CoroutineScope,
    private val ioDispatcher: () -> CoroutineDispatcher,
    private val isCurrentHost: (Long) -> Boolean,
    private val onRefreshApplied: (Long) -> Unit = {},
) {
    private val _engines = MutableStateFlow<List<RemoteEngine>>(emptyList())
    val engines: StateFlow<List<RemoteEngine>> = _engines.asStateFlow()

    private var fetchGeneration: Long = 0L
    private var stateHostId: Long? = null
    private var latestRefreshJob: Job? = null
    private var bindRefreshJob: Job? = null
    private var initialReadPending: Boolean = false

    /** Start the bind-time read and keep its ordering state with the owner. */
    fun bind(params: BoundParams): Job? {
        bindRefreshJob?.cancel()
        initialReadPending = enginesGateway != null
        val job = startRefresh(params)
        bindRefreshJob = job
        if (job == null) {
            initialReadPending = false
        } else {
            job.invokeOnCompletion {
                if (bindRefreshJob === job) initialReadPending = false
            }
        }
        return job
    }

    /** Start one explicit picker-open read. */
    fun refresh(params: BoundParams): Job? {
        initialReadPending = false
        return startRefresh(params)
    }

    private fun startRefresh(params: BoundParams): Job? {
        val generation = ++fetchGeneration
        val initialReadForHost = stateHostId != params.hostId
        if (initialReadForHost) {
            stateHostId = params.hostId
            _engines.value = emptyList()
            latestRefreshJob = null
        }
        val gateway = enginesGateway ?: return null
        val job = scope.launch {
            try {
                val host = withContext(ioDispatcher()) { hostDao.getById(params.hostId) }
                    ?: return@launch
                val result = withContext(ioDispatcher()) {
                    gateway.listEngines(
                        host = host,
                        keyPath = params.keyPath,
                        passphrase = params.passphrase,
                    )
                }
                if (!isCurrentHost(params.hostId) || generation != fetchGeneration) return@launch
                // Only a valid response replaces state. A failed read leaves the
                // last good registry intact; a valid empty registry is distinct.
                if (result is EnginesResult.Engines) {
                    _engines.value = result.engines
                    if (!initialReadPending) onRefreshApplied(params.hostId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Engine metadata is advisory. Session/tree discovery remains
                // usable when the registry read itself fails unexpectedly.
            }
        }
        latestRefreshJob = job
        return job
    }

    /**
     * Order the first session enumeration after the bind-triggered registry
     * read. A successful picker-open retry separately requests one reconcile
     * through [onRefreshApplied].
     */
    suspend fun awaitLatestRefresh(hostId: Long) {
        if (stateHostId == hostId) latestRefreshJob?.join()
    }

    /** Await only the bind read; picker retries are already callback-ordered. */
    suspend fun awaitBindRefresh(hostId: Long) {
        if (stateHostId == hostId) bindRefreshJob?.join()
    }
}
