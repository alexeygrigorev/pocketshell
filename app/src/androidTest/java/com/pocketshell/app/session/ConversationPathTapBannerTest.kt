package com.pocketshell.app.session

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

/** Rendered acceptance proof for issue #1890's non-silent in-place feedback. */
@RunWith(AndroidJUnit4::class)
class ConversationPathTapBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingPathShowsReasonAndExactResolvedPathAndCanDismiss() {
        var dismissedRequest: Long? = null
        compose.setContent {
            PocketShellTheme {
                ConversationPathTapBanner(
                    state = ConversationPathTapState.Failed(
                        requestId = 19L,
                        resolvedPath = "/home/agent/work/missing-output",
                        reason = "Path does not exist.",
                    ),
                    onDismiss = { dismissedRequest = it },
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_PATH_TAP_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Path does not exist.", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            "/home/agent/work/missing-output",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag(CONVERSATION_PATH_TAP_DISMISS_TAG)
            .assertIsDisplayed()
            .performClick()
        assertEquals(19L, dismissedRequest)
    }

    @Test
    fun boundedProbeShowsResolvedTargetWhileItIsInFlight() {
        compose.setContent {
            PocketShellTheme {
                ConversationPathTapBanner(
                    state = ConversationPathTapState.Checking(
                        requestId = 20L,
                        resolvedPath = "/home/agent/work/README",
                    ),
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_PATH_TAP_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            "Checking /home/agent/work/README",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun timeoutFailureShowsReasonAndExactResolvedPath() {
        compose.setContent {
            PocketShellTheme {
                ConversationPathTapBanner(
                    state = ConversationPathTapState.Failed(
                        requestId = 21L,
                        resolvedPath = "/home/agent/work/slow-target",
                        reason = "Timed out while checking the path.",
                    ),
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(
            "Timed out while checking the path.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "/home/agent/work/slow-target",
            substring = true,
        ).assertIsDisplayed()
    }
}
