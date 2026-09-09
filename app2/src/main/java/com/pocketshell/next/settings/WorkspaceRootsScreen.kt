package com.pocketshell.next.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.next.workspaces.canonicalRemotePath
import com.pocketshell.next.workspaces.displayRemotePath
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.pocketshell.uikit.icons.PocketShellIcons

/** Stable test tags. */
const val WORKSPACE_ROOTS_LIST_TAG: String = "workspace-roots-list"
const val WORKSPACE_ROOTS_BACK_TAG: String = "workspace-roots-back"
const val WORKSPACE_ROOTS_LABEL_FIELD_TAG: String = "workspace-roots-label-field"
const val WORKSPACE_ROOTS_PATH_FIELD_TAG: String = "workspace-roots-path-field"
const val WORKSPACE_ROOTS_ADD_TAG: String = "workspace-roots-add"
const val WORKSPACE_ROOTS_ADD_PROJECT_ROOT_TAG: String = "workspace-roots-add-project-root"
const val WORKSPACE_ROOTS_CREATE_TAG: String = "workspace-roots-create"
const val WORKSPACE_ROOTS_EMPTY_TAG: String = "workspace-roots-empty"
const val WORKSPACE_ROOTS_ERROR_TAG: String = "workspace-roots-error"
const val WORKSPACE_ROOTS_DESCRIPTION_TAG: String = "workspace-roots-description"
const val WORKSPACE_ROOTS_BROWSE_TAG: String = "workspace-roots-browse"
const val WORKSPACE_ROOTS_FOLDER_BROWSER_TAG: String = "workspace-roots-folder-browser"
const val WORKSPACE_ROOTS_FOLDER_BROWSER_USE_TAG: String = "workspace-roots-folder-browser-use"
const val WORKSPACE_ROOTS_ACTIONS_TAG: String = "workspace-root-actions"
const val WORKSPACE_ROOTS_REMOVE_CONFIRM_TAG: String = "workspace-root-remove-confirm"

fun workspaceRootRowTag(rootId: Long): String = "workspace-root-$rootId"

fun workspaceRootMenuTag(rootId: Long): String = "workspace-root-menu-$rootId"

fun workspaceRootDeleteTag(rootId: Long): String = "workspace-root-delete-$rootId"

enum class WorkspaceRootMenuAction {
    ADD_WORKSPACE,
    CREATE_FOLDER,
    START_SESSION,
    BROWSE_ROOT,
    REMOVE_ROOT,
}

/**
 * Route-level entry point for the host-scoped project-root list. The ViewModel
 * reads the host id from its own SavedStateHandle, so the route seam remains
 * safe across navigation restoration.
 */
@Composable
fun WorkspaceRootsRoute(
    onBack: () -> Unit,
    onOpenAddRoot: () -> Unit = {},
    onRootAction: (WorkspaceRootRow, WorkspaceRootMenuAction) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: WorkspaceRootsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    WorkspaceRootsScreen(
        state = state,
        onBack = onBack,
        onDeleteRoot = viewModel::deleteRoot,
        onOpenAddRoot = onOpenAddRoot,
        onRootAction = onRootAction,
        modifier = modifier,
    )
}

/**
 * Route-level entry point for the focused add-root page. It deliberately gets
 * a separate ViewModel instance from [WorkspaceRootsRoute], while both
 * instances read the same host-scoped Room rows.
 */
@Composable
fun AddWorkspaceRootRoute(
    onBack: () -> Unit,
    onAdded: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WorkspaceRootsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.successNonce) {
        if (state.successNonce > 0) onAdded()
    }
    AddWorkspaceRootScreen(
        state = state,
        onBack = onBack,
        onAddRoot = viewModel::addRoot,
        onCreateRoot = viewModel::createRoot,
        onBrowseRoot = viewModel::browseRoot,
        modifier = modifier,
    )
}

