package com.pocketshell.app.env

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.FormDialog
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Per-folder env-file management screen — issue #264.
 *
 * Lists `.env` / `.envrc` keys for [directory] (masked values by
 * default, D24), and lets the user add/update a key (value via stdin,
 * never argv), reveal a value on demand (plain, no biometric), and copy
 * keys from another already-discovered folder.
 *
 * Chrome rides the shared #479 design language: the header is [ScreenHeader]
 * (no bespoke 60dp bar) and each key renders as a dense [ListRow] whose single
 * per-row [Kebab] carries Reveal / Hide.
 */
@Composable
fun EnvScreen(
    hostId: Long,
    hostName: String,
    keyPath: String,
    passphrase: CharArray?,
    directory: String,
    folderLabel: String,
    copySources: List<EnvCopySourceFolder>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnvViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId, directory) {
        viewModel.bind(
            hostId = hostId,
            keyPath = keyPath,
            passphrase = passphrase,
            directory = directory,
            folderLabel = folderLabel,
            copySources = copySources,
        )
    }
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showCopySheet by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(ENV_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EnvAppBar(
                folderLabel = state.folderLabel.ifBlank { folderLabel },
                hostName = hostName,
                onBack = onBack,
            )
            state.transientMessage?.let { message ->
                EnvBanner(message = message, onDismiss = viewModel::consumeTransientMessage)
            }
            when (val list = state.list) {
                EnvListState.Loading -> EnvLoadingPanel()
                EnvListState.ToolUnavailable -> EnvErrorPanel(
                    message = "pocketshell is not installed on $hostName.",
                    onRetry = viewModel::refresh,
                )
                is EnvListState.Failed -> EnvErrorPanel(message = list.message, onRetry = viewModel::refresh)
                is EnvListState.Ready -> EnvKeyList(
                    directory = state.directory.ifBlank { directory },
                    keys = list.keys,
                    canCopy = state.copySources.isNotEmpty(),
                    onReveal = viewModel::revealKey,
                    onHide = viewModel::hideKey,
                    onEdit = viewModel::beginEdit,
                    onAddKey = { showAddDialog = true },
                    onCopyFrom = { showCopySheet = true },
                )
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(56.dp)
                .testTag(ENV_ADD_FAB_TAG),
            shape = CircleShape,
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
        ) {
            Text(text = "+", fontSize = 28.sp, fontWeight = FontWeight.Medium)
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            busy = state.busy,
            onDismiss = { showAddDialog = false },
            onConfirm = { key, value, file ->
                showAddDialog = false
                viewModel.setKey(key, value, file)
            },
        )
    }

    // In-place value editor (#1092): pre-loaded with the current value via the
    // reveal/get path so the user tweaks the secret instead of retyping blind.
    when (val editor = state.editor) {
        EnvEditorState.Hidden -> Unit
        is EnvEditorState.LoadingValue -> EditKeyDialog(
            key = editor.key,
            file = editor.file,
            initialValue = null,
            busy = state.busy,
            onDismiss = viewModel::dismissEditor,
            onConfirm = {},
        )
        is EnvEditorState.Editing -> EditKeyDialog(
            key = editor.key,
            file = editor.file,
            initialValue = editor.currentValue,
            busy = state.busy,
            onDismiss = viewModel::dismissEditor,
            onConfirm = { viewModel.saveEdit(it) },
        )
    }

    if (showCopySheet) {
        CopyFromFolderSheet(
            sources = state.copySources,
            loadKeys = viewModel::loadCopySourceKeys,
            onDismiss = { showCopySheet = false },
            onConfirm = { sourceDir, keys, file ->
                showCopySheet = false
                viewModel.copyKeys(sourceDir, keys, file)
            },
        )
    }
}

