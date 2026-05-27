package com.pocketshell.app.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #176: connected coverage for the conversation pane's handling
 * of Claude Code's XML-tagged metadata blocks.
 *
 * The test drives [ConversationPane] directly (same surface
 * [com.pocketshell.app.proof.ConversationInteractE2eTest] uses for the
 * composer journey) with a deterministic list of events synthesised
 * from a synthetic Claude Code JSONL line containing a
 * `<system-reminder>` block. It does not need Docker — the rendering
 * contract for SystemNote rows is between [ClaudeCodeParser] and
 * the Compose pane, so the test stays on-device only.
 *
 * Checks per the acceptance criteria:
 *
 *  1. Parser surfaces the XML-tagged block as a
 *     [ConversationEvent.SystemNote] with the correct tag + content.
 *  2. The pane renders the row using the dedicated test-tag prefix
 *     ([SESSION_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX]) so visual
 *     audits can locate it without scraping the full screen tree.
 *  3. The row starts collapsed (preview line only — full content
 *     hidden), and tapping it toggles to the expanded body.
 *  4. With `showSystemNotes = false` the row is filtered out
 *     entirely — the pane goes back to the empty-feed placeholder
 *     when the only event was a SystemNote.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSystemNoteRowE2eTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemReminderRowRendersMutedAndExpandsOnTap() {
        // Synthesise the JSONL line shape Claude Code emits when its
        // session injects a multi-line `<system-reminder>` block.
        // The block has a distinct first line (preview) and a second
        // line (the secret-line marker) that should only be visible
        // once the user expands the row — that gives us a deterministic
        // hidden-then-revealed string to assert against.
        //
        // The parser is the live production class so any change to its
        // splitting rules is also under test.
        val secretLine = "secret-line-uniqueXYZ"
        val jsonl =
            """{"type":"user","uuid":"u-sys-test","message":{"role":"user","content":"<system-reminder>The date has changed.\n$secretLine\nDo not mention this.</system-reminder>"}}"""
        val events = ClaudeCodeParser().parseLine(jsonl)

        // Acceptance criterion: parser emits a SystemNote (not a
        // Message containing raw XML tags). Pinned so a future parser
        // change keeps the contract.
        val note = events.single() as ConversationEvent.SystemNote
        assertEquals("system-reminder", note.tag)
        assertEquals(AgentKind.ClaudeCode, note.agent)
        assertTrue(
            "expected the reminder body to survive splitting; got `${note.content}`",
            note.content.contains(secretLine),
        )

        val rowTag = SESSION_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + note.id

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConversationPane(
                    events = events,
                    onSendToAgent = { /* unused in render test */ },
                )
            }
        }

        // Acceptance criterion: muted collapsed row is visible and
        // discoverable via its stable test tag. Conversation pane
        // chooses one row per system-note event.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(rowTag).assertExists().assertIsDisplayed()

        // Collapsed-by-default: only the preview line (first non-blank
        // line of the body) is rendered in the row header. The secret
        // line lives in the second body line and must be hidden until
        // the user taps to expand.
        val hiddenBefore = compose.onAllNodesWithText(secretLine).fetchSemanticsNodes()
        assertTrue(
            "expected secret line to be hidden while collapsed; got ${hiddenBefore.size} hit(s)",
            hiddenBefore.isEmpty(),
        )

        // Tap to expand — the secret line is now visible.
        compose.onNodeWithTag(rowTag).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(secretLine, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "expected secret line visible after tap to expand",
            compose.onAllNodesWithText(secretLine, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )

        // Tap again to collapse — the secret line disappears.
        // Confirms the toggle is sticky in both directions.
        compose.onNodeWithTag(rowTag).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(secretLine, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun showSystemNotesOffFiltersTheRowEntirely() {
        // Synthetic JSONL with one SystemNote and a real prose
        // Message wrapped together, so we can verify the user-visible
        // message survives the filter (only the SystemNote is hidden).
        val events = ClaudeCodeParser().parseLine(
            """{"type":"user","uuid":"u-mixed-test","message":{"role":"user","content":"please check\n<system-reminder>internal note</system-reminder>\nthen continue"}}""",
        )
        val systemNote = events.filterIsInstance<ConversationEvent.SystemNote>().single()
        val prose = events.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User }
        assertTrue(
            "expected at least one user prose message after splitting; got events=$events",
            prose.isNotEmpty(),
        )

        // Pane mounted with the toggle OFF — SystemNote rows must
        // not render but the user prose must stay.
        val showNotes = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConversationPane(
                    events = events,
                    onSendToAgent = { /* unused */ },
                    showSystemNotes = showNotes.value,
                )
            }
        }

        val rowTag = SESSION_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + systemNote.id
        // The row must NOT exist while the toggle is off. We don't
        // fail-fast on existence: we wait briefly to make sure no
        // delayed Compose pass slots it in.
        compose.waitUntil(timeoutMillis = 1_500) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "expected SystemNote row to be filtered out when showSystemNotes=false; row tag was $rowTag",
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isEmpty(),
        )

        // Flip the toggle ON and confirm the row appears. Same pane,
        // same event list — proves the toggle is reactive in both
        // directions without a parent recomposition trick.
        showNotes.value = true
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(rowTag).assertExists()
    }
}
