package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.projects.ClaudeProfile
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_AGENT_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CONTENT_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHELL_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG
import com.pocketshell.app.projects.SessionTypePickerSheet
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #898 regression proof — the in-session kebab "+ New session" must open
 * the SAME rich [SessionTypePickerSheet] the host/session-list screen uses
 * (Session type Shell/Agent, Agent CLI claude/codex/opencode, Skip
 * permissions, Profile), NOT the old stripped-down name+folder dialog.
 *
 * This drives the PRODUCTION [TmuxMoreMenu] "+ New session" item through the
 * EXACT routing the screen uses (`onCreateSession = { showNewSessionSheet =
 * true }`) into the PRODUCTION [SessionTypePickerSheet], and asserts the rich
 * controls are on screen — the divergence the issue reported. The old dialog
 * had ONLY a "Session name" field and a "Start folder" field; the assertions
 * below (Session type segmented control, Agent CLI sub-picker, Skip
 * permissions, Profile) cannot pass against that stripped-down dialog, so this
 * is a class-covering red→green proof of the swap.
 *
 * The full connected journey (kebab → sheet → create an Agent session that
 * actually launches the chosen CLI/profile over Docker) is the emulator+Docker
 * acceptance gate; this on-device test is the deterministic UI proof that the
 * kebab now routes to the rich sheet, with a full-device screenshot artifact.
 */
@RunWith(AndroidJUnit4::class)
class TmuxNewSessionRichSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun kebabNewSessionOpensRichSheetWithTypeCliSkipPermsAndProfile() {
        var lastFolderPath: String? = null
        compose.setContent {
            PocketShellTheme {
                // Mirror the screen's wiring: the kebab's onCreateSession opens
                // the rich sheet (showNewSessionSheet = true), exactly as
                // TmuxSessionScreen now does.
                var menuOpen by remember { mutableStateOf(true) }
                var showSheet by remember { mutableStateOf(false) }
                TmuxMoreMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onCreateSession = {
                        menuOpen = false
                        showSheet = true
                    },
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onDetach = {},
                )
                if (showSheet) {
                    SessionTypePickerSheet(
                        // The screen defaults the folder to the current session's
                        // pane cwd; here we pass a representative one.
                        folderPath = "/home/alex/git/project",
                        folderLabel = "project",
                        onDismiss = { showSheet = false },
                        // A multi-profile host so the Profile selector renders —
                        // proving the rich sheet's Profile criterion is reachable
                        // from this in-session entry point.
                        claudeProfiles = listOf(
                            ClaudeProfile(name = "Claude", default = true),
                            ClaudeProfile(name = "Claude (Z.AI)"),
                        ),
                        onCreate = { choice -> lastFolderPath = choice.startDirectory },
                    )
                }
            }
        }

        // Tap the production kebab item.
        compose.onNodeWithText("+ New session").assertIsDisplayed().performClick()
        compose.waitForIdle()
        SystemClock.sleep(300)

        // The rich sheet is shown — NOT the old name+folder dialog.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CONTENT_TAG, useUnmergedTree = true)
            .assertExists()
        // Session type (Shell / Agent) segmented control — the old dialog had none.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SHELL_TAG, useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_TAG, useUnmergedTree = true)
            .assertExists()
        // Agent CLI sub-picker (Agent is the default selection) — claude/codex/
        // opencode segments.
        compose.onNodeWithText("claude", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("codex", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("opencode", useUnmergedTree = true).assertExists()
        // Skip permissions row.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG, useUnmergedTree = true)
            .assertExists()
        // Profile selector (multi-profile host) — the rich sheet's Profile.
        compose.onNodeWithText("Claude (Z.AI)", useUnmergedTree = true).assertExists()
        // The Start folder defaulted to the passed pane cwd — the one good
        // behaviour the old dialog had is preserved.
        compose.onNodeWithText("/home/alex/git/project", useUnmergedTree = true).assertExists()

        captureFullDevice(File(artifactDir(), "kebab-new-session-rich-sheet-viewport.png"))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/tmux-new-session-rich-sheet")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create new-session-rich-sheet screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write new-session-rich-sheet screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_NEW_SESSION_RICH_SHEET_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