@Composable
private fun EnvAppBar(folderLabel: String, hostName: String, onBack: () -> Unit) {
    ScreenHeader(
        title = "Env · $folderLabel",
        subtitle = hostName.ifBlank { null },
        titleTestTag = ENV_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(ENV_BACK_TAG),
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

@Composable
private fun EnvBanner(message: String, onDismiss: () -> Unit) {
    Banner(
        text = message,
        role = BannerRole.Info,
        modifier = Modifier.testTag(ENV_BANNER_TAG),
        trailingContent = {
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}

@Composable
private fun EnvLoadingPanel() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(ENV_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator.Spinner(size = SpinnerSize.Medium)
    }
}

@Composable
private fun EnvErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(ENV_ERROR_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message, color = PocketShellColors.Text, style = MaterialTheme.typography.bodyMedium)
        PocketShellButton(
            text = "Retry",
            onClick = onRetry,
            variant = ButtonVariant.Text,
            modifier = Modifier.testTag(ENV_RETRY_TAG),
        )
    }
}

@Composable
private fun EnvKeyList(
    directory: String,
    keys: List<EnvKeyUiRow>,
    canCopy: Boolean,
    onReveal: (String) -> Unit,
    onHide: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAddKey: () -> Unit,
    onCopyFrom: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = directory,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (canCopy) {
            item {
                PocketShellButton(
                    text = "Copy keys from another folder",
                    onClick = onCopyFrom,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(ENV_COPY_FROM_TAG),
                )
            }
        }
        if (keys.isEmpty()) {
            item { EnvEmptyState(onAddKey = onAddKey) }
        } else {
            items(keys, key = { envRowItemKey(it) }) { row ->
                EnvKeyCard(row = row, onReveal = onReveal, onHide = onHide, onEdit = onEdit)
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun EnvEmptyState(onAddKey: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(ENV_EMPTY_TAG),
    ) {
        Text(text = "No env keys yet", color = PocketShellColors.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            // Adding the first key is how a new .env / .envrc file is created
            // (the server writes it mode 0600) — make that obvious (#1092).
            text = "This folder has no .env yet. Add a key to create one, or copy keys from another folder.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.height(12.dp))
        PocketShellButton(
            text = "Add key",
            onClick = onAddKey,
            variant = ButtonVariant.Primary,
            modifier = Modifier.testTag(ENV_EMPTY_ADD_TAG),
        )
    }
}

@Composable
private fun EnvKeyCard(
    row: EnvKeyUiRow,
    onReveal: (String) -> Unit,
    onHide: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    // One dense row per key: the key name rides the mono-adjacent title, the
    // masked / revealed value rides the mono subtitle, and Reveal / Hide / Edit
    // consolidate into the single per-row kebab (#479 §4 decision 4). The file
    // pill stays as a right-aligned chip before the kebab.
    val display = when {
        row.revealedValue != null -> row.revealedValue
        !row.hasValue -> "(empty)"
        else -> "••••••••"
    }
    ListRow(
        modifier = Modifier.testTag(envKeyRowTestTag(row.key)),
        title = row.key,
        subtitle = display,
        trailing = {
            FileTag(file = row.file)
            if (row.revealing) {
                LoadingIndicator.Spinner(size = SpinnerSize.Small)
            } else {
                EnvKeyMenu(row = row, onReveal = onReveal, onHide = onHide, onEdit = onEdit)
            }
        },
    )
}

@Composable
private fun EnvKeyMenu(
    row: EnvKeyUiRow,
    onReveal: (String) -> Unit,
    onHide: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val items = buildList {
        // Edit-in-place is offered for every key (#1092) — including an empty
        // key, which Edit lets the user give a value for the first time.
        add(
            KebabItem(
                label = "Edit",
                onClick = { onEdit(row.key) },
                testTag = envKeyEditTestTag(row.key),
            ),
        )
        when {
            row.revealedValue != null -> add(
                KebabItem(
                    label = "Hide",
                    onClick = { onHide(row.key) },
                    testTag = envKeyHideTestTag(row.key),
                ),
            )
            row.hasValue -> add(
                KebabItem(
                    label = "Reveal",
                    onClick = { onReveal(row.key) },
                    testTag = envKeyRevealTestTag(row.key),
                ),
            )
        }
    }
    Kebab(
        triggerTestTag = envKeyMenuTestTag(row.key),
        items = items,
    )
}

@Composable
private fun FileTag(file: String) {
    Box(
        modifier = Modifier
            .background(PocketShellColors.Purple.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = file, color = PocketShellColors.Purple, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddKeyDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (key: String, value: String, file: EnvFileTarget) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(EnvFileTarget.Env) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ENV_ADD_DIALOG_TAG),
        title = { Text("Add / update key", color = PocketShellColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    singleLine = true,
                    colors = envFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag(ENV_ADD_KEY_FIELD_TAG),
                )
                // Write-only value field: masked entry, never read back.
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value (write-only)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = envFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag(ENV_ADD_VALUE_FIELD_TAG),
                )
                FileTargetSelector(selected = target, onSelect = { target = it })
            }
        },
        confirmButton = {
            PocketShellButton(
                text = "Save",
                onClick = { onConfirm(key, value, target) },
                variant = ButtonVariant.Primary,
                enabled = !busy,
                modifier = Modifier.testTag(ENV_ADD_CONFIRM_TAG),
            )
        },
        dismissButton = {
            PocketShellButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Text)
        },
        containerColor = PocketShellColors.Surface,
    )
}

/**
 * In-place editor for an existing key's value (#1092).
 *
 * The key name and its file are read-only (renaming is out of scope); only
 * the value changes. [initialValue] `null` means the current value is still
 * being fetched via the reveal/get path — render a spinner. When it has
 * loaded, the field is pre-populated, starts masked, and offers a Show/Hide
 * toggle. Save routes the new value back through `setKeys` (stdin, never
 * argv — D24).
 */
@Composable
private fun EditKeyDialog(
    key: String,
    file: EnvFileTarget,
    initialValue: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (value: String) -> Unit,
) {
    var value by remember(key, initialValue) { mutableStateOf(initialValue.orEmpty()) }
    var revealed by remember(key) { mutableStateOf(false) }
    val loading = initialValue == null

    // Shared ui-kit input dialog (#865 component-drift guard): FormDialog owns
    // the title / confirm (Primary "Save") / Cancel (Text) / Surface scaffold;
    // we only supply the read-only key+file header and the value field.
    FormDialog(
        title = "Edit $key",
        confirmLabel = "Save",
        onConfirm = { onConfirm(value) },
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ENV_EDIT_DIALOG_TAG),
        confirmEnabled = !busy && !loading,
        confirmTestTag = ENV_EDIT_CONFIRM_TAG,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = key,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyMono,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(ENV_EDIT_KEY_LABEL_TAG),
            )
            FileTag(file = file.fileName)
        }
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .testTag(ENV_EDIT_LOADING_TAG),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(size = SpinnerSize.Medium)
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Value") },
                singleLine = true,
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    PocketShellButton(
                        text = if (revealed) "Hide" else "Show",
                        onClick = { revealed = !revealed },
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(ENV_EDIT_TOGGLE_TAG),
                    )
                },
                colors = envFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag(ENV_EDIT_VALUE_FIELD_TAG),
            )
        }
    }
}

