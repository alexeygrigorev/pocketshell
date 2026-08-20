package com.pocketshell.uikit.components

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertNotEquals
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
class AgentStateChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun workingRendersAccessibleCompactIcon() {
        assertAccessibleCompactIcon(SessionAgentState.Working, "Working")
    }

    @Test
    fun waitingRendersAccessibleCompactIcon() {
        assertAccessibleCompactIcon(SessionAgentState.WaitingForInput, "Waiting for input")
    }

    @Test
    fun idleRendersAccessibleCompactIcon() {
        assertAccessibleCompactIcon(SessionAgentState.Idle, "Idle (finished)")
    }

    @Test
    fun stateDescriptionsDistinguishFinishedFromWorkingAndWaiting() {
        composeRule.setContent {
            PocketShellTheme {
                AgentStateChip(state = SessionAgentState.Idle)
                AgentStateChip(state = SessionAgentState.Working)
                AgentStateChip(state = SessionAgentState.WaitingForInput)
            }
        }

        // The idle glyph must communicate a finished/resting agent, not a
        // paused control. Working and waiting remain separate states.
        composeRule.onNodeWithContentDescription("Idle (finished)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Working").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Waiting for input").assertIsDisplayed()
    }

    @Test
    fun unknownRendersNothing() {
        composeRule.setContent {
            PocketShellTheme {
                Text("marker")
                AgentStateChip(
                    state = SessionAgentState.Unknown,
                    modifier = Modifier.testTag("state-icon"),
                )
            }
        }

        composeRule.onNodeWithText("marker").assertIsDisplayed()
        composeRule.onNodeWithTag("state-icon").assertDoesNotExist()
    }

    private fun assertAccessibleCompactIcon(
        state: SessionAgentState,
        label: String,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                AgentStateChip(
                    state = state,
                    modifier = Modifier.testTag("state-icon"),
                )
            }
        }

        composeRule.onNodeWithContentDescription(label)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(18.dp)
            .assertHeightIsEqualTo(18.dp)
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun stateIconsKeepWorkingAndFinishedDistinctFromWaiting() {
        assertTrue(agentStateIconFor(SessionAgentState.Idle)?.name.orEmpty().endsWith("CheckCircle"))
        assertTrue(agentStateIconFor(SessionAgentState.Working)?.name.orEmpty().endsWith("Autorenew"))
        assertTrue(
            agentStateIconFor(SessionAgentState.WaitingForInput)?.name.orEmpty().endsWith("HourglassEmpty"),
        )
        assertNotEquals(
            "working must not be represented by the finished/idle icon",
            agentStateIconFor(SessionAgentState.Working),
            agentStateIconFor(SessionAgentState.Idle),
        )
    }
}
