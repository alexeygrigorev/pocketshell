package com.pocketshell.next.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.workspaces.canonicalRemotePath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One saved shortcut on the screen: what [SettingsScreen] calls a Workspace root. */
data class WorkspaceRootRow(val id: Long, val label: String, val path: String)

/** [WorkspaceRootsScreen]'s full state: the host it belongs to plus its roots. */
data class WorkspaceRootsUiState(
    val hostName: String = "",
    val roots: List<WorkspaceRootRow> = emptyList(),
    /** True once the host name AND the first roots emission have both landed. */
    val loaded: Boolean = false,
    val adding: Boolean = false,
    val failure: String? = null,
    /** The checked path can be created explicitly when it does not exist. */
    val canCreate: Boolean = false,
    val createPath: String? = null,
    val successNonce: Int = 0,
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
    private val hostDao: HostDao,
    private val registry: ConnectionsRegistry,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val hostId: Long =
        checkNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID)) {
            "WorkspaceRootsViewModel requires ${Destination.ARG_HOST_ID}"
        }

    private val hostName: MutableStateFlow<String?> = MutableStateFlow(null)
    private val addState = MutableStateFlow(RootAddState())

    val state: StateFlow<WorkspaceRootsUiState> = combine(
        hostName,
        projectRootDao.getByHostId(hostId),
        addState,
    ) { name, roots, action ->
        WorkspaceRootsUiState(
            hostName = name.orEmpty(),
            roots = roots.map { WorkspaceRootRow(id = it.id, label = it.label, path = it.path) },
            loaded = name != null,
            adding = action.adding,
            failure = action.failure,
            canCreate = action.canCreate,
            createPath = action.createPath,
            successNonce = action.successNonce,
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
        val trimmedPath = path.trim().trimEnd('/').ifBlank {
            addState.update { it.copy(failure = "Enter a remote folder path.", canCreate = false, createPath = null) }
            return
        }
        val canonicalPath = canonicalRemotePath(trimmedPath)
        if (canonicalPath == null || canonicalPath == ".") {
            addState.update {
                it.copy(
                    failure = "Enter a valid absolute path or a path under ~.",
                    canCreate = false,
                    createPath = null,
                )
            }
            return
        }
        if (addState.value.adding) return
        val trimmedLabel = label.trim().ifBlank {
            canonicalPath.substringAfterLast('/').ifBlank { canonicalPath }
        }
        addState.value = RootAddState(adding = true)
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                finishAdd("This host is no longer saved on this device.")
                return@launch
            }
            when (val outcome = registry.getOrConnect(hostId)) {
                is ConnectResult.NeedsTrust -> {
                    finishAdd("Confirm this host's key from the host list before adding a root.")
                    return@launch
                }
                is ConnectResult.Failed -> {
                    finishAdd(outcome.message)
                    return@launch
                }
                is ConnectResult.Connected -> {
                    val entry = runCatching { outcome.connection.sftp().stat(canonicalPath) }
                        .getOrElse { error ->
                            finishAdd("Could not inspect this root: ${error.message ?: "connection error"}")
                            return@launch
                        }
                    when {
                        entry == null -> {
                            finishAdd(
                                "That folder does not exist. You can create it on the host.",
                                canCreate = true,
                                createPath = canonicalPath,
                            )
                            return@launch
                        }
                        !entry.isDirectory -> {
                            finishAdd("That path is a file, not a project root.")
                            return@launch
                        }
                    }
                }
            }
            saveRoot(trimmedLabel, canonicalPath)
        }
    }

    /** Creates the exact missing directory after the user explicitly chooses it. */
    fun createRoot(label: String, path: String) {
        val canonicalPath = canonicalRemotePath(path.trim().trimEnd('/'))
        if (canonicalPath == null || canonicalPath == ".") {
            addState.update { it.copy(failure = "Enter a valid absolute path or a path under ~.") }
            return
        }
        if (addState.value.createPath != canonicalPath || addState.value.adding) return
        val trimmedLabel = label.trim().ifBlank {
            canonicalPath.substringAfterLast('/').ifBlank { canonicalPath }
        }
        addState.value = RootAddState(adding = true)
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                finishAdd("This host is no longer saved on this device.")
                return@launch
            }
            val connection = when (val outcome = registry.getOrConnect(hostId)) {
                is ConnectResult.Connected -> outcome.connection
                is ConnectResult.NeedsTrust -> {
                    finishAdd("Confirm this host's key from the host list before creating a root.")
                    return@launch
                }
                is ConnectResult.Failed -> {
                    finishAdd(outcome.message)
                    return@launch
                }
            }
            val existing = runCatching { connection.sftp().stat(canonicalPath) }.getOrElse { error ->
                finishAdd("Could not inspect this root: ${error.message ?: "connection error"}")
                return@launch
            }
            when {
                existing?.isDirectory == true -> saveRoot(trimmedLabel, canonicalPath)
                existing != null -> finishAdd("That path is a file, not a project root.")
                else -> try {
                    connection.sftp().mkdir(canonicalPath)
                    saveRoot(trimmedLabel, canonicalPath)
                } catch (error: Throwable) {
                    finishAdd("Could not create the root: ${error.message ?: "permission denied"}")
                }
            }
        }
    }

    fun deleteRoot(root: WorkspaceRootRow) = viewModelScope.launch(dispatcher) {
        projectRootDao.deleteById(root.id)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }

    private fun finishAdd(message: String) {
        finishAdd(message, canCreate = false, createPath = null)
    }

    private fun finishAdd(message: String, canCreate: Boolean, createPath: String?) {
        addState.update {
            it.copy(adding = false, failure = message, canCreate = canCreate, createPath = createPath)
        }
    }

    private suspend fun saveRoot(label: String, path: String) {
        projectRootDao.insert(ProjectRootEntity(hostId = hostId, label = label, path = path))
        addState.update {
            it.copy(
                adding = false,
                failure = null,
                canCreate = false,
                createPath = null,
                successNonce = it.successNonce + 1,
            )
        }
    }

    private data class RootAddState(
        val adding: Boolean = false,
        val failure: String? = null,
        val canCreate: Boolean = false,
        val createPath: String? = null,
        val successNonce: Int = 0,
    )
}
