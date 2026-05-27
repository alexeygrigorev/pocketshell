package com.pocketshell.app.snippets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Snippet library management screen — full CRUD for the snippets of a
 * single host. Reachable from [SnippetPickerSheet]'s "Manage" affordance
 * (rendered there as a fullscreen `Dialog`), so the screen has a back
 * affordance but is not currently part of the top-level navigation
 * graph.
 *
 * Layout, top-to-bottom:
 *
 *  - **App bar** — back `‹` + title "Snippets".
 *  - **Add button** — accent pill that opens the [SnippetEditorDialog].
 *  - **Inline error banner** — surfaces DAO failures from the ViewModel.
 *  - **Two grouped sections** — "Commands" and "Prompts". Empty groups
 *    are collapsed so a brand-new library shows nothing but the empty
 *    state.
 *
 * Each row carries `Edit` and `Delete` text buttons. Delete shows a
 * confirmation dialog because the action is destructive and not undoable
 * (no soft-delete flag on [SnippetEntity]).
 *
 * @param hostId the host whose library this screen renders. The ViewModel
 *               binds to this id; passing 0 / a non-existent id surfaces
 *               an empty list (no host -> no FK-valid snippets).
 * @param onBack invoked on back-press / app-bar tap; the caller should
 *               close the hosting Dialog or pop the nav route.
 * @param viewModel injected via Hilt by default. Tests substitute a fake.
 */
