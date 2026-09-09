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
import com.pocketshell.core.transport.SftpEntry
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
    val hostAddress: String = "",
    val hostUser: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val roots: List<WorkspaceRootProjection> = emptyList(),
    val errors: List<SessionListError> = emptyList(),
    val failure: String? = null,
    /** True when the rows are the last known projection and the refresh failed. */
    val statusUnavailable: Boolean = false,
    /** Client-side filter over the bounded durable workspace listing. */
    val searchQuery: String = "",
    val addWorkspaceVisible: Boolean = false,
    val addWorkspaceRootPath: String = "",
    val addWorkspacePath: String = "",
    val addingWorkspace: Boolean = false,
    val addWorkspaceFailure: String? = null,
    val addWorkspaceBrowserVisible: Boolean = false,
    val addWorkspaceBrowsePath: String = "",
    val addWorkspaceFolders: List<WorkspaceFolderEntry> = emptyList(),
    val addWorkspaceBrowseLoading: Boolean = false,
    val addWorkspaceBrowseFailure: String? = null,
    val addWorkspaceRootFolders: List<WorkspaceFolderEntry> = emptyList(),
    val addWorkspaceRootFoldersLoading: Boolean = false,
    val addWorkspaceRootFoldersFailure: String? = null,
    /** Set after a successful add so the selected folder opens immediately. */
    val openWorkspacePath: String? = null,
    val createFolderVisible: Boolean = false,
    val createFolderParentPath: String = "",
    val createFolderName: String = "",
    val creatingFolder: Boolean = false,
    val createFolderFailure: String? = null,
) {
    val workspaceCount: Int get() = roots.sumOf { it.workspaces.size }
    val sessionCount: Int get() = roots.sumOf { it.sessionCount }
    val isEmptyAndHealthy: Boolean
        get() = loaded && roots.isEmpty() && errors.isEmpty() &&
            failure == null && !statusUnavailable
}

