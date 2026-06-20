package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #755 — pure SCREENSHOT harness for the maintainer-facing evidence of the
 * REDESIGNED above-keyboard key bar (now the dedicated [TerminalHotkeysPanel] +
 * the compact keyboard-up launcher chip, #784/#787/#789).
 *
 * Two PNGs into `additional_test_output/issue-755-keybar-screens/`:
 *  - `issue755-hotkeys-panel.png` — the full redesigned panel (every key at full
 *    size, sectioned, NO `…` overflow, NO truncation): the "un-crammed" bar the
 *    issue asked for.
 *  - `issue755-keyboard-up-launcher.png` — the Terminal-tab control band with a
 *    synthetic soft IME UP, showing the compact hotkeys launcher chip sitting
 *    fully above the keyboard (the maintainer's reported "fully hidden by the
 *    keyboard" symptom, now reachable).
 *
 * This harness only renders + saves bitmaps (via `captureToImage()`, the
 * reliable Compose bitmap path). The HARD acceptance proofs are the separate
 * red→green tests: [TerminalHotkeysPanelNoTruncationTest] (no truncated key) and
 * [TmuxHotkeysLauncherImeProofTest] (launcher above the keyboard). The
 * full-device keyboard-up emulator capture over a live session is the reviewer's
 * mandatory acceptance pass (#641/#615).
 */
@RunWith(AndroidJUnit4::class)
class TerminalHotkeysPanelScreenshotHarness {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)

    @Test
    fun redesignedHotkeysPanelScreenshot() {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(PIXEL_7_WIDTH_DP.dp)
                            .testTag(PANEL_TAG),
                    ) {
                        TerminalHotkeysPanel(
                            sections = TmuxHotkeyPanelSections,
                            onKey = {},
                            onClose = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // Sanity: the redesigned panel really rendered its keys before capture.
        compose.onNodeWithText("^B").assertExists()
        save(PANEL_TAG, "issue755-hotkeys-panel.png")
    }

    @Test
    fun keyboardUpLauncherChipScreenshot() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                observedImeBottomPx.value =
                    WindowInsets.ime.getBottom(LocalDensity.current)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(PIXEL_7_WIDTH_DP.dp)
                            .testTag(BAND_TAG),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TmuxTerminalBottomControls(
                            isImeVisible = true,
                            showConversation = false,
                            sessionLive = true,
                            isAgentPane = false,
                            onChipTap = {},
                            onDictateTap = {},
                            onEnterTap = {},
                            onShowKeyboardTap = {},
                            onAddSnippetTap = {},
                            onShowHotkeysTap = {},
                            modifier = Modifier.imePadding(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        applySyntheticIme((IME_HEIGHT_DP * density()).toInt())
        compose.waitForIdle()
        check(observedImeBottomPx.value > 0) {
            "Synthetic IME inset did not reach Compose (observed=${observedImeBottomPx.value})"
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG).assertExists()
        save(BAND_TAG, "issue755-keyboard-up-launcher.png")
    }

    private fun applySyntheticIme(imeBottomPx: Int) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun density(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private fun save(tag: String, fileName: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-755-keybar-screens")
        check(dir.exists() || dir.mkdirs()) { "Could not create #755 screenshot dir: ${dir.absolutePath}" }
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                "Could not write #755 screenshot: ${file.absolutePath}"
            }
        }
        println("ISSUE755_KEYBAR_SCREENSHOT ${file.absolutePath}")
    }

    private companion object {
        const val PANEL_TAG = "issue755:panel-screenshot"
        const val BAND_TAG = "issue755:keyboard-up-band"
        const val PIXEL_7_WIDTH_DP = 412
        const val IME_HEIGHT_DP = 300f
    }
}
