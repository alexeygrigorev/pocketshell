package com.pocketshell.next.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FormDialog
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags. */
const val SSH_KEYS_LIST_TAG: String = "ssh-keys-list"
const val SSH_KEYS_GENERATE_TAG: String = "ssh-keys-generate"
const val SSH_KEYS_IMPORT_TAG: String = "ssh-keys-import"
const val SSH_KEYS_PASTE_FIELD_TAG: String = "ssh-keys-paste-field"

fun sshKeyRowTag(keyId: Long): String = "ssh-key-row-$keyId"

/**
 * Route-level entry point for the key manager.
 *
 * The one thing that cannot live in the ViewModel is reading a file the user
 * picked, which needs a `ContentResolver`. It is read here and handed over as
 * text, so [SshKeysViewModel] stays Android-free.
 */
@Composable
fun SshKeysRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SshKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) {
            viewModel.import(name, "")
        } else {
            viewModel.import(name, text)
        }
    }

    SshKeysScreen(
        state = state,
        onBack = onBack,
        onGenerate = viewModel::generate,
        onImportPasted = viewModel::import,
        onPickFile = { filePicker.launch("*/*") },
        onDelete = viewModel::delete,
        onDismissMessage = viewModel::clearMessage,
        modifier = modifier,
    )
}

/**
 * Registered SSH keys, with the two ways to add one (rewrite task P-6).
 *
 * Generate is the primary action, and is listed first, because it is the path a
 * fresh install should take: a key generated here is unencrypted by
 * construction, which is the only kind app2 can currently use — the biometric
 * passphrase-unlock half of P-6 was cut. Import is for the far commoner case of
 * a key that already exists on the user's dev box, pasted or picked from a file.
 *
 * There is no passphrase field anywhere on this screen on purpose. An encrypted
 * key is refused by [SshKeyStore.importKey] with an explanation, rather than
 * accepted and left to fail at dial time with the cause two screens away.
 */
@Composable
fun SshKeysScreen(
    state: SshKeysUiState,
    onBack: () -> Unit,
    onGenerate: (String) -> Unit,
    onImportPasted: (name: String, pem: String) -> Unit,
    onPickFile: () -> Unit,
    onDelete: (Long) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGenerate by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SshKeyRow?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "SSH keys",
            trailing = {
                PocketShellButton(
                    text = "Done",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )

        state.message?.let { message ->
            Column(modifier = Modifier.padding(horizontal = PocketShellSpacing.lg)) {
                Banner(
                    text = message,
                    role = BannerRole.Info,
                    trailingContent = {
                        PocketShellButton(
                            text = "Dismiss",
                            onClick = onDismissMessage,
                            variant = ButtonVariant.Text,
                            compact = true,
                        )
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            PocketShellButton(
                text = if (state.generating) "Generating…" else "Generate",
                onClick = { showGenerate = true },
                variant = ButtonVariant.Primary,
                enabled = !state.generating,
                modifier = Modifier.testTag(SSH_KEYS_GENERATE_TAG),
            )
            PocketShellButton(
                text = "Paste a key",
                onClick = { showPaste = true },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(SSH_KEYS_IMPORT_TAG),
            )
            PocketShellButton(
                text = "From file",
                onClick = onPickFile,
                variant = ButtonVariant.Text,
            )
        }

        when {
            !state.loaded -> Unit

            state.keys.isEmpty() -> EmptyState(
                title = "No SSH keys yet",
                description = "Generate one, or paste a key you already use.",
            )

            else -> {
                SectionHeader(label = "Keys", count = state.keys.size)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SSH_KEYS_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    items(items = state.keys, key = { it.id }) { key ->
                        ListRow(
                            title = key.name,
                            // The fingerprint's tail is enough to tell two keys
                            // apart at a glance; the full hash is noise on a
                            // phone-width row.
                            subtitle = key.fingerprint.takeLast(FINGERPRINT_TAIL),
                            trailing = {
                                Kebab(
                                    items = listOf(
                                        KebabItem(
                                            label = "Delete",
                                            onClick = { pendingDelete = key },
                                        ),
                                    ),
                                )
                            },
                            modifier = Modifier.testTag(sshKeyRowTag(key.id)),
                        )
                    }
                }
            }
        }
    }

    if (showGenerate) {
        NameDialog(
            title = "Generate a key",
            confirmLabel = "Generate",
            helper = "A new RSA-3072 key is created on this device. Leave the name " +
                "blank for a timestamped one.",
            onConfirm = { name ->
                showGenerate = false
                onGenerate(name)
            },
            onDismiss = { showGenerate = false },
        )
    }

    if (showPaste) {
        PasteKeyDialog(
            onConfirm = { name, pem ->
                showPaste = false
                onImportPasted(name, pem)
            },
            onDismiss = { showPaste = false },
        )
    }

    pendingDelete?.let { key ->
        ConfirmDialog(
            title = "Delete ${key.name}?",
            message = "Any host using this key is deleted with it. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(key.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    helper: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    FormDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(text = helper, color = PocketShellColors.TextSecondary)
    }
}

@Composable
private fun PasteKeyDialog(
    onConfirm: (name: String, pem: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    FormDialog(
        title = "Paste a private key",
        confirmLabel = "Add",
        confirmEnabled = pem.isNotBlank(),
        onConfirm = { onConfirm(name, pem) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pem,
            onValueChange = { pem = it },
            label = { Text("-----BEGIN ... PRIVATE KEY-----") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .testTag(SSH_KEYS_PASTE_FIELD_TAG),
        )
    }
}

private const val FINGERPRINT_TAIL = 12
