package com.pocketshell.app.settings

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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.theme.PocketShellColors
import kotlin.math.roundToInt

/**
 * Settings home — landing surface for app-level preferences introduced
 * in issue #112.
 *
 * Sections (top-to-bottom):
 *
 *  - **Appearance** — radio group bound to [ThemePreference]. Tapping a
 *    row updates the preference; the activity-level
 *    [com.pocketshell.app.settings.SettingsRepository] re-emits and
 *    [com.pocketshell.app.MainActivity] applies the new mode without
 *    restart.
 *  - **Terminal** — slider for default terminal font size (sp) and a
 *    switch for the "use tmux on attach when available" preference.
 *  - **Diagnostics** — single row linking to the existing
 *    `CrashReportsScreen`. The actual relocation of the Crashes top-bar
 *    affordance is left to a follow-up (so as not to clash with the
 *    parallel #110 tab work).
 *  - **About** — installed `versionName` read from `PackageManager`,
 *    matching the lookup pattern already used by
 *    [com.pocketshell.app.hosts.HostListScreen]'s footer.
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsState()
    val keyStatus by viewModel.keyStatus.collectAsState()
    val context = LocalContext.current

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
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
            item {
                AppearanceSection(
                    selected = settings.theme,
                    onSelect = viewModel::setTheme,
                )
            }
            item {
                TerminalSection(
                    fontSizeSp = settings.terminalFontSizeSp,
                    onFontSizeChange = viewModel::setTerminalFontSizeSp,
                    tmuxOnAttach = settings.tmuxOnAttachByDefault,
                    onTmuxOnAttachChange = viewModel::setTmuxOnAttachByDefault,
                )
            }
            item {
                VoiceSection(
                    keyStatus = keyStatus,
                    language = settings.voiceLanguage,
                    silenceThresholdSeconds = settings.voiceSilenceThresholdSeconds,
                    onSaveApiKey = viewModel::saveApiKey,
                    onClearApiKey = viewModel::clearApiKey,
                    onLanguageSelected = viewModel::setVoiceLanguage,
                    onSilenceThresholdChange = viewModel::setVoiceSilenceThresholdSeconds,
                    onOpenAiCosts = onOpenAiCosts,
                )
            }
            item {
                UsageSection(onOpenUsage = onOpenUsage)
            }
            item {
                DiagnosticsSection(onOpenCrashReports = onOpenCrashReports)
            }
            item {
                AboutSection(versionName = versionName)
            }
        }
    }
}

@Composable
private fun SettingsAppBar(onBack: () -> Unit) {
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
                .testTag(SETTINGS_BACK_TAG),
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
            text = "Settings",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 4.dp)
                .testTag(SETTINGS_TITLE_TAG),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = PocketShellColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
    )
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

@Composable
private fun AppearanceSection(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    Column {
        SectionLabel("Appearance")
        SectionCard {
            Text(
                text = "Theme",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose how PocketShell looks. System follows your device setting.",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ThemePreference.entries.forEach { option ->
                ThemeOptionRow(
                    label = option.label(),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    testTag = themeOptionTestTag(option),
                )
            }
        }
    }
}

private fun ThemePreference.label(): String = when (this) {
    ThemePreference.System -> "System default"
    ThemePreference.Light -> "Light"
    ThemePreference.Dark -> "Dark"
}

@Composable
private fun ThemeOptionRow(
    label: String,
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
        Text(
            text = label,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
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

@Composable
private fun TerminalSection(
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    tmuxOnAttach: Boolean,
    onTmuxOnAttachChange: (Boolean) -> Unit,
) {
    Column {
        SectionLabel("Terminal")
        SectionCard {
            Text(
                text = "Default font size",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Default to attaching via tmux on hosts that have it installed.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
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
 *    [AppSettings.VOICE_SILENCE_STEP_SECONDS] increments. The current
 *    value is rendered next to the label as `Xs`. Both the prompt
 *    composer and inline dictation read the latest snapshot before each
 *    recording starts, so a slider drag while the mic is idle takes
 *    effect on the next tap without any restart.
 */
