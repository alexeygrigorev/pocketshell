package com.pocketshell.next.tree

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the create-session sheet PAINTS and what its buttons carry (task U-6).
 *
 * Journey J04 proves the sheet creates a real session on a real host; this
 * suite pins the rules a device journey would only catch by accident: that a
 * blank name cannot be submitted at all, that Create carries the form's own
 * values (name AND `--cwd`) rather than the prefill, that Cancel creates
 * nothing, and that a failed create leaves the sheet standing with the host's
 * words on it instead of closing and losing the user's text.
 *
 * The sheet's BODY is composed directly ([CreateSessionSheetContent]) rather
 * than through [CreateSessionSheet]'s `ModalBottomSheet`: the container is
 * Material's, its window/animation machinery is not what this test is about,
 * and a sheet animation never lets Robolectric's clock go idle. The form is
 * handed in pre-filled for the same reason a focused `OutlinedTextField` is
 * avoided here (its cursor animation wedges the idle wait) — real typing is
 * exercised on the emulator by J04.
 */
@RunWith(AndroidJUnit4::class)
class CreateSessionSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the sheet renders both fields, the hint and both actions`() {
        setContent(CreateSessionState(visible = true), defaultFolder = "/home/a/git/pocketshell")

        composeRule.onNodeWithTag(CREATE_SESSION_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(CREATE_SESSION_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(CREATE_SESSION_HINT).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_NAME_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_CANCEL_TAG).assertIsDisplayed()

        // The folder prefill is on screen, and so is the name derived from it —
        // the user can create with one tap in the common case.
        composeRule.onNodeWithText("/home/a/git/pocketshell").assertIsDisplayed()
        composeRule.onNodeWithText("pocketshell").assertIsDisplayed()

        // Nothing has failed, so no error banner.
        composeRule.onNodeWithTag(CREATE_SESSION_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun `a blank name cannot be submitted`() {
        val submitted = mutableListOf<Pair<String, String?>>()
        // No folder to derive from, so the name field starts empty.
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "",
            onSubmit = { name, cwd -> submitted += name to cwd },
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals("a disabled Create must not reach the host", emptyList<Any>(), submitted)
    }

    @Test
    fun `Create carries the forms own name and folder`() {
        val submitted = mutableListOf<Pair<String, String?>>()
        val form = CreateSessionFormState("/home/a/git/pocketshell")
        form.onFolderChange("/srv/reviews")
        form.onNameChange("review-2")
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { name, cwd -> submitted += name to cwd },
            form = form,
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsEnabled().performClick()

        assertEquals(listOf("review-2" to "/srv/reviews"), submitted)
    }

    /** A blank folder means "no `--cwd`", not an empty one. */
    @Test
    fun `Create sends a null cwd when the folder field is empty`() {
        val submitted = mutableListOf<Pair<String, String?>>()
        val form = CreateSessionFormState("")
        form.onNameChange("demo")
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "",
            onSubmit = { name, cwd -> submitted += name to cwd },
            form = form,
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals(listOf<Pair<String, String?>>("demo" to null), submitted)
    }

    @Test
    fun `Cancel dismisses without creating anything`() {
        var cancelled = 0
        val submitted = mutableListOf<Pair<String, String?>>()
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { name, cwd -> submitted += name to cwd },
            onCancel = { cancelled += 1 },
        )

        composeRule.onNodeWithTag(CREATE_SESSION_CANCEL_TAG).performClick()

        assertEquals(1, cancelled)
        assertEquals(emptyList<Any>(), submitted)
    }

    /**
     * A failed create is shown ON the sheet: the fix is usually an edit to the
     * folder, and a sheet that closed would throw the user's text away.
     */
    @Test
    fun `a failed create renders the hosts own words on the still-open sheet`() {
        val failure = "`pocketshell sessions create --json` failed on the host (exit 1): " +
            "no such directory"
        setContent(
            CreateSessionState(visible = true, failure = failure),
            defaultFolder = "/nope",
        )

        composeRule.onNodeWithTag(CREATE_SESSION_ERROR_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(failure).assertIsDisplayed()
        // Still editable and still submittable — this is a retry, not a dead end.
        composeRule.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun `a create in flight freezes the sheets actions`() {
        val submitted = mutableListOf<Pair<String, String?>>()
        setContent(
            CreateSessionState(visible = true, submitting = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { name, cwd -> submitted += name to cwd },
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CREATE_SESSION_CANCEL_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()
        assertEquals("a double tap must not create twice", emptyList<Any>(), submitted)
        assertNull(submitted.firstOrNull())
    }

    private fun setContent(
        state: CreateSessionState,
        defaultFolder: String,
        onSubmit: (String, String?) -> Unit = { _, _ -> },
        onCancel: () -> Unit = {},
        form: CreateSessionFormState? = null,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                if (form == null) {
                    CreateSessionSheetContent(
                        state = state,
                        defaultFolder = defaultFolder,
                        onSubmit = onSubmit,
                        onCancel = onCancel,
                    )
                } else {
                    CreateSessionSheetContent(
                        state = state,
                        defaultFolder = defaultFolder,
                        onSubmit = onSubmit,
                        onCancel = onCancel,
                        form = form,
                    )
                }
            }
        }
        // Also the guard that the sheet can go IDLE: an animation left running
        // would hang every assertion below.
        composeRule.waitForIdle()
    }
}
