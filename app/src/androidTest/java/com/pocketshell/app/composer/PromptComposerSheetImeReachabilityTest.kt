package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #615 — the maintainer's actual on-device scenario: the **real
 * production [PromptComposerSheet]** (a real `ModalBottomSheet` dialog
 * window) is open, the user taps the draft field, the **real soft IME**
 * comes up, and the primary Send action must stay visible/reachable above
 * the keyboard.
 *
 * Why a new test class instead of reusing [ComposerPartialExpandE2eTest]:
 * that harness mounts a *hand-built* `ModalBottomSheet` and raises the IME
 * with `WindowInsetsControllerCompat.show(ime())` on the activity window.
 * It passed for many rounds while the maintainer's phone still hid Send —
 * because the harness never exercised:
 *
 *   1. the real `PromptComposerSheet` composable (with its IME auto-expand
 *      effect + `WindowInsets.ime`-driven height fraction read from inside
 *      the sheet's own dialog window), and
 *   2. the IME raised by *focusing the field* (the real user gesture),
 *      whose inset has to propagate into the sheet's separate dialog window.
 *
 * This test closes both gaps: it mounts the production sheet, focuses the
 * draft to bring up the real keyboard, captures a full-device screenshot,
 * and asserts the Send button's bottom edge sits at or above the IME top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSheetImeReachabilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
    )

    @Test
    fun sendStaysAboveKeyboardWhenDraftFocusedRaisesImeInRealSheet() {
        val vm = newViewModel()
        // Match MainActivity's edge-to-edge window setup so IME inset
        // propagation into the sheet's dialog window mirrors production.
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _, _ -> true },
                        viewModel = vm,
                    )
                }
            }
        }

        compose.waitForIdle()

        // Real user gesture: tap the draft, type, which raises the soft IME.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("printf issue615 send must be reachable")

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #615 geometry",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        // Give the sheet's IME auto-expand + host-window-driven padding
        // recomposition a chance to settle before reading Send's bounds.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val imeTop = readDecorHeightPx() - readImeBottomPx()

        // Emit the authoritative geometry so the reviewer can read it from
        // logcat/instrumentation output for the same run. We deliberately do
        // NOT take a `uiAutomation.takeScreenshot()` here: on the shared AVD a
        // sibling instrumentation can leave the UiAutomation half-connected,
        // crashing this run's teardown (`Cannot call disconnect() while
        // connecting`). The bounds check below is the authoritative emulator
        // proof; the full-device keyboard-up screenshot is captured separately
        // and host-side (crash-free) for the maintainer.
        println(
            "ISSUE615_GEOMETRY sendTop=${sendBounds.top} sendBottom=${sendBounds.bottom} imeTop=$imeTop",
        )

        assertTrue(
            "Send button must stay above the IME in the real composer sheet. " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        // Reachability, not just presence: the tappable Send must be fully
        // inside the visible viewport above the keyboard.
        assertTrue(
            "Send button top must be on-screen above the IME. " +
                "sendTop=${sendBounds.top} imeTop=$imeTop",
            sendBounds.top in 0f..imeTop.toFloat(),
        )

        // Optional hold (off by default) so a reviewer/maintainer can grab a
        // crash-free full-device screenshot host-side via `adb exec-out
        // screencap` while the composer is on screen with the keyboard up.
        // Enabled only when `-Pandroid.testInstrumentationRunnerArguments.
        // issue615HoldMs=<ms>` is passed, so normal/CI runs stay fast and never
        // touch `uiAutomation.takeScreenshot()` (which crashes teardown under
        // sibling AVD contention).
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue615HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs > 0L) {
            println("ISSUE615_HOLD_BEGIN ms=$holdMs")
            android.os.SystemClock.sleep(holdMs)
            println("ISSUE615_HOLD_END")
        }
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private fun readImeBottomPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            result = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return result
    }

    private fun readDecorHeightPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            result = activity.window.decorView.height
        }
        return result
    }
}
