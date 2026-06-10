package com.pocketshell.app.projects

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.waitForComposeLayoutStable
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.app.sessions.START_DIRECTORY_AUTOCOMPLETE_SUGGESTIONS_TAG
import com.pocketshell.app.sessions.StartDirectoryAutocompleteController
import com.pocketshell.app.sessions.startDirectoryAutocompleteSuggestionTag
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * UI proof for issue #428: the session-create picker shows a default-ON
 * "Skip permissions" checkbox in the Agent section, and hides it for
 * OpenCode (whose per-action permissions are config-driven, not a CLI
 * flag).
 *
 * Drives [SessionTypePickerContent] directly (no SSH / Compose sheet
 * animation) and captures the rendered picker so the reviewer can see
 * the checkbox.
 */
@RunWith(AndroidJUnit4::class)
class SessionTypePickerSkipPermissionsUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun skipPermissionsCheckboxIsCheckedByDefaultAndHiddenForOpenCode() {
        var lastChoice: SessionTypeChoice? = null
        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/home/alexey/git/pocketshell",
                    folderLabel = "pocketshell",
                    onCancel = {},
                    onCreate = { lastChoice = it },
                )
            }
        }

        // Agent is the default session type, Claude the default CLI, so the
        // skip-permissions row is visible and the box is checked.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Skip permissions").assertIsDisplayed()
        captureScreenshot("issue428-picker-claude-skip-on")

        // Confirm with defaults -> skipPermissions true -> claude flag.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        assertTrue(lastChoice?.skipPermissions == true)
        assertTrue(lastChoice?.agent == AgentCli.Claude)
        // Claude launches env-stripped (issue #627): `env -u … claude …`.
        val skipOnCommand = lastChoice?.startCommand()
        assertTrue(skipOnCommand?.startsWith("env -u ") == true)
        assertTrue(skipOnCommand?.endsWith(" claude --dangerously-skip-permissions") == true)

        // Switch to OpenCode -> the checkbox is hidden (no-op for OpenCode).
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG).assertDoesNotExist()
        captureScreenshot("issue428-picker-opencode-no-checkbox")
    }

    @Test
    fun uncheckingSkipPermissionsProducesBareCommand() {
        var lastChoice: SessionTypeChoice? = null
        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/srv/app",
                    folderLabel = "app",
                    onCancel = {},
                    onCreate = { lastChoice = it },
                )
            }
        }

        // Untick -> bare claude.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        assertTrue(lastChoice?.skipPermissions == false)
        // Claude launches env-stripped (issue #627): `env -u … claude` (no flag).
        val bareCommand = lastChoice?.startCommand()
        assertTrue(bareCommand?.startsWith("env -u ") == true)
        assertTrue(bareCommand?.endsWith(" claude") == true)
    }

    @Test
    fun agentCliChoicesUseSingleAlignedSegmentedRow() {
        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/srv/app",
                    folderLabel = "app",
                    onCancel = {},
                    onCreate = {},
                )
            }
        }

        val claude = compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG)
            .fetchSemanticsNode().boundsInRoot
        val codex = compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CODEX_TAG)
            .fetchSemanticsNode().boundsInRoot
        val opencode = compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG)
            .fetchSemanticsNode().boundsInRoot

        assertTrue("codex should sit to the right of claude", codex.left > claude.left)
        assertTrue("opencode should sit to the right of codex", opencode.left > codex.left)
        assertTrue("CLI segments should share one row", abs(claude.top - codex.top) < 1f)
        assertTrue("CLI segments should share one row", abs(codex.top - opencode.top) < 1f)
        assertTrue("CLI segments should have matching heights", abs(claude.height - codex.height) < 1f)
        assertTrue("CLI segments should have matching heights", abs(codex.height - opencode.height) < 1f)
    }

    @Test
    fun actionRowStaysPinnedBelowScrollablePickerContent() {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(640.dp),
                ) {
                    SessionTypePickerContent(
                        folderPath = "/home/alexey/git/ai-shipping-labs-workshops-raw",
                        folderLabel = "ai-shipping-labs-workshops-raw",
                        onCancel = {},
                        onCreate = {},
                    )
                }
            }
        }

        val contentBounds = compose.onNodeWithTag(SESSION_TYPE_PICKER_CONTENT_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val createBounds = compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val maxFooterInsetPx = with(compose.density) { 32.dp.toPx() }

        assertTrue(
            "Create action should stay pinned to the bottom action row",
            contentBounds.bottom - createBounds.bottom <= maxFooterInsetPx,
        )
    }

    @Test
    fun constrainedPickerKeepsCreateVisibleWhileFolderSuggestionsAreOpen() {
        compose.setContent {
            PocketShellTheme {
                val scope = rememberCoroutineScope()
                val autocomplete = remember {
                    StartDirectoryAutocompleteController(
                        scope = scope,
                        suggest = {
                            listOf(
                                "/srv/app",
                                "/srv/api",
                                "/srv/app-web",
                            )
                        },
                        debounceMs = 0,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(460.dp)
                        .testTag(ISSUE_613_PICKER_ROOT_TAG),
                ) {
                    SessionTypePickerContent(
                        folderPath = "/srv",
                        folderLabel = "srv",
                        onCancel = {},
                        onCreate = {},
                        autocompleteController = autocomplete,
                    )
                }
            }
        }

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG)
            .performTextInput("/a")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(START_DIRECTORY_AUTOCOMPLETE_SUGGESTIONS_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG)
            .assertIsEnabled()

        val rootBounds = compose.onNodeWithTag(ISSUE_613_PICKER_ROOT_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val createBounds = compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "Create must stay inside the constrained sheet while folder suggestions are open",
            createBounds.bottom <= rootBounds.bottom + 0.5f,
        )
    }

    @Test
    fun folderSuggestionStaysAboveImeAndCanBeSelected() {
        var lastChoice: SessionTypeChoice? = null
        val firstSuggestion = "/srv/app"
        compose.setContent {
            PocketShellTheme {
                val scope = rememberCoroutineScope()
                val autocomplete = remember {
                    StartDirectoryAutocompleteController(
                        scope = scope,
                        suggest = {
                            listOf(
                                firstSuggestion,
                                "/srv/api",
                                "/srv/app-web",
                            )
                        },
                        debounceMs = 0,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ISSUE_613_PICKER_ROOT_TAG),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    SessionTypePickerContent(
                        folderPath = "/srv",
                        folderLabel = "srv",
                        onCancel = {},
                        onCreate = { lastChoice = it },
                        autocompleteController = autocomplete,
                    )
                }
            }
        }

        try {
            compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG).performClick()
            compose.activity.runOnUiThread {
                val window = compose.activity.window
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }

            val imeVisible = waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = true,
                timeoutMs = 30_000L,
            )
            assertTrue(
                "IME must be visible for the folder-suggestion keyboard regression test",
                imeVisible,
            )

            compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG)
                .performTextInput("/a")
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(START_DIRECTORY_AUTOCOMPLETE_SUGGESTIONS_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            val suggestionTag = startDirectoryAutocompleteSuggestionTag(firstSuggestion)
            assertTrue(
                "suggestion row should settle before IME visibility is checked",
                waitForComposeLayoutStable(compose, suggestionTag),
            )
            compose.onNodeWithTag(suggestionTag).assertIsDisplayed()

            val imeBottomInset = imeBottomInsetPx()
            assertTrue(
                "IME must report a positive bottom inset for the folder-suggestion overlap check",
                imeBottomInset > 0,
            )
            val rootBounds = compose.onNodeWithTag(ISSUE_613_PICKER_ROOT_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
            val suggestionBounds = compose.onNodeWithTag(suggestionTag)
                .fetchSemanticsNode()
                .boundsInRoot
            val keyboardTop = rootBounds.bottom - imeBottomInset
            assertTrue(
                "folder suggestion should stay above the IME " +
                    "(suggestion bottom=${suggestionBounds.bottom}, keyboard top=$keyboardTop)",
                suggestionBounds.bottom <= keyboardTop + 0.5f,
            )

            compose.onNodeWithTag(suggestionTag).performClick()
            compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
            compose.waitForIdle()
            assertTrue(lastChoice?.startDirectory == firstSuggestion)
        } finally {
            compose.activity.runOnUiThread {
                WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    // Screenshot capture is best-effort evidence: a content-only Compose
    // test has no real Activity window, so PixelCopy-backed
    // `captureToImage()` can fail on some emulator images. The load-bearing
    // proof is the semantics assertions above; a capture failure must not
    // fail the test.
    private fun captureScreenshot(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null),
                "issue428-ui",
            ).apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun imeBottomInsetPx(): Int {
        var bottom = 0
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            bottom = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
        return bottom
    }

    private companion object {
        const val ISSUE_613_PICKER_ROOT_TAG = "issue613:session-type-picker-root"
    }
}
