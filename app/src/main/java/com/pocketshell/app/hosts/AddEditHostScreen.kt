package com.pocketshell.app.hosts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.projects.ClaudeProfile
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Test tags exposed for the issue #111 Add Host instrumentation tests.
 *
 * Field tags follow the form `add-host-field-<name>`; the CTA tag is
 * `add-host-cta`. Tags are intentionally stable / opaque so a test can
 * look up a node without depending on label wording, which is part of
 * the spec under review (issue #111).
 */
const val ADD_HOST_NAME_FIELD_TAG = "add-host-field-name"
const val ADD_HOST_HOSTNAME_FIELD_TAG = "add-host-field-hostname"
const val ADD_HOST_PORT_FIELD_TAG = "add-host-field-port"
const val ADD_HOST_USERNAME_FIELD_TAG = "add-host-field-username"
const val ADD_HOST_KEY_FIELD_TAG = "add-host-field-key"
const val ADD_HOST_USAGE_COMMAND_FIELD_TAG = "add-host-field-usage-command"
const val ADD_HOST_CTA_TAG = "add-host-cta"
const val ADD_HOST_KEY_DROPDOWN_TAG = "add-host-key-dropdown"
const val ADD_HOST_KEY_SEARCH_TAG = "add-host-key-search"
const val ADD_HOST_KEY_SEARCH_EMPTY_TAG = "add-host-key-search-empty"
const val ADD_HOST_DETAILS_TAB_TAG = "add-host-tab-details"
const val ADD_HOST_MANAGE_KEYS_TAB_TAG = "add-host-tab-manage-keys"
const val ADD_HOST_SCAN_QR_TAG = "add-host-scan-qr"

/**
 * Default placeholder shown in the optional "Usage command" field
 * (issue #117). Mirrors `UsageRemoteSource.defaultUsageCommand` — the
 * literal is duplicated here so the form doesn't depend on the
 * `app.usage` package, keeping the Compose surface package-isolated.
 */
private const val USAGE_COMMAND_PLACEHOLDER = "pocketshell usage --json"
private const val USAGE_COMMAND_SUPPORTING_TEXT =
    "Optional override. Leave blank to use pocketshell usage --json."

/**
 * Default supporting text shown under the Port field when there is no
 * validation error. Mirrors Material 3 guidance to render the prefilled
 * "22" as a hint rather than relying on a floating-label-with-value
 * convention that mixed three label styles on this form (issue #111).
 */
private const val PORT_SUPPORTING_TEXT = "Default: 22"

/**
 * Add a new host (when [hostId] is `null`) or edit an existing one. The
 * brief calls for "name, hostname, port, username, key selector"; this
 * screen lays them out as a single vertical column inside a scroll
 * container, with a save button pinned at the bottom of the scroll.
 *
 * Issue #111 standardises the form on Material 3 `OutlinedTextField`
 * with persistent labels for every field (no placeholder-as-label), adds
 * per-field validation with error outline + supporting text, disables
 * the CTA until the form is valid, and on a rejected submit moves focus
 * to the first invalid field.
 *
 * The key selector dropdown reads from [AddEditHostViewModel.sshKeys];
 * if the user has not registered any keys yet, the dropdown shows a
 * "no keys — tap Manage keys" row and Save fails with an inline error.
 * Key creation happens in this flow's Manage keys tab so selecting and
 * managing a key stay in one place.
 *
 * Save success → [AddEditHostViewModel.state.saved] flips to `true` →
 * [onDone] is invoked to pop the screen.
 */
@Composable
fun AddEditHostScreen(
    hostId: Long?,
    onDone: () -> Unit,
    onScanQr: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: AddEditHostViewModel = hiltViewModel(),
    keyManagementViewModel: SshKeysViewModel = hiltViewModel(),
    keyManagementRequiresUnlock: (android.content.Context) -> Boolean = ::isSshKeyUnlockRequired,
) {
    val state by viewModel.state.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    var selectedTab by remember { mutableStateOf(AddEditHostTab.Details) }

    LaunchedEffect(hostId) {
        if (hostId != null) viewModel.loadHost(hostId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    // Per-field focus requesters drive the "move focus to the first
    // invalid field on a failed submit" requirement (issue #111). They
    // are remembered once per screen instance so requesting focus on a
    // recomposition routes to the same node the user is seeing.
    val nameFocus = remember { FocusRequester() }
    val hostnameFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }

    LaunchedEffect(state.firstInvalidField) {
        val target = state.firstInvalidField ?: return@LaunchedEffect
        val requester = when (target) {
            HostFormField.Name -> nameFocus
            HostFormField.Hostname -> hostnameFocus
            HostFormField.Port -> portFocus
            HostFormField.Username -> usernameFocus
            HostFormField.SelectedKey -> keyFocus
        }
        // requestFocus can throw if the node hasn't been laid out yet
        // (e.g. screen is still composing). We silently swallow that
        // because the next save attempt will queue another focus request.
        runCatching { requester.requestFocus() }
        viewModel.consumeFirstInvalidField()
    }

    // CTA enablement derives reactively from the same pure validator the
    // ViewModel uses inside save(). Disabled until every required field
    // is non-empty AND port parses as int in [1..65535] AND a key is
    // selected (issue #111).
    val canSubmit = AddEditHostViewModel.validate(state).isClean()

    // Issue #38 item 3: intercept system-back so unsaved edits aren't
    // dropped silently. A "discard?" dialog only shows when the form is
    // actually dirty; the clean-form path falls straight through to the
    // navigation callback. `pendingDiscard` mirrors the back-press into
    // dialog visibility — `DiscardChangesDialog` confirms or cancels.
    var pendingDiscard by remember { mutableStateOf(false) }
    BackHandler {
        if (selectedTab == AddEditHostTab.ManageKeys) {
            selectedTab = AddEditHostTab.Details
        } else if (viewModel.isDirty()) {
            pendingDiscard = true
        } else {
            onDone()
        }
    }
    if (pendingDiscard) {
        DiscardChangesDialog(
            onConfirm = {
                pendingDiscard = false
                onDone()
            },
            onDismiss = { pendingDiscard = false },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FormHeader(
                title = if (hostId == null) "Add host" else "Edit host",
                onScanQr = onScanQr.takeIf { hostId == null },
                onBack = {
                    if (selectedTab == AddEditHostTab.ManageKeys) {
                        selectedTab = AddEditHostTab.Details
                    } else if (viewModel.isDirty()) {
                        pendingDiscard = true
                    } else {
                        onDone()
                    }
                },
            )

            AddEditHostTabs(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
            )

            when (selectedTab) {
                AddEditHostTab.Details -> {
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
                            errorText = state.fieldErrors.name,
                            focusRequester = nameFocus,
                            testTag = ADD_HOST_NAME_FIELD_TAG,
                        )

                        FormField(
                            label = "Hostname / IP",
                            value = state.hostname,
                            onValueChange = { v -> viewModel.updateState { it.copy(hostname = v) } },
                            errorText = state.fieldErrors.hostname,
                            focusRequester = hostnameFocus,
                            testTag = ADD_HOST_HOSTNAME_FIELD_TAG,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FormField(
                                label = "Port",
                                value = state.port,
                                onValueChange = { v -> viewModel.updateState { it.copy(port = v) } },
                                // Show the default-22 hint at rest; an error
                                // message replaces it when validation fails.
                                supportingText = PORT_SUPPORTING_TEXT,
                                errorText = state.fieldErrors.port,
                                focusRequester = portFocus,
                                modifier = Modifier.weight(1f),
                                testTag = ADD_HOST_PORT_FIELD_TAG,
                                keyboardType = KeyboardType.Number,
                            )
                            FormField(
                                label = "Username",
                                value = state.username,
                                onValueChange = { v -> viewModel.updateState { it.copy(username = v) } },
                                errorText = state.fieldErrors.username,
                                focusRequester = usernameFocus,
                                modifier = Modifier.weight(2f),
                                testTag = ADD_HOST_USERNAME_FIELD_TAG,
                            )
                        }

                        KeySelector(
                            selectedKeyId = state.selectedKeyId,
                            keys = sshKeys.map { it.id to it.name },
                            onSelect = { id -> viewModel.updateState { it.copy(selectedKeyId = id) } },
                            onManageKeys = { selectedTab = AddEditHostTab.ManageKeys },
                            errorText = state.fieldErrors.selectedKey,
                            focusRequester = keyFocus,
                        )

                        // Issue #117 (usage Fix C): optional per-host override
                        // for the usage command. The field is plain — no
                        // validation, no required-marker — because (a) any
                        // non-empty string is forwarded verbatim to
                        // [UsageRemoteSource.fetchUsage] as `commandOverride`,
                        // and (b) leaving it blank just falls back to
                        // `pocketshell usage --json`. The placeholder is the default
                        // so the user can see what the empty state will do.
                        FormField(
                            label = "Usage command (optional)",
                            value = state.usageCommand,
                            onValueChange = { v -> viewModel.updateState { it.copy(usageCommand = v) } },
                            supportingText = USAGE_COMMAND_SUPPORTING_TEXT,
                            placeholder = USAGE_COMMAND_PLACEHOLDER,
                            testTag = ADD_HOST_USAGE_COMMAND_FIELD_TAG,
                        )

                        // Issue #627: Claude Code profile configuration.
                        // Each profile has a name (display label) and an
                        // optional config directory path on the remote host
                        // that maps to CLAUDE_CONFIG_DIR.
                        ClaudeProfilesSection(
                            profiles = state.claudeProfiles,
                            onProfilesChange = { profiles ->
                                viewModel.updateState { it.copy(claudeProfiles = profiles) }
                            },
                        )

                        // The legacy global prose error survives only for the
                        // "no SSH keys exist on the device at all" hint — that's
                        // a global precondition, not a per-field problem. The
                        // per-field rejection messages live under each field via
                        // `state.fieldErrors`.
                        state.error?.let { err ->
                            Text(
                                text = err,
                                color = PocketShellColors.Red,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = viewModel::save,
                            enabled = canSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ADD_HOST_CTA_TAG),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PocketShellColors.Accent,
                                contentColor = PocketShellColors.OnAccent,
                                disabledContainerColor = PocketShellColors.Border,
                                disabledContentColor = PocketShellColors.TextMuted,
                            ),
                        ) {
                            Text(
                                text = if (hostId == null) "Add host" else "Save changes",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                AddEditHostTab.ManageKeys -> {
                    SshKeysManagementPane(
                        modifier = Modifier.weight(1f),
                        viewModel = keyManagementViewModel,
                        requiresUnlock = keyManagementRequiresUnlock,
                    )
                }
            }
        }
    }
}

private enum class AddEditHostTab {
    Details,
    ManageKeys,
}

@Composable
private fun AddEditHostTabs(
    selectedTab: AddEditHostTab,
    onSelect: (AddEditHostTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = PocketShellColors.Background,
        contentColor = PocketShellColors.Accent,
    ) {
        Tab(
            selected = selectedTab == AddEditHostTab.Details,
            onClick = { onSelect(AddEditHostTab.Details) },
            text = { Text("Host details", style = PocketShellType.bodyDense) },
            modifier = Modifier.testTag(ADD_HOST_DETAILS_TAB_TAG),
        )
        Tab(
            selected = selectedTab == AddEditHostTab.ManageKeys,
            onClick = { onSelect(AddEditHostTab.ManageKeys) },
            text = { Text("Manage keys", style = PocketShellType.bodyDense) },
            modifier = Modifier.testTag(ADD_HOST_MANAGE_KEYS_TAB_TAG),
        )
    }
}

/**
 * Add/Edit host header, routed through the shared [ScreenHeader] (#479
 * Slice E1a) so the screen reads as the tight dev-tool block — `bodyDense`
 * SemiBold title + `‹` back chevron in the leading slot — instead of the
 * old bespoke 60dp / 22.sp / 18.sp `FormAppBar`. This header is shared by
 * both the Host-details and Manage-keys tabs (the SSH-keys management pane
 * carries no separate header of its own), so migrating it onto the shared
 * primitive also closes the Slice B1 keys-header gap.
 *
 * The `‹` back affordance keeps the same 40dp tap target the old bar used;
 * the Scan-QR action keeps its `ADD_HOST_SCAN_QR_TAG` so the Add-host
 * instrumentation keeps resolving it after the migration. The Scan-QR
 * label drops its raw `13.sp` for the muted `labelSmall` type token.
 */
@Composable
private fun FormHeader(
    title: String,
    onScanQr: (() -> Unit)?,
    onBack: () -> Unit,
) {
    ScreenHeader(
        title = title,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        trailing = {
            if (onScanQr != null) {
                TextButton(
                    onClick = onScanQr,
                    modifier = Modifier.testTag(ADD_HOST_SCAN_QR_TAG),
                ) {
                    Text(
                        text = "Scan QR",
                        color = PocketShellColors.Accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}

/**
 * Thin wrapper around [OutlinedTextField] that pre-applies the
 * PocketShell colour scheme so every form field reads consistently.
 *
 * Supports per-field validation via [errorText] (issue #111). When set,
 * the field renders with `isError = true`, swapping the outline + label
 * to the error colour and replacing any [supportingText] with the error
 * message. [focusRequester] lets the screen move focus to the first
 * invalid field after a rejected submit.
 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingText: String? = null,
    errorText: String? = null,
    focusRequester: FocusRequester? = null,
    testTag: String? = null,
    placeholder: String? = null,
) {
    val isError = errorText != null
    val effectiveSupport = errorText ?: supportingText
    // Apply the test tag as the LAST modifier on the OutlinedTextField so
    // the semantics node it produces carries it. Putting `testTag` at the
    // outer end of the chain — and not on a Row-scope `Modifier.weight()`
    // that gets consumed before semantics — is what makes the tag
    // discoverable from `onNodeWithTag` for Material 3 text fields, which
    // wrap their content in a merge-descendants semantics node.
    var effective: Modifier = modifier
    if (focusRequester != null) effective = effective.focusRequester(focusRequester)
    effective = effective.fillMaxWidth()
    if (testTag != null) effective = effective.testTag(testTag)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let {
            { Text(text = it, color = PocketShellColors.TextMuted) }
        },
        singleLine = true,
        isError = isError,
        supportingText = effectiveSupport?.let {
            { Text(text = it) }
        },
        modifier = effective,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PocketShellColors.Text,
            unfocusedTextColor = PocketShellColors.Text,
            focusedBorderColor = PocketShellColors.Accent,
            unfocusedBorderColor = PocketShellColors.Border,
            errorBorderColor = PocketShellColors.Red,
            focusedLabelColor = PocketShellColors.Accent,
            unfocusedLabelColor = PocketShellColors.TextSecondary,
            errorLabelColor = PocketShellColors.Red,
            errorSupportingTextColor = PocketShellColors.Red,
            focusedSupportingTextColor = PocketShellColors.TextSecondary,
            unfocusedSupportingTextColor = PocketShellColors.TextSecondary,
            cursorColor = PocketShellColors.Accent,
            focusedContainerColor = PocketShellColors.Surface,
            unfocusedContainerColor = PocketShellColors.Surface,
            errorContainerColor = PocketShellColors.Surface,
        ),
    )
}

/**
 * Key-selector field. Issue #111 standardises every form field on a
 * persistent label + outline; this selector reuses the [OutlinedTextField]
 * chrome in read-only mode (no keyboard pops up) and anchors a
 * [DropdownMenu] off the surrounding [Box]. Tapping anywhere on the
 * field opens the menu.
 *
 * The selector accepts a [focusRequester] (so failed-submit focus moves
 * land here) and an optional [errorText] (so a missing-key save attempt
 * paints the field in the same per-field error style as the others).
 *
 * Material3's `ExposedDropdownMenuBox` is the canonical pattern but
 * historically pulled in `material3-adaptive`; a hand-rolled tap-target
 * + `DropdownMenu` is functionally equivalent for this single-line use
 * and keeps the dependency footprint flat (issue #18 forbids new
 * catalog entries).
 */
@Composable
private fun KeySelector(
    selectedKeyId: Long?,
    keys: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    onManageKeys: () -> Unit,
    errorText: String? = null,
    focusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = keys.firstOrNull { it.first == selectedKeyId }?.second
    val isError = errorText != null

    Box(modifier = Modifier.fillMaxWidth()) {
        // The read-only OutlinedTextField gives us the same persistent
        // label + outlined chrome as every other field on the form
        // (issue #111). It is `enabled = false` so the soft keyboard
        // doesn't pop up — taps fall through to the overlay below that
        // toggles the dropdown.
        OutlinedTextField(
            value = selectedName ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("SSH key") },
            placeholder = {
                Text(
                    "Select an SSH key",
                    color = PocketShellColors.TextMuted,
                )
            },
            isError = isError,
            supportingText = errorText?.let { { Text(it) } },
            singleLine = true,
            modifier = (focusRequester?.let {
                Modifier.focusRequester(it)
            } ?: Modifier)
                .fillMaxWidth()
                .testTag(ADD_HOST_KEY_FIELD_TAG),
            colors = OutlinedTextFieldDefaults.colors(
                // We use `disabledXxx` because we set enabled=false so
                // the soft keyboard doesn't appear; the visual still
                // needs to match the other (enabled) fields.
                disabledTextColor = PocketShellColors.Text,
                disabledBorderColor = PocketShellColors.Border,
                disabledLabelColor = PocketShellColors.TextSecondary,
                disabledPlaceholderColor = PocketShellColors.TextMuted,
                disabledSupportingTextColor = PocketShellColors.TextSecondary,
                disabledContainerColor = PocketShellColors.Surface,
                errorBorderColor = PocketShellColors.Red,
                errorLabelColor = PocketShellColors.Red,
                errorSupportingTextColor = PocketShellColors.Red,
                errorContainerColor = PocketShellColors.Surface,
            ),
        )
        // Transparent click-catcher that sits on top of the disabled
        // OutlinedTextField and opens the menu. Sized to match the
        // outlined area; supportingText shows below so the catcher
        // doesn't need to cover it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        // Issue #157 polish item 4: long key lists turn the dropdown
        // into an unwieldy scroll. The search field appears at the top
        // of the menu whenever the user has KEY_SEARCH_THRESHOLD or
        // more keys saved; below that, the list is small enough that
        // a filter would be friction. The query is local to this menu
        // open — `remember(expanded)` resets it on each open so a
        // user who closes + reopens the dropdown sees the full list
        // again.
        var keyQuery by remember(expanded) { mutableStateOf("") }
        val filteredKeys = remember(keys, keyQuery) {
            if (keyQuery.isBlank()) keys
            else keys.filter { (_, name) ->
                name.contains(keyQuery, ignoreCase = true)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(PocketShellColors.Surface)
                .testTag(ADD_HOST_KEY_DROPDOWN_TAG),
        ) {
            if (keys.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "No keys yet — tap Manage keys",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.bodyDense,
                        )
                    },
                    onClick = {
                        expanded = false
                        onManageKeys()
                    },
                )
            } else {
                if (keys.size >= KEY_SEARCH_THRESHOLD) {
                    // The search field is a real OutlinedTextField so
                    // it pops the soft keyboard and respects the form's
                    // focus chain. It's NOT a DropdownMenuItem because
                    // those auto-close the menu on tap.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PocketShellDensity.rowPadH,
                                vertical = PocketShellDensity.rowPadV,
                            ),
                    ) {
                        OutlinedTextField(
                            value = keyQuery,
                            onValueChange = { keyQuery = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    "Search keys",
                                    color = PocketShellColors.TextMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ADD_HOST_KEY_SEARCH_TAG),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PocketShellColors.Text,
                                unfocusedTextColor = PocketShellColors.Text,
                                focusedBorderColor = PocketShellColors.Accent,
                                unfocusedBorderColor = PocketShellColors.Border,
                                focusedContainerColor = PocketShellColors.Surface,
                                unfocusedContainerColor = PocketShellColors.Surface,
                                cursorColor = PocketShellColors.Accent,
                            ),
                        )
                    }
                }
                if (filteredKeys.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "No keys match \"$keyQuery\"",
                                color = PocketShellColors.TextMuted,
                                style = PocketShellType.bodyDense,
                            )
                        },
                        onClick = { /* swallow — keeps the menu open */ },
                        enabled = false,
                        modifier = Modifier.testTag(ADD_HOST_KEY_SEARCH_EMPTY_TAG),
                    )
                } else {
                    filteredKeys.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = name,
                                    color = PocketShellColors.Text,
                                    style = PocketShellType.bodyDense,
                                )
                            },
                            onClick = {
                                onSelect(id)
                                expanded = false
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Manage keys…",
                            color = PocketShellColors.Accent,
                            style = PocketShellType.bodyDense,
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

/**
 * Issue #157 polish item 4: minimum number of saved keys before the
 * KeySelector dropdown renders a search field above the list. Below
 * the threshold every key is visible without scrolling, so the search
 * field would be friction; at and above it, scanning N keys by eye
 * stops being practical on a Pixel-7-sized viewport.
 */
internal const val KEY_SEARCH_THRESHOLD: Int = 5

/**
 * Confirmation dialog shown when the user attempts to back out of the
 * form with unsaved edits (issue #38 item 3). Wording is deliberately
 * plain — "Discard changes?" with explicit Discard / Keep editing
 * buttons rather than a save-then-leave option, since saving requires
 * passing validation which the user may not have completed.
 */
@Composable
private fun DiscardChangesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard changes?", color = PocketShellColors.Text) },
        text = {
            Text(
                text = "You have unsaved edits. Leave the form anyway?",
                color = PocketShellColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Discard", color = PocketShellColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep editing", color = PocketShellColors.Accent)
            }
        },
        containerColor = PocketShellColors.Surface,
    )
}

