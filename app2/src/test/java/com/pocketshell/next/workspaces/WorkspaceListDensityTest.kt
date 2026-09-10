package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2630 follow-up, reproduce-first: "still too spacious".
 *
 * The maintainer's density reference is PocketShell Desktop's session sidebar,
 * where a workspace is genuinely ONE line — `dtc-website  4  now` — with no
 * subtitle row. The shipped mobile row was a 16sp title over a wrapped per-kind
 * summary ("Terminal ×2 · Claude"), floored at the 64dp workspace navigation
 * target.
 *
 * These fail on that shape (the row measures well over 48dp and the summary
 * text is present) and pass on the dense single-line row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceListDensityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aWorkspaceRowIsOneDenseLineOnTheTapFloor() {
        setContent()

        val density = composeRule.density.density
        val row = composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .fetchSemanticsNode().boundsInRoot
        val height = row.height / density

        assertTrue(
            "a workspace row must keep the 48dp tap target, was ${height}dp",
            height >= 48f,
        )
        assertTrue(
            "#2630: the workspace row was a 64dp two-line row; it must now be a " +
                "single dense line on the tap floor, was ${height}dp",
            height <= 52f,
        )

        // The wrapped per-kind subtitle is gone entirely, in every spelling it
        // used to render.
        composeRule.onNodeWithText("Terminal", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("No sessions").assertDoesNotExist()
        composeRule.onNodeWithText("more kinds", substring = true).assertDoesNotExist()
    }

    /**
     * Name, count and recency all live on that one line, as on the desktop.
     *
     * The row merges its descendants (it is one clickable Button), so the
     * merged node's own text IS the line a user and a screen reader get —
     * asserting on it is stronger than reaching for the inner tags, and it is
     * exactly the `dtc-website  4  now` shape the reference screenshot shows.
     */
    @Test
    fun theDenseRowCarriesTheCountAndRecencyInline() {
        setContent()

        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .assertIsDisplayed()
            .assertTextEquals("pocketshell", "4", "just now")

        // The same three, individually, in the unmerged tree.
        composeRule.onNodeWithTag(workspaceGlanceCountTag(BUSY_PATH), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceGlanceActivityTag(BUSY_PATH), useUnmergedTree = true)
            .assertIsDisplayed()

        // The glance sits INSIDE the row's single line, not under it.
        val row = composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .fetchSemanticsNode().boundsInRoot
        val count = composeRule
            .onNodeWithTag(workspaceGlanceCountTag(BUSY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val activity = composeRule
            .onNodeWithTag(workspaceGlanceActivityTag(BUSY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        listOf("count" to count, "activity" to activity).forEach { (what, bounds) ->
            assertTrue(
                "the $what must sit inside the row's single line " +
                    "(row ${row.top}-${row.bottom}, $what ${bounds.top}-${bounds.bottom})",
                bounds.top >= row.top && bounds.bottom <= row.bottom,
            )
        }
    }

    /** An empty workspace stays silent instead of spending ink saying "0". */
    @Test
    fun anEmptyWorkspaceShowsNoBadge() {
        setContent()

        composeRule.onNodeWithTag(workspaceRowTag(EMPTY_PATH))
            .assertIsDisplayed()
            .assertTextEquals("empty")
        composeRule.onNodeWithTag(workspaceGlanceCountTag(EMPTY_PATH), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(workspaceGlanceActivityTag(EMPTY_PATH), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `recency reads the freshest session and is null without timestamps`() {
        assertEquals(
            "1m ago",
            latestActivityLabel(
                listOf(session("a", NOW - 3_000), session("b", NOW - 90)),
                NOW,
            ),
        )
        assertEquals(null, latestActivityLabel(listOf(session("a", null)), NOW))
        assertEquals(null, latestActivityLabel(emptyList(), NOW))
    }

    private fun setContent() {
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = HostWorkspacesUiState(
                        hostId = 7,
                        hostLabel = "hetzner",
                        loaded = true,
                        roots = listOf(
                            WorkspaceRootProjection(
                                key = "/home/alexey/git",
                                label = "Git",
                                displayPath = "~/git",
                                path = "/home/alexey/git",
                                workspaces = listOf(
                                    WorkspaceProjection(
                                        path = BUSY_PATH,
                                        label = "pocketshell",
                                        displayPath = "~/git/pocketshell",
                                        sessions = List(4) { session("s$it", NOW - 10) },
                                        durable = true,
                                    ),
                                    WorkspaceProjection(
                                        path = EMPTY_PATH,
                                        label = "empty",
                                        displayPath = "~/git/empty",
                                        sessions = emptyList(),
                                        durable = true,
                                    ),
                                ),
                                rootSessions = emptyList(),
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onOpenWorkspace = {},
                    onOpenSession = {},
                    nowSec = NOW,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun session(name: String, activityEpoch: Long?): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = BUSY_PATH,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = 1L,
        activityEpoch = activityEpoch,
    )

    private companion object {
        const val NOW: Long = 1_800_000_000L
        const val BUSY_PATH = "/home/alexey/git/pocketshell"
        const val EMPTY_PATH = "/home/alexey/git/empty"
    }
}
