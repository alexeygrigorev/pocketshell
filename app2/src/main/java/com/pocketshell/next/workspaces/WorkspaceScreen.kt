package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.CreateSessionRequest
import com.pocketshell.next.tree.CreateSessionSheet
import com.pocketshell.next.tree.SESSION_TREE_FILES_TAG
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SESSION_TREE_USAGE_TAG
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.SessionTreeViewModel
import com.pocketshell.next.tree.STOP_SESSION_CANCEL_TAG
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_TAG
import com.pocketshell.next.tree.STOP_SESSION_MESSAGE_TAG
import com.pocketshell.next.tree.STOP_SESSION_TITLE
import com.pocketshell.next.tree.STOP_SESSION_TITLE_TAG
import com.pocketshell.next.tree.folderHeaderTag
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.next.tree.stopSessionMessage
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

const val WORKSPACE_SCREEN_TAG: String = "workspace-screen"
const val WORKSPACE_LIST_TAG: String = "workspace-session-list"
const val WORKSPACE_LOADING_TAG: String = "workspace-loading"
const val WORKSPACE_EMPTY_TAG: String = "workspace-empty"
const val WORKSPACE_ERROR_TAG: String = "workspace-error"
const val WORKSPACE_CREATE_NOTICE_TAG: String = "workspace-create-notice"
const val WORKSPACE_RETRY_TAG: String = "workspace-retry"
const val WORKSPACE_BACK_TAG: String = "workspace-back"
const val WORKSPACE_ACTIONS_TAG: String = "workspace-actions"
const val WORKSPACE_NEW_SESSION_TAG: String = "workspace-new-session"
const val WORKSPACE_NEW_SESSION_LABEL: String = "New session"
const val WORKSPACE_COPY_PATH_TAG: String = "workspace-copy-path"
const val WORKSPACE_REORDER_TAG: String = "workspace-reorder"
const val WORKSPACE_CREATE_FOLDER_TAG: String = "workspace-create-folder"
const val WORKSPACE_CREATE_FOLDER_NAME_TAG: String = "workspace-create-folder-name"
const val WORKSPACE_CREATE_FOLDER_CONFIRM_TAG: String = "workspace-create-folder-confirm"
const val WORKSPACE_REMOVE_FROM_LIST_TAG: String = "workspace-remove-from-list"
const val WORKSPACE_REMOVE_CONFIRM_TAG: String = "workspace-remove-confirm"

/** Route-level binding for a canonical workspace path restored from NavState. */
@Composable
fun WorkspaceRoute(
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenReorder: () -> Unit = {},
    startSessionOnEntry: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LaunchedEffect(startSessionOnEntry) {
        if (startSessionOnEntry) viewModel.openCreateSheet()
    }
    LaunchedEffect(state.create.openRequest) {
        val name = state.create.openRequest ?: return@LaunchedEffect
        viewModel.consumeOpenRequest()
        onOpenSession(name)
    }
    WorkspaceScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenSession = onOpenSession,
        onOpenFiles = onOpenFiles,
        onOpenPorts = onOpenPorts,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
        onOpenReorder = onOpenReorder,
        onCreateSession = viewModel::openCreateSheet,
        onSubmitCreate = viewModel::createSession,
        onRefreshEngines = viewModel::refreshEngines,
        onDismissCreate = viewModel::dismissCreateSheet,
        onRequestStop = viewModel::requestStopSession,
        onConfirmStop = viewModel::confirmStopSession,
        onCancelStop = viewModel::cancelStopSession,
        onOpenCreateFolder = viewModel::openCreateFolder,
        onCreateFolderNameChange = viewModel::setCreateFolderName,
        onConfirmCreateFolder = viewModel::createFolder,
        onDismissCreateFolder = viewModel::dismissCreateFolder,
        onConfirmRemoveFromList = { viewModel.removeWorkspaceFromList(onBack) },
        modifier = modifier,
    )
}

