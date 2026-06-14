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
import androidx.compose.ui.platform.testTag
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
import com.pocketshell.app.tmux.TMUX_KEY_BAR_TAG
import com.pocketshell.app.tmux.tmuxKeyBarLayout
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #755 (PR2, composer redesign — D22 hard-cut): the terminal hotkey
 * [KeyBar] (Esc / Ctrl / ^C / ⏎ / … / arrows) was relocated OUT of the
 * separate terminal-screen chrome (`TmuxTerminalBottomControls`), which was NOT
 * anchored to the IME inset and was therefore COMPLETELY HIDDEN by the soft
 * keyboard (the v0.4.0 dogfood regression), and INTO the
 * [PromptComposerSheet]'s inset-anchored column (its `keyBar` slot).
 *
 * This is the twin of [PromptComposerSheetImeReachabilityTest] (#615): it mounts
 * the REAL production [PromptComposerSheet] with the REAL terminal key-bar layout
 * in the slot, raises the REAL soft IME by focusing the draft, and asserts that
 * the key bar's bottom edge stays at or above the IME top — i.e. the bar is
 * fully visible above the keyboard, never occluded. A passing assertion here is
 * the structural guarantee the #755 redesign makes possible (the bar rides the
 * same inset as the rest of the composer, so the keyboard physically cannot
 * cover it). The mandatory full-device keyboard-up screenshot in the issue
 * thread is the visual acceptance; this test is the regression guard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerKeyBarImeReachabilityTest {

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
    fun keyBarStaysAboveKeyboardWhenDraftFocusedRaisesImeInRealSheet() {
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
                        // Issue #755: the real terminal key-bar layout in the
                        // composer's inset-anchored slot — exactly what the
                        // production screen passes for a terminal-tab pane.
                        keyBar = {
                            KeyBar(
                                keys = tmuxKeyBarLayout(expanded = false),
                                onKey = {},
                                modifier = Modifier.testTag(TMUX_KEY_BAR_TAG),
                            )
                        },
                    )
                }
            }
        }

        compose.waitForIdle()

        // Real user gesture: tap the draft, type, which raises the soft IME.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("printf issue755 keybar must be reachable")

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #755 keybar geometry",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        // Give the sheet's host-window-driven height cap recomposition a chance
        // to settle before reading the key bar's bounds.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        val keyBarBounds = compose.onNodeWithTag(TMUX_KEY_BAR_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val imeTop = readDecorHeightPx() - readImeBottomPx()

        // Emit the authoritative geometry so a reviewer can read it from
        // logcat/instrumentation output for the same run.
        println(
            "ISSUE755_KEYBAR_GEOMETRY keyBarTop=${keyBarBounds.top} keyBarBottom=${keyBarBounds.bottom} " +
                "sendTop=${sendBounds.top} sendBottom=${sendBounds.bottom} imeTop=$imeTop",
        )

        // Reachability: the key bar node must be on-screen (positive size, not
        // clipped to nothing) before the geometry assertions below.
        compose.onNodeWithTag(TMUX_KEY_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()

        // The relocated key bar must be FULLY above the keyboard (the #755 bug
        // was it being completely hidden by the IME).
        assertTrue(
            "Key bar must stay above the IME in the real composer sheet. " +
                "keyBarBottom=${keyBarBounds.bottom} imeTop=$imeTop",
            keyBarBounds.bottom <= imeTop + 2f,
        )
        assertTrue(
            "Key bar top must be on-screen above the IME. " +
                "keyBarTop=${keyBarBounds.top} imeTop=$imeTop",
            keyBarBounds.top in 0f..imeTop.toFloat(),
        )

        // The bar sits ABOVE the action controls row (Send), the #755 layout:
        // [key bar] then [Send / mic …].
        assertTrue(
            "Key bar must sit above the Send/controls row. " +
                "keyBarBottom=${keyBarBounds.bottom} sendTop=${sendBounds.top}",
            keyBarBounds.bottom <= sendBounds.top + 2f,
        )
        // Send still stays above the keyboard (don't regress #615/#765).
        assertTrue(
            "Send button must stay above the IME with the key bar present. " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        // Optional hold (off by default) so a reviewer/maintainer can grab a
        // crash-free full-device screenshot host-side via `adb exec-out
        // screencap` while the composer is on screen with the keyboard up.
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue755HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs > 0L) {
            println("ISSUE755_HOLD_BEGIN ms=$holdMs")
            android.os.SystemClock.sleep(holdMs)
            println("ISSUE755_HOLD_END")
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
