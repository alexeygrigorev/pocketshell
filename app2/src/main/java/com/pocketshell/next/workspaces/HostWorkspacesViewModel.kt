package com.pocketshell.next.workspaces

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.hostapi.HostCliError
import com.pocketshell.core.hostapi.SessionListError
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The host-level Quiet navigation state. */
data class HostWorkspacesUiState(
    val hostId: Long = 0L,
    val hostLabel: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val roots: List<WorkspaceRootProjection> = emptyList(),
    val errors: List<SessionListError> = emptyList(),
    val failure: String? = null,
    /** Client-side filter over the bounded durable workspace listing. */
    val searchQuery: String = "",
    val addWorkspaceVisible: Boolean = false,
    val addWorkspaceRootPath: String = "",
    val addWorkspacePath: String = "",
    val addingWorkspace: Boolean = false,
    val addWorkspaceFailure: String? = null,
) {
    val workspaceCount: Int get() = roots.sumOf { it.workspaces.size }
    val sessionCount: Int get() = roots.sumOf { it.sessionCount }
    val isEmptyAndHealthy: Boolean
        get() = loaded && roots.isEmpty() && errors.isEmpty() && failure == null
}

/**
 * Loads the two live host projections that make up Quiet navigation.
 *
 * The durable membership read is intentionally separate from session
 * enumeration. A host can have zero sessions and still return durable
 * workspaces, and those rows must survive that empty listing. The host's
 * opaque [com.pocketshell.core.storage.entity.HostEntity.treeIdentity] is the
 * only value passed to `workspaces list`; editable host labels never become
 * durable-state owners.
 */
@HiltViewModel
class HostWorkspacesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val hostDao: HostDao,
    private val projectRootDao: ProjectRootDao,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "HostWorkspacesViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(HostWorkspacesUiState(hostId = hostId))
    val state: StateFlow<HostWorkspacesUiState> = _state.asStateFlow()

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        _state.update { current ->
            current.copy(
                loading = !current.loaded,
                refreshing = current.loaded,
            )
        }
        inFlight = viewModelScope.launch { load() }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun openAddWorkspace(rootPath: String) {
        _state.update {
            it.copy(
                addWorkspaceVisible = true,
                addWorkspaceRootPath = rootPath,
                addWorkspacePath = "",
                addWorkspaceFailure = null,
            )
        }
    }

    fun dismissAddWorkspace() {
        if (_state.value.addingWorkspace) return
        _state.update {
            it.copy(
                addWorkspaceVisible = false,
                addWorkspaceRootPath = "",
                addWorkspaceFailure = null,
            )
        }
    }

    fun setAddWorkspacePath(path: String) {
        _state.update { it.copy(addWorkspacePath = path, addWorkspaceFailure = null) }
    }

    fun addWorkspace() {
        val path = _state.value.addWorkspacePath.trim()
        if (path.isEmpty()) {
            _state.update { it.copy(addWorkspaceFailure = "Enter an absolute path or a path under ~.") }
            return
        }
        if (_state.value.addingWorkspace) return
        _state.update { it.copy(addingWorkspace = true, addWorkspaceFailure = null) }
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                finishAdd("This host is no longer saved on this device.")
                return@launch
            }
            val connection = when (val outcome = registry.getOrConnect(hostId)) {
                is ConnectResult.Connected -> outcome.connection
                is ConnectResult.NeedsTrust -> {
                    finishAdd("Confirm this host's key from the host list before adding a workspace.")
                    return@launch
                }
                is ConnectResult.Failed -> {
                    finishAdd(outcome.message)
                    return@launch
                }
            }
            clients.create(connection).addWorkspace(host.treeIdentity, path).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            addWorkspaceVisible = false,
                            addingWorkspace = false,
                            addWorkspaceRootPath = "",
                            addWorkspacePath = "",
                            addWorkspaceFailure = null,
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    finishAdd(userMessage(error, "Could not add the workspace: "))
                },
            )
        }
    }

    private suspend fun load() {
        val host = hostDao.getById(hostId)
        if (host == null) {
            fail("This host is no longer saved on this device.")
            return
        }
        _state.update { it.copy(hostLabel = host.name.ifBlank { host.hostname }) }

        val connection = when (val outcome = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> outcome.connection
            is ConnectResult.NeedsTrust -> {
                fail(
                    "This host's key still needs to be confirmed. Open it from the host " +
                        "list to review the key.",
                )
                return
            }
            is ConnectResult.Failed -> {
                fail(outcome.message)
                return
            }
        }
        applyListing(connection, host.treeIdentity)
    }

    private suspend fun applyListing(connection: HostConnection, hostIdentity: String) {
        val client = clients.create(connection)
        val memberships = client.listWorkspaces(hostIdentity).getOrElse { error ->
            fail(userMessage(error, "Could not list workspaces on the host: "))
            return
        }
        val sessions = client.listSessions().getOrElse { error ->
            fail(userMessage(error, "Could not list sessions on the host: "))
            return
        }
        val registered = projectRootDao.getByHostId(hostId)
            .first()
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map {
                RegisteredWorkspaceRoot(
                    path = it.path,
                    label = it.label,
                    createdAt = it.createdAt,
                )
            }
        _state.update { current ->
            current.copy(
                loading = false,
                refreshing = false,
                loaded = true,
                roots = projectWorkspaceRoots(
                    sessions = sessions.sessions,
                    memberships = memberships.workspaces,
                    registeredRoots = registered,
                ),
                errors = sessions.errors,
                failure = null,
            )
        }
    }

    private fun fail(message: String) {
        _state.update { current ->
            current.copy(loading = false, refreshing = false, failure = message)
        }
    }

    private fun finishAdd(message: String) {
        _state.update { it.copy(addingWorkspace = false, addWorkspaceFailure = message) }
    }

    private fun userMessage(error: Throwable, prefix: String): String = when (error) {
        is HostCliError -> error.userMessage
        else -> prefix + (error.message ?: error::class.simpleName ?: "unknown error")
    }
}
