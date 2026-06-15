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
 * Issue #497: the tmux session kebab must expose an "Open file…" item that
 * fires the callback MainActivity wires to the in-app file viewer's
 * path-entry dialog. Mirrors [TmuxMoreMenuPortForwardingTest].
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuOpenFileTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openFileItemIsPresentAndInvokesCallback() {
        var openFileClicks = 0
        compose.setContent {
            PocketShellTheme {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    onDismiss = { expanded.value = false },
                    onCreateSession = {},
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onOpenFile = { openFileClicks++ },
                    onDetach = {},
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_OPEN_FILE_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("Open file kebab item should invoke onOpenFile", 1, openFileClicks)
    }
}
