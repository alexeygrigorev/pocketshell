package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.layout.imeKeyboardPanOffsetPx
import com.pocketshell.app.layout.rememberHostImeBottomPx
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #616 — full-device, keyboard-UP screenshot evidence (process.md
 * #641/#567/#615 strict visual gate). Captures the maintainer's exact reported
 * state — soft keyboard up in a terminal pane — for both:
 *
 *  - the BASE / buggy gate (`WindowInsets.ime`-style, which reads 0 on the
 *    maintainer's device): the terminal hotkey KeyBar is NOT shown; only the
 *    IME-hidden chip strip is — exactly the maintainer's screenshot where the
 *    shortcut keys are unreachable above the keyboard. Reproduced here by
 *    forcing `isImeVisible = false` while the real keyboard is up, which is what
 *    the unreliable `WindowInsets.ime` read produced on the device.
 *
 *  - the FIXED gate (host-window [rememberHostImeBottomPx]): the full KeyBar
 *    (Ctrl/Tab/Esc/arrows) renders directly above the keyboard, every key
 *    reachable.
 *
 * Both shots are pulled as `additional_test_output` so the reviewer / maintainer
 * can compare them side by side against the dogfood screenshot.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyBarImeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val draftTag = "ime-keybar-shot:draft"

    @Test
    fun captureKeyboardUpBaseAndFixed() {
        // `useHostWindowGate = false` reproduces the maintainer's device: the
        // keyboard is up but the OLD gate (WindowInsets.ime, which reads 0 on
        // that device) stays false, so the KeyBar collapses to the chip strip.
        // `useHostWindowGate = true` switches to the production host-window
        // reader, the shipped fix, end to end.
        val useHostWindowGate = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme {
                GateModeHarness(
                    draftTag = draftTag,
                    useHostWindowGate = useHostWindowGate.value,
                )
            }
        }
        compose.waitForIdle()
        val imeVisible = raiseKeyboard()
        // The connected run shards across AVDs; some have no working soft IME
        // (e.g. `test-2`). SKIP the keyboard-up capture there rather than
        // hard-fail — matching PromptComposerSheetImeReachabilityTest (#682).
        assumeTrue(
            "IME not available on this emulator; cannot capture issue #616 keyboard-up state",
            imeVisible,
        )

        // BASE: keyboard up, old gate false -> no KeyBar (the maintainer's
        // exact symptom: shortcut keys unreachable above the keyboard).
        compose.onNodeWithTag(TMUX_KEY_BAR_TAG).assertDoesNotExist()
        captureFullDevice(artifactFile("issue-616-keybar-base-keyboard-up.png"))

        // FIXED: flip to the host-window reader (the shipped path). With the
        // keyboard still up it reports a positive inset, so the full KeyBar
        // renders directly above the keyboard.
        compose.runOnIdle { useHostWindowGate.value = true }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithTag(TMUX_KEY_BAR_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice(artifactFile("issue-616-keybar-fixed-keyboard-up.png"))
    }

    private fun raiseKeyboard(): Boolean {
        compose.onNodeWithTag(draftTag).performClick()
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
        // Let the keyboard animation settle before the screenshot.
        SystemClock.sleep(600)
        return imeVisible
    }

    /**
     * When [useHostWindowGate] is false the gate is hard-false (reproduces the
     * device's broken `WindowInsets.ime` read while the keyboard is up); when
     * true it reads the production host-window inset ([rememberHostImeBottomPx]).
     */
    @Composable
    private fun GateModeHarness(draftTag: String, useHostWindowGate: Boolean) {
        val density = LocalDensity.current
        val hostImeBottomPx by rememberHostImeBottomPx()
        // Mirror TmuxSessionScreen: when the gate uses the host-window reader,
        // both `isImeVisible` AND the keyboard PAN are driven by the same
        // reliable inset. The BASE mode keeps the gate hard-false and applies
        // no pan — exactly the device's broken `WindowInsets.ime`-read state.
        val isImeVisible = if (useHostWindowGate) hostImeBottomPx > 0 else false
        val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
        val panOffsetPx = if (useHostWindowGate) {
            imeKeyboardPanOffsetPx(hostImeBottomPx, navBarBottomPx)
        } else {
            0
        }
        var draft by remember { mutableStateOf("") }
        var keyBarExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Mirror MainActivity's root: pad for the system bars but
                // EXCLUDE the IME inset (the terminal screens pan instead of
                // resizing). This bottom-anchors the content above the nav bar
                // exactly as production does, so the pan lands the keybar in the
                // same place on screen.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                )
                // Production PAN (#457): translate the column up by the keyboard
                // overlap so the bottom controls clear the keyboard. Driven by
                // the host-window inset, the same source as `isImeVisible`.
                .graphicsLayer { translationY = -panOffsetPx.toFloat() },
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().testTag(draftTag),
            )
            Spacer(modifier = Modifier.weight(1f))
            TmuxTerminalBottomControls(
                isImeVisible = isImeVisible,
                showConversation = false,
                sessionLive = true,
                isAgentPane = false,
                keyBarExpanded = keyBarExpanded,
                onKeyBarExpandedChange = { keyBarExpanded = it },
                onKey = {},
                onChipTap = {},
                onDictateTap = {},
                onEnterTap = {},
                onShowKeyboardTap = {},
                onAddSnippetTap = null,
            )
        }
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-616-keybar")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-616 screenshot dir: ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-616 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_616_KEYBAR_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
