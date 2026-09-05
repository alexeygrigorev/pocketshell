package com.pocketshell.next.usage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2532: Usage is a popped screen, so Back must be the word `Back`
 * (file-explorer grammar), not a hairline `‹`.
 */
@RunWith(AndroidJUnit4::class)
class UsageScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `back button fires onBack`() {
        var backs = 0
        composeRule.setContent {
            PocketShellTheme {
                UsageScreen(
                    state = UsageScreenState(),
                    onBack = { backs += 1 },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(USAGE_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(USAGE_BACK_TAG).performClick()

        assertEquals(1, backs)
    }
}
