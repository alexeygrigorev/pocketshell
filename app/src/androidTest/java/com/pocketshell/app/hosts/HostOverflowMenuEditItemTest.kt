package com.pocketshell.app.hosts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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
 * Issue #519 — the host kebab overflow menu exposes a discoverable
 * "Edit" item, and tapping it invokes the host-edit callback.
 *
 * Before this issue the edit-host route was orphaned: the long-press →
 * Edit affordance was dropped in #38 (long-press now opens the kebab,
 * #113) and the kebab had no Edit item, so `onEditHost` was a dead
 * lambda and a user could only delete-and-re-add a host to change it.
 *
 * Rendered in isolation (no Hilt / SSH / Docker) so the surface is
 * deterministic; the live wire-up of `onEditHost(host.id)` into this
 * `onEdit` slot lives at the `HostListScreen` call site, and routing on
 * to `AppDestination.EditHost` lives in `MainActivity`.
 */
@RunWith(AndroidJUnit4::class)
class HostOverflowMenuEditItemTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun kebabEditItem_isPresent_andInvokesEditCallback() {
        var editClicks = 0

        compose.setContent {
            PocketShellTheme {
                HostOverflowMenuAnchor(
                    expanded = true,
                    onExpand = {},
                    onDismiss = {},
                    usageRecord = null,
                    usageBadgeTestTag = HOST_USAGE_BADGE_TAG_PREFIX + "edit-test",
                    onEdit = { editClicks++ },
                    onOpenUsage = {},
                    usageMenuItemTestTag = HOST_USAGE_MENU_ITEM_TAG_PREFIX + "edit-test",
                    onOpenPorts = {},
                    onOpenWatchedFolders = {},
                    onShare = {},
                    onRecheckSetup = {},
                )
            }
        }

        // The "Edit" item is discoverable in the expanded kebab.
        compose.onNodeWithTag(HOST_EDIT_ITEM_TAG).assertIsDisplayed()

        // Tapping it fires the host-edit callback exactly once.
        compose.onNodeWithTag(HOST_EDIT_ITEM_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, editClicks)
    }
}
