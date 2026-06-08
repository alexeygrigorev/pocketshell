package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Per-host "Watched folders" config screen — issue #206.
 *
 * Reachable from:
 *
 *  - The host overflow menu on [com.pocketshell.app.hosts.HostListScreen]
 *    ("Watched folders" item). The kebab path supplies SSH connection
 *    parameters so the "Discover from remote" button works.
 *  - Settings → Per-host folders (which has no decrypted passphrase, so
 *    discovery is hidden when arriving from there).
 *
 * Chrome rides the shared #479 design language: the header is [ScreenHeader]
 * (no bespoke 60dp bar), folder rows are dense [ListRow]s with a single
 * per-row [Kebab] (Edit / Move up / Move down / Delete), and the host-detail
 * mode picker is a [SegmentedToggle] instead of a radio group.
 */
@Composable
fun WatchedFoldersScreen(
    hostId: Long,
    hostName: String,
    sshCredentials: WatchedFoldersViewModel.SshCredentials? = null,
    onBack: () -> Unit,
    hostDetailViewMode: HostDetailViewMode = HostDetailViewMode.Tree,
    onHostDetailViewModeSelected: (HostDetailViewMode) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WatchedFoldersViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId, hostName, sshCredentials) {
        viewModel.bind(hostId = hostId, hostName = hostName, sshCredentials = sshCredentials)
    }
    val state by viewModel.state.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProjectRootEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Workspace settings",
            subtitle = state.hostName.ifBlank { null },
            titleTestTag = WATCHED_FOLDERS_TITLE_TAG,
            modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
            leading = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button, onClick = onBack)
                        .testTag(WATCHED_FOLDERS_BACK_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(WATCHED_FOLDERS_LIST_TAG),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(
                        text = "Host detail",
                        color = PocketShellColors.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose the default layout when opening this host.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HostDetailModeToggle(
                        selected = hostDetailViewMode,
                        onSelected = onHostDetailViewModeSelected,
                    )
                }
            }
            item {
                SectionCard {
                    Text(
                        text = "Workspace roots for ${state.hostName.ifBlank { "this host" }}",
                        color = PocketShellColors.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Top-level roots shown on host detail, in the order below.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.roots.isEmpty()) {
                        EmptyWatchedFoldersHint()
                    } else {
                        state.roots.forEachIndexed { index, row ->
                            WatchedFolderRow(
                                row = row,
                                isFirst = index == 0,
                                isLast = index == state.roots.lastIndex,
                                onEdit = { editing = row },
                                onDelete = { viewModel.deleteFolder(row.id) },
                                onMoveUp = { viewModel.reorderFolder(index, -1) },
                                onMoveDown = { viewModel.reorderFolder(index, +1) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag(WATCHED_FOLDERS_ADD_TAG),
                        ) {
                            Text("+ Add folder", color = PocketShellColors.Accent)
                        }
                        if (state.sshCapable) {
                            TextButton(
                                onClick = { viewModel.discoverFromRemote() },
                                enabled = !state.discovering,
                                modifier = Modifier.testTag(WATCHED_FOLDERS_DISCOVER_TAG),
                            ) {
                                Text(
                                    text = if (state.discovering) "Discovering…" else "Discover from remote",
                                    color = PocketShellColors.Accent,
                                )
                            }
                        }
                    }
                    if (!state.sshCapable) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Open this host to enable the remote discovery probe.",
                            color = PocketShellColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            if (state.discoveredCandidates.isNotEmpty() || state.discoverError != null) {
                item {
                    DiscoveryPanel(
                        candidates = state.discoveredCandidates,
                        error = state.discoverError,
                        onAccept = viewModel::acceptDiscovered,
                        onDismiss = viewModel::dismissDiscovered,
                    )
                }
            }

            state.feedback?.let { feedback ->
                item {
                    FeedbackBanner(
                        message = feedback,
                        onDismiss = viewModel::clearFeedback,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        FolderEntryDialog(
            title = "Add watched folder",
            initialLabel = "",
            initialPath = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { label, path ->
                viewModel.addFolder(rawLabel = label, rawPath = path)
                showAddDialog = false
            },
        )
    }
    editing?.let { row ->
        FolderEntryDialog(
            title = "Edit watched folder",
            initialLabel = WatchedFoldersViewModel.stripOrderPrefix(row.label),
            initialPath = row.path,
            onDismiss = { editing = null },
            onConfirm = { label, path ->
                viewModel.updateFolder(id = row.id, rawLabel = label, rawPath = path)
                editing = null
            },
        )
    }
}

@Composable
private fun HostDetailModeToggle(
    selected: HostDetailViewMode,
    onSelected: (HostDetailViewMode) -> Unit,
) {
    // Order matches the radio group it replaces: Tree first, Flat second.
    val modes = listOf(HostDetailViewMode.Tree, HostDetailViewMode.Flat)
    SegmentedToggle(
        labels = listOf("Workspace tree", "Flat folder list"),
        selectedIndex = modes.indexOf(selected).coerceAtLeast(0),
        onSelected = { index -> onSelected(modes[index]) },
        segmentTag = { index ->
            when (modes[index]) {
                HostDetailViewMode.Tree -> WORKSPACE_VIEW_MODE_TREE_TAG
                HostDetailViewMode.Flat -> WORKSPACE_VIEW_MODE_FLAT_TAG
            }
        },
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun EmptyWatchedFoldersHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(WATCHED_FOLDERS_EMPTY_HINT_TAG),
    ) {
        Text(
            text = "No workspace roots yet",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Add roots such as ~/git or ~/tmp to control host-detail tree order.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

@Composable
private fun WatchedFolderRow(
    row: ProjectRootEntity,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val visibleLabel = WatchedFoldersViewModel.stripOrderPrefix(row.label)
    // One dense row, one overflow affordance: Edit / Move up / Move down /
    // Delete all live in the per-row kebab (#479 §4 decision 4). The path
    // rides the mono subtitle.
    ListRow(
        modifier = Modifier.testTag(watchedFolderRowTestTag(row.id)),
        title = visibleLabel.ifBlank { row.path },
        subtitle = row.path,
        trailing = {
            Kebab(
                triggerTestTag = watchedFolderMenuTestTag(row.id),
                items = buildList {
                    add(
                        KebabItem(
                            label = "Edit",
                            onClick = onEdit,
                            testTag = watchedFolderEditTestTag(row.id),
                        ),
                    )
                    if (!isFirst) {
                        add(
                            KebabItem(
                                label = "Move up",
                                onClick = onMoveUp,
                                testTag = watchedFolderUpTestTag(row.id),
                            ),
                        )
                    }
                    if (!isLast) {
                        add(
                            KebabItem(
                                label = "Move down",
                                onClick = onMoveDown,
                                testTag = watchedFolderDownTestTag(row.id),
                            ),
                        )
                    }
                    add(
                        KebabItem(
                            label = "Delete",
                            onClick = onDelete,
                            testTag = watchedFolderDeleteTestTag(row.id),
                        ),
                    )
                },
            )
        },
    )
}

@Composable
private fun FolderEntryDialog(
    title: String,
    initialLabel: String,
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (label: String, path: String) -> Unit,
) {
    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = PocketShellColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WATCHED_FOLDERS_DIALOG_LABEL_TAG),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text("Path (e.g. ~/git/pocketshell)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WATCHED_FOLDERS_DIALOG_PATH_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, path) },
                enabled = path.trim().isNotEmpty(),
                modifier = Modifier.testTag(WATCHED_FOLDERS_DIALOG_CONFIRM_TAG),
            ) {
                Text("Save", color = PocketShellColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        containerColor = PocketShellColors.Surface,
        titleContentColor = PocketShellColors.Text,
        textContentColor = PocketShellColors.TextSecondary,
    )
}

@Composable
private fun DiscoveryPanel(
    candidates: List<DiscoveredFolder>,
    error: String?,
    onAccept: (DiscoveredFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Discovered folders",
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(WATCHED_FOLDERS_DISCOVER_DISMISS_TAG),
            ) {
                Text("Hide", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        error?.let {
            Text(
                text = it,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                modifier = Modifier.testTag(WATCHED_FOLDERS_DISCOVER_ERROR_TAG),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (candidates.isEmpty() && error == null) {
            Text(
                text = "No matching folders.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
        candidates.forEach { candidate ->
            ListRow(
                title = candidate.label,
                subtitle = candidate.path,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                trailing = {
                    TextButton(
                        onClick = { onAccept(candidate) },
                        modifier = Modifier.testTag(watchedFolderAcceptTestTag(candidate.path)),
                    ) {
                        Text("Add", color = PocketShellColors.Accent, style = PocketShellType.bodyDense)
                    }
                },
            )
        }
    }
}

@Composable
private fun FeedbackBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(WATCHED_FOLDERS_FEEDBACK_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        }
    }
}

// Test tags for the connected E2E + unit tests.
const val WATCHED_FOLDERS_LIST_TAG: String = "watched-folders:list"
const val WATCHED_FOLDERS_BACK_TAG: String = "watched-folders:back"
const val WATCHED_FOLDERS_TITLE_TAG: String = "watched-folders:title"
const val WATCHED_FOLDERS_ADD_TAG: String = "watched-folders:add"
const val WATCHED_FOLDERS_DISCOVER_TAG: String = "watched-folders:discover"
const val WATCHED_FOLDERS_DISCOVER_DISMISS_TAG: String = "watched-folders:discover:dismiss"
const val WATCHED_FOLDERS_DISCOVER_ERROR_TAG: String = "watched-folders:discover:error"
const val WATCHED_FOLDERS_EMPTY_HINT_TAG: String = "watched-folders:empty-hint"
const val WATCHED_FOLDERS_DIALOG_LABEL_TAG: String = "watched-folders:dialog:label"
const val WATCHED_FOLDERS_DIALOG_PATH_TAG: String = "watched-folders:dialog:path"
const val WATCHED_FOLDERS_DIALOG_CONFIRM_TAG: String = "watched-folders:dialog:confirm"
const val WATCHED_FOLDERS_FEEDBACK_TAG: String = "watched-folders:feedback"
const val WORKSPACE_VIEW_MODE_TREE_TAG: String = "workspace-settings:view-mode:tree"
const val WORKSPACE_VIEW_MODE_FLAT_TAG: String = "workspace-settings:view-mode:flat"

fun watchedFolderRowTestTag(id: Long): String = "watched-folders:row:$id"
fun watchedFolderMenuTestTag(id: Long): String = "watched-folders:row:$id:menu"
fun watchedFolderUpTestTag(id: Long): String = "watched-folders:row:$id:up"
fun watchedFolderDownTestTag(id: Long): String = "watched-folders:row:$id:down"
fun watchedFolderEditTestTag(id: Long): String = "watched-folders:row:$id:edit"
fun watchedFolderDeleteTestTag(id: Long): String = "watched-folders:row:$id:delete"
fun watchedFolderAcceptTestTag(path: String): String = "watched-folders:accept:$path"
