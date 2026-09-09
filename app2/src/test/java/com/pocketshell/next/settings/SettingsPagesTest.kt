package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.release.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1200dp")
class SettingsPagesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `terminal page renders a user facing sp control`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                TerminalSettingsScreen(
                settings = AppSettings(terminalTextSizePx = 32),
                onBack = {},
                onTerminalTextSizeChange = {},
                onShowCommonKeysChange = {},
                )
            }
        }

        composeRule.onNodeWithTag(SETTINGS_TERMINAL_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_TERMINAL_SIZE_SLIDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("16 sp").assertIsDisplayed()
        composeRule.onNodeWithText("Text and input").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_TERMINAL_SAMPLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Show common keys").assertIsDisplayed()
        composeRule.onNodeWithText("Esc, Tab, Ctrl and arrows when typing.").assertIsDisplayed()
    }

    @Test
    fun `terminal size conversion includes density and font scale`() {
        val density = Density(2f, fontScale = 1.25f)

        assertEquals(16f, terminalTextSizeSpFromPx(40, density))
        assertEquals(40, terminalTextSizePxFromSp(16f, density))
    }

    @Test
    fun `voice page routes language through a focused page`() {
        var opened = 0
        composeRule.setContent {
            VoiceSettingsScreen(
                settings = AppSettings(voiceLanguage = "de"),
                onBack = {},
                onOpenLanguage = { opened++ },
            )
        }

        composeRule.onNodeWithText("German").assertIsDisplayed()
        composeRule.onNodeWithText("Language").performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithTag(SETTINGS_VOICE_REVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_VOICE_RECOGNITION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("PocketShell asks for microphone access when you start dictating.")
            .assertIsDisplayed()
    }

    @Test
    fun `advanced reset is a real action and keeps the compatibility page reachable`() {
        var resetCount = 0
        composeRule.setContent {
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
                onResetAdvancedDefaults = { resetCount++ },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_RESET_ADVANCED_TAG)
            .performScrollTo()
            .performClick()
        assertEquals(1, resetCount)
        composeRule.onNodeWithText("Reset advanced defaults").assertIsDisplayed()
    }

    @Test
    fun `connections explain reconnect behavior through the real grace control`() {
        var openedGrace = 0
        composeRule.setContent {
            ConnectionSettingsScreen(
                settings = AppSettings(),
                hosts = emptyList(),
                onBack = {},
                onOpenGrace = { openedGrace++ },
                onOpenWorkspaceRoots = {},
            )
        }

        composeRule.onNodeWithText("Reconnect when I return").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This controls the phone’s connection. Remote sessions are not deliberately ended " +
                "when the app leaves the foreground.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("settings-connection-grace").performClick()

        assertEquals(1, openedGrace)
    }

    @Test
    fun `about page uses the installed build value and update state summary`() {
        var openedLicenses = 0
        composeRule.setContent {
            AboutScreen(
                buildInfo = AppBuildInfo("0.5.0", 500),
                updateCheckState = SettingsUpdateCheckState.UpToDate,
                onBack = {},
                onOpenUpdate = {},
                onOpenLicenses = { openedLicenses++ },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_ABOUT_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("v0.5.0 (500)").assertIsDisplayed()
        composeRule.onNodeWithText("This build is up to date").assertIsDisplayed()
        composeRule.onNodeWithText("Open-source licenses").performClick()
        assertEquals(1, openedLicenses)
    }

    @Test
    fun `update available exposes both real native handoff URLs`() {
        val info = ReleaseInfo(
            tagName = "v0.5.1",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.5.1",
            apkUrl = "https://example.com/pocketshell-0.5.1.apk",
            publishedDateLabel = "5 Sep 2026",
        )
        val opened = mutableListOf<String>()
        composeRule.setContent {
            UpdateScreen(
                state = SettingsUpdateCheckState.UpdateAvailable(info),
                onBack = {},
                onCheckForUpdates = {},
                onOpenUrl = { opened += it },
            )
        }

        composeRule.onNodeWithText("Release notes").performClick()
        composeRule.onNodeWithText("Open release").performClick()
        assertEquals(listOf(info.htmlUrl, info.apkUrl), opened)
    }

    @Test
    fun `failed update state remains distinct and offers retry`() {
        var retries = 0
        composeRule.setContent {
            UpdateScreen(
                state = SettingsUpdateCheckState.Failed("rate-limited, try again later"),
                onBack = {},
                onCheckForUpdates = { retries++ },
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText("Couldn't check for updates: rate-limited, try again later")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry update check").performClick()
        assertEquals(1, retries)
    }
}
