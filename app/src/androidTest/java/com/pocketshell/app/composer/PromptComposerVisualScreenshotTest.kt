package com.pocketshell.app.composer

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptComposerVisualScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturesAllFourComposerStates() {
        // Issue #453: capture the four redesigned composer states matching
        // the maintainer's `issue-453-composer-states-mockup.png`:
        //  1. Idle — empty input "Compose prompt…", 📎/{} + mic + Send.
        //  2. Recording — waveform + mm:ss timer + Stop; Auto-send + red stop.
        //  3. Transcribing — "Transcribing…" spinner + Cancel + Auto-send.
        //  4. Text-inserted — transcript fills the editable input + Send.
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "",
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
            ),
        )
        renderComposer { state }

        // Warm-up: force one real recompose + draw cycle before the first
        // PixelCopy. The very first frame after `setContent` can still be
        // blank when the capture races it; nudging the state and back lands
        // the Idle capture on a fully-drawn frame (the same mechanism that
        // makes the later `runOnIdle`-driven captures reliable).
        compose.onNodeWithText("Compose prompt…").assertExists()
        compose.runOnIdle { state = state.copy(draft = " ") }
        compose.waitForIdle()
        compose.runOnIdle { state = state.copy(draft = "") }
        compose.onNodeWithText("Compose prompt…").assertExists()
        compose.waitForIdle()
        // State 1: Idle. Filename kept as `05b-composer-idle-draft` so the
        // walkthrough capture scripts
        // (`scripts/capture-walkthrough-screenshots.sh`,
        // `scripts/phone-walkthrough.sh`) keep resolving their expected
        // composer screenshot.
        WalkthroughScreenshotArtifacts.capture("05b-composer-idle-draft")

        // State 2: Recording — waveform + timer + Stop. No status text.
        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0.8f,
                hasDetectedSpeech = true,
                recordingElapsedMs = 17_000L,
            )
        }
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        compose.onNodeWithText("00:17").assertExists()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithTag(COMPOSER_AUTO_SEND_TAG).assertExists()
        // Declutter: no LISTENING/CAPTURING text.
        compose.onNodeWithText("CAPTURING").assertDoesNotExist()
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("06-composer-recording")

        // State 3: Transcribing — spinner + "Transcribing…" + Cancel.
        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
                hasDetectedSpeech = false,
            )
        }
        compose.onNodeWithText("Transcribing…").assertExists()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithText("TRANSCRIBING").assertDoesNotExist()
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("07-composer-transcribing")

        // State 4: Text inserted — transcript fills the editable input.
        compose.runOnIdle {
            state = PromptComposerViewModel.UiState(
                draft = "check the deploy log and tell me what failed in the last run",
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
            )
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed()
        compose.waitForIdle()
        WalkthroughScreenshotArtifacts.capture("08-composer-text-inserted")
    }

    private fun renderComposer(state: () -> PromptComposerViewModel.UiState) {
        compose.activityRule.scenario.onActivity { activity ->
            val dark = PocketShellColors.Background.toArgb()
            activity.window.decorView.setBackgroundColor(dark)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = dark
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Surface)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    SheetContent(
                        state = state(),
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = {},
                        onSend = {},
                    )
                }
            }
        }
    }
}
