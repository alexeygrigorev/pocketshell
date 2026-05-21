package com.pocketshell.app.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Add a new host (when [hostId] is `null`) or edit an existing one. The
 * brief calls for "name, hostname, port, username, key selector"; this
 * screen lays them out as a single vertical column inside a scroll
 * container, with a save button pinned at the bottom of the scroll.
 *
 * The key selector dropdown reads from [AddEditHostViewModel.sshKeys];
 * if the user has not registered any keys yet, the dropdown shows a
 * single disabled "no keys — go to Keys" hint and Save fails with an
 * inline error. Key creation happens on the dedicated [SshKeysScreen],
 * not here — keeps each screen's responsibility narrow.
 *
 * Save success → [AddEditHostViewModel.state.saved] flips to `true` →
 * [onDone] is invoked to pop the screen.
 */
@Composable
fun AddEditHostScreen(
    hostId: Long?,
    onDone: () -> Unit,
    onManageKeys: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditHostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()

    LaunchedEffect(hostId) {
        if (hostId != null) viewModel.loadHost(hostId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FormAppBar(
                title = if (hostId == null) "Add host" else "Edit host",
                onBack = onDone,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FormField(
                    label = "Name",
                    value = state.name,
                    onValueChange = { v -> viewModel.updateState { it.copy(name = v) } },
                )

                FormField(
                    label = "Hostname / IP",
                    value = state.hostname,
                    onValueChange = { v -> viewModel.updateState { it.copy(hostname = v) } },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        label = "Port",
                        value = state.port,
                        onValueChange = { v -> viewModel.updateState { it.copy(port = v) } },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                    FormField(
                        label = "Username",
                        value = state.username,
                        onValueChange = { v -> viewModel.updateState { it.copy(username = v) } },
                        modifier = Modifier.weight(2f),
                    )
                }

                KeySelector(
                    selectedKeyId = state.selectedKeyId,
                    keys = sshKeys.map { it.id to it.name },
                    onSelect = { id -> viewModel.updateState { it.copy(selectedKeyId = id) } },
                    onManageKeys = onManageKeys,
                )

                state.error?.let { err ->
                    Text(
                        text = err,
                        color = PocketShellColors.Red,
                        fontSize = 13.sp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.OnAccent,
                    ),
                ) {
                    Text(
                        text = if (hostId == null) "Add host" else "Save changes",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Top app bar matching the dashboard's appbar height + chrome but with a
 * back affordance instead of a title-only layout.
 */
@Composable
private fun FormAppBar(title: String, onBack: () -> Unit) {
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
            text = title,
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Thin wrapper around [OutlinedTextField] that pre-applies the
 * PocketShell colour scheme so every form field reads consistently.
 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PocketShellColors.Text,
            unfocusedTextColor = PocketShellColors.Text,
            focusedBorderColor = PocketShellColors.Accent,
            unfocusedBorderColor = PocketShellColors.Border,
            focusedLabelColor = PocketShellColors.Accent,
            unfocusedLabelColor = PocketShellColors.TextSecondary,
            cursorColor = PocketShellColors.Accent,
            focusedContainerColor = PocketShellColors.Surface,
            unfocusedContainerColor = PocketShellColors.Surface,
        ),
    )
}

/**
 * Key-selector dropdown. Renders the currently-selected key's name (or a
 * placeholder if nothing is selected) in a tappable surface; tapping
 * opens a [DropdownMenu] with one item per registered key plus a trailing
 * "Manage keys…" entry that routes to [SshKeysScreen].
 *
 * Material3's `ExposedDropdownMenuBox` is the canonical pattern but
 * pulls in `material3-adaptive` for full anchoring on some toolchains;
 * a hand-rolled tap-target + `DropdownMenu` is functionally equivalent
 * for this single-line use and keeps the dependency footprint flat
 * (issue #18 forbids new catalog entries).
 */
@Composable
private fun KeySelector(
    selectedKeyId: Long?,
    keys: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    onManageKeys: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = keys.firstOrNull { it.first == selectedKeyId }?.second

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "SSH key",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = selectedName ?: "Select an SSH key",
                color = if (selectedName != null)
                    PocketShellColors.Text else PocketShellColors.TextMuted,
                fontSize = 14.sp,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(PocketShellColors.Surface),
            ) {
                if (keys.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "No keys yet — tap Manage keys",
                                color = PocketShellColors.TextMuted,
                            )
                        },
                        onClick = {
                            expanded = false
                            onManageKeys()
                        },
                    )
                } else {
                    keys.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name, color = PocketShellColors.Text) },
                            onClick = {
                                onSelect(id)
                                expanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Manage keys…",
                                color = PocketShellColors.Accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        onClick = {
                            expanded = false
                            onManageKeys()
                        },
                    )
                }
            }
        }
    }
}
