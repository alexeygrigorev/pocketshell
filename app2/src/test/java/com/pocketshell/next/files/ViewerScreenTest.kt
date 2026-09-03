package com.pocketshell.next.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered file viewer on the host JVM (Robolectric).
 *
 * The load-bearing assertion in here is the Markdown one: a viewer that shows
 * `# Release notes` instead of a heading is the single most visible way this
 * screen can regress, and it is invisible to a ViewModel test — the state is
 * identical in both cases, only the rendering differs.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a text file renders its content`() {
        setContent(state(loaded = true, content = ViewerContent.Text("first line\nsecond line")))

        composeRule.onNodeWithTag(VIEWER_TEXT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("first line\nsecond line").assertIsDisplayed()
    }

    @Test
    fun `markdown renders formatted output, not raw syntax`() {
        setContent(
            state(
                path = "/w/README.md",
                loaded = true,
                markdownCapable = true,
                renderMarkdown = true,
                content = ViewerContent.Text("# Release notes\n\nSome **bold** prose.\n"),
            ),
        )

        composeRule.onNodeWithTag(MARKDOWN_VIEW_TAG).assertIsDisplayed()
        // The heading text is on screen WITHOUT its `#` marker — that is the
        // difference between rendering and dumping source.
        composeRule.onNodeWithText("Release notes").assertIsDisplayed()
        composeRule.onNodeWithText("# Release notes").assertDoesNotExist()
        composeRule.onNodeWithTag(VIEWER_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the source toggle shows the raw markdown instead`() {
        setContent(
            state(
                path = "/w/README.md",
                loaded = true,
                markdownCapable = true,
                renderMarkdown = false,
                content = ViewerContent.Text("# Release notes\n"),
            ),
        )

        composeRule.onNodeWithTag(VIEWER_TEXT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("# Release notes\n").assertIsDisplayed()
        composeRule.onNodeWithTag(MARKDOWN_VIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `a plain text file offers no markdown toggle`() {
        setContent(state(loaded = true, content = ViewerContent.Text("plain")))

        composeRule.onNodeWithTag(VIEWER_MARKDOWN_TOGGLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `an undecodable file falls back to a hex dump with an explanation`() {
        val bytes = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x41, 0x42)
        setContent(state(loaded = true, kind = FileKind.BINARY, content = ViewerContent.Binary(bytes)))

        composeRule.onNodeWithTag(VIEWER_BINARY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(VIEWER_BINARY_NOTE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(VIEWER_TEXT_TAG).assertDoesNotExist()
        // Editing an undecodable blob as text would corrupt it.
        composeRule.onNodeWithTag(VIEWER_EDIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the editor replaces the reader and reports every keystroke`() {
        val drafts = mutableListOf<String>()
        setContent(
            state(loaded = true, editing = true, draft = "before", content = ViewerContent.Text("before")),
            onDraftChange = { drafts += it },
        )

        composeRule.onNodeWithTag(VIEWER_EDITOR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(VIEWER_TEXT_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(VIEWER_EDITOR_TAG).performTextReplacement("after")

        assertEquals(listOf("after"), drafts)
    }

    @Test
    fun `editing swaps the header actions for Save and Cancel`() {
        var saved = 0
        var cancelled = 0
        setContent(
            state(loaded = true, editing = true, draft = "x", content = ViewerContent.Text("x")),
            onSave = { saved += 1 },
            onCancelEdit = { cancelled += 1 },
        )

        composeRule.onNodeWithTag(VIEWER_EDIT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(VIEWER_SAVE_TAG).performClick()
        composeRule.onNodeWithTag(VIEWER_CANCEL_TAG).performClick()

        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `a save in flight disables both actions so the write cannot be double-submitted`() {
        setContent(
            state(loaded = true, editing = true, saving = true, draft = "x", content = ViewerContent.Text("x")),
        )

        composeRule.onNodeWithTag(VIEWER_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(VIEWER_CANCEL_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Saving…").assertIsDisplayed()
    }

    @Test
    fun `a failure is shown as a banner rather than as an empty page`() {
        setContent(state(failure = "Could not open /w/a.txt: no such file"))

        composeRule.onNodeWithTag(VIEWER_ERROR_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Could not open /w/a.txt: no such file").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't open this file").assertIsDisplayed()
    }

    @Test
    fun `a successful save is confirmed and the confirmation can be dismissed`() {
        var dismissed = 0
        setContent(
            state(loaded = true, content = ViewerContent.Text("x"), savedMessage = "Saved a.txt"),
            onDismissSaved = { dismissed += 1 },
        )

        composeRule.onNodeWithTag(VIEWER_SAVED_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Saved a.txt").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun `the first read shows an opening state`() {
        setContent(state(loading = true))

        composeRule.onNodeWithTag(VIEWER_LOADING_TAG).assertIsDisplayed()
    }

    // --- helpers ----------------------------------------------------------

    private fun setContent(
        state: ViewerUiState,
        onDraftChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelEdit: () -> Unit = {},
        onDismissSaved: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                ViewerScreen(
                    state = state,
                    onBack = {},
                    onEdit = {},
                    onDraftChange = onDraftChange,
                    onSave = onSave,
                    onCancelEdit = onCancelEdit,
                    onToggleMarkdown = {},
                    onDismissSaved = onDismissSaved,
                )
            }
        }
    }

    private fun state(
        path: String = "/w/a.txt",
        loading: Boolean = false,
        loaded: Boolean = false,
        kind: FileKind = FileKind.TEXT,
        content: ViewerContent = ViewerContent.Empty,
        markdownCapable: Boolean = false,
        renderMarkdown: Boolean = false,
        editing: Boolean = false,
        draft: String = "",
        saving: Boolean = false,
        savedMessage: String? = null,
        failure: String? = null,
    ) = ViewerUiState(
        hostId = 1,
        path = path,
        loading = loading,
        loaded = loaded,
        kind = kind,
        content = content,
        markdownCapable = markdownCapable,
        renderMarkdown = renderMarkdown,
        editing = editing,
        draft = draft,
        saving = saving,
        savedMessage = savedMessage,
        failure = failure,
    )
}
