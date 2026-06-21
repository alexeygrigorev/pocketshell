package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 *  2. In a SETTLED failed/dropped state (`showReconnectButton = true`) a VISIBLE,
 *     tappable "Reconnect" button is shown (the maintainer's "there's not even a
 *     button to reconnect" ask) AND a tap fires the SAME `onReconnect` entrypoint.
 *  3. Issue #890 — WHILE a connect/reconnect/attach is actively IN PROGRESS
 *     (`showReconnectButton = false`, i.e. the surface shows the progress bar +
 *     "Attaching…/Reconnecting…" hold), the visible Reconnect button is HIDDEN:
 *     offering "Reconnect" while the system is already trying is nonsensical. The
 *     pull GESTURE stays mounted throughout the non-Connected window (it is an
 *     explicit user action), so the manual-recovery affordance is never fully
 *     lost — only the redundant button chrome disappears during the in-progress
 *     hold.
 *  4. In a Connected state (`pullToReconnectActive = false`) BOTH the pull wrapper
 *     and the Reconnect button are ABSENT, so neither competes with the live
 *     terminal's scroll / selection / horizontal-paging gestures and the surface
 *     content renders bare.
 *
 * This proof certifies the affordances: the pull gesture is present whenever the
 * session is non-Connected; the VISIBLE button is present ONLY on a settled
 * failed/dropped state and HIDDEN during the in-progress connect/attach/reconnect
 * (#890). It deliberately does NOT assert the #822 wedge is broken: that is
 * connection-core logic owned by epic #792 (a separate slice).
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
    fun visibleReconnectButtonInSettledFailedStateFiresReconnect() {
        // Issue #890: on a SETTLED failed/dropped state (`showReconnectButton =
        // true`, the connect/attach has stopped trying) a VISIBLE, tappable
        // Reconnect button is present (the maintainer's "there's not even a
        // button" ask) and tapping it fires the existing reconnect entrypoint.
        var reconnectCount = 0
        compose.setContent {
            PocketShellTheme {
                SessionSurfaceReconnectWrapper(
                    pullToReconnectActive = true,
                    isReconnecting = false,
                    showReconnectButton = true,
                    onReconnect = { reconnectCount++ },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(contentTag)) {
                        Text("dropped session placeholder")
                    }
                }
            }
        }

        compose.onNodeWithTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()

        assertTrue(
            "tapping the visible Reconnect button must call reconnect() " +
                "(observed reconnectCount=$reconnectCount)",
            reconnectCount >= 1,
        )
    }

    @Test
    fun reconnectButtonIsHiddenDuringTheInProgressAttachingHold() {
        // Issue #890 (RED→GREEN; reverses #823's "stays visible during the attach
        // hold"). While a connect/reconnect/attach is actively IN PROGRESS — the
        // surface shows the progress bar + centered "Attaching…/Reconnecting…"
        // hold (`isReconnecting = true`, `surfaceShowsCenteredLoader = true`,
        // `showReconnectButton = false`) — offering "Reconnect" is nonsensical
        // (the system is already trying), so the VISIBLE Reconnect button MUST be
        // hidden. The maintainer's dogfood report: "no need for a reconnect button
        // when I am connecting."
        compose.setContent {
            PocketShellTheme {
                SessionSurfaceReconnectWrapper(
                    pullToReconnectActive = true,
                    isReconnecting = true,
                    surfaceShowsCenteredLoader = true,
                    showReconnectButton = false,
                    onReconnect = { error("reconnect button must not exist mid-attach") },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(contentTag)) {
                        Text("Attaching…")
                    }
                }
            }
        }

        // The button is ABSENT during the in-progress attach/connect/reconnect.
        compose.onAllNodesWithTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        // The pull GESTURE stays mounted (it is an explicit user action, not chrome
        // that competes with anything) — manual recovery is never fully lost.
        compose.onNodeWithTag(TMUX_PULL_TO_RECONNECT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        // The surface content (the "Attaching…" placeholder) still renders.
        compose.onNodeWithTag(contentTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun connectedStateHasNoPullWrapperOrButtonSoItDoesNotFightTheTerminal() {
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
        // The visible Reconnect button is ABSENT on a live session too — it only
        // appears when dropped/reconnecting, so it never occludes the terminal.
        compose.onAllNodesWithTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
        // The surface content still renders (bare, no wrapper).
        compose.onNodeWithTag(contentTag, useUnmergedTree = true).assertIsDisplayed()
    }
}
