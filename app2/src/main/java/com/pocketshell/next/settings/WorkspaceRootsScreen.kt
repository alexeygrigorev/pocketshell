package com.pocketshell.next.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags. */
const val WORKSPACE_ROOTS_LIST_TAG: String = "workspace-roots-list"
const val WORKSPACE_ROOTS_BACK_TAG: String = "workspace-roots-back"
const val WORKSPACE_ROOTS_LABEL_FIELD_TAG: String = "workspace-roots-label-field"
const val WORKSPACE_ROOTS_PATH_FIELD_TAG: String = "workspace-roots-path-field"
const val WORKSPACE_ROOTS_ADD_TAG: String = "workspace-roots-add"
const val WORKSPACE_ROOTS_EMPTY_TAG: String = "workspace-roots-empty"

fun workspaceRootRowTag(rootId: Long): String = "workspace-root-$rootId"

fun workspaceRootMenuTag(rootId: Long): String = "workspace-root-menu-$rootId"

fun workspaceRootDeleteTag(rootId: Long): String = "workspace-root-delete-$rootId"

/**
 * Route-level entry point: binds the Hilt-provided [WorkspaceRootsViewModel],
 * which reads [hostId] from its own [androidx.lifecycle.SavedStateHandle]
 * rather than from this parameter — [hostId] is only used to build the route,
 * the same split every other P-6 route seam uses.
 */
@Composable
fun WorkspaceRootsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkspaceRootsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    WorkspaceRootsScreen(
        state = state,
        onBack = onBack,
        onAddRoot = viewModel::addRoot,
        onDeleteRoot = viewModel::deleteRoot,
        modifier = modifier,
    )
}

/**
 * Manages one host's `project_roots` shortcuts (rewrite task P-6's "workspace
 * roots" KEEP item) — opened from [SettingsScreen]'s Workspace section.
 *
 * Two fields (label, path) rather than the old client's folder-scanning
 * `RootProjectAddSheet`: that sheet browsed the REMOTE filesystem to offer
 * candidate projects under a root and is the tree screen's concern, not
 * Settings'. This screen only owns the shortcut ROWS themselves — add one by
 * typing where it lives, remove one that is no longer wanted.
 */
@Composable
fun WorkspaceRootsScreen(
    state: WorkspaceRootsUiState,
    onBack: () -> Unit,
    onAddRoot: (label: String, path: String) -> Unit,
    onDeleteRoot: (WorkspaceRootRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = if (state.hostName.isBlank()) "Workspace roots" else "Roots · ${state.hostName}",
            leading = {
                PocketShellButton(
                    text = "‹",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(WORKSPACE_ROOTS_BACK_TAG),
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Path") },
                placeholder = { Text("~/git/pocketshell") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_PATH_FIELD_TAG),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_LABEL_FIELD_TAG),
            )
            PocketShellButton(
                text = "Add root",
                onClick = {
                    onAddRoot(label, path)
                    label = ""
                    path = ""
                },
                variant = ButtonVariant.Primary,
                enabled = path.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WORKSPACE_ROOTS_ADD_TAG),
            )
        }

        if (state.roots.isEmpty()) {
            EmptyState(
                title = "No roots yet",
                description = "Add the folders you work in on this host.",
                modifier = Modifier.testTag(WORKSPACE_ROOTS_EMPTY_TAG),
            )
        } else {
            SectionHeader(label = "Saved", count = state.roots.size)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(WORKSPACE_ROOTS_LIST_TAG),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                items(items = state.roots, key = { it.id }) { root ->
                    ListRow(
                        title = root.label,
                        subtitle = root.path,
                        trailing = {
                            Kebab(
                                items = listOf(
                                    KebabItem(
                                        label = "Delete",
                                        onClick = { onDeleteRoot(root) },
                                        testTag = workspaceRootDeleteTag(root.id),
                                    ),
                                ),
                                triggerTestTag = workspaceRootMenuTag(root.id),
                            )
                        },
                        modifier = Modifier.testTag(workspaceRootRowTag(root.id)),
                    )
                }
            }
        }
    }
}
