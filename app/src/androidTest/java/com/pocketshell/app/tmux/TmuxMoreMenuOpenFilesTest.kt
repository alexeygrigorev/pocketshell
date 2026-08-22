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
 * Issue #1715: the session kebab must expose "Open files" that fires the
 * callback MainActivity wires to FileViewer(remotePath = null).
 *
 * G6 mutation: removing the menu item or leaving onOpenFiles as a no-op
 * reddens the click count.
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuOpenFilesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openFilesItemIsPresentAndInvokesCallback() {
        var clicks = 0
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
                    onOpenFiles = { clicks++ },
                    onDetach = {},
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_OPEN_FILES_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("Open files kebab item should invoke onOpenFiles", 1, clicks)
    }
}
