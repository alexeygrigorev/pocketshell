package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.app.voice.HOTKEYS_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #813 — the prompt-composer LAUNCHER must RESERVE its width first and is
 * NEVER the element that overflows the bottom bar on a NARROW / LARGE-SYSTEM-FONT
 * device. The maintainer's 2026-06-18 07:53 screenshot shows the 4-chip primary
 * cluster (`Enter | show keyboard | hotkeys | snippets`) pushing the launcher off
 * the right edge of the bottom bar (the `snippets`-wraps-to-two-lines overflow
 * tell). This is DISTINCT from #810 (launcher dropped from the tree) — here the
 * launcher is in the tree but clipped off-screen.
 *
 * This proof renders the PRODUCTION [TmuxTerminalBottomControls] exactly as
 * [TmuxSessionScreen] wires it on a Terminal-tab shell pane (keyboard down, all
 * primary chips + the hotkeys chip present), pinned to a NARROW logical width
 * (360dp) AND a LARGE font scale (1.5×) — the maintainer's reported state. It
 * then HARD-asserts:
 *
 *  1. The composer launcher lies FULLY within the bottom-bar band (containment
 *     per #657/F1, NOT `assertIsDisplayed()` which a half-off-screen control
 *     passes). On origin/main this FAILS — the cluster reserves its (now wider,
 *     because of the large font) natural width ahead of the launcher, so the
 *     launcher (pinned last, unweighted) is clipped off the right edge.
 *  2. All four primary chips remain REACHABLE — present in the tree, never
 *     silently dropped (the cluster yields by scrolling, it does not collapse a
 *     chip). When tight, a chip may sit beyond the visible viewport but is still
 *     present + scrollable; the launcher staying on-screen is the load-bearing
 *     property.
 *
 * No `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing assertion:
 * the narrow width + large font are injected synthetically via a pinned
 * `Box.width(...)` and a [LocalDensity] override, so the clip state is produced
 * deterministically on EVERY emulator (CI swiftshader included), not only on a
 * physically narrow device. Component-level (no Docker, no Hilt) so it runs in
 * the regular emulator CI job and guards the invariant at PR time.
 */
@RunWith(AndroidJUnit4::class)
class TmuxComposerLauncherNarrowFontClipProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Render the production bottom controls the way [TmuxSessionScreen] wires them
     * on a Terminal-tab shell pane (keyboard down, all primary chips present),
     * pinned to [widthDp] AND scaled to [fontScale] — the #813 narrow / large-font
     * reported state.
     */
    private fun renderBottomControls(widthDp: Int, fontScale: Float) {
        compose.setContent {
            val base = LocalDensity.current
            // Synthetic large system font (the maintainer's larger-than-default
            // font). Real on-device large-font users bump exactly this fontScale;
            // injecting it here produces the wide-chip-cluster state on any AVD.
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
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
                                // Keyboard DOWN — the maintainer's exact state
                                // (the full chip row, where the launcher lives).
                                isImeVisible = false,
                                // Terminal tab (NOT conversation) — the report.
                                showConversation = false,
                                sessionLive = true,
                                // Shell pane — snippets chip present, matching the
                                // 4-chip cluster from the 07:53 shot.
                                isAgentPane = false,
                                onChipTap = {},
                                onDictateTap = {},
                                onEnterTap = {},
                                onShowKeyboardTap = {},
                                onAddSnippetTap = {},
                                onShowHotkeysTap = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Containment of the composer launcher INSIDE the pinned bottom-bar band:
     * every launcher edge must lie within the band's bounds (1px slop). This is
     * the property `assertIsDisplayed()` misses — a launcher pushed off the right
     * edge by an overflowing chip cluster still reports "displayed". This is the
     * red→green assertion: it FAILS on origin/main, passes after the #813 rework.
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
                "(#813 / #657 containment). The launcher must reserve its width " +
                "FIRST so the chip cluster yields, never the launcher. " +
                "launcher=$launcher band=$band.",
            contained,
        )
    }

    @Test
    fun launcherReservesWidthFirstOnNarrowLargeFontTerminalTab() {
        // The maintainer's reported state: Terminal tab, shell pane (4-chip
        // cluster), keyboard down, NARROW width + LARGE font. The launcher must
        // be fully within the band — this is the red→green clip proof.
        renderBottomControls(widthDp = NARROW_WIDTH_DP, fontScale = LARGE_FONT_SCALE)
        captureFullDevice(File(artifactDir(), "issue813-narrow-largefont-launcher.png"))
        assertLauncherWithinBand(
            "narrow shell terminal tab @ ${NARROW_WIDTH_DP}dp / ${LARGE_FONT_SCALE}× font",
        )
    }

    @Test
    fun allFourPrimaryChipsRemainReachableOnNarrowLargeFont() {
        // The cluster yields by SCROLLING, never by silently dropping a chip. All
        // four primary affordances must still exist in the tree (reachable via
        // scroll) even when the launcher has reserved its width first.
        renderBottomControls(widthDp = NARROW_WIDTH_DP, fontScale = LARGE_FONT_SCALE)
        listOf(
            SESSION_ENTER_CHIP_TAG,
            SHOW_KEYBOARD_CHIP_TAG,
            HOTKEYS_CHIP_TAG,
            SESSION_ADD_SNIPPET_CHIP_TAG,
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists(
                "primary chip '$tag' must remain present + reachable (#813: chips " +
                    "yield by scrolling, they are never silently dropped)",
            )
        }
        // The launcher is also still present + contained.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertExists()
        assertLauncherWithinBand("reachability case @ ${NARROW_WIDTH_DP}dp")
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-813-launcher-narrow-font-clip")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-813 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-813 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE813_LAUNCHER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BAND_TAG = "issue813:bottom-controls-band"

        // The maintainer's clip state: a narrow logical width with a larger system
        // font. 360dp is the common small-Android width; 412dp (Pixel 7a) clips at
        // the 1.5× font scale too, but 360dp guarantees the red on origin/main on
        // the wider CI AVD as well.
        const val NARROW_WIDTH_DP = 360
        const val LARGE_FONT_SCALE = 1.5f
    }
}
