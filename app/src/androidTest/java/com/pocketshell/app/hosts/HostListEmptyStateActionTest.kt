package com.pocketshell.app.hosts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Issue #1243: first-run host-list empty state exposes a labelled primary add
 * action in addition to the existing bottom-right FAB. This directly renders
 * the host-list empty-state component under the real app theme; full add/edit
 * routing remains covered by the existing HostListScreen/AddEditHostScreen
 * connected journeys.
 */
class HostListEmptyStateActionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStatePrimaryAddAction_rendersAndInvokesAddCallback() {
        var addClicks = 0

        compose.setContent {
            PocketShellTheme {
                EmptyHostList(onAddHost = { addClicks++ })
            }
        }

        compose.onNodeWithTag(HOST_LIST_EMPTY_STATE_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("No hosts yet", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Add an SSH host to start a session.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(HOST_LIST_EMPTY_ADD_HOST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            assertEquals(1, addClicks)
        }
    }
}
