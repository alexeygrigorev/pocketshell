package com.pocketshell.next.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlashCommandSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `search filters the selected session command catalog`() {
        setContent()

        composeRule.onNodeWithTag(COMPOSER_SLASH_SEARCH_TAG).performTextInput("compact")

        composeRule.onNodeWithTag(composerSlashRowTag("/compact")).assertIsDisplayed()
        composeRule.onNodeWithTag(composerSlashRowTag("/clear")).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_SLASH_NOTE_TAG).assertIsDisplayed()
    }

    @Test
    fun `choosing a command reports the insertion without executing it`() {
        var picked: SlashCommand? = null
        setContent(onPick = { picked = it })

        composeRule.onNodeWithTag(composerSlashRowTag("/clear")).performClick()

        assertEquals("/clear", picked?.command)
    }

    @Test
    fun `unknown capability lists no commands`() {
        setContent(commands = emptyList())

        composeRule.onNodeWithText("No matching commands.").assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SLASH_NOTE_TAG).assertIsDisplayed()
    }

    private fun setContent(
        commands: List<SlashCommand> = SlashCommandAutocomplete.commandsFor("claude"),
        onPick: (SlashCommand) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SlashCommandSheetContent(
                    commands = commands,
                    onPick = onPick,
                    onDismiss = {},
                )
            }
        }
    }
}
