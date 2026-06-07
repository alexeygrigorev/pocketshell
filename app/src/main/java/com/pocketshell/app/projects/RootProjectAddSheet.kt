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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

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
        RootProjectFilter.filter(candidates, query)
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        searchFocus.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = root.label,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        root.displayPath?.let { path ->
            Text(
                text = path,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search projects") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .testTag(ROOT_PROJECT_ADD_SEARCH_TAG),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .testTag(ROOT_PROJECT_ADD_LIST_TAG),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(text = "+", color = PocketShellColors.Accent, style = PocketShellType.bodyDense, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RootProjectCandidateRow(
    candidate: RootProjectCandidate,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .testTag(rootProjectCandidateTestTag(candidate.path)),
    ) {
        ListRow(
            title = candidate.label,
            subtitle = candidatePathTail(candidate),
            onClick = onClick,
            trailing = { RootProjectSourceLabel(candidate) },
        )
    }
}

/**
 * The path segment leading up to (but excluding) the project [label], shown
 * dimmed so the user keeps just enough context to disambiguate same-named
 * folders under different parents. Returns null when the path adds nothing
 * beyond the label (e.g. the label already equals the final segment with no
 * informative parent).
 */
private fun candidatePathTail(candidate: RootProjectCandidate): String? {
    val path = candidate.path.trimEnd('/')
    val lastSlash = path.lastIndexOf('/')
    if (lastSlash <= 0) return null
    val parent = path.substring(0, lastSlash)
    val parentName = parent.substringAfterLast('/')
    return if (parentName.isBlank()) null else "$parentName/"
}

@Composable
private fun RootProjectSourceLabel(candidate: RootProjectCandidate) {
    if (candidate.source != RootProjectSource.History) {
        // Scanned folders are the common case; keep them clean and only badge
        // the rarer "used before" history candidates.
        Spacer(
            modifier = Modifier
                .size(0.dp)
                .testTag(rootProjectCandidateSourceTestTag(candidate.path)),
        )
        return
    }
    Badge(
        label = "Recent",
        role = BadgeRole.Agent,
        mono = false,
        modifier = Modifier.testTag(rootProjectCandidateSourceTestTag(candidate.path)),
    )
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
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Create a folder or clone a git repo into this root.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
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
fun rootProjectCandidateSourceTestTag(path: String): String = "root-project-add:project:$path:source"
