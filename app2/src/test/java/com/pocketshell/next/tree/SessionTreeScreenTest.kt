package com.pocketshell.next.tree

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.AgentStateSource
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.BackendError
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered session tree on the host JVM (Robolectric), the same way
 * `:shared:ui-kit` tests its primitives.
 *
 * Journey J02 proves this screen works against a real host on a real device;
 * this suite pins the rendering rules that would otherwise only be caught by
 * looking at it: which banner appears for which state, that "empty" and
 * "broken" do not render the same, that an unknown manager still gets a row,
 * and that a tap carries the session's own name.
 *
 * Every assertion is on the RENDERED tree (D29). [nowSec] is pinned so the
 * relative-activity labels are assertable rather than time-dependent.
 */
@RunWith(AndroidJUnit4::class)
class SessionTreeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sections render in group order with their sessions underneath`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("claude-main", "/home/a/git/pocketshell", activity = NOW - 120),
                    row("codex", "/home/a/git/pocketshell", activity = NOW - 4_000),
                    row("aplexer-follow:yolo", "/home/a/git/aplexer", activity = NOW - 30),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceHeaderTag("/home/a/git/aplexer")).assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceHeaderTag("/home/a/git/pocketshell")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("codex")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("aplexer-follow:yolo")).assertIsDisplayed()

        // Header counts the whole listing.
        composeRule.onNodeWithText("3 sessions · 2 workspaces").assertIsDisplayed()
    }

    @Test
    fun `a workspace-less session renders under the other heading`() {
        setContent(
            state(loaded = true, sessions = listOf(row("homeless", workspace = null, activity = NOW))),
        )

        composeRule.onNodeWithTag(workspaceHeaderTag(OTHER_WORKSPACE_LABEL)).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("homeless")).assertIsDisplayed()
    }

    @Test
    fun `a row shows its manager, tag and relative activity`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row(
                        "aplexer-follow:yolo",
                        "/w",
                        activity = NOW - 7_200,
                        backend = Backend.APLEXER,
                        tag = "yolo",
                        engine = "codex",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("aplexer · yolo · 2h ago").assertIsDisplayed()
        // The engine, not the manager, owns the badge when the host named one.
        composeRule.onNodeWithContentDescription("codex").assertIsDisplayed()
    }

    @Test
    fun `an unknown manager row is rendered and labelled, never hidden`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("from-the-future", "/w", activity = NOW, backend = Backend.UNKNOWN),
                ),
            ),
        )

        composeRule.onNodeWithTag(sessionRowTag("from-the-future")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("unknown manager").assertIsDisplayed()
    }

    @Test
    fun `an agent state renders a chip and no state renders nothing`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row(
                        "waiting-agent",
                        "/w",
                        activity = NOW,
                        agentState = AgentState.WAITING,
                        agentStateSource = AgentStateSource.REPORTED,
                    ),
                    row("plain-shell", "/w", activity = NOW - 1),
                ),
            ),
        )

        // The chip is an icon with an explicit state description; the shell row
        // must contribute no second one.
        composeRule.onNodeWithContentDescription("Waiting for input").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Idle (finished)").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Working").assertDoesNotExist()
    }

    @Test
    fun `a failing backend raises the partial banner naming that manager`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(row("claude-main", "/w", activity = NOW)),
                errors = listOf(BackendError("aplexer", "a --json snapshot failed: exit 127")),
            ),
        )

        composeRule.onNodeWithTag(SESSION_TREE_PARTIAL_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Some sessions may be missing: aplexer").assertIsDisplayed()
        // The sessions that DID arrive are still listed.
        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `an empty healthy host says so, and raises no banner at all`() {
        setContent(state(loaded = true, sessions = emptyList()))

        composeRule.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No sessions").assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_PARTIAL_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun `a hard failure shows the error banner with a retry, not the empty state`() {
        var retries = 0
        setContent(
            state(failure = "`pocketshell sessions list --json` failed on the host (exit 127)"),
            onRefresh = { retries += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(SESSION_TREE_ERROR_RETRY_TAG).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the first load shows a loading state rather than a blank screen`() {
        setContent(SessionTreeUiState(hostId = 7, loading = true))

        composeRule.onNodeWithTag(SESSION_TREE_LOADING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun `tapping a row opens that session by its own name`() {
        val opened = mutableListOf<String>()
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("first", "/w", activity = NOW),
                    row("my project:review", "/w", activity = NOW - 10),
                ),
            ),
            onOpenSession = { opened += it },
        )

        composeRule.onNodeWithTag(sessionRowTag("my project:review")).performClick()

        // The SECOND row, and its name verbatim — a captured-index bug would
        // open "first" instead.
        assertEquals(listOf("my project:review"), opened)
    }

    private fun setContent(
        state: SessionTreeUiState,
        onRefresh: () -> Unit = {},
        onOpenSession: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionTreeScreen(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenSession = onOpenSession,
                    nowSec = NOW,
                )
            }
        }
        // Also the guard that the screen can go IDLE: an indeterminate spinner
        // left running would hang every `assertIsDisplayed()` below.
        composeRule.waitForIdle()
    }

    private fun state(
        loaded: Boolean = false,
        sessions: List<SessionRow> = emptyList(),
        errors: List<BackendError> = emptyList(),
        failure: String? = null,
    ) = SessionTreeUiState(
        hostId = 7,
        loaded = loaded,
        groups = groupSessionsByWorkspace(sessions),
        errors = errors,
        failure = failure,
    )

    private fun row(
        name: String,
        workspace: String?,
        activity: Long?,
        backend: Backend = Backend.TMUX,
        tag: String? = null,
        engine: String? = null,
        attached: Boolean = false,
        agentState: AgentState? = null,
        agentStateSource: AgentStateSource? = null,
    ) = SessionRow(
        name = name,
        backend = backend,
        id = null,
        workspace = workspace,
        tag = tag,
        engine = engine,
        profile = null,
        agentState = agentState,
        agentStateSource = agentStateSource,
        attached = attached,
        createdEpoch = null,
        activityEpoch = activity,
    )

    private companion object {
        /** Pinned clock, so relative-time labels are assertable. */
        const val NOW: Long = 1_788_409_253
    }
}
