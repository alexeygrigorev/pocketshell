package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.voice.SESSION_COMPOSER_UNSENT_BADGE_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1531 (audit RC1) — the docked-launcher UNSENT badge must be PRESENT and
 * fully within the window whenever the current session has an undelivered
 * outbound row (a queued / deferred / uploading / failed send), so a stuck send
 * is SEEN on the session screen without opening the composer sheet. The badge is
 * the whole point of the "no silent drop" half: if it is clipped / off-screen /
 * occluded, the maintainer sees nothing and the send still looks "silently
 * dropped".
 *
 * ## Why a real-component instrumented proof (not a Roborazzi mirror)
 *
 * Round 1 proved only a pure-function badge helper + a ui-kit geometry MIRROR
 * render (the real [com.pocketshell.app.voice.ComposerLauncherButton] is
 * app-module private). Per the mandatory #641/#567 "Visual / composer" rule and
 * #657 F2/F3, a Roborazzi render is the fast-first check ONLY and is NOT
 * sufficient to close a visibility/occlusion claim. The badge is placed at
 * `Alignment.TopEnd` INSIDE the 44dp launcher Box (VoiceSessionSurface.kt ~L893)
 * and Compose does NOT clip by default, so whether a parent bottom-controls /
 * chip row clips or shoves it off-screen on the REAL docked launcher is exactly
 * the unproven risk. This renders the PRODUCTION [TmuxTerminalBottomControls]
 * exactly as [TmuxSessionScreen] wires it — with `unsentCount > 0` — and asserts
 * CONTAINMENT of [SESSION_COMPOSER_UNSENT_BADGE_TAG] via
 * [assertNodeFullyWithinRoot] (#657 / F1), NOT a bare `assertIsDisplayed()`
 * (which a half-off-screen badge still passes), on BOTH docked-launcher paths:
 *
 *  - the Terminal-tab chip cluster (`showConversation = false` → BottomChipControls)
 *  - the Conversation-tab launcher row (`showConversation = true` → ConversationComposerLauncherRow)
 *
 * and on BOTH a pending (calm Active) and a failed (Error) badge state. Captures
 * a full-device screenshot of each so the reviewer/orchestrator can confirm the
 * badge is visible and not occluded on the real docked launcher.
 *
 * Deterministic component-level proof (no Docker, no Hilt), so it runs in the
 * regular emulator CI job (wired into scripts/ci-journey-suite.sh) and guards the
 * invariant at PR time.
 */
@RunWith(AndroidJUnit4::class)
class ComposerUnsentBadgeContainmentProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Render the production bottom controls the way [TmuxSessionScreen] wires
     * them, pinned to a fixed Pixel-7 width, with an undelivered-row badge on the
     * docked launcher.
     *
     * [showConversation] toggles which docked-launcher path renders:
     *  - false → the Terminal-tab BottomChipControls launcher (chip cluster);
     *  - true  → the Conversation-tab ConversationComposerLauncherRow launcher.
     * [unsentHasFailure] toggles the Active (calm) vs Error (needs-attention) role.
     */
    private fun renderBadge(
        showConversation: Boolean,
        unsentCount: Int,
        unsentHasFailure: Boolean,
        widthDp: Int = PIXEL_7_WIDTH_DP,
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(widthDp.dp)
                            .testTag(BAND_TAG),
                    ) {
                        TmuxTerminalBottomControls(
                            isImeVisible = false,
                            showConversation = showConversation,
                            sessionLive = true,
                            isAgentPane = false,
                            onChipTap = {},
                            // The composer launcher: always wired non-null (issue
                            // #810 — unconditional presence). On the conversation
                            // path we also wire the #585 hold-swipe-up entry
                            // gesture so the REAL (merged-semantics) launcher shape
                            // is exercised, not a simplified one.
                            onDictateTap = {},
                            onDictateHoldSwipeUp = if (showConversation) ({}) else null,
                            onEnterTap = {},
                            onShowKeyboardTap = {},
                            onAddSnippetTap = {},
                            onShowHotkeysTap = {},
                            // Issue #1531 (audit RC1): the undelivered-row badge.
                            unsentCount = unsentCount,
                            unsentHasFailure = unsentHasFailure,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Containment of the unsent badge INSIDE the window root: every badge edge
     * must lie within the window (1dp slop). This is the property
     * `assertIsDisplayed()` misses — a badge pushed off the right edge (the
     * `Alignment.TopEnd`-inside-44dp-Box, no-clip risk) still reports "displayed".
     */
    private fun assertBadgePresentAndContained(label: String) {
        val present = compose.onAllNodesWithTag(SESSION_COMPOSER_UNSENT_BADGE_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertTrue(
            "the unsent badge must be PRESENT on the docked launcher for $label " +
                "(#1531 RC1 — a stuck send must be SEEN on the session screen)",
            present,
        )
        compose.assertNodeFullyWithinRoot(
            tag = SESSION_COMPOSER_UNSENT_BADGE_TAG,
            useUnmergedTree = true,
        )
    }

    @Test
    fun pendingBadgeContainedOnTerminalChipClusterLauncher() {
        // Terminal tab, shell pane, keyboard down, Pixel-7 width, a pending
        // (calm) unsent count. The badge must be present + fully within the
        // window on the chip-cluster docked launcher.
        renderBadge(showConversation = false, unsentCount = 2, unsentHasFailure = false)
        assertBadgePresentAndContained("terminal chip-cluster launcher, pending count=2")
        captureFullDevice(File(artifactDir(), "issue1531-terminal-pending-badge.png"))
    }

    @Test
    fun failedBadgeContainedOnTerminalChipClusterLauncher() {
        // The Error-role badge (a permanently-Failed row needing attention) — the
        // widest badge state; must still be contained.
        renderBadge(showConversation = false, unsentCount = 3, unsentHasFailure = true)
        assertBadgePresentAndContained("terminal chip-cluster launcher, failed count=3")
        captureFullDevice(File(artifactDir(), "issue1531-terminal-failed-badge.png"))
    }

    @Test
    fun pendingBadgeContainedOnConversationLauncherRow() {
        // Conversation tab: the docked launcher renders via
        // ConversationComposerLauncherRow (the entry-gesture launcher). The badge
        // must be present + contained here too.
        renderBadge(showConversation = true, unsentCount = 2, unsentHasFailure = false)
        assertBadgePresentAndContained("conversation launcher row, pending count=2")
        captureFullDevice(File(artifactDir(), "issue1531-conversation-pending-badge.png"))
    }

    @Test
    fun failedBadgeContainedOnConversationLauncherRow() {
        renderBadge(showConversation = true, unsentCount = 3, unsentHasFailure = true)
        assertBadgePresentAndContained("conversation launcher row, failed count=3")
        captureFullDevice(File(artifactDir(), "issue1531-conversation-failed-badge.png"))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-1531-unsent-badge")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-1531 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-1531 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE1531_BADGE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BAND_TAG = "issue1531:bottom-controls-band"
        const val PIXEL_7_WIDTH_DP = 412
    }
}
