package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2635 N2: cross-workspace switching without leaving the terminal.
 *
 * The audit measured this at THREE taps from a terminal — back to the workspace
 * list, the workspace, the session — against the desktop's one, where the
 * folder panel never leaves the screen. The switcher sheet the user already
 * opens for sibling sessions now lists the neighbours too, so any session on
 * the host is two taps.
 *
 * These fail on the pre-N2 sheet, which had no "Other workspaces" section at
 * all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SwitcherOtherWorkspacesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the switcher sheet lists the host's other workspaces`() {
        setContent()

        scrollTo(SESSION_SWITCHER_OTHER_WORKSPACES_LABEL_TAG)
        composeRule.onNodeWithText(SESSION_SWITCHER_OTHER_WORKSPACES_LABEL).assertIsDisplayed()
        scrollTo(switcherWorkspaceRowTag(APLEXER))
        composeRule.onNodeWithTag(switcherWorkspaceRowTag(APLEXER)).assertIsDisplayed()
        scrollTo(switcherWorkspaceRowTag(NOTES))
        composeRule.onNodeWithTag(switcherWorkspaceRowTag(NOTES)).assertIsDisplayed()
    }

    /** One tap, and it lands on that workspace's own entry session. */
    @Test
    fun `tapping a neighbour opens its entry session, not a list`() {
        val opened = mutableListOf<Pair<String, String>>()
        setContent(onOpenWorkspace = { opened += it.path to it.entrySessionName })

        // The sheet is a bounded `LazyColumn` (560dp max), so a neighbour row
        // can start life below the fold — a bare `performClick` on it is a
        // flake, and was: this test passed in isolation and failed once inside
        // the full-suite run. Scroll it into view first, the way a user would.
        scrollTo(switcherWorkspaceRowTag(APLEXER))
        composeRule.onNodeWithTag(switcherWorkspaceRowTag(APLEXER)).performClick()

        assertEquals(listOf(APLEXER to "aplexer-main"), opened)
    }

    /**
     * The CURRENT workspace is not in that list — it is the tab strip, and
     * listing it twice would be the duplicate affordance D22 exists to stop.
     */
    @Test
    fun `the current workspace is not listed as a neighbour`() {
        setContent()

        composeRule.onNodeWithTag(switcherWorkspaceRowTag(CURRENT)).assertDoesNotExist()
    }

    /** With no neighbours the section is absent, not an empty heading. */
    @Test
    fun `a host with one workspace shows no other-workspaces section`() {
        setContent(otherWorkspaces = emptyList())

        composeRule.onNodeWithText(SESSION_SWITCHER_OTHER_WORKSPACES_LABEL).assertDoesNotExist()
    }

    /** Bring [tag] into the bounded sheet's viewport before touching it. */
    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(SESSION_SWITCHER_SHEET_TAG)
            .performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun setContent(
        otherWorkspaces: List<SwitcherWorkspace> = listOf(
            SwitcherWorkspace(APLEXER, "aplexer", 2, attached = true, entrySessionName = "aplexer-main"),
            SwitcherWorkspace(NOTES, "notes", 1, attached = false, entrySessionName = "notes-shell"),
        ),
        onOpenWorkspace: (SwitcherWorkspace) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionSwitcherSheet(
                    currentSessionName = "main",
                    state = SessionSwitcherUiState(
                        sessions = listOf(session("main", CURRENT)),
                        hostLabel = "hetzner",
                        workspacePath = CURRENT,
                        otherWorkspaces = otherWorkspaces,
                    ),
                    onNewSession = {},
                    onOpenSession = {},
                    onDismiss = {},
                    onOpenWorkspace = onOpenWorkspace,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun session(name: String, workspace: String): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = null,
        activityEpoch = null,
    )

    private companion object {
        const val CURRENT = "/home/alexey/git/pocketshell"
        const val APLEXER = "/home/alexey/git/aplexer"
        const val NOTES = "/home/alexey/notes"
    }
}
