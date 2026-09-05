package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [SettingsScreen] as a stateless composable — every value comes in as a
 * parameter, every change is asserted on the callback it fires, exactly the
 * split [SettingsRoute] documents.
 *
 * The screen's `LazyColumn` only composes items actually inside the viewport
 * (unlike the host form's plain `verticalScroll` Column, `performScrollTo()`
 * cannot bring an uncomposed item in) — the tall qualifier below fits every
 * section (Terminal through About) in one unscrolled pass instead of needing
 * `performScrollToNode`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h4000dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every background grace option is a tappable row that reports its own millis`() {
        var changedTo: Long? = null
        setContent(onBackgroundGraceChange = { changedTo = it })

        composeRule.onNodeWithTag(backgroundGraceOptionTag(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS))
            .performClick()

        assertEquals(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS, changedTo)
    }

    @Test
    fun `every background grace option renders regardless of which one is current`() {
        setContent(settings = AppSettings(backgroundGraceMillis = AppSettings.BACKGROUND_GRACE_30_SECONDS_MS))

        AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
            composeRule.onNodeWithTag(backgroundGraceOptionTag(option.millis)).assertIsDisplayed()
        }
    }

    @Test
    fun `every voice language option reports its own code`() {
        var changedTo: String? = null
        setContent(onVoiceLanguageChange = { changedTo = it })

        composeRule.onNodeWithTag(voiceLanguageOptionTag("ru")).performClick()

        assertEquals("ru", changedTo)
    }

    @Test
    fun `the agent submit delay slider shows the current value`() {
        setContent(settings = AppSettings(agentSubmitEnterDelayMs = 300))

        composeRule.onNodeWithTag(SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("300ms").assertIsDisplayed()
        composeRule.onNodeWithText("Agent submit delay").assertIsDisplayed()
    }

    @Test
    fun `the agent submit delay slider defaults to 150ms`() {
        setContent()

        composeRule.onNodeWithText("150ms").assertIsDisplayed()
    }

    @Test
    fun `an empty host list shows the add-a-host nudge instead of a blank section`() {
        setContent(hosts = emptyList())

        composeRule.onNodeWithTag(SETTINGS_WORKSPACE_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `tapping a host row opens its workspace roots`() {
        var openedHostId: Long? = null
        setContent(
            hosts = listOf(SettingsHostRow(id = 9, name = "hetzner", subtitle = "alexey@10.0.0.1")),
            onOpenWorkspaceRoots = { openedHostId = it },
        )

        composeRule.onNodeWithTag(settingsHostRowTag(9)).performClick()

        assertEquals(9L, openedHostId)
    }

    /**
     * Issue #2476: the Diagnostics row is the ONLY entry point into the
     * crash-report browser, so "the row exists and fires its callback" is the
     * whole contract this screen owns; the navigation edge it feeds is pinned
     * by [SettingsNavigationTest].
     */
    @Test
    fun `tapping the crash reports row opens the crash report browser`() {
        var openCount = 0
        setContent(onOpenCrashReports = { openCount++ })

        composeRule.onNodeWithTag(SETTINGS_CRASH_REPORTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Crash reports").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_CRASH_REPORTS_TAG).performClick()

        assertEquals(1, openCount)
    }

    @Test
    fun `the installed build identity renders in the About footer`() {
        setContent(buildInfo = AppBuildInfo(versionName = "0.5.0", versionCode = 500))

        composeRule.onNodeWithTag(SETTINGS_VERSION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("v0.5.0 (500)").assertIsDisplayed()
    }

    @Test
    fun `back button fires onBack`() {
        var backCount = 0
        setContent(onBack = { backCount++ })

        composeRule.onNodeWithTag(SETTINGS_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_BACK_TAG).performClick()

        assertEquals(1, backCount)
    }

    private fun setContent(
        settings: AppSettings = AppSettings(),
        hosts: List<SettingsHostRow> = emptyList(),
        buildInfo: AppBuildInfo = AppBuildInfo(versionName = "0.0.0", versionCode = 1),
        onBack: () -> Unit = {},
        onTerminalTextSizeChange: (Int) -> Unit = {},
        onVoiceLanguageChange: (String) -> Unit = {},
        onUsageWarnThresholdChange: (Int) -> Unit = {},
        onBackgroundGraceChange: (Long) -> Unit = {},
        onAgentSubmitEnterDelayChange: (Int) -> Unit = {},
        onOpenWorkspaceRoots: (Long) -> Unit = {},
        onOpenCrashReports: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                settings = settings,
                hosts = hosts,
                buildInfo = buildInfo,
                onBack = onBack,
                onTerminalTextSizeChange = onTerminalTextSizeChange,
                onVoiceLanguageChange = onVoiceLanguageChange,
                onUsageWarnThresholdChange = onUsageWarnThresholdChange,
                onBackgroundGraceChange = onBackgroundGraceChange,
                onAgentSubmitEnterDelayChange = onAgentSubmitEnterDelayChange,
                onOpenWorkspaceRoots = onOpenWorkspaceRoots,
                onOpenCrashReports = onOpenCrashReports,
            )
        }
    }
}
