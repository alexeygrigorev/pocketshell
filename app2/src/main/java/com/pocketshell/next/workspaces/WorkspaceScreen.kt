package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.CreateSessionRequest
import com.pocketshell.next.tree.CreateSessionSheet
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.SessionTreeViewModel
import com.pocketshell.next.tree.STOP_SESSION_CANCEL_TAG
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_TAG
import com.pocketshell.next.tree.STOP_SESSION_ITEM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_ITEM_TAG
import com.pocketshell.next.tree.STOP_SESSION_MESSAGE_TAG
import com.pocketshell.next.tree.STOP_SESSION_TITLE
import com.pocketshell.next.tree.STOP_SESSION_TITLE_TAG
import com.pocketshell.next.tree.folderHeaderTag
import com.pocketshell.next.tree.sessionRowMenuTag
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.next.tree.stopSessionMessage
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

const val WORKSPACE_SCREEN_TAG: String = "workspace-screen"
const val WORKSPACE_LIST_TAG: String = "workspace-session-list"
const val WORKSPACE_LOADING_TAG: String = "workspace-loading"
const val WORKSPACE_EMPTY_TAG: String = "workspace-empty"
const val WORKSPACE_ERROR_TAG: String = "workspace-error"
const val WORKSPACE_CREATE_NOTICE_TAG: String = "workspace-create-notice"
const val WORKSPACE_RETRY_TAG: String = "workspace-retry"
const val WORKSPACE_BACK_TAG: String = "workspace-back"
const val WORKSPACE_NEW_SESSION_TAG: String = "workspace-new-session"
const val WORKSPACE_NEW_SESSION_LABEL: String = "New session"

/** Route-level binding for a canonical workspace path restored from NavState. */
@Composable
fun WorkspaceRoute(
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
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
        onCreateSession = viewModel::openCreateSheet,
        onSubmitCreate = viewModel::createSession,
        onDismissCreate = viewModel::dismissCreateSheet,
        onRequestStop = viewModel::requestStopSession,
        onConfirmStop = viewModel::confirmStopSession,
        onCancelStop = viewModel::cancelStopSession,
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
    modifier: Modifier = Modifier,
    onCreateSession: () -> Unit = {},
    onSubmitCreate: (CreateSessionRequest) -> Unit = {},
    onDismissCreate: () -> Unit = {},
    onRequestStop: (String) -> Unit = {},
    onConfirmStop: () -> Unit = {},
    onCancelStop: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(WORKSPACE_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = workspaceLabel(state.workspacePath),
            subtitle = workspaceSubtitle(state),
            titleMaxLines = Int.MAX_VALUE,
            subtitleMaxLines = Int.MAX_VALUE,
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(WORKSPACE_BACK_TAG),
                )
            },
            trailing = {
                Kebab(
                    items = listOf(
                        KebabItem(label = "Files", onClick = onOpenFiles),
                        KebabItem(label = "Ports", onClick = onOpenPorts),
                        KebabItem(label = "Usage", onClick = onOpenUsage),
                    ),
                    contentDescription = "Workspace actions",
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

                state.isEmptyAndHealthy -> EmptyState(
                    title = "No sessions",
                    description = "This workspace is empty. Start a session here.",
                    action = {
                        PocketShellButton(
                            text = WORKSPACE_NEW_SESSION_LABEL,
                            onClick = onCreateSession,
                            modifier = Modifier.testTag(WORKSPACE_NEW_SESSION_TAG),
                        )
                    },
                    modifier = Modifier.testTag(WORKSPACE_EMPTY_TAG),
                )

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
                            onClick = { onOpenSession(session.name) },
                            onRequestStop = { onRequestStop(session.name) },
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
                }
            }
        }
    }

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = state.suggestedFolder,
            onSubmit = onSubmitCreate,
            onCancel = onDismissCreate,
        )
    }

    state.pendingStop?.let { name ->
        ConfirmDialog(
            title = STOP_SESSION_TITLE,
            message = stopSessionMessage(name),
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

@Composable
private fun WorkspaceSessionRow(
    session: SessionRow,
    onClick: () -> Unit,
    onRequestStop: () -> Unit,
) {
    ListRow(
        title = session.name,
        trailing = {
            Kebab(
                items = listOf(
                    KebabItem(
                        label = STOP_SESSION_ITEM_LABEL,
                        onClick = onRequestStop,
                        testTag = STOP_SESSION_ITEM_TAG,
                    ),
                ),
                contentDescription = "Actions for ${session.name}",
                triggerTestTag = sessionRowMenuTag(session.name),
            )
        },
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
    else -> listOf(
        state.workspacePath.orEmpty(),
        "${state.workspaceSessions.size} " +
            if (state.workspaceSessions.size == 1) "session" else "sessions",
    ).filter(String::isNotBlank).joinToString(" · ")
}
