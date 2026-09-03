package com.pocketshell.next.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One saved shortcut on the screen: what [SettingsScreen] calls a Workspace root. */
data class WorkspaceRootRow(val id: Long, val label: String, val path: String)

/** [WorkspaceRootsScreen]'s full state: the host it belongs to plus its roots. */
data class WorkspaceRootsUiState(
    val hostName: String = "",
    val roots: List<WorkspaceRootRow> = emptyList(),
    /** True once the host name AND the first roots emission have both landed. */
    val loaded: Boolean = false,
)

/**
 * Backs [WorkspaceRootsScreen] — the per-host manager for `project_roots` rows
 * (rewrite task P-6's "workspace roots" KEEP item).
 *
 * The host id is read from [SavedStateHandle] under the route's own argument,
 * the same identity pattern [com.pocketshell.next.hosts.AddEditHostViewModel]
 * uses to avoid the audit-F1 retained-field bug — there is nothing here for a
 * stale binding to overwrite (this screen never edits the host row itself),
 * but a plain constructor field would still be one more place identity and
 * navigation could disagree.
 */
@HiltViewModel
class WorkspaceRootsViewModel @Inject constructor(
    private val projectRootDao: ProjectRootDao,
    hostDao: HostDao,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val hostId: Long =
        checkNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID)) {
            "WorkspaceRootsViewModel requires ${Destination.ARG_HOST_ID}"
        }

    private val hostName: MutableStateFlow<String?> = MutableStateFlow(null)

    val state: StateFlow<WorkspaceRootsUiState> = combine(
        hostName,
        projectRootDao.getByHostId(hostId),
    ) { name, roots ->
        WorkspaceRootsUiState(
            hostName = name.orEmpty(),
            roots = roots.map { WorkspaceRootRow(id = it.id, label = it.label, path = it.path) },
            loaded = name != null,
        )
    }
        .flowOn(dispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = WorkspaceRootsUiState(),
        )

    init {
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            hostName.value = host?.name.orEmpty().ifBlank { host?.hostname.orEmpty() }
        }
    }

    /**
     * Adds a root. [label] falls back to the last path segment when left
     * blank, so a user who only cares about the path never sees "Required".
     * A duplicate `(hostId, path)` REPLACEs the existing row's label — the
     * same "one root per path" contract [ProjectRootEntity]'s unique index
     * already enforces.
     */
    fun addRoot(label: String, path: String) {
        val trimmedPath = path.trim().trimEnd('/').ifBlank { return }
        val trimmedLabel = label.trim().ifBlank {
            trimmedPath.substringAfterLast('/').ifBlank { trimmedPath }
        }
        viewModelScope.launch {
            projectRootDao.insert(
                ProjectRootEntity(hostId = hostId, label = trimmedLabel, path = trimmedPath),
            )
        }
    }

    fun deleteRoot(root: WorkspaceRootRow) {
        viewModelScope.launch { projectRootDao.deleteById(root.id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
