package com.pocketshell.app.tmux

import com.pocketshell.app.projects.EnginesGateway
import com.pocketshell.app.projects.EnginesResult
import com.pocketshell.app.projects.RemoteEngine
import com.pocketshell.core.storage.dao.HostDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-registry state and explicit refresh for the in-session picker. */
internal class TmuxSessionEnginePickerState(
    private val enginesGateway: EnginesGateway?,
    private val hostDao: HostDao?,
    private val scope: CoroutineScope,
    private val ioDispatcher: () -> CoroutineDispatcher,
    private val activeTarget: () -> TmuxSessionViewModel.ConnectionTarget?,
) {
    private val _engines = MutableStateFlow<List<RemoteEngine>>(emptyList())
    val engines: StateFlow<List<RemoteEngine>> = _engines.asStateFlow()
    private var refreshGeneration: Long = 0L

    fun refresh() {
        val gateway = enginesGateway
        val dao = hostDao
        val current = activeTarget()
        if (gateway == null || dao == null || current == null) {
            _engines.value = emptyList()
            return
        }
        val hostId = current.hostId
        val generation = ++refreshGeneration
        _engines.value = gateway.cachedEngines(hostId)
        scope.launch {
            val host = withContext(ioDispatcher()) { dao.getById(hostId) } ?: return@launch
            val result = withContext(ioDispatcher()) {
                gateway.listEngines(
                    host = host,
                    keyPath = current.keyPath,
                    passphrase = current.passphrase,
                )
            }
            if (activeTarget()?.hostId != hostId || generation != refreshGeneration) {
                return@launch
            }
            if (result is EnginesResult.Engines) {
                _engines.value = result.engines
            }
        }
    }
}
