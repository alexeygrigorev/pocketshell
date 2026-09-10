package com.pocketshell.next.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.QuietChoiceRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags. */
const val SSH_KEYS_LIST_TAG: String = "ssh-keys-list"
const val SSH_KEYS_GENERATE_TAG: String = "ssh-keys-generate"
const val SSH_KEYS_IMPORT_TAG: String = "ssh-keys-import"
const val SSH_KEYS_PASTE_FIELD_TAG: String = "ssh-keys-paste-field"
const val SSH_KEYS_IMPORT_CONFIRM_TAG: String = "ssh-keys-import-confirm"
const val SSH_KEYS_PASTE_VISIBILITY_TAG: String = "ssh-keys-paste-visibility"
const val SSH_KEYS_COPY_PUBLIC_KEY_TAG: String = "ssh-keys-copy-public-key"
const val SSH_KEYS_COPY_FINGERPRINT_TAG: String = "ssh-keys-copy-fingerprint"
const val SSH_KEYS_DETAIL_TAG: String = "ssh-keys-detail"
const val SSH_KEYS_GENERATE_CONFIRM_TAG: String = "ssh-keys-generate-confirm"
const val SSH_KEYS_GENERATE_ED25519_TAG: String = "ssh-keys-generate-ed25519"
const val SSH_KEYS_GENERATE_RSA_TAG: String = "ssh-keys-generate-rsa"
const val SSH_KEYS_GENERATE_NO_PASSPHRASE_TAG: String = "ssh-keys-generate-no-passphrase"
const val SSH_KEYS_GENERATE_PASSPHRASE_TAG: String = "ssh-keys-generate-passphrase"
const val SSH_KEYS_IMPORT_REVIEW_TAG: String = "ssh-keys-import-review"
const val SSH_KEYS_IMPORT_REVIEW_CONFIRM_TAG: String = "ssh-keys-import-review-confirm"
const val SSH_KEYS_IMPORT_FILE_TAG: String = "ssh-keys-import-file"
const val SSH_KEYS_GENERATE_NAME_TAG: String = "ssh-keys-generate-name"
const val SSH_KEYS_GENERATE_PASSPHRASE_FIELD_TAG: String = "ssh-keys-generate-passphrase-field"
const val SSH_KEYS_GENERATE_CONFIRMATION_FIELD_TAG: String = "ssh-keys-generate-confirmation-field"
const val SSH_KEYS_IMPORT_NAME_TAG: String = "ssh-keys-import-name"

fun sshKeyRowTag(keyId: Long): String = "ssh-key-row-$keyId"

/** A private key held briefly while the user reviews its metadata. */
data class SshKeyImportCandidate(
    val name: String,
    val pem: String,
)

/** In-route pages keep the host-form return context in the existing route. */
private enum class SshKeysPage {
    LIST,
    GENERATE,
    IMPORT,
    IMPORT_REVIEW,
    DETAIL,
}

