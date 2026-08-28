package com.pocketshell.app.proof

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.pocketshell.app.MainActivity
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_SESSION_LABEL_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.agents.AgentKind
import org.junit.Assert.assertTrue

internal typealias MainActivityComposeRule =
    AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

internal fun MainActivityComposeRule.hasTaggedNode(tag: String): Boolean = runCatching {
    onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
}.getOrDefault(false)

// Session-switcher pages are current-first, then picker row order. One
// swipeLeft() only lands on the named peer in a two-session world; the shared
// agents fixture also has leftover sessions (#2173r: B → claude-main, never
// renamed A). Click the named page's production OnClick (beyondViewportPageCount
// = MAX so the card is composed) — same path as TmuxSessionSwitchE2eTest.
internal fun MainActivityComposeRule.clickNamedSessionSwitcherPage(sessionName: String) {
    val taggedSessionPage = hasAnyDescendant(hasText(sessionName)) and
        (1..16)
            .map { page -> hasTestTag("$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$page") }
            .reduce { left, right -> left or right }
    onNode(taggedSessionPage, useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.OnClick)
}

internal fun MainActivityComposeRule.currentLiveSessionName(): String? = runCatching {
    onAllNodesWithTag(TMUX_CONSOLIDATED_SESSION_LABEL_TAG, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config
        ?.getOrNull(SemanticsProperties.Text)
        ?.firstOrNull()
        ?.text
}.getOrNull()

internal fun MainActivityComposeRule.openSessionSwitcher(sessionName: String, timeoutMs: Long) {
    val moreTag = listOf(
        TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
    ).firstOrNull { hasTaggedNode(it) } ?: TMUX_FULL_CHROME_MORE_BUTTON_TAG
    onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
    onNodeWithText("Switch session", useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = timeoutMs) {
        onAllNodesWithText(sessionName, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun MainActivityComposeRule.clickTmuxBack(timeoutMs: Long) {
    val tag = listOf(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, TMUX_FULL_CHROME_BACK_BUTTON_TAG)
        .firstOrNull { hasTaggedNode(it) }
        ?: TMUX_FULL_CHROME_BACK_BUTTON_TAG
    onNodeWithTag(tag, useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = timeoutMs) { !hasTaggedNode(TMUX_SESSION_SCREEN_TAG) }
}

internal fun MainActivityComposeRule.pressSystemBack() {
    activityRule.scenario.onActivity { activity ->
        activity.onBackPressedDispatcher.onBackPressed()
    }
    waitForIdle()
}

internal fun MainActivityComposeRule.renameCurrentSessionThroughUi(
    newName: String,
    timeoutMs: Long,
) {
    val moreTag = listOf(TMUX_COMPACT_CHROME_MORE_BUTTON_TAG, TMUX_FULL_CHROME_MORE_BUTTON_TAG)
        .firstOrNull { hasTaggedNode(it) }
        ?: TMUX_FULL_CHROME_MORE_BUTTON_TAG
    onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
    onNodeWithText("Rename session", useUnmergedTree = true).performClick()
    onNode(hasSetTextAction(), useUnmergedTree = true).performTextClearance()
    onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput(newName)
    onNodeWithTag(TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG, useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = timeoutMs) {
        runCatching {
            onAllNodesWithText(newName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
}

internal fun TmuxSessionViewModel.assertTranscriptAcksBoundToLivePane(
    transcriptAcks: List<RecordedDiagnosticEvent>,
) {
    // The seeded session's first pane is window 0.0, whose tmux pane id is %0
    // only on an empty server. Shared agents leftover sessions (claude-main, …)
    // consume earlier %N values; bind to the healed A pane the VM actually owns,
    // not a hardcoded %0.
    val livePaneId = panes.value.singleOrNull()?.paneId
    assertTrue(
        "healed A must still have exactly one live pane; panes=${panes.value.map { it.paneId }}",
        livePaneId != null,
    )
    assertTrue(
        "transcript events must stay on the exact pane/Claude source binding; " +
            "livePane=$livePaneId events=$transcriptAcks",
        livePaneId != null && transcriptAcks.all {
            it.fields["pane"] == livePaneId &&
                it.fields["agent"] == AgentKind.ClaudeCode.name
        },
    )
}
