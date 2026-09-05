package com.pocketshell.next.tree

import androidx.compose.ui.test.assertHasNoClickAction
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
import com.pocketshell.next.usage.USAGE_GLANCE_PILL_TAG
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.model.PillKind
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
    fun `sections render as root then folder then session`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("claude-main", "/home/a/git/pocketshell", activity = NOW - 120, created = 2),
                    row("codex", "/home/a/git/pocketshell", activity = NOW - 4_000, created = 1),
                    row("aplexer-follow:yolo", "/home/a/git/aplexer", activity = NOW - 30, created = 3),
                ),
            ),
        )

        composeRule.onNodeWithTag(rootHeaderTag("~/git")).assertIsDisplayed()
        composeRule.onNodeWithTag(folderHeaderTag("~/git/pocketshell")).assertIsDisplayed()
        composeRule.onNodeWithTag(folderHeaderTag("~/git/aplexer")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("codex")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("aplexer-follow:yolo")).assertIsDisplayed()
        composeRule.onNodeWithText("pocketshell").assertIsDisplayed()
        composeRule.onNodeWithText("aplexer").assertIsDisplayed()

        // Header counts the whole listing. Both folders sit under one root.
        composeRule.onNodeWithText("3 sessions · 1 root").assertIsDisplayed()
    }

    @Test
    fun `a 1-to-1 folder still draws the folder row above the session`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(row("git-aplexer", "/home/a/git/aplexer", activity = NOW, created = 1)),
            ),
        )

        composeRule.onNodeWithTag(rootHeaderTag("~/git")).assertIsDisplayed()
        composeRule.onNodeWithTag(folderHeaderTag("~/git/aplexer")).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("git-aplexer")).assertIsDisplayed()
        composeRule.onNodeWithText("aplexer").assertIsDisplayed()
    }

    @Test
    fun `a workspace-less session renders under the other heading with no folder`() {
        setContent(
            state(loaded = true, sessions = listOf(row("homeless", workspace = null, activity = NOW))),
        )

        composeRule.onNodeWithTag(rootHeaderTag(OTHER_ROOT_KEY)).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("homeless")).assertIsDisplayed()
        composeRule.onNodeWithTag(folderHeaderTag(UNTRACKED_PATH)).assertDoesNotExist()
    }

    @Test
    fun `a row shows relative activity and never engine, tag or manager text`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row(
                        "aplexer-follow:yolo",
                        "/home/a/git/aplexer",
                        activity = NOW - 7_200,
                        backend = Backend.APLEXER,
                        tag = "yolo",
                        engine = "codex",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("2h ago").assertIsDisplayed()
        composeRule.onNodeWithText("aplexer · yolo · 2h ago").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("codex").assertDoesNotExist()
    }

    @Test
    fun `an unknown manager row is rendered, never hidden, and carries no manager badge`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("from-the-future", "/home/a/git/w", activity = NOW, backend = Backend.UNKNOWN),
                ),
            ),
        )

        composeRule.onNodeWithTag(sessionRowTag("from-the-future")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("unknown manager").assertDoesNotExist()
    }

    /**
     * Issue #2530: the tree must not show which engine/agent is running.
     * Attached is the green dot only.
     */
    @Test
    fun `the tree has no agent badge or chip and an attached row keeps its green dot`() {
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row(
                        "waiting-agent",
                        "/home/a/git/w",
                        activity = NOW,
                        engine = "claude",
                        attached = true,
                        agentState = AgentState.WAITING,
                        agentStateSource = AgentStateSource.REPORTED,
                    ),
                    row(
                        "working-codex",
                        "/home/a/git/w",
                        activity = NOW - 1,
                        engine = "codex",
                        agentState = AgentState.WORKING,
                        agentStateSource = AgentStateSource.REPORTED,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Waiting for input").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Idle (finished)").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Working").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("claude").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("codex").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(ATTACHED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionRowTag("waiting-agent")).assertIsDisplayed()
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

    /**
     * Issue #2505: Destination.Ports is registered but nothing in the shipping
     * UI navigates to it. The header action next to Files is the caller; this
     * test is RED until that action exists and fires `onOpenPorts`.
     */
    @Test
    fun `tapping Ports in the header opens port forwarding`() {
        var files = 0
        var ports = 0
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))),
            onOpenFiles = { files += 1 },
            onOpenPorts = { ports += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_FILES_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_PORTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_PORTS_TAG).performClick()

        assertEquals(1, ports)
        assertEquals("Files must stay independent of Ports", 0, files)
    }

    /**
     * Issue #2532: Hosts → Sessions was a one-way door. The tree is a popped
     * screen, so it must carry a visible Back (the word, not a hairline `‹`)
     * that fires `onBack`.
     */
    @Test
    fun `tapping Back in the header fires onBack`() {
        var backs = 0
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))),
            onBack = { backs += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TREE_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    /**
     * Issue #2532: the tree is where the maintainer spends most of the day, and
     * it had Files/Ports but no way to open Usage. The header action is the
     * caller; this test is RED until that action exists and fires `onOpenUsage`.
     */
    @Test
    fun `tapping Usage in the header opens the usage panel`() {
        var usage = 0
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))),
            onOpenUsage = { usage += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_USAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_USAGE_TAG).performClick()

        assertEquals(1, usage)
    }

    @Test
    fun `a glance pill sits beside Usage when a reading exists and also opens the panel`() {
        var usage = 0
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))),
            onOpenUsage = { usage += 1 },
            usagePillState = UsageGlancePillState(
                percent = 72,
                provider = "Codex",
                window = "7d",
                kind = PillKind.Warn,
                stale = false,
                fetchedClock = "13:40",
            ),
        )

        composeRule.onNodeWithTag(SESSION_TREE_USAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).performClick()

        assertEquals(1, usage)
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

    @Test
    fun `root and folder headers are not attach targets`() {
        val opened = mutableListOf<String>()
        setContent(
            state(
                loaded = true,
                sessions = listOf(
                    row("git-aplexer", "/home/a/git/aplexer", activity = NOW, created = 1),
                ),
            ),
            onOpenSession = { opened += it },
        )

        composeRule.onNodeWithTag(rootHeaderTag("~/git")).assertHasNoClickAction()
        composeRule.onNodeWithTag(folderHeaderTag("~/git/aplexer")).assertHasNoClickAction()
        assertEquals(emptyList<String>(), opened)
    }

    // --- create affordance (task U-6) --------------------------------------

    @Test
    fun `the create FAB is on the screen in every state and reports its taps`() {
        var creates = 0
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))),
            onCreateSession = { creates += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_CREATE_FAB_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SESSION_TREE_CREATE_LABEL).assertIsDisplayed()

        composeRule.onNodeWithTag(SESSION_TREE_CREATE_FAB_TAG).performClick()
        assertEquals(1, creates)
    }

    /**
     * An empty host is exactly when a user needs to make a session, so the
     * create affordance must survive the empty state rather than being drawn
     * over the list only.
     */
    @Test
    fun `the create FAB survives the empty and failed states`() {
        setContent(state(loaded = true, sessions = emptyList()))
        composeRule.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_CREATE_FAB_TAG).assertIsDisplayed()
    }

    /**
     * `created:false` — the session was already there. That is a SUCCESS, so
     * the screen says so in an INFO banner, never in the error banner.
     */
    @Test
    fun `an already-existed session renders a notice, not the error banner`() {
        setContent(
            state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW)))
                .copy(
                    create = CreateSessionState(
                        notice = "Session \"claude-main\" already existed — opened it.",
                    ),
                ),
        )

        composeRule.onNodeWithTag(SESSION_TREE_CREATE_NOTICE_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText("Session \"claude-main\" already existed — opened it.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TREE_PARTIAL_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun `no create state renders no notice at all`() {
        setContent(state(loaded = true, sessions = listOf(row("claude-main", "/w", activity = NOW))))

        composeRule.onNodeWithTag(SESSION_TREE_CREATE_NOTICE_TAG).assertDoesNotExist()
    }

    private fun setContent(
        state: SessionTreeUiState,
        onRefresh: () -> Unit = {},
        onOpenSession: (String) -> Unit = {},
        onCreateSession: () -> Unit = {},
        onOpenFiles: () -> Unit = {},
        onOpenPorts: () -> Unit = {},
        onBack: () -> Unit = {},
        onOpenUsage: () -> Unit = {},
        usagePillState: UsageGlancePillState? = null,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionTreeScreen(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenSession = onOpenSession,
                    onCreateSession = onCreateSession,
                    onOpenFiles = onOpenFiles,
                    onOpenPorts = onOpenPorts,
                    onBack = onBack,
                    onOpenUsage = onOpenUsage,
                    usagePillState = usagePillState,
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
        roots = groupSessionsIntoRoots(sessions),
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
        created: Long? = null,
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
        createdEpoch = created,
        activityEpoch = activity,
    )

    private companion object {
        /** Pinned clock, so relative-time labels are assertable. */
        const val NOW: Long = 1_788_409_253
    }
}
