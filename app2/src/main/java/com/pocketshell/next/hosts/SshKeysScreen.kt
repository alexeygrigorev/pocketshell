package com.pocketshell.next.hosts

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags. */
const val SSH_KEYS_LIST_TAG: String = "ssh-keys-list"
const val SSH_KEYS_GENERATE_TAG: String = "ssh-keys-generate"
const val SSH_KEYS_IMPORT_TAG: String = "ssh-keys-import"
const val SSH_KEYS_PASTE_FIELD_TAG: String = "ssh-keys-paste-field"
const val SSH_KEYS_PASTE_VISIBILITY_TAG: String = "ssh-keys-paste-visibility"
const val SSH_KEYS_COPY_PUBLIC_KEY_TAG: String = "ssh-keys-copy-public-key"

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
    onUseKey: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SshKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val deviceUnlockAvailable = remember(context) { isSshKeyUnlockRequired(context) }
    val protectedKeys = state.keys.filter { it.hasPassphrase }
    var unlocked by remember(context) {
        mutableStateOf(!deviceUnlockAvailable && protectedKeys.isEmpty())
    }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var unlockInFlight by remember { mutableStateOf(false) }
    var fallbackKeyId by remember { mutableStateOf<Long?>(null) }
    var fallbackPassphrase by remember { mutableStateOf("") }
    var fallbackInFlight by remember { mutableStateOf(false) }
    var fallbackError by remember { mutableStateOf<String?>(null) }
    val unlockGate = remember { SshKeyUnlockInFlightGate() }

    LaunchedEffect(deviceUnlockAvailable, state.loaded, protectedKeys.map { it.id }) {
        if (!deviceUnlockAvailable && state.loaded) {
            unlocked = protectedKeys.isEmpty()
        }
        if (fallbackKeyId !in protectedKeys.map { it.id }) {
            fallbackKeyId = protectedKeys.firstOrNull()?.id
        }
    }

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

    if (!unlocked) {
        SshKeyUnlockPanel(
            error = fallbackError ?: unlockError,
            inFlight = unlockInFlight,
            deviceUnlockAvailable = deviceUnlockAvailable,
            protectedKeys = protectedKeys,
            selectedKeyId = fallbackKeyId,
            fallbackPassphrase = fallbackPassphrase,
            fallbackInFlight = fallbackInFlight,
            modifier = modifier,
            onUnlock = {
                if (!unlockGate.tryMarkInFlight()) return@SshKeyUnlockPanel
                unlockInFlight = true
                unlockError = null
                launchSshKeyUnlock(
                    activity = context as? androidx.fragment.app.FragmentActivity,
                    onSuccess = {
                        unlockGate.clear()
                        unlockInFlight = false
                        unlocked = true
                    },
                    onError = {
                        unlockGate.clear()
                        unlockInFlight = false
                        unlockError = it
                    },
                    onFailure = {
                        unlockGate.clear()
                        unlockInFlight = false
                        unlockError = it
                    },
                )
            },
            onSelectKey = {
                fallbackKeyId = it
                fallbackError = null
            },
            onPassphraseChange = {
                fallbackPassphrase = it
                fallbackError = null
            },
            onUnlockWithPassphrase = {
                val keyId = fallbackKeyId ?: return@SshKeyUnlockPanel
                val chars = fallbackPassphrase.toCharArray()
                fallbackPassphrase = ""
                fallbackError = null
                fallbackInFlight = true
                viewModel.unlockWithPassphrase(keyId, chars) { success, error ->
                    fallbackInFlight = false
                    if (success) {
                        unlockError = null
                        unlocked = true
                    } else {
                        fallbackError = error
                    }
                }
            },
        )
    } else {
        SshKeysScreen(
            state = state,
            onBack = onBack,
            onUseKey = onUseKey,
            onGenerate = viewModel::generate,
            onImportPasted = viewModel::import,
            onPickFile = { filePicker.launch("*/*") },
            onDelete = { keyId -> viewModel.delete(keyId) },
            onLoadPublicKey = viewModel::loadPublicKey,
            onDismissMessage = viewModel::clearMessage,
            modifier = modifier,
        )
    }
}

