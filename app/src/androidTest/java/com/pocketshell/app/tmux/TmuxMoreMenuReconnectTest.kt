package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #993: the live session kebab must expose a "Reconnect" item (stable test tag)
 * that, when tapped, invokes the reconnect callback — the manual escape hatch that forces
 * a reconnect of the CURRENT session in place so the user does not have to switch away and
 * back. The item is disabled while a connect/reconnect is already in flight (or there is no
 * target) so a tap is never a silent no-op / redundant re-dial. The full drop → Reconnect →
 * queued-message-flushes journey is proven end-to-end by the connected reconnect journey;
 * this focused test covers the kebab AC — the item is present, reachable, enable-gated, and
 * wired.
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuReconnectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reconnectItemIsPresentAndInvokesCallbackWhenEnabled() {
        var reconnectClicks = 0
        compose.setContent {
            PocketShellTheme {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    onDismiss = { expanded.value = false },
                    onCreateSession = {},
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onOpenSettings = {},
                    onDetach = {},
                    onRedraw = {},
                    onReconnect = { reconnectClicks++ },
                    reconnectEnabled = true,
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals("Reconnect kebab item should invoke onReconnect", 1, reconnectClicks)
    }

    @Test
    fun reconnectItemDisabledDoesNotInvokeCallback() {
        // AC: sensible disabled state mid-reconnect / no target — a tap on the disabled
        // item must NOT fire the reconnect (no silent redundant re-dial).
        var reconnectClicks = 0
        compose.setContent {
            PocketShellTheme {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    onDismiss = { expanded.value = false },
                    onCreateSession = {},
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onOpenSettings = {},
                    onDetach = {},
                    onRedraw = {},
                    onReconnect = { reconnectClicks++ },
                    reconnectEnabled = false,
                )
            }
        }

        compose
            .onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals("a disabled Reconnect item must not invoke onReconnect", 0, reconnectClicks)
    }
}
