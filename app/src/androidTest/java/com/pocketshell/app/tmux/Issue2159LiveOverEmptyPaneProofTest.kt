package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2159 — the pixel-level half of "Live and an empty pane cannot coexist".
 *
 * The maintainer photographed a green `Conversation: Live` sitting directly above
 * "No conversation events yet." while the SAME session's Terminal showed a live
 * Codex agent mid-turn. `Live` claims the feed is healthy and current; over an
 * empty pane that claim is simply false, and it is the whole difference between
 * "nothing is wrong, this agent just hasn't said anything" and "we are bound to
 * nothing you can see".
 *
 * The ViewModel now stamps an honest status
 * (`Issue2159LiveOverEmptyConversationTest` covers those state transitions), but
 * a status is written by many call sites and this pane renders whatever it is
 * handed. So the status row derives its display from `(status, hasEvents)` — and
 * THIS test renders the production [TmuxConversationPane] with the exact
 * contradiction (`syncStatus = Live`, zero events) and asserts the user is never
 * shown the green claim.
 *
 * Mutation that must redden it: drop the `hasEvents = events.isNotEmpty()`
 * argument at [TmuxConversationPane]'s `ConversationSyncStatusRow` call (or make
 * `resolvedConversationSyncStatus` return its input unchanged) — the row goes
 * back to "Conversation: Live" over an empty feed and the first case fails.
 */
@RunWith(AndroidJUnit4::class)
class Issue2159LiveOverEmptyPaneProofTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyConversationNeverRendersTheGreenLiveClaim() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    // The exact state the ViewModel used to hand this pane.
                    syncStatus = AgentConversationSyncStatus.Live,
                )
            }
        }
        compose.waitForIdle()

        // The reported screen: the empty-feed message IS shown...
        compose.onNodeWithText("No conversation events yet.").assertIsDisplayed()
        // ...so the status above it must NOT claim the feed is Live.
        assertEquals(
            "#2159: `Conversation: Live` must never render above " +
                "\"No conversation events yet.\" — that is the maintainer's " +
                "reported screen.",
            0,
            compose.onAllNodesWithText("Conversation: Live").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Conversation: No messages yet").assertIsDisplayed()
    }

    @Test
    fun aPopulatedConversationStillRendersLive() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.Message(
                            id = "m1",
                            agent = AgentKind.Codex,
                            role = ConversationRole.Assistant,
                            text = "working on it",
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                    syncStatus = AgentConversationSyncStatus.Live,
                )
            }
        }
        compose.waitForIdle()

        // Selectivity: the fix must not swallow the genuine Live state — a feed
        // with content still reports Live, so the mutation above reddens ONLY
        // the empty case.
        compose.onNodeWithText("Conversation: Live").assertIsDisplayed()
    }
}
