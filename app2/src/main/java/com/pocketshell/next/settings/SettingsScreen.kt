package com.pocketshell.next.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.roundToInt

/** Stable test tags. */
const val SETTINGS_LIST_TAG: String = "settings-list"
const val SETTINGS_BACK_TAG: String = "settings-back"
const val SETTINGS_TERMINAL_SIZE_SLIDER_TAG: String = "settings-terminal-size-slider"
const val SETTINGS_TERMINAL_SIZE_VALUE_TAG: String = "settings-terminal-size-value"
const val SETTINGS_USAGE_WARN_SLIDER_TAG: String = "settings-usage-warn-slider"
const val SETTINGS_USAGE_WARN_VALUE_TAG: String = "settings-usage-warn-value"
const val SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG: String = "settings-agent-submit-delay-slider"
const val SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG: String = "settings-agent-submit-delay-value"
const val SETTINGS_WORKSPACE_EMPTY_TAG: String = "settings-workspace-empty"
const val SETTINGS_CRASH_REPORTS_TAG: String = "settings-crash-reports"
const val SETTINGS_VERSION_TAG: String = "settings-version"

fun backgroundGraceOptionTag(millis: Long): String = "settings-grace-$millis"

fun voiceLanguageOptionTag(code: String): String = "settings-voice-lang-$code"

fun settingsHostRowTag(hostId: Long): String = "settings-host-$hostId"

/** Installed build identity, rendered by the About footer. */
data class AppBuildInfo(val versionName: String, val versionCode: Long?) {
    /** `v0.4.51 (4051)`, or just the name when the code could not be read. */
    fun displayText(): String =
        if (versionCode == null) "v$versionName" else "v$versionName ($versionCode)"
}

