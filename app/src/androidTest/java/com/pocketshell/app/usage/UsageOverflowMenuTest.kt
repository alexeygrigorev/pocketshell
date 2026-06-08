package com.pocketshell.app.usage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #617: the Usage header overflow must expose real actions. This keeps
 * the focused regression outside the heavier SSH-backed Usage E2E suite.
 */
@RunWith(AndroidJUnit4::class)
class UsageOverflowMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun menuItemsArePresentAndInvokeCallbacks() {
        var refreshClicks = 0
        var settingsClicks = 0

        compose.setContent {
            PocketShellTheme {
                UsageScreen(
                    state = UsageScreenState(),
                    onBack = {},
                    onRefresh = { refreshClicks++ },
                    onOpenSettings = { settingsClicks++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Usage actions").assertIsDisplayed()
        compose.onNodeWithTag(USAGE_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(USAGE_REFRESH_ACTION_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, refreshClicks)
        assertEquals(0, settingsClicks)

        compose.onNodeWithTag(USAGE_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(USAGE_SETTINGS_ACTION_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, refreshClicks)
        assertEquals(1, settingsClicks)
    }
}
