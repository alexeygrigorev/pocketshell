package com.pocketshell.app.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.assistant.AssistantProvider
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.roundToInt

/**
 * Settings home — landing surface for app-level preferences introduced
 * in issue #112.
 *
 * Issue #486 regrouped + reordered the sections most-useful-first and
 * routed every heading through the shared [SectionHeader] (#480) for
 * consistent styling. Sections (top-to-bottom):
 *
 *  1. **Terminal** — default font-size slider, the "use tmux on attach"
 *     switch, and the open-on-launch startup destination (the former
 *     standalone "Startup" section is folded in here).
 *  2. **Voice & dictation** — Whisper key, language, silence threshold,
 *     AI costs entry.
 *  3. **Assistant** — voice→command assistant LLM config.
 *  4. **Usage** — quota panel entry, provider state, warn threshold.
 *  5. **Workspace** — per-host workspace roots / tree-flat defaults.
 *  6. **Hosts** — QR/file host import.
 *  7. **Diagnostics** — crash reports.
 *  8. **About footer** — installed `versionName` / build code read from
 *     `PackageManager`, kept at the bottom so debug metadata stays
 *     discoverable without competing with primary Settings controls.
 *
 * The screen draws its own chrome (no `Scaffold`) to stay consistent
 * with the rest of the app's hand-rolled bars (`HostsAppBar`,
 * `KeysAppBar`).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCrashReports: () -> Unit,
    onOpenUsage: () -> Unit = {},
    onOpenAiCosts: () -> Unit = {},
    onScanHostImport: () -> Unit = {},
    onChooseHostImportFile: (Uri) -> Unit = {},
    /**
     * Issue #206: per-host watched-folders config entry. The Settings
     * surface routes through a host picker (no decrypted passphrase
     * is available here) so the destination it opens omits SSH
     * credentials — the discover-from-remote button is hidden in
     * that case. The host-list kebab keeps the full-credential
     * route.
     */
    onOpenWatchedFoldersForHost: (hostId: Long, hostName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)

    val settings by viewModel.state.collectAsState()
    val keyStatus by viewModel.keyStatus.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val hasUsageInstalledHost by viewModel.hasUsageInstalledHost.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val usageProviderRecords by viewModel.usageProviderRecords.collectAsState()
    val context = LocalContext.current
    val hostImportFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onChooseHostImportFile) }

    val appBuildInfo = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            AppBuildInfo(
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
            )
        } catch (_: Exception) {
            AppBuildInfo(versionName = "unknown", versionCode = null)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        SettingsAppBar(onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SETTINGS_LAZY_COLUMN_TAG),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Settings are ordered most-useful-first (issue #486):
            // Terminal (incl. startup) → Voice → Assistant → Usage →
            // Workspace → Hosts → Diagnostics → About. Every section uses
            // the shared SectionHeader (#480) via SectionLabel for
            // consistent styling.
            item {
                TerminalSection(
                    fontSizeSp = settings.terminalFontSizeSp,
                    onFontSizeChange = viewModel::setTerminalFontSizeSp,
                    conversationFontSizeSp = settings.conversationFontSizeSp,
                    onConversationFontSizeChange = viewModel::setConversationFontSizeSp,
                    terminalKeyboardMode = settings.terminalKeyboardMode,
                    onTerminalKeyboardModeChange = viewModel::setTerminalKeyboardMode,
                    tmuxOnAttach = settings.tmuxOnAttachByDefault,
                    onTmuxOnAttachChange = viewModel::setTmuxOnAttachByDefault,
                    agentSubmitEnterDelayMs = settings.agentSubmitEnterDelayMs,
                    onAgentSubmitEnterDelayChange = viewModel::setAgentSubmitEnterDelayMs,
                    hosts = hosts,
                    selectedHostId = settings.defaultHostId,
                    onSelectDefaultHost = viewModel::setDefaultHostId,
                )
            }
            item {
                VoiceSection(
                    keyStatus = keyStatus,
                    language = settings.voiceLanguage,
                    silenceThresholdSeconds = settings.voiceSilenceThresholdSeconds,
                    transcriptionProvider = settings.voiceTranscriptionProvider,
                    onSaveApiKey = viewModel::saveApiKey,
                    onClearApiKey = viewModel::clearApiKey,
                    onLanguageSelected = viewModel::setVoiceLanguage,
                    onTranscriptionProviderSelected = viewModel::setVoiceTranscriptionProvider,
                    onSilenceThresholdChange = viewModel::setVoiceSilenceThresholdSeconds,
                    onOpenAiCosts = onOpenAiCosts,
                )
            }
            item {
                AssistantSection(
                    assistantState = assistantState,
                    onProviderSelected = viewModel::setAssistantProvider,
                    onEndpointChange = viewModel::setAssistantEndpoint,
                    onSaveKey = viewModel::saveAssistantKey,
                    onClearKey = viewModel::clearAssistantKey,
                )
            }
            item {
                UsageSection(
                    onOpenUsage = onOpenUsage,
                    hasUsageInstalledHost = hasUsageInstalledHost,
                    providerRecords = usageProviderRecords,
                    warnThresholdPercent = settings.usageWarnThresholdPercent,
                    onWarnThresholdChange = viewModel::setUsageWarnThresholdPercent,
                )
            }
            item {
                WorkspaceRootsSection(
                    hosts = hosts,
                    onPickHost = onOpenWatchedFoldersForHost,
                )
            }
            item {
                HostImportSection(
                    onScanQr = onScanHostImport,
                    onChooseFile = { hostImportFilePicker.launch("*/*") },
                )
            }
            item {
                DiagnosticsSection(onOpenCrashReports = onOpenCrashReports)
            }
            item {
                AboutFooter(appBuildInfo = appBuildInfo)
            }
        }
    }
}

