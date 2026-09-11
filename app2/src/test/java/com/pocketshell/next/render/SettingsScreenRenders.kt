package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.settings.AdvancedSettingsScreen
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsNavigation
import com.pocketshell.next.settings.SettingsScreen
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fresh Quiet renders for the categorized Settings index and its longest page.
 * Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*SettingsScreenRenders*' --rerun-tasks
 *
 * The three index captures exercise the requested phone, wide-phone, and
 * tablet widths. The Advanced capture keeps all persisted tuning controls in
 * one real scrollable page so large-text review has a concrete artifact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SettingsScreenRenders {

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun settingsIndex360() = render("i2610-settings-index-360") {
        SettingsScreen(navigation = navigation())
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun settingsIndex412() = render("i2610-settings-index-412") {
        SettingsScreen(navigation = navigation())
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-night-xxhdpi")
    fun settingsIndex600() = render("i2610-settings-index-600") {
        SettingsScreen(navigation = navigation())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
    fun advancedSettingsLargeText() = render("i2610-settings-advanced-large-text") {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = 1.3f),
        ) {
            AdvancedSettingsScreen(
                settings = AppSettings(
                    agentSubmitEnterDelayMs = 300,
                    voiceSilenceThresholdSeconds = 8f,
                    usageWarnThresholdPercent = 90,
                ),
                onBack = {},
                onVoiceSilenceChange = {},
                onUsageWarnThresholdChange = {},
                onAgentSubmitEnterDelayChange = {},
            )
        }
    }

    private fun navigation() = SettingsNavigation(
        onBack = {},
        onOpenTerminal = {},
        onOpenVoice = {},
        onOpenConnections = {},
        onOpenAdvanced = {},
        onOpenDiagnostics = {},
        onOpenAbout = {},
    )

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }
}
