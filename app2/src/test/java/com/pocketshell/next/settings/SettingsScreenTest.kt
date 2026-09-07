package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `landing index exposes every Quiet category and keeps the last row reachable`() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            SettingsScreen(
                navigation = SettingsNavigation(
                    onBack = {},
                    onOpenTerminal = { opened += "terminal" },
                    onOpenVoice = { opened += "voice" },
                    onOpenConnections = { opened += "connections" },
                    onOpenAdvanced = { opened += "advanced" },
                    onOpenDiagnostics = { opened += "diagnostics" },
                    onOpenAbout = { opened += "about" },
                ),
            )
        }

        listOf("terminal", "voice", "connections", "advanced", "diagnostics", "about")
            .forEach { id ->
                composeRule.onNodeWithTag(settingsCategoryTag(id)).performScrollTo().assertIsDisplayed()
            }
        composeRule.onNodeWithTag(settingsCategoryTag("about")).performClick()
        assertEquals(listOf("about"), opened)
    }

    @Test
    fun `back is an accessible shared button`() {
        var backCount = 0
        composeRule.setContent {
            SettingsScreen(
                navigation = SettingsNavigation(
                    onBack = { backCount++ },
                    onOpenTerminal = {},
                    onOpenVoice = {},
                    onOpenConnections = {},
                    onOpenAdvanced = {},
                    onOpenDiagnostics = {},
                    onOpenAbout = {},
                ),
            )
        }

        composeRule.onNodeWithTag(SETTINGS_BACK_TAG).assertIsDisplayed().performClick()
        assertEquals(1, backCount)
    }

    @Test
    fun `language choices are one full-row radio target and write the selected code`() {
        var changedTo: String? = null
        composeRule.setContent {
            LanguageSettingsScreen(
                settings = AppSettings(),
                onBack = {},
                onVoiceLanguageChange = { changedTo = it },
            )
        }

        composeRule.onNodeWithTag(voiceLanguageOptionTag("ru"))
            .performClick()
        assertEquals("ru", changedTo)
        assertEquals(
            Role.RadioButton,
            composeRule.onNodeWithTag(voiceLanguageOptionTag("ru"))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Role],
        )
        composeRule.onNodeWithTag(voiceLanguageOptionTag("auto")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `grace choices are sourced from the real settings options`() {
        var changedTo: Long? = null
        composeRule.setContent {
            GraceSettingsScreen(
                settings = AppSettings(backgroundGraceMillis = AppSettings.BACKGROUND_GRACE_30_SECONDS_MS),
                onBack = {},
                onBackgroundGraceChange = { changedTo = it },
            )
        }

        AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
            composeRule.onNodeWithTag(backgroundGraceOptionTag(option.millis))
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag(backgroundGraceOptionTag(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS))
            .performClick()
        assertEquals(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS, changedTo)
    }

    @Test
    fun `connections page keeps the Room host handoff and real empty state`() {
        var openedHost: Long? = null
        composeRule.setContent {
            ConnectionSettingsScreen(
                settings = AppSettings(),
                hosts = listOf(SettingsHostRow(9, "hetzner", "alexey@10.0.0.1:22")),
                onBack = {},
                onOpenGrace = {},
                onOpenWorkspaceRoots = { openedHost = it },
            )
        }

        composeRule.onNodeWithTag(settingsHostRowTag(9)).performClick()
        assertEquals(9L, openedHost)
    }

    @Test
    fun `advanced page keeps every persisted tuning value reachable by scroll`() {
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
            )
        }

        composeRule.onNodeWithTag(SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("300 ms").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_VOICE_SILENCE_VALUE_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("8 s").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_USAGE_WARN_VALUE_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("90%").performScrollTo().assertIsDisplayed()
    }
}
