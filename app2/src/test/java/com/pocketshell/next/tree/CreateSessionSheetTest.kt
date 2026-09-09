package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the create-session sheet PAINTS and what its buttons carry (task U-6,
 * issue #2522).
 *
 * Journey J04 proves the sheet creates a real session on a real host; this
 * suite pins the rules a device journey would only catch by accident: that a
 * blank name cannot be submitted at all, that Start carries the form's own
 * values (name AND `--cwd`, plus `--engine`/`--profile` when selected), that
 * closing creates nothing, that a failed create leaves the sheet standing with
 * the host's words on it instead of closing and losing the user's text, and
 * that disabled/unavailable providers remain gated by host capability data.
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
    fun `the sheet renders direct programs, more options and a provider footer`() {
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(
                    testEngine("claude"),
                    testEngine("codex"),
                    testEngine("opencode"),
                    testEngine("grok"),
                ),
            ),
            defaultFolder = "/home/a/git/pocketshell",
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(CREATE_SESSION_TITLE).assertIsDisplayed()
        listOf("Shell", "Claude Code", "Codex", "OpenCode", "Grok").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("Agent").assertDoesNotExist()
        composeRule.onNodeWithText("Terminal").assertDoesNotExist()
        composeRule.onNodeWithText("Start Claude Code").assertIsDisplayed()
        composeRule.onNodeWithText("Create").assertDoesNotExist()
        composeRule.onNodeWithText("More options").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_NAME_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Apply options").assertIsDisplayed()

        // The folder prefill is on screen, and so is the name derived from it —
        // the user can create with one tap in the common case.
        composeRule.onNodeWithText("/home/a/git/pocketshell").assertIsDisplayed()
        composeRule.onNodeWithText("pocketshell").assertIsDisplayed()

        // Nothing has failed, so no error banner.
        composeRule.onNodeWithTag(CREATE_SESSION_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun `a blank name cannot be submitted`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        // No folder to derive from, so the name field starts empty.
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "",
            onSubmit = { submitted += it },
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals("a disabled Start must not reach the host", emptyList<Any>(), submitted)
    }

    @Test
    fun `Start carries the forms own name and folder`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        val form = CreateSessionFormState("/home/a/git/pocketshell")
        form.onFolderChange("/srv/reviews")
        form.onNameChange("review-2")
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { submitted += it },
            form = form,
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsEnabled().performClick()

        assertEquals(
            listOf(CreateSessionRequest(name = "review-2", cwd = "/srv/reviews")),
            submitted,
        )
    }

    /** A blank folder means "no `--cwd`", not an empty one. */
    @Test
    fun `Start sends a null cwd when the folder field is empty`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        val form = CreateSessionFormState("")
        form.onNameChange("demo")
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "",
            onSubmit = { submitted += it },
            form = form,
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals(listOf(CreateSessionRequest(name = "demo", cwd = null)), submitted)
    }

    @Test
    fun `Cancel dismisses without creating anything`() {
        var cancelled = 0
        val submitted = mutableListOf<CreateSessionRequest>()
        setContent(
            CreateSessionState(visible = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { submitted += it },
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
        composeRule.onNodeWithText("More options").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun `a create in flight freezes the sheets actions`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        setContent(
            CreateSessionState(visible = true, submitting = true),
            defaultFolder = "/home/a/git/pocketshell",
            onSubmit = { submitted += it },
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()
        assertEquals("a double tap must not create twice", emptyList<Any>(), submitted)
        assertNull(submitted.firstOrNull())
    }

    @Test
    fun `direct programs show provider labels and authoritative availability`() {
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(
                    testEngine("claude"),
                    testEngine("codex"),
                    testEngine("opencode", enabled = true, available = false, availableForCreate = false),
                    testEngine("grok", enabled = false, available = true, availableForCreate = false),
                ),
            ),
            defaultFolder = "/home/a/git/pocketshell",
        )

        listOf("Shell", "Claude Code", "Codex", "OpenCode", "Grok").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        composeRule.onAllNodesWithText("Not available", substring = true).assertCountEquals(2)
        composeRule.onNodeWithTag(createSessionEngineTag("opencode")).assertIsDisplayed()
        composeRule.onNodeWithTag(createSessionEngineTag("grok")).assertIsDisplayed()
        composeRule.onNodeWithText("Claude Code").assertIsDisplayed()
        composeRule.onNodeWithText("Codex").assertIsDisplayed()
    }

    @Test
    fun `selecting a direct provider changes the footer and request mapping`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(testEngine("claude"), testEngine("codex")),
            ),
            defaultFolder = "/srv/reviews",
            onSubmit = { submitted += it },
        )

        composeRule.onNodeWithText("Start Claude Code").assertIsDisplayed()
        composeRule.onNodeWithTag(createSessionEngineTag("codex"))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Start Codex").assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals(
            listOf(
                CreateSessionRequest(
                    name = "reviews",
                    cwd = "/srv/reviews",
                    engine = "codex",
                ),
            ),
            submitted,
        )
    }

    @Test
    fun `unavailable provider opens the exact recovery title`() {
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(
                    testEngine("claude"),
                    testEngine("grok", enabled = false, available = false, availableForCreate = false),
                ),
            ),
            defaultFolder = "/srv/reviews",
        )

        composeRule.onNodeWithTag(createSessionEngineTag("grok"))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Grok is not available").assertIsDisplayed()
        composeRule.onNodeWithText(
            "PocketShell could not find a usable Grok installation on the host.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Grok unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Start Claude Code").assertDoesNotExist()
    }

    @Test
    fun `Start on an agent carries engine and profile`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        val form = CreateSessionFormState("/srv/reviews")
        form.onKindChange(CreateSessionKind.Agent)
        form.onEngineChange("claude")
        form.onProfileChange("Claude (Z.AI)")
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(testEngine("claude"), testEngine("codex")),
                profiles = listOf(
                    ProfileInfo("Claude", "claude", null, isDefault = true),
                    ProfileInfo("Claude (Z.AI)", "claude", "/home/a/.zlaude", isDefault = false),
                ),
            ),
            defaultFolder = "/srv/reviews",
            onSubmit = { submitted += it },
            form = form,
        )

        composeRule.onNodeWithTag(createSessionEngineTag("claude")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("More options").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CREATE_SESSION_PROFILE_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsEnabled().performClick()

        assertEquals(
            listOf(
                CreateSessionRequest(
                    name = "reviews",
                    cwd = "/srv/reviews",
                    engine = "claude",
                    profile = "Claude (Z.AI)",
                ),
            ),
            submitted,
        )
    }

    @Test
    fun `Start on Shell omits engine after switching from Agent`() {
        val submitted = mutableListOf<CreateSessionRequest>()
        val form = CreateSessionFormState("/srv/demo")
        form.onNameChange("demo")
        form.onKindChange(CreateSessionKind.Agent)
        form.onEngineChange("claude")
        form.onKindChange(CreateSessionKind.Shell)
        setContent(
            CreateSessionState(
                visible = true,
                engines = listOf(testEngine("claude")),
            ),
            defaultFolder = "/srv/demo",
            onSubmit = { submitted += it },
            form = form,
        )

        composeRule.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        assertEquals(
            listOf(CreateSessionRequest(name = "demo", cwd = "/srv/demo")),
            submitted,
        )
    }

    private fun setContent(
        state: CreateSessionState,
        defaultFolder: String,
        onSubmit: (CreateSessionRequest) -> Unit = {},
        onCancel: () -> Unit = {},
        form: CreateSessionFormState? = null,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize()) {
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
        }
        // Also the guard that the sheet can go IDLE: an animation left running
        // would hang every assertion below.
        composeRule.waitForIdle()
    }
}
