package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
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

    /**
     * #2635 (D1 remainder): the desktop's row grammar has a LEADING dot that
     * says "something live is in here". The phone's row had no status at all.
     *
     * Fails before the dot exists; the two workspaces below differ only in
     * whether one of their sessions is attached, so the assertion is about the
     * dot MEANING something, not merely being drawn.
     */
    @Test
    fun `a workspace with an attached session carries a leading status dot`() {
        setContent()

        val busy = composeRule
            .onNodeWithTag(workspaceRowStatusTag(BUSY_PATH), useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertEquals(
            listOf("Has an attached session"),
            busy.config[SemanticsProperties.ContentDescription],
        )

        val quiet = composeRule
            .onNodeWithTag(workspaceRowStatusTag(EMPTY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertEquals(
            listOf("No attached session"),
            quiet.config[SemanticsProperties.ContentDescription],
        )

        // The dot leads the row: it is left of the workspace name.
        val title = composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the dot must lead the row",
            busy.boundsInRoot.left >= title.left && busy.boundsInRoot.right < title.right,
        )
    }

    /**
     * #2635 (D1 remainder): the count is a bare muted integer, not a filled
     * chip. A filled surface on EVERY row is the one bit of chrome a scannable
     * list does not need, and its 6dp radius was off the token ladder — which
     * is why `scripts/check-design-tokens.sh` flags it once the allow-list is
     * the real `{4, 8, 12, 24}` ladder.
     */
    @Test
    fun `the count badge is a bare integer, not a filled chip`() {
        setContent()

        val count = composeRule
            .onNodeWithTag(workspaceGlanceCountTag(BUSY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode()
        val text = count.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals("4", text)

        // A filled chip is padded well past the glyph; a bare integer is not.
        val density = composeRule.density.density
        val width = count.boundsInRoot.width / density
        // A bare 11sp mono digit measures ~6.7dp.
        assertTrue(
            "the count must be one glyph wide, was ${width}dp",
            width <= 9f,
        )

        // The load-bearing oracle is the GAP, not the width: Compose reports a
        // semantics node's bounds INSIDE its own padding, so a chip's padding
        // is invisible to a width assertion (it re-appears as extra space
        // between the count and its neighbour). The glance row is
        // `Arrangement.spacedBy(sm)` = 8dp; the chip this replaced added 6dp of
        // horizontal padding on top, so any gap past ~11dp means it came back.
        val activity = composeRule
            .onNodeWithTag(workspaceGlanceActivityTag(BUSY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val gap = (activity.left - count.boundsInRoot.right) / density
        assertTrue(
            "a bare integer must not be padded into a chip; count-to-recency " +
                "gap was ${gap}dp, the plain rung is 8dp",
            gap <= 11f,
        )
    }

    /**
     * #2635 (D1 remainder): no chevron. Every row on this list navigates, so a
     * per-row glyph repeating that a dozen times is chrome, not affordance.
     */
    @Test
    fun `a workspace row has no navigation chevron`() {
        setContent()

        // The chevron is an `Icon` with a null contentDescription, so it is
        // invisible to text/description matchers — the assertion has to be
        // GEOMETRIC. Everything the row draws inside its trailing area is
        // tagged (count, recency); a chevron is an extra ~18dp of ink to the
        // right of the last tagged node, inside the row.
        val row = composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .fetchSemanticsNode().boundsInRoot
        val activity = composeRule
            .onNodeWithTag(workspaceGlanceActivityTag(BUSY_PATH), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        val tail = (row.right - activity.right) / density
        assertTrue(
            "the recency label must be the LAST thing in the row — a chevron " +
                "would put ~18dp of glyph plus its gap after it; measured ${tail}dp",
            tail <= 22f,
        )

        // Structural: the row's merged text is exactly name + count + recency.
        composeRule.onNodeWithTag(workspaceRowTag(BUSY_PATH))
            .assertTextEquals("pocketshell", "4", "just now")
    }

    /**
     * #2635 D2: a short workspace list does not pay 68dp of permanent search
     * chrome. Two workspaces is well under the threshold, so the field is a
     * header icon that expands it in place.
     */
    @Test
    fun `search is a header icon until the list is long`() {
        setContent()

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
    }

    /** Past the threshold the field is permanent and the icon disappears. */
    @Test
    fun `a long workspace list keeps the search field permanently`() {
        setContent(workspaceCount = WORKSPACE_SEARCH_THRESHOLD + 1)

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).assertDoesNotExist()
    }

    /**
     * #2635 T2: the header's steady state is a DOT, not the word "Connected"
     * spending a whole subtitle line on every screen forever.
     */
    @Test
    fun `the header shows a status dot instead of the word Connected`() {
        setContent()

        composeRule.onNodeWithText("Connected").assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_WORKSPACES_STATUS_DOT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
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

    private fun setContent(workspaceCount: Int = 0) {
        val extras = (1..workspaceCount).map { n ->
            WorkspaceProjection(
                path = "/home/alexey/git/w$n",
                label = "w$n",
                displayPath = "~/git/w$n",
                sessions = emptyList(),
                durable = true,
            )
        }
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
                                ) + extras,
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