/**
 * Route-level entry point for the key manager.
 *
 * The one thing that cannot live in the ViewModel is reading a file the user
 * picked, which needs a `ContentResolver`. It is read here and handed over as
 * text, so [SshKeysViewModel] stays Android-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var fileImportCandidate by remember { mutableStateOf<SshKeyImportCandidate?>(null) }
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
        fileImportCandidate = SshKeyImportCandidate(name = name, pem = text.orEmpty())
    }

    if (!unlocked) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(PocketShellColors.Background),
        ) {
            ScreenHeader(
                title = "SSH keys",
                onBack = onBack,
            )
        }
        SshKeysUnlockSheet(
            error = fallbackError ?: unlockError,
            inFlight = unlockInFlight,
            deviceUnlockAvailable = deviceUnlockAvailable,
            protectedKeys = protectedKeys,
            selectedKeyId = fallbackKeyId,
            fallbackPassphrase = fallbackPassphrase,
            fallbackInFlight = fallbackInFlight,
            onUnlock = {
                if (unlockGate.tryMarkInFlight()) {
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
                }
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
                fallbackKeyId?.let { keyId ->
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
                }
            },
            onDismiss = onBack,
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
            initialImportCandidate = fileImportCandidate,
            onInitialImportConsumed = { fileImportCandidate = null },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeysUnlockSheet(
    error: String?,
    inFlight: Boolean,
    deviceUnlockAvailable: Boolean,
    protectedKeys: List<SshKeyRow>,
    selectedKeyId: Long?,
    fallbackPassphrase: String,
    fallbackInFlight: Boolean,
    onUnlock: () -> Unit,
    onSelectKey: (Long) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onUnlockWithPassphrase: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = PocketShellSpacing.xxl)
                    .padding(bottom = PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
            ) {
                SheetHeader(title = "Unlock SSH keys", onClose = onDismiss)
                Banner(
                    text = "Device authentication protects private-key details. " +
                        "If device unlock is unavailable, canceled, or fails, verify the passphrase " +
                        "for one encrypted key below.",
                    role = BannerRole.Info,
                )
                if (deviceUnlockAvailable) {
                    PocketShellButton(
                        text = if (inFlight) "Waiting for device unlock…" else "Unlock with device",
                        onClick = onUnlock,
                        enabled = !inFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SSH_KEYS_UNLOCK_BUTTON_TAG),
                        variant = ButtonVariant.Primary,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SSH_KEYS_PASSPHRASE_FALLBACK_TAG),
                    verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                ) {
                    SshKeysFieldLabel("Passphrase fallback")
                    if (protectedKeys.isEmpty()) {
                        Text(
                            text = "No passphrase-protected SSH key is available for fallback.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.metadata,
                        )
                    } else {
                        Text(
                            text = "Choose the encrypted key whose passphrase you want to verify.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.metadata,
                        )
                        protectedKeys.forEach { key ->
                            QuietChoiceRow(
                                title = key.name,
                                subtitle = "Passphrase protected",
                                selected = selectedKeyId == key.id,
                                onClick = { onSelectKey(key.id) },
                                modifier = Modifier.testTag(sshKeyFallbackRowTag(key.id)),
                            )
                        }
                        SshKeysFieldLabel("Key passphrase")
                        OutlinedTextField(
                            value = fallbackPassphrase,
                            onValueChange = onPassphraseChange,
                            placeholder = { Text("Enter passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = selectedKeyId != null && !fallbackInFlight,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (selectedKeyId != null && fallbackPassphrase.isNotEmpty() && !fallbackInFlight) {
                                        onUnlockWithPassphrase()
                                    }
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sshKeysFieldDescription("Key passphrase")
                                .testTag(SSH_KEYS_FALLBACK_FIELD_TAG),
                        )
                        PocketShellButton(
                            text = if (fallbackInFlight) "Checking passphrase…" else "Unlock with passphrase",
                            onClick = onUnlockWithPassphrase,
                            enabled = selectedKeyId != null &&
                                fallbackPassphrase.isNotEmpty() &&
                                !fallbackInFlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SSH_KEYS_FALLBACK_SUBMIT_TAG),
                        )
                    }
                }
                error?.let {
                    Banner(text = it, role = BannerRole.Warning)
                }
            }
        }
    }
}

/**
 * Registered SSH keys, with the two ways to add one (rewrite task P-6).
 *
 * The list and its add actions share one scroll container. Generate and import
 * open focused pages in this route; encrypted keys remain exactly as supplied.
 * Android's native device prompt gates this route when the device offers one.
 * A key passphrase is requested only by the detail action that needs to parse a
 * protected private key, and is scrubbed after the public half has been derived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeysScreen(
    state: SshKeysUiState,
    onBack: () -> Unit,
    onUseKey: ((Long) -> Unit)? = null,
    onGenerate: (SshKeyGenerationRequest) -> Unit,
    onImportPasted: (name: String, pem: String) -> Unit,
    onPickFile: () -> Unit,
    onDelete: (Long) -> Unit,
    onLoadPublicKey: (Long, CharArray?) -> Unit = { _, _ -> },
    onDismissMessage: () -> Unit,
    onCopyPublicKey: ((String) -> Unit)? = null,
    onCopyFingerprint: ((String) -> Unit)? = null,
    initialImportCandidate: SshKeyImportCandidate? = null,
    onInitialImportConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalContext.current.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val composeClipboard = LocalClipboardManager.current
    val copyPublicKey: (String) -> Unit = { value ->
        clipboard?.setPrimaryClip(ClipData.newPlainText("SSH public key", value))
        runCatching { composeClipboard.setText(AnnotatedString(value)) }
    }
    val copyFingerprint: (String) -> Unit = { value ->
        clipboard?.setPrimaryClip(ClipData.newPlainText("SSH key fingerprint", value))
        runCatching { composeClipboard.setText(AnnotatedString(value)) }
    }
    val copyPublicKeyAction = onCopyPublicKey ?: copyPublicKey
    val copyFingerprintAction = onCopyFingerprint ?: copyFingerprint
    var copiedKeyId by remember { mutableStateOf<Long?>(null) }
    var copiedFingerprintKeyId by remember { mutableStateOf<Long?>(null) }
    var page by remember { mutableStateOf(SshKeysPage.LIST) }
    var generateName by remember { mutableStateOf("") }
    var generateType by remember { mutableStateOf(SshKeyGenerationType.ED25519) }
    var generateProtection by remember { mutableStateOf(SshKeyProtection.NONE) }
    var generatePassphrase by remember { mutableStateOf("") }
    var generateConfirmation by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }
    var importPem by remember { mutableStateOf("") }
    var importRevealed by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SshKeyRow?>(null) }
    var pendingImport by remember { mutableStateOf<SshKeyImportCandidate?>(null) }
    var selectedKeyId by remember { mutableStateOf<Long?>(null) }
    val selectedDetail = state.keys.firstOrNull { it.id == selectedKeyId }

    BackHandler(enabled = page != SshKeysPage.LIST && pendingDelete == null) {
        when (page) {
            SshKeysPage.GENERATE,
            SshKeysPage.IMPORT,
            SshKeysPage.DETAIL,
            -> {
                selectedKeyId = null
                page = SshKeysPage.LIST
            }

            SshKeysPage.IMPORT_REVIEW -> page = SshKeysPage.IMPORT
            SshKeysPage.LIST -> Unit
        }
    }

    LaunchedEffect(selectedKeyId) {
        selectedKeyId?.let { onLoadPublicKey(it, null) }
    }

    LaunchedEffect(initialImportCandidate) {
        initialImportCandidate?.let { candidate ->
            importName = candidate.name
            importPem = candidate.pem
            importRevealed = false
            pendingImport = null
            page = SshKeysPage.IMPORT
            onInitialImportConsumed()
        }
    }

    LaunchedEffect(state.keys.map { it.id }) {
        if (selectedKeyId != null && selectedDetail == null) {
            selectedKeyId = null
            page = SshKeysPage.LIST
        }
    }

    when (page) {
        SshKeysPage.LIST -> SshKeysListPage(
            state = state,
            onBack = onBack,
            onDismissMessage = onDismissMessage,
            onGenerate = { page = SshKeysPage.GENERATE },
            onImport = { page = SshKeysPage.IMPORT },
            onSelectKey = {
                selectedKeyId = it
                page = SshKeysPage.DETAIL
            },
            onDelete = { pendingDelete = it },
            modifier = modifier,
        )

        SshKeysPage.GENERATE -> SshKeysGeneratePage(
            name = generateName,
            onNameChange = { generateName = it },
            type = generateType,
            onTypeChange = { generateType = it },
            protection = generateProtection,
            onProtectionChange = {
                generateProtection = it
                if (it == SshKeyProtection.NONE) {
                    generatePassphrase = ""
                    generateConfirmation = ""
                }
            },
            passphrase = generatePassphrase,
            onPassphraseChange = { generatePassphrase = it },
            confirmation = generateConfirmation,
            onConfirmationChange = { generateConfirmation = it },
            generating = state.generating,
            onBack = { page = SshKeysPage.LIST },
            onConfirm = {
                val request = SshKeyGenerationRequest(
                    name = generateName,
                    type = generateType,
                    protection = generateProtection,
                    passphrase = generatePassphrase
                        .takeIf { generateProtection == SshKeyProtection.PASSPHRASE }
                        ?.toCharArray(),
                )
                generateName = ""
                generatePassphrase = ""
                generateConfirmation = ""
                page = SshKeysPage.LIST
                onGenerate(request)
            },
            modifier = modifier,
        )

        SshKeysPage.IMPORT -> SshKeysImportPage(
            name = importName,
            onNameChange = { importName = it },
            pem = importPem,
            onPemChange = { importPem = it },
            revealed = importRevealed,
            onToggleRevealed = { importRevealed = !importRevealed },
            onBack = {
                importName = ""
                importPem = ""
                page = SshKeysPage.LIST
            },
            onPickFile = onPickFile,
            onReview = {
                pendingImport = SshKeyImportCandidate(name = importName, pem = importPem)
                page = SshKeysPage.IMPORT_REVIEW
            },
            modifier = modifier,
        )

        SshKeysPage.IMPORT_REVIEW -> pendingImport?.let { candidate ->
            SshKeysImportReviewPage(
                candidate = candidate,
                onBack = { page = SshKeysPage.IMPORT },
                onConfirm = {
                    pendingImport = null
                    importName = ""
                    importPem = ""
                    importRevealed = false
                    page = SshKeysPage.LIST
                    onImportPasted(candidate.name, candidate.pem)
                },
                modifier = modifier,
            )
        }

        SshKeysPage.DETAIL -> selectedDetail?.let { key ->
            SshKeyDetailPage(
                key = key,
                copiedKeyId = copiedKeyId,
                copiedFingerprintKeyId = copiedFingerprintKeyId,
                onBack = {
                    selectedKeyId = null
                    page = SshKeysPage.LIST
                },
                onCopyPublicKey = { value ->
                    copiedKeyId = key.id
                    copyPublicKeyAction(value)
                },
                onCopyFingerprint = { value ->
                    copiedFingerprintKeyId = key.id
                    copyFingerprintAction(value)
                },
                onLoadPublicKey = onLoadPublicKey,
                onUseKey = onUseKey?.let { useKey ->
                    {
                        selectedKeyId = null
                        page = SshKeysPage.LIST
                        useKey(key.id)
                    }
                },
                onRemove = { pendingDelete = key },
                modifier = modifier,
            )
        }
    }

    pendingDelete?.let { key ->
        ConfirmDialog(
            title = "Delete ${key.name}?",
            message = buildString {
                if (key.dependentHostNames.isEmpty()) {
                    append("No configured hosts use this key. ")
                } else {
                    append("Used by: ${key.dependentHostNames.joinToString()}. ")
                    append("Those hosts will also be removed from this device. ")
                }
                append("The authorized key on the server is not changed.")
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                selectedKeyId = null
                page = SshKeysPage.LIST
                onDelete(key.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SshKeysListPage(
    state: SshKeysUiState,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onSelectKey: (Long) -> Unit,
    onDelete: (SshKeyRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "SSH keys",
            onBack = onBack,
        )

        state.message?.let { message ->
            Column(modifier = Modifier.padding(horizontal = PocketShellSpacing.xl)) {
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

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(SSH_KEYS_LIST_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
        ) {
            when {
                !state.loaded -> item {
                    Text(
                        text = "Loading SSH keys…",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.metadata,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
                    )
                }

                state.keys.isEmpty() -> item {
                    EmptyState(
                        title = "No SSH keys yet",
                        description = "Generate one, or paste a key you already use.",
                    )
                }

                else -> {
                    item {
                        SectionHeader(label = "Keys", count = state.keys.size)
                    }
                    items(items = state.keys, key = { it.id }) { key ->
                        ListRow(
                            title = key.name,
                            subtitle = listOfNotNull(
                                key.algorithm ?: key.publicKey?.let(SshKeyMaterial::keyAlgorithmLabel)
                                    ?: "SSH key",
                                key.dependentHostNames.takeIf { it.isNotEmpty() }?.let { hosts ->
                                    "Used by ${hosts.joinToString() }"
                                } ?: "Not assigned",
                            ).joinToString(" · "),
                            trailing = {
                                Kebab(
                                    items = listOf(
                                        KebabItem(
                                            label = "Delete",
                                            onClick = { onDelete(key) },
                                        ),
                                    ),
                                )
                            },
                            onClick = { onSelectKey(key.id) },
                            modifier = Modifier.testTag(sshKeyRowTag(key.id)),
                        )
                    }
                }
            }

            if (state.keys.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(PocketShellDensity.sectionGap))
                }
            }
            item {
                ListRow(
                    title = if (state.generating) "Generating…" else "Generate a key",
                    subtitle = "Create on this device",
                    onClick = onGenerate,
                    modifier = Modifier.testTag(SSH_KEYS_GENERATE_TAG),
                )
            }
            item {
                ListRow(
                    title = "Import a key",
                    subtitle = "Paste or select a file",
                    onClick = onImport,
                    modifier = Modifier.testTag(SSH_KEYS_IMPORT_TAG),
                )
            }
        }
    }
}

/** Page shell with a scrolling body and an action area above IME/nav insets. */
@Composable
private fun SshKeysPageLayout(
    title: String,
    onBack: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    confirmTestTag: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    pageTestTag: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val pageModifier = pageTestTag?.let { modifier.testTag(it) } ?: modifier
    Column(
        modifier = pageModifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = title,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            content = body,
        )
        HorizontalDivider(color = PocketShellColors.BorderSoft)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.md),
        ) {
            PocketShellButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(confirmTestTag),
            )
        }
    }
}