/**
 * Route-level entry point: binds the Hilt-provided [SettingsViewModel] and the
 * one thing it cannot own — the installed build identity, which comes from
 * `PackageManager` and therefore needs a `Context`.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenWorkspaceRoots: (Long) -> Unit,
    onOpenCrashReports: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val context = LocalContext.current
    val buildInfo = remember(context) { readBuildInfo(context) }

    SettingsScreen(
        settings = settings,
        hosts = hosts,
        buildInfo = buildInfo,
        onBack = onBack,
        onTerminalTextSizeChange = viewModel::setTerminalTextSizePx,
        onVoiceLanguageChange = viewModel::setVoiceLanguage,
        onUsageWarnThresholdChange = viewModel::setUsageWarnThresholdPercent,
        onBackgroundGraceChange = viewModel::setBackgroundGraceMillis,
        onAgentSubmitEnterDelayChange = viewModel::setAgentSubmitEnterDelayMs,
        onOpenWorkspaceRoots = onOpenWorkspaceRoots,
        onOpenCrashReports = onOpenCrashReports,
        modifier = modifier,
    )
}

/**
 * App settings (rewrite task P-6) — the old client's settings surface, ported
 * minus everything that configured deleted machinery.
 *
 * Six sections, ordered most-used-first, all built from ui-kit primitives so
 * this screen adds no visual vocabulary of its own:
 *
 *  1. **Terminal** — glyph size, the background grace window (a
 *     terminal-connection property, so it lives with the terminal rather than
 *     in a "Connection" section of one row), and the agent-submit Enter delay
 *     (issue #2526: body then Enter as two PTY writes).
 *  2. **Voice** — the dictation language hint.
 *  3. **Usage** — the "approaching limit" percent for the quota panel.
 *  4. **Workspace** — a host picker into that host's saved roots.
 *  5. **Diagnostics** — the local crash-report browser (issue #2476). It sits
 *     next to About because both answer "what build am I on and what went
 *     wrong with it", which is the question a user has when reporting a bug.
 *  6. **About** — the installed build, as a footer.
 *
 * Stateless: every value comes in, every change goes out. See [AppSettings] for
 * the table of ported-away fields, and [SettingsRepository] for which of the
 * kept ones are stored-but-not-yet-read.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    hosts: List<SettingsHostRow>,
    buildInfo: AppBuildInfo,
    onBack: () -> Unit,
    onTerminalTextSizeChange: (Int) -> Unit,
    onVoiceLanguageChange: (String) -> Unit,
    onUsageWarnThresholdChange: (Int) -> Unit,
    onBackgroundGraceChange: (Long) -> Unit,
    onAgentSubmitEnterDelayChange: (Int) -> Unit,
    onOpenWorkspaceRoots: (Long) -> Unit,
    onOpenCrashReports: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Settings",
            leading = {
                PocketShellButton(
                    text = "‹",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(SETTINGS_BACK_TAG),
                )
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SETTINGS_LIST_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            item {
                SettingsSection("Terminal") {
                    StepperSlider(
                        title = "Text size",
                        description = "Glyph size of the terminal grid, in pixels.",
                        value = settings.terminalTextSizePx,
                        valueLabel = "${settings.terminalTextSizePx} px",
                        min = AppSettings.MIN_TERMINAL_TEXT_SIZE_PX,
                        max = AppSettings.MAX_TERMINAL_TEXT_SIZE_PX,
                        step = AppSettings.TERMINAL_TEXT_SIZE_STEP_PX,
                        onChange = onTerminalTextSizeChange,
                        sliderTestTag = SETTINGS_TERMINAL_SIZE_SLIDER_TAG,
                        valueTestTag = SETTINGS_TERMINAL_SIZE_VALUE_TAG,
                    )
                    Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                    SettingLabel(
                        title = "Background grace",
                        description = "How long a live session is held when the app " +
                            "leaves the foreground, before it disconnects.",
                    )
                    AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
                        OptionRow(
                            label = option.label,
                            selected = settings.backgroundGraceMillis == option.millis,
                            onClick = { onBackgroundGraceChange(option.millis) },
                            testTag = backgroundGraceOptionTag(option.millis),
                        )
                    }
                    Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                    StepperSlider(
                        title = "Agent submit delay",
                        description = "Pause after typing a message before pressing Enter, so the " +
                            "agent submits it instead of leaving it in the input. Raise this " +
                            "if Send sometimes leaves text unsent.",
                        value = settings.agentSubmitEnterDelayMs,
                        valueLabel = "${settings.agentSubmitEnterDelayMs}ms",
                        min = AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS,
                        max = AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS,
                        step = AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS,
                        onChange = onAgentSubmitEnterDelayChange,
                        sliderTestTag = SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG,
                        valueTestTag = SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG,
                    )
                }
            }

            item {
                SettingsSection("Voice") {
                    SettingLabel(
                        title = "Dictation language",
                        description = "Hint passed to the speech recognizer.",
                    )
                    AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
                        OptionRow(
                            label = option.label,
                            selected = settings.voiceLanguage == option.code,
                            onClick = { onVoiceLanguageChange(option.code) },
                            testTag = voiceLanguageOptionTag(option.code),
                        )
                    }
                }
            }

            item {
                SettingsSection("Usage") {
                    StepperSlider(
                        title = "Warn at",
                        description = "Percent of a provider quota at which the usage " +
                            "panel starts warning. Critical (95%) is fixed.",
                        value = settings.usageWarnThresholdPercent,
                        valueLabel = "${settings.usageWarnThresholdPercent}%",
                        min = AppSettings.MIN_USAGE_WARN_PERCENT,
                        max = AppSettings.MAX_USAGE_WARN_PERCENT,
                        step = AppSettings.USAGE_WARN_PERCENT_STEP,
                        onChange = onUsageWarnThresholdChange,
                        sliderTestTag = SETTINGS_USAGE_WARN_SLIDER_TAG,
                        valueTestTag = SETTINGS_USAGE_WARN_VALUE_TAG,
                    )
                }
            }

            item {
                SettingsSection("Workspace") {
                    SettingLabel(
                        title = "Per-host roots",
                        description = "Folders you work in on each host.",
                    )
                    if (hosts.isEmpty()) {
                        Text(
                            text = "Add a host first.",
                            color = PocketShellColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .padding(vertical = PocketShellSpacing.sm)
                                .testTag(SETTINGS_WORKSPACE_EMPTY_TAG),
                        )
                    } else {
                        hosts.forEach { host ->
                            ListRow(
                                title = host.name,
                                subtitle = host.subtitle,
                                trailing = { NavigationChevron() },
                                onClick = { onOpenWorkspaceRoots(host.id) },
                                modifier = Modifier.testTag(settingsHostRowTag(host.id)),
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection("Diagnostics") {
                    ListRow(
                        title = "Crash reports",
                        // Kept short on purpose: `ListRow`'s subtitle is a
                        // single ellipsized line at this density, so anything
                        // longer renders as "…" (caught in the #2476 render).
                        subtitle = "Recorded on this device",
                        trailing = { NavigationChevron() },
                        onClick = onOpenCrashReports,
                        modifier = Modifier.testTag(SETTINGS_CRASH_REPORTS_TAG),
                    )
                }
            }

            item { AboutFooter(buildInfo) }
        }
    }
}

/** A titled card of rows — the one container shape this screen uses. */
@Composable
private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionHeader(label = label)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.md)
                .background(PocketShellColors.Surface, PocketShellShapes.small)
                .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                .padding(PocketShellSpacing.md),
            content = content,
        )
    }
}