@Composable
private fun FileTargetSelector(selected: EnvFileTarget, onSelect: (EnvFileTarget) -> Unit) {
    val targets = EnvFileTarget.entries
    SegmentedToggle(
        labels = targets.map { it.fileName },
        selectedIndex = targets.indexOf(selected).coerceAtLeast(0),
        onSelected = { index -> onSelect(targets[index]) },
        segmentTag = { index -> envFileTargetTestTag(targets[index]) },
        fillSegments = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyFromFolderSheet(
    sources: List<EnvCopySourceFolder>,
    loadKeys: (String, (EnvCopySourceKeys) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (sourceDir: String, keys: List<String>, file: EnvFileTarget) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSource by remember { mutableStateOf<EnvCopySourceFolder?>(null) }
    var sourceKeys by remember { mutableStateOf<EnvCopySourceKeys?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var target by remember { mutableStateOf(EnvFileTarget.Env) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(ENV_COPY_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(title = "Copy keys from another folder")

            val source = selectedSource
            if (source == null) {
                Text("Pick a source folder:", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources, key = { it.path }) { folder ->
                        SourceFolderRow(
                            folder = folder,
                            onClick = {
                                selectedSource = folder
                                checked = emptySet()
                                sourceKeys = EnvCopySourceKeys.Loading
                                loadKeys(folder.path) { sourceKeys = it }
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = "From: ${source.label}",
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
                when (val ks = sourceKeys) {
                    null, EnvCopySourceKeys.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator.Spinner(size = SpinnerSize.Medium)
                    }
                    is EnvCopySourceKeys.Failed -> Text(
                        text = ks.message,
                        color = PocketShellColors.Red,
                        style = PocketShellType.bodyDense,
                        modifier = Modifier.testTag(ENV_COPY_SOURCE_ERROR_TAG),
                    )
                    is EnvCopySourceKeys.Ready -> {
                        if (ks.keys.isEmpty()) {
                            Text(
                                text = "That folder has no env keys.",
                                color = PocketShellColors.TextSecondary,
                                style = PocketShellType.bodyDense,
                                modifier = Modifier.testTag(ENV_COPY_SOURCE_EMPTY_TAG),
                            )
                        } else {
                            // Deduplicate by key name — `env copy` reads
                            // the merged source value, so two file rows
                            // for the same key map to one selectable item.
                            val keyNames = ks.keys.map { it.key }.distinct()
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(keyNames, key = { it }) { keyName ->
                                    CopyKeyCheckboxRow(
                                        keyName = keyName,
                                        checked = keyName in checked,
                                        onToggle = {
                                            checked = if (keyName in checked) checked - keyName else checked + keyName
                                        },
                                    )
                                }
                            }
                            Text("Into:", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
                            FileTargetSelector(selected = target, onSelect = { target = it })
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketShellButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Text)
                if (source != null) {
                    PocketShellButton(
                        text = "Copy ${checked.size}",
                        onClick = { onConfirm(source.path, checked.toList(), target) },
                        variant = ButtonVariant.Primary,
                        enabled = checked.isNotEmpty(),
                        modifier = Modifier.testTag(ENV_COPY_CONFIRM_TAG),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFolderRow(folder: EnvCopySourceFolder, onClick: () -> Unit) {
    ListRow(
        title = folder.label,
        subtitle = folder.path,
        onClick = onClick,
        modifier = Modifier
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.extraSmall)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.extraSmall)
            .testTag(envCopySourceTestTag(folder.path)),
    )
}

@Composable
private fun CopyKeyCheckboxRow(keyName: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .testTag(envCopyKeyTestTag(keyName)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = PocketShellColors.Accent,
                uncheckedColor = PocketShellColors.TextSecondary,
            ),
        )
        Text(
            text = keyName,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyMono,
        )
    }
}

@Composable
private fun envFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = PocketShellColors.Text,
    unfocusedTextColor = PocketShellColors.Text,
    focusedContainerColor = PocketShellColors.SurfaceElev,
    unfocusedContainerColor = PocketShellColors.SurfaceElev,
    focusedIndicatorColor = PocketShellColors.Accent,
    unfocusedIndicatorColor = PocketShellColors.BorderSoft,
    focusedLabelColor = PocketShellColors.Accent,
    unfocusedLabelColor = PocketShellColors.TextSecondary,
    cursorColor = PocketShellColors.Accent,
)

// Test tags for the unit / connected E2E suite.
const val ENV_SCREEN_TAG: String = "env:screen"
const val ENV_BACK_TAG: String = "env:back"
const val ENV_TITLE_TAG: String = "env:title"
const val ENV_BANNER_TAG: String = "env:banner"
const val ENV_LOADING_TAG: String = "env:loading"
const val ENV_ERROR_TAG: String = "env:error"
const val ENV_RETRY_TAG: String = "env:retry"
const val ENV_EMPTY_TAG: String = "env:empty"
const val ENV_EMPTY_ADD_TAG: String = "env:empty-add"
const val ENV_ADD_FAB_TAG: String = "env:add-fab"
const val ENV_EDIT_DIALOG_TAG: String = "env:edit-dialog"
const val ENV_EDIT_KEY_LABEL_TAG: String = "env:edit-key-label"
const val ENV_EDIT_LOADING_TAG: String = "env:edit-loading"
const val ENV_EDIT_VALUE_FIELD_TAG: String = "env:edit-value-field"
const val ENV_EDIT_TOGGLE_TAG: String = "env:edit-toggle"
const val ENV_EDIT_CONFIRM_TAG: String = "env:edit-confirm"
const val ENV_ADD_DIALOG_TAG: String = "env:add-dialog"
const val ENV_ADD_KEY_FIELD_TAG: String = "env:add-key-field"
const val ENV_ADD_VALUE_FIELD_TAG: String = "env:add-value-field"
const val ENV_ADD_CONFIRM_TAG: String = "env:add-confirm"
const val ENV_COPY_FROM_TAG: String = "env:copy-from"
const val ENV_COPY_SHEET_TAG: String = "env:copy-sheet"
const val ENV_COPY_CONFIRM_TAG: String = "env:copy-confirm"
const val ENV_COPY_SOURCE_ERROR_TAG: String = "env:copy-source-error"
const val ENV_COPY_SOURCE_EMPTY_TAG: String = "env:copy-source-empty"

/**
 * The LazyColumn item key for an env row. Must be unique across the whole
 * list or Compose hard-crashes ("Key '…' was already used"). We key on the
 * row's pre-disambiguated [EnvKeyUiRow.id] (stable identity, not the mutable
 * `file:key` string) so two rows sharing the same (file, key) — a repeated
 * key in a real `.env`, or the old + edited entry during an in-place edit —
 * never collide.
 */
fun envRowItemKey(row: EnvKeyUiRow): String = row.id

fun envKeyRowTestTag(key: String): String = "env:key:$key"
fun envKeyMenuTestTag(key: String): String = "env:menu:$key"
fun envKeyRevealTestTag(key: String): String = "env:reveal:$key"
fun envKeyHideTestTag(key: String): String = "env:hide:$key"
fun envKeyEditTestTag(key: String): String = "env:edit:$key"
fun envFileTargetTestTag(target: EnvFileTarget): String = "env:file-target:${target.fileName}"
fun envCopySourceTestTag(path: String): String = "env:copy-source:$path"
fun envCopyKeyTestTag(key: String): String = "env:copy-key:$key"
