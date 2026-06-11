package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
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
    // Issue #613: the search field auto-focuses, so the soft keyboard opens
    // immediately. Use a fully-expanded sheet (not a partial one) so the
    // filtered project results have the maximum vertical room to stay visible
    // above the keyboard instead of being shoved off the bottom of a
    // half-height sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    val rootSessionTarget = remember(root.path, root.label) {
        RootProjectCandidate(
            path = root.path,
            label = root.label,
            source = RootProjectSource.Scanned,
        )
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        searchFocus.requestFocus()
    }

    // Issue #613: keyboard-aware layout.
    //
    // The old layout put a fixed-height results LazyColumn at the bottom of a
    // single `verticalScroll` Column. When the search field auto-focused and
    // the soft keyboard came up, the header + search + quick-action rows ate
    // the now-shorter sheet and the filtered project rows were pushed *below*
    // the keyboard — exactly the reported "I can't see which folders match
    // while typing" bug.
    //
    // The fix splits the sheet into a PINNED header (label, path, search field,
    // quick actions, "New session here") that always stays visible while
    // typing, and a single weighted results LazyColumn that takes the remaining
    // space and scrolls. `imePadding()` on the bounded-height outer Column
    // shrinks that remaining space to sit right above the keyboard, so the
    // matched folders stay visible and scrollable above the IME and the search
    // field never scrolls away.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .fillMaxHeight(ROOT_PROJECT_ADD_HEIGHT_FRACTION)
            .heightIn(max = ROOT_PROJECT_ADD_MAX_HEIGHT)
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.md)
            .testTag(ROOT_PROJECT_ADD_CONTENT_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
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
        Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
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
        RootSessionRow(
            root = root,
            onClick = { onStartSession(rootSessionTarget) },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .testTag(ROOT_PROJECT_ADD_LIST_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
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
private fun RootSessionRow(
    root: FolderTreeRoot,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .testTag(ROOT_PROJECT_ADD_ROOT_SESSION_TAG),
    ) {
        ListRow(
            title = "New session here",
            subtitle = root.displayPath,
            onClick = onClick,
            trailing = {
                Text(
                    text = "+",
                    color = PocketShellColors.Accent,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Bold,
                )
            },
        )
    }
}

@Composable
private fun RootQuickAction(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small),
    ) {
        ListRow(
            title = label,
            modifier = Modifier.testTag(testTag),
            onClick = onClick,
            trailing = {
                Text(
                    text = "+",
                    color = PocketShellColors.Accent,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Bold,
                )
            },
        )
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
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.md)
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

// Issue #613: bound the sheet height so the weighted results LazyColumn can
// shrink to fit above the keyboard. The fraction keeps a sliver of the
// backdrop visible (matching the SessionTypePickerSheet); the max cap stops
// the sheet from feeling oversized on tall devices.
private const val ROOT_PROJECT_ADD_HEIGHT_FRACTION = 0.92f
private val ROOT_PROJECT_ADD_MAX_HEIGHT = 640.dp

const val ROOT_PROJECT_ADD_SHEET_TAG: String = "root-project-add:sheet"
const val ROOT_PROJECT_ADD_CONTENT_TAG: String = "root-project-add:content"
const val ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG: String = "root-project-add:empty-project"
const val ROOT_PROJECT_ADD_CLONE_TAG: String = "root-project-add:clone"
const val ROOT_PROJECT_ADD_ROOT_SESSION_TAG: String = "root-project-add:root-session"
const val ROOT_PROJECT_ADD_SEARCH_TAG: String = "root-project-add:search"
const val ROOT_PROJECT_ADD_LIST_TAG: String = "root-project-add:list"
const val ROOT_PROJECT_ADD_EMPTY_TAG: String = "root-project-add:empty"

fun rootProjectCandidateTestTag(path: String): String = "root-project-add:project:$path"
fun rootProjectCandidateSourceTestTag(path: String): String = "root-project-add:project:$path:source"
