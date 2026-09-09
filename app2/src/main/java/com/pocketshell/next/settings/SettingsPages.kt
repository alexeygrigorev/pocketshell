package com.pocketshell.next.settings

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.next.release.ReleaseCheckResult
import com.pocketshell.next.release.ReleaseInfo
import com.pocketshell.next.release.UpdateCheckViewModel
import com.pocketshell.next.release.launchUpdateUrl
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.QuietChoiceRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.roundToInt

@Composable
internal fun TerminalSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    TerminalSettingsScreen(
        settings = settings,
        onBack = onBack,
        onTerminalTextSizeChange = viewModel::setTerminalTextSizePx,
        onShowCommonKeysChange = viewModel::setShowCommonKeys,
        modifier = modifier,
    )
}

@Composable
internal fun VoiceSettingsRoute(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    VoiceSettingsScreen(
        settings = settings,
        onBack = onBack,
        onOpenLanguage = onOpenLanguage,
        modifier = modifier,
    )
}

@Composable
internal fun LanguageSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    LanguageSettingsScreen(
        settings = settings,
        onBack = onBack,
        onVoiceLanguageChange = viewModel::setVoiceLanguage,
        modifier = modifier,
    )
}

@Composable
internal fun ConnectionSettingsRoute(
    onBack: () -> Unit,
    onOpenGrace: () -> Unit,
    onOpenWorkspaceRoots: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    ConnectionSettingsScreen(
        settings = settings,
        hosts = hosts,
        onBack = onBack,
        onOpenGrace = onOpenGrace,
        onReconnectWhenReturnChange = viewModel::setReconnectWhenReturn,
        onOpenWorkspaceRoots = onOpenWorkspaceRoots,
        modifier = modifier,
    )
}

@Composable
internal fun GraceSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    GraceSettingsScreen(
        settings = settings,
        onBack = onBack,
        onBackgroundGraceChange = viewModel::setBackgroundGraceMillis,
        modifier = modifier,
    )
}

@Composable
internal fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    AdvancedSettingsScreen(
        settings = settings,
        onBack = onBack,
        onVoiceSilenceChange = viewModel::setVoiceSilenceThresholdSeconds,
        onUsageWarnThresholdChange = viewModel::setUsageWarnThresholdPercent,
        onAgentSubmitEnterDelayChange = viewModel::setAgentSubmitEnterDelayMs,
        onResetAdvancedDefaults = viewModel::resetAdvancedDefaults,
        modifier = modifier,
    )
}

@Composable
internal fun AboutRoute(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckViewModel: UpdateCheckViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val checking by updateCheckViewModel.checking.collectAsStateWithLifecycle()
    val lastResult by updateCheckViewModel.lastResult.collectAsStateWithLifecycle()
    val buildInfo = remember(context) { readBuildInfo(context) }
    AboutScreen(
        buildInfo = buildInfo,
        updateCheckState = settingsUpdateCheckState(checking, lastResult),
        onBack = onBack,
        onOpenUpdate = onOpenUpdate,
        onOpenLicenses = {
            Toast.makeText(
                context,
                "Open licenses in the Android implementation",
                Toast.LENGTH_LONG,
            ).show()
        },
        modifier = modifier,
    )
}

@Composable
internal fun UpdateRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckViewModel: UpdateCheckViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val checking by updateCheckViewModel.checking.collectAsStateWithLifecycle()
    val lastResult by updateCheckViewModel.lastResult.collectAsStateWithLifecycle()
    val state = settingsUpdateCheckState(checking, lastResult)

    LaunchedEffect(updateCheckViewModel) {
        if (state is SettingsUpdateCheckState.Idle) {
            updateCheckViewModel.refreshNow()
        }
    }

    UpdateScreen(
        state = state,
        onBack = onBack,
        onCheckForUpdates = updateCheckViewModel::refreshNow,
        onOpenUrl = { url -> launchUpdateUrl(context, url) },
        modifier = modifier,
    )
}

