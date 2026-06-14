package com.pocketshell.app.session

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #756 loader batch L1 — verifies that after migrating the inline
 * dictation "transcribing" affordance from a raw `CircularProgressIndicator`
 * to the canonical `LoadingIndicator.Spinner(size = SpinnerSize.Small)`, the
 * `INLINE_DICTATION_TRANSCRIBING_TAG` test tag still resolves on the new
 * indicator (the load-bearing semantic the composer cancel/send instrumentation
 * relies on to find the spinner). Also captures a full-device screenshot of the
 * transcribing slot for emulator evidence.
 */
@RunWith(AndroidJUnit4::class)
class InlineDictationTranscribingSpinnerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transcribingSpinnerKeepsTestTag() {
        compose.setContent {
            PocketShellTheme {
                KeyBarWithMic(
                    keys = listOf(
                        KeyBinding(label = "Esc", kind = KeyKind.Regular),
                        KeyBinding(label = "Tab", kind = KeyKind.Regular),
                    ),
                    onKey = {},
                    micState = InlineDictationViewModel.RecordingState.Transcribing,
                    dictationMode = InlineDictationViewModel.DictationMode.Prompt,
                    onDictationModeSelected = {},
                    onMicTap = {},
                )
            }
        }

        compose.waitForIdle()

        // The canonical LoadingIndicator.Spinner must still expose the tag the
        // composer recording instrumentation locates the spinner by.
        compose.onNodeWithTag(INLINE_DICTATION_TRANSCRIBING_TAG).assertIsDisplayed()

        captureFullDevice(File(ensureArtifactDir(), "inline-dictation-transcribing.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/inline-dictation-transcribing")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create inline-dictation-transcribing screenshot directory: ${dir.absolutePath}"
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
                    "Could not write inline-dictation-transcribing screenshot: ${file.absolutePath}"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