/** Session list for one workspace. The path is handled by the route, not repeated per row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    state: SessionTreeUiState,
    onRefresh: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenPorts: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenReorder: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCreateSession: () -> Unit = {},
    onSubmitCreate: (CreateSessionRequest) -> Unit = {},
    onRefreshEngines: () -> Unit = {},
    onDismissCreate: () -> Unit = {},
    onRequestStop: (String) -> Unit = {},
    onConfirmStop: () -> Unit = {},
    onCancelStop: () -> Unit = {},
    onOpenCreateFolder: () -> Unit = {},
    onCreateFolderNameChange: (String) -> Unit = {},
    onConfirmCreateFolder: () -> Unit = {},
    onDismissCreateFolder: () -> Unit = {},
    onConfirmRemoveFromList: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val displayNames = remember(state.workspaceSessions) {
        sessionDisplayNames(state.workspaceSessions)
    }
    var removeDialogVisible by remember { mutableStateOf(false) }
    var workspaceActionsOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(WORKSPACE_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = workspaceLabel(state.workspacePath),
            subtitle = workspaceSubtitle(state),
            titleMaxLines = 2,
            subtitleMaxLines = 2,
            onBack = onBack,
            backTestTag = WORKSPACE_BACK_TAG,
            trailing = {
                KebabTrigger(
                    onClick = { workspaceActionsOpen = true },
                    contentDescription = "Workspace actions",
                    triggerTestTag = WORKSPACE_ACTIONS_TAG,
                )
            },
        )

        state.create.notice?.let { notice ->
            Banner(
                text = notice,
                role = BannerRole.Info,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(WORKSPACE_CREATE_NOTICE_TAG),
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
                        modifier = Modifier.testTag(WORKSPACE_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(WORKSPACE_ERROR_TAG),
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                !state.loaded && state.failure == null -> EmptyState(
                    title = "Loading sessions…",
                    description = "Reading this workspace from the host.",
                    modifier = Modifier.testTag(WORKSPACE_LOADING_TAG),
                )

                !state.loaded && state.failure != null -> EmptyState(
                    title = "Status unavailable",
                    description = state.failure,
                    action = {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRefresh,
                            modifier = Modifier.testTag(WORKSPACE_RETRY_TAG),
                        )
                    },
                    modifier = Modifier.testTag(WORKSPACE_EMPTY_TAG),
                )

                state.isEmptyAndHealthy -> Column(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        title = "No sessions",
                        description = "This workspace is empty. Start a session here.",
                        action = {
                            PocketShellButton(
                                text = WORKSPACE_NEW_SESSION_LABEL,
                                onClick = onCreateSession,
                                modifier = Modifier.testTag(WORKSPACE_NEW_SESSION_TAG),
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(WORKSPACE_EMPTY_TAG),
                    )
                    WorkspaceLinks(
                        hostLabel = state.hostLabel,
                        onOpenFiles = onOpenFiles,
                        onOpenPorts = onOpenPorts,
                        showServices = false,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(WORKSPACE_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    items(
                        items = state.workspaceSessions,
                        key = { session -> "workspace-session:${session.name}" },
                    ) { session ->
                        WorkspaceSessionRow(
                            session = session,
                            displayName = displayNames[session.name] ?: session.name,
                            onClick = { onOpenSession(session.name) },
                        )
                    }
                    item(key = "workspace-new-session") {
                        PocketShellButton(
                            text = WORKSPACE_NEW_SESSION_LABEL,
                            onClick = onCreateSession,
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PocketShellSpacing.lg,
                                    vertical = PocketShellSpacing.md,
                                )
                                .testTag(WORKSPACE_NEW_SESSION_TAG),
                        )
                    }
                    item(key = "workspace-links") {
                        WorkspaceLinks(
                            hostLabel = state.hostLabel,
                            onOpenFiles = onOpenFiles,
                            onOpenPorts = onOpenPorts,
                        )
                    }
                }
            }
        }
    }

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = state.suggestedFolder,
            existingSessionNames = state.workspaceSessions.map { it.name },
            onSubmit = onSubmitCreate,
            onCancel = onDismissCreate,
            onRefreshEngines = onRefreshEngines,
        )
    }

    if (workspaceActionsOpen) {
        WorkspaceActionsSheet(
            onNewSession = {
                workspaceActionsOpen = false
                onCreateSession()
            },
            onBrowseFiles = {
                workspaceActionsOpen = false
                onOpenFiles()
            },
            onOpenPorts = {
                workspaceActionsOpen = false
                onOpenPorts()
            },
            onOpenUsage = {
                workspaceActionsOpen = false
                onOpenUsage()
            },
            onCopyPath = {
                workspaceActionsOpen = false
                state.workspacePath?.let { path -> clipboard.setText(AnnotatedString(path)) }
            },
            onReorder = {
                workspaceActionsOpen = false
                onOpenReorder()
            },
            onCreateFolder = {
                workspaceActionsOpen = false
                onOpenCreateFolder()
            },
            onRemove = {
                workspaceActionsOpen = false
                removeDialogVisible = true
            },
            onDismiss = { workspaceActionsOpen = false },
        )
    }

    if (state.workspaceAction.createFolderVisible) {
        com.pocketshell.uikit.components.FormDialog(
            title = "Create folder",
            confirmLabel = if (state.workspaceAction.creatingFolder) "Creating…" else "Create folder",
            onConfirm = onConfirmCreateFolder,
            onDismiss = onDismissCreateFolder,
            confirmEnabled = state.workspaceAction.createFolderName.isNotBlank() &&
                !state.workspaceAction.creatingFolder,
            confirmTestTag = WORKSPACE_CREATE_FOLDER_CONFIRM_TAG,
        ) {
            Text(
                text = "Creates a folder inside ${state.workspacePath.orEmpty()}.",
                color = com.pocketshell.uikit.theme.PocketShellColors.TextMuted,
                style = com.pocketshell.uikit.theme.PocketShellType.metadata,
            )
            androidx.compose.material3.OutlinedTextField(
                value = state.workspaceAction.createFolderName,
                onValueChange = onCreateFolderNameChange,
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_CREATE_FOLDER_NAME_TAG),
            )
            state.workspaceAction.createFolderFailure?.let { message ->
                Text(
                    text = message,
                    color = com.pocketshell.uikit.theme.PocketShellColors.Red,
                    style = com.pocketshell.uikit.theme.PocketShellType.metadata,
                )
            }
        }
    }

    if (removeDialogVisible) {
        ConfirmDialog(
            title = "Remove from list?",
            message = "The folder and its running sessions stay on the host. You can add this workspace again later.",
            confirmLabel = "Remove from list",
            destructive = true,
            onConfirm = {
                removeDialogVisible = false
                onConfirmRemoveFromList()
            },
            onDismiss = { removeDialogVisible = false },
            confirmTestTag = WORKSPACE_REMOVE_CONFIRM_TAG,
        )
    }

    state.pendingStop?.let { name ->
        ConfirmDialog(
            title = STOP_SESSION_TITLE,
            message = stopSessionMessage(
                name = readableSessionName(name),
                workspace = state.workspacePath,
                host = "host #${state.hostId}",
            ),
            confirmLabel = STOP_SESSION_CONFIRM_LABEL,
            destructive = true,
            onConfirm = onConfirmStop,
            onDismiss = onCancelStop,
            confirmTestTag = STOP_SESSION_CONFIRM_TAG,
            dismissTestTag = STOP_SESSION_CANCEL_TAG,
            titleTestTag = STOP_SESSION_TITLE_TAG,
            messageTestTag = STOP_SESSION_MESSAGE_TAG,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceActionsSheet(
    onNewSession: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onCopyPath: () -> Unit,
    onReorder: () -> Unit,
    onCreateFolder: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.pocketshell.uikit.theme.PocketShellColors.Surface,
        shape = com.pocketshell.uikit.theme.PocketShellShapes.large,
        modifier = Modifier.testTag(WORKSPACE_ACTIONS_TAG),
    ) {
        WorkspaceActionsContent(
            onNewSession = onNewSession,
            onBrowseFiles = onBrowseFiles,
            onOpenPorts = onOpenPorts,
            onOpenUsage = onOpenUsage,
            onCopyPath = onCopyPath,
            onReorder = onReorder,
            onCreateFolder = onCreateFolder,
            onRemove = onRemove,
            onDismiss = onDismiss,
        )
    }
}

/** The workspace action body, separate from the modal container for deterministic UI tests. */
@Composable
internal fun WorkspaceActionsContent(
    onNewSession: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onCopyPath: () -> Unit,
    onReorder: () -> Unit,
    onCreateFolder: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketShellSpacing.lg),
    ) {
        SheetHeader(
            title = "Workspace actions",
            subtitle = "Manage this workspace",
            onClose = onDismiss,
        )
        ListRow(
            title = WORKSPACE_NEW_SESSION_LABEL,
            subtitle = "Start another terminal here",
            onClick = onNewSession,
            modifier = Modifier.testTag(WORKSPACE_NEW_SESSION_TAG),
        )
        ListRow(
            title = "Browse files",
            subtitle = "Open this folder in Files",
            onClick = onBrowseFiles,
        )
        ListRow(
            title = "Copy folder path",
            subtitle = "Copy the canonical remote path",
            onClick = onCopyPath,
            modifier = Modifier.testTag(WORKSPACE_COPY_PATH_TAG),
        )
        ListRow(
            title = "Reorder workspaces",
            subtitle = "Change the host list order",
            onClick = onReorder,
            modifier = Modifier.testTag(WORKSPACE_REORDER_TAG),
        )
        ListRow(
            title = "Remove from list",
            subtitle = "Keeps the folder and running sessions",
            onClick = onRemove,
            modifier = Modifier.testTag(WORKSPACE_REMOVE_FROM_LIST_TAG),
        )
    }
}

