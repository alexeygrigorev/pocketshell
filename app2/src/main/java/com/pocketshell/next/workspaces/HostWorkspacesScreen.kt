package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.SESSION_TREE_FILES_TAG
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SESSION_TREE_USAGE_TAG
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FormDialog
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.WorkspaceRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

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
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenFilesAtPath: (String) -> Unit = { onOpenFiles() },
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostWorkspacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    HostWorkspacesScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenWorkspace = onOpenWorkspace,
        onStartSessionAtPath = onStartSessionAtPath,
        onOpenReorder = onOpenReorder,
        onOpenSession = onOpenSession,
        onOpenFiles = onOpenFiles,
        onOpenFilesAtPath = onOpenFilesAtPath,
        onOpenPorts = onOpenPorts,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
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

/** Stateless Quiet host root; all rows come from [HostWorkspacesUiState]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostWorkspacesScreen(
    state: HostWorkspacesUiState,
    onRefresh: () -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onStartSessionAtPath: (String) -> Unit = onOpenWorkspace,
    onOpenReorder: () -> Unit = {},
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenFilesAtPath: (String) -> Unit = { onOpenFiles() },
    onOpenPorts: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
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
) {
    val clipboard = LocalClipboardManager.current
    var activeRootActions by remember { mutableStateOf<WorkspaceRootProjection?>(null) }
    var rootPendingRemoval by remember { mutableStateOf<WorkspaceRootProjection?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HOST_WORKSPACES_TAG),
    ) {
        ScreenHeader(
            title = state.hostLabel.ifBlank { "Workspaces" },
            subtitle = hostWorkspacesSubtitle(state),
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(HOST_WORKSPACES_BACK_TAG),
                )
            },
            trailing = {
                Kebab(
                    items = listOf(
                        KebabItem(
                            label = "Files",
                            onClick = onOpenFiles,
                            testTag = SESSION_TREE_FILES_TAG,
                        ),
                        KebabItem(
                            label = "Ports",
                            onClick = onOpenPorts,
                            testTag = SESSION_TREE_PORTS_TAG,
                        ),
                        KebabItem(
                            label = "Usage",
                            onClick = onOpenUsage,
                            testTag = SESSION_TREE_USAGE_TAG,
                        ),
                        KebabItem(
                            label = "Reorder workspaces",
                            onClick = onOpenReorder,
                            testTag = HOST_WORKSPACES_REORDER_TAG,
                        ),
                    ),
                    contentDescription = "Host actions",
                    triggerTestTag = HOST_WORKSPACES_ACTIONS_TAG,
                )
            },
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Search workspaces") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.xs)
                .testTag(HOST_WORKSPACES_SEARCH_TAG),
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
                text = failure,
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

                state.isEmptyAndHealthy -> EmptyState(
                    title = "No workspaces",
                    description = "Durable workspaces on this host will appear here.",
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

    if (state.addWorkspaceVisible) {
        FormDialog(
            title = "Add workspace",
            confirmLabel = if (state.addingWorkspace) "Adding…" else "Add workspace",
            onConfirm = onConfirmAddWorkspace,
            onDismiss = onDismissAddWorkspace,
            confirmEnabled = state.addWorkspacePath.isNotBlank() && !state.addingWorkspace,
            confirmTestTag = HOST_WORKSPACES_ADD_CONFIRM_TAG,
        ) {
            Text(
                text = "Root: ${state.addWorkspaceRootPath}",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
            OutlinedTextField(
                value = state.addWorkspacePath,
                onValueChange = onAddWorkspacePathChange,
                label = { Text("Remote folder") },
                placeholder = { Text("~/git/project") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HOST_WORKSPACES_ADD_PATH_TAG),
            )
            PocketShellButton(
                text = "Browse folders",
                onClick = onOpenWorkspaceBrowser,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(HOST_WORKSPACES_ADD_BROWSE_TAG),
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

    if (state.addWorkspaceBrowserVisible) {
        WorkspaceFolderBrowserSheet(
            state = state,
            onBrowse = onBrowseWorkspaceFolder,
            onChoose = onChooseWorkspaceFolder,
            onDismiss = onDismissWorkspaceBrowser,
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

private fun androidx.compose.foundation.lazy.LazyListScope.itemContent(
    root: WorkspaceRootProjection,
    onOpenWorkspace: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenAddWorkspace: (String) -> Unit,
    onOpenRootActions: () -> Unit,
) {
    item(key = "root-header:${root.key}") {
        SectionHeader(
            label = root.label,
            count = root.sessionCount.takeIf { it > 0 },
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
        Text(
            text = root.displayPath,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
            modifier = Modifier.padding(
                start = PocketShellSpacing.lg,
                end = PocketShellSpacing.lg,
                bottom = PocketShellSpacing.xs,
            ),
        )
    }

    if (root.rootSessions.isNotEmpty()) {
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
            WorkspaceSessionRow(session = session, onClick = { onOpenSession(session.name) })
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
            WorkspaceRow(
                title = workspace.label,
                subtitle = workspaceSessionSummary(workspace.sessions),
                onClick = { onOpenWorkspace(workspace.path) },
                testTag = workspaceRowTag(workspace.path),
            )
        }
    }
}

@Composable
private fun WorkspaceSessionRow(session: SessionRow, onClick: () -> Unit) {
    ListRow(
        title = session.name,
        subtitle = sessionKindLabel(session),
        titleStyle = PocketShellType.title,
        subtitleStyle = PocketShellType.metadata,
        titleWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
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
    state.failure != null && !state.loaded -> "Status unavailable"
    state.refreshing -> "Reconnecting…"
    !state.loaded -> "Connecting…"
    else -> "${state.workspaceCount} " + plural(state.workspaceCount, "workspace") +
        " · ${state.sessionCount} " + plural(state.sessionCount, "session")
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
    ) {
        RootActionsSheetContent(
            root = root,
            onAddWorkspace = onAddWorkspace,
            onCreateFolder = onCreateFolder,
            onStartSession = onStartSession,
            onCopyPath = onCopyPath,
            onBrowse = onBrowse,
            onRemove = onRemove,
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PocketShellSpacing.lg),
    ) {
        Text(
            text = root.displayPath,
            color = PocketShellColors.Text,
            style = PocketShellType.title,
            modifier = Modifier.padding(
                horizontal = PocketShellSpacing.lg,
                vertical = PocketShellSpacing.sm,
            ),
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
            title = "Copy path",
            subtitle = "Copy ${root.displayPath}",
            onClick = onCopyPath,
            testTag = HOST_WORKSPACES_ROOT_COPY_PATH_TAG,
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
private fun WorkspaceFolderBrowserSheet(
    state: HostWorkspacesUiState,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentPath = state.addWorkspaceBrowsePath
    val rootPath = canonicalRemotePath(state.addWorkspaceRootPath)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(HOST_WORKSPACES_FOLDER_BROWSER_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = PocketShellSpacing.lg),
        ) {
            Text(
                text = "Choose folder",
                color = PocketShellColors.Text,
                style = PocketShellType.title,
                modifier = Modifier.padding(
                    horizontal = PocketShellSpacing.lg,
                    vertical = PocketShellSpacing.sm,
                ),
            )
            Text(
                text = currentPath,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
                modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
            )
            PocketShellButton(
                text = "Use this folder",
                onClick = { onChoose(currentPath) },
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.sm)
                    .testTag(HOST_WORKSPACES_FOLDER_BROWSER_USE_TAG),
            )
            state.addWorkspaceBrowseFailure?.let { message ->
                Text(
                    text = message,
                    color = PocketShellColors.Red,
                    style = PocketShellType.metadata,
                    modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
                )
            }
            if (state.addWorkspaceBrowseLoading) {
                Text(
                    text = "Reading folders…",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                    modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
                )
            } else {
                val parent = currentPath
                    .takeIf { rootPath != null && it != rootPath }
                    ?.let(::parentPath)
                    ?.takeIf { rootPath == null || isPathWithin(it, rootPath) }
                if (parent != null) {
                    ListRow(
                        title = "Up",
                        subtitle = parent,
                        onClick = { onBrowse(parent) },
                    )
                }
                if (state.addWorkspaceFolders.isEmpty()) {
                    Text(
                        text = "No subfolders",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.lg),
                    )
                } else {
                    state.addWorkspaceFolders.forEach { folder ->
                        ListRow(
                            title = folder.name,
                            subtitle = folder.path,
                            onClick = { onBrowse(folder.path) },
                        )
                    }
                }
            }
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