@Composable
private fun HostImportSection(
    onScanQr: () -> Unit,
    onChooseFile: () -> Unit,
) {
    var showImportDialog by remember { mutableStateOf(false) }

    Column {
        SectionLabel("Hosts")
        SectionCard {
            SettingsNavRow(
                title = "Import host",
                description = HOST_IMPORT_SETTINGS_COPY,
                onClick = { showImportDialog = true },
                testTag = HOST_IMPORT_ROW_TAG,
            )
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import host", color = PocketShellColors.Text) },
            text = {
                Text(
                    text = HOST_IMPORT_DIALOG_COPY,
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier.testTag(HOST_IMPORT_DIALOG_COPY_TAG),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        onScanQr()
                    },
                    modifier = Modifier.testTag(HOST_IMPORT_SCAN_QR_TAG),
                ) {
                    Text("Scan QR", color = PocketShellColors.Accent)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showImportDialog = false
                            onChooseFile()
                        },
                        modifier = Modifier.testTag(HOST_IMPORT_CHOOSE_FILE_TAG),
                    ) {
                        Text("Choose file", color = PocketShellColors.Accent)
                    }
                    TextButton(onClick = { showImportDialog = false }) {
                        Text("Cancel", color = PocketShellColors.TextSecondary)
                    }
                }
            },
            containerColor = PocketShellColors.Surface,
        )
    }
}

/**
 * Settings header, routed through the shared [ScreenHeader] (#479 Slice D)
 * so the screen reads as the tight dev-tool block — `bodyDense` SemiBold
 * title + `‹` back chevron in the leading slot — instead of the old
 * 60dp / 22.sp bar. The `‹` chevron + `Settings` title keep their existing
 * `settings:back` / `settings:title` test tags so the navigator/walkthrough
 * instrumentation keeps resolving them after the migration.
 */
