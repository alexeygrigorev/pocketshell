package com.pocketshell.uikit.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionLauncherBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the closed bar is Prompt Composer plus the hotkeys chip`() {
        var composer = 0
        var hotkeys = 0
        compose.setContent {
            PocketShellTheme {
                SessionLauncherBar(
                    onOpenComposer = { composer += 1 },
                    onOpenHotkeys = { hotkeys += 1 },
                )
            }
        }

        compose.onNodeWithTag(SESSION_LAUNCHER_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText(SESSION_COMPOSER_LAUNCHER_LABEL).assertIsDisplayed()
        compose.onNodeWithText(SESSION_HOTKEYS_LAUNCHER_LABEL).assertIsDisplayed()
        compose.onNodeWithText("Ctrl").assertDoesNotExist()
        compose.onNodeWithText("Enter").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        compose.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).performClick()
        assertEquals(1, composer)
        assertEquals(1, hotkeys)
    }

    @Test
    fun `hotkeys chip is omitted when there is no pane`() {
        compose.setContent {
            PocketShellTheme {
                SessionLauncherBar(onOpenComposer = {}, onOpenHotkeys = null)
            }
        }

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertDoesNotExist()
    }
}
