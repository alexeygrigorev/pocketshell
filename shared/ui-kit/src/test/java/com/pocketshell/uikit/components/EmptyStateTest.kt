package com.pocketshell.uikit.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * JVM (Robolectric) behaviour tests for the shared [EmptyState] (#756): content
 * rendering, the optional description / icon / action slots, and that the action
 * affordance routes its tap. These run under the real [PocketShellTheme] on the
 * host JVM (`:shared:ui-kit:testDebugUnitTest`) — no emulator needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleOnly() {
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(title = "No sessions")
            }
        }
        composeRule.onNodeWithText("No sessions").assertIsDisplayed()
    }

    @Test
    fun rendersDescriptionWhenProvided() {
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(
                    title = "No panes yet",
                    description = "Start a tmux window to see panes here.",
                )
            }
        }
        composeRule.onNodeWithText("No panes yet").assertIsDisplayed()
        composeRule.onNodeWithText("Start a tmux window to see panes here.")
            .assertIsDisplayed()
    }

    @Test
    fun rendersActionSlotAndRoutesTap() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(
                    title = "No hosts",
                    description = "Add your first host to get started.",
                    icon = Icons.Filled.Info,
                    action = {
                        PocketShellButton(
                            text = "Add host",
                            onClick = { clicks++ },
                            variant = ButtonVariant.Primary,
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithText("No hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Add your first host to get started.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add host").assertIsDisplayed()
        composeRule.onNodeWithText("Add host").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun omitsDescriptionWhenNull() {
        val description = "this should not render"
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(title = "Nothing here")
            }
        }
        // The title shows; the (absent) supporting line does not exist.
        composeRule.onNodeWithText("Nothing here").assertIsDisplayed()
        composeRule.onNodeWithText(description).assertDoesNotExist()
    }
}
