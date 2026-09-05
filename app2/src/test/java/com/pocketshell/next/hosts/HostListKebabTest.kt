package com.pocketshell.next.hosts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #2523: the host kebab is Edit and Delete only. Share QR must not
 * reappear — Scan in the header is the remaining QR path.
 */
@RunWith(RobolectricTestRunner::class)
class HostListKebabTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun kebabHasEditAndDeleteAndNotShareQr() {
        composeRule.setContent {
            HostListScreen(
                state = HostListUiState(
                    loaded = true,
                    hosts = listOf(HostRow(1, "hetzner", "alexey@135.181.114.209")),
                ),
                onOpenHost = {},
                onAddHost = {},
                onEditHost = {},
                onScanQr = {},
                onOpenSettings = {},
                onDeleteHost = {},
            )
        }

        composeRule.onNodeWithTag(hostRowMenuTag(1)).performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Share QR").assertDoesNotExist()
    }
}
