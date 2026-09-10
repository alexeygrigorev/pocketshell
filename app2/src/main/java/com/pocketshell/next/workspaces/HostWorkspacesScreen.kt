package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.SESSION_TREE_FILES_TAG
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SESSION_TREE_USAGE_TAG
import com.pocketshell.next.usage.UsageGlancePill
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.UsageGlanceViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FormDialog
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.WorkspaceRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.pocketshell.uikit.icons.PocketShellIcons

const val HOST_WORKSPACES_TAG: String = "host-workspaces"
const val HOST_WORKSPACES_LIST_TAG: String = "host-workspaces-list"
const val HOST_WORKSPACES_LOADING_TAG: String = "host-workspaces-loading"
const val HOST_WORKSPACES_EMPTY_TAG: String = "host-workspaces-empty"
const val HOST_WORKSPACES_ERROR_TAG: String = "host-workspaces-error"
const val HOST_WORKSPACES_PARTIAL_BANNER_TAG: String = "host-workspaces-partial-banner"
const val HOST_WORKSPACES_RETRY_TAG: String = "host-workspaces-retry"
const val HOST_WORKSPACES_BACK_TAG: String = "host-workspaces-back"
const val HOST_WORKSPACES_ACTIONS_TAG: String = "host-workspaces-actions"
const val HOST_WORKSPACES_REORDER_TAG: String = "host-workspaces-reorder"
const val HOST_WORKSPACES_ADD_TAG: String = "host-workspaces-add"
const val HOST_WORKSPACES_SEARCH_TAG: String = "host-workspaces-search"
const val HOST_WORKSPACES_ADD_PATH_TAG: String = "host-workspaces-add-path"
const val HOST_WORKSPACES_ADD_CONFIRM_TAG: String = "host-workspaces-add-confirm"
const val HOST_WORKSPACES_ADD_BROWSE_TAG: String = "host-workspaces-add-browse"
const val HOST_WORKSPACES_ADD_LOCATION_TAG: String = "host-workspaces-add-location"
const val HOST_WORKSPACES_FOLDER_BROWSER_TAG: String = "host-workspaces-folder-browser"
const val HOST_WORKSPACES_FOLDER_BROWSER_USE_TAG: String = "host-workspaces-folder-browser-use"
const val HOST_WORKSPACES_CREATE_FOLDER_NAME_TAG: String = "host-workspaces-create-folder-name"
const val HOST_WORKSPACES_CREATE_FOLDER_CONFIRM_TAG: String = "host-workspaces-create-folder-confirm"
const val HOST_WORKSPACES_CREATE_FOLDER_TAG: String = "host-workspaces-create-folder"
const val HOST_WORKSPACES_ROOT_START_SESSION_TAG: String = "host-workspaces-root-start-session"
const val HOST_WORKSPACES_ROOT_COPY_PATH_TAG: String = "host-workspaces-root-copy-path"
const val HOST_WORKSPACES_ROOT_BROWSE_TAG: String = "host-workspaces-root-browse"
const val HOST_WORKSPACES_ROOT_REMOVE_TAG: String = "host-workspaces-root-remove"
const val HOST_WORKSPACES_ROOT_REMOVE_CONFIRM_TAG: String = "host-workspaces-root-remove-confirm"
const val HOST_WORKSPACES_IN_ROOT_LABEL: String = "In this root"
const val HOST_WORKSPACES_ROOT_EMPTY_TAG: String = "host-workspaces-root-empty"
const val HOST_WORKSPACES_HOST_TOOLS_TAG: String = "host-workspaces-host-tools"
const val HOST_WORKSPACES_PROJECT_ROOTS_TAG: String = "host-workspaces-project-roots"
const val HOST_WORKSPACES_CONNECTION_DETAILS_TAG: String = "host-workspaces-connection-details"
const val HOST_WORKSPACES_REFRESH_TAG: String = "host-workspaces-refresh"
const val HOST_WORKSPACES_DISCONNECT_TAG: String = "host-workspaces-disconnect"

fun workspaceRowTag(path: String): String = "workspace-row-$path"

fun workspaceSessionRowTag(name: String): String = "workspace-session-row-$name"

fun workspaceRootTag(key: String): String = "workspace-root-$key"

fun workspaceRootAddTag(key: String): String = "workspace-root-add-$key"

fun workspaceRootActionsTag(key: String): String = "workspace-root-actions-$key"

fun workspaceRootBrowseTag(path: String): String = "workspace-root-browse-$path"

