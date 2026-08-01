package com.pocketshell.app.projects

import com.pocketshell.core.storage.dao.HostDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns host profile discovery and the profile state consumed by new-session
 * pickers.
 *
 * Fetches run on bind and whenever a picker opens (#718/#1875). A monotonically
 * increasing generation prevents an older request from overwriting a newer
 * picker refresh, while [isCurrentHost] prevents a completed request from
 * leaking profiles across a host switch.
 */
internal class FolderListProfileDiscovery(
    private val profilesGateway: ProfilesGateway?,
    private val hostDao: HostDao,
    private val scope: CoroutineScope,
    private val ioDispatcher: () -> CoroutineDispatcher,
    private val isCurrentHost: (Long) -> Boolean,
) {
    private val _claudeProfiles = MutableStateFlow<List<ClaudeProfile>>(emptyList())
    val claudeProfiles: StateFlow<List<ClaudeProfile>> = _claudeProfiles.asStateFlow()

    private val _codexProfiles = MutableStateFlow<List<CodexProfile>>(emptyList())
    val codexProfiles: StateFlow<List<CodexProfile>> = _codexProfiles.asStateFlow()

    private var fetchGeneration: Long = 0L

    fun refresh(params: BoundParams) {
        val generation = ++fetchGeneration
        val gateway = profilesGateway ?: run {
            clearProfiles()
            return
        }
        scope.launch {
            val dispatcher = ioDispatcher()
            val host = withContext(dispatcher) { hostDao.getById(params.hostId) }
                ?: return@launch
            val result = withContext(dispatcher) {
                gateway.listProfiles(
                    host = host,
                    keyPath = params.keyPath,
                    passphrase = params.passphrase,
                )
            }
            if (!isCurrentHost(params.hostId) || generation != fetchGeneration) {
                return@launch
            }
            when (result) {
                is ProfilesResult.Profiles -> {
                    val profiles = result.profiles.toFolderListProfileLists()
                    _claudeProfiles.value = profiles.claudeProfiles
                    _codexProfiles.value = profiles.codexProfiles
                }
                else -> clearProfiles()
            }
        }
    }

    private fun clearProfiles() {
        _claudeProfiles.value = emptyList()
        _codexProfiles.value = emptyList()
    }
}
