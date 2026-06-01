package com.pocketshell.app.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.proof.clearLastSessionPrefs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #410: app version/build metadata belongs in the low-emphasis
 * Settings footer, not in the primary host/session surface.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAboutFooterE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun clearFastResume() {
        clearLastSessionPrefs()
    }

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        clearLastSessionPrefs()
    }

    @Test
    fun versionBuildInfoIsOnlyInSettingsAboutFooter() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "version/build tag must not be mounted on the host landing surface",
            compose.onAllNodesWithTag(ABOUT_VERSION_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(ABOUT_FOOTER_TAG))

        compose.onNodeWithTag(ABOUT_FOOTER_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ABOUT_VERSION_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(
            "PocketShell v",
            substring = true,
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("build", substring = true, useUnmergedTree = true).assertExists()
    }
}
