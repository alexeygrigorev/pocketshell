package com.pocketshell.app.snippets

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme

/**
 * Modal bottom sheet listing the saved snippets for [hostId], with a
 * search field at the top and a "Manage" affordance that opens the full
 * [SnippetsScreen] CRUD surface as a fullscreen [Dialog].
 *
 * Caller wiring:
 *
 *  - [onSnippetPicked]: invoked when the user taps a row. The caller
 *    decides what to do with the snippet's `body` (append to a composer
 *    text area, write to the terminal's stdin with `\n`, etc).
 *  - [onDismiss]: invoked when the sheet's grabber drag or scrim tap
 *    closes the sheet. Callers should clear their `showSheet` state.
 *
 * The sheet is "shallow" — it owns no business state of its own. The
 * Hilt-injected [SnippetsViewModel] is bound to [hostId] in a
 * [LaunchedEffect]; mutations happen on the management dialog or
 * upstream of the sheet entirely.
 *
 * Search is a client-side substring match on label + body, case-
 * insensitive. The list is small (typically <50 entries) so a full scan
 * per keystroke is fast enough that we don't need a debounce.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SnippetPickerSheet(
    hostId: Long,
    onDismiss: () -> Unit,
    onSnippetPicked: (SnippetEntity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SnippetsViewModel = hiltViewModel(),
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    LaunchedEffect(hostId) {
        viewModel.bindHost(hostId)
    }

    val snippets by viewModel.snippets.collectAsState()
    var query by remember { mutableStateOf("") }
    var showManage by remember { mutableStateOf(false) }

    val filtered = remember(query, snippets) {
        if (query.isBlank()) {
            snippets
        } else {
            val needle = query.trim().lowercase()
            snippets.filter { snippet ->
                // Match against the *displayed* label so derived-label
                // rows (issue #190) are still findable via the search.
                snippet.displayLabel().lowercase().contains(needle) ||
                    snippet.body.lowercase().contains(needle)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        SnippetPickerContent(
            snippets = filtered,
            totalCount = snippets.size,
            query = query,
            onQueryChange = { query = it },
            onSnippetTap = { snippet ->
                onSnippetPicked(snippet)
                onDismiss()
            },
            onManageTap = { showManage = true },
            onClose = onDismiss,
        )
    }

    if (showManage) {
        // Fullscreen Dialog over the sheet — keeps the CRUD UI reachable
        // without owning a navigation destination. `usePlatformDefaultWidth =
        // false` lets the Dialog fill the window the same way a fullscreen
        // Activity would.
        Dialog(
            onDismissRequest = { showManage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            SnippetsScreen(
                hostId = hostId,
                onBack = { showManage = false },
                modifier = Modifier.fillMaxWidth(),
                viewModel = viewModel,
            )
        }
    }
}

/**
 * Pure-renderer content for the sheet body. Pulled out so the `@Preview`s
 * can render the list and search without Hilt — `ModalBottomSheet` itself
 * does not preview well (it needs a real window decor view).
 */
@Composable
internal fun SnippetPickerContent(
    snippets: List<SnippetEntity>,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSnippetTap: (SnippetEntity) -> Unit,
    onManageTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp),
    ) {
        // Header: title + close X.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Snippets",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Manage",
                    color = PocketShellColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onManageTap)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 20.sp,
                    )
                }
            }
        }

        // Search field. Mirrors the composer text area's surface-elev fill
        // for visual consistency.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search snippets...",
                            color = PocketShellColors.TextMuted,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (totalCount == 0) {
            // Cold-start empty state. The "Manage" affordance in the
            // header is the way out — pointing at it explicitly so the
            // user doesn't think the sheet is broken.
            EmptyPickerState(onManageTap = onManageTap)
        } else if (snippets.isEmpty()) {
            // Non-empty library, empty filtered result.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matches for \"$query\"",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(snippets, key = { it.id }) { snippet ->
                    SnippetPickerRow(snippet = snippet, onTap = { onSnippetTap(snippet) })
                }
            }
        }
    }
}

