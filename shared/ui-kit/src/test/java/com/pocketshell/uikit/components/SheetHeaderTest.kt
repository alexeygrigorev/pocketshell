package com.pocketshell.uikit.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM behaviour tests for the shared bottom-sheet header (#756). The key
 * contract is presentational consistency plus routing the optional close action;
 * sheet animation itself stays in the app-level `ModalBottomSheet` wrappers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SheetHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleSubtitleAndTrailingSlot() {
        composeRule.setContent {
            PocketShellTheme {
                SheetHeader(
                    title = "New session",
                    subtitle = "in ~/src/pocketshell",
                    trailing = { Text("Manage") },
                )
            }
        }

        composeRule.onNodeWithText("New session").assertIsDisplayed()
        composeRule.onNodeWithText("in ~/src/pocketshell").assertIsDisplayed()
        composeRule.onNodeWithText("Manage").assertIsDisplayed()
    }

    @Test
    fun closeAffordanceRoutesOnClose() {
        var closes = 0
        composeRule.setContent {
            PocketShellTheme {
                SheetHeader(
                    title = "Snippets",
                    onClose = { closes++ },
                    closeContentDescription = "Close snippets",
                    closeTestTag = "sheet-close",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Close snippets").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet-close").performClick()
        composeRule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun exposesTitleAndSubtitleTags() {
        composeRule.setContent {
            PocketShellTheme {
                SheetHeader(
                    title = "Review saved",
                    subtitle = "Sent 2 comments to hetzner.",
                    titleTestTag = "sheet-title",
                    subtitleTestTag = "sheet-subtitle",
                )
            }
        }

        composeRule.onNodeWithTag("sheet-title").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet-subtitle").assertIsDisplayed()
    }
}