/**
 * Lists one host's saved project roots. The add flow is a separate page; this
 * surface owns the flat rows and its one clear primary action.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkspaceRootsScreen(
    state: WorkspaceRootsUiState,
    onBack: () -> Unit,
    onDeleteRoot: (WorkspaceRootRow) -> Unit,
    onOpenAddRoot: () -> Unit = {},
    onRootAction: (WorkspaceRootRow, WorkspaceRootMenuAction) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var activeRoot by remember { mutableStateOf<WorkspaceRootRow?>(null) }
    var pendingRootRemoval by remember { mutableStateOf<WorkspaceRootRow?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Project roots",
            subtitle = state.hostName.takeIf { it.isNotBlank() },
            onBack = onBack,
            backTestTag = WORKSPACE_ROOTS_BACK_TAG,
        )

        if (state.roots.isEmpty()) {
            EmptyState(
                title = "No project roots yet",
                description = "Add a folder where workspaces live.",
                modifier = Modifier
                    .weight(1f)
                    .testTag(WORKSPACE_ROOTS_EMPTY_TAG),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag(WORKSPACE_ROOTS_LIST_TAG),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
            ) {
                items(items = state.roots, key = { it.id }) { root ->
                    val path = displayRemotePath(root.path) ?: root.path
                    val count = "${root.workspaceCount} " +
                        if (root.workspaceCount == 1) "workspace" else "workspaces"
                    ListRow(
                        title = root.label.ifBlank { path },
                        subtitle = path,
                        subtitleStyle = PocketShellType.metadata,
                        trailing = {
                            Text(
                                text = count,
                                color = PocketShellColors.TextSecondary,
                                style = PocketShellType.metadata,
                            )
                        },
                        modifier = Modifier.testTag(workspaceRootRowTag(root.id)),
                        onClick = { activeRoot = root },
                    )
                }
                item {
                    Text(
                        text = "Roots organize folders on this host. They are not workspaces themselves.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PocketShellSpacing.md,
                                vertical = PocketShellSpacing.md,
                            )
                            .testTag(WORKSPACE_ROOTS_DESCRIPTION_TAG),
                    )
                }
            }
        }

        PocketShellButton(
            text = "Add project root",
            onClick = onOpenAddRoot,
            variant = ButtonVariant.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.md)
                .testTag(WORKSPACE_ROOTS_ADD_PROJECT_ROOT_TAG),
        )
    }

    activeRoot?.let { root ->
        ModalBottomSheet(
            onDismissRequest = { activeRoot = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = com.pocketshell.uikit.theme.PocketShellColors.Surface,
            shape = com.pocketshell.uikit.theme.PocketShellShapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = PocketShellSpacing.lg)
                    .testTag(WORKSPACE_ROOTS_ACTIONS_TAG),
            ) {
                SheetHeader(
                    title = displayRemotePath(root.path) ?: root.path,
                    onClose = { activeRoot = null },
                )
                RootActionRow(
                    title = "Add workspace",
                    subtitle = null,
                    icon = PocketShellIcons.Plus,
                    onClick = {
                        activeRoot = null
                        onRootAction(root, WorkspaceRootMenuAction.ADD_WORKSPACE)
                    },
                )
                RootActionRow(
                    title = "Create folder",
                    subtitle = null,
                    icon = PocketShellIcons.Folder,
                    onClick = {
                        activeRoot = null
                        onRootAction(root, WorkspaceRootMenuAction.CREATE_FOLDER)
                    },
                )
                RootActionRow(
                    title = "Start session here",
                    subtitle = "Working folder: ${displayRemotePath(root.path) ?: root.path}",
                    icon = PocketShellIcons.Terminal,
                    onClick = {
                        activeRoot = null
                        onRootAction(root, WorkspaceRootMenuAction.START_SESSION)
                    },
                )
                RootActionRow(
                    title = "Browse root",
                    subtitle = null,
                    icon = PocketShellIcons.Folder,
                    onClick = {
                        activeRoot = null
                        onRootAction(root, WorkspaceRootMenuAction.BROWSE_ROOT)
                    },
                )
                RootActionRow(
                    title = "Remove root",
                    subtitle = "Keeps folders and sessions",
                    icon = PocketShellIcons.Close,
                    onClick = {
                        activeRoot = null
                        pendingRootRemoval = root
                    },
                )
            }
        }
    }

    pendingRootRemoval?.let { root ->
        ConfirmDialog(
            title = "Remove root?",
            message = "This removes the saved shortcut. Folders and sessions stay on the host.",
            confirmLabel = "Remove root",
            destructive = true,
            onConfirm = {
                pendingRootRemoval = null
                onDeleteRoot(root)
            },
            onDismiss = { pendingRootRemoval = null },
            confirmTestTag = WORKSPACE_ROOTS_REMOVE_CONFIRM_TAG,
        )
    }
}

@Composable
private fun RootActionRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = com.pocketshell.uikit.theme.PocketShellColors.TextSecondary,
            )
        },
        onClick = onClick,
    )
}

/**
 * Focused root-registration form. Field labels remain visible while editing;
 * remote existence checks and the explicit missing-folder creation action stay
 * delegated to [WorkspaceRootsViewModel].
 */