/** Inset prose and fields while allowing shared rows to own their 20dp gutter. */
@Composable
private fun SshKeysPageInset(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        content = content,
    )
}

@Composable
private fun SshKeysFieldLabel(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        style = PocketShellType.label,
    )
}

private fun Modifier.sshKeysFieldDescription(label: String): Modifier = semantics {
    contentDescription = label
}

@Composable
private fun SshKeysGeneratePage(
    name: String,
    onNameChange: (String) -> Unit,
    type: SshKeyGenerationType,
    onTypeChange: (SshKeyGenerationType) -> Unit,
    protection: SshKeyProtection,
    onProtectionChange: (SshKeyProtection) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirmation: String,
    onConfirmationChange: (String) -> Unit,
    generating: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val protectionValid = protection == SshKeyProtection.NONE ||
        (passphrase.isNotEmpty() && passphrase == confirmation)
    val submit = {
        if (protectionValid && !generating) onConfirm()
    }
    SshKeysPageLayout(
        title = "Generate key",
        onBack = onBack,
        confirmLabel = if (generating) "Generating…" else "Generate key",
        confirmEnabled = protectionValid && !generating,
        confirmTestTag = SSH_KEYS_GENERATE_CONFIRM_TAG,
        onConfirm = onConfirm,
        modifier = modifier,
    ) {
        SshKeysPageInset {
            Text(
                text = "Create a new key stored on this device. The private key stays local.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
            SshKeysFieldLabel("Key name")
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .sshKeysFieldDescription("Key name")
                    .testTag(SSH_KEYS_GENERATE_NAME_TAG),
            )
        }
        SshKeysPageInset {
            SshKeysFieldLabel("Key type")
        }
        QuietChoiceRow(
            title = SshKeyGenerationType.ED25519.label,
            subtitle = SshKeyGenerationType.ED25519.description,
            selected = type == SshKeyGenerationType.ED25519,
            onClick = { onTypeChange(SshKeyGenerationType.ED25519) },
            modifier = Modifier.testTag(SSH_KEYS_GENERATE_ED25519_TAG),
        )
        QuietChoiceRow(
            title = SshKeyGenerationType.RSA.label,
            subtitle = SshKeyGenerationType.RSA.description,
            selected = type == SshKeyGenerationType.RSA,
            onClick = { onTypeChange(SshKeyGenerationType.RSA) },
            modifier = Modifier.testTag(SSH_KEYS_GENERATE_RSA_TAG),
        )
        SshKeysPageInset {
            SshKeysFieldLabel("Protection")
        }
        QuietChoiceRow(
            title = SshKeyProtection.NONE.label,
            subtitle = SshKeyProtection.NONE.description,
            selected = protection == SshKeyProtection.NONE,
            onClick = { onProtectionChange(SshKeyProtection.NONE) },
            modifier = Modifier.testTag(SSH_KEYS_GENERATE_NO_PASSPHRASE_TAG),
        )
        QuietChoiceRow(
            title = SshKeyProtection.PASSPHRASE.label,
            subtitle = SshKeyProtection.PASSPHRASE.description,
            selected = protection == SshKeyProtection.PASSPHRASE,
            onClick = { onProtectionChange(SshKeyProtection.PASSPHRASE) },
            modifier = Modifier.testTag(SSH_KEYS_GENERATE_PASSPHRASE_TAG),
        )
        if (protection == SshKeyProtection.PASSPHRASE) {
            SshKeysPageInset {
                SshKeysFieldLabel("Passphrase")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    placeholder = { Text("Recommended") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sshKeysFieldDescription("Passphrase")
                        .testTag(SSH_KEYS_GENERATE_PASSPHRASE_FIELD_TAG),
                )
                SshKeysFieldLabel("Confirm passphrase")
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    placeholder = { Text("Repeat passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sshKeysFieldDescription("Confirm passphrase")
                        .testTag(SSH_KEYS_GENERATE_CONFIRMATION_FIELD_TAG),
                )
            }
        }
        SshKeysPageInset {
            Text(
                text = "Add the public key to your server before connecting.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
        }
    }
}

@Composable
private fun SshKeysImportPage(
    name: String,
    onNameChange: (String) -> Unit,
    pem: String,
    onPemChange: (String) -> Unit,
    revealed: Boolean,
    onToggleRevealed: () -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parseError = remember(pem) {
        when {
            pem.isBlank() -> null
            !SshKeyMaterial.looksLikePrivateKey(pem) -> "Enter a complete private key."
            else -> runCatching { SshKeyMaterial.validatePrivateKey(pem) }
                .exceptionOrNull()
                ?.let { "This key could not be parsed on this device." }
        }
    }
    val reviewEnabled = pem.isNotBlank() && parseError == null
    SshKeysPageLayout(
        title = "Import key",
        onBack = onBack,
        confirmLabel = "Review key",
        confirmEnabled = reviewEnabled,
        confirmTestTag = SSH_KEYS_IMPORT_CONFIRM_TAG,
        onConfirm = onReview,
        modifier = modifier,
    ) {
        SshKeysPageInset {
            Text(
                text = "Import an existing key stored on this device.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
            SshKeysFieldLabel("Key name")
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .sshKeysFieldDescription("Key name")
                    .testTag(SSH_KEYS_IMPORT_NAME_TAG),
            )
            SshKeysFieldLabel("Private key")
            OutlinedTextField(
                value = pem,
                onValueChange = onPemChange,
                placeholder = { Text("Paste PEM or OpenSSH key") },
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    PocketShellButton(
                        text = if (revealed) "Hide" else "Show",
                        onClick = onToggleRevealed,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(SSH_KEYS_PASTE_VISIBILITY_TAG),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .sshKeysFieldDescription("Private key")
                    .testTag(SSH_KEYS_PASTE_FIELD_TAG),
            )
            parseError?.let { error ->
                Text(
                    text = error,
                    color = PocketShellColors.Red,
                    style = PocketShellType.metadata,
                )
            }
        }
        ListRow(
            title = "Choose a key file",
            subtitle = "Opens the Android file picker",
            onClick = onPickFile,
            modifier = Modifier.testTag(SSH_KEYS_IMPORT_FILE_TAG),
        )
        SshKeysPageInset {
            Text(
                text = "Key parsing stays on this device. Never include key text in logs or reports.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
        }
    }
}

@Composable
private fun SshKeysImportReviewPage(
    candidate: SshKeyImportCandidate,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = candidate.name.trim().ifEmpty { "imported-key" }
    val protection = if (SshKeyMaterial.isEncrypted(candidate.pem)) {
        "Passphrase protected"
    } else {
        "No passphrase"
    }
    SshKeysPageLayout(
        title = "Review key",
        onBack = onBack,
        confirmLabel = "Add key",
        confirmEnabled = candidate.pem.isNotBlank(),
        confirmTestTag = SSH_KEYS_IMPORT_REVIEW_CONFIRM_TAG,
        onConfirm = onConfirm,
        modifier = modifier,
        pageTestTag = SSH_KEYS_IMPORT_REVIEW_TAG,
    ) {
        SshKeysPageInset {
            Text(
                text = "Review before saving. The private key stays on this device and is not shown here.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
        }
        ListRow(
            title = name,
            subtitle = "Private key ready to validate and save",
        )
        ListRow(
            title = "Protection",
            subtitle = protection,
        )
    }
}

@Composable
private fun SshKeyDetailPage(
    key: SshKeyRow,
    copiedKeyId: Long?,
    copiedFingerprintKeyId: Long?,
    onBack: () -> Unit,
    onCopyPublicKey: (String) -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onLoadPublicKey: (Long, CharArray?) -> Unit,
    onUseKey: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = key.name,
            onBack = onBack,
        )
        SshKeyDetailContent(
            key = key,
            copiedKeyId = copiedKeyId,
            copiedFingerprintKeyId = copiedFingerprintKeyId,
            onClose = onBack,
            onCopyPublicKey = onCopyPublicKey,
            onCopyFingerprint = onCopyFingerprint,
            onLoadPublicKey = onLoadPublicKey,
            onUseKey = onUseKey,
            onRemove = onRemove,
            showHeader = false,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The key detail content can be rendered inside the route's page shell and on
 * its own, so the actions can be verified without relying on a platform
 * container animation in host-side tests.
 */
@Composable
internal fun SshKeyDetailContent(
    key: SshKeyRow,
    copiedKeyId: Long? = null,
    copiedFingerprintKeyId: Long? = null,
    onClose: () -> Unit,
    onCopyPublicKey: (String) -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onLoadPublicKey: (Long, CharArray?) -> Unit = { _, _ -> },
    onUseKey: (() -> Unit)? = null,
    onRemove: () -> Unit = {},
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    scrollable: Boolean = true,
) {
    val detailContentInset = if (showHeader) {
        Modifier
    } else {
        Modifier.padding(horizontal = PocketShellSpacing.xl)
    }
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = if (showHeader) PocketShellSpacing.lg else 0.dp)
            .padding(bottom = PocketShellSpacing.lg)
            .testTag(SSH_KEYS_DETAIL_TAG)
            .then(scrollModifier),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        if (showHeader) {
            SheetHeader(title = key.name, onClose = onClose)
        }
        val keyAlgorithm = key.algorithm
            ?: key.publicKey?.let(SshKeyMaterial::keyAlgorithmLabel)
        val publicFingerprint = key.publicFingerprint
            ?: key.publicKey?.let { publicKey ->
                runCatching { SshKeyMaterial.publicKeyFingerprint(publicKey) }.getOrNull()
            }
        ListRow(
            title = "Type",
            subtitle = keyAlgorithm ?: "Read the public key to identify",
        )
        ListRow(
            title = "Protection",
            subtitle = if (key.hasPassphrase) "Passphrase required" else "No passphrase",
        )
        ListRow(
            title = "Used by",
            subtitle = key.dependentHostNames.takeIf { it.isNotEmpty() }?.joinToString()
                ?: "No configured hosts",
        )
        if (publicFingerprint != null) {
            ListRow(title = "Fingerprint", subtitle = publicFingerprint)
            PocketShellButton(
                text = if (copiedFingerprintKeyId == key.id) "Fingerprint copied" else "Copy fingerprint",
                onClick = { onCopyFingerprint(publicFingerprint) },
                variant = ButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(detailContentInset)
                    .testTag(SSH_KEYS_COPY_FINGERPRINT_TAG),
            )
        } else if (key.fingerprint.isNotBlank()) {
            // The stored digest is for import deduplication. It is not
            // presented as the server-installable public-key identity until
            // the public half has been read.
            ListRow(title = "Stored key digest", subtitle = key.fingerprint)
        }
        when {
            key.publicKeyLoading -> Text(
                text = "Reading public key…",
                color = PocketShellColors.TextSecondary,
                modifier = detailContentInset,
            )

            key.publicKey != null -> {
                val publicKey = key.publicKey
                Text(
                    text = "Public key",
                    color = PocketShellColors.TextSecondary,
                    modifier = detailContentInset,
                )
                SelectionContainer {
                    Text(
                        text = publicKey,
                        color = PocketShellColors.Text,
                        modifier = detailContentInset,
                    )
                }
                PocketShellButton(
                    text = "Copy public key",
                    onClick = { onCopyPublicKey(publicKey) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(detailContentInset)
                        .testTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG),
                )
                if (copiedKeyId == key.id) {
                    Text(
                        text = "Copied public key",
                        color = PocketShellColors.TextSecondary,
                        modifier = detailContentInset,
                    )
                }
            }

            key.hasPassphrase -> KeyPassphraseField(
                key = key,
                onUnlock = { passphrase -> onLoadPublicKey(key.id, passphrase) },
                modifier = detailContentInset,
            )

            else -> Text(
                text = key.publicKeyError?.let {
                    "Could not read the public key. Try again."
                } ?: "The public key is unavailable because the private key file could not be read.",
                color = PocketShellColors.TextSecondary,
                modifier = detailContentInset,
            )
        }
        if (key.publicKeyError != null && !key.hasPassphrase) {
            PocketShellButton(
                text = "Retry reading public key",
                onClick = { onLoadPublicKey(key.id, null) },
                variant = ButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(detailContentInset),
            )
        }
        onUseKey?.let {
            PocketShellButton(
                text = "Use this key",
                onClick = it,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(detailContentInset),
            )
        }
        PocketShellButton(
            text = "Remove key from device",
            onClick = onRemove,
            variant = ButtonVariant.Destructive,
            modifier = Modifier
                .fillMaxWidth()
                .then(detailContentInset),
        )
    }
}

@Composable
private fun KeyPassphraseField(
    key: SshKeyRow,
    onUnlock: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember(key.id) { mutableStateOf("") }
    val submit = {
        if (passphrase.isNotEmpty()) {
            val chars = passphrase.toCharArray()
            passphrase = ""
            onUnlock(chars)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = "Enter the key passphrase to reveal its complete public key.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
        )
        SshKeysFieldLabel("Key passphrase")
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            placeholder = { Text("Enter passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .sshKeysFieldDescription("Key passphrase"),
        )
        key.publicKeyError?.let {
            Banner(
                text = "Could not unlock this key. Check the passphrase and try again.",
                role = BannerRole.Warning,
            )
        }
        PocketShellButton(
            text = "Show public key",
            enabled = passphrase.isNotEmpty(),
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
