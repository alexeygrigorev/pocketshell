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
 * Issue #892: the live session kebab must expose a "Redraw" item (stable test tag)
 * that, when tapped, invokes the redraw callback. The full-viewport reseed behaviour
 * is proven end-to-end by [com.pocketshell.app.proof.RedrawFullViewportReseedJourneyE2eTest];
 * this focused test covers AC1 — the item is present, reachable, and wired.
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuRedrawTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun redrawItemIsPresentAndInvokesCallback() {
        var redrawClicks = 0
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
                    onOpenSettings = {},
                    onDetach = {},
                    onRedraw = { redrawClicks++ },
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("Redraw kebab item should invoke onRedraw", 1, redrawClicks)
    }
}
