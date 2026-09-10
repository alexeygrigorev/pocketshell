package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.uikit.components.SESSION_TAB_NEW_TAG
import com.pocketshell.uikit.components.SESSION_TAB_OVERFLOW_TAG
import com.pocketshell.uikit.components.SESSION_TAB_STRIP_TAG
import com.pocketshell.uikit.components.SESSION_TAB_LABEL_STYLE
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.components.sessionTabTag
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632, acceptance criterion 3: "some tab-like switcher exists for a
 * session with multiple views, reachable in fewer taps than today".
 *
 * "Fewer taps" is the assertion, not "a strip exists": before this, switching
 * cost a tap on the context row plus a tap on a sheet row. The test that
 * proves the fix is the one that opens the sibling session with a SINGLE tap
 * and no sheet in between.
 */
@RunWith(AndroidJUnit4::class)
class SessionTabStripTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `one tap on a sibling tab opens that session`() {
        val opened = mutableListOf<String>()
        setContent(onOpenSession = { opened += it.name })

        composeRule.onNodeWithTag(SESSION_TAB_STRIP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionTabTag(OTHER)).performClick()

        assertEquals(listOf(OTHER), opened)
    }

    @Test
    fun `tapping the session already open does not renavigate`() {
        val opened = mutableListOf<String>()
        setContent(onOpenSession = { opened += it.name })

        composeRule.onNodeWithTag(sessionTabTag(CURRENT)).performClick()

        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `the strip carries the new-session action`() {
        var newSessions = 0
        setContent(onOpenNewSession = { newSessions += 1 })

        composeRule.onNodeWithTag(SESSION_TAB_NEW_TAG).performClick()

        assertEquals(1, newSessions)
    }

    /**
     * The full switcher sheet is NOT deleted by the strip — it still owns the
     * per-session status text and Stop — so the overflow has to reach it.
     */
    @Test
    fun `the overflow still opens the full switcher sheet`() {
        setContent()

        composeRule.onNodeWithTag(SESSION_TAB_OVERFLOW_TAG).performClick()

        composeRule.onNodeWithTag(SESSION_SWITCHER_SHEET_TAG).assertIsDisplayed()
    }

    /**
     * A listing that has not arrived (or failed) must not take the tab bar
     * with it: the session the user is looking at is always a tab.
     */
    @Test
    fun `the open session is a tab even before the listing arrives`() {
        setContent(sessions = emptyList())

        composeRule.onNodeWithTag(sessionTabTag(CURRENT)).assertIsDisplayed()
    }

    @Test
    fun `an unlisted open session is prepended, not duplicated`() {
        val tabs = sessionTabs(
            sessions = listOf(row(OTHER)),
            currentSessionName = CURRENT,
            currentSessionLabel = "main",
        )

        assertEquals(listOf(CURRENT, OTHER), tabs.map { it.id })
        assertEquals(1, tabs.count { it.id == CURRENT })
    }

    @Test
    fun `tab labels use the shared de-duplicating session projection`() {
        val tabs = sessionTabs(
            sessions = listOf(row("a:main"), row("b:main")),
            currentSessionName = "a:main",
            currentSessionLabel = "main",
        )

        // Two sessions whose readable leaf is the same must stay tellable
        // apart, exactly as they are in the switcher sheet.
        assertEquals(listOf("main", "main 2"), tabs.map { it.label })
    }

    /**
     * #2635 D3 / `ux-rules.md` rule 6: a strip with ONE item is vertical chrome
     * without a choice. Session chrome measured ~125dp above the first terminal
     * row against the desktop's 40px; the strip is 49dp of that, and with a
     * single session it offers nothing to switch to.
     *
     * Its "+" does not disappear with it — it moves into the header — so the
     * create affordance stays exactly one tap away. Fails on the
     * unconditionally-rendered strip.
     */
    @Test
    fun `a live session with no siblings hides the strip and keeps a header plus`() {
        setContent(
            sessions = listOf(row(CURRENT, AgentState.WORKING)),
            state = SessionUiState.Live(createRemoteTerminalSession()),
        )

        composeRule.onNodeWithTag(SESSION_TAB_STRIP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TAB_NEW_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_HEADER_NEW_TAG).assertIsDisplayed()
    }

    /** Two sessions is a choice, so the strip earns its row again. */
    @Test
    fun `a second session brings the strip back and the header plus goes away`() {
        setContent(
            sessions = listOf(row(CURRENT, AgentState.WORKING), row(OTHER)),
            state = SessionUiState.Live(createRemoteTerminalSession()),
        )

        composeRule.onNodeWithTag(SESSION_TAB_STRIP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TAB_NEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HEADER_NEW_TAG).assertDoesNotExist()
    }

    /**
     * #2635 D3: the tab label is the app's PRIMARY switching control. It was
     * `PocketShellType.metadata`, which was 16sp when #2632 designed the strip
     * and became 11sp once #2630's reconciled scale merged — below the
     * desktop's 13px tabs and at Material's caption floor.
     *
     * The assertion is on the STYLE the strip paints with, not on the rendered
     * node: Robolectric's text metrics are degenerate (a 6dp box for every
     * label at every size), so a measured-geometry oracle here would be a
     * vacuous pass. This reddens if the label is re-pointed at the caption
     * rung, and it also reddens if `bodyDense` is ever shrunk onto it.
     */
    @Test
    fun `tab labels are not on the caption rung`() {
        assertEquals(
            "the tab label must be the dense body rung",
            PocketShellType.bodyDense.fontSize,
            SESSION_TAB_LABEL_STYLE.fontSize,
        )
        assertTrue(
            "a primary switching control must not sit on the caption rung " +
                "(${SESSION_TAB_LABEL_STYLE.fontSize} vs caption " +
                "${PocketShellType.metadata.fontSize})",
            SESSION_TAB_LABEL_STYLE.fontSize.value > PocketShellType.metadata.fontSize.value,
        )
        assertTrue(
            "the desktop's tabs are 13px; do not go under that",
            SESSION_TAB_LABEL_STYLE.fontSize.value >= 13f,
        )
    }

    /**
     * #2635 T2 / D3: the header's steady state is the DOT plus a one-line
     * title, not a two-line title over "hetzner · Connected".
     */
    @Test
    fun `the live header drops the Connected subtitle for a dot`() {
        setContent(state = SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithText("hetzner · Connected").assertDoesNotExist()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_STATUS_DOT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    /** A non-steady state still spells itself out — a colour cannot say it. */
    @Test
    fun `a reconnecting header keeps its words`() {
        setContent(
            state = SessionUiState.Reconnecting(
                attempt = 2,
                retryInMs = 5_000,
                terminal = createRemoteTerminalSession(),
            ),
        )

        composeRule.onNodeWithText("hetzner · Reconnecting").assertIsDisplayed()
    }

    @Test
    fun `the dot reports the host's agent state`() {
        assertEquals(SessionTabState.Working, sessionTabState(row("x", AgentState.WORKING)))
        assertEquals(SessionTabState.NeedsInput, sessionTabState(row("x", AgentState.WAITING)))
        assertEquals(SessionTabState.Idle, sessionTabState(row("x", AgentState.IDLE)))
        assertEquals(SessionTabState.Idle, sessionTabState(row("x", null)))
    }

    private fun setContent(
        sessions: List<SessionRow> = listOf(row(CURRENT, AgentState.WORKING), row(OTHER)),
        onOpenSession: (SessionRow) -> Unit = {},
        onOpenNewSession: () -> Unit = {},
        state: SessionUiState = SessionUiState.Connecting,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    composerState = ComposerUiState(),
                    sessionName = CURRENT,
                    onBack = {},
                    onOpenSession = onOpenSession,
                    onOpenNewSession = onOpenNewSession,
                    onResized = { _, _ -> },
                    onRetry = {},
                    onHotkeySend = {},
                    onDraftChange = {},
                    onSend = { true },
                    onInsert = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscardDraft = {},
                    onUseHistoryEntry = {},
                    sessionSwitcherState = SessionSwitcherUiState(
                        sessions = sessions,
                        hostLabel = "hetzner",
                    ),
                )
            }
        }
    }

    private fun row(name: String, agentState: AgentState? = null): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = "/home/testuser/git/pocketshell",
        tag = null,
        engine = "shell",
        profile = null,
        agent = null,
        agentState = agentState,
        agentStateSource = null,
        attached = false,
        createdEpoch = null,
        activityEpoch = null,
    )

    private companion object {
        const val CURRENT = "pocketshell:main"
        const val OTHER = "pocketshell:review"
    }
}
