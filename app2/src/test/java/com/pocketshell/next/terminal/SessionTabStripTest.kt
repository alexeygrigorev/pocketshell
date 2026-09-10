package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.uikit.components.SESSION_TAB_NEW_TAG
import com.pocketshell.uikit.components.SESSION_TAB_OVERFLOW_TAG
import com.pocketshell.uikit.components.SESSION_TAB_STRIP_TAG
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.components.sessionTabTag
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
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
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionScreen(
                    state = SessionUiState.Connecting,
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
