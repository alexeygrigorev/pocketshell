package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #813 — pure SCREENSHOT harness producing the maintainer-facing evidence:
 * the production [TmuxTerminalBottomControls] at the maintainer's device profile
 * (Pixel 7a logical width, 412dp) with a LARGER system font (1.5×), showing the
 * composer launcher fully visible + tappable with all chips present, for BOTH
 * the keyboard-DOWN state (the full chip row) and the keyboard-UP state (the
 * compact hotkeys launcher above the IME).
 *
 * This is NOT the acceptance proof — that is the HARD-asserting
 * [TmuxComposerLauncherNarrowFontClipProofTest] (containment, red→green). This
 * harness only renders + saves PNGs (via `captureToImage()`, the reliable bitmap
 * path) into `additional_test_output/` so the orchestrator can attach the
 * full-bar screenshots to #813.
 */
@RunWith(AndroidJUnit4::class)
class TmuxComposerLauncherLargeFontScreenshotHarness {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(isImeVisible: Boolean) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = LARGE_FONT_SCALE),
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
                                .width(PIXEL_7A_WIDTH_DP.dp)
                                .testTag(BAND_TAG),
                        ) {
                            TmuxTerminalBottomControls(
                                isImeVisible = isImeVisible,
                                showConversation = false,
                                sessionLive = true,
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

    private fun save(fileName: String) {
        val bitmap = compose.onNodeWithTag(BAND_TAG).captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-813-largefont-screens")
        check(dir.exists() || dir.mkdirs()) { "Could not create #813 screenshot dir: ${dir.absolutePath}" }
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                "Could not write #813 screenshot: ${file.absolutePath}"
            }
        }
        println("ISSUE813_BAND_SCREENSHOT ${file.absolutePath}")
    }

    @Test
    fun keyboardDownBandAt412dpLargeFont() {
        // Keyboard DOWN — the full chip row where the launcher lives.
        render(isImeVisible = false)
        // Sanity: the launcher is present so the saved PNG includes it.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertExists()
        save("issue813-pixel7a-largefont-keyboard-down.png")
    }

    @Test
    fun keyboardUpBandAt412dpLargeFont() {
        // Keyboard UP on the Terminal tab — the compact hotkeys launcher above the
        // IME. (The composer launcher chip lives in the keyboard-down chip row;
        // with the keyboard up the terminal control band is the right-pinned
        // hotkeys launcher, which must also be fully on-screen.)
        render(isImeVisible = true)
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG).assertExists()
        save("issue813-pixel7a-largefont-keyboard-up.png")
    }

    private companion object {
        const val BAND_TAG = "issue813:largefont-band"
        const val PIXEL_7A_WIDTH_DP = 412
        const val LARGE_FONT_SCALE = 1.5f
    }
}
