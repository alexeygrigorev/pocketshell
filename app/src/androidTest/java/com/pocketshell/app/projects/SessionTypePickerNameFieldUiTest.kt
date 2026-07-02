package com.pocketshell.app.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI proof for issue #1184: the new-session picker exposes an editable
 * "Session name" field prefilled with the directory-derived default, and the
 * user's typed label is threaded onto [SessionTypeChoice.customName] (which
 * the caller sanitises + disambiguates via
 * [SessionNameDerivation.resolveSessionName]).
 *
 * Drives [SessionTypePickerContent] directly (no SSH / Compose sheet
 * animation), mirroring [SessionTypePickerSkipPermissionsUiTest]. The wiring
 * from `customName` → the final tmux name is JVM-tested in
 * [SessionNameDerivationTest]; this pins the UI-boundary behaviour:
 *
 *  - the field is PREFILLED with the derived default (accepting it unchanged
 *    reproduces today's behaviour — no regression),
 *  - a typed custom label is emitted on the choice,
 *  - clearing the field emits `null` so the caller falls back to the derived
 *    default (blank → default).
 */
@RunWith(AndroidJUnit4::class)
class SessionTypePickerNameFieldUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun content(onCreate: (SessionTypeChoice) -> Unit) {
        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/home/alexey/git/pocketshell",
                    folderLabel = "pocketshell",
                    onCancel = {},
                    onCreate = onCreate,
                    // Same deriver the production folder flow wires in.
                    deriveDefaultName = { dir ->
                        defaultSessionBaseName(dir, "/home/alexey")
                    },
                )
            }
        }
    }

    @Test
    fun nameFieldIsPrefilledWithDerivedDefault() {
        content(onCreate = {})
        // Prefilled with the directory-derived default `git-pocketshell`.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_NAME_TAG)
            .assertTextEquals("git-pocketshell")
    }

    @Test
    fun acceptingPrefilledNameUnchangedEmitsDerivedDefault() {
        var lastChoice: SessionTypeChoice? = null
        content(onCreate = { lastChoice = it })

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        // No regression: the derived default flows through as the custom label.
        assertEquals("git-pocketshell", lastChoice?.customName)
    }

    @Test
    fun typedCustomLabelIsThreadedOntoTheChoice() {
        var lastChoice: SessionTypeChoice? = null
        content(onCreate = { lastChoice = it })

        val field = compose.onNodeWithTag(SESSION_TYPE_PICKER_NAME_TAG)
        field.performTextClearance()
        field.performTextInput("git-pocketshell-review")
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("git-pocketshell-review", lastChoice?.customName)
    }

    @Test
    fun clearingNameEmitsNullSoCallerFallsBackToDerivedDefault() {
        var lastChoice: SessionTypeChoice? = null
        content(onCreate = { lastChoice = it })

        compose.onNodeWithTag(SESSION_TYPE_PICKER_NAME_TAG).performTextClearance()
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        // Blank field → null customName → caller uses the derived default.
        assertNull(lastChoice?.customName)
    }
}
