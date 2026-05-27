package com.pocketshell.app.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight read-only view-model that surfaces a host's watched
 * folders as a [StateFlow] for use inside the new-session create
 * dialog (issue #204 integration for #206).
 *
 * Separate from [WatchedFoldersViewModel] so the create-session
 * surface doesn't pull in the heavier write / discover plumbing
 * just to render a chip row.
 *
 * Caller pattern:
 *
 * ```
 * val vm: WatchedFoldersChipsViewModel = hiltViewModel()
 * LaunchedEffect(hostId) { vm.bind(hostId) }
 * val roots by vm.roots.collectAsState()
 * ```
 *
 * Re-binding to a different hostId cancels the previous subscription
 * and emits the new host's rows.
 */
@HiltViewModel
class WatchedFoldersChipsViewModel @Inject constructor(
    private val projectRootDao: ProjectRootDao,
) : ViewModel() {

    private val _roots: MutableStateFlow<List<ProjectRootEntity>> =
        MutableStateFlow(emptyList())

    val roots: StateFlow<List<ProjectRootEntity>> = _roots.asStateFlow()

    private var observeJob: Job? = null
    private var hostId: Long? = null

    fun bind(hostId: Long?) {
        if (this.hostId == hostId) return
        this.hostId = hostId
        observeJob?.cancel()
        if (hostId == null) {
            _roots.value = emptyList()
            return
        }
        observeJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { rows ->
                _roots.value = rows
            }
        }
    }
}
