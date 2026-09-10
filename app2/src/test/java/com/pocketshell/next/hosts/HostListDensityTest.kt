package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2630, reproduce-first: "the interface became worse. it's too large."
 *
 * The maintainer's Hosts screenshot is exactly the state built below — one host
 * plus a "Tools" section and an "Add host" bar — and it spent a 915dp phone
 * screen on six items. On the follow-up review they crossed out the Tools
 * section and the Add-host bar outright, leaving the page as: header, the host
 * list, and two icon actions.
 *
 * These fail on the shipped screen (72dp rows behind a 48dp-floored static
 * section label, plus full-width SSH keys / Settings / Add host chrome) and
 * pass on the density pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class HostListDensityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSingleHostScreenIsCompactAndCarriesNoFullWidthChrome() {
        setContent()

        val density = composeRule.density.density

        // A host row carries a 48dp kebab, so it is exactly the tap floor plus
        // the row's own vertical padding — 64dp, down from the 80dp the
        // redesign shipped.
        val hostRow = composeRule.onNodeWithTag(hostRowTag(1)).fetchSemanticsNode().boundsInRoot
        val rowHeight = hostRow.height / density
        assertTrue(
            "a host row must still clear the 48dp tap floor, was ${rowHeight}dp",
            rowHeight >= 48f,
        )
        assertTrue(
            "#2630: a host row was 80dp; it must be compact now, was ${rowHeight}dp",
            rowHeight <= 64f,
        )

        // The whole page is now header + one host row. The maintainer crossed
        // out everything else, so the page must END at the last host row.
        val pageBottom = hostRow.bottom / density
        assertTrue(
            "#2630: a one-host page ran to ~420dp of chrome; it must now end at " +
                "the host row, which ended at ${pageBottom}dp",
            pageBottom <= 200f,
        )
    }

    /**
     * Tools and Add-host are compact HEADER actions, not full-width page chrome.
     *
     * #2630 maintainer review crossed out the "Tools" section (SSH keys +
     * Settings rows) and the full-width "Add host" footer in the shipped
     * render. Both are now icon buttons in the header: SSH keys and Settings
     * live one tap away behind the cog, Add host behind the "+".
     *
     * This fails on the shipped screen, where both rows and the footer are
     * full-width children of the list.
     */
    @Test
    fun toolsAndAddHostAreCompactHeaderIconsNotFullWidthRows() {
        setContent()

        val density = composeRule.density.density
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width / density
        val hostRow = composeRule.onNodeWithTag(hostRowTag(1)).fetchSemanticsNode().boundsInRoot

        listOf(HOST_LIST_TOOLS_TAG to "cog", HOST_LIST_ADD_TAG to "add").forEach { (tag, what) ->
            composeRule.onAllNodesWithTag(tag).assertCountEquals(1)
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val width = bounds.width / density
            val height = bounds.height / density
            assertTrue(
                "the $what action must keep the 48dp tap target, was ${width}x${height}dp",
                width >= 48f && height >= 48f,
            )
            assertTrue(
                "#2630: the $what action must be a compact icon, not a full-width " +
                    "row — was ${width}dp of a ${screenWidth}dp screen",
                width <= 64f,
            )
            assertTrue(
                "the $what action belongs in the header, above the first host row",
                bounds.bottom <= hostRow.top,
            )
        }

        // Nothing named Tools / SSH keys / Settings is on the page itself until
        // the cog is tapped, and there is no full-width Add host bar any more.
        composeRule.onNodeWithText("Tools").assertDoesNotExist()
        composeRule.onNodeWithText("SSH keys").assertDoesNotExist()
        composeRule.onNodeWithText("Connection and app preferences").assertDoesNotExist()
        composeRule.onAllNodesWithText("Add host").assertCountEquals(0)
    }

    /** One tap on the cog reveals BOTH device surfaces, not one of them. */
    @Test
    fun theCogOpensSshKeysAndSettingsTogetherInOneTap() {
        setContent()

        composeRule.onNodeWithTag(HOST_LIST_TOOLS_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(HOST_LIST_TOOLS_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(HOST_LIST_KEYS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_LIST_SETTINGS_ROW_TAG).assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            PocketShellTheme {
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
                    onOpenSshKeys = {},
                    onDeleteHost = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