@Composable
internal fun TerminalSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onTerminalTextSizeChange: (Int) -> Unit,
    onShowCommonKeysChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val terminalTextSizeSp = terminalTextSizeSpFromPx(settings.terminalTextSizePx, density)
    SettingsPageScaffold(
        title = "Terminal",
        pageTag = SETTINGS_TERMINAL_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Reading") }
        item {
            SettingsSlider(
                title = "Terminal text size",
                description = "Adjust the terminal text size for comfortable reading.",
                value = terminalTextSizeSp,
                valueLabel = "${terminalTextSizeSp.roundToInt()} sp",
                min = AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
                max = AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
                step = AppSettings.TERMINAL_TEXT_SIZE_STEP_SP,
                onChange = { onTerminalTextSizeChange(terminalTextSizePxFromSp(it, density)) },
                sliderTestTag = SETTINGS_TERMINAL_SIZE_SLIDER_TAG,
                valueTestTag = SETTINGS_TERMINAL_SIZE_VALUE_TAG,
            )
        }
        item {
            Text(
                text = "\$ git status\nOn branch main\nWorking tree clean",
                color = PocketShellColors.TermText,
                style = PocketShellType.bodyMono.copy(fontSize = terminalTextSizeSp.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellDensity.rowPadH)
                    .background(PocketShellColors.TermBg, PocketShellShapes.small)
                    .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                    .padding(PocketShellSpacing.md)
                    .testTag(SETTINGS_TERMINAL_SAMPLE_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Text and input",
                description = "App text follows your Android font-size setting.",
            )
        }
        item { SectionHeader(label = "Input") }
        item {
            ListRow(
                title = "Show common keys",
                subtitle = "Esc, Tab, Ctrl and arrows when typing.",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Keyboard,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                trailing = {
                    Switch(
                        checked = settings.showCommonKeys,
                        onCheckedChange = onShowCommonKeysChange,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_COMMON_KEYS_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Input stays separate from the grid",
                description = "The keyboard overlays the terminal without resizing its grid.",
            )
        }
    }
}

@Composable
internal fun VoiceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = AppSettings.VOICE_LANGUAGE_OPTIONS
        .firstOrNull { it.code == settings.voiceLanguage }
        ?.label
        ?: AppSettings.VOICE_LANGUAGE_OPTIONS.first().label
    SettingsPageScaffold(
        title = "Voice",
        pageTag = SETTINGS_VOICE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Dictation") }
        item {
            ListRow(
                title = "Language",
                subtitle = language,
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Mic, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenLanguage,
                modifier = Modifier.testTag("settings-voice-language"),
            )
        }
        item {
            ListRow(
                title = "Review before sending",
                subtitle = "Dictation always stops into an editable draft.",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Eye,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_VOICE_REVIEW_TAG),
            )
        }
        item {
            ListRow(
                title = "Speech recognition",
                subtitle = "System recognizer",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Mic,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_VOICE_RECOGNITION_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Microphone access",
                description = "PocketShell asks for microphone access when you start dictating.",
            )
        }
    }
}

@Composable
internal fun LanguageSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onVoiceLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Dictation language",
        pageTag = SETTINGS_LANGUAGE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Speech recognition") }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
                    QuietChoiceRow(
                        title = option.label,
                        subtitle = if (option.code == AppSettings.VOICE_LANGUAGE_AUTO) {
                            "Use the device language"
                        } else {
                            option.code.uppercase()
                        },
                        selected = settings.voiceLanguage == option.code,
                        onClick = { onVoiceLanguageChange(option.code) },
                        modifier = Modifier.testTag(voiceLanguageOptionTag(option.code)),
                    )
                }
            }
        }
        item {
            PocketShellButton(
                text = "Done",
                onClick = onBack,
                variant = ButtonVariant.Primary,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
    }
}