@Composable
public fun SnippetsScreen(
    hostId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId) {
        viewModel.bindHost(hostId)
    }

    val snippets by viewModel.snippets.collectAsState()
    val error by viewModel.error.collectAsState()

    var editorTarget: SnippetEntity? by remember { mutableStateOf(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete: SnippetEntity? by remember { mutableStateOf(null) }
    // Issue #190: long-press surface for renaming a snippet without
    // editing the body. Reuses the rename text field shape so the user
    // can clear an override (returning to derived-label behaviour) or
    // type a new override.
    var renameTarget: SnippetEntity? by remember { mutableStateOf(null) }

    BackHandler {
        when {
            pendingDelete != null -> pendingDelete = null
            renameTarget != null -> renameTarget = null
            editorTarget != null -> editorTarget = null
            showAddDialog -> showAddDialog = false
            else -> onBack()
        }
    }

    val commands = snippets.filter {
        SnippetKind.fromStorage(it.kind) == SnippetKind.Command
    }
    val prompts = snippets.filter {
        SnippetKind.fromStorage(it.kind) == SnippetKind.Prompt
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnippetsAppBar(onBack = onBack)

            // Add affordance — accent pill stretched wide enough to read
            // as the primary action of the screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.OnAccent,
                    ),
                ) {
                    Text("Add snippet", fontWeight = FontWeight.SemiBold)
                }
            }

            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PocketShellColors.Surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = PocketShellColors.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            if (snippets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No snippets yet",
                            color = PocketShellColors.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap Add to create a command or prompt template.",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (commands.isNotEmpty()) {
                        item(key = "section-commands") {
                            SectionHeader(
                                label = "Commands",
                                count = commands.size,
                            )
                        }
                        items(commands, key = { "cmd-${it.id}" }) { snippet ->
                            SnippetRow(
                                snippet = snippet,
                                onEdit = { editorTarget = snippet },
                                onDelete = { pendingDelete = snippet },
                                onRename = { renameTarget = snippet },
                            )
                        }
                    }
                    if (prompts.isNotEmpty()) {
                        item(key = "section-prompts") {
                            SectionHeader(
                                label = "Prompts",
                                count = prompts.size,
                            )
                        }
                        items(prompts, key = { "prm-${it.id}" }) { snippet ->
                            SnippetRow(
                                snippet = snippet,
                                onEdit = { editorTarget = snippet },
                                onDelete = { pendingDelete = snippet },
                                onRename = { renameTarget = snippet },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        // Issue #190: add-flow collects ONLY the body + kind. Label is
        // null at insert time so the read-side renderer derives it from
        // the body's first line.
        SnippetAddDialog(
            onDismiss = { showAddDialog = false },
            onSave = { body, kind ->
                viewModel.addSnippet(label = null, body = body, kind = kind)
                showAddDialog = false
            },
        )
    }

    editorTarget?.let { target ->
        // Edit flow keeps the full editor: body + kind + optional label
        // override. Useful when the user wants to change the body
        // wholesale, not just the displayed name.
        SnippetEditorDialog(
            initial = target,
            onDismiss = { editorTarget = null },
            onSave = { label, body, kind ->
                viewModel.updateSnippet(
                    target.copy(
                        label = label,
                        body = body,
                        kind = kind.storageValue,
                    ),
                )
                editorTarget = null
            },
        )
    }

    renameTarget?.let { target ->
        // Issue #190: long-press rename — single text field pre-filled
        // with the current displayed label. Clearing the field reverts
        // to derived-label behaviour.
        SnippetRenameDialog(
            initial = target,
            onDismiss = { renameTarget = null },
            onSave = { newLabel ->
                viewModel.renameSnippet(target, newLabel)
                renameTarget = null
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this snippet?", color = PocketShellColors.Text) },
            text = {
                Text(
                    text = "“${target.displayLabel()}” will be removed permanently.",
                    color = PocketShellColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSnippet(target)
                    pendingDelete = null
                }) {
                    Text("Delete", color = PocketShellColors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = PocketShellColors.Accent)
                }
            },
            containerColor = PocketShellColors.Surface,
        )
    }
}

@Composable
private fun SnippetsAppBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Snippets",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Section header matching the dashboard's `.section-label` style:
 * uppercase + letter-spaced + a small pill carrying the count.
 */
@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(PocketShellColors.Surface, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = count.toString(),
                color = PocketShellColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Single snippet row — mirrors the host-card visual treatment. Two
 * trailing text buttons (Edit / Delete) carry the primary actions, and
 * a long-press anywhere on the row opens the rename affordance from
 * issue #190.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetRow(
    snippet: SnippetEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val kind = SnippetKind.fromStorage(snippet.kind)
    val explicit = snippet.hasExplicitLabel()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(14.dp),
            )
            // Long-press anywhere on the card opens the rename dialog.
            // `combinedClickable` carries the regular tap as a no-op so
            // the surface still ripples on contact.
            .combinedClickable(
                onClick = {},
                onLongClick = onRename,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snippet.displayLabel(),
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            KindTag(kind)
        }
        // Show the body preview only when the label is overridden — when
        // the label IS the derived first line of the body, the preview
        // would just repeat the primary text. (Issue #190.)
        if (explicit) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = snippet.body,
                color = PocketShellColors.TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onEdit) {
                Text("Edit", color = PocketShellColors.Accent, fontSize = 13.sp)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = PocketShellColors.Red, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Add dialog (issue #190). Single body text input + the command/prompt
 * kind toggle — no separate label field. The label is auto-derived from
 * the body's first line at read time; the user can rename via long-press
 * later if the derived label does not read well.
 */
@Composable
internal fun SnippetAddDialog(
    onDismiss: () -> Unit,
    onSave: (body: String, kind: SnippetKind) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SnippetKind.Command) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Add snippet", color = PocketShellColors.Text)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Snippet text") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "We'll use the first line as the label. " +
                        "Long-press a snippet later to rename it.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Kind",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindToggle(
                        target = SnippetKind.Command,
                        current = kind,
                        onSelect = { kind = it },
                    )
                    KindToggle(
                        target = SnippetKind.Prompt,
                        current = kind,
                        onSelect = { kind = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(body, kind) },
                enabled = body.isNotBlank(),
            ) {
                Text(
                    text = "Save",
                    color = if (body.isNotBlank()) {
                        PocketShellColors.Accent
                    } else {
                        PocketShellColors.TextMuted
                    },
                )
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

/**
 * Edit dialog. Pre-populated from [initial]; both the body and the
 * (optional) explicit label override are editable. Clearing the label
 * field reverts the snippet to derived-label behaviour (see
 * [SnippetsViewModel.updateSnippet]).
 */
@Composable
internal fun SnippetEditorDialog(
    initial: SnippetEntity,
    onDismiss: () -> Unit,
    onSave: (label: String?, body: String, kind: SnippetKind) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial.label.orEmpty()) }
    var body by remember(initial) { mutableStateOf(initial.body) }
    var kind by remember(initial) {
        mutableStateOf(SnippetKind.fromStorage(initial.kind))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Edit snippet", color = PocketShellColors.Text)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Leave blank to auto-derive from the first line.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Kind",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindToggle(
                        target = SnippetKind.Command,
                        current = kind,
                        onSelect = { kind = it },
                    )
                    KindToggle(
                        target = SnippetKind.Prompt,
                        current = kind,
                        onSelect = { kind = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalised = label.trim().ifEmpty { null }
                    onSave(normalised, body, kind)
                },
                enabled = body.isNotBlank(),
            ) {
                Text(
                    text = "Save",
                    color = if (body.isNotBlank()) {
                        PocketShellColors.Accent
                    } else {
                        PocketShellColors.TextMuted
                    },
                )
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

/**
 * Rename dialog (issue #190). Single text field pre-filled with the
 * snippet's current displayed label. Saving an empty string clears any
 * explicit override and falls back to the derived-label rule.
 */
@Composable
internal fun SnippetRenameDialog(
    initial: SnippetEntity,
    onDismiss: () -> Unit,
    onSave: (newLabel: String?) -> Unit,
) {
    var label by remember(initial) {
        mutableStateOf(initial.label.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename snippet", color = PocketShellColors.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Leave blank to use the first line of the body.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(label.trim().ifEmpty { null })
                },
            ) {
                Text(text = "Save", color = PocketShellColors.Accent)
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

/**
 * Pill toggle used in the editor dialog to pick between Command and
 * Prompt. Pre-applies the accent treatment for the selected state.
 */
@Composable
private fun KindToggle(
    target: SnippetKind,
    current: SnippetKind,
    onSelect: (SnippetKind) -> Unit,
) {
    val selected = target == current
    Box(
        modifier = Modifier
            .background(
                color = if (selected) PocketShellColors.AccentSoft else PocketShellColors.Surface,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) PocketShellColors.AccentDim else PocketShellColors.Border,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onSelect(target) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = target.label,
            color = if (selected) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PocketShellColors.Text,
    unfocusedTextColor = PocketShellColors.Text,
    focusedBorderColor = PocketShellColors.Accent,
    unfocusedBorderColor = PocketShellColors.Border,
    focusedLabelColor = PocketShellColors.Accent,
    unfocusedLabelColor = PocketShellColors.TextSecondary,
    cursorColor = PocketShellColors.Accent,
    focusedContainerColor = PocketShellColors.SurfaceElev,
    unfocusedContainerColor = PocketShellColors.SurfaceElev,
)
