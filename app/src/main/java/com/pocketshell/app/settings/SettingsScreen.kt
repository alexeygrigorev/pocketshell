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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsState()
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
            modifier = Modifier.fillMaxSize(),
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

internal const val SETTINGS_BACK_TAG = "settings:back"
internal const val SETTINGS_TITLE_TAG = "settings:title"
internal const val TERMINAL_FONT_SLIDER_TAG = "settings:terminal:font-slider"
internal const val TMUX_SWITCH_TAG = "settings:terminal:tmux-switch"
internal const val DIAGNOSTICS_CRASHES_TAG = "settings:diagnostics:crashes"
internal const val ABOUT_VERSION_TAG = "settings:about:version"

internal fun themeOptionTestTag(theme: ThemePreference): String =
    "settings:appearance:theme:" + theme.name.lowercase()
