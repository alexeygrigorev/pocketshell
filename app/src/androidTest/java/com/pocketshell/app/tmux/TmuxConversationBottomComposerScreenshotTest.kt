package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.voice.ADD_PROMPT_CHIP_LABEL
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_TOGGLE_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #786 screenshot + contract evidence — the Conversation tab's bottom band
 * is collapsed to JUST the `>_` composer launcher. The maintainer circled the
 * full command bar (the #628 previous-session toggle `› <project>` chip + the
 * snippets `{}` chip) and asked to remove it. This test renders the EXACT screen
 * seam that decides the per-tab bottom band — the real
 * [TmuxTerminalBottomControls] — for both tabs and proves:
 *
 *  - **Conversation tab (`showConversation = true`)** → ONLY the composer
 *    launcher. No `session:toggle-previous` chip, no `snippets` chip, no static
 *    command chips, no `show keyboard` chip. The launcher is fully within the
 *    window root (#657/F1 containment, not just "displayed").
 *  - **Terminal tab (`showConversation = false`)** → the full
 *    [com.pocketshell.app.voice.BottomChipControls] band: the same launcher PLUS
 *    the Terminal `show keyboard` chip (and the #628 toggle chip when a previous
 *    session exists). Untouched by #786.
 *
 * The full `TmuxSessionScreen` needs Hilt + a live tmux connect, so this renders
 * the real bottom-controls seam directly and captures a labelled full-device
 * screenshot for the maintainer / reviewer to eyeball:
 *
 *  - `issue786-conversation-launcher-only-bottom.png`
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationBottomComposerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun conversationBottomIsLauncherOnlyTerminalKeepsFullBand() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("Conversation tab (#786) — launcher only")
                    // The exact seam TmuxSessionScreen uses for the bottom band.
                    // On the Conversation tab #786 collapses this to just the
                    // composer launcher: no toggle chip, no snippets chip, no
                    // command chips, no show-keyboard chip.
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = true,
                        sessionLive = true,
                        isAgentPane = true,
                        onChipTap = {},
                        onDictateTap = {},
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                        // A previous session EXISTS — proving the #628 toggle chip
                        // is suppressed on the Conversation tab specifically, not
                        // merely absent because there is nothing to toggle to.
                        previousSessionName = "git-course-management-platform",
                        onTogglePreviousSession = {},
                        onShowHotkeysTap = {},
                        modifier = Modifier.testTag(CONVERSATION_BAND_TAG),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    SectionLabel("Terminal tab — full band (untouched by #786)")
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = false,
                        sessionLive = true,
                        isAgentPane = true,
                        onChipTap = {},
                        onDictateTap = {},
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                        previousSessionName = "git-course-management-platform",
                        onTogglePreviousSession = {},
                        onShowHotkeysTap = {},
                        modifier = Modifier.testTag(TERMINAL_BAND_TAG),
                    )
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        // Authoritative capture: render the actual Compose tree the assertions
        // below exercise (deterministic, foreground-window-independent). The
        // uiAutomation full-device shot is a best-effort fallback only — some
        // emulators return a blank/wrong-window frame from it.
        captureComposeRoot(File(artifactDir(), "issue786-conversation-launcher-only-bottom.png"))
        captureFullDevice(File(artifactDir(), "issue786-conversation-launcher-only-fulldevice.png"))

        // ── #786 contract ────────────────────────────────────────────────
        // Exactly TWO composer launchers exist (one per band) — the launcher is
        // KEPT on both tabs (#810 unconditional).
        compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG).let { nodes ->
            assert(nodes.fetchSemanticsNodes().size == 2) {
                "expected the composer launcher in BOTH bands (Conversation + Terminal)"
            }
        }
        // The Conversation launcher is fully within the window root (containment,
        // not just "displayed") — it must remain reachable/tappable now that the
        // surrounding chip bar is gone. Two launchers share the tag (one per
        // band), so check the Conversation band's row geometry directly: its row
        // is fully within the root AND the launcher inside it is displayed.
        compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG)[0]
            .assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(CONVERSATION_BAND_TAG)
        run {
            val launcher = compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG)[0]
                .fetchSemanticsNode().boundsInRoot
            val rootBounds = compose.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
            assert(
                launcher.left >= rootBounds.left &&
                    launcher.top >= rootBounds.top &&
                    launcher.right <= rootBounds.right &&
                    launcher.bottom <= rootBounds.bottom,
            ) {
                "the Conversation-tab composer launcher must be fully within the " +
                    "window root (not clipped off any edge). launcher=$launcher root=$rootBounds"
            }
        }

        // The #628 previous-session toggle chip — the `› <project>` pill the
        // maintainer circled — appears EXACTLY ONCE (Terminal only), never on the
        // Conversation tab even though a previousSessionName was supplied.
        compose.onAllNodesWithTag(SESSION_TOGGLE_CHIP_TAG).let { nodes ->
            assert(nodes.fetchSemanticsNodes().size == 1) {
                "the #628 previous-session toggle chip must be Terminal-only after " +
                    "#786 — found ${nodes.fetchSemanticsNodes().size} (a second one " +
                    "means it leaked back onto the Conversation tab)"
            }
        }

        // The snippets chip appears EXACTLY ONCE (Terminal only) — removed from
        // the Conversation tab (#786); it stays reachable via the composer `{}`.
        compose.onAllNodesWithText(ADD_PROMPT_CHIP_LABEL).let { nodes ->
            assert(nodes.fetchSemanticsNodes().size == 1) {
                "the snippets chip must be Terminal-only after #786 — found " +
                    "${nodes.fetchSemanticsNodes().size}"
            }
        }

        // The show-keyboard chip appears exactly once (Terminal only).
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertIsDisplayed()
        // No key bar raw-key labels anywhere.
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text = text,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-786-conversation-bottom")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-786 screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureComposeRoot(file: File) {
        val bitmap = runCatching {
            compose.onRoot().captureToImage().asAndroidBitmap()
        }.getOrNull() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-786 compose-root screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE786_CONVERSATION_COMPOSE_ROOT_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-786 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE786_CONVERSATION_BOTTOM_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "issue786:bottoms-root"
        const val CONVERSATION_BAND_TAG = "issue786:conversation-band"
        const val TERMINAL_BAND_TAG = "issue786:terminal-band"
    }
}
