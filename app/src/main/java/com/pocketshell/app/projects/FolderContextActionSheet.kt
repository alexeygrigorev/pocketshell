package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = folderLabel,
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = folderPath,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        FolderContextRow("+ New session here", FOLDER_CONTEXT_NEW_SESSION_TAG, onNewSession)
        // Env files folds into the per-folder overflow sheet (#455); the
        // former inline `E` button is gone. Suppressed for roots (no .env).
        if (onEnv != null) {
            FolderContextRow("Env files", FOLDER_CONTEXT_ENV_TAG, onEnv)
        }
        // The three "add a project to this folder" actions, grouped under a
        // section header so it reads as one decision: which way to add a
        // project — reuse an existing folder, clone a repo, or start empty
        // (#517).
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Add a project",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        Text(text = "›", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
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
                    fontSize = 13.sp,
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
const val FOLDER_CONTEXT_IMPORT_TAG: String = "folder-context:import"
const val FOLDER_CONTEXT_CLONE_TAG: String = "folder-context:clone"
const val FOLDER_CONTEXT_EMPTY_PROJECT_TAG: String = "folder-context:empty-project"
const val EMPTY_PROJECT_DIALOG_TAG: String = "folder-context:empty-project:dialog"
const val EMPTY_PROJECT_NAME_TAG: String = "folder-context:empty-project:name"
const val EMPTY_PROJECT_CREATE_TAG: String = "folder-context:empty-project:create"
const val EMPTY_PROJECT_CANCEL_TAG: String = "folder-context:empty-project:cancel"