@Composable
private fun SnippetPickerRow(
    snippet: SnippetEntity,
    onTap: () -> Unit,
) {
    val kind = SnippetKind.fromStorage(snippet.kind)
    val displayLabel = snippet.displayLabel()
    // Issue #190: only render the one-line body preview when the label
    // was explicitly overridden AND the body carries content beyond what
    // the label already shows. When the label IS the derived first line
    // the preview would be a duplicate.
    val showBodyPreview = remember(snippet) {
        shouldShowBodyPreview(snippet, displayLabel)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayLabel,
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                KindTag(kind)
            }
            if (showBodyPreview) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = snippet.body,
                    color = PocketShellColors.TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Returns `true` when the picker row should render the secondary body
 * preview under the label (issue #190).
 *
 * Rule:
 *  - Hide when the label is derived — the body's first line IS the
 *    label, repeating it adds nothing.
 *  - Hide when the explicit label happens to match the body exactly —
 *    same dedup reasoning.
 *  - Otherwise, show — the user picked a label that does not directly
 *    quote the body and a one-line preview clarifies what will be sent.
 */
internal fun shouldShowBodyPreview(snippet: SnippetEntity, displayLabel: String): Boolean {
    if (!snippet.hasExplicitLabel()) return false
    val firstBodyLine = snippet.body.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstBodyLine.isNotEmpty() && firstBodyLine != displayLabel.trim()
}

/**
 * Small accent pill used in the row trailing position to discriminate
 * command vs prompt snippets visually. Mirrors the
 * `.chip.icon-chip` accent treatment from the design tokens.
 */
@Composable
internal fun KindTag(kind: SnippetKind) {
    Box(
        modifier = Modifier
            .background(
                color = if (kind == SnippetKind.Prompt) {
                    PocketShellColors.AccentSoft
                } else {
                    PocketShellColors.Surface
                },
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = if (kind == SnippetKind.Prompt) {
                    PocketShellColors.AccentDim
                } else {
                    PocketShellColors.BorderSoft
                },
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = kind.label.lowercase(),
            color = if (kind == SnippetKind.Prompt) {
                PocketShellColors.Accent
            } else {
                PocketShellColors.TextSecondary
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyPickerState(onManageTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No snippets yet",
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap Manage to add commands or prompt templates",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .background(
                        color = PocketShellColors.Accent,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable(onClick = onManageTap)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Manage snippets",
                    color = PocketShellColors.OnAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// -- Previews -----------------------------------------------------------------

@Preview(name = "Snippet picker - populated", widthDp = 412, heightDp = 600)
@Composable
private fun SnippetPickerPopulatedPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SnippetPickerContent(
                snippets = listOf(
                    // Derived label: label is null, picker shows the body
                    // first line and no secondary preview (issue #190).
                    SnippetEntity(id = 1, hostId = 1, label = null, body = "kubectl get pods -A", kind = "command"),
                    SnippetEntity(id = 2, hostId = 1, label = null, body = "kubectl logs -f deploy/api", kind = "command"),
                    // Overridden label: secondary preview shows the body
                    // because it differs from the chosen label.
                    SnippetEntity(
                        id = 3,
                        hostId = 1,
                        label = "summarise diff",
                        body = "Please summarise the staged git diff and highlight risky changes.",
                        kind = "prompt",
                    ),
                ),
                totalCount = 3,
                query = "",
                onQueryChange = {},
                onSnippetTap = {},
                onManageTap = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Snippet picker - empty", widthDp = 412, heightDp = 600)
@Composable
private fun SnippetPickerEmptyPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SnippetPickerContent(
                snippets = emptyList(),
                totalCount = 0,
                query = "",
                onQueryChange = {},
                onSnippetTap = {},
                onManageTap = {},
                onClose = {},
            )
        }
    }
}
