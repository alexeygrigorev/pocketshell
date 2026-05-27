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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.pocketshell.uikit.theme.PocketShellThemeMode

/**
 * Modal bottom sheet listing the saved snippets for [hostId], with a
 * search field at the top and a "Manage" affordance that opens the full
 * [SnippetsScreen] CRUD surface as a fullscreen [Dialog].
 *
 * Caller wiring:
 *
 *  - [onSnippetPicked]: invoked when the user taps a row's body area
 *    (label + body preview). The caller decides what to do with the
 *    snippet's `body` (append to a composer text area, write to the
 *    terminal's stdin with `\n`, etc). Callers that route through the
 *    session terminal typically inspect [SnippetEntity.kind] and append
 *    Enter for commands but not for prompts (see
 *    [com.pocketshell.app.session.SessionViewModel.onSnippetPicked]).
 *    This is the "smart default" tap surface that preserves backward
 *    compatibility with the pre-#187 contract.
 *  - [onSnippetSend]: issue #187. Invoked when the user taps one of the
 *    new explicit `Send` / `Send + ↵` trailing buttons on a row. The
 *    `withEnter` flag carries the user's *explicit* intent: `true` for
 *    `Send + ↵`, `false` for plain `Send`. Defaults to a fallback that
 *    delegates to [onSnippetPicked] so existing callers (e.g.
 *    `TmuxSessionScreen`, `PromptComposerSheet`) keep working without a
 *    coordinated update — they'll just route both new buttons through
 *    their existing kind-aware logic until the caller picks up the
 *    explicit signal. Migrated callers (`SessionScreen` via the
 *    `withEnter`-aware viewmodel overload) honour the explicit intent
 *    and unblock the prompt-on-Enter workflow that motivated the issue.
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
    // Issue #187: explicit Send / Send+↵ affordances. Default falls back
    // to [onSnippetPicked] so callers that haven't been migrated yet keep
    // working (they'll route both new buttons through their pre-existing
    // kind-aware path).
    onSnippetSend: (SnippetEntity, withEnter: Boolean) -> Unit =
        { snippet, _ -> onSnippetPicked(snippet) },
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
            onSnippetSend = { snippet, withEnter ->
                onSnippetSend(snippet, withEnter)
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
    // Issue #187: explicit send buttons in the row trailing area. The
    // default forwards to [onSnippetTap] so callers that don't
    // distinguish Enter intent keep their existing kind-aware behaviour
    // through the row-body smart-default path; the buttons fire the same
    // callback but with the user-visible label still indicating Send vs
    // Send+↵ so the affordance reads correctly.
    onSnippetSend: (SnippetEntity, withEnter: Boolean) -> Unit =
        { snippet, _ -> onSnippetTap(snippet) },
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
                    SnippetPickerRow(
                        snippet = snippet,
                        onTap = { onSnippetTap(snippet) },
                        onSend = { withEnter -> onSnippetSend(snippet, withEnter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SnippetPickerRow(
    snippet: SnippetEntity,
    onTap: () -> Unit,
    onSend: (withEnter: Boolean) -> Unit,
) {
    val kind = SnippetKind.fromStorage(snippet.kind)
    val displayLabel = snippet.displayLabel()
    // Issue #198: render a one-line body preview under the label so the
    // user can tell snippets apart without expanding the row. The dedup
    // rule from #190 still applies — when the body collapses to exactly
    // the label (single-line derived labels, or explicit labels that
    // happen to quote the body verbatim) the preview would just repeat
    // the primary text, so it stays hidden in that case.
    val bodyPreview = remember(snippet, displayLabel) {
        snippetBodyPreview(snippet, displayLabel)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Row-body tap surface: keeps the legacy "smart default" path so
        // unmigrated callers (TmuxSessionScreen / PromptComposerSheet)
        // continue to honour their kind-aware behaviour. The trailing
        // explicit buttons below carry the user's overt intent for
        // callers that have wired the new (snippet, withEnter) callback.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .testTag(snippetPickerRowTag(snippet.id)),
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
                if (bodyPreview != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        // Issue #198: 12 sp monospace `TextMuted` mirrors
                        // the host-card subtitle pattern in
                        // `docs/design-system.md` so the picker reads
                        // consistently with the rest of the surface.
                        text = bodyPreview,
                        color = PocketShellColors.TextMuted,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(snippetBodyPreviewTag(snippet.id)),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Issue #187: explicit Send / Send + ↵ chip row, mirroring the
        // composer's action row (`PromptComposerSheet`) for affordance
        // consistency. The user no longer has to raise the IME just to
        // press Enter on a prompt snippet — tapping `Send + ↵` here
        // routes the body + a trailing newline through the same input
        // bridge the composer uses.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SnippetSendChip(
                label = "Send",
                primary = false,
                testTagId = snippetSendChipTag(snippet.id, withEnter = false),
                contentDescription = "Send snippet without Enter",
                modifier = Modifier.weight(1f),
                onClick = { onSend(false) },
            )
            SnippetSendChip(
                label = "Send + ↵",
                primary = true,
                testTagId = snippetSendChipTag(snippet.id, withEnter = true),
                contentDescription = "Send snippet with Enter",
                modifier = Modifier.weight(1f),
                onClick = { onSend(true) },
            )
        }
    }
}

/**
 * Issue #187: shared chip renderer for the picker row's `Send` /
 * `Send + ↵` affordances. The non-primary chip uses the same neutral
 * `SurfaceElev` fill the composer's `NeutralButton` uses; the primary
 * chip uses the accent fill so the row reads "default-recommended action
 * to the right" the same way the composer's action row does.
 */
