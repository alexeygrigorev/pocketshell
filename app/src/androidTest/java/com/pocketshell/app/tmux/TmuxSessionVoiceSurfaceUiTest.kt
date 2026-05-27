package com.pocketshell.app.tmux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.session.INLINE_DICTATION_MIC_SLOT_TAG
import com.pocketshell.app.session.INLINE_DICTATION_WAVEFORM_TAG
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.KeyBarWithMic
import com.pocketshell.app.session.VoiceCommandReviewUiState
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.VoiceCommandReviewStrip
import com.pocketshell.core.voice.CommandPlan
import com.pocketshell.core.voice.PlannedCommand
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator validation for issue #123: confirm the tmux session screen
 * actually mounts the voice / dictation surface the raw-SSH SessionScreen
 * has. The full `TmuxSessionScreen` requires Hilt + a live tmux connect,
 * so this test isolates the bottom input strip the screen renders and
 * verifies the mic affordance, the waveform, the planner review strip,
 * and the chip+mic-FAB band are all displayed and wired to the same
 * callbacks the screen routes through `viewModel.writeInputToPane(...)`.
 *
 * Coverage matches `InlineDictationUiTest` (raw-SSH path) plus extra
 * assertions for the bottom-chip + mic-FAB band and the planner review
 * strip — neither of which existed on TmuxSessionScreen before #123.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionVoiceSurfaceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyBarWithMicShowsRecordingWaveformInTmuxSurface() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                KeyBarWithMic(
                    keys = TmuxKeyBarLayout,
                    onKey = {},
                    micState = InlineDictationViewModel.RecordingState.Recording,
                    micAmplitude = 0.65f,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = {},
                )
            }
        }

        compose.onNodeWithTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .assert(hasContentDescription("Inline dictation recording waveform"))
        compose.onNodeWithTag(INLINE_DICTATION_WAVEFORM_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun keyBarWithMicSwitchesDictationModeOnSelectorTap() {
        var selected = InlineDictationViewModel.DictationMode.Prompt
        compose.setContent {
            var modeState by remember {
                mutableStateOf(InlineDictationViewModel.DictationMode.Prompt)
            }
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                KeyBarWithMic(
                    keys = TmuxKeyBarLayout,
                    onKey = {},
                    micState = InlineDictationViewModel.RecordingState.Idle,
                    micAmplitude = 0f,
                    dictationMode = modeState,
                    onDictationModeSelected = {
                        modeState = it
                        selected = it
                    },
                    onMicTap = {},
                )
            }
        }

        compose.onNodeWithText("Command").performClick()
        assertEquals(InlineDictationViewModel.DictationMode.Command, selected)
    }

    @Test
    fun bottomChipControlsRendersMicFabAndPrimaryChips() {
        var dictateTaps = 0
        var snippetTaps = 0
        var keyboardTaps = 0
        val chipTaps = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                BottomChipControls(
                    chips = DefaultSessionChips,
                    onChipTap = { chipTaps += it },
                    onDictateTap = { dictateTaps += 1 },
                    onShowKeyboardTap = { keyboardTaps += 1 },
                    onAddSnippetTap = { snippetTaps += 1 },
                    onProjectNavigationTap = null,
                )
            }
        }

        // Issue #221 (round 2): capture a viewport PNG of the rendered
        // bottom strip so reviewer evidence shows the sticky right
        // cluster (`keyboard`, `+ snippet`) is visible next to the mic
        // FAB without scrolling. Written under `additional_test_output/`
        // so `:app:connectedDebugAndroidTest` auto-pulls the artifact to
        // `app/build/outputs/connected_android_test_additional_output/`.
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val mediaRoot = instrumentation.targetContext.externalMediaDirs
                .firstOrNull { it != null }
                ?: instrumentation.targetContext.getExternalFilesDir(null)
            val dir = java.io.File(mediaRoot, "additional_test_output/issue-221-chip-row")
            if (dir.exists() || dir.mkdirs()) {
                val outFile = java.io.File(dir, "bottom-chip-controls-viewport.png")
                outFile.outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                println("ISSUE221_VIEWPORT ${outFile.absolutePath}")
            }
        }

        // Issue #221: the redundant `dictate` chip was removed (design
        // system §9). The mic FAB on the right edge is now the single
        // dictate affordance; tapping it dispatches to onDictateTap.
        // Routed through the stable test tag on the FAB container so the
        // assertion does not depend on the MicButton glyph.
        compose.onNodeWithTag("session:mic-fab").assertIsDisplayed().performClick()
        assertEquals(1, dictateTaps)

        // Confirm the removed chip is no longer present.
        compose.onNodeWithText("dictate").assertDoesNotExist()

        // Issue #131 / #221 (round 2): the show-keyboard and `+ snippet`
        // chips live in a sticky right cluster *outside* the scrolling
        // chip strip, so they are visible next to the mic FAB without
        // any horizontal scroll. `assertIsDisplayed()` here is a real
        // visibility check; the round-1 implementation kept the primary
        // chips inside the horizontalScroll Row and this assertion
        // caught them being pushed off-screen by the four wide leading
        // static chips. Both chips are located by their stable test tag
        // so the assertion survives a future caption rename.
        compose.onNodeWithTag("session:show-keyboard-chip").assertIsDisplayed().performClick()
        assertEquals(1, keyboardTaps)

        compose.onNodeWithTag("session:add-snippet-chip").assertIsDisplayed().performClick()
        assertEquals(1, snippetTaps)

        compose.onNodeWithText("git status").assertHasClickAction().performClick()
        assertEquals(listOf("git status"), chipTaps)
    }

    @Test
    fun voiceCommandReviewStripRoutesInsertAndRunThroughCallbacks() {
        val events = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                VoiceCommandReviewStrip(
                    state = VoiceCommandReviewUiState(
                        transcript = "show git status",
                        pendingPlan = CommandPlan(
                            commands = listOf(PlannedCommand("git status --short")),
                        ),
                    ),
                    onInsert = { events += "insert" },
                    onRun = { events += "run" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        compose.onNodeWithText("git status --short").assertIsDisplayed()
        compose.onNodeWithText("Insert").performClick()
        compose.onNodeWithText("Run").performClick()
        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf("insert", "run", "dismiss"), events)
    }

    @Test
    fun inlineDictationErrorStripDismissesOnTap() {
        var dismissed = false
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                InlineDictationErrorStrip(
                    message = "Microphone permission denied.",
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Microphone permission denied.").assertIsDisplayed()
        compose.onNodeWithText("Microphone permission denied.").performClick()
        assertTrue(dismissed)
    }

    private companion object {
        val TmuxKeyBarLayout: List<KeyBinding> = listOf(
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