@Composable
fun AddWorkspaceRootScreen(
    state: WorkspaceRootsUiState,
    onBack: () -> Unit,
    onAddRoot: (label: String, path: String) -> Unit,
    onCreateRoot: (label: String, path: String) -> Unit = { _, _ -> },
    onBrowseRoot: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var browserVisible by rememberSaveable { mutableStateOf(false) }
    val canonicalPath = canonicalRemotePath(path.trim().trimEnd('/'))

    LaunchedEffect(state.successNonce) {
        if (state.successNonce > 0) {
            label = ""
            path = ""
        }
    }

    if (browserVisible) {
        RootFolderBrowserPage(
            state = state,
            onBrowse = onBrowseRoot,
            onUseFolder = { selectedPath ->
                path = selectedPath
                browserVisible = false
            },
            onDismiss = { browserVisible = false },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Add project root",
            onBack = onBack,
            backTestTag = WORKSPACE_ROOTS_BACK_TAG,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Folder path") },
                placeholder = { Text("~/projects") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_PATH_FIELD_TAG),
            )
            ListRow(
                title = "Browse folders",
                subtitle = "Choose a remote folder",
                leading = {
                    Icon(
                        imageVector = PocketShellIcons.Folder,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                onClick = {
                    browserVisible = true
                    onBrowseRoot(path.ifBlank { "~" })
                },
                modifier = Modifier.testTag(WORKSPACE_ROOTS_BROWSE_TAG),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Display label (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_LABEL_FIELD_TAG),
            )
            Text(
                text = "PocketShell will look for workspaces in this folder. Existing folders and sessions are not moved.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_DESCRIPTION_TAG),
            )
            state.failure?.let { failure ->
                Banner(
                    text = failure,
                    role = BannerRole.Error,
                    modifier = Modifier.testTag(WORKSPACE_ROOTS_ERROR_TAG),
                )
            }
            if (state.canCreate && state.createPath == canonicalPath) {
                PocketShellButton(
                    text = "Create folder on host",
                    onClick = { onCreateRoot(label, path) },
                    variant = ButtonVariant.Secondary,
                    enabled = !state.adding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WORKSPACE_ROOTS_CREATE_TAG),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        ) {
            PocketShellButton(
                text = if (state.adding) "Checking…" else "Add root",
                onClick = { onAddRoot(label, path) },
                variant = ButtonVariant.Primary,
                enabled = path.isNotBlank() && !state.adding,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_ADD_TAG),
            )
        }
    }
}

@Composable
private fun RootFolderBrowserPage(
    state: WorkspaceRootsUiState,
    onBrowse: (String) -> Unit,
    onUseFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(state.browsePath) { mutableStateOf("") }
    val visibleFolders = state.browseFolders.filter { folder ->
        query.isBlank() || folder.name.contains(query, ignoreCase = true) ||
            folder.path.contains(query, ignoreCase = true)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(WORKSPACE_ROOTS_FOLDER_BROWSER_TAG),
    ) {
        ScreenHeader(
            title = "Choose folder",
            subtitle = state.browsePath,
            onBack = onDismiss,
            backTestTag = WORKSPACE_ROOTS_BACK_TAG,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find a folder") },
            leadingIcon = { Icon(PocketShellIcons.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
        ) {
            if (state.browseLoading) {
                item {
                    Text(
                        text = "Reading folders…",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
                    )
                }
            }
            state.browseFailure?.let { message ->
                item {
                    Banner(
                        text = message,
                        role = BannerRole.Error,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
                    )
                }
            }
            val parent = state.browsePath
                .takeIf { it != "/" }
                ?.let(::parentRemotePath)
            if (parent != null) {
                item {
                    ListRow(
                        title = "Up",
                        subtitle = parent,
                        leading = {
                            Icon(PocketShellIcons.Up, contentDescription = null, tint = PocketShellColors.TextSecondary)
                        },
                        onClick = { onBrowse(parent) },
                    )
                }
            }
            if (!state.browseLoading && visibleFolders.isEmpty() && state.browseFailure == null) {
                item {
                    Text(
                        text = if (query.isBlank()) "No folders" else "No matching folders",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
                    )
                }
            }
            items(items = visibleFolders, key = { it.path }) { folder ->
                ListRow(
                    title = folder.name,
                    subtitle = folder.path,
                    leading = {
                        Icon(PocketShellIcons.Folder, contentDescription = null, tint = PocketShellColors.TextSecondary)
                    },
                    onClick = { onBrowse(folder.path) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        ) {
            PocketShellButton(
                text = "Use this folder",
                onClick = { onUseFolder(state.browsePath) },
                variant = ButtonVariant.Primary,
                enabled = !state.browseLoading && state.browseFailure == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_FOLDER_BROWSER_USE_TAG),
            )
        }
    }
}

private fun parentRemotePath(path: String): String {
    val normalized = path.trimEnd('/').ifBlank { "/" }
    if (normalized == "/") return "/"
    return normalized.substringBeforeLast('/').ifBlank { "/" }
}
