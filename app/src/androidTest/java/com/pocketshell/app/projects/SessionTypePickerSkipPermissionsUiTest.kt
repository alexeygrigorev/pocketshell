package com.pocketshell.app.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
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
        assertTrue(
            lastChoice?.startCommand() == "claude --dangerously-skip-permissions",
        )

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
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
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
        assertTrue(lastChoice?.startCommand() == "claude")
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
}
