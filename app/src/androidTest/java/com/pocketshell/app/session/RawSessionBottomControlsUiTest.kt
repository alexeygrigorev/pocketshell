package com.pocketshell.app.session

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused coverage for the raw-SSH terminal bottom controls. This mirrors the
 * tmux accessory tests without requiring Hilt or a live SSH session.
 */
@RunWith(AndroidJUnit4::class)
class RawSessionBottomControlsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyboardOpenAccessoryKeepsUsableHeight() {
        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = true,
                    showConversation = false,
                    sessionLive = true,
                    onKey = {},
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    onProjectNavigationTap = {},
                    modifier = Modifier.testTag(RAW_BOTTOM_CONTROLS_TAG),
                )
            }
        }

        val height = compose
            .onNodeWithTag(RAW_BOTTOM_CONTROLS_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        assertTrue(
            "IME accessory controls must keep a real tap target instead of collapsing to a thin strip",
            height >= 56f,
        )
    }

    @Test
    fun keyboardOpenAccessoryShowsRawHotkeysOnlyIncludingEnter() {
        val keyTaps = mutableListOf<String>()
        var keyboardTaps = 0

        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = true,
                    showConversation = false,
                    sessionLive = true,
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = { keyboardTaps++ },
                    onAddSnippetTap = {},
                    onProjectNavigationTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertIsDisplayed()
        compose.onNodeWithText("Ctrl-C").assertIsDisplayed()
        compose.onNodeWithText(SessionKeyBarEnterLabel)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText("Ctrl-D").assertIsDisplayed()
        compose.onNodeWithText("Tab").assertIsDisplayed()

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithText("show keyboard").assertDoesNotExist()
        compose.onNodeWithText(ADD_COMMAND_CHIP_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Prompt").assertDoesNotExist()
        compose.onNodeWithText("Command").assertDoesNotExist()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.onNodeWithText("Speech capture ready").assertDoesNotExist()

        assertEquals(listOf(SessionKeyBarEnterLabel), keyTaps)
        assertEquals(0, keyboardTaps)
    }

    @Test
    fun keyboardHiddenKeepsBottomEnterAndShowKeyboardWithoutHotkeyBar() {
        val keyTaps = mutableListOf<String>()
        var keyboardTaps = 0

        compose.setContent {
            PocketShellTheme {
                RawSessionBottomControls(
                    isImeVisible = false,
                    showConversation = false,
                    sessionLive = true,
                    onKey = { keyTaps += it.label },
                    onChipTap = {},
                    onDictateTap = {},
                    onShowKeyboardTap = { keyboardTaps++ },
                    onAddSnippetTap = {},
                    onProjectNavigationTap = {},
                )
            }
        }

        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-C").assertDoesNotExist()
        compose.onNodeWithText("Ctrl-D").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()

        assertEquals(listOf(SessionKeyBarEnterLabel), keyTaps)
        assertEquals(1, keyboardTaps)
    }

    @Test
    fun rawSshKeyBarLayoutPlacesEnterInDefaultRowNearCtrlControls() {
        assertEquals(
            listOf("Esc", "Ctrl", "Ctrl-C", SessionKeyBarEnterLabel, "Ctrl-D", "Tab"),
            SessionTerminalKeyBarLayout.take(6).map { it.label },
        )
    }

    private companion object {
        const val RAW_BOTTOM_CONTROLS_TAG = "raw:bottom-controls"
    }
}
