package com.pocketshell.app.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side picker proof for issue #2276.
 *
 * The host manifest remains the source of truth: unavailable and disabled
 * rows are supplied intact so their reasons can be retained, but only the
 * createable row gets a segment and can be selected. This drives the real
 * picker content rather than testing a duplicate list projection.
 */
@RunWith(AndroidJUnit4::class)
class EngineAvailabilityPickerUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unavailableAndDisabledEnginesAreHiddenFromCreatePicker() {
        val available = pickerTestEngine(
            id = "available",
            family = SessionAgentKind.Codex,
            label = "Available",
        )
        val unavailable = available.copy(
            id = "missing",
            label = "Missing",
            available = false,
            availableForCreate = false,
            unavailableReason = "`codex` is not installed on this host (not on PATH).",
        )
        val disabled = available.copy(
            id = "disabled",
            label = "Disabled",
            enabled = false,
            availableForCreate = false,
            unavailableReason = "disabled in the host registry",
        )
        var choice: SessionTypeChoice? = null

        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/srv/app",
                    folderLabel = "app",
                    onCancel = {},
                    onCreate = { choice = it },
                    engines = listOf(available, unavailable, disabled),
                )
            }
        }

        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("available"))
            .assertIsDisplayed()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("missing"))
            .assertDoesNotExist()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("disabled"))
            .assertDoesNotExist()
        compose.onNodeWithText("Available").assertIsDisplayed()
        compose.onNodeWithText("Missing").assertDoesNotExist()
        compose.onNodeWithText("Disabled").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("available", choice?.engineId)
    }
}
