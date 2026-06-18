package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #823 (Slice 1) — proof for the manual pull-to-reconnect affordance on
 * the session surface.
 *
 * The full [TmuxSessionScreen] is too heavy to mount in a Compose androidTest
 * (it needs Hilt + a live tmux client), so this exercises the
 * [SessionSurfaceReconnectWrapper] composable directly with the SAME arguments
 * the screen passes in production (the screen wires `onReconnect` to the
 * existing [TmuxSessionViewModel.reconnect] entrypoint — see the
 * [SessionSurfaceReconnectWrapper] call site in `TmuxSessionScreen`).
 *
 * Asserts the user-facing contract:
 *
 *  1. In a NON-Connected state (`pullToReconnectActive = true`) the pull-to-
 *     reconnect affordance is present in the tree AND a pull-down gesture fires
 *     the supplied `onReconnect` callback — i.e. a discoverable manual reconnect
 *     trigger exists over the existing entrypoint. (The wrapped content is still
 *     shown beneath it.)
 *  2. In a Connected state (`pullToReconnectActive = false`) the pull wrapper is
 *     ABSENT so it never competes with the live terminal's scroll / selection /
 *     horizontal-paging gestures — the surface content renders bare.
 *
 * Slice 1 deliberately does NOT assert the #822 wedge is broken: that is
 * connection-core logic owned by epic #792 (Slice 2). This proof only certifies
 * the UI trigger is present and calls `reconnect()`.
 */
@RunWith(AndroidJUnit4::class)
class SessionSurfaceReconnectWrapperTest {

    @get:Rule
    val compose = createComposeRule()

    private val contentTag = "session-surface-content"

    @Test
    fun pullDownInNonConnectedStateFiresReconnect() {
        var reconnectCount = 0
        compose.setContent {
            PocketShellTheme {
                SessionSurfaceReconnectWrapper(
                    pullToReconnectActive = true,
                    isReconnecting = false,
                    onReconnect = { reconnectCount++ },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(contentTag)) {
                        Text("dropped session placeholder")
                    }
                }
            }
        }

        // The pull-to-reconnect affordance is present in the non-Connected state.
        compose.onNodeWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        // The wrapped surface content is still shown beneath the pull wrapper.
        compose.onNodeWithTag(contentTag, useUnmergedTree = true).assertIsDisplayed()

        // A pull-down gesture triggers the existing reconnect entrypoint.
        compose.onNodeWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertTrue(
            "pull-down on a non-Connected session must call reconnect() " +
                "(observed reconnectCount=$reconnectCount)",
            reconnectCount >= 1,
        )
    }

    @Test
    fun connectedStateHasNoPullWrapperSoItDoesNotFightTheTerminal() {
        compose.setContent {
            PocketShellTheme {
                SessionSurfaceReconnectWrapper(
                    pullToReconnectActive = false,
                    isReconnecting = false,
                    onReconnect = { error("reconnect must not be reachable while Connected") },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(contentTag)) {
                        Text("live terminal")
                    }
                }
            }
        }

        // The pull wrapper must NOT be mounted on a live session, so a pull
        // gesture can never steal the terminal's scroll/selection/paging.
        compose.onAllNodesWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        // The surface content still renders (bare, no wrapper).
        compose.onNodeWithTag(contentTag, useUnmergedTree = true).assertIsDisplayed()
    }
}
