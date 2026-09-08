package com.pocketshell.next.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.next.release.ReleaseCheckResult
import com.pocketshell.next.release.ReleaseInfo
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags shared by the categorized Settings pages. */
const val SETTINGS_LIST_TAG: String = "settings-list"
const val SETTINGS_BACK_TAG: String = "settings-back"
const val SETTINGS_TERMINAL_SIZE_SLIDER_TAG: String = "settings-terminal-size-slider"
const val SETTINGS_TERMINAL_SIZE_VALUE_TAG: String = "settings-terminal-size-value"
const val SETTINGS_TERMINAL_SAMPLE_TAG: String = "settings-terminal-sample"
const val SETTINGS_COMMON_KEYS_TAG: String = "settings-common-keys"
const val SETTINGS_USAGE_WARN_SLIDER_TAG: String = "settings-usage-warn-slider"
const val SETTINGS_USAGE_WARN_VALUE_TAG: String = "settings-usage-warn-value"
const val SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG: String = "settings-agent-submit-delay-slider"
const val SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG: String = "settings-agent-submit-delay-value"
const val SETTINGS_VOICE_SILENCE_SLIDER_TAG: String = "settings-voice-silence-slider"
const val SETTINGS_VOICE_SILENCE_VALUE_TAG: String = "settings-voice-silence-value"
const val SETTINGS_VOICE_REVIEW_TAG: String = "settings-voice-review"
const val SETTINGS_VOICE_RECOGNITION_TAG: String = "settings-voice-recognition"
const val SETTINGS_CONNECTION_RECONNECT_TAG: String = "settings-connection-reconnect"
const val SETTINGS_RESET_ADVANCED_TAG: String = "settings-reset-advanced"
const val SETTINGS_WORKSPACE_EMPTY_TAG: String = "settings-workspace-empty"
const val SETTINGS_CRASH_REPORTS_TAG: String = "settings-crash-reports"
const val SETTINGS_VERSION_TAG: String = "settings-version"
const val SETTINGS_UPDATE_CHECK_TAG: String = "settings-update-check"
const val SETTINGS_UPDATE_CHECK_LABEL_TAG: String = "settings-update-check-label"
const val SETTINGS_UPDATE_CHECK_DETAIL_TAG: String = "settings-update-check-detail"

const val SETTINGS_TERMINAL_PAGE_TAG: String = "settings-terminal-page"
const val SETTINGS_VOICE_PAGE_TAG: String = "settings-voice-page"
const val SETTINGS_LANGUAGE_PAGE_TAG: String = "settings-language-page"
const val SETTINGS_CONNECTIONS_PAGE_TAG: String = "settings-connections-page"
const val SETTINGS_GRACE_PAGE_TAG: String = "settings-grace-page"
const val SETTINGS_ADVANCED_PAGE_TAG: String = "settings-advanced-page"
const val SETTINGS_ABOUT_PAGE_TAG: String = "settings-about-page"
const val SETTINGS_UPDATE_PAGE_TAG: String = "settings-update-page"

fun settingsCategoryTag(id: String): String = "settings-category-$id"

fun backgroundGraceOptionTag(millis: Long): String = "settings-grace-$millis"

fun voiceLanguageOptionTag(code: String): String = "settings-voice-lang-$code"

fun settingsHostRowTag(hostId: Long): String = "settings-host-$hostId"

/** Installed build identity, rendered by the real About page. */
data class AppBuildInfo(val versionName: String, val versionCode: Long?) {
    fun displayText(): String =
        if (versionCode == null) "v$versionName" else "v$versionName ($versionCode)"
}

/** Navigation edges owned by the Settings landing page. */
data class SettingsNavigation(
    val onBack: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenVoice: () -> Unit,
    val onOpenConnections: () -> Unit,
    val onOpenAdvanced: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenAbout: () -> Unit,
)

/** Route-level entry point for the categorized Quiet Settings index. */
@Composable
fun SettingsRoute(
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(navigation = navigation, modifier = modifier)
}

private data class SettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The short Settings index from Quiet frame 70. Configuration controls live on
 * their own pages so a large-text user can read and reach every value without
 * navigating a mixed, card-heavy form.
 */
@Composable
fun SettingsScreen(
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    val categories = listOf(
        SettingsCategory(
            id = "terminal",
            title = "Terminal",
            subtitle = "Text and input",
            icon = PocketShellIcons.Terminal,
            onClick = navigation.onOpenTerminal,
        ),
        SettingsCategory(
            id = "voice",
            title = "Voice",
            subtitle = "Language and dictation",
            icon = PocketShellIcons.Mic,
            onClick = navigation.onOpenVoice,
        ),
        SettingsCategory(
            id = "connections",
            title = "Connections",
            subtitle = "App switching and recovery",
            icon = PocketShellIcons.Server,
            onClick = navigation.onOpenConnections,
        ),
        SettingsCategory(
            id = "advanced",
            title = "Advanced",
            subtitle = "Timing and compatibility",
            icon = PocketShellIcons.Sliders,
            onClick = navigation.onOpenAdvanced,
        ),
        SettingsCategory(
            id = "diagnostics",
            title = "Diagnostics",
            subtitle = "Local reports",
            icon = PocketShellIcons.Info,
            onClick = navigation.onOpenDiagnostics,
        ),
        SettingsCategory(
            id = "about",
            title = "About",
            subtitle = "Build and updates",
            icon = PocketShellIcons.Info,
            onClick = navigation.onOpenAbout,
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        SettingsHeader(title = "Settings", onBack = navigation.onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SETTINGS_LIST_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            item { SectionHeader(label = "Preferences") }
            items(categories, key = { it.id }) { category ->
                ListRow(
                    title = category.title,
                    subtitle = category.subtitle,
                    leading = {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = PocketShellColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailing = { NavigationChevron() },
                    onClick = category.onClick,
                    modifier = Modifier.testTag(settingsCategoryTag(category.id)),
                )
            }
        }
    }
}

@Composable
internal fun SettingsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenHeader(
        title = title,
        modifier = modifier,
        onBack = onBack,
        backTestTag = SETTINGS_BACK_TAG,
    )
}

internal fun settingsUpdateCheckState(
    checking: Boolean,
    lastResult: ReleaseCheckResult?,
): SettingsUpdateCheckState = when {
    checking -> SettingsUpdateCheckState.Checking
    lastResult is ReleaseCheckResult.UpdateAvailable ->
        SettingsUpdateCheckState.UpdateAvailable(lastResult.info)
    lastResult is ReleaseCheckResult.UpToDate -> SettingsUpdateCheckState.UpToDate
    lastResult is ReleaseCheckResult.Failed -> SettingsUpdateCheckState.Failed(lastResult.reason)
    else -> SettingsUpdateCheckState.Idle
}

sealed interface SettingsUpdateCheckState {
    data object Idle : SettingsUpdateCheckState
    data object Checking : SettingsUpdateCheckState
    data object UpToDate : SettingsUpdateCheckState
    data class UpdateAvailable(val info: ReleaseInfo) : SettingsUpdateCheckState
    data class Failed(val reason: String) : SettingsUpdateCheckState
}