@Composable
internal fun ConnectionSettingsScreen(
    settings: AppSettings,
    hosts: List<SettingsHostRow>,
    onBack: () -> Unit,
    onOpenGrace: () -> Unit,
    onReconnectWhenReturnChange: (Boolean) -> Unit = {},
    onOpenWorkspaceRoots: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graceLabel = AppSettings.BACKGROUND_GRACE_OPTIONS
        .firstOrNull { it.millis == settings.backgroundGraceMillis }
        ?.label
        ?: "Default"
    SettingsPageScaffold(
        title = "Connections",
        pageTag = SETTINGS_CONNECTIONS_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Recovery") }
        item {
            ListRow(
                title = "Keep connection after leaving",
                subtitle = graceLabel,
                leading = { androidx.compose.material3.Icon(PocketShellIcons.History, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenGrace,
                modifier = Modifier.testTag("settings-connection-grace"),
            )
        }
        item {
            ListRow(
                title = "Reconnect when I return",
                subtitle = "Retry a dropped session automatically when PocketShell comes back.",
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Refresh, null, tint = PocketShellColors.TextSecondary) },
                trailing = {
                    Switch(
                        checked = settings.reconnectWhenReturn,
                        onCheckedChange = onReconnectWhenReturnChange,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_CONNECTION_RECONNECT_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Connection lifetime",
                description = "This controls the phone’s connection. Remote sessions are not deliberately ended when the app leaves the foreground.",
            )
        }
        item { SectionHeader(label = "Manage saved hosts") }
        if (hosts.isEmpty()) {
            item {
                EmptyState(
                    title = "No saved hosts",
                    description = "Add a host first to manage its workspace roots.",
                    icon = PocketShellIcons.Server,
                    modifier = Modifier
                        .height(180.dp)
                        .testTag(SETTINGS_WORKSPACE_EMPTY_TAG),
                )
            }
        } else {
            items(hosts, key = { it.id }) { host ->
                ListRow(
                    title = host.name,
                    subtitle = host.subtitle,
                    leading = { androidx.compose.material3.Icon(PocketShellIcons.Server, null, tint = PocketShellColors.TextSecondary) },
                    trailing = { NavigationChevron() },
                    onClick = { onOpenWorkspaceRoots(host.id) },
                    modifier = Modifier.testTag(settingsHostRowTag(host.id)),
                )
            }
        }
    }
}

@Composable
internal fun GraceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onBackgroundGraceChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Keep connection",
        pageTag = SETTINGS_GRACE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "When you leave the app") }
        item {
            SettingsDescription(
                title = "Choose how long a live connection stays available.",
                description = "This controls the phone’s connection. Remote sessions are not ended by this setting.",
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
                    QuietChoiceRow(
                        title = option.label,
                        subtitle = graceDescription(option.millis),
                        selected = settings.backgroundGraceMillis == option.millis,
                        onClick = { onBackgroundGraceChange(option.millis) },
                        modifier = Modifier.testTag(backgroundGraceOptionTag(option.millis)),
                    )
                }
            }
        }
        item {
            PocketShellButton(
                text = "Done",
                onClick = onBack,
                variant = ButtonVariant.Primary,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
    }
}

@Composable
internal fun AdvancedSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onVoiceSilenceChange: (Float) -> Unit,
    onUsageWarnThresholdChange: (Int) -> Unit,
    onAgentSubmitEnterDelayChange: (Int) -> Unit,
    onResetAdvancedDefaults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Advanced",
        pageTag = SETTINGS_ADVANCED_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Timing") }
        item {
            SettingsSlider(
                title = "Enter-key delay",
                description = "Pause after pasted input before sending Enter. Change only if input is left unsubmitted.",
                value = settings.agentSubmitEnterDelayMs.toFloat(),
                valueLabel = "${settings.agentSubmitEnterDelayMs} ms",
                min = AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat(),
                max = AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat(),
                step = AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS.toFloat(),
                onChange = { onAgentSubmitEnterDelayChange(it.roundToInt()) },
                sliderTestTag = SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG,
                valueTestTag = SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG,
            )
        }
        item {
            SettingsSlider(
                title = "Silence window",
                description = "Speech-recognizer pause handling. Recording still ends when you tap Stop.",
                value = settings.voiceSilenceThresholdSeconds,
                valueLabel = "${settings.voiceSilenceThresholdSeconds.roundToInt()} s",
                min = AppSettings.MIN_VOICE_SILENCE_SECONDS,
                max = AppSettings.MAX_VOICE_SILENCE_SECONDS,
                step = AppSettings.VOICE_SILENCE_STEP_SECONDS,
                onChange = onVoiceSilenceChange,
                sliderTestTag = SETTINGS_VOICE_SILENCE_SLIDER_TAG,
                valueTestTag = SETTINGS_VOICE_SILENCE_VALUE_TAG,
            )
        }
        item { SectionHeader(label = "Usage") }
        item {
            SettingsSlider(
                title = "Warn at",
                description = "Start warning when a provider quota reaches this percentage. Critical and exceeded states stay fixed.",
                value = settings.usageWarnThresholdPercent.toFloat(),
                valueLabel = "${settings.usageWarnThresholdPercent}%",
                min = AppSettings.MIN_USAGE_WARN_PERCENT.toFloat(),
                max = AppSettings.MAX_USAGE_WARN_PERCENT.toFloat(),
                step = AppSettings.USAGE_WARN_PERCENT_STEP.toFloat(),
                onChange = { onUsageWarnThresholdChange(it.roundToInt()) },
                sliderTestTag = SETTINGS_USAGE_WARN_SLIDER_TAG,
                valueTestTag = SETTINGS_USAGE_WARN_VALUE_TAG,
            )
        }
        item {
            PocketShellButton(
                text = "Reset advanced defaults",
                onClick = onResetAdvancedDefaults,
                variant = ButtonVariant.Secondary,
                modifier = Modifier
                    .padding(horizontal = PocketShellDensity.rowPadH)
                    .testTag(SETTINGS_RESET_ADVANCED_TAG),
            )
        }
    }
}

