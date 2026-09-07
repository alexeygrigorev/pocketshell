package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
const val HOST_WORKSPACES_ADD_TAG: String = "host-workspaces-add"
const val HOST_WORKSPACES_SEARCH_TAG: String = "host-workspaces-search"
const val HOST_WORKSPACES_ADD_PATH_TAG: String = "host-workspaces-add-path"
const val HOST_WORKSPACES_ADD_CONFIRM_TAG: String = "host-workspaces-add-confirm"
const val HOST_WORKSPACES_IN_ROOT_LABEL: String = "In this root"
const val HOST_WORKSPACES_ROOT_EMPTY_TAG: String = "host-workspaces-root-empty"

fun workspaceRowTag(path: String): String = "workspace-row-$path"

fun workspaceSessionRowTag(name: String): String = "workspace-session-row-$name"

fun workspaceRootTag(key: String): String = "workspace-root-$key"

fun workspaceRootAddTag(key: String): String = "workspace-root-add-$key"

/** Route-level binding for the real host workspace projection. */
@Composable
fun HostWorkspacesRoute(
    onOpenWorkspace: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
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
        onOpenSession = onOpenSession,
        onOpenFiles = onOpenFiles,
        onOpenPorts = onOpenPorts,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
        onOpenAddWorkspace = viewModel::openAddWorkspace,
        onSearchQueryChange = viewModel::setSearchQuery,
        onAddWorkspacePathChange = viewModel::setAddWorkspacePath,
        onConfirmAddWorkspace = viewModel::addWorkspace,
        onDismissAddWorkspace = viewModel::dismissAddWorkspace,
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
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenPorts: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenAddWorkspace: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onAddWorkspacePathChange: (String) -> Unit = {},
    onConfirmAddWorkspace: () -> Unit = {},
    onDismissAddWorkspace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                style = PocketShellType.bodyDense,
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
            state.addWorkspaceFailure?.let { message ->
                Text(
                    text = message,
                    color = PocketShellColors.Red,
                    style = PocketShellType.bodyDense,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemContent(
    root: WorkspaceRootProjection,
    onOpenWorkspace: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenAddWorkspace: (String) -> Unit,
) {
    item(key = "root-header:${root.key}") {
        SectionHeader(
            label = root.label,
            count = root.sessionCount.takeIf { it > 0 },
            trailing = {
                PocketShellButton(
                    text = "+ Add",
                    onClick = { onOpenAddWorkspace(root.path ?: root.key) },
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(workspaceRootAddTag(root.key)),
                )
            },
            modifier = Modifier.testTag(workspaceRootTag(root.key)),
        )
        Text(
            text = root.displayPath,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
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
                subtitle = workspaceSummary(workspace),
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

private fun workspaceSummary(workspace: WorkspaceProjection): String {
    val sessionLabel = when (workspace.sessions.size) {
        0 -> "No sessions"
        1 -> "1 session"
        else -> "${workspace.sessions.size} sessions"
    }
    return "${workspace.displayPath} · $sessionLabel"
}

private fun hostWorkspacesSubtitle(state: HostWorkspacesUiState): String = when {
    state.failure != null && !state.loaded -> "Status unavailable"
    state.refreshing -> "Reconnecting…"
    !state.loaded -> "Connecting…"
    else -> "${state.workspaceCount} " + plural(state.workspaceCount, "workspace") +
        " · ${state.sessionCount} " + plural(state.sessionCount, "session")
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
