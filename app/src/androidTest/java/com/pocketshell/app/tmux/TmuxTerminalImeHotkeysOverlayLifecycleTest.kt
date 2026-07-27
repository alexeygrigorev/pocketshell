package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retained-screen proof for the root-level IME overlay.
 *
 * Two tmux destinations may remain composed during navigation. Exactly the
 * RESUMED lifecycle owner may expose the public launcher, and the gate must
 * react when foreground ownership swaps.
 */
@RunWith(AndroidJUnit4::class)
class TmuxTerminalImeHotkeysOverlayLifecycleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onlyResumedRetainedScreenExposesClickableLauncher() {
        val retained = TestLifecycleOwner()
        val foreground = TestLifecycleOwner()
        val foregroundUsesSecondPane = mutableStateOf(false)
        var retainedClicks = 0
        var foregroundFirstPaneClicks = 0
        var foregroundSecondPaneClicks = 0

        compose.runOnIdle {
            retained.registry.currentState = Lifecycle.State.STARTED
            foreground.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLifecycleOwner provides retained) {
                        TmuxTerminalImeHotkeysOverlay(
                            isImeVisible = true,
                            hasPane = true,
                            isConversationTab = false,
                            onShowHotkeysTap = { retainedClicks += 1 },
                        )
                    }
                    CompositionLocalProvider(LocalLifecycleOwner provides foreground) {
                        TmuxTerminalImeHotkeysOverlay(
                            isImeVisible = true,
                            hasPane = true,
                            isConversationTab = false,
                            onShowHotkeysTap = if (foregroundUsesSecondPane.value) {
                                { foregroundSecondPaneClicks += 1 }
                            } else {
                                { foregroundFirstPaneClicks += 1 }
                            },
                        )
                    }
                }
            }
        }

        assertExactlyOneLauncher()
        compose.onNodeWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.runOnIdle {
            assertEquals(0, retainedClicks)
            assertEquals(1, foregroundFirstPaneClicks)
            assertEquals(0, foregroundSecondPaneClicks)
            foregroundUsesSecondPane.value = true
        }

        // A retained destination can also switch its active tmux pane without
        // changing lifecycle ownership. The single public launcher must invoke
        // the latest pane-routed callback, not the one captured at first mount.
        assertExactlyOneLauncher()
        compose.onNodeWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, foregroundFirstPaneClicks)
            assertEquals(1, foregroundSecondPaneClicks)
            foreground.registry.currentState = Lifecycle.State.STARTED
            retained.registry.currentState = Lifecycle.State.RESUMED
        }

        assertExactlyOneLauncher()
        compose.onNodeWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, retainedClicks)
            assertEquals(1, foregroundFirstPaneClicks)
            assertEquals(1, foregroundSecondPaneClicks)
        }
    }

    private fun assertExactlyOneLauncher() {
        val nodes = compose.onAllNodesWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("only the RESUMED retained screen may expose a launcher", 1, nodes.size)
        compose.onNodeWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).assertHasClickAction()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
