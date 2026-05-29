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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Per-folder env-file management screen — issue #264.
 *
 * Lists `.env` / `.envrc` keys for [directory] (masked values by
 * default, D24), and lets the user add/update a key (value via stdin,
 * never argv), reveal a value on demand (plain, no biometric), and copy
 * keys from another already-discovered folder.
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
                .clickable(role = Role.Button, onClick = onBack)
                .testTag(ENV_BACK_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "‹", color = PocketShellColors.TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
            Text(
                text = "Env · $folderLabel",
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(ENV_TITLE_TAG),
            )
            Text(text = hostName, color = PocketShellColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EnvBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(ENV_BANNER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EnvLoadingPanel() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(ENV_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
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
        Text(text = message, color = PocketShellColors.Text, fontSize = 14.sp)
        TextButton(onClick = onRetry, modifier = Modifier.testTag(ENV_RETRY_TAG)) {
            Text("Retry", color = PocketShellColors.Accent)
        }
    }
}

@Composable
private fun EnvKeyList(
    directory: String,
    keys: List<EnvKeyUiRow>,
    canCopy: Boolean,
    onReveal: (String) -> Unit,
    onHide: (String) -> Unit,
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
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (canCopy) {
            item {
                TextButton(onClick = onCopyFrom, modifier = Modifier.testTag(ENV_COPY_FROM_TAG)) {
                    Text("Copy keys from another folder", color = PocketShellColors.Accent, fontSize = 13.sp)
                }
            }
        }
        if (keys.isEmpty()) {
            item { EnvEmptyState() }
        } else {
            items(keys, key = { "${it.file}:${it.key}" }) { row ->
                EnvKeyCard(row = row, onReveal = onReveal, onHide = onHide)
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun EnvEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(ENV_EMPTY_TAG),
    ) {
        Text(text = "No env keys yet", color = PocketShellColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap + to add a key, or copy keys from another folder.",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EnvKeyCard(row: EnvKeyUiRow, onReveal: (String) -> Unit, onHide: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(envKeyRowTestTag(row.key)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.key,
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            FileTag(file = row.file)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val display = when {
                row.revealedValue != null -> row.revealedValue
                !row.hasValue -> "(empty)"
                else -> "••••••••"
            }
            Text(
                text = display,
                color = if (row.revealedValue != null) PocketShellColors.Green else PocketShellColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .testTag(envKeyValueTestTag(row.key)),
            )
            if (row.revealing) {
                CircularProgressIndicator(
                    color = PocketShellColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else if (row.revealedValue != null) {
                TextButton(
                    onClick = { onHide(row.key) },
                    modifier = Modifier.testTag(envKeyHideTestTag(row.key)),
                ) {
                    Text("Hide", color = PocketShellColors.Accent, fontSize = 12.sp)
                }
            } else if (row.hasValue) {
                TextButton(
                    onClick = { onReveal(row.key) },
                    modifier = Modifier.testTag(envKeyRevealTestTag(row.key)),
                ) {
                    Text("Reveal", color = PocketShellColors.Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FileTag(file: String) {
    Box(
        modifier = Modifier
            .background(PocketShellColors.Purple.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = file, color = PocketShellColors.Purple, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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
            TextButton(
                enabled = !busy,
                onClick = { onConfirm(key, value, target) },
                modifier = Modifier.testTag(ENV_ADD_CONFIRM_TAG),
            ) {
                Text("Save", color = PocketShellColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = PocketShellColors.TextSecondary) }
        },
        containerColor = PocketShellColors.Surface,
    )
}

@Composable
private fun FileTargetSelector(selected: EnvFileTarget, onSelect: (EnvFileTarget) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnvFileTarget.entries.forEach { target ->
            val isSelected = target == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) PocketShellColors.Accent else PocketShellColors.BorderSoft,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(role = Role.RadioButton) { onSelect(target) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(envFileTargetTestTag(target)),
            ) {
                Text(
                    text = target.fileName,
                    color = if (isSelected) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
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
            Text(
                text = "Copy keys from another folder",
                color = PocketShellColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            val source = selectedSource
            if (source == null) {
                Text("Pick a source folder:", color = PocketShellColors.TextSecondary, fontSize = 12.sp)
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                when (val ks = sourceKeys) {
                    null, EnvCopySourceKeys.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = PocketShellColors.Accent, modifier = Modifier.size(24.dp))
                    }
                    is EnvCopySourceKeys.Failed -> Text(
                        text = ks.message,
                        color = PocketShellColors.Red,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag(ENV_COPY_SOURCE_ERROR_TAG),
                    )
                    is EnvCopySourceKeys.Ready -> {
                        if (ks.keys.isEmpty()) {
                            Text(
                                text = "That folder has no env keys.",
                                color = PocketShellColors.TextSecondary,
                                fontSize = 13.sp,
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
                            Text("Into:", color = PocketShellColors.TextSecondary, fontSize = 12.sp)
                            FileTargetSelector(selected = target, onSelect = { target = it })
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PocketShellColors.TextSecondary)
                }
                if (source != null) {
                    TextButton(
                        enabled = checked.isNotEmpty(),
                        onClick = { onConfirm(source.path, checked.toList(), target) },
                        modifier = Modifier.testTag(ENV_COPY_CONFIRM_TAG),
                    ) {
                        Text("Copy ${checked.size}", color = PocketShellColors.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFolderRow(folder: EnvCopySourceFolder, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(envCopySourceTestTag(folder.path)),
    ) {
        Text(text = folder.label, color = PocketShellColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = folder.path,
            color = PocketShellColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
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
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
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
const val ENV_ADD_FAB_TAG: String = "env:add-fab"
const val ENV_ADD_DIALOG_TAG: String = "env:add-dialog"
const val ENV_ADD_KEY_FIELD_TAG: String = "env:add-key-field"
const val ENV_ADD_VALUE_FIELD_TAG: String = "env:add-value-field"
const val ENV_ADD_CONFIRM_TAG: String = "env:add-confirm"
const val ENV_COPY_FROM_TAG: String = "env:copy-from"
const val ENV_COPY_SHEET_TAG: String = "env:copy-sheet"
const val ENV_COPY_CONFIRM_TAG: String = "env:copy-confirm"
const val ENV_COPY_SOURCE_ERROR_TAG: String = "env:copy-source-error"
const val ENV_COPY_SOURCE_EMPTY_TAG: String = "env:copy-source-empty"

fun envKeyRowTestTag(key: String): String = "env:key:$key"
fun envKeyValueTestTag(key: String): String = "env:value:$key"
fun envKeyRevealTestTag(key: String): String = "env:reveal:$key"
fun envKeyHideTestTag(key: String): String = "env:hide:$key"
fun envFileTargetTestTag(target: EnvFileTarget): String = "env:file-target:${target.fileName}"
fun envCopySourceTestTag(path: String): String = "env:copy-source:$path"
fun envCopyKeyTestTag(key: String): String = "env:copy-key:$key"
