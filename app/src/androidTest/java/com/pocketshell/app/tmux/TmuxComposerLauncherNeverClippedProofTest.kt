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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #810 (epic #809) — the prompt composer LAUNCHER must be PRESENT and fully
 * within the bottom-control band whenever those controls render, on the Terminal
 * tab, keyboard down, on BOTH a shell and an agent pane (the maintainer's
 * 2026-06-18 smoking-gun shot is a Terminal-tab agent session with the launcher
 * absent).
 *
 * This renders the PRODUCTION [TmuxTerminalBottomControls] exactly as
 * [TmuxSessionScreen] wires it on a Terminal-tab pane (keyboard down, all primary
 * chips present), pinned to a deterministic Pixel-7 logical width, and asserts the
 * launcher edges lie INSIDE the band — containment per #657/F1, NOT a bare
 * `assertIsDisplayed()` (which a half-off-screen control still passes).
 *
 * Scope note: this is the COMPONENT-level guard for the launcher's presence in the
 * bottom controls. The structural fix that makes the bottom controls (and thus the
 * launcher) ALWAYS render — independent of surface-pane / detection / tab / switch
 * state — lives in [TmuxSessionScreen] (the `surfacePane?.let { }` wrapper that
 * could drop them is DELETED), and is proved end-to-end by
 * `ComposerAlwaysPresentSwitchJourneyE2eTest` (the multi-session switch journey)
 * and `TmuxSessionScreenTest` (the JVM red→green of the presence gate). This guard
 * complements those by pinning the band's own geometry.
 *
 * Deterministic component-level proof (no Docker, no Hilt), so it runs in the
 * regular emulator CI job and guards the invariant at PR time.
 */
@RunWith(AndroidJUnit4::class)
class TmuxComposerLauncherNeverClippedProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Render the production bottom controls the way [TmuxSessionScreen] wires them
     * on a Terminal-tab pane, keyboard down, all primary chips present, pinned to a
     * fixed Pixel-7 width — the maintainer's reported state.
     *
     * [isAgentPane] toggles the chip SET (shell quick-run chips + snippets vs agent
     * exit chips) so the launcher's presence is proved on BOTH a plain shell and an
     * agent pane.
     */
    private fun renderBottomControls(isAgentPane: Boolean, widthDp: Int) {
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
                            // Keyboard DOWN — the maintainer's exact reported state
                            // (the full chip row, where the launcher lives).
                            isImeVisible = false,
                            // Terminal tab (NOT conversation) — the reported shot.
                            showConversation = false,
                            sessionLive = true,
                            isAgentPane = isAgentPane,
                            onChipTap = {},
                            // The composer launcher: always wired non-null by the
                            // screen (issue #810 — unconditional presence).
                            onDictateTap = {},
                            onEnterTap = {},
                            onShowKeyboardTap = {},
                            // snippets chip present on a shell pane (host persisted,
                            // no sticky agent) — matches the reported screenshot.
                            onAddSnippetTap = if (isAgentPane) null else ({}),
                            onShowHotkeysTap = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Containment of the composer launcher INSIDE the pinned bottom-bar band:
     * every launcher edge must lie within the band's bounds (1px slop). This is
     * the property `assertIsDisplayed()` misses — a control pushed off the right
     * edge by an overflowing chip cluster still reports "displayed".
     */
    private fun assertLauncherWithinBand(label: String) {
        val launcher = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .fetchSemanticsNode().boundsInRoot
        val band = compose.onNodeWithTag(BAND_TAG)
            .fetchSemanticsNode().boundsInRoot
        val slop = 1f
        val contained = launcher.left >= band.left - slop &&
            launcher.top >= band.top - slop &&
            launcher.right <= band.right + slop &&
            launcher.bottom <= band.bottom + slop
        assertTrue(
            "Composer launcher is not fully within the bottom-bar band for $label " +
                "(#810 / #657 containment). launcher=$launcher band=$band.",
            contained,
        )
    }

    @Test
    fun composerLauncherPresentAndContainedOnShellTerminalTab() {
        // The maintainer's reported state: Terminal tab, shell pane (snippets
        // present), keyboard down, Pixel-7 width. The launcher must be present and
        // fully within the band.
        renderBottomControls(isAgentPane = false, widthDp = PIXEL_7_WIDTH_DP)
        assertLauncherWithinBand("shell terminal tab @ ${PIXEL_7_WIDTH_DP}dp")
        captureFullDevice(File(artifactDir(), "issue810-shell-terminal-launcher-present.png"))
    }

    @Test
    fun composerLauncherPresentAndContainedOnAgentTerminalTab() {
        // Agent pane (agent exit chips + the hotkeys chip), Terminal tab — the
        // agent chrome from the smoking-gun shot. The launcher must be present and
        // contained here too.
        renderBottomControls(isAgentPane = true, widthDp = PIXEL_7_WIDTH_DP)
        assertLauncherWithinBand("agent terminal tab @ ${PIXEL_7_WIDTH_DP}dp")
        captureFullDevice(File(artifactDir(), "issue810-agent-terminal-launcher-present.png"))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-810-launcher-never-clipped")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-810 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-810 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE810_LAUNCHER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BAND_TAG = "issue810:bottom-controls-band"
        const val PIXEL_7_WIDTH_DP = 412
    }
}
