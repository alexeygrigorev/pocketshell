package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderContextActionSheet(
    folderLabel: String,
    folderPath: String,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onImport: () -> Unit,
    onCloneGitProject: () -> Unit,
    onEmptyProject: () -> Unit,
    onEnv: (() -> Unit)? = null,
    onGitHistory: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(FOLDER_CONTEXT_SHEET_TAG),
    ) {
        FolderContextActionContent(
            folderLabel = folderLabel,
            folderPath = folderPath,
            onNewSession = onNewSession,
            onImport = onImport,
            onCloneGitProject = onCloneGitProject,
            onEmptyProject = onEmptyProject,
            onEnv = onEnv,
            onGitHistory = onGitHistory,
        )
    }
}

@Composable
internal fun FolderContextActionContent(
    folderLabel: String,
    folderPath: String,
    onNewSession: () -> Unit,
    onImport: () -> Unit,
    onCloneGitProject: () -> Unit,
    onEmptyProject: () -> Unit,
    onEnv: (() -> Unit)? = null,
    onGitHistory: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        Text(
            text = folderLabel,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = folderPath,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyMono,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs))
        FolderContextRow("+ New session here", FOLDER_CONTEXT_NEW_SESSION_TAG, onNewSession)
        // Read-only Git commit-history / timeline view (#646). Suppressed for
        // roots, which aren't themselves projects (null onGitHistory).
        if (onGitHistory != null) {
            FolderContextRow(
                label = "Git history",
                description = "View recent commits for this project",
                testTag = FOLDER_CONTEXT_GIT_HISTORY_TAG,
                onClick = onGitHistory,
            )
        }
        // Env files folds into the per-folder overflow sheet (#455); the
        // former inline `E` button is gone. Suppressed for roots (no .env).
        if (onEnv != null) {
            FolderContextRow("Env files", FOLDER_CONTEXT_ENV_TAG, onEnv)
        }
        // The three "add a project to this folder" actions, grouped under a
        // section header so it reads as one decision: which way to add a
        // project — reuse an existing folder, clone a repo, or start empty
        // (#517).
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs))
        SectionHeader(label = "Add a project")
        FolderContextRow(
            label = "Import into this folder",
            description = "Use an existing folder already on the host",
            testTag = FOLDER_CONTEXT_IMPORT_TAG,
            onClick = onImport,
        )
        FolderContextRow(
            label = "Clone git project",
            description = "Clone a git repository into this folder",
            testTag = FOLDER_CONTEXT_CLONE_TAG,
            onClick = onCloneGitProject,
        )
        FolderContextRow(
            label = "New empty project",
            description = "Create a new empty folder to start from scratch",
            testTag = FOLDER_CONTEXT_EMPTY_PROJECT_TAG,
            onClick = onEmptyProject,
        )
    }
}

@Composable
private fun FolderContextRow(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    description: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .testTag(testTag),
    ) {
        ListRow(
            title = label,
            subtitle = description,
            onClick = onClick,
            trailing = {
                Text(
                    text = "›",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )
    }
}

@Composable
fun EmptyProjectDialog(
    folderLabel: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        title = {
            Text(
                text = "Empty project",
                color = PocketShellColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "in $folderLabel",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(EMPTY_PROJECT_NAME_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Accent,
                    contentColor = PocketShellColors.OnAccent,
                ),
                modifier = Modifier.testTag(EMPTY_PROJECT_CREATE_TAG),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(EMPTY_PROJECT_CANCEL_TAG)) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        modifier = Modifier.testTag(EMPTY_PROJECT_DIALOG_TAG),
    )
}

const val FOLDER_CONTEXT_SHEET_TAG: String = "folder-context:sheet"
const val FOLDER_CONTEXT_NEW_SESSION_TAG: String = "folder-context:new-session"
const val FOLDER_CONTEXT_ENV_TAG: String = "folder-context:env"
const val FOLDER_CONTEXT_GIT_HISTORY_TAG: String = "folder-context:git-history"
const val FOLDER_CONTEXT_IMPORT_TAG: String = "folder-context:import"
const val FOLDER_CONTEXT_CLONE_TAG: String = "folder-context:clone"
const val FOLDER_CONTEXT_EMPTY_PROJECT_TAG: String = "folder-context:empty-project"
const val EMPTY_PROJECT_DIALOG_TAG: String = "folder-context:empty-project:dialog"
const val EMPTY_PROJECT_NAME_TAG: String = "folder-context:empty-project:name"
const val EMPTY_PROJECT_CREATE_TAG: String = "folder-context:empty-project:create"
const val EMPTY_PROJECT_CANCEL_TAG: String = "folder-context:empty-project:cancel"
