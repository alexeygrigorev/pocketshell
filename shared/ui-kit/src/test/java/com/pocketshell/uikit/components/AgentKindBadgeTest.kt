package com.pocketshell.uikit.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class AgentKindBadgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readsFullAgentNameToScreenReaderNotTheMonogram() {
        setBadge(monogram = "CL", label = "Claude")

        composeRule.onNodeWithContentDescription("Claude").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("CL").assertDoesNotExist()
    }

    @Test
    fun codexReadsFullAgentName() {
        setBadge(monogram = "CX", label = "Codex")

        composeRule.onNodeWithContentDescription("Codex").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("CX").assertDoesNotExist()
    }

    @Test
    fun badgeIsCompact() {
        var density = 1f
        composeRule.setContent {
            PocketShellTheme {
                density = LocalDensity.current.density
                AgentKindBadge(
                    monogram = "CL",
                    label = "Claude",
                    isAgent = true,
                    modifier = Modifier.testTag("agent-kind"),
                )
            }
        }

        val widthDp = composeRule.onNodeWithContentDescription("Claude")
            .fetchSemanticsNode()
            .boundsInRoot
            .width / density
        assertTrue("agent-kind badge must stay under 44dp, was ${widthDp}dp", widthDp < 44f)
    }

    private fun setBadge(
        monogram: String,
        label: String,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                AgentKindBadge(
                    monogram = monogram,
                    label = label,
                    isAgent = true,
                )
            }
        }
    }
}
