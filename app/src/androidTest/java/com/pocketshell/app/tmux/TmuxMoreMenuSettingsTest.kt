package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #592: live session chrome should expose a direct global Settings entry
 * so users do not need to back out to the host list first.
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsItemIsPresentAndInvokesCallback() {
        var settingsClicks = 0
        compose.setContent {
            PocketShellTheme {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    currentWindowId = "@1",
                    multipleWindows = true,
                    onDismiss = { expanded.value = false },
                    onCreateSession = {},
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onOpenSettings = { settingsClicks++ },
                    onNewWindow = {},
                    onRenameWindow = {},
                    onKillWindow = {},
                    onDetach = {},
                    onSwitchWindow = {},
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_SETTINGS_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("Settings kebab item should invoke onOpenSettings", 1, settingsClicks)
    }
}