/** One directory child offered by the root-scoped workspace picker. */
data class WorkspaceFolderEntry(
    val name: String,
    val path: String,
)

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
    private val workspaceOrderStore: WorkspaceOrderStore,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "HostWorkspacesViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(HostWorkspacesUiState(hostId = hostId))
    val state: StateFlow<HostWorkspacesUiState> = _state.asStateFlow()

    private var inFlight: Job? = null
    private var browseInFlight: Job? = null
    private var folderInFlight: Job? = null
    private var rootFoldersInFlight: Job? = null

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

    fun consumeOpenWorkspace(): String? {
        val path = _state.value.openWorkspacePath ?: return null
        _state.update { it.copy(openWorkspacePath = null) }
        return path
    }

    /** Moves a saved root and persists the placement for future refreshes. */
    fun moveRoot(rootId: Long, direction: Int) {
        val current = _state.value.roots
        val registered = current.filter { it.registeredRootId != null }
        val index = registered.indexOfFirst { it.registeredRootId == rootId }
        val target = index + direction
        if (index < 0 || target !in registered.indices) return
        val reordered = registered.toMutableList().apply {
            add(target, removeAt(index))
        }.mapIndexed { position, root -> root.copy(order = position.toLong()) }
        val byId = reordered.associateBy { it.registeredRootId }
        _state.update { state ->
            state.copy(
                roots = state.roots
                    .map { root -> byId[root.registeredRootId] ?: root }
                    .sortedWith(compareBy<WorkspaceRootProjection> { it.other }.thenBy { it.order }.thenBy { it.label }),
            )
        }
        viewModelScope.launch {
            val stored = projectRootDao.getByHostId(hostId).first()
                .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))
                .toMutableList()
            val storedIndex = stored.indexOfFirst { it.id == rootId }
            val storedTarget = storedIndex + direction
            if (storedIndex < 0 || storedTarget !in stored.indices) return@launch
            stored.add(storedTarget, stored.removeAt(storedIndex))
            stored.forEachIndexed { position, root ->
                projectRootDao.updateSortOrder(root.id, position.toLong())
            }
        }
    }

    /** Moves a workspace within its root; the host's durable membership stays intact. */
    fun moveWorkspace(rootPath: String, workspacePath: String, direction: Int) {
        val root = _state.value.roots.firstOrNull { it.path == rootPath } ?: return
        val paths = root.workspaces.map { it.path }.toMutableList()
        val index = paths.indexOf(workspacePath)
        val target = index + direction
        if (index < 0 || target !in paths.indices) return
        paths.add(target, paths.removeAt(index))
        workspaceOrderStore.put(hostId, rootPath, paths)
        val reordered = paths.mapNotNull { path -> root.workspaces.find { it.path == path } }
        _state.update { state ->
            state.copy(
                roots = state.roots.map { candidate ->
                    if (candidate.path == rootPath) candidate.copy(workspaces = reordered) else candidate
                },
            )
        }
    }

    fun openAddWorkspace(rootPath: String) {
        _state.update {
            it.copy(
                addWorkspaceVisible = true,
                addWorkspaceRootPath = rootPath,
                addWorkspacePath = "",
                addWorkspaceFailure = null,
                addWorkspaceBrowserVisible = false,
                addWorkspaceBrowsePath = "",
                addWorkspaceFolders = emptyList(),
                addWorkspaceBrowseFailure = null,
                addWorkspaceRootFolders = emptyList(),
                addWorkspaceRootFoldersLoading = true,
                addWorkspaceRootFoldersFailure = null,
            )
        }
        loadAddWorkspaceRootFolders(rootPath)
    }

    fun dismissAddWorkspace() {
        if (_state.value.addingWorkspace) return
        rootFoldersInFlight?.cancel()
        _state.update {
            it.copy(
                addWorkspaceVisible = false,
                addWorkspaceRootPath = "",
                addWorkspaceFailure = null,
                addWorkspaceBrowserVisible = false,
                addWorkspaceBrowsePath = "",
                addWorkspaceFolders = emptyList(),
                addWorkspaceBrowseFailure = null,
                addWorkspaceRootFolders = emptyList(),
                addWorkspaceRootFoldersLoading = false,
                addWorkspaceRootFoldersFailure = null,
            )
        }
    }

    private fun loadAddWorkspaceRootFolders(rootPath: String) {
        rootFoldersInFlight?.cancel()
        rootFoldersInFlight = viewModelScope.launch {
            val connection = resolveConnection()
            if (connection == null) {
                _state.update {
                    it.copy(
                        addWorkspaceRootFoldersLoading = false,
                        addWorkspaceRootFoldersFailure = "Could not connect to browse this root.",
                    )
                }
                return@launch
            }
            runCatching { connection.sftp().list(rootPath) }
                .fold(
                    onSuccess = { entries ->
                        _state.update {
                            it.copy(
                                addWorkspaceRootFoldersLoading = false,
                                addWorkspaceRootFolders = entries
                                    .filter(SftpEntry::isDirectory)
                                    .sortedBy { entry -> entry.name.lowercase() }
                                    .map { entry -> WorkspaceFolderEntry(entry.name, entry.path) },
                                addWorkspaceRootFoldersFailure = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                addWorkspaceRootFoldersLoading = false,
                                addWorkspaceRootFoldersFailure = userMessage(
                                    error,
                                    "Could not read this root: ",
                                ),
                            )
                        }
                    },
                )
        }
    }

    fun setAddWorkspacePath(path: String) {
        _state.update { it.copy(addWorkspacePath = path, addWorkspaceFailure = null) }
    }

    /** Opens the remote folder picker inside the root that owns the add flow. */
    fun openWorkspaceBrowser() {
        val root = _state.value.addWorkspaceRootPath.trim()
        if (root.isEmpty()) return
        browseWorkspaceFolder(root)
    }

    fun browseWorkspaceFolder(path: String) {
        val target = canonicalRemotePath(path) ?: return
        browseInFlight?.cancel()
        _state.update {
            it.copy(
                addWorkspaceVisible = false,
                addWorkspaceBrowserVisible = true,
                addWorkspaceBrowsePath = target,
                addWorkspaceFolders = emptyList(),
                addWorkspaceBrowseLoading = true,
                addWorkspaceBrowseFailure = null,
            )
        }
        browseInFlight = viewModelScope.launch {
            val connection = resolveConnection { message ->
                _state.update {
                    it.copy(
                        addWorkspaceBrowseLoading = false,
                        addWorkspaceBrowseFailure = message,
                    )
                }
            } ?: return@launch
            runCatching { connection.sftp().list(target) }
                .fold(
                    onSuccess = { entries ->
                        _state.update {
                            it.copy(
                                addWorkspaceBrowseLoading = false,
                                addWorkspaceFolders = entries
                                    .filter(SftpEntry::isDirectory)
                                    .sortedBy { entry -> entry.name.lowercase() }
                                    .map { entry -> WorkspaceFolderEntry(entry.name, entry.path) },
                                addWorkspaceBrowseFailure = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                addWorkspaceBrowseLoading = false,
                                addWorkspaceBrowseFailure = userMessage(
                                    error,
                                    "Could not browse this folder: ",
                                ),
                            )
                        }
                    },
                )
        }
    }

    fun dismissWorkspaceBrowser() {
        browseInFlight?.cancel()
        rootFoldersInFlight?.cancel()
        _state.update {
            it.copy(
                addWorkspaceVisible = true,
                addWorkspaceBrowserVisible = false,
                addWorkspaceBrowseFailure = null,
            )
        }
    }

    fun chooseWorkspaceFolder(path: String) {
        rootFoldersInFlight?.cancel()
        val canonical = canonicalRemotePath(path)
        val alreadyAdded = canonical != null && _state.value.roots
            .flatMap { root -> root.workspaces }
            .any { workspace -> canonicalRemotePath(workspace.path) == canonical }
        _state.update {
            it.copy(
                addWorkspacePath = path,
                addWorkspaceVisible = !alreadyAdded,
                addWorkspaceBrowserVisible = false,
                addWorkspaceBrowseFailure = null,
                openWorkspacePath = canonical.takeIf { alreadyAdded },
            )
        }
        if (!alreadyAdded) addWorkspace()
    }

    fun openCreateFolder(parentPath: String) {
        _state.update {
            it.copy(
                createFolderVisible = true,
                createFolderParentPath = parentPath,
                createFolderName = "",
                createFolderFailure = null,
            )
        }
    }

    fun dismissCreateFolder() {
        if (_state.value.creatingFolder) return
        _state.update {
            it.copy(
                createFolderVisible = false,
                createFolderParentPath = "",
                createFolderName = "",
                createFolderFailure = null,
            )
        }
    }

    fun setCreateFolderName(name: String) {
        _state.update { it.copy(createFolderName = name, createFolderFailure = null) }
    }

    fun addWorkspace() {
        val path = _state.value.addWorkspacePath.trim()
        if (path.isEmpty()) {
            _state.update { it.copy(addWorkspaceFailure = "Enter an absolute path or a path under ~.") }
            return
        }
        if (_state.value.addingWorkspace) return
        val root = canonicalRemotePath(_state.value.addWorkspaceRootPath)
        val canonicalPath = canonicalRemotePath(path)
        if (root == null || canonicalPath == null || !isWithinRoot(canonicalPath, root)) {
            _state.update {
                it.copy(addWorkspaceFailure = "Choose a folder inside ${_state.value.addWorkspaceRootPath}.")
            }
            return
        }
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
            val entry = runCatching { connection.sftp().stat(canonicalPath) }.getOrElse { error ->
                finishAdd(userMessage(error, "Could not inspect the workspace folder: "))
                return@launch
            }
            when {
                entry == null -> {
                    finishAdd("That folder does not exist on the host.")
                    return@launch
                }
                !entry.isDirectory -> {
                    finishAdd("That path is a file, not a workspace folder.")
                    return@launch
                }
            }
            clients.create(connection).addWorkspace(host.treeIdentity, canonicalPath).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            addWorkspaceVisible = false,
                            addWorkspaceBrowserVisible = false,
                            addingWorkspace = false,
                            addWorkspaceRootPath = "",
                            addWorkspacePath = "",
                            addWorkspaceFailure = null,
                            openWorkspacePath = canonicalPath,
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

    /** Creates one directory in a registered root and registers it as a workspace. */
    fun createFolder() {
        val current = _state.value
        val name = current.createFolderName.trim()
        val parent = canonicalRemotePath(current.createFolderParentPath)
        val invalid = name.isEmpty() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it.isISOControl() }
        if (invalid) {
            _state.update {
                it.copy(createFolderFailure = "Use one folder name without separators.")
            }
            return
        }
        if (parent == null) {
            _state.update { it.copy(createFolderFailure = "The root path is unavailable.") }
            return
        }
        if (current.creatingFolder) return
        _state.update { it.copy(creatingFolder = true, createFolderFailure = null) }
        folderInFlight?.cancel()
        folderInFlight = viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                finishFolder("This host is no longer saved on this device.")
                return@launch
            }
            val connection = resolveConnection(::finishFolder) ?: run {
                return@launch
            }
            val path = childPath(parent, name)
            val existing = runCatching { connection.sftp().stat(path) }.getOrElse { error ->
                finishFolder(userMessage(error, "Could not inspect the new folder: "))
                return@launch
            }
            if (existing != null) {
                finishFolder(
                    if (existing.isDirectory) {
                        "A folder already exists there. Add the existing folder instead."
                    } else {
                        "A file already exists there; choose a different folder name."
                    },
                )
                return@launch
            }
            runCatching { connection.sftp().mkdir(path) }.fold(
                onFailure = { error ->
                    finishFolder(userMessage(error, "Could not create the folder: "))
                },
                onSuccess = {
                    clients.create(connection).addWorkspace(host.treeIdentity, path).fold(
                        onSuccess = {
                            _state.update {
                                it.copy(
                                    createFolderVisible = false,
                                    creatingFolder = false,
                                    createFolderParentPath = "",
                                    createFolderName = "",
                                    createFolderFailure = null,
                                    addWorkspaceVisible = false,
                                    addWorkspaceBrowserVisible = false,
                                    addWorkspaceRootPath = "",
                                    addWorkspacePath = "",
                                    openWorkspacePath = path,
                                )
                            }
                            refresh()
                        },
                        onFailure = { error ->
                            finishFolder(
                                "Folder created, but it could not be added to the workspace list: " +
                                    userMessage(error, ""),
                            )
                        },
                    )
                },
            )
        }
    }

    /** Removes only the local root shortcut; remote folders and sessions remain. */
    fun removeRoot(root: WorkspaceRootProjection) {
        val id = root.registeredRootId ?: return
        viewModelScope.launch {
            projectRootDao.deleteById(id)
            refresh()
        }
    }

    private suspend fun load() {
        val host = hostDao.getById(hostId)
        if (host == null) {
            fail("This host is no longer saved on this device.")
            return
        }
        _state.update {
            it.copy(
                hostLabel = host.name.ifBlank { host.hostname },
                hostAddress = "${host.hostname}:${host.port}",
                hostUser = host.username,
            )
        }

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

    private suspend fun resolveConnection(onFailure: (String) -> Unit = {}): HostConnection? {
        return when (val outcome = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> outcome.connection
            is ConnectResult.NeedsTrust -> {
                onFailure("Confirm this host's key from the host list first.")
                null
            }
            is ConnectResult.Failed -> {
                onFailure(outcome.message)
                null
            }
        }
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
        val registeredEntities = projectRootDao.getByHostId(hostId)
            .first()
            .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))
        val registered = registeredEntities
            .map {
                RegisteredWorkspaceRoot(
                    path = it.path,
                    label = it.label,
                    createdAt = it.createdAt,
                    id = it.id,
                    sortOrder = it.sortOrder,
                )
            }
        val workspaceOrders = registeredEntities.associate { root ->
            root.path to workspaceOrderStore.get(hostId, root.path)
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
                    workspaceOrders = workspaceOrders,
                ),
                errors = sessions.errors,
                failure = null,
                statusUnavailable = false,
            )
        }
    }

    private fun fail(message: String) {
        _state.update { current ->
            current.copy(
                loading = false,
                refreshing = false,
                failure = message,
                statusUnavailable = current.loaded,
            )
        }
    }

    private fun finishAdd(message: String) {
        _state.update { it.copy(addingWorkspace = false, addWorkspaceFailure = message) }
    }

    private fun finishFolder(message: String) {
        _state.update { it.copy(creatingFolder = false, createFolderFailure = message) }
    }

    private fun userMessage(error: Throwable, prefix: String): String = when (error) {
        is HostCliError -> error.userMessage
        else -> prefix + (error.message ?: error::class.simpleName ?: "unknown error")
    }

    private fun isWithinRoot(path: String, root: String): Boolean =
        path == root || path.startsWith("$root/")

    private fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
}