/**
 * Registered SSH keys, with the two ways to add one (rewrite task P-6).
 *
 * Generate is listed first for a fresh install; import also retains encrypted
 * keys exactly as supplied. Android's native device prompt gates this route
 * when the device offers one. A key passphrase is requested only by the detail
 * action that needs to parse a protected private key, and is scrubbed after the
 * public half has been derived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeysScreen(
    state: SshKeysUiState,
    onBack: () -> Unit,
    onUseKey: ((Long) -> Unit)? = null,
    onGenerate: (String) -> Unit,
    onImportPasted: (name: String, pem: String) -> Unit,
    onPickFile: () -> Unit,
    onDelete: (Long) -> Unit,
    onLoadPublicKey: (Long, CharArray?) -> Unit = { _, _ -> },
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalContext.current
        .getSystemService(ClipboardManager::class.java)
    var copiedKeyId by remember { mutableStateOf<Long?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SshKeyRow?>(null) }
    var selectedKeyId by remember { mutableStateOf<Long?>(null) }
    val selectedDetail = state.keys.firstOrNull { it.id == selectedKeyId }

    LaunchedEffect(selectedKeyId) {
        selectedKeyId?.let { onLoadPublicKey(it, null) }
    }

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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            ListRow(
                title = if (state.generating) "Generating…" else "Generate a key",
                subtitle = "Create on this device",
                onClick = { showGenerate = true },
                modifier = Modifier.testTag(SSH_KEYS_GENERATE_TAG),
            )
            ListRow(
                title = "Import a key",
                subtitle = "Paste a key into the app",
                onClick = { showPaste = true },
                modifier = Modifier.testTag(SSH_KEYS_IMPORT_TAG),
            )
            ListRow(
                title = "Choose a key file",
                subtitle = "Opens the Android file picker",
                onClick = onPickFile,
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
                            // Keep the list quiet; the full value remains
                            // available in the detail sheet when it is real.
                            subtitle = key.fingerprint
                                .takeIf { it.isNotBlank() }
                                ?.takeLast(FINGERPRINT_TAIL),
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
                            onClick = { selectedKeyId = key.id },
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
            message = "Hosts using this key are removed from this device. " +
                "The authorized key on the server is not changed.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(key.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    selectedDetail?.let { key ->
        ModalBottomSheet(
            onDismissRequest = { selectedKeyId = null },
            containerColor = PocketShellColors.Surface,
            shape = PocketShellShapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PocketShellSpacing.lg)
                    .padding(bottom = PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                SheetHeader(title = key.name)
                ListRow(
                    title = "Protection",
                    subtitle = if (key.hasPassphrase) "Passphrase protected" else "No passphrase",
                )
                if (key.fingerprint.isNotBlank()) {
                    ListRow(title = "Fingerprint", subtitle = key.fingerprint)
                }
                when {
                    key.publicKeyLoading -> Text(
                        text = "Reading public key…",
                        color = PocketShellColors.TextSecondary,
                    )

                    key.publicKey != null -> {
                        val publicKey = key.publicKey
                        Text(
                            text = "Public key",
                            color = PocketShellColors.TextSecondary,
                        )
                        SelectionContainer {
                            Text(
                                text = key.publicKey,
                                color = PocketShellColors.Text,
                            )
                        }
                        PocketShellButton(
                            text = "Copy public key",
                            onClick = {
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("SSH public key", publicKey),
                                )
                                copiedKeyId = key.id
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG),
                        )
                        if (copiedKeyId == key.id) {
                            Text(
                                text = "Copied public key",
                                color = PocketShellColors.TextSecondary,
                            )
                        }
                    }

                    key.hasPassphrase -> KeyPassphraseField(
                        key = key,
                        onUnlock = { passphrase -> onLoadPublicKey(key.id, passphrase) },
                    )

                    else -> Text(
                        text = "The public key is unavailable because the private key file could not be read.",
                        color = PocketShellColors.TextSecondary,
                    )
                }
                onUseKey?.let { useKey ->
                    PocketShellButton(
                        text = "Use this key",
                        onClick = {
                            selectedKeyId = null
                            useKey(key.id)
                        },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PocketShellButton(
                    text = "Remove key from device",
                    onClick = {
                        selectedKeyId = null
                        pendingDelete = key
                    },
                    variant = ButtonVariant.Destructive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun KeyPassphraseField(
    key: SshKeyRow,
    onUnlock: (CharArray) -> Unit,
) {
    var passphrase by remember(key.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
        Text(
            text = "Enter the key passphrase to reveal its complete public key.",
            color = PocketShellColors.TextSecondary,
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Key passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        key.publicKeyError?.let {
            Banner(text = "Could not unlock this key. Check the passphrase and try again.", role = BannerRole.Warning)
        }
        PocketShellButton(
            text = "Show public key",
            enabled = passphrase.isNotEmpty(),
            onClick = {
                val chars = passphrase.toCharArray()
                passphrase = ""
                onUnlock(chars)
            },
            modifier = Modifier.fillMaxWidth(),
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
    var revealed by remember { mutableStateOf(false) }
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
                    modifier = Modifier.testTag(SSH_KEYS_PASTE_VISIBILITY_TAG),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .testTag(SSH_KEYS_PASTE_FIELD_TAG),
        )
    }
}

private const val FINGERPRINT_TAIL = 12