/**
 * Issue #627: Claude Code profile editor for the host form.
 *
 * Displays a list of named profiles with optional config directory paths.
 * The user can add, edit, and remove profiles. Each profile maps to a
 * `CLAUDE_CONFIG_DIR` value on the remote host when launching Claude Code.
 */
@Composable
private fun ClaudeProfilesSection(
    profiles: List<ClaudeProfile>,
    onProfilesChange: (List<ClaudeProfile>) -> Unit,
) {
    val cardShape = RoundedCornerShape(8.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Claude Code profiles",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Optional: configure named profiles for CLAUDE_CONFIG_DIR.",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )

        profiles.forEachIndexed { index, profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PocketShellColors.SurfaceElev, cardShape)
                    .border(1.dp, PocketShellColors.BorderSoft, cardShape)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = profile.name,
                        onValueChange = { newName ->
                            val updated = profiles.toMutableList()
                            updated[index] = profile.copy(name = newName)
                            onProfilesChange(updated)
                        },
                        label = { Text("Profile name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PocketShellColors.Text,
                            unfocusedTextColor = PocketShellColors.Text,
                            focusedBorderColor = PocketShellColors.Accent,
                            unfocusedBorderColor = PocketShellColors.Border,
                            focusedContainerColor = PocketShellColors.Surface,
                            unfocusedContainerColor = PocketShellColors.Surface,
                            cursorColor = PocketShellColors.Accent,
                        ),
                    )
                    OutlinedTextField(
                        value = profile.configDir,
                        onValueChange = { newDir ->
                            val updated = profiles.toMutableList()
                            updated[index] = profile.copy(configDir = newDir)
                            onProfilesChange(updated)
                        },
                        label = { Text("Config dir (optional)") },
                        placeholder = { Text("~/.claude") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PocketShellColors.Text,
                            unfocusedTextColor = PocketShellColors.Text,
                            focusedBorderColor = PocketShellColors.Accent,
                            unfocusedBorderColor = PocketShellColors.Border,
                            focusedContainerColor = PocketShellColors.Surface,
                            unfocusedContainerColor = PocketShellColors.Surface,
                            cursorColor = PocketShellColors.Accent,
                        ),
                    )
                }
                TextButton(
                    onClick = {
                        val updated = profiles.toMutableList()
                        updated.removeAt(index)
                        onProfilesChange(updated)
                    },
                ) {
                    Text("Remove", color = PocketShellColors.Red)
                }
            }
        }

        TextButton(
            onClick = {
                onProfilesChange(profiles + ClaudeProfile(name = ""))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("+ Add profile", color = PocketShellColors.Accent)
        }
    }
}