@Composable
private fun SettingsAppBar(onBack: () -> Unit) {
    ScreenHeader(
        title = "Settings",
        titleTestTag = SETTINGS_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(SETTINGS_BACK_TAG),
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
 * Section label rendered above each [SectionCard].
 *
 * Issue #486 polish: every Settings section now routes its heading
 * through the shared [SectionHeader] (#480) so the section vocabulary is
 * identical across surfaces (Hosts / Sessions / Settings). This replaces
 * the old hand-rolled `Text` heading and keeps the per-section behaviour
 * the screen relies on:
 *
 *  - A 1 dp `BorderSoft` divider above the heading whenever
 *    [includeTopDivider] is true. The first section passes `false` so
 *    the divider only ever appears BETWEEN sections, never above the top
 *    of the list. The divider colour matches the card border
 *    (`BorderSoft`) so the eye reads "card-edge → gap → next card-edge".
 *  - The stable `settings:section-label:*` test tag carried by the
 *    heading so existing instrumentation can still target it.
 *
 * The shared header owns the label styling (uppercased `labelSmall`
 * SemiBold on the muted token) and the Slice 0 spacing/density tokens, so
 * spacing stays consistent with the rest of the app.
 */
@Composable
private fun SectionLabel(text: String, includeTopDivider: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (includeTopDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(1.dp)
                    .background(PocketShellColors.BorderSoft),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        SectionHeader(
            label = text,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .testTag(sectionLabelTestTag(text)),
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * The right-aligned `›` disclosure chevron carried by every navigation row
 * (Import host, Usage, Crash reports, …). Tokenised onto
 * [PocketShellType.bodyDense] so the glyph rides the dense-row type rung
 * instead of a raw `22.sp` literal (#479 Slice D); the muted secondary
 * colour keeps it quiet next to the row title.
 */
@Composable
private fun NavChevron() {
    Text(
        text = "›",
        color = PocketShellColors.TextSecondary,
        style = PocketShellType.bodyDense,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * A disclosure navigation row inside a [SectionCard] — title + optional
 * prose description + a right-aligned `›` chevron that routes deeper
 * (Import host, Usage & quota, Crash reports). The actionable line is the
 * shared [ListRow] so the row inherits the design language's dense
 * 44/8/12 density and the 48dp touch floor (#479 Slice D); the prose
 * description renders below it on the [PocketShellType.labelSmall] caption
 * rung (mono [ListRow] subtitles are reserved for paths/IDs, so a prose
 * description does not belong in that slot).
 */
@Composable
private fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    testTag: String,
    description: String? = null,
) {
    ListRow(
        title = title,
        trailing = { NavChevron() },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
    if (description != null) {
        Text(
            text = description,
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                start = PocketShellSpacing.sm,
                top = 2.dp,
                bottom = 4.dp,
            ),
        )
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    // Custom radio glyph so the on-screen styling stays consistent with
    // the rest of PocketShell's hand-rolled controls (the app intentionally
    // bypasses some Material3 widgets to keep the dark surface palette).
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(
                width = 2.dp,
                color = if (selected) PocketShellColors.Accent else PocketShellColors.Border,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = PocketShellColors.Accent, shape = CircleShape),
            )
        }
    }
}

/**
 * Terminal section — issue #486 folds the former standalone "Startup"
 * section in here so the terminal/startup knobs live in one card:
 *
 *  - **Default font size** slider (sp).
 *  - **Use tmux when available** switch.
 *  - **Open on launch** startup-destination radio group (was the
 *    `StartupSection`, issue #305). Exactly one saved host can be the
 *    launch default, or "Host list" clears it. The launch resolver still
 *    validates the host/key before routing, so a deleted host lands back
 *    on the host list.
 */
@Composable
private fun TerminalSection(
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    conversationFontSizeSp: Float,
    onConversationFontSizeChange: (Float) -> Unit,
    terminalKeyboardMode: TerminalKeyboardMode,
    onTerminalKeyboardModeChange: (TerminalKeyboardMode) -> Unit,
    tmuxOnAttach: Boolean,
    onTmuxOnAttachChange: (Boolean) -> Unit,
    agentSubmitEnterDelayMs: Int,
    onAgentSubmitEnterDelayChange: (Int) -> Unit,
    hosts: List<com.pocketshell.core.storage.entity.HostEntity>,
    selectedHostId: Long?,
    onSelectDefaultHost: (Long?) -> Unit,
) {
    val selectedExists = selectedHostId != null && hosts.any { it.id == selectedHostId }
    Column {
        // First section in the list — no divider above the label.
        SectionLabel("Terminal", includeTopDivider = false)
        SectionCard {
            Text(
                text = "Default font size",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = fontSizeSp,
                    onValueChange = { onFontSizeChange(it) },
                    valueRange = AppSettings.MIN_TERMINAL_FONT_SP..AppSettings.MAX_TERMINAL_FONT_SP,
                    steps = ((AppSettings.MAX_TERMINAL_FONT_SP - AppSettings.MIN_TERMINAL_FONT_SP)
                        / AppSettings.FONT_STEP_SP).toInt() - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = PocketShellColors.Accent,
                        activeTrackColor = PocketShellColors.Accent,
                        inactiveTrackColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TERMINAL_FONT_SLIDER_TAG),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${fontSizeSp.roundToInt()}sp",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Issue #496: conversation message-body font size. Sits next to
            // the terminal font slider so both text-size knobs live together.
            // Defaults to the compact bodyDense rung (13sp, #493); the slider
            // scales the agent-conversation turns up or down.
            Text(
                text = "Conversation font size",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Scale the agent conversation message text.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = conversationFontSizeSp,
                    onValueChange = { onConversationFontSizeChange(it) },
                    valueRange = AppSettings.MIN_CONVERSATION_FONT_SP..AppSettings.MAX_CONVERSATION_FONT_SP,
                    steps = ((AppSettings.MAX_CONVERSATION_FONT_SP - AppSettings.MIN_CONVERSATION_FONT_SP)
                        / AppSettings.FONT_STEP_SP).toInt() - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = PocketShellColors.Accent,
                        activeTrackColor = PocketShellColors.Accent,
                        inactiveTrackColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CONVERSATION_FONT_SLIDER_TAG),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${conversationFontSizeSp.roundToInt()}sp",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(CONVERSATION_FONT_VALUE_TAG),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Smart text keyboard",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Enable swipe and autocorrect for terminal text. Text is staged " +
                            "and sent only when Enter confirms it; keep this off for shell " +
                            "commands and use Prompt Composer for prose.",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = terminalKeyboardMode == TerminalKeyboardMode.SmartText,
                    onCheckedChange = { enabled ->
                        onTerminalKeyboardModeChange(
                            if (enabled) {
                                TerminalKeyboardMode.SmartText
                            } else {
                                TerminalKeyboardMode.RawCommand
                            },
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PocketShellColors.OnAccent,
                        checkedTrackColor = PocketShellColors.Accent,
                        uncheckedThumbColor = PocketShellColors.TextSecondary,
                        uncheckedTrackColor = PocketShellColors.Surface,
                        uncheckedBorderColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier.testTag(TERMINAL_SMART_TEXT_SWITCH_TAG),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use tmux when available",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Default to attaching via tmux on hosts that have it installed.",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = tmuxOnAttach,
                    onCheckedChange = onTmuxOnAttachChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PocketShellColors.OnAccent,
                        checkedTrackColor = PocketShellColors.Accent,
                        uncheckedThumbColor = PocketShellColors.TextSecondary,
                        uncheckedTrackColor = PocketShellColors.Surface,
                        uncheckedBorderColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier.testTag(TMUX_SWITCH_TAG),
                )
            }

            // Issue #526: agent-submit Enter delay. The composer types the
            // message text into the agent pane, waits this long, then presses
            // the submit Enter as a separate key so a fast Enter doesn't race
            // ahead of the agent TUI's paste ingestion (which left the message
            // sitting unsent). Lives next to the other terminal/session knobs.
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Agent submit delay",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Pause after typing a message before pressing Enter, so the " +
                    "agent submits it instead of leaving it in the input. Raise this " +
                    "if Send sometimes leaves text unsent.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = agentSubmitEnterDelayMs.toFloat(),
                    onValueChange = { onAgentSubmitEnterDelayChange(it.roundToInt()) },
                    valueRange = AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat()..
                        AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat(),
                    steps = ((AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS -
                        AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS) /
                        AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = PocketShellColors.Accent,
                        activeTrackColor = PocketShellColors.Accent,
                        inactiveTrackColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(AGENT_SUBMIT_DELAY_SLIDER_TAG),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${agentSubmitEnterDelayMs}ms",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(AGENT_SUBMIT_DELAY_VALUE_TAG),
                )
            }

            // -- Startup: open-on-launch destination (folded in from the
            //    former standalone "Startup" section, issue #486) --------
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Open on launch",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Choose a saved host to open directly when PocketShell starts.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DefaultHostOptionRow(
                title = "Host list",
                subtitle = "Show all saved hosts first",
                selected = selectedHostId == null || !selectedExists,
                onClick = { onSelectDefaultHost(null) },
                testTag = DEFAULT_HOST_NONE_TAG,
            )
            hosts.forEach { host ->
                DefaultHostOptionRow(
                    title = host.name,
                    subtitle = "${host.username}@${host.hostname}:${host.port}",
                    selected = host.id == selectedHostId,
                    onClick = { onSelectDefaultHost(host.id) },
                    testTag = defaultHostOptionTag(host.id),
                )
            }
            if (hosts.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add a host first to choose a launch default.",
                    color = PocketShellColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag(DEFAULT_HOST_EMPTY_TAG),
                )
            }
        }
    }
}

@Composable
private fun DefaultHostOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Voice section — issue #125. Surfaces the three voice-related knobs
 * named in `docs/input-methods.md` §Settings:
 *
 *  - **Whisper API key** — single-field dialog that delegates to
 *    [AndroidKeystoreApiKeyStorage]. The masked tail (`sk-…1234`) shows
 *    when a key is saved; an inline "Clear" affordance removes it. The
 *    plaintext key never enters the [androidx.compose.runtime.State]
 *    graph — the dialog owns its own `String` for the lifetime of the
 *    entry, hands it as a `CharArray` to the ViewModel, and zeroes the
 *    local copy on dismiss.
 *  - **Language** — radio group over [AppSettings.VOICE_LANGUAGE_OPTIONS].
 *    The selected ISO-639-1 code is forwarded to Whisper's `language`
 *    parameter; the sentinel `auto` value means "let Whisper detect" and
 *    causes the parameter to be omitted from the multipart upload.
 *  - **Auto-stop silence threshold** — slider over
 *    [AppSettings.MIN_VOICE_SILENCE_SECONDS] /
 *    [AppSettings.MAX_VOICE_SILENCE_SECONDS] in
 *    [AppSettings.VOICE_SILENCE_STEP_SECONDS] increments, labelled from
 *    aggressive to conservative. The current value is rendered next to
 *    the label as `Xs`. Both the prompt
 *    composer and inline dictation read the latest snapshot before each
 *    recording starts, so a slider drag while the mic is idle takes
 *    effect on the next tap without any restart.
 */
@Composable
private fun VoiceSection(
    keyStatus: WhisperKeyStatus,
    language: String,
    silenceThresholdSeconds: Float,
    transcriptionProvider: VoiceTranscriptionProvider,
    onSaveApiKey: (CharArray) -> Unit,
    onClearApiKey: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onTranscriptionProviderSelected: (VoiceTranscriptionProvider) -> Unit,
    onSilenceThresholdChange: (Float) -> Unit,
    onOpenAiCosts: () -> Unit = {},
) {
    var showKeyDialog by remember { mutableStateOf(false) }

    Column {
        SectionLabel("Voice & dictation")
        SectionCard {
            // -- Provider row -------------------------------------------
            Text(
                text = "Transcription provider",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Whisper uses OpenAI and a full recording. Android uses the device's system recognizer, may stream partial text, and depends on the installed service, language packs, network, and that service's privacy policy.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            VoiceProviderOptionRow(
                title = "OpenAI Whisper",
                subtitle = "Best for technical prompts; requires an OpenAI key.",
                provider = VoiceTranscriptionProvider.OpenAiWhisper,
                selected = transcriptionProvider == VoiceTranscriptionProvider.OpenAiWhisper,
                onClick = { onTranscriptionProviderSelected(VoiceTranscriptionProvider.OpenAiWhisper) },
            )
            VoiceProviderOptionRow(
                title = "Android / Google Speech",
                subtitle = "No OpenAI key; availability, network use, and privacy depend on the system recognizer.",
                provider = VoiceTranscriptionProvider.AndroidSpeech,
                selected = transcriptionProvider == VoiceTranscriptionProvider.AndroidSpeech,
                onClick = { onTranscriptionProviderSelected(VoiceTranscriptionProvider.AndroidSpeech) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // -- API key row --------------------------------------------
            Text(
                text = "Whisper API key",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Used only by OpenAI Whisper. Stored encrypted on this device and sent to api.openai.com.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusLabel = when (keyStatus) {
                    WhisperKeyStatus.Unset -> "Set Whisper API key"
                    is WhisperKeyStatus.Set -> "Key set: sk-…${keyStatus.maskedTail}"
                }
                Text(
                    text = statusLabel,
                    color = when (keyStatus) {
                        WhisperKeyStatus.Unset -> PocketShellColors.TextSecondary
                        is WhisperKeyStatus.Set -> PocketShellColors.Text
                    },
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { showKeyDialog = true }
                        .testTag(VOICE_API_KEY_ROW_TAG)
                        .padding(vertical = 8.dp),
                )
                if (keyStatus is WhisperKeyStatus.Set) {
                    Text(
                        text = "Clear",
                        color = PocketShellColors.Accent,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onClearApiKey)
                            .testTag(VOICE_API_KEY_CLEAR_TAG)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Language row -------------------------------------------
            Text(
                text = "Language",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Hint Whisper about the spoken language. Auto-detect works for most cases.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
                LanguageOptionRow(
                    option = option,
                    selected = option.code == language,
                    onClick = { onLanguageSelected(option.code) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Silence threshold row ---------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto-stop silence threshold",
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatThresholdLabel(silenceThresholdSeconds)}s",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(VOICE_SILENCE_VALUE_TAG),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Default is conservative for long dictation; lower values stop more aggressively.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val totalRangeSeconds =
                AppSettings.MAX_VOICE_SILENCE_SECONDS - AppSettings.MIN_VOICE_SILENCE_SECONDS
            val steps = (totalRangeSeconds / AppSettings.VOICE_SILENCE_STEP_SECONDS)
                .toInt()
                .coerceAtLeast(1) - 1
            Slider(
                value = silenceThresholdSeconds,
                onValueChange = onSilenceThresholdChange,
                valueRange = AppSettings.MIN_VOICE_SILENCE_SECONDS..AppSettings.MAX_VOICE_SILENCE_SECONDS,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = PocketShellColors.Accent,
                    activeTrackColor = PocketShellColors.Accent,
                    inactiveTrackColor = PocketShellColors.Border,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(VOICE_SILENCE_SLIDER_TAG),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Aggressive",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "Conservative",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- AI Costs row -------------------------------------------
            //
            // Issue #181: opens the client-side AI cost tracker. Lives
            // under Voice (rather than Diagnostics or its own section)
            // because the only AI feature wired today is the Whisper
            // call site directly above; future LLM features (planner,
            // chat) sit in the same section.
            SettingsNavRow(
                title = "AI Costs",
                description = "Track OpenAI spend per voice transcription.",
                onClick = onOpenAiCosts,
                testTag = VOICE_AI_COSTS_ROW_TAG,
            )
        }
    }

    if (showKeyDialog) {
        VoiceApiKeyEntryDialog(
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                onSaveApiKey(key)
                java.util.Arrays.fill(key, ' ')
                showKeyDialog = false
            },
        )
    }
}

@Composable
private fun LanguageOptionRow(
    option: VoiceLanguageOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(voiceLanguageOptionTestTag(option.code)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = option.label,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VoiceProviderOptionRow(
    title: String,
    subtitle: String,
    provider: VoiceTranscriptionProvider,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(voiceProviderOptionTestTag(provider)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Minimal one-field dialog for entering an OpenAI Whisper API key from
 * the Settings screen. Mirrors `ApiKeyEntryDialog` in
 * `PromptComposerSheet.kt` so users see the same dialog whether they
 * enter the key from the composer first-tap fallback or from Settings.
 */
@Composable
internal fun VoiceApiKeyEntryDialog(
    onDismiss: () -> Unit,
    onSave: (CharArray) -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI API key", color = PocketShellColors.Text) },
        text = {
            Column {
                Text(
                    text = "Paste your OpenAI API key. It's stored encrypted on this device " +
                        "and only sent in the Authorization header to api.openai.com.",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(VOICE_API_KEY_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = keyText.toCharArray()
                    onSave(chars)
                    keyText = ""
                },
                enabled = keyText.isNotBlank(),
                modifier = Modifier.testTag(VOICE_API_KEY_SAVE_TAG),
            ) {
                Text("Save", color = PocketShellColors.Accent)
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
 * Pretty-format the silence threshold for the inline `Xs` label. Drops
 * the trailing `.0` for whole-second values so the label reads `30s`
 * rather than `30.0s`; otherwise renders one decimal (`2.5s`).
 */
internal fun formatThresholdLabel(seconds: Float): String {
    val rounded = (seconds * 10f).roundToInt() / 10f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) asInt.toString() else "%.1f".format(rounded)
}

/**
 * Assistant section — issue #265. Configures the in-app action assistant's
 * LLM provider, independent of voice transcription (which stays on
 * Whisper/OpenAI).
 *
 *  - **Provider** — radio group over [AssistantProvider]. Default OpenAI.
 *    ZAI has its own product-facing settings and key, while internally it
 *    uses the Anthropic-compatible Messages protocol.
 *  - **Base URL / model** — editable per provider; only the active
 *    provider's fields are shown.
 *  - **API key** — masked single-field dialog (`sk-…1234`), KeyStore-backed,
 *    same UX as the Whisper key. Stored in a separate encrypted file so it
 *    never disturbs the voice key.
 */
@Composable
private fun AssistantSection(
    assistantState: AssistantSettingsUiState,
    onProviderSelected: (AssistantProvider) -> Unit,
    onEndpointChange: (AssistantProvider, String, String) -> Unit,
    onSaveKey: (AssistantProvider, CharArray) -> Unit,
    onClearKey: (AssistantProvider) -> Unit,
) {
    var showKeyDialog by remember { mutableStateOf(false) }
    val provider = assistantState.provider

    Column {
        SectionLabel("Assistant")
        SectionCard {
            Text(
                text = "Provider",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "LLM backing the in-app action assistant. Separate from voice transcription.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AssistantProviderRow(
                label = "OpenAI",
                selected = provider == AssistantProvider.OpenAi,
                onClick = { onProviderSelected(AssistantProvider.OpenAi) },
                testTag = ASSISTANT_PROVIDER_OPENAI_TAG,
            )
            AssistantProviderRow(
                label = "Anthropic",
                selected = provider == AssistantProvider.Anthropic,
                onClick = { onProviderSelected(AssistantProvider.Anthropic) },
                testTag = ASSISTANT_PROVIDER_ANTHROPIC_TAG,
            )
            AssistantProviderRow(
                label = "ZAI",
                selected = provider == AssistantProvider.Zai,
                onClick = { onProviderSelected(AssistantProvider.Zai) },
                testTag = ASSISTANT_PROVIDER_ZAI_TAG,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // -- Base URL -----------------------------------------------
            val baseUrl = assistantState.baseUrlFor(provider)
            val model = assistantState.modelFor(provider)
            Text(
                text = "Base URL",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { onEndpointChange(provider, it, model) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ASSISTANT_BASE_URL_FIELD_TAG),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // -- Model --------------------------------------------------
            Text(
                text = "Model",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { onEndpointChange(provider, baseUrl, it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ASSISTANT_MODEL_FIELD_TAG),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // -- API key ------------------------------------------------
            Text(
                text = "API key",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Stored encrypted on this device, per provider.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val keyStatus = assistantState.keyStatusFor(provider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusLabel = when (keyStatus) {
                    WhisperKeyStatus.Unset -> "Set API key"
                    is WhisperKeyStatus.Set -> "Key set: …${keyStatus.maskedTail}"
                }
                Text(
                    text = statusLabel,
                    color = when (keyStatus) {
                        WhisperKeyStatus.Unset -> PocketShellColors.TextSecondary
                        is WhisperKeyStatus.Set -> PocketShellColors.Text
                    },
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { showKeyDialog = true }
                        .testTag(ASSISTANT_API_KEY_ROW_TAG)
                        .padding(vertical = 8.dp),
                )
                if (keyStatus is WhisperKeyStatus.Set) {
                    Text(
                        text = "Clear",
                        color = PocketShellColors.Accent,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onClearKey(provider) }
                            .testTag(ASSISTANT_API_KEY_CLEAR_TAG)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showKeyDialog) {
        AssistantApiKeyEntryDialog(
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                onSaveKey(provider, key)
                java.util.Arrays.fill(key, ' ')
                showKeyDialog = false
            },
        )
    }
}

@Composable
private fun AssistantProviderRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * One-field masked dialog for entering the assistant provider's API key.
 * Mirrors [VoiceApiKeyEntryDialog] so the entry UX is consistent across
 * the two key surfaces.
 */
@Composable
internal fun AssistantApiKeyEntryDialog(
    onDismiss: () -> Unit,
    onSave: (CharArray) -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assistant API key", color = PocketShellColors.Text) },
        text = {
            Column {
                Text(
                    text = "Paste the API key for the selected provider. It's stored " +
                        "encrypted on this device and only sent in the request header.",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ASSISTANT_API_KEY_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = keyText.toCharArray()
                    onSave(chars)
                    keyText = ""
                },
                enabled = keyText.isNotBlank(),
                modifier = Modifier.testTag(ASSISTANT_API_KEY_SAVE_TAG),
            ) {
                Text("Save", color = PocketShellColors.Accent)
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
 * Issue #114 Fix A: entry point to the Usage / quota panel. Lives under
 * Settings because the panel surfaces cross-host server-side state, not a
 * per-host preference. Tapping the row routes to
 * [com.pocketshell.app.nav.AppDestination.Usage] which renders
 * `UsageScreen` populated from every bootstrapped host that has the
 * pocketshell CLI installed.
 *
 * Fix B (compact dashboard strip on the host list) and Fix C
 * (bootstrap-aware periodic fetch + per-host command override) extend the
 * surfaces — both are tracked as follow-up issues so the panel becomes
 * reachable in this issue without scope creep.
 */
@Composable
private fun UsageSection(
    onOpenUsage: () -> Unit,
    hasUsageInstalledHost: Boolean,
    providerRecords: List<com.pocketshell.core.usage.UsageProviderRecord> = emptyList(),
    warnThresholdPercent: Int = AppSettings.DEFAULT_USAGE_WARN_PERCENT,
    onWarnThresholdChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    Column {
        SectionLabel("Usage")
        SectionCard {
            SettingsNavRow(
                title = "Usage & quota",
                description = "Provider quotas reported by pocketshell on bootstrapped hosts.",
                onClick = onOpenUsage,
                testTag = USAGE_OPEN_TAG,
            )

            // Issue #214: per-provider state list rendered inline in
            // the Settings → Usage section. Surfaces the same threshold
            // tint the host card / sessions chip use so a user opening
            // Settings sees, at a glance, which providers are
            // approaching / critical / exceeded their reported limits.
            // The list is keyed off the user-configurable warn
            // threshold so dragging the slider below updates the row
            // tints live.
            if (providerRecords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                com.pocketshell.app.usage.UsageProviderStateList(
                    records = providerRecords,
                    warnPercent = warnThresholdPercent.toDouble(),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Issue #214: "Warn me when usage exceeds X%" slider.
            // Only rendered when at least one pocketshell-installed host
            // exists — adjusting the threshold for an empty workspace
            // is meaningless (the warning surfaces would have nothing
            // to threshold against).
            if (hasUsageInstalledHost) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Warn me when usage exceeds",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$warnThresholdPercent%",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag(USAGE_WARN_THRESHOLD_VALUE_TAG),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Default 80%. Critical (95%) and exceeded (100%) are fixed.",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val sliderRange = AppSettings.MIN_USAGE_WARN_PERCENT.toFloat()..
                    AppSettings.MAX_USAGE_WARN_PERCENT.toFloat()
                val step = AppSettings.USAGE_WARN_PERCENT_STEP
                val sliderSteps = (
                    (AppSettings.MAX_USAGE_WARN_PERCENT - AppSettings.MIN_USAGE_WARN_PERCENT) / step
                    ).coerceAtLeast(1) - 1
                Slider(
                    value = warnThresholdPercent.toFloat(),
                    onValueChange = { onWarnThresholdChange(it.roundToInt()) },
                    valueRange = sliderRange,
                    steps = sliderSteps,
                    colors = SliderDefaults.colors(
                        thumbColor = PocketShellColors.Accent,
                        activeTrackColor = PocketShellColors.Accent,
                        inactiveTrackColor = PocketShellColors.Border,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(USAGE_WARN_THRESHOLD_SLIDER_TAG),
                )
            }

            // Issue #157 polish item 5: when no host has `pocketshell` installed
            // the cross-host Usage dashboard strip (issue #116) is hidden
            // — by design, "no empty rail" — but that also leaves the user
            // with no discoverable way to learn that the panel exists.
            // Render an inline hint inside the same section card so a
            // first-time user opening Settings sees both the existing row
            // (still tappable so they can see the empty state) AND the
            // nudge to install `pocketshell` on a host.
            //
            // The Usage docs link surfaces `docs/usage-panel.md` via the
            // GitHub web view (no in-app docs browser today). The system
            // browser handles the rest; we never silently install or
            // download.
            if (!hasUsageInstalledHost) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = PocketShellColors.SurfaceElev,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = PocketShellColors.BorderSoft,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag(USAGE_EMPTY_HINT_TAG),
                ) {
                    Text(
                        text = "No pocketshell-installed hosts detected",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Install pocketshell on a host to see provider quotas here.",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Learn more about pocketshell usage",
                        color = PocketShellColors.Accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(role = Role.Button) {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(USAGE_DOCS_URL),
                                )
                                runCatching { context.startActivity(intent) }
                            }
                            .testTag(USAGE_EMPTY_HINT_DOCS_LINK_TAG)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(onOpenCrashReports: () -> Unit) {
    Column {
        SectionLabel("Diagnostics")
        SectionCard {
            SettingsNavRow(
                title = "Crash reports",
                description = "Local-only crash logs you can share manually.",
                onClick = onOpenCrashReports,
                testTag = DIAGNOSTICS_CRASHES_TAG,
            )
        }
    }
}

/**
 * Issue #206: per-host "Watched folders" picker. Renders a row per
 * saved host that routes the user to the per-host config screen.
 *
 * Lives under Settings (not host-detail) because the per-host detail
 * screen does not exist as a stand-alone surface today — host editing
 * is gated behind the QR import / add-host form. Surfacing the
 * watched-folders entry here gives users a discoverable entry that
 * doesn't depend on remembering the kebab.
 *
 * Discovery (the SSH probe of `~/git` etc.) is only available from
 * the host-list kebab path because Settings has no decrypted
 * passphrase to authenticate the one-shot SSH session.
 */
@Composable
private fun WorkspaceRootsSection(
    hosts: List<com.pocketshell.core.storage.entity.HostEntity>,
    onPickHost: (Long, String) -> Unit,
) {
    Column {
        SectionLabel("Workspace")
        SectionCard {
            Text(
                text = "Per-host workspace roots",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Configure host-detail roots and tree/flat defaults.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (hosts.isEmpty()) {
                Text(
                    text = "Add a host first to configure workspace roots.",
                    color = PocketShellColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag(WATCHED_FOLDERS_SETTINGS_EMPTY_TAG),
                )
            } else {
                hosts.forEach { host ->
                    ListRow(
                        title = host.name,
                        subtitle = "${host.username}@${host.hostname}:${host.port}",
                        trailing = { NavChevron() },
                        onClick = { onPickHost(host.id, host.name) },
                        modifier = Modifier.testTag(watchedFoldersSettingsHostRowTag(host.id)),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AboutFooter(appBuildInfo: AppBuildInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 12.dp)
            .testTag(ABOUT_FOOTER_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "About",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = appBuildInfo.displayText(),
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag(ABOUT_VERSION_TAG),
        )
    }
}

internal data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long?,
) {
    fun displayText(): String = buildString {
        append("PocketShell v")
        append(versionName)
        versionCode?.let {
            append(" (build ")
            append(it)
            append(")")
        }
    }
}

internal const val SETTINGS_LAZY_COLUMN_TAG = "settings:lazy-column"
internal const val SETTINGS_BACK_TAG = "settings:back"
internal const val SETTINGS_TITLE_TAG = "settings:title"
internal const val HOST_IMPORT_ROW_TAG = "settings:host-import:row"
internal const val HOST_IMPORT_DIALOG_COPY_TAG = "settings:host-import:dialog-copy"
internal const val HOST_IMPORT_SCAN_QR_TAG = "settings:host-import:scan-qr"
internal const val HOST_IMPORT_CHOOSE_FILE_TAG = "settings:host-import:choose-file"
internal const val HOST_IMPORT_SETTINGS_COPY =
    "Import an SSH host QR/text payload generated by pocketshell qr-share or a compatible pocketshell.ssh-import.v1 file."
internal const val HOST_IMPORT_DIALOG_COPY =
    "This imports one PocketShell SSH host payload generated by pocketshell qr-share or a compatible pocketshell.ssh-import.v1 file. It does not import app settings, costs, sessions, or backups."
internal const val TERMINAL_FONT_SLIDER_TAG = "settings:terminal:font-slider"
// Issue #496: conversation message-body font-size slider + its right-aligned
// "XXsp" value label, placed beside the terminal font slider.
internal const val CONVERSATION_FONT_SLIDER_TAG = "settings:terminal:conversation-font-slider"
internal const val CONVERSATION_FONT_VALUE_TAG = "settings:terminal:conversation-font-value"
internal const val TERMINAL_SMART_TEXT_SWITCH_TAG = "settings:terminal:smart-text-switch"
internal const val TMUX_SWITCH_TAG = "settings:terminal:tmux-switch"
// Issue #526: agent-submit Enter delay slider + its right-aligned "Xms"
// value label, placed under Settings → Terminal.
internal const val AGENT_SUBMIT_DELAY_SLIDER_TAG = "settings:terminal:agent-submit-delay-slider"
internal const val AGENT_SUBMIT_DELAY_VALUE_TAG = "settings:terminal:agent-submit-delay-value"
internal const val DEFAULT_HOST_NONE_TAG = "settings:startup:default-host:none"
internal const val DEFAULT_HOST_EMPTY_TAG = "settings:startup:default-host:empty"
internal const val DIAGNOSTICS_CRASHES_TAG = "settings:diagnostics:crashes"
internal const val USAGE_OPEN_TAG = "settings:usage:open"
internal const val USAGE_EMPTY_HINT_TAG = "settings:usage:empty-hint"
internal const val USAGE_EMPTY_HINT_DOCS_LINK_TAG = "settings:usage:empty-hint:docs-link"

/**
 * Issue #214: stable test tags for the "Warn me when usage exceeds X%"
 * slider added to Settings → Usage. Value tag rides on the right-aligned
 * `XX%` label so a connected test can assert the displayed value
 * without depending on the slider widget's accessibility tree.
 */
internal const val USAGE_WARN_THRESHOLD_SLIDER_TAG = "settings:usage:warn-threshold-slider"
internal const val USAGE_WARN_THRESHOLD_VALUE_TAG = "settings:usage:warn-threshold-value"

/**
 * GitHub web URL to the in-repo usage panel docs (the local mirror is
 * `docs/usage-panel.md`). Hard-coded here because the app does not yet
 * have an in-app docs browser; tapping the link opens the system
 * browser via `Intent.ACTION_VIEW`.
 */
internal const val USAGE_DOCS_URL: String =
    "https://github.com/alexeygrigorev/pocketshell/blob/main/docs/usage-panel.md"
internal const val ABOUT_FOOTER_TAG = "settings:about:footer"
internal const val ABOUT_VERSION_TAG = "settings:about:version"
internal const val VOICE_API_KEY_ROW_TAG = "settings:voice:api-key-row"
internal const val VOICE_API_KEY_CLEAR_TAG = "settings:voice:api-key-clear"
internal const val VOICE_API_KEY_FIELD_TAG = "settings:voice:api-key-field"
internal const val VOICE_API_KEY_SAVE_TAG = "settings:voice:api-key-save"
internal const val VOICE_SILENCE_SLIDER_TAG = "settings:voice:silence-slider"
internal const val VOICE_SILENCE_VALUE_TAG = "settings:voice:silence-value"
internal const val VOICE_AI_COSTS_ROW_TAG = "settings:voice:ai-costs-row"
internal const val ASSISTANT_PROVIDER_OPENAI_TAG = "settings:assistant:provider-openai"
internal const val ASSISTANT_PROVIDER_ANTHROPIC_TAG = "settings:assistant:provider-anthropic"
internal const val ASSISTANT_PROVIDER_ZAI_TAG = "settings:assistant:provider-zai"
internal const val ASSISTANT_BASE_URL_FIELD_TAG = "settings:assistant:base-url-field"
internal const val ASSISTANT_MODEL_FIELD_TAG = "settings:assistant:model-field"
internal const val ASSISTANT_API_KEY_ROW_TAG = "settings:assistant:api-key-row"
internal const val ASSISTANT_API_KEY_CLEAR_TAG = "settings:assistant:api-key-clear"
internal const val ASSISTANT_API_KEY_FIELD_TAG = "settings:assistant:api-key-field"
internal const val ASSISTANT_API_KEY_SAVE_TAG = "settings:assistant:api-key-save"

// Issue #206: stable tags for the per-host watched-folders picker in
// Settings. The empty-state tag fires when the user has no hosts
// configured yet; per-host row tags include the host id so the
// connected E2E test can drive a specific host without depending on
// list ordering.
const val WATCHED_FOLDERS_SETTINGS_EMPTY_TAG: String = "settings:watched-folders:empty"

fun watchedFoldersSettingsHostRowTag(hostId: Long): String =
    "settings:watched-folders:host:$hostId"

fun defaultHostOptionTag(hostId: Long): String =
    "settings:startup:default-host:$hostId"

internal fun voiceLanguageOptionTestTag(code: String): String =
    "settings:voice:language:" + code.lowercase()

internal fun voiceProviderOptionTestTag(provider: VoiceTranscriptionProvider): String =
    "settings:voice:provider:" + provider.name.lowercase()

/**
 * Stable test tag for the section label above each [SectionCard].
 * Mirrors the lowercase-without-spaces convention already in use for
 * tags like `settings:voice:language:*`.
 */
internal fun sectionLabelTestTag(label: String): String =
    "settings:section-label:" + label.lowercase().replace(" ", "-")
