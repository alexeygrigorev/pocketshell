package com.pocketshell.app.session

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator validation hooks for issues #63 and #65. These render the same
 * key-bar dictation surface used by [SessionScreen] with mocked FSM states
 * so connected tests can assert the recording waveform and transcribing
 * spinner without depending on a real microphone or Whisper latency.
 */
@RunWith(AndroidJUnit4::class)
class InlineDictationUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingStateShowsAmplitudeWaveformOnInlineMicSlot() {
        renderKeyBar(
            micState = InlineDictationViewModel.RecordingState.Recording,
            micAmplitude = 0.74f,
        )

        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .assert(hasContentDescription("Inline dictation recording waveform"))
        compose.onNodeWithTag(INLINE_DICTATION_WAVEFORM_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun transcribingStateShowsSpinnerAndBlocksDuplicateTap() {
        var micTaps = 0
        renderKeyBar(
            micState = InlineDictationViewModel.RecordingState.Transcribing,
            micAmplitude = 0f,
            onMicTap = { micTaps++ },
        )

        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .assert(hasContentDescription("Inline dictation transcribing"))
            .assert(hasNoClickAction())
        compose.onNodeWithTag(INLINE_DICTATION_TRANSCRIBING_TAG)
            .assertExists()
        assertEquals(0, micTaps)
    }

    private fun renderKeyBar(
        micState: InlineDictationViewModel.RecordingState,
        micAmplitude: Float,
        onMicTap: () -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                KeyBarWithMic(
                    keys = testKeys,
                    onKey = {},
                    micState = micState,
                    micAmplitude = micAmplitude,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = onMicTap,
                )
            }
        }
    }

    private companion object {
        val testKeys: List<KeyBinding> = listOf(
            KeyBinding(label = "Esc", kind = KeyKind.Regular),
            KeyBinding(label = "Tab", kind = KeyKind.Regular),
            KeyBinding(label = "Ctrl", kind = KeyKind.Modifier),
            KeyBinding(label = "Alt", kind = KeyKind.Modifier),
            KeyBinding(label = "<", kind = KeyKind.Arrow),
            KeyBinding(label = "v", kind = KeyKind.Arrow),
            KeyBinding(label = "^", kind = KeyKind.Arrow),
            KeyBinding(label = ">", kind = KeyKind.Arrow),
        )
    }
}
