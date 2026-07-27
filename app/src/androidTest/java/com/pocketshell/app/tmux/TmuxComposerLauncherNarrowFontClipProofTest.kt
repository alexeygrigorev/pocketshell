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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.app.voice.TERMINAL_HOTKEYS_ACCESSIBILITY_LABEL
import com.pocketshell.app.voice.HOTKEYS_CHIP_TAG
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
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

    private val clickedPrimaryControls = mutableListOf<String>()

    /**
     * Render the production bottom controls the way [TmuxSessionScreen] wires them
     * on a Terminal-tab shell pane (keyboard down, all primary chips present),
     * pinned to [widthDp] AND scaled to [fontScale] — the #813 narrow / large-font
     * reported state.
     */
    private fun renderBottomControls(widthDp: Int, fontScale: Float) {
        clickedPrimaryControls.clear()
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
                                // Terminal tab (NOT conversation) — the report.
                                showConversation = false,
                                sessionLive = true,
                                // Shell pane — snippets chip present, matching the
                                // 4-chip cluster from the 07:53 shot.
                                isAgentPane = false,
                                onChipTap = {},
                                onDictateTap = {},
                                onEnterTap = { clickedPrimaryControls += SESSION_ENTER_CHIP_TAG },
                                onShowKeyboardTap = {
                                    clickedPrimaryControls += SHOW_KEYBOARD_CHIP_TAG
                                },
                                onAddSnippetTap = {
                                    clickedPrimaryControls += SESSION_ADD_SNIPPET_CHIP_TAG
                                },
                                onShowHotkeysTap = {
                                    clickedPrimaryControls += HOTKEYS_CHIP_TAG
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun renderRawSshBottomControls(widthDp: Int, fontScale: Float) {
        clickedPrimaryControls.clear()
        compose.setContent {
            val base = LocalDensity.current
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
                            BottomChipControls(
                                chips = emptyList(),
                                onChipTap = {},
                                onDictateTap = {
                                    clickedPrimaryControls += SESSION_COMPOSER_LAUNCHER_TAG
                                },
                                onEnterTap = {
                                    clickedPrimaryControls += SESSION_ENTER_CHIP_TAG
                                },
                                onShowKeyboardTap = {
                                    clickedPrimaryControls += SHOW_KEYBOARD_CHIP_TAG
                                },
                                onAddSnippetTap = {
                                    clickedPrimaryControls += SESSION_ADD_SNIPPET_CHIP_TAG
                                },
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
        // The one-line cluster yields by SCROLLING, never by silently dropping or
        // clipping an unreachable chip. Bring every control into view, prove its
        // complete bounds, then invoke its real callback.
        renderBottomControls(widthDp = NARROW_WIDTH_DP, fontScale = LARGE_FONT_SCALE)
        val tags = listOf(
            SESSION_ENTER_CHIP_TAG,
            SHOW_KEYBOARD_CHIP_TAG,
            HOTKEYS_CHIP_TAG,
            SESSION_ADD_SNIPPET_CHIP_TAG,
        )
        val bandBounds = compose.onNodeWithTag(BAND_TAG).getUnclippedBoundsInRoot()
        val launcherBounds = compose.onNodeWithTag(
            SESSION_COMPOSER_LAUNCHER_TAG,
        ).getUnclippedBoundsInRoot()
        tags.forEach { tag ->
            val control = compose.onNodeWithTag(tag)
            control.assertExists(
                "primary control '$tag' must remain in the one-line scroll viewport",
            )
            control.performScrollTo()
            compose.waitForIdle()
            val bounds = control.getUnclippedBoundsInRoot()
            assertTrue(
                "bring-into-view must fully contain '$tag' clear of the pinned " +
                    "composer launcher; control=$bounds band=$bandBounds " +
                    "launcher=$launcherBounds",
                bounds.left >= bandBounds.left &&
                    bounds.top >= bandBounds.top &&
                    bounds.right <= launcherBounds.left &&
                    bounds.bottom <= bandBounds.bottom,
            )
            control.assertHasClickAction().performClick()
        }
        compose.runOnIdle { assertEquals(tags, clickedPrimaryControls) }
        // The launcher is also still present + contained.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertExists()
        assertLauncherWithinBand("reachability case @ ${NARROW_WIDTH_DP}dp")

        // #789's compact hotkeys launcher remains a real accessible 48dp button,
        // not a decorative glyph or a clipped sliver, even in this tight case.
        val hotkeys = compose.onNodeWithTag(HOTKEYS_CHIP_TAG)
        hotkeys.assertHasClickAction()
            .assertContentDescriptionEquals(TERMINAL_HOTKEYS_ACCESSIBILITY_LABEL)
        val hotkeysBounds = hotkeys.getUnclippedBoundsInRoot()
        assertTrue(
            "compact Terminal hotkeys launcher must remain fully within the " +
                "narrow large-font band; hotkeys=$hotkeysBounds band=$bandBounds",
            hotkeysBounds.left >= bandBounds.left &&
                hotkeysBounds.top >= bandBounds.top &&
                hotkeysBounds.right <= bandBounds.right &&
                hotkeysBounds.bottom <= bandBounds.bottom,
        )
    }

    @Test
    fun rawSshPrimaryControlsScrollIntoFullReachOnNarrowLargeFont() {
        renderRawSshBottomControls(
            widthDp = NARROW_WIDTH_DP,
            fontScale = LARGE_FONT_SCALE,
        )
        val tags = listOf(
            SESSION_ENTER_CHIP_TAG,
            SHOW_KEYBOARD_CHIP_TAG,
            SESSION_ADD_SNIPPET_CHIP_TAG,
        )
        val bandBounds = compose.onNodeWithTag(BAND_TAG).getUnclippedBoundsInRoot()
        val launcher = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
        val launcherBounds = launcher.getUnclippedBoundsInRoot()
        assertTrue(
            "raw SSH composer launcher must stay pinned inside the narrow band",
            launcherBounds.left >= bandBounds.left &&
                launcherBounds.right <= bandBounds.right &&
                launcherBounds.bottom <= bandBounds.bottom,
        )
        tags.forEach { tag ->
            val control = compose.onNodeWithTag(tag)
            control.performScrollTo()
            compose.waitForIdle()
            val bounds = control.getUnclippedBoundsInRoot()
            assertTrue(
                "raw SSH '$tag' must become fully contained and clear of the " +
                    "pinned launcher; control=$bounds launcher=$launcherBounds band=$bandBounds",
                bounds.left >= bandBounds.left &&
                    bounds.top >= bandBounds.top &&
                    bounds.right <= launcherBounds.left &&
                    bounds.bottom <= bandBounds.bottom,
            )
            control.assertHasClickAction().performClick()
        }
        launcher.assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(tags + SESSION_COMPOSER_LAUNCHER_TAG, clickedPrimaryControls)
        }
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