/** The name + one-line explanation above a control. */
@Composable
private fun SettingLabel(title: String, description: String) {
    Text(
        text = title,
        color = PocketShellColors.Text,
        style = PocketShellType.bodyDense,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = description,
        color = PocketShellColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * An integer slider that reports only whole grid stops.
 *
 * Compose's [Slider] emits continuous floats even with `steps` set (the thumb
 * animates between stops), so a caller that rounded on write would still write
 * on every animation frame. Rounding to the grid HERE and letting the
 * repository's no-op-if-unchanged guard swallow the repeats is what keeps one
 * drag from producing forty preference writes.
 */
@Composable
private fun StepperSlider(
    title: String,
    description: String,
    value: Int,
    valueLabel: String,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    sliderTestTag: String,
    valueTestTag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            SettingLabel(title = title, description = description)
        }
        Text(
            text = valueLabel,
            color = PocketShellColors.Accent,
            style = PocketShellType.labelMono,
            modifier = Modifier.testTag(valueTestTag),
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { raw ->
            val steps = ((raw - min) / step).roundToInt()
            onChange((min + steps * step).coerceIn(min, max))
        },
        valueRange = min.toFloat()..max.toFloat(),
        // `steps` counts the stops BETWEEN the ends, hence the -1.
        steps = ((max - min) / step) - 1,
        colors = SliderDefaults.colors(
            thumbColor = PocketShellColors.Accent,
            activeTrackColor = PocketShellColors.AccentDim,
            inactiveTrackColor = PocketShellColors.Border,
        ),
        modifier = Modifier.testTag(sliderTestTag),
    )
}

/** One radio-style choice in a fixed option list. */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    ListRow(
        title = label,
        leading = { RadioMark(selected) },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * The selected/unselected disc.
 *
 * Drawn from ui-kit tokens rather than `RadioButton` so the option lists match
 * the rest of the app's chrome instead of Material's default palette — the same
 * reason the old client hand-rolled its own.
 */
@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(
                width = 1.dp,
                color = if (selected) PocketShellColors.Accent else PocketShellColors.Border,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(PocketShellColors.Accent, CircleShape),
            )
        }
    }
}

/**
 * The installed build, at the bottom.
 *
 * A footer, not a section, and deliberately without the old client's
 * check-for-updates row: that row drove the self-distribution update notifier
 * the scope amendment cut, and it is the only part of About that needed the
 * network.
 */
@Composable
private fun AboutFooter(buildInfo: AppBuildInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PocketShellSpacing.sm, bottom = PocketShellSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PocketShell",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildInfo.displayText(),
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
            modifier = Modifier.testTag(SETTINGS_VERSION_TAG),
        )
    }
}

/**
 * Reads the installed `versionName` / version code.
 *
 * Guarded: `getPackageInfo` can throw for its own package under an unusual
 * install state, and About is the least important thing on the screen — it must
 * never be the reason Settings fails to open.
 */
private fun readBuildInfo(context: android.content.Context): AppBuildInfo = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    AppBuildInfo(
        versionName = info.versionName ?: UNKNOWN_VERSION,
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        },
    )
}.getOrElse { AppBuildInfo(versionName = UNKNOWN_VERSION, versionCode = null) }

private const val UNKNOWN_VERSION = "unknown"
