package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
fun RootProjectAddSheet(
    root: FolderTreeRoot,
    onDismiss: () -> Unit,
    onStartSession: (RootProjectCandidate) -> Unit,
    onCreateEmptyProject: () -> Unit,
    onCloneGitProject: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(ROOT_PROJECT_ADD_SHEET_TAG),
    ) {
        RootProjectAddSheetContent(
            root = root,
            candidates = root.addSheetProjects,
            onStartSession = onStartSession,
            onCreateEmptyProject = onCreateEmptyProject,
            onCloneGitProject = onCloneGitProject,
        )
    }
}

@Composable
internal fun RootProjectAddSheetContent(
    root: FolderTreeRoot,
    candidates: List<RootProjectCandidate>,
    onStartSession: (RootProjectCandidate) -> Unit,
    onCreateEmptyProject: () -> Unit,
    onCloneGitProject: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        FolderListViewModel.filterRootProjectCandidates(candidates, query)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = root.label,
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = root.path,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RootQuickAction(
                label = "Empty project",
                testTag = ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG,
                onClick = onCreateEmptyProject,
                modifier = Modifier.weight(1f),
            )
            RootQuickAction(
                label = "Clone git repo",
                testTag = ROOT_PROJECT_ADD_CLONE_TAG,
                onClick = onCloneGitProject,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search projects") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ROOT_PROJECT_ADD_SEARCH_TAG),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .testTag(ROOT_PROJECT_ADD_LIST_TAG),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filtered.isEmpty()) {
                item { RootProjectAddEmptyState(query = query) }
            } else {
                items(filtered, key = { it.path }) { candidate ->
                    RootProjectCandidateRow(
                        candidate = candidate,
                        onClick = { onStartSession(candidate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RootQuickAction(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(text = "+", color = PocketShellColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RootProjectCandidateRow(
    candidate: RootProjectCandidate,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(rootProjectCandidateTestTag(candidate.path)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.label,
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = candidate.path,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        RootProjectSourcePill(candidate)
    }
}

@Composable
private fun RootProjectSourcePill(candidate: RootProjectCandidate) {
    val label = when (candidate.source) {
        RootProjectSource.Active -> if (candidate.activeSessionCount == 1) "Active" else "${candidate.activeSessionCount} active"
        RootProjectSource.History -> "Used before"
        RootProjectSource.Scanned -> "Folder"
    }
    val fg = when (candidate.source) {
        RootProjectSource.Active -> PocketShellColors.Green
        RootProjectSource.History -> PocketShellColors.Accent
        RootProjectSource.Scanned -> PocketShellColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .background(fg.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RootProjectAddEmptyState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(ROOT_PROJECT_ADD_EMPTY_TAG),
    ) {
        Text(
            text = if (query.isBlank()) "No projects found" else "No matching projects",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Create a folder or clone a git repo into this root.",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

const val ROOT_PROJECT_ADD_SHEET_TAG: String = "root-project-add:sheet"
const val ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG: String = "root-project-add:empty-project"
const val ROOT_PROJECT_ADD_CLONE_TAG: String = "root-project-add:clone"
const val ROOT_PROJECT_ADD_SEARCH_TAG: String = "root-project-add:search"
const val ROOT_PROJECT_ADD_LIST_TAG: String = "root-project-add:list"
const val ROOT_PROJECT_ADD_EMPTY_TAG: String = "root-project-add:empty"

fun rootProjectCandidateTestTag(path: String): String = "root-project-add:project:$path"