@Composable
private fun SnippetSendChip(
    label: String,
    primary: Boolean,
    testTagId: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (primary) {
        PocketShellColors.Accent
    } else {
        PocketShellColors.Surface
    }
    val labelColor = if (primary) {
        PocketShellColors.OnAccent
    } else {
        PocketShellColors.Text
    }
    val borderColor = if (primary) {
        PocketShellColors.Accent
    } else {
        PocketShellColors.Border
    }
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTagId)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Test tag for the row's smart-default tap surface (the label / body
 * preview area). Used by Compose tests to drive the legacy
 * [onSnippetPicked] path without going through the new explicit chips.
 */
internal fun snippetPickerRowTag(snippetId: Long): String =
    "snippet-picker-row-$snippetId"

/**
 * Test tag for a row's explicit send chip. Two flavours per snippet:
 * `withEnter = false` for plain `Send`, `withEnter = true` for the
 * primary `Send + ↵` affordance.
 */
internal fun snippetSendChipTag(snippetId: Long, withEnter: Boolean): String =
    if (withEnter) "snippet-send-with-enter-$snippetId" else "snippet-send-$snippetId"

/**
 * Returns the one-line body preview string for [snippet] to render
 * under [displayLabel] in the picker row, or `null` when the row
 * should suppress the preview entirely (issue #198).
 *
 * Rule:
 *  - Empty bodies render no preview row (no blank padding). This is
 *    defensive: the editor blocks blank-body inserts but legacy rows
 *    might still surface here.
 *  - When the body collapses to exactly the displayed label — the
 *    common case for single-line snippets with a derived label, or
 *    an explicit label that quotes the body verbatim — the preview
 *    would just duplicate the primary text, so it stays hidden.
 *  - Otherwise the preview is the body with newlines collapsed to a
 *    single space so multi-line snippets surface their hidden lines
 *    in the one-line preview (with Compose-driven ellipsis on
 *    overflow). Internal runs of whitespace are not normalised: shell
 *    bodies frequently rely on doubled spaces (here-doc indentation,
 *    awk field separators) that we should preserve verbatim.
 */
internal fun snippetBodyPreview(snippet: SnippetEntity, displayLabel: String): String? {
    val body = snippet.body
    if (body.isBlank()) return null
    // Collapse \r\n then \n to a single space so a multi-line body
    // reads as one line in the picker preview. `\r` on its own is
    // treated the same (defensive: legacy Mac-style line endings).
    val singleLine = body
        .replace("\r\n", " ")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    if (singleLine.isEmpty()) return null
    if (singleLine == displayLabel.trim()) return null
    return singleLine
}

/**
 * Test tag for a row's one-line body preview Text (issue #198).
 */
internal fun snippetBodyPreviewTag(snippetId: Long): String =
    "snippet-body-preview-$snippetId"

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
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SnippetPickerContent(
                snippets = listOf(
                    // Derived label, single-line body: the body collapses
                    // to the label so the preview row stays hidden
                    // (issue #198 dedup).
                    SnippetEntity(id = 1, hostId = 1, label = null, body = "kubectl get pods -A", kind = "command"),
                    // Derived label, multi-line body: the second line is
                    // hidden by the derived label, so the preview shows
                    // the full body with newlines collapsed to spaces.
                    SnippetEntity(
                        id = 2,
                        hostId = 1,
                        label = null,
                        body = "kubectl logs -f deploy/api\n  --since=10m --tail=200",
                        kind = "command",
                    ),
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
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
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