@Composable
private fun VoiceSection(
    keyStatus: WhisperKeyStatus,
    language: String,
    silenceThresholdSeconds: Float,
    onSaveApiKey: (CharArray) -> Unit,
    onClearApiKey: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onSilenceThresholdChange: (Float) -> Unit,
    onOpenAiCosts: () -> Unit = {},
) {
    var showKeyDialog by remember { mutableStateOf(false) }

    Column {
        SectionLabel("Voice")
        SectionCard {
            // -- API key row --------------------------------------------
            Text(
                text = "Whisper API key",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Stored encrypted on this device. Only sent to api.openai.com.",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
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
                    fontSize = 13.sp,
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
                        fontSize = 13.sp,
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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Hint Whisper about the spoken language. Auto-detect works for most cases.",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatThresholdLabel(silenceThresholdSeconds)}s",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(VOICE_SILENCE_VALUE_TAG),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Recording auto-stops after this many seconds of silence.",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
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

            Spacer(modifier = Modifier.height(16.dp))

            // -- AI Costs row -------------------------------------------
            //
            // Issue #181: opens the client-side AI cost tracker. Lives
            // under Voice (rather than Diagnostics or its own section)
            // because the only AI feature wired today is the Whisper
            // call site directly above; future LLM features (planner,
            // chat) sit in the same section.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenAiCosts)
                    .padding(vertical = 8.dp)
                    .testTag(VOICE_AI_COSTS_ROW_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Costs",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Track OpenAI spend per voice transcription.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "›",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
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
                    fontSize = 12.sp,
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
 * the trailing `.0` for whole-second values so the label reads `5s`
 * rather than `5.0s`; otherwise renders one decimal (`1.5s`).
 */
internal fun formatThresholdLabel(seconds: Float): String {
    val rounded = (seconds * 10f).roundToInt() / 10f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) asInt.toString() else "%.1f".format(rounded)
}

/**
 * Issue #114 Fix A: entry point to the Usage / quota panel. Lives under
 * Settings because the panel surfaces cross-host server-side state, not a
 * per-host preference. Tapping the row routes to
 * [com.pocketshell.app.nav.AppDestination.Usage] which renders
 * `UsageScreen` populated from every bootstrapped host that has the quse
 * CLI installed.
 *
 * Fix B (compact dashboard strip on the host list) and Fix C
 * (bootstrap-aware periodic fetch + per-host command override) extend the
 * surfaces — both are tracked as follow-up issues so the panel becomes
 * reachable in this issue without scope creep.
 */
@Composable
private fun UsageSection(onOpenUsage: () -> Unit) {
    Column {
        SectionLabel("Usage")
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenUsage)
                    .padding(vertical = 8.dp)
                    .testTag(USAGE_OPEN_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usage & quota",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Provider quotas reported by quse on bootstrapped hosts.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "›",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(onOpenCrashReports: () -> Unit) {
    Column {
        SectionLabel("Diagnostics")
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenCrashReports)
                    .padding(vertical = 8.dp)
                    .testTag(DIAGNOSTICS_CRASHES_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Crash reports",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Local-only crash logs you can share manually.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "›",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AboutSection(versionName: String) {
    Column {
        SectionLabel("About")
        SectionCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PocketShell",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Voice-first, tmux-native, agent-aware SSH client.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "v$versionName",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(ABOUT_VERSION_TAG),
                )
            }
        }
    }
}

internal const val SETTINGS_LAZY_COLUMN_TAG = "settings:lazy-column"
internal const val SETTINGS_BACK_TAG = "settings:back"
internal const val SETTINGS_TITLE_TAG = "settings:title"
internal const val TERMINAL_FONT_SLIDER_TAG = "settings:terminal:font-slider"
internal const val TMUX_SWITCH_TAG = "settings:terminal:tmux-switch"
internal const val DIAGNOSTICS_CRASHES_TAG = "settings:diagnostics:crashes"
internal const val USAGE_OPEN_TAG = "settings:usage:open"
internal const val ABOUT_VERSION_TAG = "settings:about:version"
internal const val VOICE_API_KEY_ROW_TAG = "settings:voice:api-key-row"
internal const val VOICE_API_KEY_CLEAR_TAG = "settings:voice:api-key-clear"
internal const val VOICE_API_KEY_FIELD_TAG = "settings:voice:api-key-field"
internal const val VOICE_API_KEY_SAVE_TAG = "settings:voice:api-key-save"
internal const val VOICE_SILENCE_SLIDER_TAG = "settings:voice:silence-slider"
internal const val VOICE_SILENCE_VALUE_TAG = "settings:voice:silence-value"
internal const val VOICE_AI_COSTS_ROW_TAG = "settings:voice:ai-costs-row"

internal fun themeOptionTestTag(theme: ThemePreference): String =
    "settings:appearance:theme:" + theme.name.lowercase()

internal fun voiceLanguageOptionTestTag(code: String): String =
    "settings:voice:language:" + code.lowercase()
