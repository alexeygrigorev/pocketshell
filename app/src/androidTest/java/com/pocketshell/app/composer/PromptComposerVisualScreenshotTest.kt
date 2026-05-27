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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.DogfoodScreenshotArtifacts
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
    fun capturesRecordingAndTranscribingStates() {
        // Issue #195: the visual `Recording` state now splits into two
        // sub-states (pre-speech "LISTENING" + idle waveform, then
        // post-speech "CAPTURING" + active waveform). Capture both so
        // the dogfood screenshot strip reflects what the user sees end
        // to end during a single dictation.
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "check the deploy log and tell me what failed",
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
            ),
        )
        renderComposer { state }

        // Pre-speech sub-state: mic is open, no amplitude has crossed
        // the threshold yet — waveform stays in its idle rest pose.
        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0f,
                hasDetectedSpeech = false,
            )
        }
        compose.onNodeWithText("LISTENING").assertExists()
        compose.waitForIdle()
        // New for issue #195: dedicated screenshot of the pre-speech
        // sub-state. The historic `06-composer-recording.png` is kept
        // below for the active-speech (capturing) view so the existing
        // dogfood-visual-pass scripts and docs still resolve their
        // expected filenames.
        DogfoodScreenshotArtifacts.capture("06b-composer-listening")

        // Active-speech sub-state: the sampler loop saw at least one
        // amplitude sample over `SILENCE_AMPLITUDE_THRESHOLD`; the
        // label flips to "CAPTURING" and the waveform animates by the
        // live amplitude. This is the screen the user sees while
        // actually dictating.
        compose.runOnIdle {
            state = state.copy(
                amplitude = 0.8f,
                hasDetectedSpeech = true,
            )
        }
        compose.onNodeWithText("CAPTURING").assertExists()
        compose.waitForIdle()
        // `06-composer-recording.png` historically captured what the user
        // sees while actively dictating — that's the "capturing" sub-state
        // post issue #195. The scripts/dogfood-visual-pass list refers
        // to this exact filename so we keep it stable; the new
        // pre-speech screenshot lives under `06b-composer-listening.png`
        // above.
        DogfoodScreenshotArtifacts.capture("06-composer-recording")

        compose.runOnIdle {
            state = state.copy(
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
                hasDetectedSpeech = false,
            )
        }
        compose.onNodeWithText("TRANSCRIBING").assertExists()
        compose.waitForIdle()
        DogfoodScreenshotArtifacts.capture("07-composer-transcribing")
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
