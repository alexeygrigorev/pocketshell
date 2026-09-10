package com.pocketshell.next.hosts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.usage.USAGE_GLANCE_PILL_TAG
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.model.PillKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632, acceptance criterion 1: "usage/cost is visible from the landing
 * screen".
 *
 * The Hosts list is a pre-connection screen — usage never dials (D21) — so
 * "visible on load" can only mean the last reading, and this pins both halves:
 * the ViewModel hands the cached reading to the screen, and the screen paints
 * it in the header where it costs no taps.
 */
@RunWith(AndroidJUnit4::class)
class HostListUsageGlanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the header paints the pill and it opens the usage panel`() {
        var opened = 0
        composeRule.setContent {
            HostListScreen(
                state = HostListUiState(
                    loaded = true,
                    hosts = listOf(HostRow(1, "hetzner", "alexey@135.181.114.209")),
                    usagePill = UsageGlancePillState(
                        percent = 63,
                        provider = "Claude",
                        window = null,
                        kind = PillKind.Ok,
                        stale = true,
                        fetchedClock = "13:40",
                    ),
                ),
                onOpenHost = {},
                onAddHost = {},
                onEditHost = {},
                onOpenSettings = {},
                onDeleteHost = {},
                onOpenUsage = { opened += 1 },
            )
        }

        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertIsDisplayed()
        // A cached number never passes itself off as live: the clock it was
        // actually read at is painted next to it.
        composeRule.onNodeWithText("13:40").assertIsDisplayed()
        composeRule.onNodeWithText("63%").assertIsDisplayed()

        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no reading means no pill in the header`() {
        composeRule.setContent {
            HostListScreen(
                state = HostListUiState(
                    loaded = true,
                    hosts = listOf(HostRow(1, "hetzner", "alexey@135.181.114.209")),
                ),
                onOpenHost = {},
                onAddHost = {},
                onEditHost = {},
                onOpenSettings = {},
                onDeleteHost = {},
            )
        }

        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertDoesNotExist()
    }

}
