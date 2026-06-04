package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issues #192 / #156: chrome-level coverage for the per-window
 * navigation strip ([WindowStrip]).
 *
 * These mount the composables directly (no Hilt / live tmux) so the
 * #192 affordances are verified at the UI layer:
 *
 *  - The active pill carries an explicit ✕ that kills THAT window in
 *    one tap (kill control is ≤ 1 tap from the window, not in the kebab
 *    two levels up).
 *  - Selecting a pill switches windows.
 *  - The "+ window" affordance is present on the strip.
 * The Terminal/Conversation pill now lives inside
 * [ConsolidatedTopChrome] per #303; [TmuxSessionScreenImeChromeTest]
 * covers that inline toolbar behavior.
 */
@RunWith(AndroidJUnit4::class)
class WindowStripChromeUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sampleWindows(count: Int): List<WindowSummary> =
        (1..count).map { idx -> WindowSummary(windowId = "@$idx", title = "Window $idx") }

    @Test
    fun activePillExposesKillAffordanceForThatWindow() {
        var killedWindowId: String? = null
        compose.setContent {
            PocketShellTheme {
                Column {
                    WindowStrip(
                        windows = sampleWindows(3),
                        currentWindowId = "@2",
                        onSelectWindow = {},
                        onOpenWindowMenu = {},
                        onKillWindow = { killedWindowId = it.windowId },
                        onNewWindow = {},
                    )
                }
            }
        }

        // The strip itself is present.
        compose.onNodeWithTag(TMUX_WINDOW_STRIP_TAG).assertIsDisplayed()
        // Window 2 is the active pill (index 2, 1-based), so its ✕ is
        // present; the inactive pills carry none.
        compose.onNodeWithTag("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}2").assertIsDisplayed()
        compose.onAllNodesWithTagAssertCount("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}1", 0)
        compose.onAllNodesWithTagAssertCount("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}3", 0)

        // One tap on the active pill's ✕ kills THAT window — not the
        // kebab's current-window kill, and without a long-press.
        compose.onNodeWithTag("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}2").performClick()
        assertEquals("@2", killedWindowId)
    }

    @Test
    fun tappingPillSwitchesWindow() {
        var selectedWindowId: String? = null
        compose.setContent {
            PocketShellTheme {
                Column {
                    WindowStrip(
                        windows = sampleWindows(3),
                        currentWindowId = "@1",
                        onSelectWindow = { selectedWindowId = it.windowId },
                        onOpenWindowMenu = {},
                        onKillWindow = {},
                        onNewWindow = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}3").performClick()
        assertEquals("@3", selectedWindowId)
    }

    @Test
    fun newWindowAffordanceIsPresentOnStrip() {
        var newWindowTapped = false
        compose.setContent {
            PocketShellTheme {
                Column {
                    WindowStrip(
                        windows = sampleWindows(2),
                        currentWindowId = "@1",
                        onSelectWindow = {},
                        onOpenWindowMenu = {},
                        onKillWindow = {},
                        onNewWindow = { newWindowTapped = true },
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_NEW_WINDOW_BUTTON_TAG).performClick()
        assertTrue(newWindowTapped)
    }

    @Test
    fun killAffordanceAbsentOnInactivePills() {
        // Sanity: when no pill matches the current window id (defensive
        // case) no ✕ is rendered at all, so the strip never accidentally
        // exposes a kill on a window the user is not on.
        var killed: WindowSummary? = null
        compose.setContent {
            PocketShellTheme {
                Column {
                    WindowStrip(
                        windows = sampleWindows(3),
                        currentWindowId = "@nonexistent",
                        onSelectWindow = {},
                        onOpenWindowMenu = {},
                        onKillWindow = { killed = it },
                        onNewWindow = {},
                    )
                }
            }
        }
        compose.onAllNodesWithTagAssertCount("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}1", 0)
        compose.onAllNodesWithTagAssertCount("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}2", 0)
        compose.onAllNodesWithTagAssertCount("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}3", 0)
        assertNull(killed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagAssertCount(
        tag: String,
        expected: Int,
    ) {
        val count = onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertEquals("expected $expected node(s) tagged $tag, found $count", expected, count)
    }
}
