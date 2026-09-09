package com.pocketshell.next.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.next.files.RemotePath
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
import com.pocketshell.next.workspaces.canonicalRemotePath
import com.pocketshell.next.workspaces.projectWorkspaceRoots
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One saved shortcut on the screen: what [SettingsScreen] calls a Workspace root. */
data class WorkspaceRootRow(
    val id: Long,
    val label: String,
    val path: String,
    val workspaceCount: Int = 0,
)

/** One remote directory offered by the add-root browser. */
data class RootFolderEntry(val name: String, val path: String)

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
    val browsePath: String = "~",
    val browseFolders: List<RootFolderEntry> = emptyList(),
    val browseLoading: Boolean = false,
    val browseFailure: String? = null,
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
    private val clients: HostCliClientFactory,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val hostId: Long =
        checkNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID)) {
            "WorkspaceRootsViewModel requires ${Destination.ARG_HOST_ID}"
        }

    private val hostName: MutableStateFlow<String?> = MutableStateFlow(null)
    private val workspaceCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val addState = MutableStateFlow(RootAddState())
    private var browseJob: kotlinx.coroutines.Job? = null

    val state: StateFlow<WorkspaceRootsUiState> = combine(
        hostName,
        projectRootDao.getByHostId(hostId),
        addState,
        workspaceCounts,
    ) { name, roots, action, counts ->
        WorkspaceRootsUiState(
            hostName = name.orEmpty(),
            roots = roots.map {
                WorkspaceRootRow(
                    id = it.id,
                    label = it.label,
                    path = it.path,
                    workspaceCount = counts[it.label] ?: 0,
                )
            },
            loaded = name != null,
            adding = action.adding,
            failure = action.failure,
            canCreate = action.canCreate,
            createPath = action.createPath,
            browsePath = action.browsePath,
            browseFolders = action.browseFolders,
            browseLoading = action.browseLoading,
            browseFailure = action.browseFailure,
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
            if (host != null) {
                val connection = (registry.getOrConnect(hostId) as? ConnectResult.Connected)?.connection
                if (connection != null) {
                    clients.create(connection).listWorkspaces(host.treeIdentity).onSuccess { listing ->
                        val savedRoots = projectRootDao.getByHostId(hostId).first()
                        val projection = projectWorkspaceRoots(
                            sessions = emptyList(),
                            memberships = listing.workspaces,
                            registeredRoots = savedRoots.map {
                                RegisteredWorkspaceRoot(
                                    path = it.path,
                                    label = it.label,
                                    id = it.id,
                                    sortOrder = it.sortOrder,
                                )
                            },
                        )
                        workspaceCounts.value = projection.associate { root ->
                            root.label to root.workspaces.size
                        }
                    }
                }
            }
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
        val requestedPath = rootPathInput(path) ?: run {
            addState.update { it.copy(failure = "Enter a remote folder path.", canCreate = false, createPath = null) }
            return
        }
        if (requestedPath == ".") {
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
        addState.value = RootAddState(adding = true)
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                finishAdd("This host is no longer saved on this device.")
                return@launch
            }
            val connection = when (val outcome = registry.getOrConnect(hostId)) {
                is ConnectResult.NeedsTrust -> {
                    finishAdd("Confirm this host's key from the host list before adding a root.")
                    return@launch
                }
                is ConnectResult.Failed -> {
                    finishAdd(outcome.message)
                    return@launch
                }
                is ConnectResult.Connected -> outcome.connection
            }
            val canonicalPath = resolveRootPath(connection, requestedPath)
            if (canonicalPath == null) {
                finishAdd("Could not resolve the folder path on the host.")
                return@launch
            }
            val entry = runCatching { connection.sftp().stat(canonicalPath) }
                .getOrElse { error ->
                    finishAdd("Could not inspect this root: ${error.message ?: "connection error"}")
                    return@launch
                }
            when {
                entry == null -> {
                    finishAdd(
                        "That folder does not exist. You can create it on the host.",
                        canCreate = true,
                        createPath = requestedPath,
                    )
                    return@launch
                }
                !entry.isDirectory -> {
                    finishAdd("That path is a file, not a project root.")
                    return@launch
                }
            }
            val trimmedLabel = label.trim().ifBlank {
                canonicalPath.substringAfterLast('/').ifBlank { canonicalPath }
            }
            saveRoot(trimmedLabel, canonicalPath)
        }
    }

    /** Creates the exact missing directory after the user explicitly chooses it. */
    fun createRoot(label: String, path: String) {
        val requestedPath = rootPathInput(path)
        if (requestedPath == null || requestedPath == ".") {
            addState.update { it.copy(failure = "Enter a valid absolute path or a path under ~.") }
            return
        }
        if (addState.value.createPath != requestedPath || addState.value.adding) return
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
            val canonicalPath = resolveRootPath(connection, requestedPath)
            if (canonicalPath == null) {
                finishAdd("Could not resolve the folder path on the host.")
                return@launch
            }
            val trimmedLabel = label.trim().ifBlank {
                canonicalPath.substringAfterLast('/').ifBlank { canonicalPath }
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

    /** Reads one directory for the focused add-root browser. */
    fun browseRoot(path: String) {
        val requested = path.trim().ifBlank { "~" }
        browseJob?.cancel()
        addState.update {
            it.copy(
                browsePath = requested,
                browseFolders = emptyList(),
                browseLoading = true,
                browseFailure = null,
            )
        }
        browseJob = viewModelScope.launch {
            val connection = when (val outcome = registry.getOrConnect(hostId)) {
                is ConnectResult.Connected -> outcome.connection
                is ConnectResult.NeedsTrust -> {
                    finishBrowse("Confirm this host's key from the host list before browsing folders.")
                    return@launch
                }
                is ConnectResult.Failed -> {
                    finishBrowse(outcome.message)
                    return@launch
                }
            }
            val target = resolveBrowsePath(connection, requested)
            addState.update { it.copy(browsePath = target) }
            runCatching { connection.sftp().list(target) }
                .fold(
                    onSuccess = { entries ->
                        addState.update {
                            it.copy(
                                browseLoading = false,
                                browseFailure = null,
                                browseFolders = entries
                                    .filter(SftpEntry::isDirectory)
                                    .sortedBy { entry -> entry.name.lowercase() }
                                    .map { entry -> RootFolderEntry(entry.name, entry.path) },
                            )
                        }
                    },
                    onFailure = { error ->
                        finishBrowse("Could not read $target: ${error.message ?: "connection error"}")
                    },
                )
        }
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

    private fun finishBrowse(message: String) {
        addState.update { it.copy(browseLoading = false, browseFailure = message) }
    }

    /** Keeps user-entered home aliases until the host home directory is known. */
    private fun rootPathInput(path: String): String? {
        val canonical = canonicalRemotePath(path.trim().trimEnd('/')) ?: return null
        val homeAlias = canonical == "~" || canonical.startsWith("~/") ||
            canonical == "\$HOME" || canonical.startsWith("\$HOME/")
        return canonical.takeIf { it.startsWith('/') || homeAlias }
    }

    /** Resolves a root to an absolute host path before any SFTP or CLI call. */
    private suspend fun resolveRootPath(connection: HostConnection, requested: String): String? {
        val home = if (
            requested == "~" || requested.startsWith("~/") ||
            requested == "\$HOME" || requested.startsWith("\$HOME/")
        ) {
            resolveRemoteHome(connection)
        } else {
            null
        }
        return canonicalRemotePath(requested, home)
            ?.let(RemotePath::normalize)
            ?.takeIf { it.startsWith('/') }
    }

    private suspend fun resolveRemoteHome(connection: HostConnection): String? =
        runCatching { connection.exec("pwd") }.getOrNull()
            ?.takeIf { it.exitCode == 0 && !it.timedOut }
            ?.stdout
            ?.lineSequence()
            ?.map(String::trim)
            ?.lastOrNull { it.startsWith("/") }

    private suspend fun resolveBrowsePath(connection: HostConnection, requested: String): String {
        val home = if (requested == "~" || requested.startsWith("~/")) {
            resolveRemoteHome(connection)
        } else null
        val resolved = canonicalRemotePath(requested, home)
        return resolved?.let(RemotePath::normalize) ?: RemotePath.ROOT
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
        val browsePath: String = "~",
        val browseFolders: List<RootFolderEntry> = emptyList(),
        val browseLoading: Boolean = false,
        val browseFailure: String? = null,
        val successNonce: Int = 0,
    )
}
