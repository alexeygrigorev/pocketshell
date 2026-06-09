package com.pocketshell.app.snippets

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

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
 *  - **Kind tabs** — top-level Prompts / Commands toggle that filters
 *    the manager list.
 *  - **Add button** — accent pill that opens the [SnippetEditorDialog].
 *  - **Inline error banner** — surfaces DAO failures from the ViewModel.
 *  - **Filtered list** — shows one snippet kind at a time.
 *
 * Each row carries a kebab with edit, rename, and delete actions. Delete
 * shows a confirmation dialog because the action is destructive and not
 * undoable (no soft-delete flag on [SnippetEntity]).
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
    commandTemplatesViewModel: CommandTemplatesViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId) {
        viewModel.bindHost(hostId)
        commandTemplatesViewModel.bindHost(hostId)
    }

    val snippets by viewModel.snippets.collectAsState()
    val error by viewModel.error.collectAsState()
    val commandTemplates by commandTemplatesViewModel.templates.collectAsState()
    val commandTemplateError by commandTemplatesViewModel.error.collectAsState()

    var editorTarget: SnippetEntity? by remember { mutableStateOf(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete: SnippetEntity? by remember { mutableStateOf(null) }
    var selectedTab by remember { mutableStateOf(SnippetLibraryTab.Prompts) }
    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var templateEditorTarget: CommandTemplateEntity? by remember { mutableStateOf(null) }
    var pendingTemplateDelete: CommandTemplateEntity? by remember { mutableStateOf(null) }
    // Rename surface for changing a snippet label without editing the
    // body. Reuses the rename text field shape so the user can clear an
    // override (returning to derived-label behaviour) or type a new
    // override.
    var renameTarget: SnippetEntity? by remember { mutableStateOf(null) }

    BackHandler {
        when {
            pendingDelete != null -> pendingDelete = null
            pendingTemplateDelete != null -> pendingTemplateDelete = null
            renameTarget != null -> renameTarget = null
            editorTarget != null -> editorTarget = null
            templateEditorTarget != null -> templateEditorTarget = null
            showAddDialog -> showAddDialog = false
            showAddTemplateDialog -> showAddTemplateDialog = false
            else -> onBack()
        }
    }

    val commands = remember(snippets) { snippetsForKind(snippets, SnippetKind.Command) }
    val prompts = remember(snippets) { snippetsForKind(snippets, SnippetKind.Prompt) }
    val visibleSnippets = when (selectedTab) {
        SnippetLibraryTab.Prompts -> prompts
        SnippetLibraryTab.Commands -> commands
        SnippetLibraryTab.Macros -> emptyList()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SnippetsAppBar(onBack = onBack)

            SnippetLibraryTabs(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Add affordance — accent pill stretched wide enough to read
            // as the primary action of the screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            ) {
                Button(
                    onClick = {
                        if (selectedTab == SnippetLibraryTab.Macros) {
                            showAddTemplateDialog = true
                        } else {
                            showAddDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.OnAccent,
                    ),
                ) {
                    Text(
                        text = addButtonTextForTab(selectedTab),
                        fontWeight = FontWeight.SemiBold,
                    )
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
            commandTemplateError?.let { msg ->
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
                    TextButton(onClick = commandTemplatesViewModel::clearError) {
                        Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            if (selectedTab == SnippetLibraryTab.Macros) {
                if (commandTemplates.isEmpty()) {
                    EmptyLibraryState(
                        title = "No command macros",
                        message = "Tap Add macro to create a multi-command template.",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(commandTemplates, key = { "macro-${it.id}" }) { template ->
                            CommandTemplateRow(
                                template = template,
                                onEdit = { templateEditorTarget = template },
                                onDelete = { pendingTemplateDelete = template },
                            )
                        }
                    }
                }
            } else if (visibleSnippets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = emptyTitleForTab(selectedTab),
                            color = PocketShellColors.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = emptyMessageForTab(selectedTab, snippets.isEmpty()),
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.bodyDense,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleSnippets, key = { "${selectedTab.name}-${it.id}" }) { snippet ->
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

    if (showAddDialog) {
        // Issue #190: add-flow collects ONLY the body + kind. Label is
        // null at insert time so the read-side renderer derives it from
        // the body's first line.
        SnippetAddDialog(
            initialKind = if (selectedTab == SnippetLibraryTab.Commands) {
                SnippetKind.Command
            } else {
                SnippetKind.Prompt
            },
            onDismiss = { showAddDialog = false },
            onSave = { body, kind ->
                viewModel.addSnippet(label = null, body = body, kind = kind)
                showAddDialog = false
            },
        )
    }

    if (showAddTemplateDialog) {
        CommandTemplateEditorDialog(
            initial = null,
            onDismiss = { showAddTemplateDialog = false },
            onSave = { label, commands ->
                commandTemplatesViewModel.addTemplate(label = label, commands = commands)
                showAddTemplateDialog = false
            },
        )
    }

    templateEditorTarget?.let { target ->
        CommandTemplateEditorDialog(
            initial = target,
            onDismiss = { templateEditorTarget = null },
            onSave = { label, commands ->
                commandTemplatesViewModel.updateTemplate(target.copy(label = label, commands = commands))
                templateEditorTarget = null
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
        // Single-field rename, pre-filled
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

    pendingTemplateDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTemplateDelete = null },
            title = { Text("Delete this macro?", color = PocketShellColors.Text) },
            text = {
                Text(
                    text = "\"${target.label}\" will be removed permanently.",
                    color = PocketShellColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    commandTemplatesViewModel.deleteTemplate(target)
                    pendingTemplateDelete = null
                }) {
                    Text("Delete", color = PocketShellColors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplateDelete = null }) {
                    Text("Cancel", color = PocketShellColors.Accent)
                }
            },
            containerColor = PocketShellColors.Surface,
        )
    }
}

internal enum class SnippetLibraryTab(val label: String) {
    Prompts("Prompts"),
    Commands("Commands"),
    Macros("Macros"),
}

internal fun snippetsForKind(
    snippets: List<SnippetEntity>,
    kind: SnippetKind,
): List<SnippetEntity> =
    snippets.filter { SnippetKind.fromStorage(it.kind) == kind }

private fun addButtonTextForTab(tab: SnippetLibraryTab): String =
    when (tab) {
        SnippetLibraryTab.Prompts -> "Add prompt"
        SnippetLibraryTab.Commands -> "Add command"
        SnippetLibraryTab.Macros -> "Add macro"
    }

private fun emptyTitleForTab(tab: SnippetLibraryTab): String =
    when (tab) {
        SnippetLibraryTab.Prompts -> "No prompt snippets"
        SnippetLibraryTab.Commands -> "No command snippets"
        SnippetLibraryTab.Macros -> "No command macros"
    }

private fun emptyMessageForTab(tab: SnippetLibraryTab, libraryIsEmpty: Boolean): String =
    if (libraryIsEmpty) {
        "Tap Add to create a command, prompt, or macro template."
    } else {
        "Switch tabs or add a ${tab.label.dropLast(1).lowercase()}."
    }

/**
 * Snippets header, routed through the shared [ScreenHeader] (#479 Slice B1)
 * so this management screen reads as the same tight dev-tool block as the host
 * list and folder tree instead of the old bespoke 60dp / 22.sp app bar. The
 * back `‹` chevron lives in the header's leading slot.
 */
@Composable
private fun SnippetsAppBar(onBack: () -> Unit) {
    ScreenHeader(
        title = "Snippets",
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
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
}

/**
 * Top-level category tabs for the snippet manager. Prompts are listed
 * first because agent prompt snippets are the motivating workflow for
 * this split; Commands remain one tap away for plain shell sessions.
 */
@Composable
private fun SnippetLibraryTabs(
    selectedTab: SnippetLibraryTab,
    onSelect: (SnippetLibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        SnippetLibraryTab.Prompts,
        SnippetLibraryTab.Commands,
        SnippetLibraryTab.Macros,
    )
    SegmentedToggle(
        labels = tabs.map { it.label },
        selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
        onSelected = { index -> onSelect(tabs[index]) },
        modifier = modifier,
        segmentTag = { index -> snippetLibraryTabTag(tabs[index]) },
        fillSegments = true,
    )
}

internal fun snippetKindTabTag(kind: SnippetKind): String =
    snippetLibraryTabTag(
        when (kind) {
            SnippetKind.Prompt -> SnippetLibraryTab.Prompts
            SnippetKind.Command -> SnippetLibraryTab.Commands
        },
    )

internal fun snippetLibraryTabTag(tab: SnippetLibraryTab): String =
    "snippets-library-tab-${tab.name.lowercase()}"

@Composable
private fun ColumnScope.EmptyLibraryState(title: String, message: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
    }
}

/**
 * Single snippet row, composed from the shared dense-row primitives (#479
 * Slice B1). [ListRow] carries the snippet label (`bodyDense`) + a one-line
 * mono body preview in the subtitle slot (`bodyMono`); the [KindTag] command/
 * prompt pill and a per-row [Kebab] sit in the trailing slot.
 *
 * The former inline `Edit` / `Delete` text buttons and rename gesture all
 * collapse into the one kebab (§4 decision 4), keeping row actions visible
 * without widening the dense row.
 */
@Composable
private fun SnippetRow(
    snippet: SnippetEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val kind = SnippetKind.fromStorage(snippet.kind)
    val explicit = snippet.hasExplicitLabel()
    // Show the body preview only when the label is overridden — when the label
    // IS the derived first line of the body, the preview would just repeat the
    // primary text (issue #190). Collapse to a single line for the dense row.
    val subtitle = if (explicit) {
        snippet.body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    ListRow(
        title = snippet.displayLabel(),
        subtitle = subtitle,
        modifier = Modifier.testTag(snippetRowTestTag(snippet.id)),
        trailing = {
            SnippetKindBadge(kind)
            Kebab(
                contentDescription = "Snippet ${snippet.displayLabel()} actions",
                triggerTestTag = snippetActionsTestTag(snippet.id),
                triggerSize = PocketShellDensity.tapTargetMin,
                items = listOf(
                    KebabItem(
                        label = "Edit",
                        onClick = onEdit,
                        testTag = snippetEditActionTestTag(snippet.id),
                        contentDescription = "Edit snippet ${snippet.displayLabel()}",
                    ),
                    KebabItem(
                        label = "Rename",
                        onClick = onRename,
                        testTag = snippetRenameActionTestTag(snippet.id),
                        contentDescription = "Rename snippet ${snippet.displayLabel()}",
                    ),
                    KebabItem(
                        label = "Delete",
                        onClick = onDelete,
                        testTag = snippetDeleteActionTestTag(snippet.id),
                        contentDescription = "Delete snippet ${snippet.displayLabel()}",
                    ),
                ),
            )
        },
    )
}

@Composable
private fun CommandTemplateRow(
    template: CommandTemplateEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListRow(
        title = template.label,
        subtitle = commandTemplatePreview(template.commands),
        modifier = Modifier.testTag(commandTemplateRowTestTag(template.id)),
        trailing = {
            MacroTag()
            Kebab(
                contentDescription = "Macro ${template.label} actions",
                triggerTestTag = commandTemplateActionsTestTag(template.id),
                triggerSize = PocketShellDensity.tapTargetMin,
                items = listOf(
                    KebabItem(
                        label = "Edit",
                        onClick = onEdit,
                        testTag = commandTemplateEditActionTestTag(template.id),
                        contentDescription = "Edit macro ${template.label}",
                    ),
                    KebabItem(
                        label = "Delete",
                        onClick = onDelete,
                        testTag = commandTemplateDeleteActionTestTag(template.id),
                        contentDescription = "Delete macro ${template.label}",
                    ),
                ),
            )
        },
    )
}

private fun commandTemplatePreview(commands: String): String? =
    commands
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("  \u2192  ")
        .takeIf { it.isNotBlank() }

@Composable
private fun MacroTag() {
    Badge(label = "macro", role = BadgeRole.Agent, mono = false)
}

@Composable
private fun SnippetKindBadge(kind: SnippetKind) {
    Badge(
        label = kind.label.lowercase(),
        role = if (kind == SnippetKind.Prompt) BadgeRole.Agent else BadgeRole.Shell,
        mono = false,
    )
}

internal fun snippetRowTestTag(id: Long): String = "snippets:row:$id"

internal fun snippetActionsTestTag(id: Long): String = "snippets:row:$id:actions"

internal fun snippetEditActionTestTag(id: Long): String = "snippets:row:$id:edit"

internal fun snippetRenameActionTestTag(id: Long): String = "snippets:row:$id:rename"

internal fun snippetDeleteActionTestTag(id: Long): String = "snippets:row:$id:delete"

internal fun commandTemplateRowTestTag(id: Long): String = "snippets:macro:$id"

internal fun commandTemplateActionsTestTag(id: Long): String = "snippets:macro:$id:actions"

internal fun commandTemplateEditActionTestTag(id: Long): String = "snippets:macro:$id:edit"

internal fun commandTemplateDeleteActionTestTag(id: Long): String = "snippets:macro:$id:delete"

/**
 * Add dialog (issue #190). Single body text input + the command/prompt
 * kind toggle — no separate label field. The label is auto-derived from
 * the body's first line at read time; the user can rename from the row
 * menu later if the derived label does not read well.
 */
@Composable
internal fun SnippetAddDialog(
    initialKind: SnippetKind,
    onDismiss: () -> Unit,
    onSave: (body: String, kind: SnippetKind) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    var kind by remember(initialKind) { mutableStateOf(initialKind) }

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
                        "Use the row menu to rename it.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                Text(
                    text = "Kind",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindToggle(
                        target = SnippetKind.Prompt,
                        current = kind,
                        onSelect = { kind = it },
                    )
                    KindToggle(
                        target = SnippetKind.Command,
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
                Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                Text(
                    text = "Kind",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindToggle(
                        target = SnippetKind.Prompt,
                        current = kind,
                        onSelect = { kind = it },
                    )
                    KindToggle(
                        target = SnippetKind.Command,
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

@Composable
internal fun CommandTemplateEditorDialog(
    initial: CommandTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (label: String, commands: String) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label.orEmpty()) }
    var commands by remember(initial) { mutableStateOf(initial?.commands.orEmpty()) }
    val ready = label.isNotBlank() && commands.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "Add macro" else "Edit macro",
                color = PocketShellColors.Text,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(commandTemplateLabelFieldTag()),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = commands,
                    onValueChange = { commands = it },
                    label = { Text("Commands, one per line") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(commandTemplateCommandsFieldTag()),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    colors = dialogFieldColors(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Use {{name}} placeholders; they will be filled before sending.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, commands) },
                enabled = ready,
            ) {
                Text(
                    text = "Save",
                    color = if (ready) PocketShellColors.Accent else PocketShellColors.TextMuted,
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

internal fun commandTemplateLabelFieldTag(): String = "command-template-label"

internal fun commandTemplateCommandsFieldTag(): String = "command-template-commands"

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
                shape = PocketShellShapes.extraSmall,
            )
            .border(
                width = 1.dp,
                color = if (selected) PocketShellColors.AccentDim else PocketShellColors.Border,
                shape = PocketShellShapes.extraSmall,
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
