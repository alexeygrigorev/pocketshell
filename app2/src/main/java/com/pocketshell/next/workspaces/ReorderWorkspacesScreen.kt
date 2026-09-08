package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

const val REORDER_WORKSPACES_SCREEN_TAG: String = "reorder-workspaces-screen"
const val REORDER_WORKSPACES_LIST_TAG: String = "reorder-workspaces-list"
const val REORDER_WORKSPACES_BACK_TAG: String = "reorder-workspaces-back"
const val REORDER_WORKSPACES_ROOT_PREFIX: String = "reorder-root-"
const val REORDER_WORKSPACES_ITEM_PREFIX: String = "reorder-workspace-"

fun reorderRootTag(rootId: Long): String = "$REORDER_WORKSPACES_ROOT_PREFIX$rootId"

fun reorderWorkspaceTag(path: String): String = "$REORDER_WORKSPACES_ITEM_PREFIX$path"

/** Route-level binding for the persistent ordering page. */
@Composable
fun ReorderWorkspacesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostWorkspacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    ReorderWorkspacesScreen(
        state = state,
        onBack = onBack,
        onMoveRoot = viewModel::moveRoot,
        onMoveWorkspace = viewModel::moveWorkspace,
        modifier = modifier,
    )
}

/**
 * A quiet, explicit ordering page. Both the saved root sections and the
 * durable workspace rows use 48dp move controls; a refresh never changes the
 * order that the user chose.
 */
@Composable
fun ReorderWorkspacesScreen(
    state: HostWorkspacesUiState,
    onBack: () -> Unit,
    onMoveRoot: (Long, Int) -> Unit,
    onMoveWorkspace: (String, String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registeredRoots = state.roots.filter { it.registeredRootId != null }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(REORDER_WORKSPACES_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Reorder workspaces",
            subtitle = state.hostLabel,
            onBack = onBack,
            backTestTag = REORDER_WORKSPACES_BACK_TAG,
        )

        when {
            !state.loaded && state.failure == null -> EmptyState(
                title = "Loading workspaces…",
                description = "Reading the saved roots and folders from the host.",
            )

            !state.loaded && state.failure != null -> EmptyState(
                title = "Status unavailable",
                description = state.failure,
            )

            registeredRoots.isEmpty() -> EmptyState(
                title = "No saved roots",
                description = "Add a project root before arranging workspaces.",
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(REORDER_WORKSPACES_LIST_TAG),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                item {
                    Text(
                        text = "Arrange roots and folders once. Refreshes keep this order.",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(
                            horizontal = PocketShellSpacing.lg,
                            vertical = PocketShellSpacing.sm,
                        ),
                    )
                }
                items(
                    items = registeredRoots,
                    key = { root -> "reorder-root:${root.registeredRootId}" },
                ) { root ->
                    val rootId = root.registeredRootId ?: return@items
                    val rootIndex = registeredRoots.indexOfFirst { it.registeredRootId == rootId }
                    SectionHeader(
                        label = root.displayPath,
                        count = root.workspaces.size,
                        modifier = Modifier.testTag(reorderRootTag(rootId)),
                        trailing = {
                            MoveControls(
                                label = "root ${root.displayPath}",
                                canMoveUp = rootIndex > 0,
                                canMoveDown = rootIndex < registeredRoots.lastIndex,
                                onMoveUp = { onMoveRoot(rootId, -1) },
                                onMoveDown = { onMoveRoot(rootId, 1) },
                            )
                        },
                    )
                    if (root.workspaces.isEmpty()) {
                        ListRow(
                            title = "No workspaces yet",
                            subtitle = "Folders added inside this root appear here.",
                        )
                    } else {
                        root.workspaces.forEachIndexed { index, workspace ->
                            ListRow(
                                title = workspace.label,
                                subtitle = workspaceSessionSummary(workspace.sessions),
                                titleStyle = PocketShellType.title,
                                subtitleStyle = PocketShellType.metadata,
                                titleWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.testTag(reorderWorkspaceTag(workspace.path)),
                                trailing = {
                                    MoveControls(
                                        label = workspace.label,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < root.workspaces.lastIndex,
                                        onMoveUp = {
                                            onMoveWorkspace(root.path.orEmpty(), workspace.path, -1)
                                        },
                                        onMoveDown = {
                                            onMoveWorkspace(root.path.orEmpty(), workspace.path, 1)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveControls(
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
        PocketShellButton(
            text = "↑",
            onClick = onMoveUp,
            enabled = canMoveUp,
            variant = ButtonVariant.Text,
            modifier = Modifier.testTag("move-up-$label"),
        )
        PocketShellButton(
            text = "↓",
            onClick = onMoveDown,
            enabled = canMoveDown,
            variant = ButtonVariant.Text,
            modifier = Modifier.testTag("move-down-$label"),
        )
    }
}