@Composable
internal fun AboutScreen(
    buildInfo: AppBuildInfo,
    updateCheckState: SettingsUpdateCheckState,
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "About PocketShell",
        pageTag = SETTINGS_ABOUT_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsDescription(
                title = "PocketShell",
                description = "Workspaces first. Terminals stay terminals.",
            )
        }
        item {
            ListRow(
                title = "Installed version",
                subtitle = buildInfo.displayText(),
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Info, null, tint = PocketShellColors.TextSecondary) },
                modifier = Modifier.testTag(SETTINGS_VERSION_TAG),
            )
        }
        item {
            ListRow(
                title = "Check for updates",
                subtitle = aboutUpdateSummary(updateCheckState),
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Refresh, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenUpdate,
                modifier = Modifier.testTag(SETTINGS_UPDATE_CHECK_TAG),
            )
        }
        item {
            ListRow(
                title = "Open-source licenses",
                leading = { androidx.compose.material3.Icon(PocketShellIcons.File, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenLicenses,
            )
        }
    }
}

@Composable
internal fun UpdateScreen(
    state: SettingsUpdateCheckState,
    onBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = when (state) {
            is SettingsUpdateCheckState.UpdateAvailable -> "Update available"
            else -> "Updates"
        },
        pageTag = SETTINGS_UPDATE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Build and updates") }
        when (state) {
            SettingsUpdateCheckState.Idle -> {
                item {
                    SettingsDescription(
                        title = "Check GitHub for the latest verified release.",
                        description = "PocketShell opens the system browser or download manager for the release.",
                    )
                }
                item {
                    PocketShellButton(
                        text = "Check for updates",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            SettingsUpdateCheckState.Checking -> {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                    ) {
                        LoadingIndicator.Spinner(
                            size = com.pocketshell.uikit.components.SpinnerSize.Medium,
                            label = "Checking for updates…",
                        )
                        Text(
                            text = "Contacting GitHub releases.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.body,
                        )
                    }
                }
            }

            SettingsUpdateCheckState.UpToDate -> {
                item {
                    SettingsDescription(
                        title = "Up to date",
                        description = "This installed build is the latest verified release.",
                    )
                }
                item {
                    PocketShellButton(
                        text = "Check again",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Text,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            is SettingsUpdateCheckState.Failed -> {
                item {
                    Banner(
                        text = "Couldn't check for updates: ${state.reason}",
                        role = BannerRole.Error,
                        leadingIcon = PocketShellIcons.Warning,
                        modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
                    )
                }
                item {
                    PocketShellButton(
                        text = "Retry update check",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            is SettingsUpdateCheckState.UpdateAvailable -> {
                item {
                    SettingsDescription(
                        title = "A newer PocketShell build is available.",
                        description = buildString {
                            append("Release ${state.info.tagName}. Keep the app and host helper on compatible versions.")
                            if (state.info.publishedDateLabel.isNotBlank()) {
                                append(" Published ${state.info.publishedDateLabel}.")
                            }
                        },
                    )
                }
                item {
                    ListRow(
                        title = "Release notes",
                        subtitle = "Review changes before updating",
                        leading = { androidx.compose.material3.Icon(PocketShellIcons.External, null, tint = PocketShellColors.TextSecondary) },
                        trailing = { NavigationChevron() },
                        onClick = { onOpenUrl(state.info.htmlUrl) },
                        modifier = Modifier.testTag("settings-release-notes"),
                    )
                }
                item {
                    PocketShellButton(
                        text = "Open release",
                        onClick = { onOpenUrl(state.info.apkUrl) },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    pageTag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(pageTag),
    ) {
        SettingsHeader(title = title, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
            content = content,
        )
    }
}

@Composable
private fun SettingsDescription(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = PocketShellType.body,
        )
        Text(
            text = description,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.body,
        )
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueLabel: String,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit,
    sliderTestTag: String,
    valueTestTag: String,
) {
    val clampedValue = value.coerceIn(min, max)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = PocketShellColors.Text, style = PocketShellType.body)
                Text(text = description, color = PocketShellColors.TextSecondary, style = PocketShellType.body)
            }
            Text(
                text = valueLabel,
                color = PocketShellColors.Text,
                style = PocketShellType.metadata,
                modifier = Modifier.testTag(valueTestTag),
            )
        }
        Slider(
            value = clampedValue,
            onValueChange = { raw ->
                val steps = ((raw - min) / step).roundToInt()
                onChange((min + steps * step).coerceIn(min, max))
            },
            valueRange = min..max,
            steps = ((max - min) / step).roundToInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = PocketShellColors.Text,
                activeTrackColor = PocketShellColors.TextSecondary,
                inactiveTrackColor = PocketShellColors.Border,
            ),
            modifier = Modifier
                .testTag(sliderTestTag)
                .semantics {
                    contentDescription = title
                    stateDescription = valueLabel
                },
        )
    }
}

private fun graceDescription(millis: Long): String = when (millis) {
    AppSettings.BACKGROUND_GRACE_30_SECONDS_MS -> "Good for switching apps"
    AppSettings.BACKGROUND_GRACE_1_MINUTE_MS -> "More time between app switches"
    AppSettings.BACKGROUND_GRACE_90_SECONDS_MS -> "Default recovery window"
    AppSettings.BACKGROUND_GRACE_5_MINUTES_MS -> "Longer app switches"
    AppSettings.BACKGROUND_GRACE_10_MINUTES_MS -> "Extended recovery window"
    else -> "Custom recovery window"
}

/**
 * Converts the renderer's persisted raw pixels into the SP value shown to a
 * user. The terminal view consumes pixels directly, so this conversion belongs
 * at the settings boundary rather than in the terminal package.
 */
internal fun terminalTextSizeSpFromPx(sizePx: Int, density: Density): Float {
    val pixelsPerSp = pixelsPerSp(density)
    return (sizePx / pixelsPerSp)
        .coerceIn(
            AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
            AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
        )
        .roundToInt()
        .toFloat()
}

/** Converts a user-facing SP stop back to the raw pixels stored and rendered. */
internal fun terminalTextSizePxFromSp(sizeSp: Float, density: Density): Int {
    val pixels = (sizeSp.coerceIn(
        AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
        AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
    ) * pixelsPerSp(density)).roundToInt()
    return pixels.coerceIn(
        AppSettings.MIN_TERMINAL_TEXT_SIZE_PX,
        AppSettings.MAX_TERMINAL_TEXT_SIZE_PX,
    )
}

private fun pixelsPerSp(density: Density): Float =
    (density.density * density.fontScale).takeIf { it.isFinite() && it > 0f } ?: 1f

private fun aboutUpdateSummary(state: SettingsUpdateCheckState): String = when (state) {
    SettingsUpdateCheckState.Idle -> "Check the latest verified release"
    SettingsUpdateCheckState.Checking -> "Checking GitHub releases…"
    SettingsUpdateCheckState.UpToDate -> "This build is up to date"
    is SettingsUpdateCheckState.Failed -> "Check failed · tap to retry"
    is SettingsUpdateCheckState.UpdateAvailable -> "${state.info.tagName} is available"
}

private fun readBuildInfo(context: Context): AppBuildInfo = runCatching {
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