/** Route-level binding for the real host workspace projection. */
@Composable
fun HostWorkspacesRoute(
    onOpenWorkspace: (String) -> Unit,
    onStartSessionAtPath: (String) -> Unit = onOpenWorkspace,
    onOpenReorder: () -> Unit = {},
    onOpenSession: (SessionRow) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenFilesAtPath: (String) -> Unit = { onOpenFiles() },
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenProjectRoots: () -> Unit = {},
    onOpenConnectionDetails: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    initialRootPath: String? = null,
    initialRootAction: String? = null,
    /**
     * Issue #2632: when true, this visit was started by opening the HOST (a
     * cold-launch resume or a host-row tap), so the last session the user was
     * in on this host is reopened as soon as the live listing confirms it is
     * still running. Backing out of that session returns here and does NOT
     * re-fire: the arm is consumed once by the nav-entry-scoped ViewModel.
     */
    resumeLastSession: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: HostWorkspacesViewModel = hiltViewModel(),
    /**
     * Optional so a Robolectric composition with no Hilt graph can still host
     * the route — the same seam [com.pocketshell.next.tree.SessionTreeRoute]
     * uses. Production always passes one.
     */
    usageGlanceViewModel: UsageGlanceViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    val usagePillState = hostUsageGlancePill(usageGlanceViewModel, state.loaded)
    LaunchedEffect(resumeLastSession) { if (resumeLastSession) viewModel.armResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LaunchedEffect(state.resumeSession) {
        val session = viewModel.consumeResumeSession() ?: return@LaunchedEffect
        onOpenSession(session)
    }
    LaunchedEffect(initialRootPath, initialRootAction) {
        val root = initialRootPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        when (initialRootAction) {
            "add-workspace" -> viewModel.openAddWorkspace(root)
            "create-folder" -> viewModel.openCreateFolder(root)
        }
    }
    LaunchedEffect(state.openWorkspacePath) {
        val path = viewModel.consumeOpenWorkspace() ?: return@LaunchedEffect
        // A just-added workspace has no sessions yet, so this deliberately
        // goes to the workspace screen rather than through the direct entry.
        onOpenWorkspace(path)
    }

    // Issue #2632 (maintainer follow-up 2026-09-10): "I don't want to have
    // another screen — I want to jump to the last session I opened". A
    // workspace tap therefore resolves to that workspace's own session and
    // opens the terminal directly; the workspace screen is what a workspace
    // with NOTHING running still needs, and only that.
    val openWorkspaceDirectly: (String) -> Unit = { path ->
        val entry = viewModel.entrySessionFor(path)
        if (entry != null) onOpenSession(entry) else onOpenWorkspace(path)
    }

    HostWorkspacesScreen(
        state = state,
        // Pull-to-refresh means "tell me what is true now", and the usage
        // number is part of that. `loaded` only flips once, so without this
        // the pill would keep showing the reading from screen entry.
        onRefresh = {
            viewModel.refresh()
            usageGlanceViewModel?.refresh()
        },
        onOpenWorkspace = openWorkspaceDirectly,
        onStartSessionAtPath = onStartSessionAtPath,
        onOpenReorder = onOpenReorder,
        onOpenSession = onOpenSession,
        onOpenFiles = onOpenFiles,
        onOpenFilesAtPath = onOpenFilesAtPath,
        onOpenPorts = onOpenPorts,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
        usagePillState = usagePillState,
        onOpenProjectRoots = onOpenProjectRoots,
        onOpenConnectionDetails = onOpenConnectionDetails,
        onDisconnect = onDisconnect,
        onOpenAddWorkspace = viewModel::openAddWorkspace,
        onSearchQueryChange = viewModel::setSearchQuery,
        onAddWorkspacePathChange = viewModel::setAddWorkspacePath,
        onConfirmAddWorkspace = viewModel::addWorkspace,
        onDismissAddWorkspace = viewModel::dismissAddWorkspace,
        onOpenWorkspaceBrowser = viewModel::openWorkspaceBrowser,
        onBrowseWorkspaceFolder = viewModel::browseWorkspaceFolder,
        onChooseWorkspaceFolder = viewModel::chooseWorkspaceFolder,
        onDismissWorkspaceBrowser = viewModel::dismissWorkspaceBrowser,
        onOpenCreateFolder = viewModel::openCreateFolder,
        onCreateFolderNameChange = viewModel::setCreateFolderName,
        onConfirmCreateFolder = viewModel::createFolder,
        onDismissCreateFolder = viewModel::dismissCreateFolder,
        onRemoveRoot = viewModel::removeRoot,
        modifier = modifier,
    )
}

/**
 * The host-scoped usage glance (issue #2632).
 *
 * Refreshed on `ON_START` — the same event the workspace listing uses — and
 * AGAIN once [loaded] flips true. The second trigger is what makes the pill
 * reliable rather than lucky: on a cold-launch resume both effects fire while
 * the dial is still in flight, and a `fetchAll` at that moment sees no live
 * connection (D21: usage never dials, it only asks hosts that are already
 * connected) and returns nothing. Re-asking once the listing has landed means
 * the connection definitely exists.
 */
@Composable
private fun hostUsageGlancePill(
    viewModel: UsageGlanceViewModel?,
    loaded: Boolean,
): UsageGlancePillState? {
    if (viewModel == null) return null
    val pill by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LaunchedEffect(loaded) { if (loaded) viewModel.refresh() }
    return pill
}

/** Stateless Quiet host root; all rows come from [HostWorkspacesUiState]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostWorkspacesScreen(
    state: HostWorkspacesUiState,
    onRefresh: () -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onStartSessionAtPath: (String) -> Unit = onOpenWorkspace,
    onOpenReorder: () -> Unit = {},
    onOpenSession: (SessionRow) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenFilesAtPath: (String) -> Unit = { onOpenFiles() },
    onOpenPorts: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    usagePillState: UsageGlancePillState? = null,
    onOpenProjectRoots: () -> Unit = {},
    onOpenConnectionDetails: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onOpenAddWorkspace: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onAddWorkspacePathChange: (String) -> Unit = {},
    onConfirmAddWorkspace: () -> Unit = {},
    onDismissAddWorkspace: () -> Unit = {},
    onOpenWorkspaceBrowser: () -> Unit = {},
    onBrowseWorkspaceFolder: (String) -> Unit = {},
    onChooseWorkspaceFolder: (String) -> Unit = {},
    onDismissWorkspaceBrowser: () -> Unit = {},
    onOpenCreateFolder: (String) -> Unit = {},
    onCreateFolderNameChange: (String) -> Unit = {},
    onConfirmCreateFolder: () -> Unit = {},
    onDismissCreateFolder: () -> Unit = {},
    onRemoveRoot: (WorkspaceRootProjection) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Clock for the dense workspace row's relative activity label (#2630). */
    nowSec: Long = System.currentTimeMillis() / 1000,
) {
    val clipboard = LocalClipboardManager.current
    var activeRootActions by remember { mutableStateOf<WorkspaceRootProjection?>(null) }
    var rootPendingRemoval by remember { mutableStateOf<WorkspaceRootProjection?>(null) }
    var hostToolsVisible by remember { mutableStateOf(false) }
    var connectionDetailsVisible by remember { mutableStateOf(false) }

    // These are full navigation surfaces in the Android handoff. They are
    // state-backed here so returning from the folder browser keeps the typed
    // path and the selected root without stacking two modal windows.
    if (state.addWorkspaceBrowserVisible) {
        WorkspaceFolderBrowserPage(
            state = state,
            existingWorkspacePaths = state.roots
                .flatMap { root -> root.workspaces }
                .map { workspace -> workspace.path }
                .toSet(),
            onBrowse = onBrowseWorkspaceFolder,
            onChoose = onChooseWorkspaceFolder,
            onCreateFolder = { onOpenCreateFolder(state.addWorkspaceBrowsePath) },
            onCreateFolderNameChange = onCreateFolderNameChange,
            onConfirmCreateFolder = onConfirmCreateFolder,
            onDismissCreateFolder = onDismissCreateFolder,
            onDismiss = onDismissWorkspaceBrowser,
            modifier = modifier,
        )
        return
    }
    if (state.addWorkspaceVisible) {
        AddWorkspacePage(
            state = state,
            onBrowse = onOpenWorkspaceBrowser,
            onSelectFolder = onChooseWorkspaceFolder,
            onCreateFolder = { onOpenCreateFolder(state.addWorkspaceRootPath) },
            onStartSession = { onStartSessionAtPath(state.addWorkspaceRootPath) },
            onCreateFolderNameChange = onCreateFolderNameChange,
            onConfirmCreateFolder = onConfirmCreateFolder,
            onDismissCreateFolder = onDismissCreateFolder,
            onDismiss = onDismissAddWorkspace,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HOST_WORKSPACES_TAG),
    ) {
        ScreenHeader(
            title = state.hostLabel.ifBlank { "Workspaces" },
            subtitle = hostWorkspacesSubtitle(state),
            onBack = onBack,
            backTestTag = HOST_WORKSPACES_BACK_TAG,
            trailing = {
                // Issue #2632: usage sits ON the host screen, not three taps
                // deep behind the kebab's Host tools sheet.
                usagePillState?.let { pill ->
                    UsageGlancePill(state = pill, onClick = onOpenUsage)
                }
                KebabTrigger(
                    onClick = { hostToolsVisible = true },
                    contentDescription = "Host actions",
                    triggerTestTag = HOST_WORKSPACES_ACTIONS_TAG,
                )
            },
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Find a workspace") },
            leadingIcon = {
                Icon(
                    imageVector = PocketShellIcons.Search,
                    contentDescription = null,
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(
                    start = PocketShellSpacing.xl,
                    end = PocketShellSpacing.xl,
                    bottom = PocketShellSpacing.md,
                )
                .testTag(HOST_WORKSPACES_SEARCH_TAG),
            shape = PocketShellShapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PocketShellColors.Surface,
                unfocusedContainerColor = PocketShellColors.Surface,
                focusedTextColor = PocketShellColors.Text,
                unfocusedTextColor = PocketShellColors.Text,
                focusedBorderColor = PocketShellColors.Accent,
                unfocusedBorderColor = PocketShellColors.Border,
                cursorColor = PocketShellColors.Accent,
            ),
        )

        if (state.errors.isNotEmpty()) {
            Banner(
                text = "Some sessions may be missing: " +
                    state.errors.map { it.message }.distinct().joinToString(", "),
                role = BannerRole.Warning,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(HOST_WORKSPACES_PARTIAL_BANNER_TAG),
            )
        }

        state.failure?.let { failure ->
            Banner(
                text = if (state.statusUnavailable) {
                    "Cannot reach ${state.hostLabel.ifBlank { "this host" }}. " +
                        "Saved workspaces are shown; session status is unavailable."
                } else {
                    failure
                },
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = onRefresh,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(HOST_WORKSPACES_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(HOST_WORKSPACES_ERROR_TAG),
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                !state.loaded && state.failure == null -> EmptyState(
                    title = "Loading workspaces…",
                    description = "Reading durable workspaces and live sessions from the host.",
                    modifier = Modifier.testTag(HOST_WORKSPACES_LOADING_TAG),
                )

                !state.loaded && state.failure != null -> EmptyState(
                    title = "Status unavailable",
                    description = state.failure,
                    action = {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRefresh,
                            modifier = Modifier.testTag(HOST_WORKSPACES_RETRY_TAG),
                        )
                    },
                    modifier = Modifier.testTag(HOST_WORKSPACES_EMPTY_TAG),
                )

                state.statusUnavailable && state.roots.isEmpty() -> EmptyState(
                    title = "Status unavailable",
                    description = state.failure ?: "Could not refresh this host.",
                    action = {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRefresh,
                            modifier = Modifier.testTag(HOST_WORKSPACES_RETRY_TAG),
                        )
                    },
                    modifier = Modifier.testTag(HOST_WORKSPACES_EMPTY_TAG),
                )

                state.isEmptyAndHealthy -> EmptyState(
                    title = "No workspaces yet",
                    description = "Add an existing folder or create a new one inside this root.",
                    modifier = Modifier.testTag(HOST_WORKSPACES_EMPTY_TAG),
                )

                filteredRoots(state).isEmpty() && state.searchQuery.isNotBlank() -> EmptyState(
                    title = "No matching workspaces",
                    description = "Search names or paths from this host.",
                    modifier = Modifier.testTag(HOST_WORKSPACES_EMPTY_TAG),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HOST_WORKSPACES_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    filteredRoots(state).forEach { root ->
                        itemContent(
                            root = root,
                            sessionsUnavailable = state.statusUnavailable || state.errors.isNotEmpty(),
                            nowSec = nowSec,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenSession = onOpenSession,
                            onOpenAddWorkspace = onOpenAddWorkspace,
                            onOpenRootActions = { activeRootActions = root },
                        )
                    }
                }
            }
        }
    }

    if (hostToolsVisible) {
        HostToolsSheet(
            hostLabel = state.hostLabel,
            onOpenFiles = {
                hostToolsVisible = false
                onOpenFiles()
            },
            onOpenPorts = {
                hostToolsVisible = false
                onOpenPorts()
            },
            onOpenUsage = {
                hostToolsVisible = false
                onOpenUsage()
            },
            onOpenProjectRoots = {
                hostToolsVisible = false
                onOpenProjectRoots()
            },
            onOpenConnectionDetails = {
                hostToolsVisible = false
                connectionDetailsVisible = true
            },
            onRefresh = {
                hostToolsVisible = false
                onRefresh()
            },
            onReorder = {
                hostToolsVisible = false
                onOpenReorder()
            },
            onDisconnect = {
                hostToolsVisible = false
                onDisconnect()
            },
            onDismiss = { hostToolsVisible = false },
        )
    }

    if (connectionDetailsVisible) {
        HostConnectionDetailsSheet(
            state = state,
            onEdit = {
                connectionDetailsVisible = false
                onOpenConnectionDetails()
            },
            onDismiss = { connectionDetailsVisible = false },
        )
    }

    if (state.createFolderVisible) {
        FormDialog(
            title = "Create folder",
            confirmLabel = if (state.creatingFolder) "Creating…" else "Create folder",
            onConfirm = onConfirmCreateFolder,
            onDismiss = onDismissCreateFolder,
            confirmEnabled = state.createFolderName.isNotBlank() && !state.creatingFolder,
            confirmTestTag = HOST_WORKSPACES_CREATE_FOLDER_CONFIRM_TAG,
        ) {
            Text(
                text = "Creates a workspace folder inside ${state.createFolderParentPath}.",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
            OutlinedTextField(
                value = state.createFolderName,
                onValueChange = onCreateFolderNameChange,
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HOST_WORKSPACES_CREATE_FOLDER_NAME_TAG),
            )
            state.createFolderFailure?.let { message ->
                Text(
                    text = message,
                    color = PocketShellColors.Red,
                    style = PocketShellType.metadata,
                    modifier = Modifier.testTag(HOST_WORKSPACES_CREATE_FOLDER_TAG),
                )
            }
        }
    }

    activeRootActions?.let { root ->
        RootActionsSheet(
            root = root,
            onAddWorkspace = {
                root.path?.let(onOpenAddWorkspace)
                activeRootActions = null
            },
            onCreateFolder = {
                root.path?.let(onOpenCreateFolder)
                activeRootActions = null
            },
            onStartSession = {
                onStartSessionAtPath(root.path ?: root.key)
                activeRootActions = null
            },
            onCopyPath = {
                root.path?.let { path -> clipboard.setText(AnnotatedString(path)) }
                activeRootActions = null
            },
            onBrowse = {
                root.path?.let(onOpenFilesAtPath)
                activeRootActions = null
            },
            onRemove = {
                rootPendingRemoval = root
                activeRootActions = null
            },
            onDismiss = { activeRootActions = null },
        )
    }

    rootPendingRemoval?.let { root ->
        ConfirmDialog(
            title = "Remove root from this host?",
            message = "This removes the saved shortcut. Folders and sessions stay on the host.",
            confirmLabel = "Remove root",
            destructive = true,
            onConfirm = {
                rootPendingRemoval = null
                onRemoveRoot(root)
            },
            onDismiss = { rootPendingRemoval = null },
            confirmTestTag = HOST_WORKSPACES_ROOT_REMOVE_CONFIRM_TAG,
        )
    }
}