/** Workspace-scoped utilities stay visible below the session list. */
@Composable
private fun WorkspaceLinks(
    hostLabel: String,
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    showServices: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(label = "Workspace")
        ListRow(
            title = "Browse files",
            onClick = onOpenFiles,
            modifier = Modifier.testTag(SESSION_TREE_FILES_TAG),
        )
        if (showServices) {
            ListRow(
                title = "Services & tunnels",
                subtitle = hostLabel.takeIf { it.isNotBlank() }?.let { "On $it" },
                onClick = onOpenPorts,
                modifier = Modifier.testTag(SESSION_TREE_PORTS_TAG),
            )
        }
    }
}

@Composable
private fun WorkspaceSessionRow(
    session: SessionRow,
    displayName: String = session.name,
    onClick: () -> Unit,
) {
    ListRow(
        title = displayName,
        subtitle = sessionKindStatusLabel(session),
        leading = { SessionKindMark(agent = session.agent) },
        titleStyle = com.pocketshell.uikit.theme.PocketShellType.body,
        subtitleStyle = com.pocketshell.uikit.theme.PocketShellType.metadata,
        titleWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        titleMaxLines = 2,
        onClick = onClick,
        modifier = Modifier.testTag(sessionRowTag(session.name)),
    )
}

private fun workspaceLabel(path: String?): String =
    path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { path } ?: "Workspace"

private fun workspaceSubtitle(state: SessionTreeUiState): String = when {
    state.failure != null && !state.loaded -> "Status unavailable"
    state.refreshing -> "Reconnecting…"
    !state.loaded -> "Connecting…"
    state.failure != null -> "Status unavailable"
    else -> listOfNotNull(
        state.hostLabel.takeIf { it.isNotBlank() },
        displayRemotePath(state.workspacePath),
    ).joinToString(" · ").ifBlank { "Connected" }
}
