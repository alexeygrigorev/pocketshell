package com.pocketshell.app.snippets

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
class CommandTemplateEditorDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editorCollectsLabelAndMultilineCommands() {
        val saves = mutableListOf<Pair<String, String>>()

        compose.setContent {
            PocketShellTheme {
                CommandTemplateEditorDialog(
                    initial = null,
                    onDismiss = {},
                    onSave = { label, commands -> saves += label to commands },
                )
            }
        }

        compose.onNodeWithText("Add macro").assertIsDisplayed()
        compose.onNodeWithTag(commandTemplateLabelFieldTag()).performTextInput("Git release")
        compose.onNodeWithTag(commandTemplateCommandsFieldTag())
            .performTextInput("git tag {{version}}\ngit push origin {{version}}")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            listOf("Git release" to "git tag {{version}}\ngit push origin {{version}}"),
            saves,
        )
    }
}
