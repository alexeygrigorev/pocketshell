package com.pocketshell.app.proof

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TmuxConversationPane
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkthroughConversationScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturesConversationPaneForDocs() {
        compose.activityRule.scenario.onActivity { activity ->
            val dark = PocketShellColors.Background.toArgb()
            activity.window.decorView.setBackgroundColor(dark)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = dark
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    TmuxConversationPane(
                        events = sampleConversationEvents(),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TMUX_CONVERSATION_PANE_TAG),
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG).assertIsDisplayed()
        compose.onAllNodesWithText("US" + "ER", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("ASS" + "ISTANT", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("Bash", substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("deploy logs", substring = true, useUnmergedTree = true)
            .assertCountEquals(1)

        WalkthroughScreenshotArtifacts.capture("06-conversation-view")
    }

    private fun sampleConversationEvents(): List<ConversationEvent> = listOf(
        ConversationEvent.Message(
            id = "u1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Check why the staging deploy failed and keep it brief.",
        ),
        ConversationEvent.Message(
            id = "a1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "I will inspect the deploy logs and compare the failing revision with the last green run.",
        ),
        ConversationEvent.ToolCall(
            id = "t1",
            agent = AgentKind.ClaudeCode,
            name = "Bash",
            input = "kubectl logs deploy/api -n staging --tail=80",
        ),
        ConversationEvent.ToolResult(
            id = "r1",
            agent = AgentKind.ClaudeCode,
            toolCallId = "t1",
            output = "migrations: pending column user_timezone on table accounts",
        ),
        ConversationEvent.Message(
            id = "u2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Patch it and rerun the dry-run.",
        ),
        ConversationEvent.Message(
            id = "a2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "The migration dry-run is clean now. Next step: redeploy staging and watch the rollout.",
        ),
    )
}
