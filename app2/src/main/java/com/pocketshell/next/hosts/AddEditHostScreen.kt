package com.pocketshell.next.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the form. */
const val HOST_FORM_NAME_TAG: String = "host-form-name"
const val HOST_FORM_HOSTNAME_TAG: String = "host-form-hostname"
const val HOST_FORM_PORT_TAG: String = "host-form-port"
const val HOST_FORM_USERNAME_TAG: String = "host-form-username"
const val HOST_FORM_KEY_TAG: String = "host-form-key"
const val HOST_FORM_SAVE_TAG: String = "host-form-save"

/**
 * Route-level entry point for the add/edit host form.
 *
 * [hostId] comes from the route; `null` means Add. It is handed to
 * [AddEditHostViewModel.bind] unconditionally on every change of the value —
 * including to `null` — which is the navigation half of the F1 fix documented
 * on the ViewModel: entering Add must actively clear the previous target, not
 * merely fail to set a new one.
 */
@Composable
fun AddEditHostRoute(
    hostId: Long?,
    onDone: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditHostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keys by viewModel.sshKeys.collectAsState()

    LaunchedEffect(hostId) { viewModel.bind(hostId) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onDone()
        }
    }

    AddEditHostScreen(
        state = state,
        keys = keys,
        onChange = viewModel::update,
        onSave = viewModel::save,
        onCancel = onDone,
        onAddKey = onAddKey,
        modifier = modifier,
    )
}

/**
 * The add/edit host form (rewrite task P-6) — app2's only hand-entry path for a
 * host, and therefore the screen a fresh install cannot start without.
 *
 * Five fields, matching the columns the user actually owns on `hosts`: name,
 * hostname, port, username, key. Everything else on that table is a cache the
 * connect/bootstrap paths fill in, so putting it on a form would be asking the
 * user to hand-maintain derived state.
 *
 * Validation is surfaced per field rather than as one banner, because "which
 * field is wrong" is the entire question a rejected submit has to answer. The
 * port field is the only one that can be wrong while non-empty, so it is the
 * one with a real message rather than "Required".
 */
@Composable
fun AddEditHostScreen(
    state: HostFormState,
    keys: List<SshKeyEntity>,
    onChange: ((HostFormState) -> HostFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = if (state.editing) "Edit host" else "Add host",
            trailing = {
                PocketShellButton(
                    text = "Cancel",
                    onClick = onCancel,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )

        if (state.loading) {
            LoadingIndicator.Spinner(label = "Loading host…")
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            FormField(
                label = "Name",
                value = state.name,
                error = state.errors.name,
                testTag = HOST_FORM_NAME_TAG,
                onValueChange = { value -> onChange { it.copy(name = value) } },
            )
            FormField(
                label = "Hostname or IP",
                value = state.hostname,
                error = state.errors.hostname,
                testTag = HOST_FORM_HOSTNAME_TAG,
                onValueChange = { value -> onChange { it.copy(hostname = value) } },
            )
            FormField(
                label = "Port",
                value = state.port,
                error = state.errors.port,
                testTag = HOST_FORM_PORT_TAG,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onChange { it.copy(port = value) } },
            )
            FormField(
                label = "Username",
                value = state.username,
                error = state.errors.username,
                testTag = HOST_FORM_USERNAME_TAG,
                onValueChange = { value -> onChange { it.copy(username = value) } },
            )

            KeyPicker(
                keys = keys,
                selectedKeyId = state.selectedKeyId,
                error = state.errors.key,
                onSelect = { keyId -> onChange { it.copy(selectedKeyId = keyId) } },
                onAddKey = onAddKey,
            )

            if (keys.isEmpty()) {
                // A precondition, not a field error: there is nothing the user
                // can type into this form to fix it.
                Banner(
                    text = "Add an SSH key before saving a host.",
                    role = BannerRole.Warning,
                    trailingContent = {
                        PocketShellButton(
                            text = "Add key",
                            onClick = onAddKey,
                            variant = ButtonVariant.Text,
                            compact = true,
                        )
                    },
                )
            }

            PocketShellButton(
                text = if (state.editing) "Save changes" else "Add host",
                onClick = onSave,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HOST_FORM_SAVE_TAG),
            )
        }
    }
}

/**
 * One labelled text field plus its error line.
 *
 * The submit button stays enabled even when the form is invalid: disabling it
 * would leave the user tapping a dead control with nothing telling them which
 * field is at fault. Tapping it runs validation and paints the errors.
 */
@Composable
private fun FormField(
    label: String,
    value: String,
    error: String?,
    testTag: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        )
        if (error != null) {
            Text(
                text = error,
                color = PocketShellColors.Red,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = PocketShellSpacing.md, top = 2.dp),
            )
        }
    }
}

/**
 * The SSH-key selector: a read-only field that opens a menu of registered keys,
 * with "Add a new key…" as the last entry so the dead end of "no keys yet" is
 * always one tap from being fixed.
 */
@Composable
private fun KeyPicker(
    keys: List<SshKeyEntity>,
    selectedKeyId: Long?,
    error: String?,
    onSelect: (Long) -> Unit,
    onAddKey: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = keys.firstOrNull { it.id == selectedKeyId }?.name

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = selectedName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("SSH key") },
                placeholder = { Text("Choose a key") },
                singleLine = true,
                isError = error != null,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOST_FORM_KEY_TAG),
            )
            PocketShellButton(
                text = "Choose",
                onClick = { expanded = true },
                variant = ButtonVariant.Secondary,
                compact = true,
                modifier = Modifier.padding(start = PocketShellSpacing.sm),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.name) },
                    onClick = {
                        expanded = false
                        onSelect(key.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Add a new key…") },
                onClick = {
                    expanded = false
                    onAddKey()
                },
            )
        }
        if (error != null) {
            Text(
                text = error,
                color = PocketShellColors.Red,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = PocketShellSpacing.md, top = 2.dp),
            )
        }
    }
}