@Composable
private fun AddWorkspacePage(
    state: HostWorkspacesUiState,
    onBrowse: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onStartSession: () -> Unit,
    onCreateFolderNameChange: (String) -> Unit,
    onConfirmCreateFolder: () -> Unit,
    onDismissCreateFolder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val visibleRootFolders = state.addWorkspaceRootFolders.filter { folder ->
        query.isBlank() || folder.name.contains(query, ignoreCase = true) ||
            folder.path.contains(query, ignoreCase = true)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HOST_WORKSPACES_TAG),
    ) {
        ScreenHeader(
            title = "Add workspace",
            onBack = onDismiss,
            backTestTag = HOST_WORKSPACES_BACK_TAG,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.lg),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                PocketShellSpacing.md,
            ),
        ) {
            RemoteLocationRow(
                path = displayRemotePath(state.addWorkspaceRootPath) ?: state.addWorkspaceRootPath,
                action = "Change",
                onClick = onBrowse,
                modifier = Modifier.testTag(HOST_WORKSPACES_ADD_LOCATION_TAG),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find a folder") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HOST_WORKSPACES_ADD_PATH_TAG),
                shape = PocketShellShapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = PocketShellColors.Surface,
                    unfocusedContainerColor = PocketShellColors.Surface,
                    focusedTextColor = PocketShellColors.Text,
                    unfocusedTextColor = PocketShellColors.Text,
                    focusedBorderColor = PocketShellColors.Accent,
                    unfocusedBorderColor = PocketShellColors.Border,
                    cursorColor = PocketShellColors.Accent,
                ),
            )
            ListRow(
                title = "Create folder",
                leading = {
                    Icon(
                        imageVector = PocketShellIcons.Plus,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                onClick = onCreateFolder,
                modifier = Modifier.testTag(HOST_WORKSPACES_CREATE_FOLDER_TAG),
            )
            Text(
                text = "Folders",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.label,
            )
            when {
                state.addWorkspaceRootFoldersLoading -> Text(
                    text = "Reading folders…",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
                state.addWorkspaceRootFoldersFailure != null -> Text(
                    text = state.addWorkspaceRootFoldersFailure.orEmpty(),
                    color = PocketShellColors.Red,
                    style = PocketShellType.metadata,
                )
                state.addWorkspaceRootFolders.isEmpty() -> Text(
                    text = "No folders in this root",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
                else -> visibleRootFolders.forEach { folder ->
                    val alreadyAdded = state.roots
                        .flatMap { root -> root.workspaces }
                        .any { workspace -> canonicalRemotePath(workspace.path) == canonicalRemotePath(folder.path) }
                    ListRow(
                        title = folder.name,
                        subtitle = "Already added".takeIf { alreadyAdded },
                        onClick = { onSelectFolder(folder.path) },
                    )
                }
            }
            ListRow(
                title = "Browse subfolders",
                leading = {
                    Icon(
                        imageVector = PocketShellIcons.Folder,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                onClick = onBrowse,
                modifier = Modifier.testTag(HOST_WORKSPACES_ADD_BROWSE_TAG),
            )
            ListRow(
                title = "Start session in ${state.addWorkspaceRootPath}",
                subtitle = "Use the root itself",
                leading = {
                    Icon(
                        imageVector = PocketShellIcons.Terminal,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                onClick = onStartSession,
            )
            state.addWorkspaceFailure?.let { message ->
                Text(
                    text = message,
                    color = PocketShellColors.Red,
                    style = PocketShellType.metadata,
                )
            }
        }
    }
    CreateFolderFormDialog(
        state = state,
        onNameChange = onCreateFolderNameChange,
        onConfirm = onConfirmCreateFolder,
        onDismiss = onDismissCreateFolder,
    )
}

/** Compact path/action row used by the folder pickers in the design catalog. */
@Composable
private fun RemoteLocationRow(
    path: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = path,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            modifier = Modifier.weight(1f),
        )
        if (action.isNotBlank()) {
            PocketShellButton(
                text = action,
                onClick = onClick,
                variant = ButtonVariant.Text,
                compact = true,
            )
        }
    }
}

@Composable
private fun WorkspaceFolderBrowserPage(
    state: HostWorkspacesUiState,
    existingWorkspacePaths: Set<String>,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFolderNameChange: (String) -> Unit,
    onConfirmCreateFolder: () -> Unit,
    onDismissCreateFolder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPath = state.addWorkspaceBrowsePath
    val rootPath = canonicalRemotePath(state.addWorkspaceRootPath)
    val existingCanonicalPaths = existingWorkspacePaths.mapNotNull(::canonicalRemotePath).toSet()
    var query by remember(currentPath) { mutableStateOf("") }
    val visibleFolders = state.addWorkspaceFolders.filter { folder ->
        query.isBlank() || folder.name.contains(query, ignoreCase = true) ||
            folder.path.contains(query, ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HOST_WORKSPACES_FOLDER_BROWSER_TAG),
    ) {
        ScreenHeader(
            title = "Choose folder",
            onBack = onDismiss,
            backTestTag = HOST_WORKSPACES_BACK_TAG,
        )
        val parent = currentPath
            .takeIf { rootPath != null && it != rootPath }
            ?.let(::parentPath)
            ?.takeIf { rootPath == null || isPathWithin(it, rootPath) }
        RemoteLocationRow(
            path = displayRemotePath(currentPath) ?: currentPath,
            action = "Up".takeIf { parent != null }.orEmpty(),
            onClick = { parent?.let(onBrowse) },
            modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find a folder") },
            leadingIcon = {
                Icon(imageVector = PocketShellIcons.Search, contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.sm),
            shape = PocketShellShapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PocketShellColors.Surface,
                unfocusedContainerColor = PocketShellColors.Surface,
                focusedTextColor = PocketShellColors.Text,
                unfocusedTextColor = PocketShellColors.Text,
                focusedBorderColor = PocketShellColors.Accent,
                unfocusedBorderColor = PocketShellColors.Border,
                cursorColor = PocketShellColors.Accent,
            ),
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
        ) {
            if (state.addWorkspaceBrowseLoading) {
                item {
                    Text(
                        text = "Reading folders…",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
                    )
                }
            } else {
                if (visibleFolders.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "No subfolders" else "No matching folders",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.metadata,
                            modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
                        )
                    }
                } else {
                    items(
                        items = visibleFolders,
                        key = { folder -> folder.path },
                    ) { folder ->
                        val alreadyAdded = canonicalRemotePath(folder.path) in existingCanonicalPaths
                        ListRow(
                            title = folder.name,
                            subtitle = if (alreadyAdded) {
                                "Already added · ${folder.path}"
                            } else {
                                folder.path
                            },
                            onClick = { onBrowse(folder.path) },
                        )
                    }
                }
                item {
                    ListRow(
                        title = "Create folder here",
                        subtitle = "Create and add a workspace",
                        onClick = onCreateFolder,
                        modifier = Modifier.testTag(HOST_WORKSPACES_CREATE_FOLDER_TAG),
                    )
                }
            }
            state.addWorkspaceBrowseFailure?.let { message ->
                item {
                    Text(
                        text = message,
                        color = PocketShellColors.Red,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.sm),
        ) {
            PocketShellButton(
                text = "Use this folder",
                onClick = { onChoose(currentPath) },
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HOST_WORKSPACES_FOLDER_BROWSER_USE_TAG),
            )
        }
        CreateFolderFormDialog(
            state = state,
            onNameChange = onCreateFolderNameChange,
            onConfirm = onConfirmCreateFolder,
            onDismiss = onDismissCreateFolder,
        )
    }
}

@Composable
private fun CreateFolderFormDialog(
    state: HostWorkspacesUiState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.createFolderVisible) return
    FormDialog(
        title = "Create folder",
        confirmLabel = if (state.creatingFolder) "Creating…" else "Create folder",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = state.createFolderName.isNotBlank() && !state.creatingFolder,
        confirmTestTag = HOST_WORKSPACES_CREATE_FOLDER_CONFIRM_TAG,
    ) {
        Text(
            text = "Creates a workspace folder inside ${state.createFolderParentPath}.",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
        )
        OutlinedTextField(
            value = state.createFolderName,
            onValueChange = onNameChange,
            label = { Text("Folder name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HOST_WORKSPACES_CREATE_FOLDER_NAME_TAG),
        )
        state.createFolderFailure?.let { message ->
            Text(
                text = message,
                color = PocketShellColors.Red,
                style = PocketShellType.metadata,
                modifier = Modifier.testTag(HOST_WORKSPACES_CREATE_FOLDER_TAG),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemContent(
    root: WorkspaceRootProjection,
    sessionsUnavailable: Boolean,
    nowSec: Long,
    onOpenWorkspace: (String) -> Unit,
    onOpenSession: (SessionRow) -> Unit,
    onOpenAddWorkspace: (String) -> Unit,
    onOpenRootActions: () -> Unit,
) {
    item(key = "root-header:${root.key}") {
        SectionHeader(
            label = root.displayPath.ifBlank { root.label },
            onLabelClick = root.path?.let { { onOpenRootActions() } },
            labelTestTag = root.path?.let { workspaceRootActionsTag(root.key) },
            trailing = root.path?.let { path ->
                {
                    PocketShellButton(
                        text = "+ Add",
                        onClick = { onOpenAddWorkspace(path) },
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(workspaceRootAddTag(root.key)),
                    )
                }
            },
            modifier = Modifier.testTag(workspaceRootTag(root.key)),
        )
    }

    if (root.rootSessions.isNotEmpty()) {
        val displayNames = sessionDisplayNames(root.rootSessions)
        item(key = "root-sessions:${root.key}") {
            SectionHeader(
                label = HOST_WORKSPACES_IN_ROOT_LABEL,
                count = root.rootSessions.size,
            )
        }
        items(
            items = root.rootSessions,
            key = { session -> "root-session:${root.key}:${session.name}" },
        ) { session ->
            WorkspaceSessionRow(
                session = session,
                displayName = displayNames[session.name] ?: session.name,
                statusUnavailable = sessionsUnavailable,
                onClick = { onOpenSession(session) },
            )
        }
    }

    if (root.workspaces.isEmpty() && root.rootSessions.isEmpty()) {
        item(key = "root-empty:${root.key}") {
            ListRow(
                title = "No workspaces yet",
                subtitle = "Durable workspaces stay in this root.",
                modifier = Modifier.testTag(HOST_WORKSPACES_ROOT_EMPTY_TAG),
            )
        }
    } else {
        items(
            items = root.workspaces,
            key = { workspace -> "workspace:${workspace.path}" },
        ) { workspace ->
            // #2630: one information-dense line per workspace — name, session
            // count, recency — matching PocketShell Desktop's sidebar, instead
            // of a title row over a wrapped per-kind summary.
            WorkspaceRow(
                title = workspace.label,
                dense = true,
                trailingContent = {
                    WorkspaceGlance(
                        path = workspace.path,
                        sessions = workspace.sessions,
                        nowSec = nowSec,
                        unavailable = sessionsUnavailable,
                    )
                },
                onClick = { onOpenWorkspace(workspace.path) },
                testTag = workspaceRowTag(workspace.path),
            )
        }
    }
}

@Composable
private fun WorkspaceSessionRow(
    session: SessionRow,
    displayName: String = session.name,
    statusUnavailable: Boolean = false,
    onClick: () -> Unit,
) {
    ListRow(
        title = displayName,
        subtitle = if (statusUnavailable) "Status unavailable" else sessionKindLabel(session),
        leading = { SessionKindMark(agent = session.agent) },
        titleStyle = PocketShellType.body,
        subtitleStyle = PocketShellType.metadata,
        titleWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        titleMaxLines = 2,
        onClick = onClick,
        modifier = Modifier.testTag(workspaceSessionRowTag(session.name)),
    )
}

private fun filteredRoots(state: HostWorkspacesUiState): List<WorkspaceRootProjection> {
    val query = state.searchQuery.trim().lowercase()
    if (query.isEmpty()) return state.roots
    return state.roots.mapNotNull { root ->
        val rootMatches = root.label.lowercase().contains(query) ||
            root.displayPath.lowercase().contains(query)
        val workspaces = root.workspaces.filter { workspace ->
            rootMatches ||
                workspace.label.lowercase().contains(query) ||
                workspace.displayPath.lowercase().contains(query) ||
                workspace.sessions.any { it.name.lowercase().contains(query) }
        }
        // Search results are workspace objects only. Root-level sessions stay
        // under their root's "In this root" section in the unfiltered view;
        // returning them here would mix session rows into a workspace search.
        root.copy(workspaces = workspaces, rootSessions = emptyList())
            .takeIf { it.workspaces.isNotEmpty() }
    }
}

private fun hostWorkspacesSubtitle(state: HostWorkspacesUiState): String = when {
    state.refreshing -> "Reconnecting…"
    state.failure != null && state.statusUnavailable -> "Offline · Saved list"
    state.failure != null -> "Offline"
    !state.loaded -> "Connecting…"
    else -> "Connected"
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootActionsSheet(
    root: WorkspaceRootProjection,
    onAddWorkspace: () -> Unit,
    onCreateFolder: () -> Unit,
    onStartSession: () -> Unit,
    onCopyPath: () -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = com.pocketshell.uikit.theme.PocketShellShapes.large,
        containerColor = PocketShellColors.Surface,
    ) {
        RootActionsSheetContent(
            root = root,
            onAddWorkspace = onAddWorkspace,
            onCreateFolder = onCreateFolder,
            onStartSession = onStartSession,
            onCopyPath = onCopyPath,
            onBrowse = onBrowse,
            onRemove = onRemove,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Root actions without the modal container. Keeping the action body separate
 * makes its callbacks testable without relying on Robolectric's modal window.
 */
@Composable
internal fun RootActionsSheetContent(
    root: WorkspaceRootProjection,
    onAddWorkspace: () -> Unit,
    onCreateFolder: () -> Unit,
    onStartSession: () -> Unit,
    onCopyPath: () -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketShellSpacing.lg),
    ) {
        SheetHeader(
            title = root.displayPath,
            subtitle = "Root actions",
            onClose = onDismiss,
            modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
        )
        RootActionRow(
            title = "Add workspace",
            subtitle = "Choose an existing folder under this root",
            onClick = onAddWorkspace,
            testTag = workspaceRootAddTag(root.key),
        )
        RootActionRow(
            title = "Create folder",
            subtitle = "Create and add a workspace here",
            onClick = onCreateFolder,
            testTag = HOST_WORKSPACES_CREATE_FOLDER_TAG,
        )
        RootActionRow(
            title = "Start session here",
            subtitle = "Working folder: ${root.displayPath}",
            onClick = onStartSession,
            testTag = HOST_WORKSPACES_ROOT_START_SESSION_TAG,
        )
        RootActionRow(
            title = "Browse root",
            subtitle = "Open files at ${root.displayPath}",
            onClick = onBrowse,
            testTag = HOST_WORKSPACES_ROOT_BROWSE_TAG,
        )
        if (root.registeredRootId != null) {
            RootActionRow(
                title = "Remove root",
                subtitle = "Keeps folders and sessions on the host",
                onClick = onRemove,
                testTag = HOST_WORKSPACES_ROOT_REMOVE_TAG,
            )
        }
    }
}

@Composable
private fun RootActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostToolsSheet(
    hostLabel: String,
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenProjectRoots: () -> Unit,
    onOpenConnectionDetails: () -> Unit,
    onRefresh: () -> Unit,
    onReorder: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = com.pocketshell.uikit.theme.PocketShellShapes.large,
        containerColor = PocketShellColors.Surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag(HOST_WORKSPACES_HOST_TOOLS_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
        ) {
            item {
                SheetHeader(
                    title = hostLabel.ifBlank { "Host" },
                    onClose = onDismiss,
                    modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
                )
            }
            item { HostToolRow("Browse host files", PocketShellIcons.File, onOpenFiles, SESSION_TREE_FILES_TAG) }
            item { HostToolRow("Services & tunnels", PocketShellIcons.Ports, onOpenPorts, SESSION_TREE_PORTS_TAG) }
            item { HostToolRow("Usage", PocketShellIcons.Chart, onOpenUsage, SESSION_TREE_USAGE_TAG) }
            item { HostToolRow("Project roots", PocketShellIcons.Folder, onOpenProjectRoots, HOST_WORKSPACES_PROJECT_ROOTS_TAG) }
            item { HostToolRow("Refresh workspaces", PocketShellIcons.Refresh, onRefresh, HOST_WORKSPACES_REFRESH_TAG) }
            item { HostToolRow("Connection details", PocketShellIcons.Info, onOpenConnectionDetails, HOST_WORKSPACES_CONNECTION_DETAILS_TAG) }
            item { HostToolRow("Disconnect", PocketShellIcons.Close, onDisconnect, HOST_WORKSPACES_DISCONNECT_TAG) }
        }
    }
}

@Composable
private fun HostToolRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String,
) {
    ListRow(
        title = title,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PocketShellColors.TextSecondary,
                modifier = Modifier.padding(2.dp),
            )
        },
        trailing = {
            Icon(
                imageVector = PocketShellIcons.Chevron,
                contentDescription = null,
                tint = PocketShellColors.TextMuted,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostConnectionDetailsSheet(
    state: HostWorkspacesUiState,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = com.pocketshell.uikit.theme.PocketShellShapes.large,
        containerColor = PocketShellColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg),
        ) {
            SheetHeader(
                title = "Connection details",
                subtitle = "Saved connection for this host.",
                onClose = onDismiss,
            )
            ListRow(title = "Host", subtitle = state.hostLabel)
            ListRow(title = "Address", subtitle = state.hostAddress)
            ListRow(title = "User", subtitle = state.hostUser)
            PocketShellButton(
                text = "Edit connection",
                onClick = onEdit,
                variant = ButtonVariant.Text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun parentPath(path: String): String {
    val normalized = canonicalRemotePath(path) ?: return path
    if (normalized == "/") return normalized
    return normalized.substringBeforeLast('/').ifBlank { "/" }
}

private fun isPathWithin(path: String, root: String): Boolean =
    path == root || path.startsWith("$root/")
